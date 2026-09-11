package com.loyea.plugin.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * AC-17 免打扰区间判定合同：支持跨午夜（23:00 → 08:00）、普通区间、起止相同视为未配置。
 */
class CompanionProactiveGateTest {

    private fun millisAt(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun config(start: Int, end: Int) = CompanionConfig(dndStartMinute = start, dndEndMinute = end)

    @Test
    fun `cross midnight interval suppresses late night and early morning`() {
        val cfg = config(23 * 60, 8 * 60)
        assertTrue(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(23, 30)))
        assertTrue(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(23, 0)))  // 起始时刻即抑制
        assertTrue(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(7, 59)))
        assertFalse(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(8, 0)))  // 结束时刻不再抑制
        assertFalse(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(12, 0)))
    }

    @Test
    fun `same day interval suppresses only inside range`() {
        val cfg = config(9 * 60, 18 * 60)
        assertTrue(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(12, 0)))
        assertFalse(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(20, 0)))
        assertFalse(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(7, 0)))
    }

    @Test
    fun `equal start and end means no interval configured`() {
        val cfg = config(8 * 60, 8 * 60)
        assertFalse(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(8, 0)))
        assertFalse(CompanionProactiveGate.inDoNotDisturb(cfg, millisAt(23, 0)))
    }
}
