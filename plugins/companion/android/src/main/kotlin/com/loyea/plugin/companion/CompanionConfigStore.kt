package com.loyea.plugin.companion

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * 陪伴模式配置（Spec §9 最小合同：CompanionConfig）。
 *
 * 持久化在插件自有 SharedPreferences（JSON 单文档，原子写）。
 * enabled 只表达“当前模式入口”；关闭陪伴模式（NAV-03/DATA-06）永不清空
 * sessionId 及聊天数据，再次开启恢复原记录。
 */
data class CompanionConfig(
    val enabled: Boolean = false,
    val sessionId: String = "",
    val displayName: String = "Loyea",
    val avatarUri: String = "",
    val userCalledName: String = "",
    /** FUN-05：仅首次创建陪伴配置时默认开；用户关掉后不得被重开。 */
    val perceptionEnabled: Boolean = true,
    /** S-01 建议：未保存过用户选择时默认关；关闭不影响其他陪伴功能。 */
    val proactiveEnabled: Boolean = false,
    /** 免打扰（分钟数，支持跨午夜区间）。 */
    val dndStartMinute: Int = 23 * 60,
    val dndEndMinute: Int = 8 * 60,
    val configVersion: Int = 1,
    val createdAt: Long = 0L,
    /** 开启陪伴模式前宿主的 current_session_id（NAV-03：关闭后返回最近的普通会话）。 */
    val lastNormalSessionId: String = ""
) {
    val effectiveUserName: String get() = userCalledName.ifBlank { "你" }
}

class CompanionConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(CompanionContract.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): CompanionConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return CompanionConfig()
        return runCatching {
            val o = org.json.JSONObject(raw)
            CompanionConfig(
                enabled = o.optBoolean("enabled", false),
                sessionId = o.optString("sessionId", ""),
                displayName = o.optString("displayName", "Loyea").ifBlank { "Loyea" },
                avatarUri = o.optString("avatarUri", ""),
                userCalledName = o.optString("userCalledName", ""),
                perceptionEnabled = o.optBoolean("perceptionEnabled", true),
                proactiveEnabled = o.optBoolean("proactiveEnabled", false),
                dndStartMinute = o.optInt("dndStartMinute", 23 * 60),
                dndEndMinute = o.optInt("dndEndMinute", 8 * 60),
                configVersion = o.optInt("configVersion", 1),
                createdAt = o.optLong("createdAt", 0L),
                lastNormalSessionId = o.optString("lastNormalSessionId", "")
            )
        }.getOrDefault(CompanionConfig())
    }

    fun save(config: CompanionConfig) {
        val o = org.json.JSONObject()
        o.put("enabled", config.enabled)
        o.put("sessionId", config.sessionId)
        o.put("displayName", config.displayName)
        o.put("avatarUri", config.avatarUri)
        o.put("userCalledName", config.userCalledName)
        o.put("perceptionEnabled", config.perceptionEnabled)
        o.put("proactiveEnabled", config.proactiveEnabled)
        o.put("dndStartMinute", config.dndStartMinute)
        o.put("dndEndMinute", config.dndEndMinute)
        o.put("configVersion", config.configVersion)
        o.put("createdAt", config.createdAt)
        o.put("lastNormalSessionId", config.lastNormalSessionId)
        prefs.edit().putString(KEY_CONFIG, o.toString()).apply()
    }

    /** FUN-05 语义：仅当陪伴配置从未创建过（无 createdAt）时应用默认值，之后不回改。 */
    fun update(transform: (CompanionConfig) -> CompanionConfig): CompanionConfig {
        val next = transform(load())
        save(next)
        return next
    }

    private companion object {
        const val KEY_CONFIG = "companion_config_json"
    }
}

/**
 * 进程内模式状态：MainActivity 以此决定渲染陪伴外壳还是普通导航外壳。
 * 变更一律经 refresh() 从磁盘重读，保证与持久化一致（NAV-02 同一模式解析逻辑）。
 */
object CompanionModeState {
    val enabled: MutableState<Boolean> = mutableStateOf(false)

    fun refresh(context: Context) {
        enabled.value = CompanionConfigStore(context).load().enabled
    }
}
