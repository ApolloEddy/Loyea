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
 * AC-17/ACT-02 主动问候门控：GreetingWorker 执行前调用。
 *
 * 仅当陪伴模式开启时介入——
 * - 主动联系关闭：抑制问候（不落盘、不发通知）；
 * - 处于免打扰时段（支持跨午夜区间）：抑制问候；
 * 普通模式（未开启陪伴）永远返回 false，宿主原有开关与深夜静默逻辑全权生效，
 * 两种模式共用同一调度链、不重复问候。
 */
object CompanionProactiveGate {

    fun shouldSuppressGreeting(context: android.content.Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val config = CompanionConfigStore(context).load()
        if (!config.enabled) return false
        if (!config.proactiveEnabled) return true
        return inDoNotDisturb(config, nowMillis)
    }

    fun inDoNotDisturb(config: CompanionConfig, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
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
}
