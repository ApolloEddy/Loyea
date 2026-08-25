package com.loyea.plugins.tavern.core

import java.net.URI
import java.util.Locale

/**
 * 一个已解析的角色卡来源。
 *
 * @param host    识别出的主机（规范化小写，不含端口），如 "www.chub.ai" 或 "aicharactercards.com"。
 * @param sourceId 用于请求的角色卡 ID（chub 为路径中的 id；aicharactercards 暂不支持时为空串）。
 * @param apiUrl  可直接请求的 API 端点；当宿主尚未支持该 host 时为空串。
 * @param displayName 面向用户展示的名称。
 */
data class TavernCardSource(
    val host: String,
    val sourceId: String,
    val apiUrl: String,
    val displayName: String
)

/**
 * A3 角色卡 URL 源解析器（纯字符串 / 正则规则，不含任何网络 I/O）。
 *
 * 网络由宿主注入；本对象只负责把用户粘贴的角色卡链接识别为可请求的 API 地址，
 * 与后续 B1 网络下载模块保持解耦（仅约定 apiUrl 语义）。任何失败 / 无法识别的
 * 输入都会返回 null，绝不抛异常。
 */
object TavernCardUrlResolver {

    /** chub 主域名（去 www 归一化后参与匹配，如 chub.ai）。 */
    private val chubBaseHosts: Set<String> = setOf("chub.ai")

    /** aicharactercards 域名（仅用于 host 识别）。 */
    private const val aicharacterHost = "aicharactercards.com"

    /** chub 角色页路径前缀：`characters` 必须是完整路径段（后面跟着 / 或路径结束），大小写不敏感。 */
    private val chubCharactersPrefixRegex = Regex("""^/characters(/|$)""", RegexOption.IGNORE_CASE)

    /** chub 角色 id 允许的字符白名单（字母、数字、下划线、短横线、点）。 */
    private val chubIdAllowed = Regex("""[A-Za-z0-9_.-]+""")

    /**
     * 所有本解析器可识别（至少能做 host 判定）的主机集合。
     */
    fun supportedHosts(): Set<String> = chubBaseHosts + chubBaseHosts.map { "www.$it" } + aicharacterHost

    /**
     * 解析输入链接。
     *
     * - chub.ai / www.chub.ai：从 `/characters/<id>` 提取 id 并生成
     *   `https://www.chub.ai/api/characters/<id>` API 地址。
     * - aicharactercards.com：仅识别 host，apiUrl 为空串、displayName 注明"需宿主扩展"。
     *
     * @return 无法解析（非法 URL / 未知 host / 无角色 id）时返回 null。
     */
    fun resolve(inputUrl: String): TavernCardSource? {
        val url = inputUrl.trim()
        if (url.isEmpty()) return null

        val rawHost = parseHost(url) ?: return null
        // 去 www. 前缀归一化，使 www.chub.ai 与 chub.ai、www.aicharactercards.com 与主域名等价。
        val host = rawHost.removePrefix("www.")
        when (host) {
            in chubBaseHosts -> return resolveChub(url)
            aicharacterHost -> return resolveAicharacter()
            else -> return null
        }
    }

    private fun resolveChub(url: String): TavernCardSource? {
        val path = extractPath(url) ?: return null

        // 提取 /characters/<id>，仅容忍尾随斜杠；id 本身绝不能含路径分隔符。
        val rest = chubCharactersPrefixRegex.find(path)?.let { path.removeRange(0, it.value.length) }
            ?: return null
        val idCandidate = rest.trimEnd('/')
        if (!isValidChubId(idCandidate)) return null

        val apiUrl = "https://www.chub.ai/api/characters/$idCandidate"
        return TavernCardSource(
            host = "www.chub.ai",
            sourceId = idCandidate,
            apiUrl = apiUrl,
            displayName = "chub.ai 角色卡 / $idCandidate"
        )
    }

    private fun resolveAicharacter(): TavernCardSource =
        TavernCardSource(
            host = aicharacterHost,
            sourceId = "",
            apiUrl = "",
            displayName = aicharacterHost + " 需宿主扩展"
        )

    /** 校验 chub 角色 id：非空、无路径分隔符、仅含白名单字符。 */
    private fun isValidChubId(id: String): Boolean {
        if (id.isEmpty()) return false
        if (id.contains('/') || id.contains('\\')) return false
        return chubIdAllowed.matches(id)
    }

    /**
     * 解析输入串的主机（小写、去端口），仅接受以 http(s) 开头或裸域名。
     * 非法 URI 返回 null，绝不抛异常。
     */
    private fun parseHost(raw: String): String? = runCatching {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || looksLikeBareHost(lower))) {
            return null
        }
        val withScheme = if (looksLikeBareHost(lower)) "https://$trimmed" else trimmed
        URI(withScheme).host?.lowercase(Locale.ROOT)
    }.getOrNull()

    /** 不含协议前缀、以合法域名骨架开头的裸主机（如 "chub.ai/characters/xxx"）。 */
    private fun looksLikeBareHost(lower: String): Boolean =
        lower.matches(Regex("""^[A-Za-z0-9._-]+(:(\d+))?/""")) || lower.matches(Regex("""^[A-Za-z0-9._-]+(:(\d+))?$"""))

    /** 提取原始大小写的路径（不含 query / fragment），取不到返回 null。 */
    private fun extractPath(raw: String): String? = runCatching {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val withScheme = if (looksLikeBareHost(lower)) "https://$trimmed" else trimmed
        URI(withScheme).path ?: ""
    }.getOrNull()
}