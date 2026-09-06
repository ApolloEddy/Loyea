package com.loyea.ui.chat

/**
 * Thinking 标题计时的纯逻辑（抽出便于 JVM 单测覆盖边界）。
 *
 * 三种时间来源：
 * - 思考已结束 → 固定显示 ViewModel 落盘的最终时长；
 * - 有绝对锚点（thinkingStartedAt，c18db54 起写入）→ 按 now-anchor 秒数计算，
 *   重组/旋转/切后台回归都不重置；
 * - 无锚点的旧消息 → 回退相对累加值。
 */
object ThinkingTimer {

    /**
     * @param isStillThinking 思考是否进行中（工具执行期间保持 true，计时持续走动）
     * @param finalDurationSeconds 结束后展示的最终时长（覆盖整个多轮响应，含工具轮）
     * @param startedAtMillis 思考开始的绝对时间戳；<=0 表示旧消息无锚点
     * @param nowMillis 当前取样时刻（调用方以 tick 驱动每秒取样一次）
     * @param fallbackSeconds 无锚点时的相对累加秒数
     */
    fun elapsedSeconds(
        isStillThinking: Boolean,
        finalDurationSeconds: Int,
        startedAtMillis: Long,
        nowMillis: Long,
        fallbackSeconds: Int
    ): Int = when {
        !isStillThinking -> finalDurationSeconds
        startedAtMillis <= 0L -> fallbackSeconds
        // 设备时钟向未来跳变/锚点异常时钳到 0，不显示负数秒
        else -> maxOf(0, ((nowMillis - startedAtMillis) / 1000L).toInt())
    }
}
