package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyOutputSanitizerTest {

    @Test
    fun removesCompleteMessageTimeMetadataAnywhereInReply() {
        val raw = "开头[MESSAGE TIME: 2026-08-22T12:34:56+08:00]结尾\n[MESSAGE TIME: unavailable (legacy message)]"

        assertEquals("开头结尾\n", ReplyOutputSanitizer.sanitize(raw))
    }

    @Test
    fun hidesIncompleteMetadataUntilClosingBracketArrives() {
        assertEquals("你好\n", ReplyOutputSanitizer.sanitize("你好\n[MESSAGE TIME: 2026-08"))
        assertEquals("你好\n世界", ReplyOutputSanitizer.sanitize("你好\n[MESSAGE TIME: 2026-08-22]世界"))
    }

    @Test
    fun doesNotRemoveSimilarNonMetadataText() {
        val raw = "[MESSAGE TIMESTAMP: user label]\n[MESSAGE TIMEOUT: 3s]"

        assertEquals(raw, ReplyOutputSanitizer.sanitize(raw))
        assertTrue(ReplyOutputSanitizer.sanitize(raw).contains("MESSAGE TIMEOUT"))
    }

    @Test
    fun doesNotHideSimilarIncompleteText() {
        val raw = "状态：[MESSAGE TIMEOUT"
        assertEquals(raw, ReplyOutputSanitizer.sanitize(raw))
    }

    @Test
    fun incrementalLlmParserAppliesTheSameOutputFilter() {
        val client = LlmClient()

        assertEquals(
            "你好",
            client.parseIncrementalStreamState("你好[MESSAGE TIME: 2026-08", isDone = false).visibleContent
        )
        assertEquals(
            "你好世界",
            client.parseIncrementalStreamState(
                "你好[MESSAGE TIME: 2026-08-22T12:34:56+08:00]世界",
                isDone = true
            ).visibleContent
        )
    }

    @Test
    fun accumulatedReasoningFilteringHandlesMetadataSplitAcrossDeltas() {
        var accumulated = "你好[MESSAGE TIME: 2026-08"
        assertEquals("你好", ReplyOutputSanitizer.sanitize(accumulated))

        accumulated += "-22]世界"
        assertEquals("你好世界", ReplyOutputSanitizer.sanitize(accumulated))
    }
}
