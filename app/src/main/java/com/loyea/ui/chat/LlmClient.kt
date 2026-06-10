package com.loyea.ui.chat

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 远程 LLM 服务响应实体
 */
data class LlmResponse(
    val content: String,
    val thoughts: String? = null,
    val isError: Boolean = false
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

    /**
     * 发送 Chat Completion 对话请求
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

            val requestJson = JsonObject().apply {
                addProperty("model", config.modelName)
                add("messages", gson.toJsonTree(chatHistory))
            }

            val requestBody = gson.toJson(requestJson).toRequestBody(mediaType)
            
            // 智能补全 completions 请求地址路由
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
                        // 尝试提取 JSON 中的错误描述
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

                // 解析标准的 OpenAI Chat Completions 响应结构
                val responseJson = gson.fromJson(responseBody, JsonObject::class.java)
                val choices = responseJson.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) {
                    return@withContext LlmResponse(
                        content = "[错误] 未能从接口解析出有效文本选择支，服务器输出：$responseBody",
                        isError = true
                    )
                }

                val messageObj = choices.get(0).asJsonObject.getAsJsonObject("message")
                val rawContent = messageObj?.get("content")?.asString ?: ""
                val reasoningContent = messageObj?.get("reasoning_content")?.asString

                var finalThoughts: String? = null
                var finalContent = rawContent

                // 1. 优先提取专门的 reasoning_content 推理链字段 (如 DeepSeek R1 官方标准)
                if (!reasoningContent.isNullOrBlank()) {
                    finalThoughts = reasoningContent
                }

                // 2. 备选方案：提取 content 内嵌的 <think>...</think> 文本块 (中转渠道或微调模型常用)
                val thinkRegex = Regex("<think>([\\s\\S]*?)</think>")
                val matchResult = thinkRegex.find(rawContent)
                if (matchResult != null) {
                    if (finalThoughts == null) {
                        finalThoughts = matchResult.groupValues[1].trim()
                    }
                    // 过滤掉思考标签及其中间文本，保留正文回答
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
}
