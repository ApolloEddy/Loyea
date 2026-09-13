package com.loyea.plugin.companion.runtime

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 每次外发前生效的权限与来源有效性快照（Spec 接入文档 §8.4）。
 *
 * policyRevision 独立于会话代际：权限/来源开关变化即递增；
 * 冻结请求在真正交给 transport 前必须按最新快照重新校验。
 */
data class CompanionPolicySnapshot(
    val revision: Long,
    /** 物理感知总开关（载体：ChatSession.useSystemTime）。 */
    val physicalPerceptionEnabled: Boolean,
    /** 文字情绪感知开关（新增独立开关，默认开；用户关闭后不得被自动重开）。 */
    val textPerceptionEnabled: Boolean,
    /** 记忆真实来源修订（记忆删除/修订后失效来源与请求缓存）。 */
    val memoryRevision: Long,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("revision", revision)
        addProperty("physical_perception_enabled", physicalPerceptionEnabled)
        addProperty("text_perception_enabled", textPerceptionEnabled)
        addProperty("memory_revision", memoryRevision)
    }

    companion object {
        fun fromJson(o: JsonObject): CompanionPolicySnapshot = CompanionPolicySnapshot(
            revision = o.get("revision")?.asLong ?: 0L,
            physicalPerceptionEnabled = o.get("physical_perception_enabled")?.asBoolean ?: false,
            textPerceptionEnabled = o.get("text_perception_enabled")?.asBoolean ?: true,
            memoryRevision = o.get("memory_revision")?.asLong ?: 0L,
        )
    }
}

/**
 * 策略簿：持久化 policyRevision，配置提交入口（开关变化）负责 bump。
 * 放在 companion prefs 的独立 key；与 CompanionConfigStore 的配置 JSON 解耦，
 * 任何写入路径漏调 bump 时，版本落后只会导致多校验一次，不会放行失效来源。
 */
interface CompanionPolicyBook {
    fun current(physical: Boolean, text: Boolean, memoryRevision: Long): CompanionPolicySnapshot

    /** 配置提交入口在感知/权限相关开关变化时调用（幂等；重复调用无害）。 */
    fun bump(): Long
}

/** 默认实现：companion prefs 持久化 revision。 */
class PrefsCompanionPolicyBook(context: Context) : CompanionPolicyBook {

    private val prefs = context.applicationContext
        .getSharedPreferences("loyea_companion_prefs", Context.MODE_PRIVATE)

    override fun current(physical: Boolean, text: Boolean, memoryRevision: Long): CompanionPolicySnapshot {
        val revision = prefs.getLong(KEY_REVISION, 0L)
        return CompanionPolicySnapshot(revision, physical, text, memoryRevision)
    }

    override fun bump(): Long {
        val next = prefs.getLong(KEY_REVISION, 0L) + 1
        prefs.edit().putLong(KEY_REVISION, next).apply()
        return next
    }

    companion object {
        private const val KEY_REVISION = "companion_policy_revision"
    }
}
