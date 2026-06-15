package com.loyea.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

object WatchBluetoothClient {
    private const val TAG = "WatchBtClient"
    
    // 必须与手表端相同的 UUID
    private val BT_UUID: UUID = UUID.fromString("e9206d20-b4ba-4c92-ad79-df84074ee288")

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 对外公开的状态数据流
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate

    private val _steps = MutableStateFlow(0)
    val steps: StateFlow<Int> = _steps

    // 新增睡眠数据流
    private val _sleepDuration = MutableStateFlow(0)
    val sleepDuration: StateFlow<Int> = _sleepDuration

    private val _sleepQuality = MutableStateFlow("Unknown")
    val sleepQuality: StateFlow<String> = _sleepQuality

    // 新增运动数据流
    private val _exerciseDuration = MutableStateFlow(0)
    val exerciseDuration: StateFlow<Int> = _exerciseDuration

    private val _exerciseCalories = MutableStateFlow(0)
    val exerciseCalories: StateFlow<Int> = _exerciseCalories

    private val _exerciseType = MutableStateFlow("Resting")
    val exerciseType: StateFlow<String> = _exerciseType

    // 自动重连管理变量
    private var lastConnectedDevice: BluetoothDevice? = null
    private var contextRef: Context? = null
    private var isReconnecting = false
    private var isUserInitiatedDisconnect = false
    private var reconnectJob: kotlinx.coroutines.Job? = null

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    /**
     * 初始化蓝牙适配器
     */
    fun init(context: Context) {
        if (bluetoothAdapter == null) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothAdapter = bluetoothManager.adapter
        }
    }

    /**
     * 获取系统已配对的蓝牙设备列表
     */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(context: Context): List<BluetoothDevice> {
        init(context)
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return emptyList()
        
        return if (hasBtPermission(context)) {
            bluetoothAdapter!!.bondedDevices.toList()
        } else {
            emptyList()
        }
    }

    private fun hasBtPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 连接到指定的手表设备
     */
    @Synchronized
    fun connect(context: Context, device: BluetoothDevice) {
        connectInternal(context, device, isRetry = false)
    }

    @Synchronized
    private fun connectInternal(context: Context, device: BluetoothDevice, isRetry: Boolean) {
        Log.d(TAG, "connectInternal: Initiating connection to ${device.name ?: "Unknown"} (isRetry=$isRetry)")
        if (!hasBtPermission(context)) {
            Log.e(TAG, "connectInternal: Missing BLUETOOTH_CONNECT permission")
            return
        }

        // 如果是全新连接（非重试），重置重连状态与用户标志
        if (!isRetry) {
            isUserInitiatedDisconnect = false
            lastConnectedDevice = device
            contextRef = context.applicationContext
            reconnectJob?.cancel()
            isReconnecting = false
        }

        // 停止之前的连接线程（重连或新连都需要清理旧线程）
        connectThread?.cancel()
        connectThread = null
        
        connectedThread?.cancel()
        connectedThread = null

        _connectionState.value = ConnectionState.CONNECTING
        connectThread = ConnectThread(device, context)
        connectThread?.start()
    }

    /**
     * 断开当前连接
     */
    @Synchronized
    fun disconnect() {
        Log.d(TAG, "disconnect: Stopping all threads and cancelling auto-reconnect")
        isUserInitiatedDisconnect = true
        reconnectJob?.cancel()
        isReconnecting = false
        
        connectThread?.cancel()
        connectThread = null
        
        connectedThread?.cancel()
        connectedThread = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 写入指令给手表
     */
    fun sendCommand(action: String) {
        val payload = JSONObject().apply {
            put("action", action)
        }.toString() + "\n"
        
        connectedThread?.write(payload.toByteArray())
    }

    /**
     * 请求手表开启实时健康数据报告
     */
    fun startRealtimeReporting() {
        Log.d(TAG, "Sending START_REALTIME command to watch")
        sendCommand("START_REALTIME")
    }

    /**
     * 请求手表停止实时健康数据报告
     */
    fun stopRealtimeReporting() {
        Log.d(TAG, "Sending STOP_REALTIME command to watch")
        sendCommand("STOP_REALTIME")
    }

    /**
     * 请求获取手表近期健康数据
     */
    fun getRecentData() {
        Log.d(TAG, "Sending GET_RECENT command to watch")
        sendCommand("GET_RECENT")
    }

    // ==================== 内部线程类 ====================

    @SuppressLint("MissingPermission")
    private class ConnectThread(
        private val device: BluetoothDevice,
        private val context: Context
    ) : Thread() {
        private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                device.createRfcommSocketToServiceRecord(BT_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "Socket creation failed: ${e.message}")
                null
            }
        }

        override fun run() {
            if (socket == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                return
            }

            // 取消发现以释放系统资源，确保连接顺利
            if (hasBtPermission(context)) {
                bluetoothAdapter?.cancelDiscovery()
            }

            try {
                Log.d(TAG, "ConnectThread: Connecting to socket...")
                socket?.connect()
                Log.d(TAG, "ConnectThread: Connected successfully")
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread: Connection failed: ${e.message}")
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "ConnectThread: Could not close socket: ${closeException.message}")
                }
                _connectionState.value = ConnectionState.DISCONNECTED
                triggerAutoReconnect()
                return
            }

            synchronized(WatchBluetoothClient) {
                connectThread = null
                // 启动已连接线程处理IO
                socket?.let {
                    connectedThread = ConnectedThread(it)
                    connectedThread?.start()
                    _connectionState.value = ConnectionState.CONNECTED
                }
            }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "ConnectThread close() failed: ${e.message}")
            }
        }
    }

    private class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream = socket.inputStream
        private val outputStream: OutputStream = socket.outputStream
        private val reader = BufferedReader(InputStreamReader(inputStream))

        override fun run() {
            Log.d(TAG, "ConnectedThread: IO thread started")
            while (true) {
                try {
                    val line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "ConnectedThread: readLine returned null (EOF)")
                        break
                    }
                    Log.d(TAG, "Received payload from watch: $line")
                    parseWatchData(line)
                } catch (e: IOException) {
                    Log.e(TAG, "ConnectedThread: Connection broken: ${e.message}")
                    break
                }
            }
            // 循环退出说明连接断开了
            closeSocket()
            _connectionState.value = ConnectionState.DISCONNECTED
            triggerAutoReconnect()
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
                outputStream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread: write failed: ${e.message}")
            }
        }

        fun cancel() {
            closeSocket()
        }

        private fun closeSocket() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "ConnectedThread close() socket failed: ${e.message}")
            }
        }
    }

    private fun triggerAutoReconnect() {
        val device = lastConnectedDevice ?: return
        val context = contextRef ?: return
        if (isReconnecting || isUserInitiatedDisconnect) return
        
        isReconnecting = true
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            val delays = listOf(5000L, 10000L, 20000L) // 5s, 10s, 20s
            for ((index, delayMs) in delays.withIndex()) {
                if (isUserInitiatedDisconnect || _connectionState.value == ConnectionState.CONNECTED) break
                Log.d(TAG, "AutoReconnect: Attempt ${index + 1} of ${delays.size} in ${delayMs / 1000}s...")
                delay(delayMs)
                if (isUserInitiatedDisconnect || _connectionState.value == ConnectionState.CONNECTED) break
                
                // 发起重连
                connectInternal(context, device, isRetry = true)
                
                // 等待连接结果，最多等待 10 秒
                var checkCount = 0
                while (_connectionState.value == ConnectionState.CONNECTING && checkCount < 20) {
                    delay(500)
                    checkCount++
                }
                
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    Log.i(TAG, "AutoReconnect: Reconnected successfully!")
                    isReconnecting = false
                    return@launch
                }
            }
            Log.w(TAG, "AutoReconnect: All attempts failed.")
            isReconnecting = false
        }
    }

    private fun parseWatchData(payload: String) {
        try {
            val json = JSONObject(payload)
            val type = json.optString("type")
            Log.d(TAG, "parseWatchData: Type: $type")
            when (type) {
                "HEART_RATE" -> {
                    val value = json.optInt("value", 0)
                    if (value > 0) {
                        _heartRate.value = value
                    }
                }
                "STEP_COUNTER" -> {
                    val value = json.optInt("value", 0)
                    if (value >= 0) {
                        _steps.value = value
                    }
                }
                "SLEEP" -> {
                    val duration = json.optInt("duration", 0)
                    val quality = json.optString("quality", "Good")
                    if (duration > 0) {
                        _sleepDuration.value = duration
                        _sleepQuality.value = quality
                    }
                }
                "EXERCISE" -> {
                    val duration = json.optInt("duration", 0)
                    val calories = json.optInt("calories", 0)
                    val exType = json.optString("exerciseType", "Resting")
                    _exerciseDuration.value = duration
                    _exerciseCalories.value = calories
                    _exerciseType.value = exType
                }
                "RECENT_DATA", "MOCK_DATA" -> {
                    val hr = json.optInt("heartRate", 0)
                    val stepsVal = json.optInt("steps", 0)
                    if (hr > 0) _heartRate.value = hr
                    if (stepsVal >= 0) _steps.value = stepsVal
                    
                    val sleepDur = json.optInt("sleepDuration", 0)
                    val sleepQual = json.optString("sleepQuality", "")
                    if (sleepDur > 0) {
                        _sleepDuration.value = sleepDur
                        _sleepQuality.value = sleepQual
                    }
                    
                    val exeDur = json.optInt("exerciseDuration", 0)
                    val exeCal = json.optInt("exerciseCalories", 0)
                    val exeTyp = json.optString("exerciseType", "Resting")
                    if (exeDur > 0 || exeCal > 0) {
                        _exerciseDuration.value = exeDur
                        _exerciseCalories.value = exeCal
                        _exerciseType.value = exeTyp
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse watch JSON: ${e.message}")
        }
    }
}
