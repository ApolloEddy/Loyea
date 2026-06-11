# Loyea MCP 客户端协议与多服务器管理设计分析报告

本报告根据 `PROJECT.md` 的 Milestone 1 要求，深入分析并详细设计了 Loyea 的多 MCP 服务器管理模块、标准 JSON-RPC over HTTP/SSE 客户端协议实现、结合“Claude美学”的配置面板 UI 设计、SharedPreferences 持久化策略以及断线自动重试与状态流转机制。

---

## 一、 系统架构设计与 Code Layout

为完美融入 Loyea 现有的代码架构，我们新增的 MCP 模块代码将存放在 `app/src/main/java/com/loyea/mcp/` 目录下，并在 `app/src/main/java/com/loyea/ui/settings/` 中修改和集成配置界面。

```
com.loyea.
├── mcp/
│   ├── model/
│   │   ├── McpServerConfig.kt     // 服务器配置实体
│   │   ├── McpConnectionState.kt  // 连接状态枚举
│   │   └── JsonRpcModels.kt       // JSON-RPC 2.0 报文实体 (Request, Response, Error)
│   ├── client/
│   │   └── McpServerClient.kt     // 单个 SSE/HTTP 客户端连接管理器
│   ├── McpManager.kt              // 多服务器并发连接管理器与工具路由
│   └── McpConfigStorage.kt        // SharedPreferences 持久化与损坏自愈
└── ui/
    └── settings/
        └── SettingsScreen.kt      // 注入 MCP 配置面板 (Claude 美学)
```

---

## 二、 任务 1：标准 JSON-RPC over HTTP/SSE 协议客户端实现

由于 Android 设备运行 Stdio 外部子进程具有限制，Loyea 采用标准的 **JSON-RPC 2.0 over HTTP/SSE** 传输协议与 MCP 服务器通信。

### 1. 协议生命周期与握手流程
1. **建立 SSE 链接**：客户端发起对服务器 SSE URL（如 `http://10.0.2.2:8000/sse`）的 HTTP GET 请求，并设置 `Accept: text/event-stream` 请求头。
2. **初始化端点绑定**：建立 SSE 连接后，服务器应向客户端推送一个事件，事件类型（`event`）为 `"endpoint"`，其 `data` 为客户端之后发送 JSON-RPC 请求的 HTTP POST 地址（`postEndpointUrl`），例如 `http://10.0.2.2:8000/message?session_id=xxx`。
3. **完成握手**：客户端接收到 `"endpoint"` 事件并成功解析 URL 后，状态正式流转为 `CONNECTED`。之后的所有 JSON-RPC 请求将通过 HTTP POST 发送到该地址。

### 2. 双向异步通信与请求配对设计
因为 SSE 是服务器到客户端的单向流，且 JSON-RPC 的请求与响应是异步的，我们需要将发送的 HTTP POST 请求与 SSE 推送回来的响应包进行配对。

在 `McpServerClient` 中设计一个并发容器 `pendingRequests`。当客户端发送一个 JSON-RPC 请求时，为其分配唯一的 `id`，并使用 `CompletableDeferred` 阻塞协程等待。当 SSE 接收到对应 `id` 的消息事件时，唤醒挂起的协程。

#### JsonRpcModels.kt (数据模型设计)
```kotlin
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String?,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)
```

#### McpServerClient.kt (核心发送与配对实现)
```kotlin
class McpServerClient(
    val config: McpServerConfig,
    private val httpClient: OkHttpClient,
    private val coroutineScope: CoroutineScope
) {
    private val _connectionState = MutableStateFlow(McpConnectionState.DISCONNECTED)
    val connectionState: StateFlow<McpConnectionState> = _connectionState.asStateFlow()

    private var eventSource: EventSource? = null
    private var postEndpointUrl: String? = null
    
    // 用于配对异步 SSE 响应的容器
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()

    fun connect() {
        if (_connectionState.value != McpConnectionState.DISCONNECTED) return
        _connectionState.value = McpConnectionState.CONNECTING

        val request = Request.Builder()
            .url(config.url)
            .header("Accept", "text/event-stream")
            .build()

        eventSource = EventSources.createFactory(httpClient)
            .newEventSource(request, sseListener)
    }

    fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        postEndpointUrl = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        _connectionState.value = McpConnectionState.DISCONNECTED
    }

    suspend fun callTool(toolName: String, arguments: JsonElement): JsonRpcResponse {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[id] = deferred

        val params = JsonObject().apply {
            addProperty("name", toolName)
            add("arguments", arguments)
        }
        val rpcRequest = JsonRpcRequest(id = id, method = "tools/call", params = params)
        val requestBody = Gson().toJson(rpcRequest).toRequestBody("application/json".toMediaType())

        val postUrl = postEndpointUrl ?: throw IllegalStateException("SSE endpoint not initialized")
        val postRequest = Request.Builder().url(postUrl).post(requestBody).build()

        return try {
            val response = httpClient.newCall(postRequest).executeAsync()
            
            // E2E 容错支持：部分服务器可能直接在 HTTP POST 响应中返回 200 OK 和结果，而非通过 SSE
            if (response.isSuccessful && response.code == 200) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val rpcResponse = Gson().fromJson(body, JsonRpcResponse::class.java)
                    pendingRequests.remove(id)
                    return rpcResponse
                }
            }
            
            // 等待 SSE 返回或 10 秒超时
            withTimeout(10000L) {
                deferred.await()
            }
        } catch (e: Exception) {
            pendingRequests.remove(id)
            throw e
        }
    }

    private val sseListener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            // 已开启 SSE 通道，但仍需等待 "endpoint" 事件通知以获取 POST 目标 URL
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            coroutineScope.launch {
                try {
                    when (type) {
                        "endpoint" -> {
                            postEndpointUrl = data.trim()
                            _connectionState.value = McpConnectionState.CONNECTED
                        }
                        else -> {
                            // 解析 data 为 JsonRpcResponse 并分发
                            val response = Gson().fromJson(data, JsonRpcResponse::class.java)
                            response.id?.let { rpcId ->
                                pendingRequests.remove(rpcId)?.complete(response)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 解析异常处理
                }
            }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            disconnect()
            // 触发指数退避自动重试逻辑
        }

        override fun onClosed(eventSource: EventSource) {
            disconnect()
        }
    }
}
```

---

## 三、 任务 2：设置页 `SettingsScreen.kt` 与“Claude 美学”的配置面板

“Claude美学”的核心在于：**极致的物理交互（水波纹）、低饱和度莫兰迪色系搭配、磨砂玻璃般透明有质感的细边框，以及弹性的展开折叠动画**。

### 1. 莫兰迪配色方案与卡片样式定义
我们在配置面板中引入以下颜色与修饰符：
- **亮色莫兰迪米白**（背景）：`Color(0xFFFBF8F5)`
- **莫兰迪灰**（边框/非活跃底色）：`Color(0xFFEBE8E2)`
- **Claude 土橘红**（主激活/按钮）：`Color(0xFFD97756)`
- **莫兰迪健康绿**（已连接）：`Color(0xFF8FA89B)` 或 `Color(0xFFE2F1E8)`
- **文字深炭灰**：`Color(0xFF383836)`

### 2. 磨砂质感卡片与折叠动画列表设计
在 `SettingsScreen.kt` 中添加 `McpConfigLayout`。每一个服务器显示为一个卡片：
- 边框采用高透明度浅灰色：`Modifier.border(1.dp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp))`
- 交互水波纹回馈：使用 `Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = rememberRipple(bounded = true, color = Color(0xFFD97756)))`。
- 折叠动画：使用 `AnimatedVisibility` 的 `expandVertically` 与弹性 `spring` 插值器展示服务器的 Tools 列表。

```kotlin
@Composable
fun McpServerCard(
    server: McpServerConfig,
    connectionState: McpConnectionState,
    tools: List<McpTool>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(
                width = 1.dp,
                color = if (server.isEnabled) Color(0xFFD97756).copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { isExpanded = !isExpanded }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 连接状态指示灯 (莫兰迪绿/灰)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    McpConnectionState.CONNECTED -> Color(0xFF8FA89B)
                                    McpConnectionState.CONNECTING -> Color(0xFFE58A6B)
                                    McpConnectionState.DISCONNECTED -> Color(0xFFC4C4C2)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = server.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF383836)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = server.url,
                    fontSize = 11.sp,
                    color = Color.Black.copy(alpha = 0.4f)
                )
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFD97756),
                        uncheckedThumbColor = Color(0xFFC4C4C2),
                        uncheckedTrackColor = Color(0xFFEBE8E2)
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF383836).copy(alpha = 0.6f))
                }
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.6f))
                }
            }
        }

        // 折叠式 Tools 列表展示
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) + fadeOut()
        ) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Divider(color = Color.Black.copy(alpha = 0.05f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "EXPOSED TOOLS (${tools.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF383836).copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (tools.isEmpty()) {
                    Text(
                        text = "No tools available. Connect server to load.",
                        fontSize = 12.sp,
                        color = Color.Black.copy(alpha = 0.3f)
                    )
                } else {
                    // 流式布局显示工具 Badge
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tools.forEach { tool ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFE2F1E8))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(text = tool.name, fontSize = 11.sp, color = Color(0xFF5D7A68))
                            }
                        }
                    }
                }
            }
        }
    }
}
```

---

## 四、 任务 3：使用 SharedPreferences 持久化 MCP 配置

我们将所有的 MCP 服务器配置以单个 JSON 数组的形式持久化到 SharedPreferences 中。

### 1. JSON 持久化数据结构
```json
[
  {
    "id": "srv_1718080000000",
    "name": "MathServer",
    "url": "http://10.0.2.2:8000/sse",
    "type": "SSE",
    "isEnabled": true
  },
  {
    "id": "srv_1718080000001",
    "name": "WeatherServer",
    "url": "http://10.0.2.2:9000/sse",
    "type": "SSE",
    "isEnabled": false
  }
]
```

### 2. 防崩溃自愈加载逻辑
如 E2E 异常测试用例 `R1-T2-05` 所述，若本地存储的 SharedPreferences 遭到外部破坏（例如篡改为非法非 JSON 格式），读取解析将抛出 `JsonSyntaxException`。
为保证 App 绝对防崩自愈，我们采用带有自愈功能的 `loadConfigs` 实现。

```kotlin
class McpConfigStorage(context: Context) {
    private val prefs = context.getSharedPreferences("loyea_mcp_config_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "mcp_servers"

    fun saveConfigs(configs: List<McpServerConfig>) {
        try {
            val json = gson.toJson(configs)
            prefs.edit().putString(key, json).apply()
        } catch (e: Exception) {
            // 静默处理或打日志，不抛出崩溃
        }
    }

    fun loadConfigs(): List<McpServerConfig> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<McpServerConfig>>() {}.type
            gson.fromJson<List<McpServerConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            // 检测到数据文件损坏，执行自愈：清除脏数据，恢复空状态
            prefs.edit().remove(key).apply()
            emptyList()
        }
    }
}
```

---

## 五、 任务 4：断线自动重试与在线状态的实时流转逻辑

### 1. 指数退避与网络抖动自愈机制
自动重连必须遵循：
- 仅当服务器的配置 `isEnabled` 为 `true` 且当前状态处于断开状态时执行。
- 引入指数退避公式：$t = \min(60s, 1s \times 2^{retryCount}) + \text{jitter}$，防范雪崩效应，保护设备电池寿命。
- 加入连接超时，若 10s 内未收到连接确认，抛出超时以重试，防范 UI 线程挂死。

```kotlin
class ConnectionRetryHandler(
    private val client: McpServerClient,
    private val scope: CoroutineScope
) {
    private var retryJob: Job? = null

    fun startAutoRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            var retryCount = 0
            while (isActive && client.config.isEnabled && client.connectionState.value == McpConnectionState.DISCONNECTED) {
                retryCount++
                
                // 计算重试等待间隔
                val delayMs = calculateExponentialDelay(retryCount)
                delay(delayMs)
                
                // 执行一次重连
                try {
                    client.connect()
                    // 挂起观察状态，若 10s 内未连上则判定为本次连接失败，触发下一次重试循环
                    withTimeout(10000L) {
                        client.connectionState.first { it == McpConnectionState.CONNECTED }
                    }
                    // 成功连上，重置计数并退出
                    retryCount = 0
                    break
                } catch (e: Exception) {
                    client.disconnect()
                }
            }
        }
    }

    fun stop() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun calculateExponentialDelay(retry: Int): Long {
        val baseDelay = 1000L // 1秒
        val maxDelay = 60000L // 60秒
        val factor = min(10, retry) // 限制翻倍最大为 2^10
        val calculated = baseDelay * (1 shl factor)
        val jitter = (0..300).random() // 随机加入 0-300ms 抖动，平滑请求峰值
        return min(maxDelay, calculated) + jitter
    }
}
```

### 2. 多服务器重名工具冲突与路由设计 (`McpManager`)
如 `R1-T2-03` 测试用例所述，多台 MCP 服务器可能向客户端暴露同名的 Tools（例如 `CalculatorServer` 和 `GeneralAgent` 都暴露了 `calculate`）。

为防范同名 Tool 导致的注入混乱，`McpManager` 采取**统一命名空间重构与路由**设计：
1. **工具注册包装**：`McpManager` 聚合所有可用工具。如果发现工具名称发生冲突，或者为达到高内聚的彻底路由，所有对外宣告的工具统一重命名为：`[ServerId]__[ToolName]`（例如 `srv_01__calculate`）。
2. **LLM 注入**：大模型接收到的 `tools` 列表中将包含 `srv_01__calculate` 形式的工具声明。
3. **拦截路由分发**：大模型发出 Tool Call，指令为 `srv_01__calculate`。`McpManager` 拦截该指令，首先通过 `__` 将其切割为两部分：`ServerId = "srv_01"`，`OriginalToolName = "calculate"`。
4. **定位分发**：寻找 ID 为 `srv_01` 的 `McpServerClient`，向其发送 `tools/call` 请求，参数中工具名填入 `calculate`。这就完美实现了网络解耦与多服务器共存路由。
