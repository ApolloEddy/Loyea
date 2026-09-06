package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Thinking 计时逻辑压力用例：覆盖结束态、锚点推算、时钟跳变钳制、
 * 无锚点回退、跨小时长思考与多轮工具响应不重置等边界。
 */
class ThinkingTimerTest {

    private val startedAt = 1_725_000_000_000L

    @Test
    fun endedThinkingShowsFinalDuration() {
        assertEquals(42, ThinkingTimer.elapsedSeconds(
            isStillThinking = false,
            finalDurationSeconds = 42,
            startedAtMillis = startedAt,
            nowMillis = startedAt + 999_999,
            fallbackSeconds = 7
        ))
    }

    @Test
    fun anchoredElapsedFloorsToSeconds() {
        // 9.5 秒 → 9（向下取整，不打虚高）
        assertEquals(9, ThinkingTimer.elapsedSeconds(true, 0, startedAt, startedAt + 9_500, 0))
        assertEquals(0, ThinkingTimer.elapsedSeconds(true, 0, startedAt, startedAt + 999, 0))
    }

    @Test
    fun clockSkewIntoFutureClampsToZero() {
        // 设备时钟向未来跳变：now < startedAt → 0，绝不显示负数
        assertEquals(0, ThinkingTimer.elapsedSeconds(true, 0, startedAt, startedAt - 5_000, 3))
    }

    @Test
    fun legacyMessageWithoutAnchorUsesRelativeFallback() {
        assertEquals(17, ThinkingTimer.elapsedSeconds(true, 0, 0L, startedAt, 17))
        assertEquals(0, ThinkingTimer.elapsedSeconds(true, 0, -1L, startedAt, 0))
    }

    @Test
    fun longThinkingSessionsSpanHoursWithoutOverflow() {
        // 两小时思考（极端压力）：秒级换算稳定
        assertEquals(7200, ThinkingTimer.elapsedSeconds(true, 0, startedAt, startedAt + 7_200_000L, 0))
        assertEquals(3600 + 59, ThinkingTimer.elapsedSeconds(true, 0, startedAt, startedAt + 3_659_000L, 0))
    }

    @Test
    fun toolRoundsKeepClockRunningViaSameAnchor() {
        // 多轮工具响应：思考态保持 true 期间，锚点持续有效（回归 c18db54 后冻结 bug 的契约）
        val midToolRound = startedAt + 15_000L
        val stillTooling = startedAt + 47_000L
        assertEquals(15, ThinkingTimer.elapsedSeconds(true, 0, startedAt, midToolRound, 0))
        assertEquals(47, ThinkingTimer.elapsedSeconds(true, 0, startedAt, stillTooling, 0))
    }

    @Test
    fun negativeFinalDurationPassesThroughWhenEnded() {
        // 结束态不做钳制：落盘值即显示值（由 ViewModel 保证非负）
        assertEquals(0, ThinkingTimer.elapsedSeconds(false, 0, startedAt, startedAt, 5))
    }
}
