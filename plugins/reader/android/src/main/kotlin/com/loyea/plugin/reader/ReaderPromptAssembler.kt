package com.loyea.plugin.reader

/**
 * 伴读提示词组装器（Reader Spec §8）。
 *
 * 组装顺序固定"最稳定 → 最易变"，保证 provider 侧前缀缓存命中：
 *   [稳定 System] 陪伴 Soul 段 + 伴读角色段（逐字不变）
 *   [半稳定]      书名/章节键（每章不变）
 *   [易变]        滚动摘要 + 实体卡 + 可视上下文（防剧透截取）
 * 防剧透屏障：visibleContext 由 ReaderChapterBuffer 物理截取，这里不得绕过。
 */
object ReaderPromptAssembler {

    /** 稳定 System 前缀（逐字恒定；任何改动都会使缓存全量失效）。 */
    fun stableSystem(): String = """
        你是 Loyea，用户身边长期的陪伴者。现在你正在和用户一起读书。
        伴读守则：你只知道读者已经读到的内容；引用原文每次不超过两句；
        每次发言不超过三句；不总结、不剧透未读部分；不假装读过整本书；
        用户问后面的情节时，温和说明"还不能偷看后面"。
    """.trimIndent()

    /** 半稳定层：书与章节上下文（章节内恒定）。 */
    fun bookLayer(bookTitle: String, chapterKey: String): String =
        "[READING / 正在读]\n书名：$bookTitle\n当前章节：$chapterKey"

    /** 易变层：滚动摘要 + 实体卡 + 可视上下文。 */
    fun dynamicLayer(
        summary: String,
        entities: List<Pair<String, String>>,
        visibleBlocks: List<String>,
        maxContextChars: Int = 1500,
    ): String = buildString {
        append("[READ-SO-FAR / 已读部分]\n")
        if (summary.isNotBlank()) {
            append(summary)
            append('\n')
        }
        if (entities.isNotEmpty()) {
            append("[CHARACTERS / 已出场]\n")
            entities.take(12).forEach { (name, firstSeen) ->
                append("$name：$firstSeen\n")
            }
        }
        append("[CURRENT TEXT / 当前可见]\n")
        // 从最新往回保留，恢复正序输出；预算内物理截取（防剧透由调用方保证）
        val keptList = ArrayDeque<String>()
        var budget = maxContextChars
        for (block in visibleBlocks.asReversed()) {
            if (budget - block.length < 0) break
            keptList.addFirst(block)
            budget -= block.length + 1
        }
        keptList.forEach { append(it).append('\n') }
    }.trimEnd()

    /** 完整动态模块（供 LlmChatMessage 组装）。 */
    fun dynamicModule(
        bookTitle: String,
        chapterKey: String,
        summary: String,
        entities: List<Pair<String, String>>,
        visibleBlocks: List<String>,
    ): String = buildString {
        append(bookLayer(bookTitle, chapterKey))
        append('\n')
        append(dynamicLayer(summary, entities, visibleBlocks))
    }

    /** 防剧透拒绝模板（用户问"后面写了什么"）。 */
    fun spoilerRefusal(): String =
        "我不能偷看后面的内容；不过刚读到的部分，我们可以随便聊。"
}
