package com.loyea.ui.chat

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimeFormatterTest {

    private val chinaTime = TimeZone.getTimeZone("GMT+08:00")
    private val now = time(2026, 8, 21, 16, 30)

    @Test
    fun todayUsesTwelveHourClockWithDayPart() {
        assertEquals("上午 9:05", MessageTimeFormatter.format(time(2026, 8, 21, 9, 5), now, "zh", chinaTime))
        assertEquals("下午 3:20", MessageTimeFormatter.format(time(2026, 8, 21, 15, 20), now, "zh", chinaTime))
    }

    @Test
    fun yesterdayAndTheDayBeforeUseRelativeChineseLabels() {
        assertEquals("昨天 上午 9:05", MessageTimeFormatter.format(time(2026, 8, 20, 9, 5), now, "zh", chinaTime))
        assertEquals("前天 下午 3:20", MessageTimeFormatter.format(time(2026, 8, 19, 15, 20), now, "zh", chinaTime))
    }

    @Test
    fun olderDatesUseMonthDayThenYearWhenNeeded() {
        assertEquals("8月1日 上午 9:05", MessageTimeFormatter.format(time(2026, 8, 1, 9, 5), now, "zh", chinaTime))
        assertEquals("2025年12月31日 下午 11:05", MessageTimeFormatter.format(time(2025, 12, 31, 23, 5), now, "zh", chinaTime))
    }

    @Test
    fun englishKeepsTheSameCalendarBuckets() {
        assertEquals("Yesterday 9:05 AM", MessageTimeFormatter.format(time(2026, 8, 20, 9, 5), now, "en", chinaTime))
        assertEquals("Aug 1, 3:20 PM", MessageTimeFormatter.format(time(2026, 8, 1, 15, 20), now, "en", chinaTime))
        assertEquals("Dec 31, 2025, 11:05 PM", MessageTimeFormatter.format(time(2025, 12, 31, 23, 5), now, "en", chinaTime))
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(chinaTime).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
