package com.loyea.perception

import android.content.Context
import android.util.Log
import com.loyea.health.HealthContextBuilder

class PhysicalContextManager(private val context: Context) {
    val locationProvider = LocationProvider(context)
    val watchProvider: WatchProvider = BluetoothWatchProvider(context)
    private val timeProvider = TimeProvider()
    private val healthContextBuilder by lazy { HealthContextBuilder(context) }

    // 新增多传感器和天气组件
    private val environmentProvider = EnvironmentProvider(context)
    private val bluetoothProvider = BluetoothProvider(context)
    val activityProvider = ActivityProvider(context)
    private val weatherProvider = WeatherProvider(context)
    val wifiProvider = WifiProvider(context)
    val noiseProvider = NoiseProvider(context)

    suspend fun buildPhysicalContextString(): String {
        Log.d("Perception", "Building physical context...")
        val sb = StringBuilder()

        // 1. Time Context
        sb.append("Current Time: ${timeProvider.getCurrentTimeFormatted()} (${timeProvider.getDayOfWeek()})\n")

        // 2. Location Context
        val loc = locationProvider.getCurrentLocation()
        sb.append("Location: $loc\n")

        // 3. Weather Context
        val weather = weatherProvider.getLiveWeather(loc)
        sb.append("Weather: $weather\n")

        // 4. Activity State Context
        val activity = activityProvider.getCurrentActivityState()
        sb.append("Activity State: $activity\n")

        // 5. Environment Light Context
        val light = environmentProvider.getLightIntensity()
        sb.append("Environment Light: $light\n")

        // 6. Battery Status Context
        val battery = environmentProvider.getBatteryStatus()
        sb.append("Battery Status: $battery\n")

        // 7. Bluetooth Devices Context
        val bluetooth = bluetoothProvider.getBluetoothStatus()
        sb.append("Bluetooth: $bluetooth\n")

        // 8. Health Context（统一走 com.loyea.health 类型化管线：蓝牙 > 健康连接 > 模拟）
        val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("tool_auth_health", true)) {
            val healthText = healthContextBuilder.buildHealthContextString()
            if (healthText.isNotBlank()) sb.append(healthText)
        }

        // 9. Network SSID Context
        val net = wifiProvider.getNetworkSsid()
        sb.append("Network: $net\n")

        // 10. Ambient Noise Context
        val noise = noiseProvider.getAmbientNoiseDb()
        val noiseText = if (noise >= 0) "$noise dB" else "Permission Denied"
        sb.append("Ambient Noise: $noiseText\n")

        return sb.toString()
    }
}
