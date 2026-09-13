package com.loyea.plugin.companion.runtime

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 陪伴内部经过时间（Spec 接入文档 §6.4）。
 *
 * 为调制器提供单调的逻辑秒数：同一开机周期内用 elapsedRealtime 差值；
 * 检测到设备重启时用 max(0, nowWall - savedWall) 补算离线间隔，
 * 墙钟回拨不产生负时间，异常前跳按七天上限裁剪并记录原因。
 * 时区只影响显示，不影响动力学时间。
 */
interface CompanionClock {
    /** 当前逻辑时间（秒；单调，跨重启非负递增）。 */
    fun nowSeconds(): Double

    /** 当前逻辑时间的墙钟参考（仅供显示/调度，不进入动力学）。 */
    fun wallNowMillis(): Long
}

/** 锚点持久化（JSON：boot_count / wall_millis / elapsed_millis / logic_seconds）。 */
class CompanionClockAnchor(private val prefs: android.content.SharedPreferences) {

    fun load(): JsonObject? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
    }

    fun save(anchor: JsonObject) {
        prefs.edit().putString(KEY, anchor.toString()).apply()
    }

    companion object {
        private const val KEY = "runtime_clock_anchor"
    }
}

class SystemCompanionClock(
    context: Context,
    /** 允许的最大离线补算间隔（秒）；工程保护上限，不代表心理学常数。 */
    private val maxOfflineCatchUpSeconds: Double = 7.0 * 24 * 3600.0,
) : CompanionClock {

    private val appContext = context.applicationContext
    private val anchorStore = CompanionClockAnchor(
        appContext.getSharedPreferences(CompanionContractPrefs.PREFS_NAME, Context.MODE_PRIVATE),
    )

    @Volatile
    private var cachedBoot: Int = Int.MIN_VALUE

    @Volatile
    private var cachedAnchorElapsedMillis: Long = 0L

    @Volatile
    private var cachedAnchorLogicSeconds: Double = 0.0

    override fun wallNowMillis(): Long = System.currentTimeMillis()

    /** 当前锚点快照（备份 v3 与 owner 记录用；非动力学输入）。 */
    @Synchronized
    fun anchorSnapshotJson(): JsonObject {
        // 触发一次 nowSeconds 的锚点解析，确保缓存与持久化一致。
        nowSeconds()
        return JsonObject().apply {
            addProperty("boot_count", if (cachedBoot == Int.MIN_VALUE) -1 else cachedBoot)
            addProperty("elapsed_millis", cachedAnchorElapsedMillis)
            addProperty("logic_seconds", cachedAnchorLogicSeconds)
            addProperty("wall_millis", System.currentTimeMillis())
        }
    }

    @Synchronized
    override fun nowSeconds(): Double {
        val elapsedNow = SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        val bootCount = currentBootCount()

        if (bootCount != cachedBoot) {
            val anchor = anchorStore.load()
            val anchorBoot = anchor?.get("boot_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: Int.MIN_VALUE
            if (anchor != null && anchorBoot == bootCount) {
                // 同一开机周期（进程重启）：继续用持久锚点 + 单调钟差。
                cachedAnchorElapsedMillis = anchor.get("elapsed_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                cachedAnchorLogicSeconds = anchor.get("logic_seconds")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
            } else {
                // 首次或设备已重启：用墙钟差补算离线间隔后重置锚点。
                val anchorWall = anchor?.get("wall_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: wallNow
                val anchorLogic = anchor?.get("logic_seconds")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                val offline = (wallNow - anchorWall).coerceAtLeast(0L) / 1000.0
                val clamped = offline.coerceAtMost(maxOfflineCatchUpSeconds)
                cachedAnchorElapsedMillis = elapsedNow
                cachedAnchorLogicSeconds = (anchorLogic + clamped).coerceAtLeast(0.0)
                anchorStore.save(
                    JsonObject().apply {
                        addProperty("boot_count", bootCount)
                        addProperty("wall_millis", wallNow)
                        addProperty("elapsed_millis", elapsedNow)
                        addProperty("logic_seconds", cachedAnchorLogicSeconds)
                    },
                )
            }
            cachedBoot = bootCount
        }
        // 同一开机周期内 elapsedRealtime 单调；锚点保持常量即可，无需每次写盘。
        return (cachedAnchorLogicSeconds + (elapsedNow - cachedAnchorElapsedMillis) / 1000.0)
            .coerceAtLeast(0.0)
    }

    private fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)
}

/** 测试与确定性回放用固定时钟。 */
class FixedCompanionClock(var seconds: Double = 0.0) : CompanionClock {
    override fun nowSeconds(): Double = seconds
    override fun wallNowMillis(): Long = (seconds * 1000.0).toLong()
    fun advance(deltaSeconds: Double) {
        seconds += deltaSeconds
    }
}

/** 偏好文件名与 CompanionContract.PREFS_NAME 保持一致（避免直接依赖 UI 层符号）。 */
private object CompanionContractPrefs {
    const val PREFS_NAME = "loyea_companion_prefs"
}
