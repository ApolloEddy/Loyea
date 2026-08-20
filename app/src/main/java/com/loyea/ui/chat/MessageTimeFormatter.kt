package com.loyea.ui.chat

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 消息记录的本地化时间格式：当天显示 12 小时制，随后优先使用昨天/前天，再退回日期。
 * 使用消息日期与当前日期的日历差，而不是固定 24 小时，避免跨夏令时或午夜时产生误判。
 */
object MessageTimeFormatter {

    fun format(
        timestampMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        appLanguage: String = "zh",
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val zone = timeZone.toZoneId()
        val messageDate = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val dayDelta = ChronoUnit.DAYS.between(messageDate, today)
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMillis }
        val clock = formatClock(calendar, appLanguage)

        return if (appLanguage == "en") {
            formatEnglish(messageDate, today.year, dayDelta, clock, calendar)
        } else {
            formatChinese(messageDate, today.year, dayDelta, clock)
        }
    }

    private fun formatChinese(
        messageDate: java.time.LocalDate,
        currentYear: Int,
        dayDelta: Long,
        clock: String
    ): String {
        val month = messageDate.monthValue
        val day = messageDate.dayOfMonth
        return when (dayDelta) {
            0L -> clock
            1L -> "昨天 $clock"
            2L -> "前天 $clock"
            else -> if (messageDate.year == currentYear) {
                "${month}月${day}日 $clock"
            } else {
                "${messageDate.year}年${month}月${day}日 $clock"
            }
        }
    }

    private fun formatEnglish(
        messageDate: java.time.LocalDate,
        currentYear: Int,
        dayDelta: Long,
        clock: String,
        calendar: Calendar
    ): String {
        val month = calendar.get(Calendar.MONTH)
        val day = messageDate.dayOfMonth
        val monthName = java.text.DateFormatSymbols(Locale.ENGLISH).shortMonths[month]
        return when (dayDelta) {
            0L -> clock
            1L -> "Yesterday $clock"
            2L -> "The day before yesterday $clock"
            else -> if (messageDate.year == currentYear) {
                "$monthName $day, $clock"
            } else {
                "$monthName $day, ${messageDate.year}, $clock"
            }
        }
    }

    private fun formatClock(calendar: Calendar, appLanguage: String): String {
        val hour = calendar.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val minute = calendar.get(Calendar.MINUTE)
        val clock = String.format(Locale.ROOT, "%d:%02d", hour, minute)
        val period = if (calendar.get(Calendar.AM_PM) == Calendar.AM) {
            if (appLanguage == "en") "AM" else "上午"
        } else {
            if (appLanguage == "en") "PM" else "下午"
        }
        return if (appLanguage == "en") "$clock $period" else "$period $clock"
    }
}
