package com.loyea.ui.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.plugins.tavern.core.TavernCardSource
import com.loyea.plugins.tavern.core.TavernCardUrlResolver
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * B1：角色卡 URL 下载宿主能力（网络字节获取 + 轻量实体提取）。
 *
 * 本类只负责：
 * 1. 通过注入的 [fetchBytes] 取回原始字节（网络 I/O 可注入，便于单测，见 [downloadCard]）；
 * 2. 施加 1MB 体积上限；
 * 3. 对 chub.ai 这类返回包装 JSON 的端点做"宽松实体提取"，取不到则原样返回字节。
 *
 * 它**不做**完整的 V1/V2/V3 解析——字节拿到后交给现有的
 * [com.loyea.plugins.tavern.core.TavernCardCodec]（V1/V2/V3 JSON / PNG）继续导入。
 * 因此即便提取失败，原始字节也会保留在 [TavernDownloadResult.Success.rawBytes] 中以便兜底。
 */
object TavernCardDownloader {

    /** 下载体量上限，超过即返回 [TavernDownloadFailure.TOO_LARGE]，避免无节制拉取进内存。 */
    const val MAX_DOWNLOAD_BYTES = 1 * 1024 * 1024

    /** 默认同步 GET 的连接 / 读超时（秒）。 */
    private const val TIMEOUT_SECONDS = 15L

    /** 简洁 User-Agent；部分站点会拦截默认 OkHttp UA。 */
    private const val USER_AGENT = "Loyea/0.1 (+https://loyea.app)"

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载结果。
     *
     * - [Success.rawBytes]：始终携带原始响应字节，供 [TavernCardCodec] 后续完整导入。
     * - [Success.cardJson]：若能从响应中宽松提取到角色卡 JSON 文本则非空，否则为 null（原样兜底）。
     * - [Success.description]：顺带解析出的内容描述（当前为角色名），仅作轻量展示，不参与导入。
     */
    sealed interface TavernDownloadResult {
        data class Success(
            val rawBytes: ByteArray,
            val cardJson: String?,
            val description: String?
        ) : TavernDownloadResult

        data class Failure(val reason: TavernDownloadFailure) : TavernDownloadResult
    }

    /** 下载失败的原因枚举。 */
    enum class TavernDownloadFailure { INVALID_URL, NETWORK, PARSE, TOO_LARGE }

    /**
     * 默认网络实现：OkHttp 同步 GET。
     *
     * 这是本类中唯一依赖 OkHttp 的地方；业务逻辑全部通过注入的 [fetchBytes] 运行，
     * 因此测试不再触碰真实网络。非 2xx 视为网络层失败并抛出 [IOException]。
     */
    val defaultFetchBytes: (url: String) -> ByteArray = { url ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        defaultClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} for $url")
            }
            resp.body?.bytes() ?: throw IOException("Empty body for $url")
        }
    }

    /**
     * 便捷入口：先解析 URL，再进行下载。
     *
     * URL 无法被 [TavernCardUrlResolver.resolve] 识别（非法 URL / 未知 host / 缺 id）
     * 即返回 [TavernDownloadFailure.INVALID_URL]。
     */
    fun downloadFromUrl(
        url: String,
        fetchBytes: (url: String) -> ByteArray,
    ): TavernDownloadResult {
        val source = TavernCardUrlResolver.resolve(url) ?: return TavernDownloadResult.Failure(TavernDownloadFailure.INVALID_URL)
        return downloadCard(source, fetchBytes)
    }

    /**
     * 核心下载函数。
     *
     * 流程：
     * 1. 校验来源 API 地址（空白视为无效来源）；
     * 2. 调 [fetchBytes] 取字节（异常统一映射为 [TavernDownloadFailure.NETWORK]）；
     * 3. 施加 1MB 上限；
     * 4. 对 chub 响应做宽松实体提取；取不到时原样返回字节（rawBytes 仍可用于完整导入）。
     */
    fun downloadCard(
        source: TavernCardSource,
        fetchBytes: (url: String) -> ByteArray,
    ): TavernDownloadResult {
        val apiUrl = source.apiUrl.trim()
        if (apiUrl.isEmpty()) {
            return TavernDownloadResult.Failure(TavernDownloadFailure.INVALID_URL)
        }

        val bytes = try {
            fetchBytes(apiUrl)
        } catch (_: Exception) {
            return TavernDownloadResult.Failure(TavernDownloadFailure.NETWORK)
        }

        if (bytes.size > MAX_DOWNLOAD_BYTES) {
            return TavernDownloadResult.Failure(TavernDownloadFailure.TOO_LARGE)
        }

        val text = String(bytes, StandardCharsets.UTF_8)
        val cardJson = extractCharacterJson(text)
        val description = cardJson?.let(::parseCharacterName)
        return TavernDownloadResult.Success(
            rawBytes = bytes,
            cardJson = cardJson,
            description = description
        )
    }

    /**
     * 宽松提取 chub 响应中的 `character` 字段文本（纯函数，可单测）。
     *
     * 输入一段 JSON 字符串，输出：
     * - 顶层 `character` 为对象 / 数组：返回其 JSON 文本；
     * - 顶层 `character` 为字符串：先尝试作为 JSON 解析；不合法则尝试按 base64 解码后再作 JSON
     *   （覆盖"character 值为含 base64 的字段"的形态）；
     * - 找不到 / 解析失败 / 形态非法：返回 null，由调用方原样兜底。
     */
    fun extractCharacterJson(json: String): String? {
        if (json.isBlank()) return null
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return null
            val character = root.asJsonObject["character"] ?: return null
            when {
                character.isJsonObject || character.isJsonArray ->
                    character.toString()
                character.isJsonPrimitive -> extractStringPrimitive(character.asString)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 处理 `character` 为字符串的形态：先按 JSON（仅接受对象/数组，避免裸字符串误判），再按 base64。 */
    private fun extractStringPrimitive(s: String): String? {
        if (s.isBlank()) return null
        // 形态一：字符串本身就是 JSON 对象 / 数组（Gson 会把任意裸字符串解析成字符串字面量，
        // 因此必须限定类型为对象/数组，否则 "just-plain-text" 这类文本会被误判为卡片）。
        runCatching { JsonParser.parseString(s) }
            .getOrNull()
            ?.takeIf { it.isJsonObject || it.isJsonArray }
            ?.let { return it.toString() }
        // 形态二：base64 编码后解码再作 JSON 解析（如含 base64 的角色卡数据）。
        runCatching {
            val decoded = Base64.getDecoder().decode(s)
            val text = String(decoded, StandardCharsets.UTF_8)
            val el = JsonParser.parseString(text)
            if (el.isJsonObject || el.isJsonArray) return text
        }
        return null
    }

    /** 从提取出的卡片 JSON 里轻量取角色名，用于描述；取不到返回 null，绝不抛异常。 */
    private fun parseCharacterName(cardJson: String): String? = try {
        val el = JsonParser.parseString(cardJson)
        (el as? JsonObject)?.let { obj ->
            obj["name"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
    } catch (_: Exception) {
        null
    }
}