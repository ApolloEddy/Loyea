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

/**
 * R-06 主动联系完整门控合同测试（审计 AC-17~19）：每个条件独立判定，
 * 生成前与提交前共用同一 evaluate 函数。
 */
class CompanionProactiveGateEvaluateTest {

    private fun config(
        enabled: Boolean = true,
        proactive: Boolean = true,
        dndStart: Int = 23 * 60,
        dndEnd: Int = 8 * 60
    ) = CompanionConfig(
        enabled = enabled,
        proactiveEnabled = proactive,
        dndStartMinute = dndStart,
        dndEndMinute = dndEnd
    )

    private fun ledger(
        countToday: Int = 0,
        lastGreetingAt: Long = 0L
    ) = CompanionProactiveGate.LedgerState(
        countDate = todayKey(),
        countToday = countToday,
        lastGreetingAt = lastGreetingAt
    )

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        return CompanionProactiveGate.dateKey(cal)
    }

    private fun millisAt(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val noon = millisAt(12, 0)

    private fun evaluate(
        cfg: CompanionConfig = config(),
        led: CompanionProactiveGate.LedgerState = ledger(),
        now: Long = noon,
        notification: Boolean = true,
        sessionExists: Boolean = true,
        foreground: Boolean = false,
        lastUserAt: Long? = null,
        unanswered: Boolean = false
    ) = CompanionProactiveGate.evaluate(
        config = cfg,
        ledger = led,
        nowMillis = now,
        notificationGranted = notification,
        sessionExists = sessionExists,
        uiForeground = foreground,
        lastUserMessageAt = lastUserAt,
        lastMessageIsUnansweredGreeting = unanswered
    )

    private fun reasonOf(d: CompanionProactiveGate.GreetDecision) =
        (d as CompanionProactiveGate.GreetDecision.Postpone).reason

    @Test
    fun `all clear proceeds`() {
        assertTrue(evaluate() is CompanionProactiveGate.GreetDecision.Proceed)
    }

    @Test
    fun `each hard condition postpones independently`() {
        assertTrue(reasonOf(evaluate(cfg = config(enabled = false))) == "companion_disabled")
        assertTrue(reasonOf(evaluate(cfg = config(proactive = false))) == "proactive_disabled")
        assertTrue(reasonOf(evaluate(notification = false)) == "notification_not_granted")
        assertTrue(reasonOf(evaluate(sessionExists = false)) == "session_missing")
        assertTrue(reasonOf(evaluate(foreground = true)) == "ui_foreground")
        assertTrue(reasonOf(evaluate(unanswered = true)) == "last_greeting_unanswered")
    }

    @Test
    fun `dnd postpones with delay until window end`() {
        val d = evaluate(cfg = config(dndStart = 11 * 60, dndEnd = 13 * 60))
        assertTrue(d is CompanionProactiveGate.GreetDecision.Postpone)
        assertTrue(reasonOf(d) == "dnd")
        // 12:00 处于 11:00–13:00 → 顺延至少 60 分钟
        assertTrue((d as CompanionProactiveGate.GreetDecision.Postpone).retryAfterMinutes >= 60)
    }

    @Test
    fun `daily limit postpones until midnight`() {
        val d = evaluate(led = ledger(countToday = 2))
        assertTrue(d is CompanionProactiveGate.GreetDecision.Postpone)
        assertTrue(reasonOf(d) == "daily_limit")
    }

    @Test
    fun `yesterday count does not carry over`() {
        val d = evaluate(
            led = CompanionProactiveGate.LedgerState(countDate = "1999-01-01", countToday = 5)
        )
        assertTrue(d is CompanionProactiveGate.GreetDecision.Proceed)
    }

    @Test
    fun `min interval postpones`() {
        val d = evaluate(led = ledger(lastGreetingAt = noon - 60 * 60 * 1000L))
        assertTrue(d is CompanionProactiveGate.GreetDecision.Postpone)
        assertTrue(reasonOf(d) == "min_interval")
    }

    @Test
    fun `recent user message postpones until silence window passes`() {
        val d = evaluate(lastUserAt = noon - 10 * 60 * 1000L)
        assertTrue(d is CompanionProactiveGate.GreetDecision.Postpone)
        assertTrue(reasonOf(d) == "user_recently_active")
        // 静默满 30 分钟后放行
        assertTrue(evaluate(lastUserAt = noon - 31 * 60 * 1000L) is CompanionProactiveGate.GreetDecision.Proceed)
    }
}
