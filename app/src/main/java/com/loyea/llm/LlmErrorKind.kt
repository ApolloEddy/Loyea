package com.loyea.llm

/**
 * 统一内部错误类型（Spec §12.2）。
 * UI 可继续显示中文自然语言；业务逻辑禁止依赖 message.contains("流式") 之类字符串判断。
 */
sealed interface LlmErrorKind {
    data object Authentication : LlmErrorKind
    data object RateLimit : LlmErrorKind
    data object Quota : LlmErrorKind
    data object InvalidRequest : LlmErrorKind
    data object UnsupportedStreaming : LlmErrorKind
    data object UnsupportedTools : LlmErrorKind
    data object UnsupportedSearch : LlmErrorKind
    data object UnsupportedModality : LlmErrorKind
    data object Timeout : LlmErrorKind
    data object Network : LlmErrorKind
    data object Server : LlmErrorKind
    data object MalformedResponse : LlmErrorKind
    data object EmptyResponse : LlmErrorKind
    data object ConfigurationMissing : LlmErrorKind
    data object ConfigurationDisabled : LlmErrorKind
    data object StorageFailure : LlmErrorKind
    data object Unknown : LlmErrorKind
}

/**
 * 错误分类器：把 HTTP 码 / 服务商结构化错误体归入 [LlmErrorKind]。
 *
 * Spec §8.5 纪律：`UnsupportedStreaming` 只能来自「结构化 error + 明确语义」，
 * 不得仅凭空 body / HTML / JSON 解析失败 / timeout / connection reset 判定。
 * provider error code + message 文本匹配只作为辅助手段，且必须作用于已确认为
 * 结构化错误（JSON error 对象或明确错误文案）的输入。
 */
object ErrorClassifier {

    /** 不可自动重试的 5xx：501 Not Implemented 属能力缺失，非瞬时故障。 */
    private val NON_RETRYABLE_5XX = setOf(501)

    fun fromHttpCode(code: Int): LlmErrorKind = when {
        code == 401 || code == 403 -> LlmErrorKind.Authentication
        code == 402 -> LlmErrorKind.Quota
        code == 429 -> LlmErrorKind.RateLimit
        code in 400..499 -> LlmErrorKind.InvalidRequest
        code in 500..599 -> LlmErrorKind.Server
        else -> LlmErrorKind.Unknown
    }

    fun isRetryableHttp(code: Int): Boolean =
        code == 429 || (code in 500..599 && code !in NON_RETRYABLE_5XX)

    /**
     * 对已确认为结构化错误的 message 做能力缺失细分。
     * 输入约定：调用方已确认这是服务商返回的错误文案（HTTP error 体 / 200 error JSON /
     * SSE error 事件），而不是任意响应文本。
     */
    fun classifyStructuredError(message: String): LlmErrorKind {
        val m = message.lowercase()
        return when {
            mentionsUnsupported(m, listOf("tool", "function call", "工具调用", "function.call")) -> LlmErrorKind.UnsupportedTools
            mentionsUnsupported(m, listOf("web_search", "enable_search", "search tool", "联网搜索")) -> LlmErrorKind.UnsupportedSearch
            isExplicitStreamUnsupported(message) -> LlmErrorKind.UnsupportedStreaming
            mentionsUnsupported(m, listOf("image", "vision", "audio", "multimodal", "多模态", "图片", "语音")) -> LlmErrorKind.UnsupportedModality
            else -> LlmErrorKind.Unknown
        }
    }

    /**
     * 明确的「不支持流式」判定（Spec §8.5）。
     * 必须同时出现流式词与否定语义，避免把 "invalid stream parameter" 之类参数错误误判。
     */
    fun isExplicitStreamUnsupported(message: String): Boolean {
        val m = message.lowercase()
        val mentionsStream = m.contains("stream") || message.contains("流式")
        if (!mentionsStream) return false
        val negations = listOf(
            "not support", "unsupported", "does not support", "doesn't support",
            "not allowed", "not available", "disabled", "only supports non-stream",
            "not implemented", "无法使用流式", "不支持", "不支持流式", "流式不支持", "不支持流",
            "仅支持非流式", "暂不支持"
        )
        if (negations.any { m.contains(it) }) return true
        // 中文否定词不在 lowercase 结果里出现，需对原文再查一遍
        return listOf("不支持", "无法", "暂不支持", "仅支持非流式").any { message.contains(it) }
    }

    private fun mentionsUnsupported(m: String, nouns: List<String>): Boolean {
        val negations = listOf(
            "not support", "unsupported", "does not support", "doesn't support",
            "not allowed", "not available", "disabled", "not implemented",
            "不支持", "无法使用"
        )
        return nouns.any { m.contains(it) } && negations.any { m.contains(it) }
    }
}
