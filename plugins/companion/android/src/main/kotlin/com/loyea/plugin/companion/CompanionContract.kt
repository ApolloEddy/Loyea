package com.loyea.plugin.companion

/**
 * 陪伴模式插件对外契约：宿主（app 模块）允许引用的最小符号面。
 *
 * 非侵入约定：宿主只通过本契约判断陪伴归属；陪伴功能的全部实现都收敛在
 * com.loyea.plugin.companion 命名空间内。宿主触碰点清单见 plugins/companion/README.md。
 */
object CompanionContract {
    /** 陪伴对象内部资料 ID（FUN-03 独立副本标识，不复用普通角色卡）。 */
    const val COMPANION_CHARACTER_ID = "char_loyea_companion"

    /** 陪伴插件自有偏好文件（不与宿主 loyea_prefs 混用）。 */
    const val PREFS_NAME = "loyea_companion_prefs"

    /** 会话 / 角色卡是否属于陪伴模式归属。 */
    fun isCompanionCharacter(characterId: String?): Boolean =
        characterId == COMPANION_CHARACTER_ID
}

/** App 模式（Spec §9 最小合同：AppMode）。缺失/未开启时按旧版普通模式解析。 */
enum class CompanionAppMode { NORMAL, COMPANION }

/**
 * AC-17/ACT-02 主动问候门控。
 *
 * [shouldSuppressGreeting]：普通路径调度前的抑制判断（陪伴开启时普通问候让位给陪伴路径）。
 * [evaluate]：陪伴主动联系的完整门控合同（审计 R-06）——Worker 生成前与提交前都调用，
 * 每个条件独立判定并给出顺延时长；全部通过才允许生成/落盘/通知。
 */
object CompanionProactiveGate {

    /** 每日最多主动联系次数（Spec ACT 合同）。 */
    const val MAX_PER_DAY = 2

    /** 两次主动联系的最小间隔（毫秒）。 */
    const val MIN_INTERVAL_MS = 4L * 60 * 60 * 1000

    /** 用户最近发言后的静默窗口：30 分钟内有聊天不打扰（毫秒）。 */
    const val USER_SILENCE_MS = 30L * 60 * 1000

    /** 单一条件不满足时的顺延决策。 */
    sealed class GreetDecision {
        /** 全部条件满足，允许生成与投递。 */
        object Proceed : GreetDecision()

        /** 暂缓：reason 供日志；retryAfterMinutes 为建议顺延。 */
        data class Postpone(val reason: String, val retryAfterMinutes: Long) : GreetDecision()
    }

    /** 问候台账快照（持久化见 [CompanionGreetingLedger]，纯数据便于判定测试）。 */
    data class LedgerState(
        val countDate: String = "",
        val countToday: Int = 0,
        val lastGreetingAt: Long = 0L,
        val lastGreetingEventId: String = "",
        val pendingEventId: String = "",
        val pendingAt: Long = 0L
    )

    /**
     * 陪伴主动联系完整门控（生成前与提交前同一函数，审计 R-06）。
     *
     * @param notificationGranted 系统通知权限（未授权不生成、不落盘）
     * @param sessionExists 陪伴绑定会话仍存在于存储（恢复/重开/删除后失效）
     * @param uiForeground 用户正在应用内（前台不打扰，改约稍后）
     * @param lastUserMessageAt 用户最近一条消息时间（null = 会话尚无用户发言）
     * @param lastMessageIsUnansweredGreeting 最后一条消息是尚未被回应的主动问候
     */
    fun evaluate(
        config: CompanionConfig,
        ledger: LedgerState,
        nowMillis: Long = System.currentTimeMillis(),
        notificationGranted: Boolean,
        sessionExists: Boolean,
        uiForeground: Boolean,
        lastUserMessageAt: Long?,
        lastMessageIsUnansweredGreeting: Boolean
    ): GreetDecision {
        if (!config.enabled) return GreetDecision.Postpone("companion_disabled", 60)
        if (!config.proactiveEnabled) return GreetDecision.Postpone("proactive_disabled", 60)
        if (!notificationGranted) return GreetDecision.Postpone("notification_not_granted", 60)
        if (!sessionExists) return GreetDecision.Postpone("session_missing", 60)
        if (uiForeground) return GreetDecision.Postpone("ui_foreground", 30)
        if (lastMessageIsUnansweredGreeting) return GreetDecision.Postpone("last_greeting_unanswered", 240)

        inDoNotDisturb(config, nowMillis).let { dnd ->
            if (dnd) return GreetDecision.Postpone("dnd", minutesUntilDndEnd(config, nowMillis))
        }

        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        val today = dateKey(cal)
        val effectiveCount = if (ledger.countDate == today) ledger.countToday else 0
        if (effectiveCount >= MAX_PER_DAY) {
            return GreetDecision.Postpone("daily_limit", minutesUntilMidnight(nowMillis))
        }
        if (ledger.lastGreetingAt > 0 && nowMillis - ledger.lastGreetingAt < MIN_INTERVAL_MS) {
            val waitMin = ((MIN_INTERVAL_MS - (nowMillis - ledger.lastGreetingAt)) / 60000L).coerceAtLeast(60)
            return GreetDecision.Postpone("min_interval", waitMin)
        }
        if (lastUserMessageAt != null && nowMillis - lastUserMessageAt < USER_SILENCE_MS) {
            val waitMin = ((USER_SILENCE_MS - (nowMillis - lastUserMessageAt)) / 60000L).coerceAtLeast(5)
            return GreetDecision.Postpone("user_recently_active", waitMin)
        }
        return GreetDecision.Proceed
    }

    /** 生成前的预检（不含会话内容类条件）：通道/权限等硬前置。 */
    fun shouldSuppressGreeting(context: android.content.Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val config = CompanionConfigStore(context).load()
        if (!config.enabled) return false
        if (!config.proactiveEnabled) return true
        return inDoNotDisturb(config, nowMillis)
    }

    fun inDoNotDisturb(config: CompanionConfig, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val minutes = minuteOfDay(nowMillis)
        val start = config.dndStartMinute
        val end = config.dndEndMinute
        return if (start == end) {
            false // 起止相同视为未配置区间
        } else if (start < end) {
            minutes in start until end
        } else {
            // 跨午夜：如 23:00 → 08:00
            minutes >= start || minutes < end
        }
    }

    /** 距免打扰结束的分钟数（至少 60），供顺延调度。 */
    fun minutesUntilDndEnd(config: CompanionConfig, nowMillis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, config.dndEndMinute / 60)
        cal.set(java.util.Calendar.MINUTE, config.dndEndMinute % 60)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMillis) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return ((cal.timeInMillis - nowMillis) / 60000L).coerceAtLeast(60)
    }

    private fun minutesUntilMidnight(nowMillis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        return ((cal.timeInMillis - nowMillis) / 60000L).coerceAtLeast(60)
    }

    private fun minuteOfDay(nowMillis: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /** 本地日期键（yyyy-MM-dd），每日次数随日期翻新。 */
    fun dateKey(cal: java.util.Calendar): String =
        "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}
