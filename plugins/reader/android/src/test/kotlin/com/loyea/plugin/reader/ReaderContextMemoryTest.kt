package com.loyea.plugin.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D2-M2：滚动摘要 / 实体卡 / 上下文管线（Reader Spec §6/§7）。 */
class ReaderContextMemoryTest {

    private fun paragraph(text: String, repeat: Int = 1): String = text.repeat(repeat)

    /** 长度闸填充：让夹具总量 ≥200 字（模拟真实书页样本）。 */
    private fun pad(): List<String> = (1..6).map {
        "填充段落$it：这是为了满足采样长度闸门的最小字符要求，没有别的含义。"
    }

    // ------------------------------------------------------------------
    // RollingSummary
    // ------------------------------------------------------------------

    @Test
    fun summaryExtractsFirstSentencesPerBlock() {
        val blocks = listOf(
            "主角清晨出门，天上飘着小雨。他没带伞。",
            "半路上遇到旧友，两人寒暄了几句。",
        )
        val text = ReaderRollingSummaryHelper.summarizeForTest(blocks)
        assertTrue(text.contains("主角清晨出门，天上飘着小雨。"))
        assertTrue(text.contains("半路上遇到旧友，两人寒暄了几句。"))
    }

    @Test
    fun summaryIsCappedAtBudget() {
        // 10 段 × 120 字 ≈ 1200 字；预算 200 字 → 摘要必须被裁剪
        val blocks = (1..10).map { "第${it}段的事情发生了。" + "细节".repeat(55) }
        val text = ReaderRollingSummaryHelper.summarizeForTest(blocks, maxChars = 200)
        assertTrue("summary should be capped, got ${text.length}", text.length <= 220)
    }

    @Test
    fun summarySlidingWindowDropsOldest() {
        val rs = RollingSummary(ingestThresholdChars = 0, maxChars = 100)
        rs.ingest(listOf("开头铺垫内容非常非常长，几乎占据了全部摘要预算的空间，用来验证滑动窗口的丢弃逻辑。"))
        rs.ingest(listOf("结尾的新事件：主角找到了钥匙。"))
        val text = rs.summaryText()
        assertTrue("最新摘要必须保留", text.contains("主角找到了钥匙"))
        assertFalse("超预算的最旧句应被丢弃", text.contains("开头的一句很长很长的铺垫"))
    }

    // ------------------------------------------------------------------
    // EntityCards
    // ------------------------------------------------------------------

    @Test
    fun entitiesExtractSpeakerNamesWithFirstSeen() {
        val cards = EntityCards()
        cards.ingest(listOf("林小雨说道：“今天风好大。”"))
        val list = cards.cardsList()
        assertTrue(list.any { it.name == "林小雨" })
        val card = list.first { it.name == "林小雨" }
        assertTrue(card.firstSeen.contains("林小雨"))
        assertTrue(card.firstSeen.length <= 62)
    }

    @Test
    fun entitiesIgnorePronounPrefixedAndDuplicates() {
        val cards = EntityCards()
        cards.ingest(listOf("这是他说的话。", "林小雨说道：“今天真开心。”", "林小雨又说了一句话。"))
        val names = cards.names()
        assertTrue("林小雨" in names)
        assertFalse(names.any { it.startsWith("这是") })
        assertEquals(1, names.size)
    }

    @Test
    fun entitiesCapAtMax() {
        val cards = EntityCards(maxEntities = 3)
        val blocks = listOf(
            "王小明说道：“一句话。”",
            "李铁柱说道：“一句话。”",
            "赵子龙说道：“一句话。”",
            "公孙策说道：“一句话。”",
        )
        cards.ingest(blocks)
        assertEquals(3, cards.cardsList().size)
    }

    // ------------------------------------------------------------------
    // Pipeline：缓冲 + 摘要 + 实体 + 防剧透
    // ------------------------------------------------------------------

    @Test
    fun pipelineFeedsOnlyVisibleBlocksToSummaryAndEntities() {
        val pipe = ReaderContextPipeline(
            summary = RollingSummary(ingestThresholdChars = 0), minSampleChars = 0)
        pipe.ingest(
            listOf("第一章", "林小雨说道：她今天不来了。", "这句话还没被读到，藏着未来的秘密。"),
            chapterKey = "第一章",
        )
        // 采样 = 已读：提纯后的块全部进入摘要与实体
        println("T1 summary=<" + pipe.summaryText() + "> entities=" + pipe.entities().map { it.name })
        assertTrue(pipe.summaryText().contains("林小雨"))
        assertTrue(pipe.entities().any { it.name == "林小雨" })

        // 提示词层的防剧透屏障：markVisible 之前可见上下文为空
        assertTrue(pipe.visibleContext().isEmpty())
        pipe.markVisible(2)
        val visible = pipe.visibleContext()
        assertFalse(visible.any { it.contains("未来的秘密") } && visible.size > 3)
        // 可见上下文进入组装器；解释性文字与"已读"摘要并存
        val module = ReaderPromptAssembler.dynamicModule(
            bookTitle = "测试之书",
            chapterKey = pipe.chapterKey(),
            summary = pipe.summaryText(),
            entities = pipe.entities().map { it.name to it.firstSeen },
            visibleBlocks = visible,
        )
        assertTrue(module.contains("林小雨"))
        assertFalse(module.contains("calibratedProbability"))
    }

    @Test
    fun pipelineChapterSwitchKeepsSummaryButResetsCursor() {
        val pipe = ReaderContextPipeline(
            summary = RollingSummary(ingestThresholdChars = 0), minSampleChars = 0)
        pipe.setBookTitle("测试之书")
        pipe.ingest(listOf("第一章", "林小雨说道：她今天不来了。"), chapterKey = "第一章")
        pipe.markVisible(1)
        val summaryBefore = pipe.summaryText()
        println("T2 summaryBefore=<" + summaryBefore + ">")
        assertTrue(summaryBefore.isNotEmpty())

        pipe.ingest(listOf("第二章", "新的内容开始了。") + pad(), chapterKey = "第二章")
        assertEquals(-1, pipe.visibleCursor())
        // 摘要跨章延续（新内容追加，不丢已读摘要）
        assertTrue(pipe.summaryText().contains(summaryBefore.take(12)))
    }

    @Test
    fun pipelineFeedsAssembler() {
        val pipe = ReaderContextPipeline(
            summary = RollingSummary(ingestThresholdChars = 0), minSampleChars = 0)
        pipe.setBookTitle("测试之书")
        pipe.ingest(listOf("第一章", "林小雨说道：她今天不来了。"), chapterKey = "第一章")
        pipe.markVisible(1)
        val module = ReaderPromptAssembler.dynamicModule(
            bookTitle = pipe.bookTitle,
            chapterKey = pipe.chapterKey(),
            summary = pipe.summaryText(),
            entities = pipe.entities().map { it.name to it.firstSeen },
            visibleBlocks = pipe.visibleContext(),
        )
        println("T3 module=<" + module + ">")
        assertTrue(module.contains("测试之书"))
        assertTrue(module.contains("林小雨"))
        assertFalse(module.contains("calibratedProbability"))
    }
}

/** 测试辅助：无状态摘要入口的直调（避免测试依赖阈值语义）。 */
private object ReaderRollingSummaryHelper {
    fun summarizeForTest(blocks: List<String>, maxChars: Int = 600): String =
        RollingSummary.summarize(blocks, maxChars = maxChars)
}
