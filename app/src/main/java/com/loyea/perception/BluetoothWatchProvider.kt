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
            
            // 优先选择名字中带有 Watch 或 OPPO 的配对设备，没有就用第一个配对的
            val targetDevice = pairedDevices.firstOrNull { 
                it.name?.contains("Watch", ignoreCase = true) == true || 
                it.name?.contains("OPPO", ignoreCase = true) == true 
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
