# Loyea MCP 客户端协议与多服务器管理设计分析

本报告针对 Loyea 赛博伴侣项目的 **Milestone 1: MCPClient** 进行详细的技术分析与架构设计。本设计旨在 Android/Kotlin 环境下实现标准的 Model Context Protocol (MCP) 客户端，支持多服务器的并发连接与生命周期管理，并通过 SharedPreferences 实现配置持久化。同时，本设计在 `SettingsScreen.kt` 中引入符合“Claude美学”的高颜值 UI 面板。

---

## 一、 标准 JSON-RPC over HTTP/SSE 协议客户端设计

在 MCP 协议规范中，客户端与服务器的通信可以通过**标准输入输出 (Stdio)** 或 **服务器发送事件 (SSE, Server-Sent Events)** 进行。在 Android 应用中，由于 MCP 服务端通常运行在本地其他进程（如 Termux、Node.js 运行环境）或远程主机上，**SSE 传输协议**是最佳选择。

### 1.1 SSE 传输协议交互流程

标准的 MCP over SSE 协议分为两个主要通道：
1. **下行通道 (SSE Stream)**：客户端向服务端发起 GET 请求建立 SSE 长连接，服务端通过该连接将事件和响应单向推送到客户端。
2. **上行通道 (HTTP POST)**：客户端通过普通的 HTTP POST 请求将 JSON-RPC 请求发送到服务端。

具体通信时序如下：
1. **建立 SSE 隧道**：
   - 客户端发起 `GET <sse-url>` 请求，Header 中携带 `Accept: text/event-stream`。
   - 服务端保持连接，并返回 `HTTP 200` 或 `HTTP 202`。
2. **接收 Endpoint 公告**：
   - 连接建立后，服务端会立即发送一个事件类型为 `endpoint` 的消息，其 data 字段包含一个用于接收上行 POST 请求的 URL（可能是相对路径或绝对路径，例如 `http://10.0.2.2:3000/message?session_id=xxx`）。
   - 客户端解析该 URL，并保存为后续上行通信的 `postEndpointUrl`。
3. **初始化握手 (Initialize)**：
   - 客户端向 `postEndpointUrl` 发送 POST 请求，Payload 为 JSON-RPC 格式的 `initialize` 请求。
   - 服务端接收到初始化请求后，不通过 POST 响应直接返回，而是通过 SSE 通道（事件类型为 `message`）将 `initialize` 响应推送给客户端。
   - 客户端接收并解析该响应，确认协议版本与能力（Capabilities）。
   - 客户端向 `postEndpointUrl` 发送 POST 请求，Payload 为 JSON-RPC 格式的 `notifications/initialized` 通知（无响应）。
4. **常规调用 (tools/list 与 tools/call)**：
   - 客户端发送 POST 请求。
   - 服务端通过 SSE 推送 `message` 事件作为响应。

### 1.2 客户端核心 Kotlin 代码结构设计

由于 POST 请求与 SSE 异步推送响应是分离的，我们需要在客户端内部使用 `CompletableDeferred` 实现**异步转挂起 (Async to Suspend)** 的机制，将 JSON-RPC 的请求-响应模式在 Kotlin 协程中封装为直观的挂起函数。

#### 1.2.1 协议数据模型

```kotlin
// JSON-RPC 基础结构
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: Map<String, Any>? = null
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String?,
    val result: Map<String, Any>? = null,
    val error: JsonRpcError? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)
```

#### 1.2.2 McpClient 实现方案

```kotlin
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class McpClient(
    val serverId: String,
    val serverName: String,
    val sseUrl: String,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 需要无限超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()
    
    // 连接状态流
    private val _status = MutableStateFlow(McpStatus.DISCONNECTED)
    val status: StateFlow<McpStatus> = _status

    // 缓存可用工具列表
    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val tools: StateFlow<List<McpTool>> = _tools

    private var eventSource: EventSource? = null
    private var postEndpointUrl: String? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 挂起中的请求映射表：RequestID -> Deferred Response
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()

    fun connect() {
        if (_status.value != McpStatus.DISCONNECTED) return
        _status.value = McpStatus.CONNECTING
        
        val request = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .build()

        val factory = EventSources.createFactory(okHttpClient)
        eventSource = factory.newEventSource(request, sseListener)
    }

    fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        postEndpointUrl = null
        pendingRequests.forEach { (_, deferred) ->
            deferred.cancel(CancellationException("Client disconnected"))
        }
        pendingRequests.clear()
        _tools.value = emptyList()
        _status.value = McpStatus.DISCONNECTED
    }

    private val sseListener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            Log.d("McpClient", "[$serverName] SSE Stream Opened")
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            Log.d("McpClient", "[$serverName] SSE Event: type=$type, data=$data")
            when (type) {
                "endpoint" -> {
                    // 解析 POST 通信 Endpoint 并触发握手
                    postEndpointUrl = resolveEndpoint(data)
                    coroutineScope.launch {
                        performHandshake()
                    }
                }
                "message" -> {
                    // 接收 JSON-RPC 消息响应
                    handleIncomingMessage(data)
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            Log.d("McpClient", "[$serverName] SSE Stream Closed")
            disconnect()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            Log.e("McpClient", "[$serverName] SSE Stream Failure: ${t?.message}")
            _status.value = McpStatus.DISCONNECTED
            // 触发自动重试逻辑（交由 McpManager 处理）
        }
    }

    private fun resolveEndpoint(data: String): String {
        return if (data.startsWith("http://") || data.startsWith("https://")) {
            data
        } else {
            // 如果是相对路径，进行基准 URL 拼接
            val baseUri = java.net.URI(sseUrl)
            baseUri.resolve(data).toString()
        }
    }

    private fun handleIncomingMessage(json: String) {
        try {
            val response = gson.fromJson(json, JsonRpcResponse::class.java)
            val reqId = response.id
            if (reqId != null) {
                val deferred = pendingRequests.remove(reqId)
                deferred?.complete(response)
            }
        } catch (e: Exception) {
            Log.e("McpClient", "Failed to parse message: $json", e)
        }
    }

    // 发送 JSON-RPC 请求的核心挂起函数
    private suspend fun sendRequest(method: String, params: Map<String, Any>? = null): JsonRpcResponse {
        val endpoint = postEndpointUrl ?: throw IOException("POST endpoint not resolved yet")
        val requestId = UUID.randomUUID().toString()
        val requestPayload = JsonRpcRequest(id = requestId, method = method, params = params)
        val requestBody = gson.toJson(requestPayload).toRequestBody(mediaTypeJson)

        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[requestId] = deferred

        val postRequest = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        // 异步发送 POST 请求
        okHttpClient.newCall(postRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                pendingRequests.remove(requestId)
                deferred.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    pendingRequests.remove(requestId)
                    deferred.completeExceptionally(IOException("HTTP error code: ${response.code}"))
                }
                // 注意：由于是异步 SSE，这里的 response.body 往往是空的，只代表服务端接收到了 POST
                response.close()
            }
        })

        // 在协程中挂起，等待对应的 SSE message 事件回来，并设置 30s 超时
        return withTimeout(30000) {
            deferred.await()
        }
    }

    // 握手逻辑
    private suspend fun performHandshake() {
        try {
            val initParams = mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf<String, Any>(),
                "clientInfo" to mapOf("name" to "Loyea-Android-Client", "version" to "1.0.0")
            )
            val response = sendRequest("initialize", initParams)
            if (response.error != null) {
                throw IOException("Handshake failed: ${response.error.message}")
            }
            
            // 发送 initialized 通知
            sendNotification("notifications/initialized")
            
            _status.value = McpStatus.CONNECTED
            // 握手成功后立即拉取工具列表
            fetchTools()
        } catch (e: Exception) {
            Log.e("McpClient", "Handshake failed", e)
            disconnect()
        }
    }

    private fun sendNotification(method: String, params: Map<String, Any>? = null) {
        val endpoint = postEndpointUrl ?: return
        val payload = mapOf(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params
        )
        val body = gson.toJson(payload).toRequestBody(mediaTypeJson)
        val postRequest = Request.Builder().url(endpoint).post(body).build()
        okHttpClient.newCall(postRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    suspend fun fetchTools() {
        if (_status.value != McpStatus.CONNECTED) return
        try {
            val response = sendRequest("tools/list")
            if (response.error != null) {
                throw IOException(response.error.message)
            }
            val toolsResult = response.result?.get("tools") as? List<Map<String, Any>>
            val parsedTools = toolsResult?.map {
                McpTool(
                    name = it["name"] as? String ?: "",
                    description = it["description"] as? String ?: "",
                    inputSchema = it["inputSchema"] as? Map<String, Any> ?: emptyMap()
                )
            } ?: emptyList()
            _tools.value = parsedTools
        } catch (e: Exception) {
            Log.e("McpClient", "Failed to fetch tools", e)
        }
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>): JsonRpcResponse {
        if (_status.value != McpStatus.CONNECTED) {
            throw IOException("Server not connected")
        }
        val params = mapOf(
            "name" to name,
            "arguments" to arguments
        )
        return sendRequest("tools/call", params)
    }
}

enum class McpStatus {
    DISCONNECTED, CONNECTING, CONNECTED
}

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any>
)
```

---

## 二、 SharedPreferences 持久化 MCP 配置设计

我们需要提供一个 `McpConfigStorage` 来保存多个 MCP 服务器的连接配置，包括其唯一 ID、别名、SSE URL、启用状态以及断线自动重试选项。

### 2.1 配置实体数据结构

```kotlin
data class McpServerConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val sseUrl: String,
    val isEnabled: Boolean = true,
    val autoRetry: Boolean = true
)
```

### 2.2 JSON 序列化读写类 `McpConfigStorage`

为了与 `ChatViewModel` 中其他配置的读取模式保持一致，我们采用 `SharedPreferences` 配合 `Gson` 进行持久化存储。

```kotlin
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class McpConfigStorage(context: Context) {
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mcpKey = "mcp_servers_config"

    // 读取所有服务器配置
    fun loadServers(): List<McpServerConfig> {
        val json = prefs.getString(mcpKey, "") ?: ""
        if (json.isBlank()) {
            return getBuiltInServers() // 无配置时返回默认内置示例
        }
        return try {
            val type = object : TypeToken<List<McpServerConfig>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 保存服务器配置列表
    fun saveServers(servers: List<McpServerConfig>) {
        val json = gson.toJson(servers)
        prefs.edit().putString(mcpKey, json).apply()
    }

    // 默认内置的本地示例服务器，方便开发者本地调试 (如 Ollama MCP 或 Stdio-SSE 适配器)
    private fun getBuiltInServers(): List<McpServerConfig> {
        val localMock = McpServerConfig(
            id = "mock_weather_server",
            name = "Weather & Geo MCP Server",
            sseUrl = "http://10.0.2.2:3000/sse", // Android 模拟器访问宿主机的环回地址
            isEnabled = false,
            autoRetry = true
        )
        return listOf(localMock)
    }
}
```

---

## 三、 多服务器并发管理与断线自动重试逻辑 (`McpManager`)

为了应对不稳定的网络环境或本地服务的启停，`McpManager` 统一管理所有的 `McpClient` 实例，处理它们的并发并发连接，并实现自动重连。

### 3.1 `McpManager` 整体逻辑架构

1. **实例池**：维护一个以 `serverId` 为 Key，`McpClient` 为 Value 的 `ConcurrentHashMap`。
2. **生命周期绑定**：当 App 启动时，`McpManager` 从存储中读取配置，并对所有 `isEnabled = true` 的服务器执行 `connect()`。
3. **断线检测与重试调度**：
   - 监听每个 `McpClient` 的 `status` 流。
   - 当检测到状态由 `CONNECTED`/`CONNECTING` 转变为 `DISCONNECTED`，且配置中的 `autoRetry` 为 true 时，自动开启指数退避的重试机制。
   - 提供一个全局的工具映射表，汇总所有在线服务器的工具，为 LLM 提供统一的工具集调用。

### 3.2 自动重连与状态合并代码设计

```kotlin
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

class McpManager(private val context: Context) {
    private val storage = McpConfigStorage(context)
    private val clientPool = ConcurrentHashMap<String, McpClient>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 重试任务的管理 Map，防止同一服务器触发多个重试协程
    private val retryJobs = ConcurrentHashMap<String, Job>()

    // 暴露全局所有服务器的在线状态流
    private val _globalStatuses = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val globalStatuses: StateFlow<Map<String, McpStatus>> = _globalStatuses

    // 合并并暴露所有可用工具的只读流
    val allTools: StateFlow<List<McpToolWithServer>> = _globalStatuses
        .map { statuses ->
            clientPool.values
                .filter { statuses[it.serverId] == McpStatus.CONNECTED }
                .flatMap { client ->
                    client.tools.value.map { McpToolWithServer(client.serverId, client.serverName, it) }
                }
        }
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    init {
        initializeClients()
    }

    private fun initializeClients() {
        val configs = storage.loadServers()
        configs.forEach { config ->
            val client = McpClient(config.id, config.name, config.sseUrl)
            clientPool[config.id] = client
            
            // 订阅单个客户端的状态，实现全局状态合并与断线重连触发
            coroutineScope.launch {
                client.status.collect { status ->
                    updateGlobalStatus(config.id, status)
                    if (status == McpStatus.DISCONNECTED) {
                        triggerAutoRetry(config.id)
                    } else if (status == McpStatus.CONNECTED) {
                        // 连上后，取消当前的重试 Job
                        retryJobs[config.id]?.cancel()
                        retryJobs.remove(config.id)
                    }
                }
            }

            if (config.isEnabled) {
                client.connect()
            }
        }
    }

    private fun updateGlobalStatus(id: String, status: McpStatus) {
        val current = _globalStatuses.value.toMutableMap()
        current[id] = status
        _globalStatuses.value = current
    }

    // 指数退避重试逻辑
    private fun triggerAutoRetry(serverId: String) {
        // 如果没有配置该服务器、服务器已停用，或者已有重试任务在运行，则不启动新重试
        val config = storage.loadServers().find { it.id == serverId } ?: return
        if (!config.isEnabled || !config.autoRetry) return
        if (retryJobs.containsKey(serverId)) return

        val client = clientPool[serverId] ?: return

        val job = coroutineScope.launch {
            var attempt = 0
            val baseDelayMs = 2000L // 基础重试间隔 2s
            val maxDelayMs = 60000L // 最大重试间隔 60s
            
            while (isActive && client.status.value == McpStatus.DISCONNECTED) {
                attempt++
                val delayTime = min(maxDelayMs, baseDelayMs * 2.0.pow(attempt.toDouble()).toLong())
                Log.d("McpManager", "[$serverId] Attempt $attempt: reconnecting in ${delayTime}ms")
                delay(delayTime)
                
                // 再次检查配置是否中途被禁用
                val currentConfig = storage.loadServers().find { it.id == serverId }
                if (currentConfig == null || !currentConfig.isEnabled) {
                    break
                }
                
                Log.d("McpManager", "[$serverId] Retrying connection...")
                client.connect()
            }
        }
        retryJobs[serverId] = job
    }

    // 动态增删服务器
    fun addServer(config: McpServerConfig) {
        val current = storage.loadServers().toMutableList()
        current.add(config)
        storage.saveServers(current)

        val client = McpClient(config.id, config.name, config.sseUrl)
        clientPool[config.id] = client
        coroutineScope.launch {
            client.status.collect { status ->
                updateGlobalStatus(config.id, status)
                if (status == McpStatus.DISCONNECTED) triggerAutoRetry(config.id)
            }
        }
        if (config.isEnabled) {
            client.connect()
        }
    }

    fun updateServer(config: McpServerConfig) {
        val current = storage.loadServers().map { if (it.id == config.id) config else it }
        storage.saveServers(current)

        val client = clientPool[config.id]
        if (client != null) {
            // 如果连接 URL 变了，或者被禁用了，先断开当前连接
            if (client.sseUrl != config.sseUrl || !config.isEnabled) {
                client.disconnect()
                // 更新 client 实例，由于 URL 更改需要重新实例化
                if (client.sseUrl != config.sseUrl) {
                    val newClient = McpClient(config.id, config.name, config.sseUrl)
                    clientPool[config.id] = newClient
                    coroutineScope.launch {
                        newClient.status.collect { status ->
                            updateGlobalStatus(config.id, status)
                            if (status == McpStatus.DISCONNECTED) triggerAutoRetry(config.id)
                        }
                    }
                    if (config.isEnabled) newClient.connect()
                }
            } else if (config.isEnabled && client.status.value == McpStatus.DISCONNECTED) {
                client.connect()
            }
        }
    }

    fun deleteServer(serverId: String) {
        val current = storage.loadServers().filter { it.id != serverId }
        storage.saveServers(current)

        retryJobs[serverId]?.cancel()
        retryJobs.remove(serverId)

        clientPool.remove(serverId)?.disconnect()
        val currentStatuses = _globalStatuses.value.toMutableMap()
        currentStatuses.remove(serverId)
        _globalStatuses.value = currentStatuses
    }
}

data class McpToolWithServer(
    val serverId: String,
    val serverName: String,
    val tool: McpTool
)
```

---

## 四、 SettingsScreen.kt 的 “Claude美学” 配置面板设计

为了让 Loyea 保持高端优雅的视觉品质，我们将为 MCP 面板进行深度的 UI 定制。Claude美学的视觉核心是**温暖自然、低对比度、半透明质感、顺滑微动效**。

### 4.1 视觉美学设计规范
1. **配色方案 (莫兰迪色调)**：
   - 使用柔和米色、灰褐色、鼠尾草绿与微润的土黄。
   - 背景使用非常舒适的轻米白/极客浅灰（`#FBFBFA` 对应 Light Mode，`#1A1A18` 对应 Dark Mode）。
   - 连接成功态小圆点使用自然淡雅的草绿（`#8FBC8F` 或 `#84A98C`），而非刺眼的亮绿色。
   - 警告态小圆点使用柔和陶土色（`#C58F7B`）。
2. **质感 (磨砂边框与物理卡片)**：
   - 杜绝粗重的硬边框。卡片背景采用高透明的 Surface 色，辅以轻微的磨砂边框：
     `border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape = RoundedCornerShape(16.dp))`。
   - 增加轻盈的物理投影（微弱阴影）。
3. **动效 (物理弹性与折叠动画)**：
   - 展开详细配置与工具列表时，绝不可生硬闪现。应当基于 `animateDpAsState` 进行高度物理阻尼过渡。
   - 展开工具卡片时，使用 `AnimatedVisibility` 结合 `expandVertically` 与 `shrinkVertically`。
   - 指示灯（如连接中）采用优雅的脉动缩放或呼吸淡入淡出（通过 `infiniteRepeatable` 的 `Keyframes` 改变 Scale 与 Alpha）。
4. **回馈 (水波纹)**：
   - 触控回馈不使用普通的纯色变暗，而是使用基于莫兰迪辅色调的温和水波纹（如 `rememberRipple(color = Color(0xFFEADFD3))`）。

### 4.2 SettingsScreen.kt 二级页面扩展设计

我们需要在 `SettingsScreen` 中为 `SettingsSubPage` 添加 `MCP_CONFIG` 分支。

#### 4.2.1 整体架构嵌入

```kotlin
// SettingsScreen.kt
enum class SettingsSubPage {
    MAIN, API_CONFIG, THEME_SETTINGS, MCP_CONFIG // 新增 MCP_CONFIG 页面
}

// 并在 SettingsScreen 的 AnimatedContent 路由分发中增加：
composable(SettingsSubPage.MCP_CONFIG) {
    McpConfigLayout(
        mcpManager = chatViewModel.mcpManager, // ViewModel 注入的管理器
        appLanguage = appLanguage,
        onBackClick = { subPage = SettingsSubPage.MAIN }
    )
}
```

#### 4.2.2 McpConfigLayout 页面结构设计 (Jetpack Compose 伪代码)

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpConfigLayout(
    mcpManager: McpManager,
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"
    
    // 监听全局服务器状态与配置
    val serversList by remember { mutableStateOf(mcpManager.getStorage().loadServers()) } // 可通过 ViewModel 封装为 MutableState
    val serverStatuses by mcpManager.globalStatuses.collectAsState()
    
    var showSheet by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "MCP Servers" else "MCP 协议多服务器管理", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingServer = null
                        showSheet = true
                    }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Server", modifier = Modifier.size(26.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFBFBFA)) // 莫兰迪自然白
            )
        },
        containerColor = Color(0xFFFBFBFA)
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (serversList.isEmpty()) {
                    item {
                        EmptyStatePanel(isEn)
                    }
                } else {
                    items(serversList) { server ->
                        val status = serverStatuses[server.id] ?: McpStatus.DISCONNECTED
                        McpServerCard(
                            server = server,
                            status = status,
                            isEn = isEn,
                            onToggleEnable = { enabled ->
                                mcpManager.updateServer(server.copy(isEnabled = enabled))
                            },
                            onEdit = {
                                editingServer = server
                                showSheet = true
                            },
                            onDelete = {
                                mcpManager.deleteServer(server.id)
                            }
                        )
                    }
                }
            }

            // 添加/编辑抽屉
            AnimatedVisibility(
                visible = showSheet,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable { showSheet = false }
                )
            }

            AnimatedVisibility(
                visible = showSheet,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                AddOrEditMcpSheet(
                    editingServer = editingServer,
                    isEn = isEn,
                    onSave = { updatedServer ->
                        if (editingServer == null) {
                            mcpManager.addServer(updatedServer)
                        } else {
                            mcpManager.updateServer(updatedServer)
                        }
                        showSheet = false
                    },
                    onDismiss = { showSheet = false }
                )
            }
        }
    }
}
```

#### 4.2.3 Claude 风格 McpServerCard 设计 (折叠动效与磨砂指示器)

```kotlin
@Composable
fun McpServerCard(
    server: McpServerConfig,
    status: McpStatus,
    isEn: Boolean,
    onToggleEnable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), // 极细半透明磨砂边框
                shape = RoundedCornerShape(16.dp)
            )
            .background(Color.White) // 保持纯净感
            .clickable(
                onClick = { expanded = !expanded },
                indication = rememberRipple(color = Color(0xFFEADFD3)), // 琥珀沙黄水波纹
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(16.dp)
    ) {
        // 卡片头部：状态、别名、主开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 莫兰迪色态指示灯
                StatusIndicatorDot(status = status)
                Spacer(modifier = Modifier.width(10.dp))
                
                Column {
                    Text(
                        text = server.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E2E2C) // 灰褐色字体代替纯黑，更柔和
                    )
                    Text(
                        text = getStatusText(status, isEn),
                        fontSize = 11.sp,
                        color = getStatusColor(status).copy(alpha = 0.8f)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 折叠指示箭头
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF2E2E2C).copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                // 极简扁平开关
                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggleEnable,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF84A98C), // 莫兰迪鼠尾草绿
                        uncheckedThumbColor = Color(0xFFE0EDE9),
                        uncheckedTrackColor = Color(0xFFF0F0F2)
                    )
                )
            }
        }

        // 折叠面板：展示 SSE 详情、工具列表与快速操作
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Divider(color = Color(0xFFF0EDE9)) // 莫兰迪灰边线

                // 物理参数标签：SSE URL
                Column {
                    Text(
                        text = "SSE ENDPOINT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E2E2C).copy(alpha = 0.4f)
                    )
                    Text(
                        text = server.sseUrl,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF2E2E2C).copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // 自动重连标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEn) "Auto Reconnect" else "断线自动重试",
                        fontSize = 13.sp,
                        color = Color(0xFF2E2E2C).copy(alpha = 0.7f)
                    )
                    BadgeLabel(text = if (server.autoRetry) "ON" else "OFF")
                }

                // 模拟已连接状态下的工具展示列表
                if (status == McpStatus.CONNECTED) {
                    ToolsShowcaseSection(isEn)
                }

                // 操作按钮条
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onEdit) {
                        Text(if (isEn) "Edit" else "编辑", color = Color(0xFF84A98C))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onDelete) {
                        Text(if (isEn) "Delete" else "删除", color = Color(0xFFC58F7B))
                    }
                }
            }
        }
    }
}
```

#### 4.2.4 StatusIndicatorDot 指示灯设计 (带渐变脉冲动画)

为了体现高动态感，`CONNECTING` 状态指示灯应当有一个柔和的顺时针微动或脉冲动画。

```kotlin
@Composable
fun StatusIndicatorDot(status: McpStatus) {
    val color = getStatusColor(status)
    
    if (status == McpStatus.CONNECTING) {
        // 脉冲式缩放动画
        val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "IndicatorPulse"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "IndicatorAlpha"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha * 0.3f))
            )
        }
    } else {
        // CONNECTED 和 DISCONNECTED 使用静态高颜值圆点
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

private fun getStatusColor(status: McpStatus): Color {
    return when (status) {
        McpStatus.CONNECTED -> Color(0xFF84A98C)     // 莫兰迪草绿
        McpStatus.CONNECTING -> Color(0xFFD6B265)    // 莫兰迪姜黄
        McpStatus.DISCONNECTED -> Color(0xFF9E9E9E)  // 莫兰迪灰
    }
}
```

---

## 五、 设计验证方案与联调规划

Milestone 1 的验证方法依赖于单元测试与接口协议测试（在 Milestone 0 中搭建的测试桩）。

1. **模拟测试桩设计**：
   在宿主机上运行本地 Node.js 示例 MCP SSE 服务器，开启端口 `3000`，暴露出 `weather_server` 等工具。
2. **断线状态自检**：
   - 客户端连接建立后，通过断开宿主机网络，或者直接杀掉 Node.js 进程来强制中断 TCP 连接。
   - 验证客户端是否立刻检测到 `onFailure`，并且状态是否成功回落到 `DISCONNECTED`。
   - 开启 `autoRetry` 后，验证控制台日志中是否输出 `Attempt 1`，`Attempt 2` 的秒数级递增日志，并在 Node.js 重启后，客户端是否能够自动握手完成 `CONNECTED` 恢复。
3. **UI 回馈测试**：
   - 切换系统深浅色主题，验证莫兰迪色系的视觉表现力。
   - 点击展开列表项，验证折叠/展开时的插值器性能，保证无掉帧现象。
