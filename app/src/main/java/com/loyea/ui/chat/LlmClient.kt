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
import okhttp3.RequestBody.Companion.asRequestBody
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
    val toolCalls: List<LlmToolCall> = emptyList(),
    val imageUrl: String? = null
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

                                    // 2. 正文流 (兼容内嵌式 <think> 与 <tool_call> 标签并增量提取)
                                    val content = delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                                    if (!content.isNullOrEmpty()) {
                                        fullContentBuilder.append(content)
                                        val fullStr = fullContentBuilder.toString()
                                        
                                        val parsedState = parseIncrementalStreamState(fullStr)
                                        
                                        if (parsedState.thoughts.length > emittedThoughtsLength) {
                                            val newThoughts = parsedState.thoughts.substring(emittedThoughtsLength)
                                            emit(StreamEvent.Thoughts(newThoughts))
                                            emittedThoughtsLength = parsedState.thoughts.length
                                        }
                                        
                                        if (parsedState.visibleContent.length > emittedContentLength) {
                                            val newContent = parsedState.visibleContent.substring(emittedContentLength)
                                            emit(StreamEvent.Content(newContent))
                                            emittedContentLength = parsedState.visibleContent.length
                                        }
                                    }

                                    // 3. 工具调用流
                                    val toolCallsJson = delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
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
                val finalState = parseIncrementalStreamState(fullContentBuilder.toString(), isDone = true)
                val finalToolCalls = toolCallBuffers.entries.sortedBy { it.key }.mapNotNull { (_, buffer) ->
                    val id = buffer.id ?: "call_${System.currentTimeMillis()}"
                    val name = buffer.name ?: return@mapNotNull null
                    LlmToolCall(id = id, name = name, argumentsJson = buffer.arguments.toString())
                }
                val combinedCalls = finalToolCalls + finalState.completedXmlCalls
                if (combinedCalls.isNotEmpty()) {
                    emit(StreamEvent.ToolCalls(combinedCalls))
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
        // 自愈映射：如果提供商是 MiMo 且仍然传入默认的 gpt-4o-mini，重定向为 mimo-v2.5-pro
        if (config.provider.equals("MiMo", ignoreCase = true) && targetModel.equals("gpt-4o-mini", ignoreCase = true)) {
            targetModel = "mimo-v2.5-pro"
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

    private fun encodeFileToBase64(filePath: String): String {
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return ""
            
            // 1. 使用 BitmapFactory 解码图片边界
            val options = android.graphics.BitmapFactory.Options()
            options.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(filePath, options)
            
            val srcWidth = options.outWidth
            val srcHeight = options.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                val bytes = file.readBytes()
                return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
            
            // 2. 计算缩放因子，使得最大边不超过 800 像素
            val maxSide = 800
            var inSampleSize = 1
            if (srcWidth > maxSide || srcHeight > maxSide) {
                val widerSampleSize = Math.round(srcWidth.toFloat() / maxSide.toFloat())
                val tallerSampleSize = Math.round(srcHeight.toFloat() / maxSide.toFloat())
                inSampleSize = Math.max(widerSampleSize, tallerSampleSize)
            }
            if (inSampleSize < 1) inSampleSize = 1
            
            // 3. 解码 bitmap
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            val bitmap = android.graphics.BitmapFactory.decodeFile(filePath, options) ?: return ""
            
            // 4. 等比例缩放到最大边 800 像素
            val finalBitmap = if (bitmap.width > maxSide || bitmap.height > maxSide) {
                val ratio = Math.min(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height)
                val destWidth = (bitmap.width * ratio).toInt()
                val destHeight = (bitmap.height * ratio).toInt()
                android.graphics.Bitmap.createScaledBitmap(bitmap, destWidth, destHeight, true)
            } else {
                bitmap
            }
            
            // 5. 压缩为 JPEG 字节流
            val baos = java.io.ByteArrayOutputStream()
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            val bytes = baos.toByteArray()
            
            if (finalBitmap != bitmap) {
                finalBitmap.recycle()
            }
            bitmap.recycle()
            
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            // 回退到原样读取
            try {
                val bytes = java.io.File(filePath).readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (ex: Exception) {
                ""
            }
        }
    }

    private fun toProviderMessages(messages: List<LlmChatMessage>): JsonArray {
        val array = JsonArray()
        messages.forEach { msg ->
            val obj = JsonObject().apply {
                addProperty("role", msg.role)
                if (!msg.imageUrl.isNullOrBlank()) {
                    val contentArray = JsonArray()
                    contentArray.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", msg.content ?: "")
                    })
                    contentArray.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            val base64 = encodeFileToBase64(msg.imageUrl)
                            val mimeType = when {
                                msg.imageUrl.endsWith(".png", true) -> "image/png"
                                msg.imageUrl.endsWith(".webp", true) -> "image/webp"
                                msg.imageUrl.endsWith(".gif", true) -> "image/gif"
                                else -> "image/jpeg"
                            }
                            addProperty("url", "data:$mimeType;base64,$base64")
                        })
                    })
                    add("content", contentArray)
                } else {
                    if (msg.content != null) addProperty("content", msg.content)
                }
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
        val apiToolCalls = parseToolCalls(messageObj?.getAsJsonArray("tool_calls"))

        val parsedState = parseIncrementalStreamState(rawContent, isDone = true)
        val finalThoughts = if (!reasoningContent.isNullOrBlank()) reasoningContent else parsedState.thoughts.takeIf { it.isNotBlank() }
        val finalContent = parsedState.visibleContent.trim()
        val combinedToolCalls = apiToolCalls + parsedState.completedXmlCalls

        return LlmResponse(
            content = finalContent,
            thoughts = finalThoughts,
            toolCalls = combinedToolCalls
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

    private fun resolveImagesGenerationsUrl(config: com.loyea.ui.settings.ApiConfig): String {
        val baseUrl = config.apiUrl.trim()
        val cleaned = baseUrl.substringBefore("/chat/completions").removeSuffix("/")
        return "$cleaned/images/generations"
    }

    private fun resolveAudioSpeechUrl(config: com.loyea.ui.settings.ApiConfig): String {
        val baseUrl = config.apiUrl.trim()
        val cleaned = baseUrl.substringBefore("/chat/completions").removeSuffix("/")
        return "$cleaned/audio/speech"
    }

    private fun resolveAudioTranscriptionsUrl(config: com.loyea.ui.settings.ApiConfig): String {
        val baseUrl = config.apiUrl.trim()
        val cleaned = baseUrl.substringBefore("/chat/completions").removeSuffix("/")
        return "$cleaned/audio/transcriptions"
    }

    data class TtsResult(
        val success: Boolean,
        val errorMsg: String? = null
    )

    suspend fun generateSpeech(
        config: com.loyea.ui.settings.ApiConfig,
        text: String,
        model: String,
        voice: String,
        outputFile: java.io.File
    ): TtsResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext TtsResult(false, "API Key 不能为空，请前往设置中配置您的 API Key。")
        try {
            val provider = config.provider.trim()
            val isMiMo = provider.equals("MiMo", ignoreCase = true)
            val isAli = provider.equals("Alibaba", ignoreCase = true) || provider.equals("DashScope", ignoreCase = true)
            val isVolcano = provider.equals("Volcengine", ignoreCase = true) || provider.equals("Doubao", ignoreCase = true)
            
            // 1. 构建 URL
            val url = when {
                isMiMo -> resolveChatCompletionsUrl(config)
                isAli -> {
                    val baseUrl = config.apiUrl.trim().removeSuffix("/")
                    if (baseUrl.contains("/api/v1/services")) baseUrl else "$baseUrl/api/v1/services/audio/tts/SpeechSynthesizer"
                }
                isVolcano -> {
                    val baseUrl = config.apiUrl.trim().removeSuffix("/")
                    if (baseUrl.contains("/api/v1/tts")) baseUrl else "$baseUrl/api/v1/tts"
                }
                else -> resolveAudioSpeechUrl(config)
            }
            
            // 2. 解析火山引擎密钥结构: APPID:ACCESS_TOKEN:CLUSTER_ID
            var volcanoAppId = ""
            var volcanoToken = ""
            var volcanoClusterId = ""
            if (isVolcano) {
                val parts = config.apiKey.split(":", ",")
                if (parts.size < 3) {
                    return@withContext TtsResult(false, "火山引擎 API Key 格式错误！请在 API 设置中将密钥配置为：APPID:ACCESS_TOKEN:CLUSTER_ID (冒号或逗号分隔)")
                }
                volcanoAppId = parts[0].trim()
                volcanoToken = parts[1].trim()
                volcanoClusterId = parts[2].trim()
            }
            
            // 3. 智能模型自愈
            val targetModel = when {
                isMiMo -> {
                    if (model.isBlank() || model.equals("tts-1", ignoreCase = true) || model.contains("default", ignoreCase = true)) {
                        "mimo-v2.5-tts"
                    } else model
                }
                isAli -> {
                    if (model.isBlank() || model.contains("default", ignoreCase = true)) {
                        "cosyvoice-v3-flash"
                    } else model
                }
                isVolcano -> {
                    if (model.isBlank() || model.contains("default", ignoreCase = true)) {
                        "volcengine-tts"
                    } else model
                }
                else -> {
                    if (model.isBlank() || model.contains("default", ignoreCase = true)) {
                        "tts-1"
                    } else model
                }
            }
            
            // 4. 智能音色自愈
            val targetVoice = when {
                isMiMo -> {
                    val mimoVoices = setOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean")
                    if (voice.isBlank() || !mimoVoices.contains(voice)) "茉莉" else voice
                }
                isAli -> {
                    if (voice.isBlank()) "longanyang" else voice
                }
                isVolcano -> {
                    if (voice.isBlank()) "female_emotion_1" else voice
                }
                else -> {
                    val openAiVoices = setOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
                    val cleanVoice = voice.lowercase().trim()
                    if (voice.isBlank() || !openAiVoices.contains(cleanVoice)) "alloy" else voice
                }
            }
            
            // 5. 构建请求体
            val requestJson = when {
                isMiMo -> {
                    JsonObject().apply {
                        addProperty("model", targetModel)
                        val messagesArray = JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", "用温柔亲切的语气朗读下文。")
                            })
                            add(JsonObject().apply {
                                addProperty("role", "assistant")
                                addProperty("content", text)
                            })
                        }
                        add("messages", messagesArray)
                        val audioObj = JsonObject().apply {
                            addProperty("format", "wav")
                            addProperty("voice", targetVoice)
                        }
                        add("audio", audioObj)
                    }
                }
                isAli -> {
                    JsonObject().apply {
                        addProperty("model", targetModel)
                        add("input", JsonObject().apply {
                            addProperty("text", text)
                        })
                        add("parameters", JsonObject().apply {
                            addProperty("voice", targetVoice)
                            addProperty("format", "mp3")
                        })
                    }
                }
                isVolcano -> {
                    JsonObject().apply {
                        add("app", JsonObject().apply {
                            addProperty("appid", volcanoAppId)
                            addProperty("token", volcanoToken)
                            addProperty("cluster", volcanoClusterId)
                        })
                        add("user", JsonObject().apply {
                            addProperty("uid", "loyea_user")
                        })
                        add("audio", JsonObject().apply {
                            addProperty("voice_type", targetVoice)
                            addProperty("encoding", "mp3")
                        })
                        add("request", JsonObject().apply {
                            addProperty("reqid", java.util.UUID.randomUUID().toString())
                            addProperty("text", text)
                            addProperty("text_type", "plain")
                            addProperty("operation", "query")
                        })
                    }
                }
                else -> {
                    JsonObject().apply {
                        addProperty("model", targetModel)
                        addProperty("input", text)
                        addProperty("voice", targetVoice)
                    }
                }
            }
            
            val requestBodyStr = gson.toJson(requestJson)
            android.util.Log.d("LlmClient", "TTS request to $url (provider=$provider): $requestBodyStr")
            val requestBody = requestBodyStr.toRequestBody(mediaType)
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
            
            when {
                isMiMo -> {
                    requestBuilder.addHeader("api-key", config.apiKey)
                    requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                }
                isAli -> {
                    requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                }
                isVolcano -> {
                    requestBuilder.addHeader("Authorization", "Bearer;${volcanoToken}")
                }
                else -> {
                    requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                }
            }
            
            val request = requestBuilder.post(requestBody).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string() ?: ""
                    android.util.Log.e("LlmClient", "TTS failed! HTTP ${response.code}, response body: $errorMsg")
                    val displayError = try {
                        val errJson = gson.fromJson(errorMsg, JsonObject::class.java)
                        errJson.getAsJsonObject("error")?.get("message")?.asString ?: errorMsg
                    } catch (e: Exception) {
                        errorMsg
                    }
                    return@withContext TtsResult(false, "HTTP 错误 ${response.code}: $displayError")
                }
                
                when {
                    isMiMo -> {
                        val resBody = response.body?.string() ?: ""
                        val resJson = gson.fromJson(resBody, JsonObject::class.java)
                        val choices = resJson.getAsJsonArray("choices")
                        if (choices == null || choices.size() == 0) {
                            return@withContext TtsResult(false, "接口未返回 choices: $resBody")
                        }
                        val msgObj = choices.get(0).asJsonObject.getAsJsonObject("message")
                        val audioObj = msgObj?.getAsJsonObject("audio")
                        val audioData = audioObj?.get("data")?.asString
                        if (audioData.isNullOrBlank()) {
                            return@withContext TtsResult(false, "接口未返回音频数据，请检查服务商额度或配置，服务器返回: $resBody")
                        }
                        
                        val audioBytes = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)
                        outputFile.outputStream().use { output ->
                            output.write(audioBytes)
                        }
                        return@withContext TtsResult(true)
                    }
                    isVolcano -> {
                        val resBody = response.body?.string() ?: ""
                        val resJson = gson.fromJson(resBody, JsonObject::class.java)
                        val code = resJson.get("code")?.asInt ?: 0
                        val msg = resJson.get("message")?.asString ?: "未知错误"
                        if (code != 3000 && !msg.equals("success", ignoreCase = true)) {
                            return@withContext TtsResult(false, "火山语音合成错误 (code $code): $msg")
                        }
                        val audioData = resJson.get("data")?.asString
                        if (audioData.isNullOrBlank()) {
                            return@withContext TtsResult(false, "火山语音未返回音频数据，服务器返回: $resBody")
                        }
                        val audioBytes = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)
                        outputFile.outputStream().use { output ->
                            output.write(audioBytes)
                        }
                        return@withContext TtsResult(true)
                    }
                    else -> {
                        // Ali 或 OpenAI，返回的是音频二进制流
                        val body = response.body ?: return@withContext TtsResult(false, "语音合成接口返回了空的响应体 (Empty Response)")
                        body.byteStream().use { inputStream ->
                            outputFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        return@withContext TtsResult(true)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("LlmClient", "TTS exception", e)
            if (outputFile.exists()) {
                try {
                    outputFile.delete()
                } catch (ex: Exception) {}
            }
            return@withContext TtsResult(false, e.localizedMessage ?: e.message ?: "网络超时或网络异常")
        }
    }

    suspend fun transcribeAudio(
        config: com.loyea.ui.settings.ApiConfig,
        audioFile: java.io.File,
        model: String
    ): String? = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext null
        try {
            val isMiMo = config.provider.equals("MiMo", ignoreCase = true)
            val targetModel = if (isMiMo) {
                if (model.isBlank() || model.equals("whisper-1", ignoreCase = true) || model.contains("default", ignoreCase = true)) {
                    "mimo-v2.5-asr"
                } else {
                    model
                }
            } else {
                model
            }

            if (isMiMo) {
                val url = resolveChatCompletionsUrl(config)
                val audioBytes = audioFile.readBytes()
                val base64Data = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                val format = when (audioFile.extension.lowercase()) {
                    "mp3" -> "mp3"
                    "wav" -> "wav"
                    "m4a" -> "m4a"
                    else -> "wav"
                }

                val requestJson = JsonObject().apply {
                    addProperty("model", targetModel)
                    val messagesArray = JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            val contentArray = JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "input_audio")
                                    val inputAudioObj = JsonObject().apply {
                                        addProperty("data", base64Data)
                                        addProperty("format", format)
                                    }
                                    add("input_audio", inputAudioObj)
                                })
                            }
                            add("content", contentArray)
                        })
                    }
                    add("messages", messagesArray)
                }

                val requestBodyStr = gson.toJson(requestJson)
                android.util.Log.d("LlmClient", "ASR request to $url (isMiMo=true): size=${audioBytes.size} bytes, format=$format")
                val requestBody = requestBodyStr.toRequestBody(mediaType)
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                if (isMiMo) {
                    requestBuilder.addHeader("api-key", config.apiKey)
                }
                requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
                val request = requestBuilder.post(requestBody).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorMsg = response.body?.string() ?: ""
                        android.util.Log.e("LlmClient", "ASR failed! HTTP ${response.code}, response body: $errorMsg")
                        return@withContext null
                    }
                    val resBody = response.body?.string() ?: ""
                    val resJson = gson.fromJson(resBody, JsonObject::class.java)
                    val choices = resJson.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) return@withContext null
                    val content = choices.get(0).asJsonObject.getAsJsonObject("message")?.get("content")?.asString
                    return@withContext content
                }
            } else {
                val url = resolveAudioTranscriptionsUrl(config)
                val mimeType = when (audioFile.extension.lowercase()) {
                    "m4a" -> "audio/m4a"
                    "mp4" -> "audio/mp4"
                    "wav" -> "audio/wav"
                    else -> "audio/m4a"
                }
                val fileBody = audioFile.asRequestBody(mimeType.toMediaType())
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name, fileBody)
                    .addFormDataPart("model", targetModel)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val resBody = response.body?.string() ?: ""
                    val jsonObj = gson.fromJson(resBody, JsonObject::class.java)
                    return@withContext jsonObj.get("text")?.asString
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun generateImage(
        config: com.loyea.ui.settings.ApiConfig,
        prompt: String,
        model: String
    ): String? = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext null
        try {
            val url = resolveImagesGenerationsUrl(config)
            val targetModel = if (config.provider.equals("MiMo", ignoreCase = true) && (model.equals("dall-e-3", ignoreCase = true) || model.isBlank())) {
                "mimo-v2.5-images"
            } else {
                model
            }
            val requestJson = JsonObject().apply {
                addProperty("prompt", prompt)
                addProperty("model", targetModel)
                addProperty("n", 1)
                addProperty("size", "1024x1024")
            }
            val requestBody = gson.toJson(requestJson).toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val resBody = response.body?.string() ?: ""
                val jsonObj = gson.fromJson(resBody, JsonObject::class.java)
                val dataArray = jsonObj.getAsJsonArray("data")
                if (dataArray != null && dataArray.size() > 0) {
                    return@withContext dataArray.get(0).asJsonObject.get("url")?.asString
                }
                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class ParsedStreamState(
        val thoughts: String,
        val visibleContent: String,
        val completedXmlCalls: List<LlmToolCall>
    )

    fun parseIncrementalStreamState(fullStr: String, isDone: Boolean = false): ParsedStreamState {
        var thoughtsAccumulator = ""
        var visibleContentAccumulator = ""
        val completedXmlCalls = mutableListOf<LlmToolCall>()

        // 1. 寻找未闭合的 <tool_call> 或 <tool_invocation>。如果有且尚未 Done，截断后续正在生成的文本，挂起不发射
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

        // 2.1 处理 <tool_call>... (使用超强自愈正则，适配传统闭合、残缺、或连续不闭合)
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
            val funcName = match.groupValues[1]
            val argumentsJson = match.groupValues[2]
            completedXmlCalls.add(
                LlmToolCall(
                    id = "xml_call_${System.currentTimeMillis()}_${(0..1000).random()}",
                    name = funcName,
                    argumentsJson = argumentsJson
                )
            )
            cleanStr = cleanStr.replace(xmlBlock, "")
        }

        // 3. 在已经滤除掉工具调用的 cleanStr 中，进行 <think> 与正文的处理
        var cursor = 0
        val len = cleanStr.length
        while (cursor < len) {
            val thinkStart = cleanStr.indexOf("<think>", cursor)
            if (thinkStart == -1) {
                visibleContentAccumulator += cleanStr.substring(cursor)
                break
            }

            if (thinkStart > cursor) {
                visibleContentAccumulator += cleanStr.substring(cursor, thinkStart)
            }

            val thinkEnd = cleanStr.indexOf("</think>", thinkStart)
            if (thinkEnd != -1) {
                thoughtsAccumulator += cleanStr.substring(thinkStart + 7, thinkEnd)
                cursor = thinkEnd + 8
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

    private fun parseXmlToolCallsOnly(xmlBlock: String): List<LlmToolCall> {
        val calls = mutableListOf<LlmToolCall>()
        
        // 1. 支持自愈残缺不闭合的标签正则
        val blockContentRegex = Regex("<tool_call>([\\s\\S]*?)(?:</tool_call>|$)")
        val match = blockContentRegex.find(xmlBlock) ?: return emptyList()
        val block = match.groupValues[1].trim()

        // 2. 优先尝试解析函数风格：name(key="value", ...)
        val funcStyleRegex = Regex("^([a-zA-Z0-9_]+)\\(([\\s\\S]*)\\)$")
        val funcMatch = funcStyleRegex.matchEntire(block)
        if (funcMatch != null) {
            val funcName = funcMatch.groupValues[1]
            val argsStr = funcMatch.groupValues[2]
            
            val paramRegex = Regex("([a-zA-Z0-9_]+)\\s*=\\s*['\"]([\\s\\S]*?)['\"](?=\\s*,|\\s*$)")
            val argsMap = mutableMapOf<String, Any>()
            paramRegex.findAll(argsStr).forEach { paramMatch ->
                val key = paramMatch.groupValues[1]
                val valStr = paramMatch.groupValues[2]
                argsMap[key] = valStr
            }
            
            if (funcName.isNotBlank()) {
                calls.add(
                    LlmToolCall(
                        id = "xml_call_${System.currentTimeMillis()}_${(0..1000).random()}",
                        name = funcName,
                        argumentsJson = gson.toJson(argsMap)
                    )
                )
                return calls
            }
        }

        // 3. 兜底走原本的 XML 风格解析：<function=name> <parameter=val>...</parameter>
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
            val argumentsJson = gson.toJson(argsMap)
            calls.add(
                LlmToolCall(
                    id = "xml_call_${System.currentTimeMillis()}_${(0..1000).random()}",
                    name = funcName,
                    argumentsJson = argumentsJson
                )
            )
        }
        return calls
    }
}


