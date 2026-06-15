package com.loyea.perception

import android.content.Context
import com.loyea.mcp.McpTool
import com.loyea.mcp.JsonRpcResponse
import com.loyea.mcp.JsonRpcError
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地物理感知 MCP 服务器，允许 AI 通过工具调用获取细粒度的传感器数据
 */
class PerceptionMcpServer(private val context: Context) {
    private val perceptionManager = PhysicalContextManager(context)
    private val healthProvider = HealthProvider(context)
    private val environmentProvider = EnvironmentProvider(context)
    private val bluetoothProvider = BluetoothProvider(context)
    private val activityProvider = ActivityProvider(context)
    private val weatherProvider = WeatherProvider(context)
    private val gson = Gson()

    var webSearchProvider: (suspend (String) -> String)? = null

    fun getTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "get_location",
                description = "获取用户当前的 GPS 经纬度位置信息。若定位感知开关被关或权限不足，会返回相应状态供模型引导用户开启。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_live_weather",
                description = "获取指定地区或当前所在地区的实时天气状况（温度、湿度、天气状况）。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "location" to mapOf(
                            "type" to "string",
                            "description" to "可选。要查询的城市或地区名称（如“北京”、“上海”或“Tokyo”）。如果不传，则默认查询用户当前定位所在的位置。"
                        )
                    )
                )
            ),
            McpTool(
                name = "get_weather_forecast",
                description = "获取指定地区或当前所在地区的未来 3 天天气预报（包括日期、天气状况描述以及最低与最高温摄氏度范围）。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "location" to mapOf(
                            "type" to "string",
                            "description" to "可选。要查询预报的城市或地区名称（如“北京”、“上海”或“Tokyo”）。如果不传，则默认查询用户当前定位所在的位置。"
                        )
                    )
                )
            ),
            McpTool(
                name = "get_environment_light",
                description = "获取用户当前所在环境的光照强度 Lux 值与环境亮度描述（如室内正常光、昏暗等）。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_battery_status",
                description = "获取手机的剩余电量百分比与充电状态。可据此感知用户设备的电源状况。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_bluetooth_status",
                description = "获取当前手机连接的蓝牙外设（如蓝牙耳机、智能手环等）的状态与连接的设备名称。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_activity_state",
                description = "获取用户当前的系统级实时运动状态（静止、步行、跑步、乘车等）。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_health_data",
                description = "获取用户当天的身体健康指标数据（包括今日步数、心率 BPM、血压状况、上次睡眠监测概览等）。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_wifi_status",
                description = "获取当前手机连接的网络类型与 Wi-Fi SSID 名称（网络名称）。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_noise_level",
                description = "获取瞬时环境的噪音分贝等级值 (dB)。",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "send_voice_reply",
                description = "向用户（主人）发送一条语音消息。当你想要用语音对主人表达关心、娇嗔、或者说悄悄话、或者用户明确要求你“发语音”、“发段语音”、“语音聊聊”时必须调用。这会直接在主人的屏幕上呈现出一条你的专属语音回复条并自动开始播放。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "text" to mapOf(
                            "type" to "string",
                            "description" to "要转换为语音播放的纯口头话语。严禁带有动作描述（如括号等旁白描述），只填你想直接说给主人听的话。"
                        )
                    ),
                    "required" to listOf("text")
                )
            ),
            McpTool(
                name = "web_search",
                description = "在互联网上检索最新的实时信息，当需要确认客观事实、新闻、实时资讯或解答没有把握的位置性问题时使用。",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "检索的问题或关键词"
                        )
                    ),
                    "required" to listOf("query")
                )
            )
        )
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>?): JsonRpcResponse = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
        try {
            val cleanName = name.substringAfterLast("__").substringAfterLast(".")
            
            // 进行细粒度权限校验
            val authErrorText = when (cleanName) {
                "get_location" -> if (!prefs.getBoolean("tool_auth_location", true)) "Permission Denied: Location access is unauthorized by the user." else null
                "get_live_weather", "get_weather_forecast" -> if (!prefs.getBoolean("tool_auth_weather", true)) "Permission Denied: Weather access is unauthorized by the user." else null
                "get_environment_light" -> if (!prefs.getBoolean("tool_auth_environment", true)) "Permission Denied: Environment light access is unauthorized by the user." else null
                "get_battery_status" -> if (!prefs.getBoolean("tool_auth_device", true)) "Permission Denied: Device battery/power access is unauthorized by the user." else null
                "get_bluetooth_status" -> if (!prefs.getBoolean("tool_auth_bluetooth_activity", true)) "Permission Denied: Bluetooth status access is unauthorized by the user." else null
                "get_activity_state" -> if (!prefs.getBoolean("tool_auth_bluetooth_activity", true)) "Permission Denied: Activity state access is unauthorized by the user." else null
                "get_health_data" -> if (!prefs.getBoolean("tool_auth_health", true)) "Permission Denied: Health data access is unauthorized by the user." else null
                else -> null
            }

            if (authErrorText != null) {
                return@withContext JsonRpcResponse(
                    jsonrpc = "2.0",
                    idStr = null,
                    error = JsonRpcError(code = -32602, message = authErrorText)
                )
            }

            val resultText = when (cleanName) {
                "get_location" -> {
                    "Location: ${perceptionManager.locationProvider.getCurrentLocation()}"
                }
                "get_live_weather" -> {
                    val argLoc = arguments?.get("location")?.toString()
                    val queryLoc = if (!argLoc.isNullOrBlank()) argLoc else perceptionManager.locationProvider.getCurrentLocation()
                    "Weather: ${weatherProvider.getLiveWeather(queryLoc)}"
                }
                "get_weather_forecast" -> {
                    val argLoc = arguments?.get("location")?.toString()
                    val queryLoc = if (!argLoc.isNullOrBlank()) argLoc else perceptionManager.locationProvider.getCurrentLocation()
                    "Weather Forecast: ${weatherProvider.getWeatherForecast(queryLoc)}"
                }
                "get_environment_light" -> {
                    "Environment Light: ${environmentProvider.getLightIntensity()}"
                }
                "get_battery_status" -> {
                    "Battery Status: ${environmentProvider.getBatteryStatus()}"
                }
                "get_bluetooth_status" -> {
                    "Bluetooth Status: ${bluetoothProvider.getBluetoothStatus()}"
                }
                "get_activity_state" -> {
                    "Activity State: ${activityProvider.getCurrentActivityState()}"
                }
                "get_health_data" -> {
                    val sb = StringBuilder()
                    val isRealWatchConnected = com.loyea.bluetooth.WatchBluetoothClient.connectionState.value == com.loyea.bluetooth.WatchBluetoothClient.ConnectionState.CONNECTED
                    val isWatchSyncEnabled = prefs.getBoolean("sim_watch_connected", false)
                    
                    // --- 心率获取 ---
                    if (isRealWatchConnected) {
                        val realHR = com.loyea.bluetooth.WatchBluetoothClient.heartRate.value
                        val state = perceptionManager.watchProvider.getMovementState()
                        if (realHR > 0) {
                            sb.append("Heart Rate: $realHR bpm ($state) [Smartwatch Bluetooth]\n")
                        } else {
                            sb.append("Heart Rate: Waiting for sensor... [Smartwatch Bluetooth]\n")
                        }
                    } else if (isWatchSyncEnabled) {
                        val hrStatus = healthProvider.getHeartRateStatus()
                        if (hrStatus == "Permission Denied" || hrStatus == "No Data (Check OHealth Sync)" || hrStatus == "Service Unavailable" || hrStatus == "No Sample Data") {
                            val mockHR = perceptionManager.watchProvider.getHeartRateBpm()
                            val state = perceptionManager.watchProvider.getMovementState()
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
                    
                    // --- 步数获取 ---
                    if (isRealWatchConnected) {
                        val realSteps = com.loyea.bluetooth.WatchBluetoothClient.steps.value
                        sb.append("Today's Steps: $realSteps [Smartwatch Bluetooth]\n")
                    } else if (isWatchSyncEnabled) {
                        val stepsStatus = healthProvider.getStepsStatus()
                        if (stepsStatus != "Permission Denied" && stepsStatus != "Service Unavailable") {
                            val mockSteps = com.loyea.bluetooth.WatchBluetoothClient.steps.value
                            if ((stepsStatus == "No Data" || stepsStatus == "0") && mockSteps > 0) {
                                sb.append("Today's Steps: $mockSteps [Simulated]\n")
                            } else {
                                sb.append("Today's Steps: $stepsStatus\n")
                            }
                        } else {
                            val mockSteps = com.loyea.bluetooth.WatchBluetoothClient.steps.value
                            sb.append("Today's Steps: $mockSteps [Simulated]\n")
                        }
                    } else {
                        val stepsStatus = healthProvider.getStepsStatus()
                        sb.append("Today's Steps: $stepsStatus\n")
                    }
                    
                    val bp = healthProvider.getBloodPressureStatus()
                    sb.append("Blood Pressure: $bp\n")
                    
                    val sleep = healthProvider.getSleepStatus()
                    sb.append("Last Sleep: $sleep\n")
                    
                    sb.toString().trim()
                }
                "get_wifi_status" -> {
                    "Network: ${perceptionManager.wifiProvider.getNetworkSsid()}"
                }
                "get_noise_level" -> {
                    val db = perceptionManager.noiseProvider.getAmbientNoiseDb()
                    val dbText = if (db >= 0) "$db dB" else "Permission Denied"
                    "Ambient Noise: $dbText"
                }
                "send_voice_reply" -> {
                    val text = arguments?.get("text")?.toString() ?: ""
                    if (text.isBlank()) {
                        "Error: Voice reply text cannot be empty."
                    } else {
                        "Voice reply sent successfully: \"$text\""
                    }
                }
                "web_search" -> {
                    val query = arguments?.get("query")?.toString() ?: ""
                    if (query.isBlank()) {
                        "Error: Search query cannot be empty."
                    } else {
                        webSearchProvider?.invoke(query) ?: "Error: Web search engine is not initialized."
                    }
                }
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }
            
            val resultJson = mapOf("content" to listOf(mapOf("type" to "text", "text" to resultText)))
            JsonRpcResponse(
                jsonrpc = "2.0",
                idStr = null,
                result = gson.toJsonTree(resultJson)
            )
        } catch (e: Exception) {
            JsonRpcResponse(
                jsonrpc = "2.0",
                idStr = null,
                error = JsonRpcError(code = -32603, message = e.message ?: "Internal error")
            )
        }
    }
}
