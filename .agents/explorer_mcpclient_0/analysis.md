# Loyea MCP 客户端协议与多服务器管理设计分析

本报告针对 Loyea 赛博伴侣项目的 Milestone 1 进行深入分析与技术架构设计。本着只读探索的原则，本文件不直接修改项目源码，而是提供手把手的改造方案与逻辑设计，以支持后续的开发实现。

---

## 一、 标准 JSON-RPC over HTTP/SSE 协议客户端设计

在 Android (Kotlin) 环境中，由于无法像桌面端那样轻量地拉起本地进程（Stdio），**HTTP/SSE（Server-Sent Events）** 是对接外部 MCP 服务的最佳且最标准的协议方式。

### 1. 通讯流程与时序
根据 MCP 规范，客户端与服务端的通信采用“发送/接收分离”的非对称通道：
1. **建立 SSE 链接**：客户端发起对服务端的 SSE 请求（`GET <sse-url>`），并建立持久连接。
2. **端点握手 (Endpoint Discovery)**：一旦连接开启，服务端会推送一个特定的 SSE 事件（`event: "endpoint"`），数据负载（`data`）为客户端发送 POST 请求的目标端点路径（例如 `/message?sessionId=xxx`）。
3. **初始化握手 (Initialize)**：
   - 客户端解析并记录该 `messageEndpoint`。
   - 客户端向 `messageEndpoint` 发送 HTTP POST 请求，载荷为 JSON-RPC `initialize` 格式。
   - 服务端通过已建立的 SSE 链接返回 `initialize` 的 JSON-RPC 响应。
   - 客户端确认收到后，向 `messageEndpoint` 发送 `notifications/initialized` 通知。
4. **业务请求 (Tools List & Tools Call)**：客户端发送 POST，服务端通过 SSE 异步回应，通过消息 ID (`id`) 对齐。

```
Client                                                  MCP Server
  |                                                          |
  |================ 1. GET <sseUrl> (SSE 连接) =============>|
  |<=============== 2. SSE event: "endpoint" (带SessionID) ==|
  |                                                          |
  |---- 3. POST <endpoint> (method: "initialize") ---------->|
  |<--- 4. SSE event: "message" (initialize 响应) ------------|
  |                                                          |
  |---- 5. POST <endpoint> (method: "notifications/initialized") ->|
  |                                                          |
  |                     [ 连接建立 (CONNECTED) ]              |
  |                                                          |
  |---- 6. POST <endpoint> (method: "tools/list", id: 101) ->|
  |<--- 7. SSE event: "message" (tools/list 响应, id: 101) ---|
```

### 2. 类层级设计 (位于 `com.loyea.mcp`)
我们将设计两个核心的底层类：
- `McpClient`：单个 MCP 服务的连接实体，负责生命周期和数据读写。
- `McpManager`：多服务端连接的管理中枢，暴露在线状态和工具聚合并发调用。

#### `McpClient` 设计
- **核心成员变量**：
  - `config: McpServerConfig`（配置数据）
  - `status: MutableStateFlow<McpServerStatus>`（单个连接状态流）
  - `okhttpClient: OkHttpClient`（网络请求组件）
  - `eventSource: EventSource?`（SSE 订阅对象）
  - `messageEndpoint: String?`（从 `endpoint` 事件中解析出的 POST 目标）
  - `pendingRequests: ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>`（并发请求挂起队列）
  - `discoveredTools: List<McpTool>`（已发现的工具缓存）
  
- **核心方法**：
  - `suspend fun connect()`：启动 SSE 连接，订阅事件，并挂起等待 `initialize` 和 `notifications/initialized` 握手完成。
  - `fun disconnect()`：取消 SSE 连接，清空挂起的请求，并将状态置为 `DISCONNECTED`。
  - `suspend fun sendRequest(method: String, params: Any?): JsonRpcResponse`：发送 JSON-RPC 请求的主函数。
    1. 生成自增 `id`。
    2. 创建 `CompletableDeferred<JsonRpcResponse>` 并放入 `pendingRequests`。
    3. 将请求序列化为 JSON，通过 POST 发送至 `messageEndpoint`。
    4. 用 `withTimeout(15000)` 挂起等待 `deferred.await()`。
    5. 返回响应或抛出超时/网络异常。

- **SSE 接收分发逻辑 (`EventSourceListener`)**：
  - `onOpen`：连接建立。
  - `onEvent(event, id, data)`：
    - 若 `event == "endpoint"`：更新 `messageEndpoint` 变量，并发射开始初始化的信号。
    - 若 `event == "message"`：解析 `data` 为 JSON-RPC 消息结构。
      - 如果包含 `id` 属性，在 `pendingRequests` 找到对应的 `CompletableDeferred`，调用 `complete(response)` 并从 map 移除。
      - 如果是通知（无 `id`，带 `method`），由客户端进行本地状态同步。
  - `onClosed` & `onFailure`：执行 `disconnect()`，并通过回调通知 `McpManager` 触发自动重试。

---

## 二、 McpManager 多服务器并发连接与重试设计

`McpManager` 作为单例（通过 Dagger/Hilt 注入或在 `ChatViewModel` 中实例化），对外统一封装 MCP 子系统的全部能力。

### 1. 多服务器管理与工具列表并发聚合
- **客户端映射**：`private val activeClients = ConcurrentHashMap<String, McpClient>()`
- **连接控制**：
  - `fun start()`：从存储中加载所有配置，对所有 `isEnabled == true` 的服务端异步调用 `connect()`。
  - `fun stop()`：断开所有活动的客户端。
  - `fun toggleServer(id: String, enable: Boolean)`：根据开关，创建并连接新 `McpClient`，或销毁现有 client。
- **并发工具列表聚合 (`getTools`)**：
  - 大模型需要获取所有可用服务器的工具合集。
  - 实现策略：
    ```kotlin
    suspend fun getAggregateTools(): List<McpTool> = coroutineScope {
        activeClients.values
            .filter { it.status.value == McpServerStatus.CONNECTED }
            .map { client ->
                async {
                    try {
                        client.fetchTools() // 对应 JSON-RPC tools/list 请求
                    } catch (e: Exception) {
                        emptyList<McpTool>()
                    }
                }
            }
            .awaitAll()
            .flatten()
    }
    ```

### 2. 状态流转与断线指数退避重试
- **状态流**：对外暴露 `val serverStates: StateFlow<Map<String, McpServerStatus>>`，使 UI 可以响应式地更新指示灯。
- **重试状态机流转**：
  ```
  [DISCONNECTED] --(启用/手动连接)--> [CONNECTING] --(握手成功)--> [CONNECTED]
         ^                                |                             |
         |                         (连接/握手失败)                     (网络断开)
         |                                |                             |
         +-------(重试超限/用户关闭)<------+<----------------------------+
                                          |
                                    [指数退避重试]
  ```
- **重试策略设计**：
  - 当 `McpClient` 连接失败或在连接状态下因网络故障断开（触发 `onFailure`），如果服务器配置仍处于启用状态，则启动重连例程。
  - **网络感知**：使用 Android 的 `ConnectivityManager`。当网络彻底断开时，通过协程的 `suspendCancellableCoroutine` 挂起，监听网络状态广播，当网络恢复时立即唤醒，避免在没有网的环境下进行无效请求。
  - **指数退避参数**：
    - `initialDelay` = 2000 ms
    - `maxDelay` = 30000 ms
    - `multiplier` = 2.0
  - **重试循环实现伪代码**：
    ```kotlin
    fun scheduleReconnect(config: McpServerConfig) {
        viewModelScope.launch {
            var attempt = 0
            while (isServerEnabled(config.id)) {
                updateServerStatus(config.id, McpServerStatus.CONNECTING)
                // 等待网络可用
                awaitNetworkAvailable()
                
                val success = tryConnect(config)
                if (success) {
                    break
                }
                
                attempt++
                val delayTime = (initialDelay * Math.pow(multiplier, attempt.toDouble()))
                    .toLong().coerceAtMost(maxDelay)
                delay(delayTime)
            }
        }
    }
    ```

---

## 三、 配置持久化与 JSON 读写设计 (`McpConfigStorage`)

使用 SharedPreferences 进行持久化。虽然 SharedPreferences 不适合海量数据，但对于少量的服务器配置（一般不超过 10 个），采用 GSON 序列化为 JSON 字符串存储是极简且高度内聚的做法。

### 1. 持久化数据模型
```kotlin
data class McpServerConfig(
    val id: String,          // 唯一标识 (UUID 或毫秒时间戳)
    val name: String,        // 服务器别名 (如 "Local Workspace")
    val sseUrl: String,      // SSE 连接地址 (如 "http://10.0.2.2:3000/sse")
    val isEnabled: Boolean   // 启用状态开关
)
```

### 2. 存储设计
在 `com.loyea.mcp` 中实现 `McpConfigStorage` 类：
- 成员变量：`private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)`
- 序列化方案：使用已经引入项目的 `com.google.gson.Gson`。
- 读写接口设计：
  ```kotlin
  class McpConfigStorage(context: Context) {
      private val gson = Gson()
      private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
      private val key = "mcp_server_configs"

      fun loadConfigs(): List<McpServerConfig> {
          val json = prefs.getString(key, null) ?: return emptyList()
          return try {
              val type = object : TypeToken<List<McpServerConfig>>() {}.type
              gson.fromJson(json, type) ?: emptyList()
          } catch (e: Exception) {
              emptyList()
          }
      }

      fun saveConfigs(configs: List<McpServerConfig>) {
          val json = gson.toJson(configs)
          prefs.edit().putString(key, json).apply()
      }
  }
  ```

---

## 四、 设置页 SettingsScreen.kt 的“Claude美学”设计与集成

为保持与项目已有的“极简高颜值 Anthropic”风格（莫兰迪色调、燕麦白、Anthropic 衬线与无衬线字体）一致，我们将为 MCP 配置面板深度定制一套具有“磨砂感”、“阻尼折叠”与“呼吸指示”的 Compose UI。

### 1. SettingsScreen 的导航扩展
- **二级页面枚举扩充**：
  在 `SettingsSubPage` 中新增：
  ```kotlin
  enum class SettingsSubPage {
      MAIN, API_CONFIG, THEME_SETTINGS, MCP_CONFIG // 新增 MCP_CONFIG
  }
  ```
- **导航分发更新**：
  在 `SettingsScreen` 的 `AnimatedContent` 容器中增加：
  ```kotlin
  SettingsSubPage.MCP_CONFIG -> {
      McpConfigLayout(
          mcpConfigs = mcpConfigs, // 来自 ViewModel
          mcpStates = mcpStates,   // 来自 ViewModel
          onMcpConfigsSave = onMcpConfigsSave,
          onBackClick = { subPage = SettingsSubPage.MAIN }
      )
  }
  ```

- **一级设置主页入口设计 (SettingsMainLayout)**：
  在“系统设置”分组中，加入“MCP 赛博插件”的磨砂质感卡片入口：
  - **莫兰迪色调底色**：亮色模式下使用 `LoyeaLightSurface` (`#F3EFE6`)，暗色模式下使用 `LoyeaDarkSurface` (`#2E2A27`)。
  - **磨砂边框**：`border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), RoundedCornerShape(12.dp))`。
  - **水波纹反馈**：使用 `.clip(RoundedCornerShape(12.dp)).clickable { onNavigateToMcp() }`。
  - **动态连接数提示**：副标题动态显示 `已连接：$connectedCount / ${mcpConfigs.size}`。

### 2. MCP 管理二级页面设计 (`McpConfigLayout`)
整体采用全屏 Scaffold，配合顶部的 `TopAppBar` 和右侧的 `+`（新增服务器）按钮。

#### A. 列表卡片组件 (`McpServerCardItem`)
每个卡片代表一个服务端配置。卡片支持**折叠动画**：
- **容器样式**：
  ```kotlin
  Column(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(
              width = 1.dp,
              color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) 
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
              shape = RoundedCornerShape(12.dp)
          )
          .background(MaterialTheme.colorScheme.surface)
          .padding(16.dp)
  )
  ```
- **头部核心元素 (Header)**：
  - **在线状态呼吸指示灯**：
    - 颜色对应：
      - `CONNECTED` -> 莫兰迪绿 `Color(0xFF84A98C)`
      - `CONNECTING` -> 琥珀沙黄 `Color(0xFFEADFD3)` (动态呼吸效果)
      - `DISCONNECTED` -> 辅助灰褐 `Color(0xFF9E998F)`
    - **呼吸灯动效实现**：
      ```kotlin
      val infiniteTransition = rememberInfiniteTransition()
      val alpha by infiniteTransition.animateFloat(
          initialValue = 0.4f,
          targetValue = 1.0f,
          animationSpec = infiniteRepeatable(
              animation = tween(1200, easing = LinearEasing),
              repeatMode = RepeatMode.Reverse
          )
      )
      // 在 CONNECTING 状态下应用 alpha 进行闪烁/呼吸
      Box(
          modifier = Modifier
              .size(10.dp)
              .clip(CircleShape)
              .background(color.copy(alpha = if (status == CONNECTING) alpha else 1f))
      )
      ```
  - **服务器别名与简略 URL**：使用 `AnthropicSans` 字体，主标题加粗。
  - **开启/关闭 Switch**：控制 `isEnabled`，改变时触发 `McpManager` 连接/断开逻辑。
  - **折叠控制按钮**：点击整行可展开折叠，带有水波纹。

- **折叠展示区 (展开面板)**：
  使用 Jetpack Compose 的 `AnimatedVisibility` 结合 `expandVertically() + fadeIn()` 和 `shrinkVertically() + fadeOut()`，提供平滑阻尼折叠：
  ```kotlin
  AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)) + fadeIn(),
      exit = shrinkVertically() + fadeOut()
  ) {
      Column(
          modifier = Modifier.padding(top = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
          // 1. 服务端别名输入框
          OutlinedTextField(
              value = nameInput,
              onValueChange = { nameInput = it },
              label = { Text("服务端名称") },
              textStyle = TextStyle(fontFamily = AnthropicSans)
          )
          // 2. SSE URL 输入框
          OutlinedTextField(
              value = urlInput,
              onValueChange = { urlInput = it },
              label = { Text("SSE 连接 URL") }
          )
          
          // 3. 已发现的工具展示列表 (仅 CONNECTED 状态展示)
          if (status == CONNECTED && tools.isNotEmpty()) {
              Text("可用工具", style = MaterialTheme.typography.labelSmall)
              FlowRow(
                  mainAxisSpacing = 6.dp,
                  crossAxisSpacing = 6.dp
              ) {
                  tools.forEach { tool ->
                      BadgeLabel(text = tool.name) // 沿用项目的 Badge 组件
                  }
              }
          }
          
          // 4. 操作按钮行
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End
          ) {
              // 测试连接按钮 (Outlined 极简风格)
              OutlinedButton(
                  onClick = { onTestConnection(urlInput) },
                  colors = ButtonDefaults.outlinedButtonColors()
              ) {
                  Text("测试连接")
              }
              Spacer(modifier = Modifier.width(8.dp))
              // 删除按钮 (莫兰迪粉红)
              Button(
                  onClick = { onDeleteConfig(config.id) },
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC97A7A))
              ) {
                  Text("删除", color = Color.White)
              }
          }
      }
  }
  ```

#### B. 新增服务器抽屉 (BottomSheet)
点击右上角的 `+` 动作，弹出半屏 Sheet：
- 使用圆角面板：`RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)`。
- 顶部保留阻尼手势条：`Box` 容器，尺寸 `36.dp x 4.dp`，圆角，背景为弱透明的 `onBackground`。
- 水波纹保存按钮：点击后将配置存入持久化，立即触发 `McpManager` 建立连接。

---

## 五、 全局集成改造要点

1. **`ChatViewModel` 桥接**：
   - 增加状态：
     - `var mcpConfigs = mutableStateOf<List<McpServerConfig>>(emptyList())`
     - `val mcpStates`：从 `McpManager.serverStates` 中 `collectAsState` 转换为 Compose State。
   - 在 `init` 中读取配置并调用 `McpManager.start(mcpConfigs.value)`。
   - 实现增删改接口：`saveMcpConfigs(newList)`，保存至 `McpConfigStorage` 并在 `McpManager` 中更新连接。

2. **对话拦截设计预留 (Milestone 2 基础)**：
   - 在 `LlmClient` 发送请求前，调用 `McpManager.getAggregateTools()` 获取所有工具，动态转换为大模型的 `tools` 参数声明。
   - 当 `LlmClient` 返回带有 `tool_calls` 的响应时，挂起当前流式传输，提取工具名与参数，调用 `McpManager.callTool()` 异步执行，将执行结果追加回对话历史，重新发起请求。
