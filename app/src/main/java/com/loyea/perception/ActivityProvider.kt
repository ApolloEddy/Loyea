package com.loyea.perception

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

class ActivityProvider(private val context: Context) : SensorEventListener {
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stepDetector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    // 滑动窗口存储最近的合加速度数据（保存最近 3 秒，假设采样率约 20Hz，窗口 60 个点）
    private val accelWindow = ConcurrentLinkedQueue<Float>()
    private val maxWindowSize = 60

    // 记录最近一次步数检测的时间戳
    @Volatile
    private var lastStepTime: Long = 0

    init {
        // 1. 初始化本地传感器监听
        startLocalSensorListening()
        
        // 2. 尝试启动 Google Play Services Activity Recognition
        try {
            if (isGooglePlayServicesAvailable() && hasActivityRecognitionPermission()) {
                requestGoogleActivityUpdates()
            }
        } catch (e: Exception) {
            Log.e("ActivityProvider", "Error initializing Google Activity Recognition", e)
        }
    }

    private fun startLocalSensorListening() {
        try {
            sensorManager?.let { manager ->
                accelerometer?.let {
                    manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                stepDetector?.let {
                    manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        } catch (e: Exception) {
            Log.e("ActivityProvider", "Failed to start local sensors", e)
        }
    }

    fun stopLocalSensorListening() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                accelWindow.add(magnitude)
                while (accelWindow.size > maxWindowSize) {
                    accelWindow.poll()
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                lastStepTime = System.currentTimeMillis()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 判断 Google Play Services 是否可用
     */
    private fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            val result = availability.isGooglePlayServicesAvailable(context)
            result == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否已授予运动状态权限
     */
    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 向 Google Play Services 注册 Activity Updates
     */
    private fun requestGoogleActivityUpdates() {
        try {
            val intent = Intent(context, ActivityReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 2000, intent, flags)
            
            val client = ActivityRecognition.getClient(context)
            client.requestActivityUpdates(30000L, pendingIntent)
                .addOnSuccessListener {
                    Log.d("ActivityProvider", "Successfully registered Google Activity Updates")
                }
                .addOnFailureListener { e ->
                    Log.e("ActivityProvider", "Failed to register Google Activity Updates", e)
                }
        } catch (e: SecurityException) {
            Log.w("ActivityProvider", "SecurityException requesting Google Activity Updates", e)
        } catch (e: Exception) {
            Log.e("ActivityProvider", "Exception requesting Google Activity Updates", e)
        }
    }

    /**
     * 计算合加速度在当前窗口内的标准差
     */
    private fun calculateStdDev(): Float {
        val list = accelWindow.toList()
        if (list.size < 10) return 0f
        val mean = list.average().toFloat()
        val variance = list.map { (it - mean) * (it - mean) }.sum() / list.size
        return sqrt(variance)
    }

    /**
     * 获取当前的运动状态描述
     */
    fun getCurrentActivityState(): String {
        // 1. 优先使用最近（5分钟内）的谷歌融合活动识别结果
        val googleState = prefs.getString("google_activity_state", null)
        val googleTime = prefs.getLong("google_activity_time", 0)
        if (googleState != null && (System.currentTimeMillis() - googleTime) < 5 * 60 * 1000) {
            return googleState
        }

        // 2. 如果谷歌结果不可用，或者已过期，使用本地传感器降级滤波算法
        val stdDev = calculateStdDev()
        val now = System.currentTimeMillis()
        val secondsSinceLastStep = (now - lastStepTime) / 1000f

        return when {
            secondsSinceLastStep <= 5f && stdDev > 2.5f -> "Running (跑步中) [Local]"
            secondsSinceLastStep <= 5f || (stdDev in 0.2f..2.5f) -> "Walking (步行中) [Local]"
            stdDev < 0.08f -> "Still (静止) [Local]"
            else -> "Still / Slight Movement (静止有微动) [Local]"
        }
    }
}
