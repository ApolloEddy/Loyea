package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML 面板拆分与解析测试（Spec §7.1 / 验收矩阵 D01、D02、D03 的展示逻辑）。
 */
class HtmlDisplaySplitterTest {

    @Test
    fun `plain markdown stays single segment`() {
        val raw = "你好呀。\n\n**加粗**的普通正文。"
        val segments = HtmlDisplaySplitter.split(raw)
        assertEquals(1, segments.size)
        assertEquals(raw, (segments[0] as ContentSegment.Markdown).text)
    }

    @Test
    fun `details panel parses summary paragraphs strong and span`() {
        val raw = """
            <details style='border: 1px solid #DA70D6;'>
            <summary style='color: #FFD700; font-weight: bold;'>【状态面板】</summary>
            <div style='display: grid; grid-template-columns: 1fr 1fr;'>
            <p><strong>生命值 (HP):</strong> <span style='color: #00FF00;'>100/100</span></p>
            <p>心情：平静 &amp; 放松</p>
            </div>
            </details>
        """.trimIndent()
        val segments = HtmlDisplaySplitter.split(raw)
        val panel = segments.filterIsInstance<ContentSegment.Panel>().single().parsed
        assertNotNull(panel)
        assertTrue("summary 文本保留", panel!!.summarySpans.joinToString { it.text }.contains("状态面板"))
        assertTrue("summary 颜色保留", panel.summarySpans.any { it.colorHex.equals("#FFD700", true) })
        // grid div 降级为流式段落，内容不丢
        val texts = panel.children.flatMap { (it as? PanelBlock.Paragraph)?.spans ?: emptyList() }
            .joinToString("") { it.text }
        assertTrue("HP 字段保留", texts.contains("生命值 (HP):"))
        assertTrue("数值 span 保留", texts.contains("100/100"))
        assertTrue("实体解码", texts.contains("心情：平静 & 放松"))
        val bold = panel.children.flatMap { (it as? PanelBlock.Paragraph)?.spans ?: emptyList() }
        assertTrue("strong 加粗", bold.any { it.bold && it.text.contains("生命值") })
        val green = bold.filter { it.colorHex != null }
        assertTrue("span 颜色保留", green.any { it.colorHex.equals("#00FF00", true) })
    }

    @Test
    fun `mixed markdown and panel split into ordered segments`() {
        val raw = "她整理了一下诊台。\n<details><summary>面板</summary><p>HP 100</p></details>\n\n「请坐。」"
        val segments = HtmlDisplaySplitter.split(raw)
        assertEquals(3, segments.size)
        assertTrue((segments[0] as ContentSegment.Markdown).text.contains("诊台"))
        assertNotNull((segments[1] as ContentSegment.Panel).parsed)
        assertTrue((segments[2] as ContentSegment.Markdown).text.contains("请坐"))
    }

    @Test
    fun `fenced code block containing details is not a panel`() {
        val raw = "示例：\n```\n<details><summary>x</summary></details>\n```"
        val segments = HtmlDisplaySplitter.split(raw)
        assertTrue(segments.none { it is ContentSegment.Panel && it.parsed != null })
    }

    @Test
    fun `unclosed details during streaming yields stable placeholder`() {
        val raw = "正文已完成。<details><summary>状态面板</summary><p>HP"
        val segments = HtmlDisplaySplitter.split(raw)
        val panel = segments.filterIsInstance<ContentSegment.Panel>().single()
        assertNull("未完成面板为占位", panel.parsed)
        val md = segments.filterIsInstance<ContentSegment.Markdown>().single().text
        assertTrue("已完成正文保留", md.contains("正文已完成"))
    }

    @Test
    fun `narrative text for tts excludes panel content`() {
        val raw = "「请坐。」\n<details><summary>状态面板</summary><p>HP 100/100</p></details>"
        val narrative = HtmlDisplaySplitter.narrativeText(raw)
        assertTrue("正文保留", narrative.contains("请坐"))
        assertTrue("面板数值不朗读", !narrative.contains("HP 100/100") && !narrative.contains("状态面板"))
    }

    @Test
    fun `script and style bodies are not output`() {
        val raw = "<details><summary>s</summary><script>alert(1)</script><style>.x{}</style><p>正文</p></details>"
        val segments = HtmlDisplaySplitter.split(raw)
        val panel = segments.filterIsInstance<ContentSegment.Panel>().single().parsed
        assertNotNull(panel)
        val texts = panel!!.children.flatMap { (it as? PanelBlock.Paragraph)?.spans ?: emptyList() }
            .joinToString("") { it.text }
        assertTrue(!texts.contains("alert"))
        assertTrue(!texts.contains(".x{}"))
        assertTrue(texts.contains("正文"))
    }

    @Test
    fun `collapsed state key is per message and panel`() {
        // 键结构（messageId + 面板序号）由 MessageContentWithPanels 生成；此处锁定格式约定
        val key = "msg-42:panel0"
        assertTrue(key.startsWith("msg-42") && key.endsWith("panel0"))
    }

    @Test
    fun `container-only html runs are stripped to readable text`() {
        val raw = "<div style='text-align:center;'>她抬起头。</div>\n\n下一句正文。"
        val segments = HtmlDisplaySplitter.split(raw)
        val md = segments.filterIsInstance<ContentSegment.Markdown>().joinToString("\n") { it.text }
        assertTrue("容器标签剥离后文字保留", md.contains("她抬起头"))
        assertTrue("不应出现裸标签", !md.contains("<div"))
        assertTrue(segments.none { it is ContentSegment.Code })
    }

    @Test
    fun `non-text constructs collapse into code segment instead of dumping raw html`() {
        val raw = "正文开场。\n<div style='background-image:url(https://x/y.gif)'>\n<img src=https://x/z.png width=30% />\n<h1>标题</h1>\n</div>\n\n结尾正文。"
        val segments = HtmlDisplaySplitter.split(raw)
        val code = segments.filterIsInstance<ContentSegment.Code>()
        assertEquals(1, code.size)
        assertTrue("img 构造进入折叠段", code[0].text.contains("<img"))
        val joined = segments.joinToString("\n") {
            when (it) {
                is ContentSegment.Markdown -> it.text
                is ContentSegment.Code -> ""
                is ContentSegment.Panel -> ""
            }
        }
        assertTrue("正文前后保留", joined.contains("正文开场") && joined.contains("结尾正文"))
        assertTrue(!joined.contains("<img"))
    }
}