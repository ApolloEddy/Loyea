package com.loyea.llm

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * SSE data 行载荷提取：兼容 "data: {...}"（标准，带空格）与 "data:{...}"（部分网关省略空格）。
 */
fun extractSseDataPayload(trimmedLine: String): String? {
    if (!trimmedLine.startsWith("data:")) return null
    return trimmedLine.substring(5).trim()
}

/** 非 SSE 响应体（HTTP 200 却读不到任何 data 事件）的判定结果。 */
sealed class NonSseBodyOutcome {
    /** 空响应体：连接正常但零字节 */
    object Empty : NonSseBodyOutcome()
    /** JSON error 体：message 为服务商标错原文（部分网关以 200 包错误返回） */
    data class ApiError(val message: String) : NonSseBodyOutcome()
    /** JSON 带 choices：渠道忽略 stream 参数回整包 JSON（含顶层裸字符串变体） */
    data class Completion(val response: ChatResponse) : NonSseBodyOutcome()
    /** 其余（HTML/纯文本/损坏 JSON）：附响应体采样供透出 */
    data class Unrecognized(val sample: String) : NonSseBodyOutcome()
}

/** <think> 剥离 + 内嵌 XML 工具调用的增量解析结果。 */
data class ParsedStreamState(
    val thoughts: String,
    val visibleContent: String,
    val completedXmlCalls: List<ToolCall>
)

/**
 * 响应解析器：只回答「这是什么」（Spec §12.1）；
 * 「接下来做什么」（降级/重试/学习）由 Transport 状态机决定。
 *
 * 保留近期沉淀的全部兼容能力：标准 SSE、data:{...} 无空格、标准整包、
 * 顶层裸 JSON 字符串（小马实测）、choices[].message 为字符串的网关变体、
 * content 为多模态数组的逐段拼接、finish_reason 截断识别。
 */
class ResponseParser(private val gson: Gson = Gson()) {

    /** 非流式整包解析入口（含容错与错误分类）。 */
    fun parseChatCompletionResponse(responseBody: String): ChatResponse {
        return try {
            val responseEl = gson.fromJson(responseBody, JsonElement::class.java)
            // 顶层裸 JSON 字符串（非标准渠道实测形态）：按正文文本处理
            if (responseEl != null && responseEl.isJsonPrimitive && responseEl.asJsonPrimitive.isString) {
                return ChatResponse(content = responseEl.asJsonPrimitive.asString)
            }
            val responseJson = responseEl?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return ChatResponse(
                    content = "[错误] 接口响应解析失败：响应不是 JSON 对象，响应体开头：${responseBody.take(200)}",
                    isError = true,
                    errorKind = LlmErrorKind.MalformedResponse
                )
            // 顶层 error 对象：服务商标错原文（部分网关 200 包错误）
            responseJson.getAsJsonObject("error")?.let { errObj ->
                val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: responseBody.take(300)
                return ChatResponse(
                    content = msg,
                    isError = true,
                    errorKind = ErrorClassifier.classifyStructuredError(msg)
                )
            }
            val choices = if (responseJson.has("choices") && responseJson.get("choices").isJsonArray) {
                responseJson.getAsJsonArray("choices")
            } else null
            if (choices == null || choices.size() == 0) {
                return ChatResponse(
                    content = "[错误] 未能从接口解析出有效文本选择支，服务器输出：${responseBody.take(300)}",
                    isError = true,
                    errorKind = LlmErrorKind.MalformedResponse
                )
            }

            val firstChoice = choices.get(0)
            if (!firstChoice.isJsonObject) {
                return ChatResponse(
                    content = "[错误] 未能从接口解析出有效文本选择支，服务器输出：${responseBody.take(300)}",
                    isError = true,
                    errorKind = LlmErrorKind.MalformedResponse
                )
            }
            val messageEl = firstChoice.asJsonObject.get("message")?.takeIf { !it.isJsonNull }
            // message 直接是字符串的网关变体：{"choices":[{"message":"正文"}]}
            if (messageEl != null && messageEl.isJsonPrimitive && messageEl.asJsonPrimitive.isString) {
                return ChatResponse(content = messageEl.asJsonPrimitive.asString)
            }
            val messageObj = messageEl?.takeIf { it.isJsonObject }?.asJsonObject
            val rawContent = messageObj?.get("content")?.takeIf { !it.isJsonNull }
                ?.let {
                    if (it.isJsonArray) it.asJsonArray.joinToString("") { el ->
                        el.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString ?: ""
                    } else it.asString
                }
                ?: ""
            val reasoningContent = messageObj?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
            val apiToolCalls = if (messageObj?.has("tool_calls") == true && messageObj.get("tool_calls").isJsonArray) {
                parseToolCalls(messageObj.getAsJsonArray("tool_calls"))
            } else emptyList()

            val parsedState = StreamContentParser.parseIncrementalStreamState(rawContent, isDone = true)
            val finalThoughts = if (!reasoningContent.isNullOrBlank()) reasoningContent else parsedState.thoughts.takeIf { it.isNotBlank() }
            val finalContent = parsedState.visibleContent.trim()
            val combinedToolCalls = apiToolCalls + parsedState.completedXmlCalls

            ChatResponse(
                content = finalContent,
                thoughts = finalThoughts,
                toolCalls = combinedToolCalls,
                usage = parseUsage(responseJson)
            )
        } catch (e: Exception) {
            ChatResponse(
                content = "[错误] 接口响应解析失败: ${e.localizedMessage ?: e.message}；响应体开头：${responseBody.take(120)}",
                isError = true,
                errorKind = LlmErrorKind.MalformedResponse
            )
        }
    }

    /** HTTP 200 却整条流无 data 事件的四分类判定。 */
    fun interpretNonSseBody(rawBody: String): NonSseBodyOutcome {
        if (rawBody.isEmpty()) return NonSseBodyOutcome.Empty
        // 顶层裸 JSON 字符串（非标准渠道实测）：按正文文本直接降级为可用回复
        if (rawBody.startsWith("\"")) {
            val bare = runCatching { gson.fromJson(rawBody, String::class.java) }.getOrNull()
            if (!bare.isNullOrEmpty()) return NonSseBodyOutcome.Completion(ChatResponse(content = bare))
        }
        if (!rawBody.startsWith("{") && !rawBody.startsWith("[")) {
            return NonSseBodyOutcome.Unrecognized(rawBody.take(200))
        }
        val parsed = runCatching { gson.fromJson(rawBody, JsonObject::class.java) }.getOrNull()
            ?: return NonSseBodyOutcome.Unrecognized(rawBody.take(200))
        val errObj = parsed.getAsJsonObject("error")
        if (errObj != null) {
            val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: rawBody.take(300)
            return NonSseBodyOutcome.ApiError(msg)
        }
        if (parsed.has("choices") && parsed.get("choices").isJsonArray) {
            return NonSseBodyOutcome.Completion(parseChatCompletionResponse(rawBody))
        }
        return NonSseBodyOutcome.Unrecognized(rawBody.take(200))
    }

    fun parseUsage(responseJson: JsonObject): Usage? {
        val usage = responseJson.get("usage")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject ?: return null
        return Usage(
            promptTokens = usage.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asLong,
            completionTokens = usage.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asLong,
            totalTokens = usage.get("total_tokens")?.takeIf { !it.isJsonNull }?.asLong,
            promptCacheHitTokens = usage.get("prompt_cache_hit_tokens")?.takeIf { !it.isJsonNull }?.asLong,
            promptCacheMissTokens = usage.get("prompt_cache_miss_tokens")?.takeIf { !it.isJsonNull }?.asLong
        )
    }

    private fun parseToolCalls(toolCallsArray: JsonArray?): List<ToolCall> {
        if (toolCallsArray == null || toolCallsArray.size() == 0) return emptyList()
        val calls = mutableListOf<ToolCall>()
        toolCallsArray.forEachIndexed { index, element ->
            val obj = element.asJsonObject
            val functionObj = obj.getAsJsonObject("function") ?: return@forEachIndexed
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                ?: "tool_${System.currentTimeMillis()}_$index"
            val name = functionObj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEachIndexed
            val arguments = functionObj.get("arguments")?.takeIf { !it.isJsonNull }?.let(::jsonElementToString) ?: "{}"
            calls.add(ToolCall(id = id, name = name, argumentsJson = arguments))
        }
        return calls
    }

    private fun jsonElementToString(element: JsonElement): String {
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            gson.toJson(element)
        }
    }
}

/**
 * <think> 剥离 + 内嵌 XML 工具调用（<tool_call>/<tool_invocation>）的增量解析。
 * 自 LlmClient 平移，行为保持字节级一致（含未闭合标签挂起、残缺自愈正则）。
 */
object StreamContentParser {

    fun parseIncrementalStreamState(fullStr: String, isDone: Boolean = false): ParsedStreamState {
        var thoughtsAccumulator = ""
        var visibleContentAccumulator = ""
        val completedXmlCalls = mutableListOf<ToolCall>()

        // 1. 寻找未闭合的 <tool_call> 或 <tool_invocation>。有且尚未 Done 时，截断后续正在生成的文本，挂起不发射
        val lastToolCallStart = fullStr.lastIndexOf("<tool_call>")
        val lastToolCallEnd = fullStr.lastIndexOf("</tool_call>")
        val isToolCallUnclosed = !isDone && lastToolCallStart != -1 && lastToolCallStart > lastToolCallEnd

        val lastToolInvocationStart = fullStr.lastIndexOf("<tool_invocation")
        var lastToolInvocationEnd = -1
        if (lastToolInvocationStart != -1) {
            val endIdx = fullStr.indexOf("/>", lastToolInvocationStart)
            if (endIdx != -1) {
                lastToolInvocationEnd = endIdx + 2
            }
        }
        val isToolInvocationUnclosed = !isDone && lastToolInvocationStart != -1 && lastToolInvocationEnd == -1

        var processLimit = fullStr.length
        if (isToolCallUnclosed && isToolInvocationUnclosed) {
            processLimit = minOf(lastToolCallStart, lastToolInvocationStart)
        } else if (isToolCallUnclosed) {
            processLimit = lastToolCallStart
        } else if (isToolInvocationUnclosed) {
            processLimit = lastToolInvocationStart
        }

        val safeStr = fullStr.substring(0, processLimit)

        // 2. 提取并滤除安全文本中所有已就绪的工具调用
        var cleanStr = safeStr

        // 2.1 处理 <tool_call>（自愈残缺/连续不闭合）
        val toolCallRegex = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|(?=<tool_call>|$))")
        val matches = toolCallRegex.findAll(cleanStr)
        for (match in matches) {
            val xmlBlock = match.value
            val parsedCalls = parseXmlToolCallsOnly(xmlBlock)
            completedXmlCalls.addAll(parsedCalls)
            cleanStr = cleanStr.replace(xmlBlock, "")
        }

        // 2.2 处理 <tool_invocation ... />
        val toolInvocationRegex = Regex("""<tool_invocation\s+name="([^"]+)"\s+arguments=["']?(\{[\s\S]*?\})["']?\s*/>""")
        val invocationMatches = toolInvocationRegex.findAll(cleanStr)
        for (match in invocationMatches) {
            val xmlBlock = match.value
            completedXmlCalls.add(
                ToolCall(
                    id = "xml_call_${System.currentTimeMillis()}_${(0..1000).random()}",
                    name = match.groupValues[1],
                    argumentsJson = match.groupValues[2]
                )
            )
            cleanStr = cleanStr.replace(xmlBlock, "")
        }

        // 3. 在已滤除工具调用的 cleanStr 中处理 <think> 与正文
        var cursor = 0
        var trimLeadingFromClose = false
        val len = cleanStr.length
        while (cursor < len) {
            val thinkStart = cleanStr.indexOf("<think>", cursor)
            if (thinkStart == -1) {
                val tail = cleanStr.substring(cursor)
                visibleContentAccumulator += if (trimLeadingFromClose) tail.trimStart('\n', '\r', ' ') else tail
                break
            }

            if (thinkStart > cursor) {
                val seg = cleanStr.substring(cursor, thinkStart)
                if (trimLeadingFromClose) {
                    visibleContentAccumulator += seg.trimStart('\n', '\r', ' ')
                    trimLeadingFromClose = false
                } else {
                    visibleContentAccumulator += seg
                }
            }

            val thinkEnd = cleanStr.indexOf("</think>", thinkStart)
            if (thinkEnd != -1) {
                thoughtsAccumulator += cleanStr.substring(thinkStart + 7, thinkEnd)
                cursor = thinkEnd + 8
                trimLeadingFromClose = true
            } else {
                thoughtsAccumulator += cleanStr.substring(thinkStart + 7)
                break
            }
        }

        return ParsedStreamState(
            thoughts = thoughtsAccumulator.trim(),
            visibleContent = visibleContentAccumulator,
            completedXmlCalls = completedXmlCalls
        )
    }

    private val gson = Gson()

    private fun parseXmlToolCallsOnly(xmlBlock: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()

        val blockContentRegex = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|$)")
        val match = blockContentRegex.find(xmlBlock) ?: return emptyList()
        val block = match.groupValues[1].trim()

        // 优先函数风格：name(key="value", ...)
        val funcStyleRegex = Regex("^([a-zA-Z0-9_]+)\\(([\\s\\S]*)\\)$")
        val funcMatch = funcStyleRegex.matchEntire(block)
        if (funcMatch != null) {
            val funcName = funcMatch.groupValues[1]
            val argsStr = funcMatch.groupValues[2]

            val paramRegex = Regex("([a-zA-Z0-9_]+)\\s*=\\s*['\"]([\\s\\S]*?)['\"](?=\\s*,|\\s*$)")
            val argsMap = mutableMapOf<String, Any>()
            paramRegex.findAll(argsStr).forEach { paramMatch ->
                argsMap[paramMatch.groupValues[1]] = paramMatch.groupValues[2]
            }

            if (funcName.isNotBlank()) {
                calls.add(
                    ToolCall(
                        id = "xml_call_${System.currentTimeMillis()}_${(0..1000).random()}",
                        name = funcName,
                        argumentsJson = gson.toJson(argsMap)
                    )
                )
                return calls
            }
        }

        // 兜底 XML 风格：<function=name> <parameter=val>...</parameter>
        val oldFuncRegex = Regex("<function\\s*=\\s*([^>\\s]+)>|<function\\s+name\\s*=\\s*\"([^\"]+)\">")
        val oldFuncMatch = oldFuncRegex.find(block)
        val funcName = oldFuncMatch?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?: oldFuncMatch?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
            ?: ""

        if (funcName.isNotBlank()) {
            val paramRegex = Regex("<parameter\\s*=\\s*([^>\\s]+)>([\\s\\S]*?)</parameter>|<parameter\\s+name\\s*=\\s*\"([^\"]+)\">([\\s\\S]*?)</parameter>")
            val argsMap = mutableMapOf<String, Any>()
            paramRegex.findAll(block).forEach { paramMatch ->
                val key = paramMatch.groupValues[1].takeIf { it.isNotEmpty() }
                    ?: paramMatch.groupValues[3].takeIf { it.isNotEmpty() }
                val value = paramMatch.groupValues[2].takeIf { paramMatch.groupValues[1].isNotEmpty() }
                    ?: paramMatch.groupValues[4]
                if (key != null) {
                    argsMap[key] = value.trim()
                }
            }
            calls.add(
                ToolCall(
                    id = "xml_call_${System.currentTimeMillis()}_${(0..1000).random()}",
                    name = funcName,
                    argumentsJson = gson.toJson(argsMap)
                )
            )
        }
        return calls
    }
}
