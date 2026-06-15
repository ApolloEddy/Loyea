package com.loyea.perception

import android.content.Context
import android.util.Log
import com.loyea.bluetooth.WatchBluetoothClient

class PhysicalContextManager(private val context: Context) {
    val locationProvider = LocationProvider(context)
    val watchProvider: WatchProvider = BluetoothWatchProvider(context)
    private val timeProvider = TimeProvider()
    private val healthProvider = HealthProvider(context)
    
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
        
        // 8. Health Context
        val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
        val isHealthAuthorized = prefs.getBoolean("tool_auth_health", true)

        if (isHealthAuthorized) {
            val isRealWatchConnected = WatchBluetoothClient.connectionState.value == WatchBluetoothClient.ConnectionState.CONNECTED
            val isWatchSyncEnabled = prefs.getBoolean("sim_watch_connected", false)
            
            // --- 8.1 心率与运动状态数据拼接 ---
            if (isRealWatchConnected) {
                val realHR = WatchBluetoothClient.heartRate.value
                val exeType = WatchBluetoothClient.exerciseType.value
                val exeDur = WatchBluetoothClient.exerciseDuration.value
                val exeCal = WatchBluetoothClient.exerciseCalories.value
                
                if (realHR > 0) {
                    sb.append("Heart Rate: $realHR bpm ($exeType) [Smartwatch Bluetooth]\n")
                } else {
                    sb.append("Heart Rate: Waiting for sensor... [Smartwatch Bluetooth]\n")
                }
                
                if (exeDur > 0 || exeCal > 0) {
                    sb.append("Today's Exercise: $exeDur mins, $exeCal kcal ($exeType) [Smartwatch Bluetooth]\n")
                }
            } else if (isWatchSyncEnabled) {
                val hrStatus = healthProvider.getHeartRateStatus()
                // 优雅降级：若 Health Connect 没数据，且开启了模拟同步，则回退到模拟心率
                if (hrStatus == "Permission Denied" || hrStatus == "No Data (Check OHealth Sync)" || hrStatus == "Service Unavailable" || hrStatus == "No Sample Data") {
                    val mockHR = watchProvider.getHeartRateBpm()
                    val state = watchProvider.getMovementState()
                    if (mockHR > 0) {
                        sb.append("Heart Rate: $mockHR bpm ($state) [Simulated]\n")
                    } else {
                        sb.append("Heart Rate: $hrStatus\n")
                    }
                } else {
                    sb.append("Heart Rate: $hrStatus\n")
                }
            } else {
                val hrStatus = healthProvider.getHeartRateStatus()
                sb.append("Heart Rate: $hrStatus\n")
            }
            
            // --- 8.2 步数数据拼接 ---
            if (isRealWatchConnected) {
                val realSteps = WatchBluetoothClient.steps.value
                sb.append("Today's Steps: $realSteps [Smartwatch Bluetooth]\n")
            } else if (isWatchSyncEnabled) {
                val stepsStatus = healthProvider.getStepsStatus()
                if (stepsStatus != "Permission Denied" && stepsStatus != "Service Unavailable") {
                    val mockSteps = WatchBluetoothClient.steps.value
                    if ((stepsStatus == "No Data" || stepsStatus == "0") && mockSteps > 0) {
                        sb.append("Today's Steps: $mockSteps [Simulated]\n")
                    } else {
                        sb.append("Today's Steps: $stepsStatus\n")
                    }
                } else {
                    val mockSteps = WatchBluetoothClient.steps.value
                    sb.append("Today's Steps: $mockSteps [Simulated]\n")
                }
            } else {
                val stepsStatus = healthProvider.getStepsStatus()
                sb.append("Today's Steps: $stepsStatus\n")
            }
            
            val bp = healthProvider.getBloodPressureStatus()
            if (bp != "Permission Denied" && bp != "No Data") {
                sb.append("Blood Pressure: $bp\n")
            }
            
            // --- 8.3 睡眠数据拼接 ---
            if (isRealWatchConnected) {
                val sleepDur = WatchBluetoothClient.sleepDuration.value
                val sleepQual = WatchBluetoothClient.sleepQuality.value
                if (sleepDur > 0) {
                    val h = sleepDur / 60
                    val m = sleepDur % 60
                    sb.append("Last Sleep: ${h}h ${m}m ($sleepQual) [Smartwatch Bluetooth]\n")
                } else {
                    val sleep = healthProvider.getSleepStatus()
                    if (sleep != "Permission Denied" && sleep != "No Data") {
                        sb.append("Last Sleep: $sleep\n")
                    }
                }
            } else {
                val sleep = healthProvider.getSleepStatus()
                if (sleep != "Permission Denied" && sleep != "No Data") {
                    sb.append("Last Sleep: $sleep\n")
                }
            }
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
