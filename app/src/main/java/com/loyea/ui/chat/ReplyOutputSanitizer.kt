package com.loyea.ui.chat

/**
 * 清理只应该存在于 provider prompt 中的内部元数据，避免模型回显后进入用户可见回复。
 *
 * 流式响应会把一个标签拆成多个 SSE 片段，因此除了移除完整标签，还要暂时隐藏尾部
 * 尚未闭合的 `[MESSAGE TIME ...` 片段；下一批文字到达后，完整标签会一次性被移除。
 */
object ReplyOutputSanitizer {

    // 惰性编译：类加载期（object <clinit>）永不接触 Android/ICU 正则引擎，避免在旧设备上
    // 因 ICU 拒绝模式而抛 ExceptionInInitializerError。首次使用若仍失败，由 sanitize 的
    // runCatching 兜底为“原样返回”，流式输出不受影响。
    private val messageTimeMetadataRegex by lazy {
        Regex(
            """\[MESSAGE\s+TIME\s*:[^\]\r\n]*\]""",
            RegexOption.IGNORE_CASE
        )
    }
    private val messageTimePrefixRegex by lazy {
        Regex(
            """\[MESSAGE\s+TIME\s*:""",
            RegexOption.IGNORE_CASE
        )
    }

    fun sanitize(text: String): String {
        return runCatching {
            sanitizeInternal(text)
        }.getOrDefault(text)
    }

    private fun sanitizeInternal(text: String): String {
        val withoutCompleteMetadata = text.replace(messageTimeMetadataRegex, "")
        val markerStart = messageTimePrefixRegex.findAll(withoutCompleteMetadata)
            .lastOrNull()
            ?.range
            ?.first
            ?: -1
        val lastClosingBracket = withoutCompleteMetadata.lastIndexOf(']')

        return if (markerStart > lastClosingBracket) {
            withoutCompleteMetadata.substring(0, markerStart)
        } else {
            withoutCompleteMetadata
        }
    }
}
