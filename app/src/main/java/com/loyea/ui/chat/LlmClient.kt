package com.loyea.ui.chat

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.loyea.mcp.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 远程 LLM 服务流式事件
 */
sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Thoughts(val text: String) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    data class ToolCalls(val calls: List<LlmToolCall>) : StreamEvent()
    object Done : StreamEvent()
}

/**
 * 远程 LLM 服务非流式响应实体
 */
data class LlmResponse(
    val content: String,
    val thoughts: String? = null,
    val isError: Boolean = false,
    val toolCalls: List<LlmToolCall> = emptyList()
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class LlmChatMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList()
)

/**
 * 大模型 API 网络通信客户端
 */
class LlmClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    /**
     * 发送 Chat Completion 流式对话请求 (SSE)
     */
    fun sendChatCompletionStream(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>,
        tools: List<McpTool> = emptyList()
    ): Flow<StreamEvent> = flow {
        if (config.apiKey.isBlank()) {
            emit(StreamEvent.Error("[错误] API Key 未配置，请在设置中配置您的 Key 后重试。"))
            return@flow
        }

        try {
            // 根据深度思考状态，对 DeepSeek 进行智能路由（仅在开启智能模型路由时生效）
            val targetModel = resolveTargetModel(config)

            val requestJson = JsonObject().apply {
                addProperty("model", targetModel)
                add("messages", toProviderMessages(messages))
                addProperty("stream", true)
                if (tools.isNotEmpty()) {
                    add("tools", toProviderTools(tools))
                    addProperty("tool_choice", "auto")
                }
                
                // 开启联网搜索 (支持常见中转和平台如 SiliconFlow, Moonshot 兼容等字段)
                if (config.enableSearch) {
                    addProperty("web_search", true)
                    addProperty("enable_search", true) // 部分平台用这个
                }
            }

            val requestBody = gson.toJson(requestJson).toRequestBody(mediaType)
            
            // 智能补全 completions 请求地址路由
            val baseUrl = resolveChatCompletionsUrl(config)

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: ""
                    val displayError = try {
                        val errJson = gson.fromJson(errorMsg, JsonObject::class.java)
                        errJson.getAsJsonObject("error")?.get("message")?.asString ?: errorMsg
                    } catch (e: Exception) {
                        errorMsg
                    }
                    emit(StreamEvent.Error("[错误] 服务器返回 HTTP 错误 ${response.code}: $displayError"))
                    return@flow
                }

                val body = response.body
                if (body == null) {
                    emit(StreamEvent.Error("[错误] 大模型接口返回了空响应"))
                    return@flow
                }

                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var line: String?
                
                // 使用增量解析器，防范分块边界截断标签
                val fullContentBuilder = StringBuilder()
                var emittedThoughtsLength = 0
                var emittedContentLength = 0
                
                class ToolCallBuffer(
                    var id: String? = null,
                    var name: String? = null,
                    val arguments: StringBuilder = StringBuilder()
                )
                val toolCallBuffers = mutableMapOf<Int, ToolCallBuffer>()

                while (reader.readLine().also { line = it } != null) {
                    val trimmedLine = line!!.trim()
                    if (trimmedLine.startsWith("data: ")) {
                        val data = trimmedLine.substring(6).trim()
                        if (data == "[DONE]") {
                            break
                        }
                        try {
                            val chunkJson = gson.fromJson(data, JsonObject::class.java)
                            val choices = chunkJson.getAsJsonArray("choices")
                            if (choices != null && choices.size() > 0) {
                                val delta = choices.get(0).asJsonObject.getAsJsonObject("delta")
                                if (delta != null) {
                                    // 1. 官方 reasoning_content 推理流 (Deepseek R1 官方标准字段)
                                    val reasoningContent = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                                    if (!reasoningContent.isNullOrEmpty()) {
                                        emit(StreamEvent.Thoughts(reasoningContent))
                                    }

                                    // 2. 正文流 (兼容内嵌式 <think> 标签并增量对比提取)
                                    val content = delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                                    if (!content.isNullOrEmpty()) {
                                        fullContentBuilder.append(content)
                                        val fullStr = fullContentBuilder.toString()
                                        
                                        val thinkStart = fullStr.indexOf("<think>")
                                        val thinkEnd = fullStr.indexOf("</think>")
                                        
                                        if (thinkStart != -1) {
                                            if (thinkEnd != -1) {
                                                // 思考段已完全结束
                                                // 提取思考块
                                                val thoughtsText = fullStr.substring(thinkStart + 7, thinkEnd)
                                                if (thoughtsText.length > emittedThoughtsLength) {
                                                    val newThoughts = thoughtsText.substring(emittedThoughtsLength)
                                                    emit(StreamEvent.Thoughts(newThoughts))
                                                    emittedThoughtsLength = thoughtsText.length
                                                }
                                                
                                                // 提取正文（去掉整个思考标签和内容）
                                                val beforeThink = fullStr.substring(0, thinkStart)
                                                val afterThink = fullStr.substring(thinkEnd + 8)
                                                val totalContent = beforeThink + afterThink
                                                if (totalContent.length > emittedContentLength) {
                                                    val newContent = totalContent.substring(emittedContentLength)
                                                    emit(StreamEvent.Content(newContent))
                                                    emittedContentLength = totalContent.length
                                                }
                                            } else {
                                                // 思考块仍在持续中
                                                // <think> 之前的部分已经属于正文
                                                val beforeThink = fullStr.substring(0, thinkStart)
                                                if (beforeThink.length > emittedContentLength) {
                                                    val newContent = beforeThink.substring(emittedContentLength)
                                                    emit(StreamEvent.Content(newContent))
                                                    emittedContentLength = beforeThink.length
                                                }
                                                // <think> 之后的是思考内容
                                                val thoughtsText = fullStr.substring(thinkStart + 7)
                                                if (thoughtsText.length > emittedThoughtsLength) {
                                                    val newThoughts = thoughtsText.substring(emittedThoughtsLength)
                                                    emit(StreamEvent.Thoughts(newThoughts))
                                                    emittedThoughtsLength = thoughtsText.length
                                                }
                                            }
                                        } else {
                                            // 全属于普通正文，无思考标签
                                            if (fullStr.length > emittedContentLength) {
                                                val newContent = fullStr.substring(emittedContentLength)
                                                emit(StreamEvent.Content(newContent))
                                                emittedContentLength = fullStr.length
                                            }
                                        }
                                    }

                                    // 3. 工具调用流
                                    val toolCallsJson = delta.getAsJsonArray("tool_calls")
                                    if (toolCallsJson != null && toolCallsJson.size() > 0) {
                                        toolCallsJson.forEach { element ->
                                            val tcObj = element.asJsonObject
                                            val index = tcObj.get("index")?.asInt ?: 0
                                            val tcId = tcObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                                            val functionObj = tcObj.getAsJsonObject("function")
                                            val funcName = functionObj?.get("name")?.takeIf { !it.isJsonNull }?.asString
                                            val funcArgs = functionObj?.get("arguments")?.takeIf { !it.isJsonNull }?.asString

                                            val buffer = toolCallBuffers.getOrPut(index) { ToolCallBuffer() }
                                            if (tcId != null) {
                                                buffer.id = tcId
                                            }
                                            if (funcName != null) {
                                                buffer.name = funcName
                                            }
                                            if (funcArgs != null) {
                                                buffer.arguments.append(funcArgs)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // 忽略解析失败的行，可能是非 JSON 报文
                        }
                    }
                }

                // 发射收集到的完整 Tool Calls
                val finalToolCalls = toolCallBuffers.entries.sortedBy { it.key }.mapNotNull { (_, buffer) ->
                    val id = buffer.id ?: "call_${System.currentTimeMillis()}"
                    val name = buffer.name ?: return@mapNotNull null
                    LlmToolCall(id = id, name = name, argumentsJson = buffer.arguments.toString())
                }
                if (finalToolCalls.isNotEmpty()) {
                    emit(StreamEvent.ToolCalls(finalToolCalls))
                }

                emit(StreamEvent.Done)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emit(StreamEvent.Error("[错误] 网络请求故障: ${e.localizedMessage ?: e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    fun sendChatCompletionStream(
        config: com.loyea.ui.settings.ApiConfig,
        systemPrompt: String?,
        history: List<Message>,
        tools: List<McpTool> = emptyList()
    ): Flow<StreamEvent> {
        val chatHistory = mutableListOf<LlmChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            chatHistory.add(LlmChatMessage(role = "system", content = systemPrompt))
        }
        chatHistory.addAll(
            history.filter { 
                it.content.isNotBlank() && !it.content.startsWith("[错误]") && !it.content.startsWith("[Error]")
            }.map { msg ->
                LlmChatMessage(
                    role = if (msg.sender == Sender.USER) "user" else "assistant",
                    content = msg.content
                )
            }
        )
        return sendChatCompletionStream(config, chatHistory, tools)
    }

    /**
     * 发送 Chat Completion 同步对话请求 (保留用于某些测试或旧调用)
     */
    suspend fun sendChatCompletion(
        config: com.loyea.ui.settings.ApiConfig,
        systemPrompt: String?,
        history: List<Message>
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext LlmResponse(
                content = "[错误] API Key 未配置，请在设置中配置您的 Key 后重试。",
                isError = true
            )
        }

        try {
            val chatHistory = mutableListOf<Map<String, String>>()
            if (!systemPrompt.isNullOrBlank()) {
                chatHistory.add(mapOf("role" to "system", "content" to systemPrompt))
            }
            chatHistory.addAll(
                history.filter { 
                    it.content.isNotBlank() && !it.content.startsWith("[错误]") && !it.content.startsWith("[Error]")
                }.map { msg ->
                    mapOf(
                        "role" to if (msg.sender == Sender.USER) "user" else "assistant",
                        "content" to msg.content
                    )
                }
            )

            // 根据深度思考状态，对 DeepSeek 进行智能路由（仅在开启智能模型路由时生效）
            var targetModel = config.modelName
            if (config.provider.equals("DeepSeek", ignoreCase = true) && config.enableSmartRouting) {
                if (config.enableReasoning) {
                    if (targetModel == "deepseek-chat") targetModel = "deepseek-reasoner"
                    else if (targetModel == "deepseek-v4-flash") targetModel = "deepseek-v4-pro"
                } else {
                    if (targetModel == "deepseek-reasoner") targetModel = "deepseek-chat"
                    else if (targetModel == "deepseek-v4-pro") targetModel = "deepseek-v4-flash"
                }
            }

            val requestJson = JsonObject().apply {
                addProperty("model", targetModel)
                add("messages", gson.toJsonTree(chatHistory))
                if (config.enableSearch) {
                    addProperty("web_search", true)
                }
            }

            val requestBody = gson.toJson(requestJson).toRequestBody(mediaType)
            
            var baseUrl = config.apiUrl.trim()
            if (!baseUrl.endsWith("/chat/completions")) {
                if (baseUrl.endsWith("/")) {
                    baseUrl += "chat/completions"
                } else {
                    baseUrl += "/chat/completions"
                }
            }

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: ""
                    val displayError = try {
                        val errJson = gson.fromJson(errorMsg, JsonObject::class.java)
                        errJson.getAsJsonObject("error")?.get("message")?.asString ?: errorMsg
                    } catch (e: Exception) {
                        errorMsg
                    }
                    return@withContext LlmResponse(
                        content = "[错误] 服务器返回 HTTP 错误 ${response.code}: $displayError",
                        isError = true
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext LlmResponse(
                        content = "[错误] 大模型接口返回了空响应",
                        isError = true
                    )
                }

                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val choices = responseJson.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) {
                    return@withContext LlmResponse(
                        content = "[错误] 未能从接口解析出有效文本选择支，服务器输出：$responseBody",
                        isError = true
                    )
                }

                val messageObj = choices.get(0).asJsonObject.getAsJsonObject("message")
                val rawContent = messageObj?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val reasoningContent = messageObj?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString

                var finalThoughts: String? = null
                var finalContent = rawContent

                if (!reasoningContent.isNullOrBlank()) {
                    finalThoughts = reasoningContent
                }

                val thinkRegex = Regex("<think>([\\s\\S]*?)</think>")
                val matchResult = thinkRegex.find(rawContent)
                if (matchResult != null) {
                    if (finalThoughts == null) {
                        finalThoughts = matchResult.groupValues[1].trim()
                    }
                    finalContent = rawContent.replace(thinkRegex, "").trim()
                }

                return@withContext LlmResponse(
                    content = finalContent,
                    thoughts = finalThoughts
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext LlmResponse(
                content = "[错误] 网络请求故障: ${e.localizedMessage ?: e.message}",
                isError = true
            )
        }
    }

    suspend fun sendChatCompletionWithTools(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>,
        tools: List<McpTool>
    ): LlmResponse = withContext(Dispatchers.IO) {
        sendRawChatCompletion(config, messages, tools, stream = false)
    }

    fun sendRawChatCompletionStream(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>
    ): Flow<StreamEvent> = flow {
        val response = sendRawChatCompletion(config, messages, emptyList(), stream = false)
        if (response.isError) {
            emit(StreamEvent.Error(response.content))
            return@flow
        }
        if (!response.thoughts.isNullOrBlank()) {
            emit(StreamEvent.Thoughts(response.thoughts))
        }
        if (response.content.isNotBlank()) {
            emit(StreamEvent.Content(response.content))
        }
        emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)

    private fun resolveTargetModel(config: com.loyea.ui.settings.ApiConfig): String {
        var targetModel = config.modelName
        if (config.provider.equals("DeepSeek", ignoreCase = true) && config.enableSmartRouting) {
            if (config.enableReasoning) {
                if (targetModel == "deepseek-chat") targetModel = "deepseek-reasoner"
                else if (targetModel == "deepseek-v4-flash") targetModel = "deepseek-v4-pro"
            } else {
                if (targetModel == "deepseek-reasoner") targetModel = "deepseek-chat"
                else if (targetModel == "deepseek-v4-pro") targetModel = "deepseek-v4-flash"
            }
        }
        return targetModel
    }

    private fun resolveChatCompletionsUrl(config: com.loyea.ui.settings.ApiConfig): String {
        var baseUrl = config.apiUrl.trim()
        if (!baseUrl.endsWith("/chat/completions")) {
            baseUrl = if (baseUrl.endsWith("/")) {
                baseUrl + "chat/completions"
            } else {
                "$baseUrl/chat/completions"
            }
        }
        return baseUrl
    }

    private fun toProviderMessages(messages: List<LlmChatMessage>): JsonArray {
        val array = JsonArray()
        messages.forEach { msg ->
            val obj = JsonObject().apply {
                addProperty("role", msg.role)
                if (msg.content != null) addProperty("content", msg.content)
                if (!msg.toolCallId.isNullOrBlank()) addProperty("tool_call_id", msg.toolCallId)
                if (!msg.name.isNullOrBlank()) addProperty("name", msg.name)
                if (msg.toolCalls.isNotEmpty()) {
                    val calls = JsonArray()
                    msg.toolCalls.forEach { call ->
                        calls.add(JsonObject().apply {
                            addProperty("id", call.id)
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", call.name)
                                addProperty("arguments", call.argumentsJson)
                            })
                        })
                    }
                    add("tool_calls", calls)
                }
            }
            array.add(obj)
        }
        return array
    }

    private fun toProviderTools(tools: List<McpTool>): JsonArray {
        val array = JsonArray()
        tools.forEach { tool ->
            array.add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", tool.name)
                    addProperty("description", tool.description ?: "")
                    add("parameters", gson.toJsonTree(tool.inputSchema ?: mapOf("type" to "object")))
                })
            })
        }
        return array
    }

    private suspend fun sendRawChatCompletion(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>,
        tools: List<McpTool>,
        stream: Boolean
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext LlmResponse(
                content = "[错误] API Key 未配置，请在设置中配置您的 Key 后重试。",
                isError = true
            )
        }

        try {
            val requestJson = JsonObject().apply {
                addProperty("model", resolveTargetModel(config))
                add("messages", toProviderMessages(messages))
                addProperty("stream", stream)
                if (tools.isNotEmpty()) {
                    add("tools", toProviderTools(tools))
                    addProperty("tool_choice", "auto")
                }
                if (config.enableSearch) {
                    addProperty("web_search", true)
                    addProperty("enable_search", true)
                }
            }

            val request = Request.Builder()
                .url(resolveChatCompletionsUrl(config))
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(requestJson).toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: ""
                    val displayError = try {
                        val errJson = gson.fromJson(errorMsg, JsonObject::class.java)
                        errJson.getAsJsonObject("error")?.get("message")?.asString ?: errorMsg
                    } catch (e: Exception) {
                        errorMsg
                    }
                    return@withContext LlmResponse(
                        content = "[错误] 服务器返回 HTTP 错误 ${response.code}: $displayError",
                        isError = true
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext LlmResponse(content = "[错误] 大模型接口返回了空响应", isError = true)
                }

                parseChatCompletionResponse(responseBody)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LlmResponse(content = "[错误] 网络请求故障: ${e.localizedMessage ?: e.message}", isError = true)
        }
    }

    private fun parseChatCompletionResponse(responseBody: String): LlmResponse {
        val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
        val choices = responseJson.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) {
            return LlmResponse(
                content = "[错误] 未能从接口解析出有效文本选择支，服务器输出：$responseBody",
                isError = true
            )
        }

        val messageObj = choices.get(0).asJsonObject.getAsJsonObject("message")
        val rawContent = messageObj?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
        val reasoningContent = messageObj?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
        val toolCalls = parseToolCalls(messageObj?.getAsJsonArray("tool_calls"))

        var finalThoughts: String? = null
        var finalContent = rawContent

        if (!reasoningContent.isNullOrBlank()) {
            finalThoughts = reasoningContent
        }

        val thinkRegex = Regex("<think>([\\s\\S]*?)</think>")
        val matchResult = thinkRegex.find(rawContent)
        if (matchResult != null) {
            if (finalThoughts == null) {
                finalThoughts = matchResult.groupValues[1].trim()
            }
            finalContent = rawContent.replace(thinkRegex, "").trim()
        }

        return LlmResponse(
            content = finalContent,
            thoughts = finalThoughts,
            toolCalls = toolCalls
        )
    }

    private fun parseToolCalls(toolCallsArray: JsonArray?): List<LlmToolCall> {
        if (toolCallsArray == null || toolCallsArray.size() == 0) return emptyList()
        val calls = mutableListOf<LlmToolCall>()
        toolCallsArray.forEachIndexed { index, element ->
            val obj = element.asJsonObject
            val functionObj = obj.getAsJsonObject("function") ?: return@forEachIndexed
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                ?: "tool_${System.currentTimeMillis()}_$index"
            val name = functionObj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@forEachIndexed
            val arguments = functionObj.get("arguments")?.takeIf { !it.isJsonNull }?.let(::jsonElementToString) ?: "{}"
            calls.add(LlmToolCall(id = id, name = name, argumentsJson = arguments))
        }
        return calls
    }

    fun parseArgumentsMap(argumentsJson: String): Map<String, Any> {
        return try {
            gson.fromJson<Map<String, Any>>(argumentsJson.ifBlank { "{}" }, mapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun toJson(value: Any?): String = gson.toJson(value)

    private fun jsonElementToString(element: JsonElement): String {
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            gson.toJson(element)
        }
    }
}
