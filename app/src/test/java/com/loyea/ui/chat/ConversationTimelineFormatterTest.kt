package com.loyea.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ConversationTimelineFormatterTest {

    private val chinaTime = TimeZone.getTimeZone("GMT+08:00")

    @Test
    fun emitsStableAbsoluteTimestampWithoutCopyingMessageContent() {
        val message = Message("1", "private user text", Sender.USER, timestamp = 1_725_000_000_000L)

        val first = ConversationTimelineFormatter.formatMessageMetadata(message, chinaTime)
        val second = ConversationTimelineFormatter.formatMessageMetadata(message, chinaTime)

        assertEquals("[MESSAGE TIME: 2024-08-30T14:40:00+08:00]", first)
        assertEquals("同一条历史消息的时间元数据必须字节稳定", first, second)
        assertFalse("时间元数据不应复制对话正文", first.contains("private user text"))
    }

    @Test
    fun handlesLegacyTimestampWithoutInventingRelativeTime() {
        val legacy = ConversationTimelineFormatter.formatMessageMetadata(
            Message("legacy", "old", Sender.USER, timestamp = 0L),
            chinaTime
        )

        assertEquals("[MESSAGE TIME: unavailable (legacy message)]", legacy)
        assertFalse(legacy.contains("ago"))
    }

    @Test
    fun decoratesContentWithoutChangingOriginalMessage() {
        val original = Message("1", "hello", Sender.USER, timestamp = 1_725_000_000_000L)
        val decorated = ConversationTimelineFormatter.decorateContent(original, "hello", chinaTime)

        assertTrue(decorated.startsWith("[MESSAGE TIME: 2024-08-30T14:40:00+08:00]\n"))
        assertTrue(decorated.endsWith("hello"))
        assertEquals("hello", original.content)
    }

    @Test
    fun persistedSendTimeZoneWinsOverLaterDeviceTimeZoneChanges() {
        val original = Message(
            id = "1",
            content = "hello",
            sender = Sender.USER,
            timestamp = 1_725_000_000_000L,
            llmTimeZoneId = "GMT+08:00"
        )

        val beforeDeviceChange = ConversationTimelineFormatter.formatMessageMetadata(
            original,
            TimeZone.getTimeZone("GMT+08:00")
        )
        val afterDeviceChange = ConversationTimelineFormatter.formatMessageMetadata(
            original,
            TimeZone.getTimeZone("GMT-05:00")
        )

        assertEquals(beforeDeviceChange, afterDeviceChange)
        assertTrue(afterDeviceChange.endsWith("+08:00]"))
    }
}
