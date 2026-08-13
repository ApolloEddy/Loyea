package com.loyea.health

import com.loyea.bluetooth.WatchBluetoothClient

/**
 * 直连手表蓝牙数据源（OPPO Watch 专属链路，保留现状）。实时值来自 [WatchBluetoothClient] 的 StateFlow。
 * 未连接 → NoData；已连接但无心率 → 仍返回 Success（让格式化层输出 "Waiting for sensor..."，并保持蓝牙优先级）。
 */
class SmartwatchBluetoothDataSource : HealthDataSource {
    override val sourceLabel: String = "Smartwatch Bluetooth"

    override suspend fun readSnapshot(): HealthDataResult<HealthSnapshot> {
        if (WatchBluetoothClient.connectionState.value != WatchBluetoothClient.ConnectionState.CONNECTED) {
            return HealthDataResult.NoData
        }
        val snapshot = HealthSnapshot(
            heartRateBpm = WatchBluetoothClient.heartRate.value.takeIf { it > 0 },
            steps = WatchBluetoothClient.steps.value,
            sleepMinutes = WatchBluetoothClient.sleepDuration.value.takeIf { it > 0 },
            sleepQuality = WatchBluetoothClient.sleepQuality.value.takeIf { it.isNotBlank() && it != "Unknown" },
            exerciseMinutes = WatchBluetoothClient.exerciseDuration.value.takeIf { it > 0 },
            exerciseCalories = WatchBluetoothClient.exerciseCalories.value.takeIf { it > 0 },
            exerciseType = WatchBluetoothClient.exerciseType.value.takeIf { it.isNotBlank() },
            sourceLabel = sourceLabel
        )
        return HealthDataResult.Success(snapshot)
    }
}
