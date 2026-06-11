package com.loyea.perception

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthProvider(private val context: Context) {
    private val healthConnectClient by lazy {
        try { HealthConnectClient.getOrCreate(context) } catch (e: Exception) { null }
    }

    private suspend fun hasPermission(recordType: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>): Boolean {
        val client = healthConnectClient ?: return false
        val permission = HealthPermission.getReadPermission(recordType)
        return client.permissionController.getGrantedPermissions().contains(permission)
    }

    suspend fun getHeartRateStatus(): String = withContext(Dispatchers.IO) {
        Log.d("HealthProvider", "Fetching heart rate status...")
        val client = healthConnectClient ?: return@withContext "Service Unavailable"
        if (!hasPermission(HeartRateRecord::class)) {
            Log.w("HealthProvider", "Heart rate permission denied")
            return@withContext "Permission Denied"
        }
        
        try {
            val endTime = Instant.now()
            // 扩大搜索范围到 3 天，增加获取到数据的概率
            val startTime = endTime.minus(3, ChronoUnit.DAYS)
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                ascendingOrder = false
            )
            val response = client.readRecords(request)
            Log.d("HealthProvider", "Found ${response.records.size} heart rate records")
            
            // 尝试获取所有贡献数据的 App 名称，帮助诊断
            val contributors = response.records.map { it.metadata.dataOrigin.packageName }.distinct()
            if (contributors.isNotEmpty()) {
                Log.d("HealthProvider", "Data contributors: ${contributors.joinToString(", ")}")
            }

            val latestRecord = response.records.firstOrNull()
            val bpm = latestRecord?.samples?.lastOrNull()?.beatsPerMinute?.toInt()
            
            if (bpm != null) {
                Log.d("HealthProvider", "Latest heart rate: $bpm bpm from ${latestRecord.metadata.dataOrigin.packageName}")
                "$bpm bpm"
            } else if (response.records.isNotEmpty()) {
                // 如果有记录但没采样值，可能数据结构不同，尝试搜索整个记录集
                val anyBpm = response.records.flatMap { it.samples }.lastOrNull()?.beatsPerMinute?.toInt()
                if (anyBpm != null) {
                    Log.d("HealthProvider", "Found heart rate in older record: $anyBpm bpm")
                    "$anyBpm bpm"
                } else {
                    "No Sample Data"
                }
            } else {
                Log.i("HealthProvider", "No heart rate records found in last 3 days")
                "No Data (Check OHealth Sync)"
            }
        } catch (e: Exception) {
            Log.e("HealthProvider", "Error reading heart rate", e)
            "Error: ${e.message}"
        }
    }

    suspend fun getStepsStatus(): String = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext "Service Unavailable"
        if (!hasPermission(StepsRecord::class)) return@withContext "Permission Denied"
        try {
            val endTime = Instant.now()
            val startTime = endTime.truncatedTo(ChronoUnit.DAYS)
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = client.readRecords(request)
            val total = response.records.sumOf { it.count }
            "$total steps"
        } catch (e: Exception) {
            "Error"
        }
    }

    suspend fun getBloodPressureStatus(): String = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext "Service Unavailable"
        if (!hasPermission(BloodPressureRecord::class)) return@withContext "Permission Denied"
        try {
            val endTime = Instant.now()
            val startTime = endTime.minus(7, ChronoUnit.DAYS)
            val request = ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                ascendingOrder = false
            )
            val response = client.readRecords(request)
            val latestRecord = response.records.firstOrNull()
            if (latestRecord != null) {
                "${latestRecord.systolic.inMillimetersOfMercury.toInt()}/${latestRecord.diastolic.inMillimetersOfMercury.toInt()} mmHg"
            } else "No Data"
        } catch (e: Exception) {
            "Error"
        }
    }

    suspend fun getSleepStatus(): String = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext "Service Unavailable"
        if (!hasPermission(SleepSessionRecord::class)) return@withContext "Permission Denied"
        try {
            val endTime = Instant.now()
            val startTime = endTime.minus(2, ChronoUnit.DAYS)
            val request = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                ascendingOrder = false
            )
            val response = client.readRecords(request)
            val latestRecord = response.records.firstOrNull()
            if (latestRecord != null) {
                val duration = ChronoUnit.MINUTES.between(latestRecord.startTime, latestRecord.endTime)
                "${duration / 60}h ${duration % 60}m"
            } else "No Data"
        } catch (e: Exception) {
            "Error"
        }
    }
}
