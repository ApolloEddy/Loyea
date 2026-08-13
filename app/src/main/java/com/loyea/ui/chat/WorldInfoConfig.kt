package com.loyea.ui.chat

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 世界书全局排序模式（对齐 SillyTavern 全局「默认插入顺序」选项）。
 * ORDER 为默认：按条目的 ST order 升序（保持 v0.5.1 行为）。
 */
enum class WorldInfoInsertionOrder {
    ORDER,           // 按 order 升序（保持现状）
    KEY_LENGTH,      // 按首个主关键词长度降序
    ALPHABETICAL,    // 按 content 字典序
    INSERT_AT_TOP,   // 按 order 升序（置顶语义）
    INSERT_AT_BOTTOM // 按 order 降序（置底语义）
}

/**
 * 世界书作用域：
 * - GLOBAL = 全局共享书（跨所有会话，v0.5.2 及之前的行为）；
 * - SESSION = 某会话专属书（文件存在时完全替代全局书；不存在时回退全局书）。
 */
enum class WorldInfoScope { GLOBAL, SESSION }

/**
 * 世界书全局配置（与条目文件分开存储，存 SharedPreferences）。
 * 纯 Kotlin 数据类（storage 才有 Android 依赖），便于单测。
 */
data class WorldInfoConfig(
    val scanDepth: Int = 10,                          // 条目 depth=0 时的回退扫描窗口（条数）
    val position: String = "bottom",                  // 块注入位置："bottom" | "top"
    val insertionOrderMode: WorldInfoInsertionOrder = WorldInfoInsertionOrder.ORDER,
    val tokenBudget: Long = 2048,                     // 世界书注入 token 预算（estimateTokens 估算后裁剪）
    val recursionDepthCap: Int = 3,                   // 递归轮次上限
    val allowRecursion: Boolean = true,               // 全局递归总开关
    val emitGroupHeaders: Boolean = false             // 分组前输出 "# <group>" 注释行
)

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
        emitGroupHeaders = prefs.getBoolean(K_GROUP_HEADERS, false)
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
                emitGroupHeaders = if (obj.has("emitGroupHeaders")) obj.get("emitGroupHeaders").asBoolean else false
            )
        } catch (e: Exception) {
            WorldInfoConfig()
        }
    }
}
