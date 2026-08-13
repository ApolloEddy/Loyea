package com.loyea.health

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 健康快照仓库：按优先级合并各数据源，替代原 PhysicalContextManager / PerceptionMcpServer 两处
 * 逐行复制的三态 triage。
 *
 * 优先级：真实蓝牙 > Health Connect > 模拟。血压/睡眠只来自 Health Connect（蓝牙补缺）。
 * 模拟仅在 `sim_watch_connected` 开启且真实缺失 HR/静息 HR/steps 时兜底。
 */
class HealthSnapshotRepository(context: Context) {
    private val healthConnect = HealthConnectDataSource(context)
    private val bluetooth = SmartwatchBluetoothDataSource()
    private val simulated = SimulatedDataSource(context)
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var cache: Pair<Long, HealthSnapshot>? = null

    /** 取最优快照，带短 TTL 缓存（默认 15s）。 */
    suspend fun readBestSnapshot(ttlMillis: Long = 15_000L): HealthSnapshot = withContext(Dispatchers.IO) {
        cache?.let { (t, s) ->
            if (System.currentTimeMillis() - t < ttlMillis) return@withContext s
        }

        val btSnap = (bluetooth.readSnapshot() as? HealthDataResult.Success)?.data
        val hcSnap = (healthConnect.readSnapshot() as? HealthDataResult.Success)?.data
        val simResult = simulated.readSnapshot()
        val simEnabled = prefs.getBoolean("sim_watch_connected", false)

        val base = when {
            btSnap != null -> btSnap
            hcSnap != null -> hcSnap
            simEnabled -> (simResult as? HealthDataResult.Success)?.data
            else -> null
        } ?: HealthSnapshot()

        // 蓝牙为主源时，用 Health Connect 补缺（血压/睡眠等蓝牙没有的）
        val merged = if (btSnap != null) base.fillMissingFrom(hcSnap ?: HealthSnapshot()) else base

        // 模拟兜底：仅当 sim 开关开启且真实缺失对应字段
        val final = if (simEnabled) {
            val simSnap = (simResult as? HealthDataResult.Success)?.data
            if (simSnap != null) {
                merged.copy(
                    heartRateBpm = merged.heartRateBpm ?: simSnap.heartRateBpm,
                    restingHeartRateBpm = merged.restingHeartRateBpm ?: simSnap.restingHeartRateBpm,
                    steps = merged.steps ?: simSnap.steps
                )
            } else merged
        } else merged

        cache = System.currentTimeMillis() to final
        final
    }
}
