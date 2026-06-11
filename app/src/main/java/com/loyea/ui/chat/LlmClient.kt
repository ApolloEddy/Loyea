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
            val processedMessages = messages

            // 根据深度思考状态，对 DeepSeek 进行智能路由（仅在开启智能模型路由时生效）
            val targetModel = resolveTargetModel(config)

            val requestJson = JsonObject().apply {
                addProperty("model", targetModel)
                add("messages", toProviderMessages(processedMessages))
                addProperty("stream", true)
                if (tools.isNotEmpty()) {
                    add("tools", toProviderTools(tools))
                    addProperty("tool_choice", "auto")
                }
                
                // 开启联网搜索 (非独立搜索时才写入 web_search 参数，避免中转冲突；排除 MiMo 避免 401 鉴权问题)
                if (config.enableSearch && !config.useIndependentSearch && !config.provider.equals("MiMo", ignoreCase = true)) {
                    addProperty("web_search", true)
                    addProperty("enable_search", true)
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
                if (config.enableSearch && !config.provider.equals("MiMo", ignoreCase = true)) {
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
            val processedMessages = messages

            val requestJson = JsonObject().apply {
                addProperty("model", resolveTargetModel(config))
                add("messages", toProviderMessages(processedMessages))
                addProperty("stream", stream)
                if (tools.isNotEmpty()) {
                    add("tools", toProviderTools(tools))
                    addProperty("tool_choice", "auto")
                }
                if (config.enableSearch && !config.useIndependentSearch && !config.provider.equals("MiMo", ignoreCase = true)) {
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

    suspend fun performIndependentWebSearch(config: com.loyea.ui.settings.ApiConfig, query: String): String = withContext(Dispatchers.IO) {
        if (config.searchApiKey.isBlank()) return@withContext "\n\n[联网搜索失败: 未配置搜索 API Key]\n\n"
        
        val baseUrl = config.searchApiUrl.trim().removeSuffix("/")
        val finalUrl = if (config.searchProvider.equals("Tavily", ignoreCase = true)) {
            "$baseUrl/search"
        } else {
            baseUrl
        }

        val requestJson = JsonObject().apply {
            addProperty("api_key", config.searchApiKey)
            addProperty("query", query)
            addProperty("search_depth", "basic")
            addProperty("include_answer", false)
        }

        val requestBody = gson.toJson(requestJson).toRequestBody(mediaType)
        val request = Request.Builder()
            .url(finalUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "\n\n[联网搜索失败: HTTP ${response.code}]\n\n"
                }
                val bodyStr = response.body?.string() ?: return@withContext "\n\n[联网搜索失败: 空内容]\n\n"
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                val results = json.getAsJsonArray("results")
                if (results == null || results.size() == 0) {
                    return@withContext "\n\n[联网搜索结束: 未找到相关实时网页结果]\n\n"
                }

                val sb = StringBuilder()
                sb.append("\n\n=== [联网搜索实时参考资料] ===\n")
                var count = 1
                for (elem in results) {
                    val obj = elem.asJsonObject
                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    sb.append("${count}. 标题: $title\n   链接: $url\n   摘要: $content\n\n")
                    count++
                    if (count > 5) break
                }
                sb.append("请优先结合以上联网搜索到的最新实时信息，回答用户的提问。如果上述网页信息与用户提问无关，请忽略。\n=========================\n\n")
                sb.toString()
            }
        } catch (e: Exception) {
            android.util.Log.e("LlmClient", "Independent web search error", e)
            "\n\n[联网搜索网络错误: ${e.message}]\n\n"
        }
    }

    suspend fun performFreeWebSearch(query: String): String = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<String>()
        var sourceUsed = ""

        // 1. 第一尝试：Bing 搜索 (国内外检索质量最高且抗反爬极佳)
        try {
            val url = "https://cn.bing.com/search?q=$encodedQuery"
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val blockRegex = Regex("<li class=\"b_algo\">([\\s\\S]*?)</li>")
                    val aRegex = Regex("<a[^>]*href=\"(http[s]?://.*?)\"[^>]*>([\\s\\S]*?)</a>")
                    val pRegex = Regex("<p>([\\s\\S]*?)</p>")
                    
                    var count = 1
                    blockRegex.findAll(html).forEach { match ->
                        val block = match.groupValues[1]
                        val aMatch = aRegex.find(block)
                        val href = aMatch?.groupValues?.get(1) ?: ""
                        
                        // 排除 Bing 内部的搜索或者无关链接
                        if (href.isNotEmpty() && !href.contains("bing.com/") && !href.contains("microsoft.com/")) {
                            var title = aMatch?.groupValues?.get(2) ?: ""
                            title = title.replace(Regex("<[^>]*>"), "").trim()
                            
                            var snippet = pRegex.find(block)?.groupValues?.get(1) ?: ""
                            snippet = snippet.replace(Regex("<[^>]*>"), "").trim()
                            
                            if (snippet.isNotEmpty()) {
                                results.add("${count}. 标题: ${title.ifBlank { "无标题" }}\n   链接: $href\n   摘要: $snippet")
                                count++
                            }
                        }
                        if (count > 5) return@forEach
                    }
                    if (results.size >= 2) {
                        sourceUsed = "Bing"
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LlmClient", "Bing search error", e)
        }

        // 2. 第二尝试：如果 Bing 结果不足，尝试 360 搜索 (国内直连备用，零门槛防爬)
        if (results.size < 2) {
            results.clear()
            try {
                val url = "https://www.so.com/s?q=$encodedQuery"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                    
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string() ?: ""
                        val blockRegex = Regex("<li class=\"res-list\">([\\s\\S]*?)</li>")
                        val aRegex = Regex("<a[^>]*href=\"(http[s]?://.*?)\"[^>]*>([\\s\\S]*?)</a>")
                        val descRegex = Regex("<p[^>]*class=\"[^\"]*(?:desc|rich-desc)[^\"]*\"[^>]*>([\\s\\S]*?)</p>")
                        
                        var count = 1
                        blockRegex.findAll(html).forEach { match ->
                            val block = match.groupValues[1]
                            val aMatch = aRegex.find(block)
                            val href = aMatch?.groupValues?.get(1) ?: ""
                            
                            if (href.isNotEmpty()) {
                                var title = aMatch?.groupValues?.get(2) ?: ""
                                title = title.replace(Regex("<[^>]*>"), "").trim()
                                
                                var snippet = descRegex.find(block)?.groupValues?.get(1) ?: ""
                                snippet = snippet.replace(Regex("<[^>]*>"), "").trim()
                                
                                if (snippet.isNotEmpty()) {
                                    results.add("${count}. 标题: ${title.ifBlank { "无标题" }}\n   链接: $href\n   摘要: $snippet")
                                    count++
                                }
                            }
                            if (count > 5) return@forEach
                        }
                        if (results.size >= 2) {
                            sourceUsed = "360搜索"
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LlmClient", "360 search error", e)
            }
        }

        // 3. 第三尝试：回退到 DuckDuckGo HTML 搜索
        if (results.size < 2) {
            results.clear()
            try {
                val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36")
                    .build()
                    
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string() ?: ""
                        val blockRegex = Regex("<div class=\"result__body\">([\\s\\S]*?)</div>")
                        val urlRegex = Regex("<a class=\"result__url\" href=\"(.*?)\"")
                        val titleARegex = Regex("<a class=\"result__title-a\" href=\".*?\">(.*?)</a>")
                        val snippetRegex = Regex("<a class=\"result__snippet\" href=\".*?\">([\\s\\S]*?)</a>")
                        
                        var count = 1
                        blockRegex.findAll(html).forEach { match ->
                            val block = match.groupValues[1]
                            val href = urlRegex.find(block)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                            val realUrl = if (href.contains("uddg=")) href.substringAfter("uddg=").substringBefore("&") else href
                            
                            var title = titleARegex.find(block)?.groupValues?.get(1) ?: ""
                            title = title.replace(Regex("<[^>]*>"), "").trim()
                            
                            var snippet = snippetRegex.find(block)?.groupValues?.get(1) ?: ""
                            snippet = snippet.replace(Regex("<[^>]*>"), "").trim()
                            
                            if (realUrl.isNotEmpty() && snippet.isNotEmpty()) {
                                results.add("${count}. 标题: ${title.ifBlank { "无标题" }}\n   链接: $realUrl\n   摘要: $snippet")
                                count++
                            }
                            if (count > 5) return@forEach
                        }
                        if (results.size >= 2) {
                            sourceUsed = "DuckDuckGo"
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LlmClient", "DuckDuckGo search error", e)
            }
        }

        // 最终拼装结果
        if (results.isEmpty()) {
            "\n\n[联网搜索结束: 未在任何公共检索源（Bing/360/DuckDuckGo）中提取到关于该关键词的实时参考网页，可能由于网络超时或被反爬拦截]\n\n"
        } else {
            "\n\n=== [联网搜索实时参考资料 (免 Key 公共检索 - 源自 $sourceUsed)] ===\n" + 
            results.joinToString("\n\n") + 
            "\n=========================\n\n"
        }
    }
}
