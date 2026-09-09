package com.loyea.ui.chat

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.loyea.mcp.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /** 非致命提示（如"渠道不支持流式已自动降级"）：不打断流，UI 以 Toast 告知 */
    data class Notice(val text: String) : StreamEvent()
    data class Usage(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val promptCacheHitTokens: Long = 0,
        val promptCacheMissTokens: Long = 0
    ) : StreamEvent()
    object Done : StreamEvent()
}

/** usage 计量统一为 llm.Usage（字段同形，历史调用点零改动） */
typealias LlmUsage = com.loyea.llm.Usage

/**
 * 远程 LLM 服务非流式响应实体（业务层契约保持不变；
 * Transport 层使用同形的 com.loyea.llm.ChatResponse，errorKind 额外携带错误分类）。
 */
data class LlmResponse(
    val content: String,
    val thoughts: String? = null,
    val isError: Boolean = false,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val totalTokens: Long? = null,
    val promptCacheHitTokens: Long? = null,
    val promptCacheMissTokens: Long? = null,
    val errorKind: com.loyea.llm.LlmErrorKind? = null
)

fun com.loyea.llm.ChatResponse.toLlmResponse(): LlmResponse = LlmResponse(
    content = content,
    thoughts = thoughts,
    isError = isError,
    toolCalls = toolCalls,
    promptTokens = usage?.promptTokens,
    completionTokens = usage?.completionTokens,
    totalTokens = usage?.totalTokens,
    promptCacheHitTokens = usage?.promptCacheHitTokens,
    promptCacheMissTokens = usage?.promptCacheMissTokens,
    errorKind = errorKind
)

typealias LlmToolCall = com.loyea.llm.ToolCall

typealias LlmChatMessage = com.loyea.llm.ChatMessage

/**
 * 大模型 API 网络通信客户端
 */
/** 生图失败（携带可展示给用户的真实原因：HTTP 状态 / 服务商错误体 / 网络异常） */
class ImageGenException(message: String) : Exception(message)

/** 生图结果：远程 URL（需下载）或 base64 编码图片（直接解码落盘）二选一。 */
data class ImageGenResult(val remoteUrl: String?, val base64Png: String?)

typealias NonSseBodyOutcome = com.loyea.llm.NonSseBodyOutcome

/** SSE data 行载荷提取（统一实现移至 llm 包；保留此处供既有测试引用） */
internal fun extractSseDataPayload(trimmedLine: String): String? =
    com.loyea.llm.extractSseDataPayload(trimmedLine)

class LlmClient {
    @Volatile
    var lastAsrError: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    /** 统一 Chat Completion Transport：全应用唯一的聊天补全 HTTP 实现（Spec §10） */
    private val transport = com.loyea.llm.UnifiedChatTransport(client)
    private val parser = com.loyea.llm.ResponseParser(gson)

    /** 多模态部件（图片/音频 base64）编码回调：注入统一 Transport */
    private val messageEncoder = object : com.loyea.llm.MessageEncoder {
        override fun encodeMessages(messages: List<LlmChatMessage>): JsonArray = toProviderMessages(messages)
        override fun encodeTools(tools: List<com.loyea.llm.ToolSchema>): JsonArray = toProviderTools(
            tools.map { McpTool(name = it.name, description = it.description, inputSchema = it.inputSchema) }
        )
    }

    private fun buildExecutionRequest(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>,
        tools: List<McpTool>,
        streamMode: com.loyea.llm.StreamMode
    ): com.loyea.llm.ChatExecutionRequest {
        val targetModel = resolveTargetModel(config)
        return com.loyea.llm.ChatExecutionRequest(
            configId = config.id,
            apiUrl = config.apiUrl,
            apiKey = config.apiKey,
            providerPreset = config.provider,
            model = targetModel,
            messages = messages,
            tools = tools.map { com.loyea.llm.ToolSchema(it.name, it.description, it.inputSchema) },
            streamMode = streamMode,
            nativeSearchRequested = config.enableSearch && !config.useIndependentSearch
        )
    }

    private fun com.loyea.llm.TransportEvent.toStreamEvent(): StreamEvent = when (this) {
        is com.loyea.llm.TransportEvent.Content -> StreamEvent.Content(text)
        is com.loyea.llm.TransportEvent.Thoughts -> StreamEvent.Thoughts(text)
        is com.loyea.llm.TransportEvent.ToolCalls -> StreamEvent.ToolCalls(calls)
        is com.loyea.llm.TransportEvent.Notice -> StreamEvent.Notice(text)
        is com.loyea.llm.TransportEvent.UsageEvent -> StreamEvent.Usage(
            promptTokens = usage.promptTokens ?: 0L,
            completionTokens = usage.completionTokens ?: 0L,
            totalTokens = usage.totalTokens ?: 0L,
            promptCacheHitTokens = usage.promptCacheHitTokens ?: 0L,
            promptCacheMissTokens = usage.promptCacheMissTokens ?: 0L
        )
        is com.loyea.llm.TransportEvent.Error -> StreamEvent.Error(message)
        com.loyea.llm.TransportEvent.Done -> StreamEvent.Done
    }

    /**
     * 将 HTTP 错误码转化为对用户可操作的分级提示（旧调用点兼容保留；
     * 统一 Transport 内部已使用 LlmErrorKind 分类）。
     */
    internal fun buildFriendlyHttpError(code: Int, rawDetail: String): String {
        val safeDetail = rawDetail.trim().take(300)
        return when (code) {
            401, 403 -> "[错误] 鉴权失败 (HTTP $code)：请检查 API Key 是否正确或账户额度是否有效。$safeDetail"
            429 -> "[错误] 请求过于频繁或额度已耗尽 (HTTP 429)：已自动重试，仍失败请稍后再试或检查账户额度。$safeDetail"
            in 500..599 -> "[错误] 服务器繁忙 (HTTP $code)：已自动重试，仍失败请稍后再试。$safeDetail"
            in 400..499 -> "[错误] 参数错误 (HTTP $code)：$safeDetail"
            else -> "[错误] 服务器返回 HTTP 错误 $code: $safeDetail"
        }
    }

    /**
     * 发送 Chat Completion 流式对话请求（统一 Transport 状态机分派）。
     */
    fun sendChatCompletionStream(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>,
        tools: List<McpTool> = emptyList()
    ): Flow<StreamEvent> {
        // 当本次请求 tools 为空（例如传图时），对 messages 历史做自愈翻译，将 tool_calls 翻译为普通文本 XML 格式，防止 API 400 报错
        val processedMessages = sanitizeMessages(messages, tools.isNotEmpty())
        val request = buildExecutionRequest(config, processedMessages, tools, config.streamMode)
        return transport.stream(request, messageEncoder).map { it.toStreamEvent() }
    }

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
     * 发送 Chat Completion 同步对话请求：已迁移到统一 Transport（Spec §10）。
     * 相比旧实现补齐了：MiMo api-key 头、429/5xx 退避重试、180s 非流式读超时、统一错误分类，
     * 且 Memory/压缩等后台路径与前台共享同一套解析与重试行为。
     */
    suspend fun sendChatCompletion(
        config: com.loyea.ui.settings.ApiConfig,
        systemPrompt: String?,
        history: List<Message>
    ): LlmResponse {
        val chatMessages = mutableListOf<LlmChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            chatMessages.add(LlmChatMessage(role = "system", content = systemPrompt))
        }
        chatMessages.addAll(
            history.filter {
                it.content.isNotBlank() && !it.content.startsWith("[错误]") && !it.content.startsWith("[Error]")
            }.map { msg ->
                LlmChatMessage(
                    role = if (msg.sender == Sender.USER) "user" else "assistant",
                    content = msg.content
                )
            }
        )
        val request = buildExecutionRequest(config, chatMessages, emptyList(), com.loyea.llm.StreamMode.NON_STREAM)
        return transport.once(request, messageEncoder).toLlmResponse()
    }

    suspend fun sendChatCompletionWithTools(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>,
        tools: List<McpTool>
    ): LlmResponse {
        val request = buildExecutionRequest(config, messages, tools, com.loyea.llm.StreamMode.NON_STREAM)
        return transport.once(request, messageEncoder).toLlmResponse()
    }

    /**
     * 显式整包模式的流事件包装（业务命名保留；底层与流式路径共用同一 Transport）。
     */
    fun sendRawChatCompletionStream(
        config: com.loyea.ui.settings.ApiConfig,
        messages: List<LlmChatMessage>
    ): Flow<StreamEvent> {
        val request = buildExecutionRequest(config, messages, emptyList(), com.loyea.llm.StreamMode.NON_STREAM)
        return transport.stream(request, messageEncoder).map { it.toStreamEvent() }
    }

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
            
            // 2. 计算缩放因子：上限 1280px——800px 会把整页密排小字压到 4-6px 高，
            // 模型"看得见图读不了字"（行测卷面识别失败根因之一）；1280 对文档 OCR 明显更稳
            val maxSide = 1280
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
            val bitmap = android.graphics.BitmapFactory.decodeFile(filePath, options)
            if (bitmap == null) {
                android.util.Log.w("LoyeaVision",
                    "bitmap decode FAILED: $filePath bounds=${options.outWidth}x${options.outHeight} mime=${options.outMimeType}"
                )
                return ""
            }
            
            // 4. EXIF 方向：BitmapFactory 不应用旋转标记，竖拍照片会横着发给模型
            val orientedBitmap = applyExifRotation(filePath, bitmap)

            // 5. 等比例缩放到上限内
            val finalBitmap = if (orientedBitmap.width > maxSide || orientedBitmap.height > maxSide) {
                val ratio = Math.min(maxSide.toFloat() / orientedBitmap.width, maxSide.toFloat() / orientedBitmap.height)
                val destWidth = (orientedBitmap.width * ratio).toInt()
                val destHeight = (orientedBitmap.height * ratio).toInt()
                android.graphics.Bitmap.createScaledBitmap(orientedBitmap, destWidth, destHeight, true)
            } else {
                orientedBitmap
            }

            // 6. 压缩为 JPEG 字节流
            val baos = java.io.ByteArrayOutputStream()
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
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

    /** EXIF 方向矫正：按照片的旋转标记摆正 bitmap，失败时原样返回 */
    private fun applyExifRotation(filePath: String, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        return try {
            val exif = android.media.ExifInterface(filePath)
            val degrees = when (exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bitmap
            val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
            val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun toProviderMessages(messages: List<LlmChatMessage>): JsonArray {
        val array = JsonArray()
        messages.forEach { msg ->
            val obj = JsonObject().apply {
                addProperty("role", msg.role)
                if (!msg.imageUrl.isNullOrBlank()) {
                    val base64 = encodeFileToBase64(msg.imageUrl)
                    android.util.Log.d("LoyeaVision", "image part: path=${msg.imageUrl} base64Len=${base64.length}")
                    if (base64.isBlank()) {
                        // 图片文件读取/解码失败：绝不发送空 base64 的畸形 data URL（服务端必 400），
                        // 退化为占位文本，让本轮请求照常继续
                        val text = (msg.content?.takeIf { it.isNotBlank() }?.plus("\n") ?: "") + "[图片]"
                        addProperty("content", text)
                    } else {
                        val contentArray = JsonArray()
                        // 纯图片消息（无文字）不给 text 块塞空串：严格网关会判 content 结构非法（智谱 1214 一类）
                        if (!msg.content.isNullOrBlank()) {
                            contentArray.add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", msg.content)
                            })
                        }
                        contentArray.add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply {
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
                    }
                } else if (!msg.audioUrl.isNullOrBlank()) {
                    val contentArray = JsonArray()
                    if (!msg.content.isNullOrBlank()) {
                        contentArray.add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", msg.content)
                        })
                    }
                    contentArray.add(JsonObject().apply {
                        addProperty("type", "input_audio")
                        val inputAudioObj = JsonObject().apply {
                            val base64 = encodeFileToBase64(msg.audioUrl)
                            val format = when (msg.audioUrl.substringAfterLast(".").lowercase()) {
                                "mp3" -> "mp3"
                                "wav" -> "wav"
                                "m4a" -> "m4a"
                                else -> "wav"
                            }
                            addProperty("data", base64)
                            addProperty("format", format)
                        }
                        add("input_audio", inputAudioObj)
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
        LlmRequestCanonicalizer.canonicalizeTools(tools).forEach { tool ->
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

    /**
     * 整包响应解析已收敛至 com.loyea.llm.ResponseParser（Spec §12.1：
     * parser 只回答「这是什么」，降级/学习决策由 Transport 状态机承担）。
     * 此处保留兼容委托。
     */
    private fun parseChatCompletionResponse(responseBody: String): LlmResponse =
        parser.parseChatCompletionResponse(responseBody).toLlmResponse()

    internal fun interpretNonSseBody(rawBody: String): NonSseBodyOutcome =
        parser.interpretNonSseBody(rawBody)

    private fun parseUsage(responseJson: JsonObject): LlmUsage? =
        parser.parseUsage(responseJson)

    fun parseArgumentsMap(argumentsJson: String): Map<String, Any> {
        return try {
            gson.fromJson<Map<String, Any>>(argumentsJson.ifBlank { "{}" }, mapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun toJson(value: Any?): String = gson.toJson(value)

    suspend fun performIndependentWebSearch(searchProvider: String, searchApiUrl: String, searchApiKey: String, query: String): String = withContext(Dispatchers.IO) {
        if (searchApiKey.isBlank()) return@withContext "\n\n[联网搜索失败: 未配置搜索 API Key]\n\n"
        
        val baseUrl = searchApiUrl.trim().removeSuffix("/")
        val finalUrl = if (searchProvider.equals("Tavily", ignoreCase = true)) {
            "$baseUrl/search"
        } else {
            baseUrl
        }

        val requestJson = JsonObject().apply {
            addProperty("api_key", searchApiKey)
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

    /**
     * 抓取指定网页的正文内容（Agent 化浏览：搜索锁定官网/权威页后，直接读取正文细节）。
     * 仅支持 http/https；带浏览器 UA 通过基础反爬；限读 1MB 防超大页面内存尖峰；
     * Jsoup 优先提取正文容器并截断回喂，失败时返回明确错误供模型优雅降级。
     */
    suspend fun fetchWebPage(url: String): String = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return@withContext "\n\n[网页读取失败: 仅支持 http/https 链接]\n\n"
        }
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        try {
            val request = okhttp3.Request.Builder()
                .url(trimmedUrl)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "\n\n[网页读取失败: HTTP ${response.code}]\n\n"
                }
                val body = response.body ?: return@withContext "\n\n[网页读取失败: 响应为空]\n\n"
                // 限读 1MB 上限，防止超大页面整页读入造成内存尖峰
                val limitedBytes = body.byteStream().use { ins ->
                    val bos = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    val cap = 1_048_576
                    while (total < cap) {
                        val n = ins.read(buffer)
                        if (n < 0) break
                        val toWrite = minOf(n, cap - total)
                        bos.write(buffer, 0, toWrite)
                        total += toWrite
                    }
                    bos.toByteArray()
                }
                // Jsoup 直接解析字节流，自动从 BOM/meta 检测字符集（兼容 GBK 等中文站点）
                val doc = org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(limitedBytes), null, trimmedUrl)
                // 优先提取正文容器（文章/内容区），回退到 body 全文
                val mainEl = doc.selectFirst(
                    "article, main, .article, .content, .article-content, #content, .post-content, .entry-content"
                )
                val rawText = (mainEl ?: doc.body() ?: doc)?.text().orEmpty()
                val clean = rawText.replace(Regex("\\s+"), " ").trim()
                if (clean.isBlank()) {
                    return@withContext "\n\n[网页读取失败: 页面未提取到有效正文，可能为 JS 动态渲染页面]\n\n"
                }
                val excerpt = clean.take(4000)
                "\n\n=== [网页正文读取 (来源: $trimmedUrl)] ===\n$excerpt\n=========================\n\n"
            }
        } catch (e: Exception) {
            "\n\n[网页读取失败: ${e.message ?: "网络错误"}]\n\n"
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
            // 隐私红线（Spec §20）：TTS 请求体含完整朗读文本，release 禁止进日志；debug 仅记录尺寸元数据
            if (com.loyea.BuildConfig.DEBUG) {
                android.util.Log.d("LlmClient", "TTS request to $url (provider=$provider): ${requestBodyStr.length} chars, model=$targetModel, voice=$targetVoice")
            }
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
                    android.util.Log.e("LlmClient", "TTS failed! HTTP ${response.code}, body sample: ${errorMsg.trim().take(300)}")
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
        model: String,
        providerTemplate: String = "Auto"
    ): String? = withContext(Dispatchers.IO) {
        lastAsrError = null
        if (config.apiKey.isBlank()) {
            lastAsrError = "API Key 不能为空，请在设置中配置"
            return@withContext null
        }
        try {
            val effectiveProvider = if (providerTemplate == "Auto") {
                config.provider
            } else {
                providerTemplate
            }
            // 判定放宽为包含 "mimo"：兼容 "Xiaomi MiMo"、"小米 mimo" 等自定义写法，避免误走 OpenAI Multipart 端点导致 400
            val isMiMo = effectiveProvider.contains("mimo", ignoreCase = true)
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
                val base64DataBare = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                val format = when (audioFile.extension.lowercase()) {
                    "mp3" -> "mp3"
                    "wav" -> "wav"
                    "m4a" -> "mp3" // 强制伪装为 mp3 绕过 MiMo 严格网关的 m4a 校验拦截限制
                    else -> "mp3"
                }
                val mimeType = when (format) {
                    "mp3" -> "audio/mpeg"
                    "wav" -> "audio/wav"
                    else -> "audio/mpeg"
                }
                val base64Data = "data:$mimeType;base64,$base64DataBare"

                // 官方 MiMo ASR 规范（mimo.mi.com/docs/zh-CN/api/audio/Speech-Recognition）：
                // content 仅含单个 input_audio 部件（无 text 引导块）；data 为 data:{MIME};base64,... 的 data URL；
                // MIME 与 format 同时提供时两者值必须一致（400 时降级为仅 data URL 携带 MIME 重试）
                fun buildAsrJson(includeFormat: Boolean): String {
                    val json = JsonObject().apply {
                        addProperty("model", targetModel)
                        val messagesArray = JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                val contentArray = JsonArray().apply {
                                    add(JsonObject().apply {
                                        addProperty("type", "input_audio")
                                        val inputAudioObj = JsonObject().apply {
                                            addProperty("data", base64Data)
                                            if (includeFormat) {
                                                addProperty("format", format)
                                            }
                                        }
                                        add("input_audio", inputAudioObj)
                                    })
                                }
                                add("content", contentArray)
                            })
                        }
                        add("messages", messagesArray)
                    }
                    return gson.toJson(json)
                }

                fun asrRequest(jsonStr: String): okhttp3.Response {
                    val requestBody = jsonStr.toRequestBody(mediaType)
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer ${config.apiKey}")
                    if (isMiMo) {
                        requestBuilder.addHeader("api-key", config.apiKey)
                    }
                    return client.newCall(requestBuilder.post(requestBody).build()).execute()
                }

                android.util.Log.d("LlmClient", "ASR request to $url (isMiMo=true): size=${audioBytes.size} bytes, format=$format")
                // 首次按规范携带 format 字段；400 时自动降级为省略 format 重试一次（部分网关对 MIME/format 一致性校验更严格）
                var response = asrRequest(buildAsrJson(true))
                if (!response.isSuccessful && response.code == 400) {
                    response.close()
                    android.util.Log.w("LlmClient", "MiMo ASR got 400 with format field, retrying without format")
                    response = asrRequest(buildAsrJson(false))
                }

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorMsg = resp.body?.string() ?: ""
                        lastAsrError = "ASR 请求失败 HTTP ${resp.code}: ${errorMsg.trim().take(300)}"
                        android.util.Log.e("LlmClient", "ASR failed! HTTP ${resp.code}, body sample: ${errorMsg.trim().take(300)}")
                        return@withContext null
                    }
                    val resBody = resp.body?.string() ?: ""
                    val resJson = gson.fromJson(resBody, JsonObject::class.java)
                    val choices = resJson.getAsJsonArray("choices")
                    if (choices == null || choices.size() == 0) {
                        lastAsrError = "ASR 接口返回数据结构为空 choices"
                        return@withContext null
                    }
                    val content = choices.get(0).asJsonObject.getAsJsonObject("message")?.get("content")?.asString
                    if (content.isNullOrBlank()) {
                        lastAsrError = "ASR 识别文本 content 解析为空"
                    }
                    return@withContext content
                }
            } else {
                val url = resolveAudioTranscriptionsUrl(config)
                val (mimeType, ext) = when (audioFile.extension.lowercase()) {
                    "m4a" -> "audio/mp4" to "m4a"
                    "mp4" -> "audio/mp4" to "mp4"
                    "wav" -> "audio/wav" to "wav"
                    "mp3" -> "audio/mpeg" to "mp3"
                    else -> "audio/mp4" to "m4a"
                }
                val fileBody = audioFile.asRequestBody(mimeType.toMediaType())
                val finalModel = if (targetModel.isBlank()) "whisper-1" else targetModel
                
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", "audio.$ext", fileBody)
                    .addFormDataPart("model", finalModel)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .post(requestBody)
                    .build()
                
                android.util.Log.d("LlmClient", "ASR request to $url (isMiMo=false): model=$finalModel, mime=$mimeType")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorMsg = response.body?.string() ?: ""
                        lastAsrError = "ASR 请求失败 HTTP ${response.code}: ${errorMsg.trim().take(200)}"
                        android.util.Log.e("LlmClient", "ASR failed! HTTP ${response.code}, body sample: ${errorMsg.trim().take(200)}")
                        return@withContext null
                    }
                    val resBody = response.body?.string() ?: ""
                    val jsonObj = gson.fromJson(resBody, JsonObject::class.java)
                    val text = jsonObj.get("text")?.asString
                    if (text.isNullOrBlank()) {
                        lastAsrError = "ASR 识别文本 text 解析为空"
                    }
                    return@withContext text
                }
            }
        } catch (e: Exception) {
            lastAsrError = "ASR 发生异常: ${e.localizedMessage ?: e.message ?: e.toString()}"
            e.printStackTrace()
            null
        }
    }

    suspend fun generateImage(
        config: com.loyea.ui.settings.ApiConfig,
        prompt: String,
        model: String
    ): ImageGenResult = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw ImageGenException("生图 API Key 未配置 / image API key is missing")
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
                if (!response.isSuccessful) {
                    // 失败原因透出：HTTP 状态 + 错误响应体前 300 字符，让 UI 能显示真实原因
                    val errBody = runCatching { response.body?.string() }.getOrNull()
                        ?.trim()?.take(300)?.ifBlank { null }
                    throw ImageGenException("HTTP ${response.code}" + (errBody?.let { " - $it" } ?: ""))
                }
                val resBody = response.body?.string() ?: ""
                val jsonObj = runCatching { gson.fromJson(resBody, JsonObject::class.java) }.getOrNull()
                    ?: throw ImageGenException("响应不是有效 JSON / response is not valid JSON")
                val dataArray = jsonObj.getAsJsonArray("data")
                if (dataArray != null && dataArray.size() > 0) {
                    val first = dataArray.get(0).asJsonObject
                    val url = first.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!url.isNullOrBlank()) return@withContext ImageGenResult(remoteUrl = url, base64Png = null)
                    val b64 = first.get("b64_json")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!b64.isNullOrBlank()) return@withContext ImageGenResult(remoteUrl = null, base64Png = b64)
                    throw ImageGenException("响应缺少 url / b64_json 字段 / no url or b64_json field in response")
                }
                throw ImageGenException("响应中没有图片数据 / no image data in response")
            }
        } catch (e: ImageGenException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw ImageGenException(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 自动图注：让视觉模型用 ≤15 字概括图片内容（仅输出短语）。
     * 失败/超时返回 null，调用方静默跳过。
     */
    suspend fun describeImage(config: com.loyea.ui.settings.ApiConfig, imagePath: String): String? =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank()) return@withContext null
            withTimeoutOrNull(20_000L) {
                val sb = StringBuilder()
                val messages = listOf(
                    LlmChatMessage(
                        role = "user",
                        content = "用不超过 15 个字概括这张图片的内容。只输出这个短语本身，不要引号、句号或任何解释。",
                        imageUrl = imagePath
                    )
                )
                sendChatCompletionStream(config = config, messages = messages, tools = emptyList())
                    .collect { event ->
                        if (event is StreamEvent.Content) sb.append(event.text)
                    }
                sb.toString().trim().take(60).ifBlank { null }
            }
        }

    /**
     * 增量流解析（<think> 剥离 + XML 工具调用）统一实现移至 com.loyea.llm.StreamContentParser，
     * 此处保留委托避免双实现漂移。
     */
    fun parseIncrementalStreamState(fullStr: String, isDone: Boolean = false): com.loyea.llm.ParsedStreamState =
        com.loyea.llm.StreamContentParser.parseIncrementalStreamState(fullStr, isDone)

    private fun sanitizeMessages(messages: List<LlmChatMessage>, hasTools: Boolean): List<LlmChatMessage> {
        if (hasTools) return messages
        
        val result = mutableListOf<LlmChatMessage>()
        var pendingToolOutputs = java.lang.StringBuilder()
        
        messages.forEach { msg ->
            if (msg.role == "tool") {
                // 收集所有的 tool 消息，在后面非 tool 消息或结束时统一合并发射
                val toolName = msg.name ?: "unknown_tool"
                if (pendingToolOutputs.isNotEmpty()) {
                    pendingToolOutputs.append("\n\n")
                }
                pendingToolOutputs.append("[系统提示 - 感知外设 `${toolName}` 的返回结果:\n${msg.content ?: ""}]")
            } else {
                // 遇到非 tool 消息时，先将之前积累的 tool 消息以一条 user 消息形式发射，确保 role 交替
                if (pendingToolOutputs.isNotEmpty()) {
                    result.add(
                        LlmChatMessage(
                            role = "user",
                            content = pendingToolOutputs.toString()
                        )
                    )
                    pendingToolOutputs = java.lang.StringBuilder()
                }
                
                // 处理 assistant 消息中的 toolCalls 翻译为普通文本 XML
                if (msg.role == "assistant" && msg.toolCalls.isNotEmpty()) {
                    val sb = StringBuilder(msg.content ?: "")
                    msg.toolCalls.forEach { call ->
                        sb.append("\n<tool_call>${call.name}")
                        try {
                            val argsMap = parseArgumentsMap(call.argumentsJson)
                            if (argsMap.isNotEmpty()) {
                                val argsStr = argsMap.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }
                                sb.append("($argsStr)")
                            } else {
                                sb.append("()")
                            }
                        } catch (e: Exception) {
                            sb.append("()")
                        }
                        sb.append("</tool_call>")
                    }
                    result.add(msg.copy(role = "assistant", content = sb.toString(), toolCalls = emptyList()))
                } else {
                    result.add(msg)
                }
            }
        }
        
        // 扫尾：如果最后一条或几条是 tool 消息，将其合并发射
        if (pendingToolOutputs.isNotEmpty()) {
            result.add(
                LlmChatMessage(
                    role = "user",
                    content = pendingToolOutputs.toString()
                )
            )
        }
        
        return result
    }
}

