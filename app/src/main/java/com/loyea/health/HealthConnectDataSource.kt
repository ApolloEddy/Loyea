package com.loyea.health

import android.content.Context
import android.util.Log
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
 * 基于 Android 健康连接（Health Connect）的通用数据源。
 * 厂商无关：小米运动健康、三星健康、Wear OS 等只要把数据写进健康连接，都从这里读。
 * 原 [com.loyea.perception.HealthProvider] 的类型化重构。
 */
class HealthConnectDataSource(private val context: Context) : HealthDataSource {
    override val sourceLabel: String = "Health Connect"

    private val healthConnectClient by lazy {
        try { HealthConnectClient.getOrCreate(context) } catch (e: Exception) { null }
    }

    override suspend fun readSnapshot(): HealthDataResult<HealthSnapshot> = withContext(Dispatchers.IO) {
        Log.d("HealthProvider", "HealthConnectDataSource reading snapshot...")
        val client = healthConnectClient ?: return@withContext HealthDataResult.Unavailable

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return@withContext HealthDataResult.Error(e.message)
        }

        val recordTypes = listOf(
            HeartRateRecord::class,
            StepsRecord::class,
            SleepSessionRecord::class,
            BloodPressureRecord::class,
            RestingHeartRateRecord::class
        )
        val missing = recordTypes
            .map { HealthPermission.getReadPermission(it) }
            .filter { it !in granted }
        if (missing.isNotEmpty()) return@withContext HealthDataResult.NoPermission(missing.toSet())

        val origins = mutableSetOf<String>()
        val endTime = Instant.now()

        try {
            // 心率：近 3 天最新采样
            val hrRecord = readLatest(client, HeartRateRecord::class, endTime.minus(3, ChronoUnit.DAYS), endTime, origins)
            val heartRate = hrRecord?.samples?.lastOrNull()?.beatsPerMinute?.toInt()

            // 静息心率：近 7 天最新（同一 READ_HEART_RATE 权限）
            val restingRecord = readLatest(client, RestingHeartRateRecord::class, endTime.minus(7, ChronoUnit.DAYS), endTime, origins)
            val restingHr = restingRecord?.beatsPerMinute?.toInt()

            // 步数：今日累计（0 也算有效数据）
            val stepsStart = endTime.truncatedTo(ChronoUnit.DAYS)
            val stepsResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(stepsStart, endTime)
                )
            )
            val steps = stepsResponse.records.sumOf { it.count.toLong() }.toInt()
            stepsResponse.records.forEach { origins += it.metadata.dataOrigin.packageName }

            // 血压：近 7 天最新
            val bpRecord = readLatest(client, BloodPressureRecord::class, endTime.minus(7, ChronoUnit.DAYS), endTime, origins)

            // 睡眠：近 2 天最新
            val sleepRecord = readLatest(client, SleepSessionRecord::class, endTime.minus(2, ChronoUnit.DAYS), endTime, origins)
            val sleepMinutes = sleepRecord?.let { ChronoUnit.MINUTES.between(it.startTime, it.endTime) }?.toInt()

            val snapshot = HealthSnapshot(
                heartRateBpm = heartRate,
                restingHeartRateBpm = restingHr,
                steps = steps,
                bpSystolic = bpRecord?.systolic?.inMillimetersOfMercury?.toInt(),
                bpDiastolic = bpRecord?.diastolic?.inMillimetersOfMercury?.toInt(),
                sleepMinutes = sleepMinutes,
                sourceLabel = sourceLabel,
                dataOrigins = origins
            )
            if (!snapshot.hasData) HealthDataResult.NoData else HealthDataResult.Success(snapshot)
        } catch (e: Exception) {
            Log.e("HealthProvider", "Error reading health snapshot", e)
            HealthDataResult.Error(e.message)
        }
    }

    /** 按时间倒序读该类型最新一条记录，并收集其数据来源包名。 */
    private suspend fun <T : Record> readLatest(
        client: HealthConnectClient,
        recordType: KClass<T>,
        startTime: Instant,
        endTime: Instant,
        origins: MutableSet<String>
    ): T? {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                ascendingOrder = false
            )
        )
        val record = response.records.firstOrNull()
        if (record != null) origins += record.metadata.dataOrigin.packageName
        return record
    }
}
