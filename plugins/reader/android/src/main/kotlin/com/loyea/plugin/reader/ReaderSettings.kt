package com.loyea.plugin.reader

import android.content.Context
import android.content.SharedPreferences

/** 伴读设置（Reader Spec §9/§10）：防剧透开关、主动感言节流、白名单。 */
class ReaderSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 防剧透屏障开关（Spec §7）：默认开；关闭需设置页二次确认（M3 从简：仅开关）。 */
    fun antiSpoilerEnabled(): Boolean = prefs.getBoolean(KEY_ANTI_SPOILER, true)

    fun setAntiSpoilerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANTI_SPOILER, enabled).commit()
    }

    /** 主动感言最小间隔分钟（Spec §9：每章 ≤2 次、间隔 ≥5 分钟）。 */
    fun minActiveCommentIntervalMin(): Long =
        prefs.getLong(KEY_ACTIVE_INTERVAL, 5L)

    fun setActiveCommentIntervalMin(minutes: Long) {
        prefs.edit().putLong(KEY_ACTIVE_INTERVAL, minutes.coerceAtLeast(1L)).commit()
    }

    companion object {
        private const val PREFS = "loyea_reader_prefs"
        private const val KEY_ANTI_SPOILER = "reader_anti_spoiler"
        private const val KEY_ACTIVE_INTERVAL = "reader_active_interval_min"
    }
}
