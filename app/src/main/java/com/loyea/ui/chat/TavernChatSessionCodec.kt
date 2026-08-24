package com.loyea.ui.chat

import com.google.gson.JsonParser
import com.loyea.plugins.tavern.core.TavernChatFile
import com.loyea.plugins.tavern.core.TavernChatFileCodec
import com.loyea.plugins.tavern.core.TavernChatHeader
import com.loyea.plugins.tavern.core.TavernChatMessageRecord
import com.loyea.plugins.tavern.core.TavernChatParseIssue
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A user-visible diagnostic produced while adapting a Tavern chat file to native messages. */
data class TavernChatSessionIssue(
    val reason: String,
    val lineNumber: Int? = null,
    val messageIndex: Int? = null,
    val fatal: Boolean = false
)

data class TavernChatSessionImport(
    val header: TavernChatHeader,
    val messages: List<Message>,
    val issues: List<TavernChatSessionIssue> = emptyList()
) {
    val isClean: Boolean
        get() = issues.none { it.fatal }
}

/**
 * Android-host adapter for the pure Tavern JSONL codec.
 *
 * System messages remain `Message` records marked with [Message.tavernIsSystem], so the
 * provider serializer can send them with the correct `system` role instead of silently turning
 * them into assistant text. Unknown source fields and swipe metadata stay attached to the native
 * message for a loss-aware export.
 */
object TavernChatSessionCodec {
    private val outputTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    private val localDateTimeFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    )

    fun importJsonl(
        jsonl: String,
        fallbackTimestampMillis: Long = System.currentTimeMillis()
    ): TavernChatSessionImport {
        val parsed = TavernChatFileCodec.parse(jsonl)
        val issues = parsed.issues.map(::toIssue).toMutableList()
        val messages = parsed.chat.messages.mapIndexed { index, record ->
            record.toMessage(index, fallbackTimestampMillis, issues)
        }
        return TavernChatSessionImport(
            header = parsed.chat.header,
            messages = messages,
            issues = issues.toList()
        )
    }

    fun exportJsonl(
        messages: List<Message>,
        userName: String,
        characterName: String,
        chatMetadataJson: String = "{}",
        createDate: String? = messages.firstOrNull()?.timestamp?.let(::formatTimestamp),
        headerRawJson: String? = null
    ): String {
        val effectiveMetadataJson = if (chatMetadataJson == "{}" && !headerRawJson.isNullOrBlank()) {
            runCatching {
                JsonParser.parseString(headerRawJson).asJsonObject["chat_metadata"]
                    ?.takeIf { it.isJsonObject }
                    ?.toString()
            }.getOrNull() ?: chatMetadataJson
        } else {
            chatMetadataJson
        }
        val header = TavernChatHeader(
            userName = userName.takeIf(String::isNotBlank),
            characterName = characterName.takeIf(String::isNotBlank),
            createDate = createDate,
            chatMetadataJson = effectiveMetadataJson,
            rawJson = headerRawJson
        )
        return TavernChatFileCodec.toJsonl(
            TavernChatFile(
                header = header,
                messages = messages.map { it.toTavernRecord(userName, characterName) }
            )
        )
    }

    private fun toIssue(issue: TavernChatParseIssue): TavernChatSessionIssue =
        TavernChatSessionIssue(
            reason = issue.reason,
            lineNumber = issue.lineNumber,
            fatal = true
        )

    private fun TavernChatMessageRecord.toMessage(
        index: Int,
        fallbackTimestampMillis: Long,
        issues: MutableList<TavernChatSessionIssue>
    ): Message {
        val timestamp = parseTimestamp(sendDate, fallbackTimestampMillis + index)
        if (isSystem && isUser) {
            issues += TavernChatSessionIssue(
                reason = "record is marked both user and system; system role wins",
                messageIndex = index
            )
        }
        val selectedIndex = if (swipes.isEmpty()) 0 else swipeId.coerceIn(0, swipes.lastIndex)
        val versions = if (swipes.isEmpty()) {
            emptyList()
        } else {
            swipes.map { swipe -> MessageVersion(content = swipe) }
        }
        return Message(
            id = "tavern-$index-${timestamp.coerceAtLeast(0L)}",
            content = selectedMessage,
            sender = if (isUser && !isSystem) Sender.USER else Sender.AI,
            timestamp = timestamp,
            tavernName = name.takeIf(String::isNotBlank),
            tavernIsSystem = isSystem,
            tavernExtraJson = extraJson,
            tavernSwipeInfoJson = swipeInfoJson,
            tavernRawJson = rawJson,
            versions = versions,
            activeVersionIndex = selectedIndex
        )
    }

    private fun Message.toTavernRecord(userName: String, characterName: String): TavernChatMessageRecord {
        val swipes = versions.map { it.content }
        val selectedSwipeId = if (swipes.isEmpty()) 0 else activeVersionIndex.coerceIn(0, swipes.lastIndex)
        return TavernChatMessageRecord(
            name = tavernName ?: if (sender == Sender.USER) userName else characterName,
            message = content,
            isUser = sender == Sender.USER && !tavernIsSystem,
            isSystem = tavernIsSystem,
            sendDate = formatTimestamp(timestamp),
            swipes = swipes,
            swipeInfoJson = tavernSwipeInfoJson,
            swipeId = selectedSwipeId,
            extraJson = tavernExtraJson,
            rawJson = tavernRawJson
        )
    }

    private fun parseTimestamp(value: String?, fallback: Long): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return fallback
        raw.toLongOrNull()?.let { numeric ->
            return if (numeric in 1L..9_999_999_999L) numeric * 1_000L else numeric
        }
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        localDateTimeFormatters.forEach { formatter ->
            runCatching {
                LocalDateTime.parse(raw, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return fallback
    }

    private fun formatTimestamp(timestamp: Long): String =
        outputTimestampFormatter.format(Instant.ofEpochMilli(timestamp.coerceAtLeast(0L)))
}
