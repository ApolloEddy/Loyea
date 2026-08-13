package com.loyea.health

/** 健康指标类型。 */
enum class HealthMetric { HEART_RATE, STEPS, SLEEP, BLOOD_PRESSURE, RESTING_HEART_RATE }

/** 单个指标在健康连接中的可用状态。 */
enum class MetricAvailability { GRANTED_WITH_DATA, GRANTED_NO_DATA, NO_PERMISSION, SDK_UNAVAILABLE }

/** 检测到的数据来源生态（按 dataOrigins 包名映射）。 */
enum class HealthEcosystem { XIAOMI, HUAWEI, SAMSUNG, OPPO, OTHER, NONE }

/** 健康连接配对/同步状态，供设置页配对状态面板展示。 */
data class HealthPairingStatus(
    val sdkStatus: Int,
    val apiLevelNote: String?,
    val grantedPermissions: Set<String>,
    val metrics: Map<HealthMetric, MetricAvailability>,
    val dataOrigins: Set<String>,
    val ecosystem: HealthEcosystem,
    val lastSyncTimeMillis: Long?,
    val guidanceText: String,
) {
    val hasSdkAvailable: Boolean get() = sdkStatus == 3 // HealthConnectClient.SDK_AVAILABLE
    val hasAnyGrantedWithData: Boolean get() = metrics.values.any { it == MetricAvailability.GRANTED_WITH_DATA }
}
