package com.loyea.plugin.reader

/**
 * 气泡问答的提示词组装（Reader Spec §9 对话形态）。
 *
 * - 用户问题作为独立 user 消息发送（不污染滚动摘要）。
 * - 防剧透屏障：用户问题命中"后面/结局/剧透"等意图词时，直接返回拒绝模板，
 *   不发起网络请求（Spec §7 硬规则 + §9 拒绝模板）。
 */
object ReaderBubbleChat {

    private val SPOILER_INTENT = Regex("后面|结局|还没读|接下来|下章|剧透|先说|提前")

    /** 返回 null = 允许发往 LLM；非 null = 直接以该文本回复（本地拒绝）。 */
    fun localRefusal(question: String, antiSpoilerEnabled: Boolean): String? {
        if (!antiSpoilerEnabled) return null
        if (SPOILER_INTENT.containsMatchIn(question)) {
            return ReaderPromptAssembler.spoilerRefusal()
        }
        return null
    }
}
