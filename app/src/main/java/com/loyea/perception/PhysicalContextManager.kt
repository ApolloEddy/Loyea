package com.loyea.perception

import android.content.Context
import android.util.Log

class PhysicalContextManager(context: Context) {
    val locationProvider = LocationProvider(context)
    val watchProvider: WatchProvider = MockWatchProvider(context)
    private val timeProvider = TimeProvider()
    private val healthProvider = HealthProvider(context)
    
    // 新增多传感器和天气组件
    private val environmentProvider = EnvironmentProvider(context)
    private val bluetoothProvider = BluetoothProvider(context)
    private val activityProvider = ActivityProvider(context)
    private val weatherProvider = WeatherProvider(context)

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
        val hrStatus = healthProvider.getHeartRateStatus()
        
        // Logical Fallback: If real data is unavailable (Denied or No Data), check simulation
        if ((hrStatus == "Permission Denied" || hrStatus == "No Data (Check OHealth Sync)" || hrStatus == "Service Unavailable" || hrStatus == "No Sample Data") 
            && watchProvider.isWatchConnected()) {
            
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
        
        val steps = healthProvider.getStepsStatus()
        if (steps != "Permission Denied" && steps != "Service Unavailable") {
            sb.append("Today's Steps: $steps\n")
        }
        
        val bp = healthProvider.getBloodPressureStatus()
        if (bp != "Permission Denied" && bp != "No Data") {
            sb.append("Blood Pressure: $bp\n")
        }
        
        val sleep = healthProvider.getSleepStatus()
        if (sleep != "Permission Denied" && sleep != "No Data") {
            sb.append("Last Sleep: $sleep\n")
        }
        
        return sb.toString()
    }
}
