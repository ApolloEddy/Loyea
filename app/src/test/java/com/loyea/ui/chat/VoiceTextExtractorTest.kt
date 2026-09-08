package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音文本提取契约：TTS 语音卡转写与 MiMo STT 清洗共用逻辑。
 * 核心回归——惰性正则遇值内引号（转义 \"）拦腰截断且尾部残留孤立 \，
 * 表现为"语音音频正常、转写文字断半句"。
 */
class VoiceTextExtractorTest {

    @Test
    fun transcriptWithInnerQuotesNotTruncated() {
        // 截图场景回归：文本内含英文引号，旧正则在第一个 \" 处截断
        val json = "{\"text\": \"一会儿甩我一句英文的\\\"哦豁\\\"然后跑题了\"}"
        val out = VoiceTextExtractor.extractTranscript(json)
        assertEquals("一会儿甩我一句英文的\"哦豁\"然后跑题了", out)
        assertEquals(false, out.contains("\\"))
    }

    @Test
    fun transcriptPreservesNewlineEscape() {
        val json = "{\"text\": \"第一行\\n第二行\"}"
        assertEquals("第一行\n第二行", VoiceTextExtractor.extractTranscript(json))
    }

    @Test
    fun escapedBackslashNDoesNotBecomeNewline() {
        // 顺序敏感回归：字面 \\n 必须还原为 \ + n 两个字符，而非换行（旧链 \\ 置后必错）
        val json = "{\"text\": \"a\\\\nb\"}"
        assertEquals("a\\nb", VoiceTextExtractor.extractTranscript(json))
    }

    @Test
    fun unicodeEscapeDecoded() {
        val json = "{\"text\": \"\\u4f60\\u597d\"}"
        assertEquals("你好", VoiceTextExtractor.extractTranscript(json))
    }

    @Test
    fun invalidJsonFallsBackToEscapeAwareRegex() {
        val raw = "{\"text\": \"带\\\"引号\\\"的文本\", trailing"
        assertEquals("带\"引号\"的文本", VoiceTextExtractor.extractTranscript(raw))
    }

    @Test
    fun nonJsonInputYieldsEmptyTranscript() {
        assertEquals("", VoiceTextExtractor.extractTranscript("纯文本不是 JSON"))
        assertEquals("", VoiceTextExtractor.extractTranscript(null))
        assertEquals("", VoiceTextExtractor.extractTranscript(""))
        assertEquals("", VoiceTextExtractor.extractTranscript("{\"other\": 1}"))
    }

    @Test
    fun styleTagsStrippedFromTranscript() {
        val json = "{\"text\": \"(微笑)你好【星光】继续\"}"
        assertEquals("你好继续", VoiceTextExtractor.extractTranscript(json))
    }

    @Test
    fun sttPlainTextPassesThroughWithStripping() {
        assertEquals("你好", VoiceTextExtractor.cleanSttText("(吸气)你好"))
        assertEquals("", VoiceTextExtractor.cleanSttText(null))
        assertEquals("", VoiceTextExtractor.cleanSttText(""))
    }

    @Test
    fun sttJsonishExtractsTextField() {
        val raw = "{\"text\": \"(微笑)晚安\\n好梦\"}"
        assertEquals("晚安\n好梦", VoiceTextExtractor.cleanSttText(raw))
    }

    @Test
    fun sttPlainWithLiteralEscapesUnescaped() {
        // 纯文本直通路径保留反转义清洗：字面 \n 残留还原为换行
        assertEquals("行1\n行2", VoiceTextExtractor.cleanSttText("行1\\n行2"))
    }

    @Test
    fun unescapeUnknownEscapeKeepsChar() {
        // 未知转义宽容处理：丢弃反斜杠保留字符（与正规解析器的宽容模式一致）
        assertEquals("axb", VoiceTextExtractor.unescapeJsonString("a\\xb"))
        assertEquals("", VoiceTextExtractor.unescapeJsonString(""))
        assertEquals("无反斜杠原样", VoiceTextExtractor.unescapeJsonString("无反斜杠原样"))
    }
}
