package com.loyea.plugins.tavern.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure exporter that renders a [TavernChatFile] into the two Tavo-compatible formats (`.txt` and `.json`).
 *
 * It is a view over the same normalized [TavernChatMessageRecord] / [TavernChatHeader] produced by
 * [TavernChatFileCodec.parse], picks the active swipe text via [TavernChatMessageRecord.selectedMessage],
 * and never mutates the source chat.
 */
object TavernChatExportCodec {

    private val displayTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    private const val FALLBACK_USER_NAME = "You"

    /**
     * Renders the chat body as plain text, one line per message.
     *
     * Line shapes:
     *  - user:      `"userName: text"`      (falls back to `"You"` when header.userName is blank)
     *  - assistant: `"characterName: text"` (uses each message's name)
     *  - system:    `"[system] text"`
     *
     * When a message has a resolvable [TavernChatMessageRecord.sendDate] it is prepended with a
     * `"[yyyy-MM-dd HH:mm] "` prefix formatted in UTC; an unparsable date keeps its raw text.
     */
    fun toTxt(chat: TavernChatFile): String {
        if (chat.messages.isEmpty()) return ""
        val userName = chat.header.userName?.takeIf { it.isNotBlank() } ?: FALLBACK_USER_NAME
        return chat.messages.joinToString("\n") { record ->
            val timePrefix = timeDisplay(record.sendDate)?.let { "[$it] " } ?: ""
            val body = when {
                record.isSystem -> "[system] ${record.selectedMessage}"
                record.isUser -> "$userName: ${record.selectedMessage}"
                else -> "${record.name}: ${record.selectedMessage}"
            }
            timePrefix + body
        }
    }

    /**
     * Renders the chat as a JSON array; each element is
     * `{role, name, message, timestamp}`.
     *
     *  - `role` is `"user"` / `"assistant"` / `"system"`.
     *  - For user messages `name` falls back to `"You"`; system messages omit `name`.
     *  - `timestamp` is normalized to epoch millis and omitted when the source date cannot be resolved.
     */
    fun toJson(chat: TavernChatFile): String {
        val userName = chat.header.userName?.takeIf { it.isNotBlank() } ?: FALLBACK_USER_NAME
        val array = JsonArray()
        chat.messages.forEach { record ->
            val role = when {
                record.isSystem -> "system"
                record.isUser -> "user"
                else -> "assistant"
            }
            val obj = JsonObject()
            obj.addProperty("role", role)
            when (role) {
                "user" -> obj.addProperty("name", userName)
                "assistant" -> if (record.name.isNotBlank()) obj.addProperty("name", record.name)
            }
            obj.addProperty("message", record.selectedMessage)
            epochMillis(record.sendDate)?.let { obj.addProperty("timestamp", it) }
            array.add(obj)
        }
        return array.toString()
    }

    /**
     * Resolves a wire `sendDate` (either numeric epoch millis or an ISO-8601 timestamp) to
     * normalized epoch millis, or null when it cannot be interpreted.
     */
    private fun epochMillis(sendDate: String?): Long? {
        val raw = sendDate?.trim() ?: return null
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { return it }
        return runCatching {
            val instant = runCatching { Instant.parse(raw) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
                ?: return null
            instant.toEpochMilli()
        }.getOrNull()
    }

    /**
     * Formats a `sendDate` for the `"[yyyy-MM-dd HH:mm]"` text prefix. On parse failure the
     * raw text is preserved so no information is lost.
     */
    private fun timeDisplay(sendDate: String?): String? {
        val millis = epochMillis(sendDate)
        return if (millis != null) displayTimeFormatter.format(Instant.ofEpochMilli(millis))
        else sendDate?.trim()?.takeIf { it.isNotEmpty() }
    }
}