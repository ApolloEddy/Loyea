package com.loyea.ui.chat

import com.loyea.context.core.*
import com.loyea.plugins.tavern.core.*

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 世界书作用域：
 * - GLOBAL = 全局共享书（跨所有会话，v0.5.2 及之前的行为）；
 * - SESSION = 某会话专属书（文件存在时完全替代全局书；不存在时回退全局书）。
 */
enum class WorldInfoScope { GLOBAL, SESSION }

/**
 * WorldInfoConfig 的 SharedPreferences 读写（key 前缀 world_info_*）。
 */
object WorldInfoConfigStorage {
    private const val K_SCAN = "world_info_scan_depth"
    private const val K_POS = "world_info_position"
    private const val K_ORDER = "world_info_order_mode"
    private const val K_BUDGET = "world_info_token_budget"
    private const val K_REC_DEPTH = "world_info_recursion_depth_cap"
    private const val K_REC_ALLOW = "world_info_allow_recursion"
    private const val K_GROUP_HEADERS = "world_info_emit_group_headers"
    private const val K_CASE = "world_info_case_sensitive"
    private const val K_WHOLE = "world_info_match_whole_words"
    private const val K_GROUP_SCORING = "world_info_group_scoring"
    private const val K_BUDGET_CAP = "world_info_budget_cap"
    private const val K_INCLUDE_NAMES = "world_info_include_names"

    fun load(prefs: SharedPreferences): WorldInfoConfig = WorldInfoConfig(
        scanDepth = prefs.getInt(K_SCAN, 10),
        position = prefs.getString(K_POS, "bottom") ?: "bottom",
        insertionOrderMode = runCatching {
            WorldInfoInsertionOrder.valueOf(
                prefs.getString(K_ORDER, WorldInfoInsertionOrder.ORDER.name)
                    ?: WorldInfoInsertionOrder.ORDER.name
            )
        }.getOrDefault(WorldInfoInsertionOrder.ORDER),
        tokenBudget = prefs.getLong(K_BUDGET, 2048),
        recursionDepthCap = prefs.getInt(K_REC_DEPTH, 3),
        allowRecursion = prefs.getBoolean(K_REC_ALLOW, true),
        emitGroupHeaders = prefs.getBoolean(K_GROUP_HEADERS, false),
        caseSensitive = prefs.getBoolean(K_CASE, false),
        matchWholeWords = prefs.getBoolean(K_WHOLE, false),
        useGroupScoring = prefs.getBoolean(K_GROUP_SCORING, false),
        budgetCap = prefs.getLong(K_BUDGET_CAP, 0),
        includeNames = prefs.getBoolean(K_INCLUDE_NAMES, true)
    )

    fun save(prefs: SharedPreferences, config: WorldInfoConfig) {
        prefs.edit()
            .putInt(K_SCAN, config.scanDepth)
            .putString(K_POS, config.position)
            .putString(K_ORDER, config.insertionOrderMode.name)
            .putLong(K_BUDGET, config.tokenBudget)
            .putInt(K_REC_DEPTH, config.recursionDepthCap)
            .putBoolean(K_REC_ALLOW, config.allowRecursion)
            .putBoolean(K_GROUP_HEADERS, config.emitGroupHeaders)
            .putBoolean(K_CASE, config.caseSensitive)
            .putBoolean(K_WHOLE, config.matchWholeWords)
            .putBoolean(K_GROUP_SCORING, config.useGroupScoring)
            .putLong(K_BUDGET_CAP, config.budgetCap)
            .putBoolean(K_INCLUDE_NAMES, config.includeNames)
            .apply()
    }

    private val gson = Gson()

    /**
     * 序列化为紧凑 JSON（供会话书文件内嵌 config 使用）。
     */
    fun toJson(config: WorldInfoConfig): String {
        val obj = JsonObject()
        obj.addProperty("scanDepth", config.scanDepth)
        obj.addProperty("position", config.position)
        obj.addProperty("insertionOrderMode", config.insertionOrderMode.name)
        obj.addProperty("tokenBudget", config.tokenBudget)
        obj.addProperty("recursionDepthCap", config.recursionDepthCap)
        obj.addProperty("allowRecursion", config.allowRecursion)
        obj.addProperty("emitGroupHeaders", config.emitGroupHeaders)
        obj.addProperty("caseSensitive", config.caseSensitive)
        obj.addProperty("matchWholeWords", config.matchWholeWords)
        obj.addProperty("useGroupScoring", config.useGroupScoring)
        obj.addProperty("budgetCap", config.budgetCap)
        obj.addProperty("includeNames", config.includeNames)
        return obj.toString()
    }

    /**
     * 从 JSON 还原配置，缺字段逐个兜底默认值（原始类型缺字段会退化为 0/false，必须显式归位）。
     */
    fun fromJson(json: String?): WorldInfoConfig {
        if (json.isNullOrBlank()) return WorldInfoConfig()
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            WorldInfoConfig(
                scanDepth = if (obj.has("scanDepth")) obj.get("scanDepth").asInt else 10,
                position = if (obj.has("position")) obj.get("position").asString else "bottom",
                insertionOrderMode = if (obj.has("insertionOrderMode")) {
                    runCatching { WorldInfoInsertionOrder.valueOf(obj.get("insertionOrderMode").asString) }
                        .getOrDefault(WorldInfoInsertionOrder.ORDER)
                } else WorldInfoInsertionOrder.ORDER,
                tokenBudget = if (obj.has("tokenBudget")) obj.get("tokenBudget").asLong else 2048,
                recursionDepthCap = if (obj.has("recursionDepthCap")) obj.get("recursionDepthCap").asInt else 3,
                allowRecursion = if (obj.has("allowRecursion")) obj.get("allowRecursion").asBoolean else true,
                emitGroupHeaders = if (obj.has("emitGroupHeaders")) obj.get("emitGroupHeaders").asBoolean else false,
                caseSensitive = if (obj.has("caseSensitive")) obj.get("caseSensitive").asBoolean else false,
                matchWholeWords = if (obj.has("matchWholeWords")) obj.get("matchWholeWords").asBoolean else false,
                useGroupScoring = if (obj.has("useGroupScoring")) obj.get("useGroupScoring").asBoolean else false,
                budgetCap = if (obj.has("budgetCap")) obj.get("budgetCap").asLong else 0,
                includeNames = if (obj.has("includeNames")) obj.get("includeNames").asBoolean else true
            )
        } catch (e: Exception) {
            WorldInfoConfig()
        }
    }
}
