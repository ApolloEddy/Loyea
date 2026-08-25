package com.loyea.plugins.tavern.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Aggregated per-chat statistics aligned with the Tavo statistics page semantics.
 *
 * System messages never contribute to message counts or to the character total; they are
 * also ignored when resolving the earliest message date used for companionship days.
 * A chat that is empty or contains only system messages yields all zeros.
 */
data class TavernChatStatisticsResult(
    /** Full natural days from the first message date through the statistics day (inclusive). */
    val companionshipDays: Long,
    /** Count of non-system user messages. */
    val userMessageCount: Long,
    /** Count of non-system character messages. */
    val characterMessageCount: Long,
    /** Sum of characters of all non-system messages, using each message's selected swipe text. */
    val totalCharacterCount: Long,
    /** The statistics day (as given) this result was computed for. */
    val statisticsDay: LocalDate,
    /** Earliest successfully parsed non-system message date, or null when none is available. */
    val firstMessageDate: LocalDate? = null
)

/**
 * Pure calculator for per-chat companionship and message statistics.
 *
 * The input is the normalized [TavernChatMessageRecord] list, so callers feed the exact same
 * records produced by [TavernChatFileCodec]. Date parsing accepts either an ISO 8601 string or
 * an epoch-millis timestamp; a message whose [TavernChatMessageRecord.sendDate] cannot be parsed
 * is skipped (it is excluded from companionship-day resolution) rather than failing the whole
 * calculation.
 */
object TavernChatStatistics {
    /**
     * Computes statistics for [messages] as observed on [statisticsDay].
     *
     * @param messages normalized chat messages, ordering is irrelevant.
     * @param statisticsDay the day the statistics are "as of"; defaults to today.
     */
    fun calculate(
        messages: List<TavernChatMessageRecord>,
        statisticsDay: LocalDate = LocalDate.now()
    ): TavernChatStatisticsResult {
        val firstNonSystemDate = messages.asSequence()
            .filter { !it.isSystem }
            .mapNotNull { parseDate(it.sendDate) }
            .minOrNull()

        val companionshipDays = if (firstNonSystemDate == null) 0L
        else if (statisticsDay.isBefore(firstNonSystemDate)) 0L
        else statisticsDay.toEpochDay() - firstNonSystemDate.toEpochDay() + 1

        val userMessageCount = messages.count { it.isUser && !it.isSystem }.toLong()
        val characterMessageCount = messages.count { !it.isUser && !it.isSystem }.toLong()
        val totalCharacterCount = messages
            .filter { !it.isSystem }
            .sumOf { it.selectedMessage.length }
            .toLong()

        return TavernChatStatisticsResult(
            companionshipDays = companionshipDays,
            userMessageCount = userMessageCount,
            characterMessageCount = characterMessageCount,
            totalCharacterCount = totalCharacterCount,
            statisticsDay = statisticsDay,
            firstMessageDate = firstNonSystemDate
        )
    }

    /**
     * Parses a [TavernChatMessageRecord.sendDate] into a [LocalDate] (UTC for timestamps).
     * Returns null when input is blank or cannot be parsed as ISO 8601 or epoch millis.
     */
    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null

        raw.toLongOrNull()?.let { millis ->
            return runCatching {
                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            }.getOrNull()
        }

        return runCatching {
            try {
                OffsetDateTime.parse(raw).toLocalDate()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(raw).toLocalDate()
                } catch (_: Exception) {
                    LocalDate.parse(raw.take(10))
                }
            }
        }.getOrNull()
    }
}