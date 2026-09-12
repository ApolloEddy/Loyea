package com.loyea.plugin.companion

import android.content.Context
import com.google.gson.Gson

/**
 * 主动联系台账（审计 R-06）：持久化限频状态与稳定事件 ID，进程死亡/任务重投不重置。
 * 存放于插件自有 SharedPreferences（独立键，不与用户可见配置混存）。
 */
class CompanionGreetingLedger(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(CompanionContract.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): CompanionProactiveGate.LedgerState {
        val raw = prefs.getString(KEY_LEDGER, null) ?: return CompanionProactiveGate.LedgerState()
        return runCatching { gson.fromJson(raw, CompanionProactiveGate.LedgerState::class.java) }
            .getOrNull() ?: CompanionProactiveGate.LedgerState()
    }

    fun save(state: CompanionProactiveGate.LedgerState) {
        prefs.edit().putString(KEY_LEDGER, gson.toJson(state)).apply()
    }

    /** 生成开始：登记待提交事件（崩溃后重投可据此去重）。 */
    fun beginEvent(eventId: String, nowMillis: Long) =
        save(load().copy(pendingEventId = eventId, pendingAt = nowMillis))

    /** 提交完成：清待办、累计当日次数、记录最近问候。 */
    fun commitEvent(eventId: String, nowMillis: Long) {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        val today = CompanionProactiveGate.dateKey(cal)
        val current = load()
        val countToday = if (current.countDate == today) current.countToday + 1 else 1
        save(
            current.copy(
                countDate = today,
                countToday = countToday,
                lastGreetingAt = nowMillis,
                lastGreetingEventId = eventId,
                pendingEventId = "",
                pendingAt = 0L
            )
        )
    }

    /** 清除遗留待办（消息已在库=已提交过；或待办过期）。 */
    fun clearPending() =
        save(load().copy(pendingEventId = "", pendingAt = 0L))

    private companion object {
        const val KEY_LEDGER = "greeting_ledger_json"
    }
}

/**
 * 进程内运行时状态：Activity 生命周期回写，Worker 读取。
 * 审计 R-06「正在使用中不打扰」的可执行近似：应用前台时顺延而非生成。
 */
object CompanionRuntime {
    @Volatile var uiForeground: Boolean = false
}
