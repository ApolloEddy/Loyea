package com.loyea.plugin.reader

/**
 * 伴读上下文管线（Reader Spec §6/§7/§8 的粘合层）。
 *
 * 职责分离：
 * - ingest：采样块 → 提纯 → 章节缓冲；提纯后的全部块视为用户已读
 *   （它们曾出现在屏幕上），直接喂滚动摘要与实体卡；切章时重置防剧透游标。
 * - markVisible：推进提示词可见游标——防剧透屏障只约束"给模型的上下文"，
 *   不约束摘要/实体卡的累积。
 */
class ReaderContextPipeline(
    private val purifier: ReaderPurifierSession = ReaderPurifierSession(),
    private val buffer: ReaderChapterBuffer = ReaderChapterBuffer(),
    private val summary: RollingSummary = RollingSummary(),
    private val entities: EntityCards = EntityCards(),
    /** 采样最小字数闸（真实页 200；测试夹具可传 0）。 */
    private val minSampleChars: Int = 200,
) {

    var bookTitle: String = "这本书"
        private set

    /** 采样入口：原始文本块 → 管线。返回本次提纯后的段落（未构成有效样本时为空表）。 */
    fun ingest(rawBlocks: List<String>, chapterKey: String?): List<String> {
        val purified = purifier.purify(rawBlocks)
        if (ReaderTextPurifier.isTrivialSample(purified, minSampleChars)) return emptyList()
        val switched = buffer.ingest(purified, chapterKey)
        // 已读内容进入滚动摘要与实体卡（它们描述"读到哪里了"，天然不含未读）
        if (purified.isNotEmpty()) {
            println("PIPE purified=" + purified + " priorSummary=<" + summary.summaryText() + ">")
            summary.ingest(purified)
            entities.ingest(purified)
            println("PIPE afterSummary=<" + summary.summaryText() + "> entities=" + entities.names())
        }
        return if (switched) purified else emptyList()
    }

    /** 可视区推进（服务在采样后调用）：只影响提示词可见游标。 */
    fun markVisible(index: Int) = buffer.markVisible(index)

    fun visibleContext(): List<String> = buffer.visibleContext()

    fun summaryText(): String = summary.summaryText()

    fun entities(): List<EntityCards.Card> = entities.cardsList()

    fun chapterKey(): String = buffer.currentKey

    /** 当前章节的总段落数。 */
    fun blockCount(): Int = buffer.currentBlocks.size

    /** 防剧透游标（测试与诊断用）。 */
    fun visibleCursor(): Int = buffer.visibleCursor

    fun setBookTitle(title: String) {
        if (title.isNotBlank()) bookTitle = title
    }

    fun reset() {
        purifier.reset()
        buffer.clear()
        summary.reset()
        entities.reset()
        bookTitle = "这本书"
    }
}
