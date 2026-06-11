package com.loyea.perception

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class EnvironmentProvider(private val context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    
    // 缓存最新光照强度值，以防临时注册时无法立刻获取
    private var lastKnownLux: Float = -1f

    init {
        // 尝试启动一个持久的低频监听以保持 lastKnownLux 的更新
        lightSensor?.let { sensor ->
            try {
                sensorManager?.registerListener(object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            if (it.sensor.type == Sensor.TYPE_LIGHT) {
                                lastKnownLux = it.values[0]
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (e: Exception) {
                Log.e("EnvironmentProvider", "Failed to register light sensor listener", e)
            }
        }
    }

    /**
     * 获取当前环境光强描述
     */
    suspend fun getLightIntensity(): String {
        if (lightSensor == null) return "Not Supported (No light sensor)"
        
        // 尝试等待最新值（最多等待 150 毫秒）
        val freshLux = withTimeoutOrNull(150) {
            suspendCancellableCoroutine<Float> { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            if (it.sensor.type == Sensor.TYPE_LIGHT) {
                                try {
                                    sensorManager?.unregisterListener(this)
                                } catch (e: Exception) {
                                    // ignore
                                }
                                continuation.resume(it.values[0])
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                try {
                    sensorManager?.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_FASTEST)
                } catch (e: Exception) {
                    continuation.resume(-1f)
                }
                continuation.invokeOnCancellation {
                    try {
                        sensorManager?.unregisterListener(listener)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }

        val lux = freshLux ?: lastKnownLux
        if (lux < 0) return "Unknown"
        
        // 将勒克斯 (Lux) 翻译为人类易读的环境描述
        val description = when {
            lux <= 5f -> "Pitch Black (极暗)"
            lux <= 20f -> "Dim Light (昏暗)"
            lux <= 100f -> "Indoor / Weak Light (室内弱光)"
            lux <= 500f -> "Normal Indoor Light (室内正常光照)"
            lux <= 2000f -> "Bright Light (明亮)"
            else -> "Extremely Bright / Outdoor (户外强光)"
        }
        return "$description ($lux lux)"
    }

    /**
     * 获取手机电量及充电状态
     */
    fun getBatteryStatus(): String {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter) ?: return "Unknown"

            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargingType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC (插座)"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB (电脑接口)"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless (无线)"
                else -> "Unknown"
            }

            val sb = StringBuilder()
            if (batteryPct >= 0) {
                sb.append("$batteryPct%")
            } else {
                sb.append("Unknown level")
            }

            if (isCharging) {
                sb.append(" [Charging via $chargingType]")
            } else {
                sb.append(" [Discharging]")
            }
            sb.toString()
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
