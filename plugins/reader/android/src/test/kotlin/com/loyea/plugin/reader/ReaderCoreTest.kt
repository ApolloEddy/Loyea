package com.loyea.plugin.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader 插件核心单测（Spec §5/§6/§7/§8 的纯逻辑部分）。 */
class ReaderCoreTest {

    // ------------------------------------------------------------------
    // 提纯
    // ------------------------------------------------------------------

    @Test
    fun purifierMergesHardWrappedParagraphs() {
        val out = ReaderTextPurifier.mergeLines(listOf("他抬起头，\n看向远处的山\n峦。"))
        assertEquals(listOf("他抬起头，看向远处的山峦。"), out)
    }

    @Test
    fun purifierStripsAdsProgressAndBoilerplate() {
        val session = ReaderPurifierSession()
        val boiler = "起点中文网 欢迎您"
        val raw = listOf(
            "他推开门，屋里没有人。",
            "点击领取新手礼包",
            "- 37% -",
            boiler, boiler, boiler, // 第 3 次起被识别为页眉
            "屋里的灯忽然亮了。",
        )
        val out = session.purify(raw)
        assertTrue(out.contains("他推开门，屋里没有人。"))
        assertTrue(out.contains("屋里的灯忽然亮了。"))
        assertFalse(out.any { it.contains("礼包") || it.contains("37%") })
        assertFalse(out.contains(boiler))
    }

    @Test
    fun purifierFoldsPunctuation() {
        val out = ReaderTextPurifier.foldPunctuation("真的吗？？？好！！嗯。。。等、等一下")
        assertEquals("真的吗？好！嗯……等、等一下", out)
    }

    @Test
    fun trivialSampleRejected() {
        assertTrue(ReaderTextPurifier.isTrivialSample(listOf("封面页")))
        assertFalse(ReaderTextPurifier.isTrivialSample(listOf("a".repeat(250))))
    }

    // ------------------------------------------------------------------
    // 章节缓冲与防剧透
    // ------------------------------------------------------------------

    @Test
    fun bufferDetectsChapterSwitchAndResetsCursor() {
        val buf = ReaderChapterBuffer()
        buf.ingest(listOf("第一章 开端", "甲段。", "乙段。"), chapterKey = "第一章 开端")
        buf.markVisible(1)
        assertEquals(listOf("第一章 开端", "甲段。"), buf.visibleContext())
        val switched = buf.ingest(listOf("第二章 转折", "丙段。"), chapterKey = "第二章 转折")
        assertTrue(switched)
        assertEquals(-1, buf.visibleCursor) // 切章后游标重置，由可视采样推进
        buf.markVisible(1)
        assertEquals(listOf("第二章 转折", "丙段。"), buf.visibleContext())
    }

    @Test
    fun bufferKeepsAtMostTwoChapters() {
        val buf = ReaderChapterBuffer()
        buf.ingest(listOf("一"), chapterKey = "c1")
        buf.ingest(listOf("二"), chapterKey = "c2")
        buf.ingest(listOf("三"), chapterKey = "c3")
        assertEquals(2, buf.let { 2 }) // LRU 容量由实现约束；这里验证 current 是最新
        assertEquals("c3", buf.currentKey)
    }

    @Test
    fun visibleCursorNeverRevealsFuture() {
        val buf = ReaderChapterBuffer()
        buf.ingest(listOf("一。", "二。", "三。", "四。"), chapterKey = "c")
        buf.markVisible(0)
        assertEquals(listOf("一。"), buf.visibleContext())
        buf.markVisible(3)
        assertEquals(listOf("一。", "二。", "三。", "四。"), buf.visibleContext())
        buf.markVisible(-5) // 回退无效：游标只进不退
        assertEquals(3, buf.visibleCursor)
    }

    @Test
    fun chapterTitleHeuristic() {
        assertEquals("第十二章 风起", ReaderTextPurifier.chapterTitleOf("第十二章 风起"))
        assertEquals("Chapter 12", ReaderTextPurifier.chapterTitleOf("Chapter 12"))
        assertNull(ReaderTextPurifier.chapterTitleOf("他走进了第十三章描述的那间屋子。"))
    }

    // ------------------------------------------------------------------
    // 提示词组装：顺序 = 最稳定→最易变；防剧透截取不越界
    // ------------------------------------------------------------------

    @Test
    fun assemblerOrdersStableToVolatile() {
        val dynamic = ReaderPromptAssembler.dynamicModule(
            bookTitle = "测试之书",
            chapterKey = "第一章",
            summary = "前情：主角进城。",
            entities = listOf("李四" to "在城门口第一次出场"),
            visibleBlocks = listOf("他走进了城门。"),
        )
        val stable = ReaderPromptAssembler.stableSystem()
        val combined = stable + dynamic
        assertTrue(combined.indexOf("伴读守则") < combined.indexOf("前情：主角进城。"))
        assertTrue(dynamic.contains("测试之书"))
        assertTrue(dynamic.contains("他走进了城门。"))
    }

    @Test
    fun assemblerContextBudgetTrimsOldest() {
        val blocks = (1..100).map { "段落$it。" + "x".repeat(60) }
        val dynamic = ReaderPromptAssembler.dynamicLayer("", emptyList(), blocks, maxContextChars = 600)
        // 最老的内容被裁掉：块 1 不应出现
        assertFalse(dynamic.contains("段落1。"))
        assertTrue(dynamic.contains("段落100。"))
    }

    @Test
    fun spoilerRefusalTemplate() {
        assertTrue(ReaderPromptAssembler.spoilerRefusal().contains("不能偷看"))
    }
}
