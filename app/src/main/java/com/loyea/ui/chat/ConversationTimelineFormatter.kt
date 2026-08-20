package com.loyea.ui.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.time.ZoneId

/**
 * 为每条历史消息生成只依赖其自身绝对时间戳的 provider 元数据。
 * 不使用“几分钟前”等随请求变化的相对时间，保证同一条消息在后续请求中始终字节一致。
 */
object ConversationTimelineFormatter {

    fun formatMessageMetadata(
        message: Message,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        if (message.timestamp <= 0L) {
            return "[MESSAGE TIME: unavailable (legacy message)]"
        }
        val messageTimeZone = message.llmTimeZoneId
            ?.takeIf { it.isNotBlank() }
            ?.let { zoneId ->
                runCatching { TimeZone.getTimeZone(ZoneId.of(zoneId)) }.getOrNull()
            }
            ?: timeZone
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply {
            this.timeZone = messageTimeZone
        }
        return "[MESSAGE TIME: ${dateFormat.format(Date(message.timestamp))}]"
    }

    fun decorateContent(
        message: Message,
        content: String,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = "${formatMessageMetadata(message, timeZone)}\n$content"
}
