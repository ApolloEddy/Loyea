package com.loyea.health

import android.content.Context
import com.loyea.bluetooth.WatchBluetoothClient

/**
 * 模拟数据源（演示/测试用）。仅当 prefs `sim_watch_connected` 开启时产生数据。
 * 由 [HealthSnapshotRepository] 作为最后兜底使用，不压过真实数据。
 */
class SimulatedDataSource(private val context: Context) : HealthDataSource {
    override val sourceLabel: String = "Simulated"

    override suspend fun readSnapshot(): HealthDataResult<HealthSnapshot> {
        val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sim_watch_connected", false)) return HealthDataResult.NoData
        val moving = prefs.getBoolean("sim_watch_moving", false)
        val heartRate = if (moving) (100..140).random() else (60..90).random()
        val steps = WatchBluetoothClient.steps.value.takeIf { it > 0 } ?: (3000..10000).random()
        return HealthDataResult.Success(
            HealthSnapshot(
                heartRateBpm = heartRate,
                steps = steps,
                sourceLabel = sourceLabel
            )
        )
    }
}
