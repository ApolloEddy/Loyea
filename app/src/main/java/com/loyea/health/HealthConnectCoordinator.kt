package com.loyea.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * 配对协调器：探测健康连接 SDK / 权限 / 各指标数据可用性 / 数据来源生态，并生成配对引导文案。
 * 「配对获取」的核心——让符合条件的人（主要是小米）知道该去哪开启同步。
 */
class HealthConnectCoordinator(private val context: Context) {

    suspend fun getSdkStatus(): Int = withContext(Dispatchers.IO) {
        try {
            HealthConnectClient.getSdkStatus(context)
        } catch (e: Exception) {
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }

    /** 构建完整配对状态（suspend，IO）。 */
    suspend fun buildPairingStatus(): HealthPairingStatus = withContext(Dispatchers.IO) {
        val sdk = try {
            HealthConnectClient.getSdkStatus(context)
        } catch (e: Exception) {
            HealthConnectClient.SDK_UNAVAILABLE
        }

        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            return@withContext HealthPairingStatus(
                sdkStatus = sdk,
                apiLevelNote = sdkNote(sdk),
                grantedPermissions = emptySet(),
                metrics = emptyMap(),
                dataOrigins = emptySet(),
                ecosystem = HealthEcosystem.NONE,
                lastSyncTimeMillis = null,
                guidanceText = sdkGuidance(sdk)
            )
        }

        try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()

            val probes = listOf(
                HealthMetric.HEART_RATE to HeartRateRecord::class,
                HealthMetric.STEPS to StepsRecord::class,
                HealthMetric.SLEEP to SleepSessionRecord::class,
                HealthMetric.BLOOD_PRESSURE to BloodPressureRecord::class,
                HealthMetric.RESTING_HEART_RATE to RestingHeartRateRecord::class
            )
            val metrics = mutableMapOf<HealthMetric, MetricAvailability>()
            val origins = mutableSetOf<String>()
            var lastSync = 0L
            val now = Instant.now()

            for ((metric, type) in probes) {
                val perm = HealthPermission.getReadPermission(type)
                if (perm !in granted) {
                    metrics[metric] = MetricAvailability.NO_PERMISSION
                    continue
                }
                val probe = probeMetric(client, type, now)
                if (probe.hasRecords) {
                    metrics[metric] = MetricAvailability.GRANTED_WITH_DATA
                    origins += probe.origins
                    if (probe.lastModified > lastSync) lastSync = probe.lastModified
                } else {
                    metrics[metric] = MetricAvailability.GRANTED_NO_DATA
                }
            }

            val ecosystem = detectEcosystem(origins)
            HealthPairingStatus(
                sdkStatus = sdk,
                apiLevelNote = sdkNote(sdk),
                grantedPermissions = granted,
                metrics = metrics,
                dataOrigins = origins,
                ecosystem = ecosystem,
                lastSyncTimeMillis = lastSync.takeIf { it > 0 },
                guidanceText = guidanceFor(ecosystem, metrics)
            )
        } catch (e: Exception) {
            HealthPairingStatus(
                sdkStatus = sdk,
                apiLevelNote = sdkNote(sdk),
                grantedPermissions = emptySet(),
                metrics = emptyMap(),
                dataOrigins = emptySet(),
                ecosystem = HealthEcosystem.NONE,
                lastSyncTimeMillis = null,
                guidanceText = "读取健康连接状态失败：${e.message}"
            )
        }
    }

    private data class ProbeResult(
        val hasRecords: Boolean,
        val origins: Set<String>,
        val lastModified: Long
    )

    /** 探测某类型近 7 天是否有记录 + 来源 + 最近修改时间（作为"最近同步时间"）。 */
    private suspend fun probeMetric(
        client: HealthConnectClient,
        recordType: KClass<out Record>,
        now: Instant
    ): ProbeResult {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now),
                    ascendingOrder = false,
                    pageSize = 1
                )
            )
            val record = response.records.firstOrNull()
            if (record != null) {
                ProbeResult(
                    hasRecords = true,
                    origins = setOf(record.metadata.dataOrigin.packageName),
                    lastModified = record.metadata.lastModifiedTime.toEpochMilli()
                )
            } else {
                ProbeResult(false, emptySet(), 0L)
            }
        } catch (e: Exception) {
            ProbeResult(false, emptySet(), 0L)
        }
    }

    private fun detectEcosystem(origins: Set<String>): HealthEcosystem {
        val known = mapOf(
            "com.mi.health" to HealthEcosystem.XIAOMI,          // 小米运动健康 (Mi Fitness)
            "com.xiaomi.hm.health" to HealthEcosystem.XIAOMI,   // 小米运动 (Mi Fit 旧)
            "com.huawei.health" to HealthEcosystem.HUAWEI,
            "com.samsung.shealth" to HealthEcosystem.SAMSUNG,
            "com.heytap.health" to HealthEcosystem.OPPO
        )
        return origins.firstNotNullOfOrNull { known[it] }
            ?: if (origins.isEmpty()) HealthEcosystem.NONE else HealthEcosystem.OTHER
    }

    private fun sdkNote(sdk: Int): String? = when (sdk) {
        HealthConnectClient.SDK_AVAILABLE -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "系统级健康数据平台"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "需『健康连接』App"
            else -> "Android 13 以下不支持"
        }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "健康连接需更新"
        else -> null
    }

    private fun sdkGuidance(sdk: Int): String = when (sdk) {
        HealthConnectClient.SDK_UNAVAILABLE ->
            "需要 Android 13+（或安装『健康连接』App）才能使用健康数据。"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            "已安装的健康连接版本过旧，请更新后重试。"
        else -> ""
    }

    private fun guidanceFor(
        ecosystem: HealthEcosystem,
        metrics: Map<HealthMetric, MetricAvailability>
    ): String {
        val anyGrantedWithData = metrics.values.any { it == MetricAvailability.GRANTED_WITH_DATA }
        return when {
            anyGrantedWithData -> when (ecosystem) {
                HealthEcosystem.XIAOMI ->
                    "已检测到小米运动健康数据。请在『小米运动健康』中绑定手表/手环，并开启『设置 → 健康服务/健康连接』同步；若部分指标缺失，请在手机『健康连接』App 中为小米运动健康放行对应读写权限，然后点『刷新』。"
                HealthEcosystem.HUAWEI ->
                    "已检测到华为健康数据。华为运动健康需在 App 内开启『Health Kit 健康服务』同步，部分机型/地区不支持。"
                HealthEcosystem.SAMSUNG ->
                    "已检测到三星健康数据。请确认三星健康已开启『健康连接』同步。"
                HealthEcosystem.OPPO ->
                    "已检测到欢太健康数据。请确认已在 App 内开启『健康连接』写入同步。"
                else ->
                    "已检测到健康数据。若有指标缺失，请在手表的健康应用中开启对应数据的『健康连接』同步。"
            }
            else -> when (ecosystem) {
                HealthEcosystem.HUAWEI ->
                    "华为运动健康暂不支持完整写入『健康连接』，部分机型/地区可尝试 App 内开启 Health Kit 同步。"
                else ->
                    "未检测到健康数据源。请在您手表对应的健康应用（如小米运动健康）中绑定手表，并开启『健康连接/Health Connect』数据同步与写入授权，然后点『刷新』。"
            }
        }
    }
}
