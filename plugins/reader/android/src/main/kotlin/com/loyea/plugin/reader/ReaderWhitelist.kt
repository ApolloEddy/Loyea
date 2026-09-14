package com.loyea.plugin.reader

import android.content.Context

/**
 * 伴读书包白名单（Reader Spec §4）：内置主流阅读器 + 用户自定义追加。
 * M1 默认含 Chrome，供模拟器验证采集链路；发布前可收窄。
 */
object ReaderWhitelist {

    private val DEFAULTS = setOf(
        "com.tencent.weread",      // 微信读书
        "com.dragon.read",         // 番茄小说
        "com.qidian.QDReader",     // 起点读书
        "com.qq.reader",           // QQ阅读
        "com.kuaishou.nebula",     // 小说类兜底占位（按需调整）
        "com.android.chrome",      // 浏览器阅读（M1 验证用）
    )

    private const val PREFS = "loyea_reader_prefs"
    private const val KEY_EXTRA = "reader_whitelist_extra"
    private const val KEY_ENABLED = "reader_enabled"

    fun contains(packageName: String, context: Context): Boolean {
        if (packageName == "com.loyea" || packageName == "com.loyea.rebuild.debug") return false
        return packageName in DEFAULTS || packageName in extras(context)
    }

    fun extras(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXTRA, emptySet()) ?: emptySet()

    fun addExtra(packageName: String, context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = extras(context) + packageName
        return prefs.edit().putStringSet(KEY_EXTRA, next).commit()
    }

    fun removeExtra(packageName: String, context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = extras(context) - packageName
        return prefs.edit().putStringSet(KEY_EXTRA, next).commit()
    }

    /** 伴读总开关（挂接陪伴模式的门禁之一；默认开——真正门槛是无障碍授权）。 */
    fun isReaderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setReaderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).commit()
    }
}
