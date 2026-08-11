package com.loyea.perception

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.loyea.bluetooth.WatchBluetoothClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BluetoothWatchProvider(private val context: Context) : WatchProvider {
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "BtWatchProvider"
    }

    init {
        // 初始化蓝牙客户端
        WatchBluetoothClient.init(context)
        
        // 自动连接逻辑：如果上次记录中已经开启了智能手表同步 (sim_watch_connected == true)，
        // 则在 APP 启动时，自动尝试在后台唤醒与手表的经典蓝牙连接
        val isMockConnected = prefs.getBoolean("sim_watch_connected", false)
        if (isMockConnected) {
            Log.d(TAG, "init: Last watch sync was enabled. Automatically connecting to watch in background...")
            CoroutineScope(Dispatchers.IO).launch {
                delay(1500) // 稍作延迟以待蓝牙适配器和应用就绪
                setWatchConnected(true)
            }
        }
    }

    override fun getHeartRateBpm(): Int {
        // 1. 优先获取真实蓝牙手表的心率
        if (WatchBluetoothClient.connectionState.value == WatchBluetoothClient.ConnectionState.CONNECTED) {
            val realHr = WatchBluetoothClient.heartRate.value
            Log.d(TAG, "getHeartRateBpm: Reading real heart rate: $realHr")
            if (realHr > 0) return realHr
        }
        
        // 2. 如果真实蓝牙未连接，则读取模拟数据（仅在模拟连接开关开启时）
        val isMockConnected = prefs.getBoolean("sim_watch_connected", false)
        if (isMockConnected) {
            val isMoving = prefs.getBoolean("sim_watch_moving", false)
            val mockHr = if (isMoving) {
                (100..140).random()
            } else {
                (60..90).random()
            }
            Log.d(TAG, "getHeartRateBpm: Reading mock heart rate: $mockHr")
            return mockHr
        }
        
        return 0
    }

    override fun getMovementState(): String {
        if (WatchBluetoothClient.connectionState.value == WatchBluetoothClient.ConnectionState.CONNECTED) {
            // 真实连接下根据心率粗略估算状态
            return if (WatchBluetoothClient.heartRate.value > 95) "Moving" else "Resting"
        }
        
        return if (prefs.getBoolean("sim_watch_moving", false)) "Moving" else "Resting"
    }

    override fun isWatchConnected(): Boolean {
        // 真实已连接或模拟已连接均返回 true
        val isRealConnected = WatchBluetoothClient.connectionState.value == WatchBluetoothClient.ConnectionState.CONNECTED
        val isMockConnected = prefs.getBoolean("sim_watch_connected", false)
        return isRealConnected || isMockConnected
    }

    override fun setSimulationState(isMoving: Boolean) {
        prefs.edit().putBoolean("sim_watch_moving", isMoving).apply()
    }

    @SuppressLint("MissingPermission")
    override fun setWatchConnected(connected: Boolean) {
        prefs.edit().putBoolean("sim_watch_connected", connected).apply()
        
        if (connected) {
            Log.d(TAG, "setWatchConnected: User requested connection. Scanning paired devices...")
            val pairedDevices = WatchBluetoothClient.getPairedDevices(context)
            if (pairedDevices.isEmpty()) {
                Log.w(TAG, "No paired devices found")
                return
            }
            
            // 过滤并寻找正确的手表设备，避免将 OPPO 蓝牙耳机（如 Enco、Buds）错当成手表连接
            val targetDevice = pairedDevices.firstOrNull { device ->
                val name = device.name ?: ""
                val isWatch = name.contains("Watch", ignoreCase = true)
                val isAudio = name.contains("Enco", ignoreCase = true) || 
                              name.contains("Buds", ignoreCase = true) || 
                              name.contains("Earphone", ignoreCase = true) ||
                              name.contains("Headset", ignoreCase = true) ||
                              name.contains("W51", ignoreCase = true) ||
                              name.contains("W31", ignoreCase = true)
                isWatch && !isAudio
            } ?: pairedDevices.firstOrNull { device ->
                // 次优先选择包含 "OPPO" 且不包含耳机关键字的设备
                val name = device.name ?: ""
                val isOppo = name.contains("OPPO", ignoreCase = true)
                val isAudio = name.contains("Enco", ignoreCase = true) || 
                              name.contains("Buds", ignoreCase = true) || 
                              name.contains("Earphone", ignoreCase = true) ||
                              name.contains("Headset", ignoreCase = true) ||
                              name.contains("W51", ignoreCase = true) ||
                              name.contains("W31", ignoreCase = true)
                isOppo && !isAudio
            } ?: pairedDevices.firstOrNull { device ->
                // 再次匹配包含 "Watch" 或 "OPPO" 的任何设备
                val name = device.name ?: ""
                name.contains("Watch", ignoreCase = true) || name.contains("OPPO", ignoreCase = true)
            } ?: pairedDevices.firstOrNull()
 
            targetDevice?.let {
                Log.d(TAG, "Connecting to paired device: ${it.name} (${it.address})")
                WatchBluetoothClient.connect(context, it)
                
                // 启动协程延迟发送“开启数据收集”命令，确保连接建立完毕
                CoroutineScope(Dispatchers.IO).launch {
                    // 等待直到状态变成 CONNECTED，最多等待5秒
                    var retryCount = 0
                    while (WatchBluetoothClient.connectionState.value != WatchBluetoothClient.ConnectionState.CONNECTED && retryCount < 10) {
                        delay(500)
                        retryCount++
                    }
                    if (WatchBluetoothClient.connectionState.value == WatchBluetoothClient.ConnectionState.CONNECTED) {
                        Log.d(TAG, "Watch connected, requesting realtime reporting")
                        WatchBluetoothClient.startRealtimeReporting()
                    }
                }
            }
        } else {
            Log.d(TAG, "setWatchConnected: User requested disconnect")
            WatchBluetoothClient.stopRealtimeReporting()
            WatchBluetoothClient.disconnect()
        }
    }
}
