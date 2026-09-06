package com.loyea.ui.settings

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 服务商可用模型列表的云端同步（OpenAI 兼容 GET /models 约定）。
 *
 * 覆盖：DeepSeek / OpenAI / Kimi / Qwen / MiniMax / Groq / Ollama / 各类 OpenAI 兼容中转；
 * Anthropic 使用 x-api-key 头单独适配。解析按宽松顺序尝试：{"data":[{"id":…}]} →
 * {"models":[…]} → 裸字符串数组。
 */
object ModelCatalogClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 拉取模型 id 列表；失败抛异常（UI 显示 message）。 */
    suspend fun fetchModels(baseUrl: String, apiKey: String, provider: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleaned = normalizeBase(baseUrl)
                val candidates = candidateUrls(cleaned)
                var lastError: Exception? = null
                for (url in candidates) {
                    try {
                        return@runCatching fetchOnce(url, apiKey, provider)
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                throw lastError ?: IllegalStateException("no endpoint responded")
            }
        }

    /** 规范化：去尾斜杠、去尾部的 /chat/completions、/embeddings 等资源路径。 */
    internal fun normalizeBase(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        listOf("/chat/completions", "/completions", "/embeddings", "/models").forEach { suffix ->
            if (base.lowercase().endsWith(suffix)) {
                base = base.removeSuffix(suffix).trimEnd('/')
            }
        }
        return base
    }

    /** 候选端点：优先 {base}/models，未命中再试 {base}/v1/models（DeepSeek 等无 v1 前缀的服务商）。 */
    internal fun candidateUrls(normalizedBase: String): List<String> {
        val direct = "$normalizedBase/models"
        return if (normalizedBase.endsWith("/v1") || Regex("/v\\d+$").containsMatchIn(normalizedBase)) {
            listOf(direct)
        } else {
            listOf(direct, "$normalizedBase/v1/models")
        }
    }

    private fun fetchOnce(url: String, apiKey: String, provider: String): List<String> {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
        // Anthropic 的模型列表接口使用 x-api-key 鉴权
        if (provider.equals("Anthropic", ignoreCase = true) || provider.equals("Claude", ignoreCase = true)) {
            builder.header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
        }
        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}${if (body.length > 160) "" else ": ${body.take(160)}"}")
            }
            return parseModels(body)
        }
    }

    internal fun parseModels(body: String): List<String> {
        val root = JsonParser.parseString(body)
        val ids = linkedSetOf<String>()
        fun collectArray(array: com.google.gson.JsonArray) {
            array.forEach { element ->
                when {
                    element.isJsonPrimitive -> ids.add(element.asString)
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        obj.get("id")?.takeIf { it.isJsonPrimitive }?.let { ids.add(it.asString) }
                            ?: obj.get("name")?.takeIf { it.isJsonPrimitive }?.let { ids.add(it.asString) }
                            ?: obj.get("model")?.takeIf { it.isJsonPrimitive }?.let { ids.add(it.asString) }
                    }
                }
            }
        }
        when {
            root.isJsonObject -> {
                val obj = root.asJsonObject
                obj.get("data")?.takeIf { it.isJsonArray }?.let { collectArray(it.asJsonArray) }
                    ?: obj.get("models")?.takeIf { it.isJsonArray }?.let { collectArray(it.asJsonArray) }
            }
            root.isJsonArray -> collectArray(root.asJsonArray)
        }
        return ids.filter { it.isNotBlank() }.sorted()
    }
}
