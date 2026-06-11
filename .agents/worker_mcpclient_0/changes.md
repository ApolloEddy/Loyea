# 修改与开发详述 (changes.md)

本文件详述了在 Milestone 1 开发中所添加及修改的全部文件与其背后的架构及业务逻辑实现。

---

## 一、 新增文件 (Added Files)

### 1. `McpServerConfig.kt`
- **路径**：`app/src/main/java/com/loyea/mcp/McpServerConfig.kt`
- **逻辑**：
  - 定义了服务器在线状态枚举 `McpServerStatus`（`CONNECTED`, `CONNECTING`, `DISCONNECTED`）。
  - 定义了服务端配置实体 `McpServerConfig`（包含 `id`, `name`, `sseUrl`, `isEnabled` 开关）。
  - 定义了可用工具数据类 `McpTool`（包含 `name`, `description`, `inputSchema`），用于对接大模型的 Tools 字段声明。

### 2. `JsonRpc.kt`
- **路径**：`app/src/main/java/com/loyea/mcp/JsonRpc.kt`
- **逻辑**：
  - 提供了符合标准 JSON-RPC 2.0 规范的序列化与反序列化模型。
  - 包含 `JsonRpcRequest`（请求模型）、`JsonRpcResponse`（响应模型，包含结果 `JsonElement` 和错误实体）以及 `JsonRpcError`。

### 3. `McpConfigStorage.kt`
- **路径**：`app/src/main/java/com/loyea/mcp/McpConfigStorage.kt`
- **逻辑**：
  - 基于 SharedPreferences 实现 MCP 服务器配置列表的本地 JSON 读写持久化。
  - **损坏自愈逻辑**：在解析 JSON 抛出异常（例如旧配置迁移产生反序列化错误或存储文件损坏）时，通过 `try-catch` 拦截，自动擦除本地损坏键值并返回空列表，从根本上防止应用启动因脏数据引发的闪退崩溃。

### 4. `McpClient.kt`
- **路径**：`app/src/main/java/com/loyea/mcp/McpClient.kt`
- **逻辑**：
  - **下行接收**：利用 OkHttp `EventSourceListener` 监听 SSE 长连接，实时收取服务端推送的事件。
  - **上行发送**：通过普通的 HTTP POST 请求将 JSON-RPC 报文发送给 SSE 返回的 `messageEndpoint`。
  - **异步转挂起（配对唤醒）**：使用 `ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>`。在发送 POST 请求时，注册一个由 UUID 组成的请求 `id` 并挂起协程；在 SSE 端收到带相同 `id` 的 `message` 事件响应时，通过 `CompletableDeferred.complete(response)` 立刻唤醒该挂起函数，完美支持挂起形式的 `suspend fun callTool(...)`。
  - **状态机与握手**：连接时经历 `CONNECTING`，先等待 SSE `endpoint` 事件以解析并拼接（支持相对路径与绝对路径解析）上行接口 URL。接着发送 `initialize` 请求握手，成功后再发送 `notifications/initialized` 通知。最后通过 `fetchTools()` 读取工具列表，并把状态更新为 `CONNECTED`。
  - **Tools 响应式流**：将已发现的工具列表设计为 `StateFlow<List<McpTool>>`，使 Compose 界面能够实时捕获最新可用工具。

### 5. `McpManager.kt`
- **路径**：`app/src/main/java/com/loyea/mcp/McpManager.kt`
- **逻辑**：
  - **多连接池管理**：维护 `ConcurrentHashMap<String, McpClient>` 形式的活动客户端池。在 `start()` 和 `updateConfigs()` 时按需创建、删除或切换连接。
  - **带 Jitter 的指数退避重试**：当客户端异常断开或连接失败时，启动重试协程。初始延迟 2s，按 2.0 倍率指数级翻倍，上限为 60s。重试时加入 `+/- 10%` 的随机抖动（Jitter），防止多服务器并发请求造成的 thundering herd 效应。
  - **网络状态感知**：向 `ConnectivityManager` 注册 `NetworkCallback`，当网络彻底断开时，重连协程会通过 Flow 的 `first { it }` 挂起，断网期间不发起任何请求；当网络恢复时立即唤醒，避免空耗 CPU 和网络资源。
  - **前缀式路由防冲突与 Fallback 机制**：工具聚合时自动为工具名拼上前缀 `${cleanServerName}__${toolName}`；调用时若名字中包含 `__`，切割出前缀直接分发给对应客户端；若不包含，则遍历搜索已连接的客户端列表作 Fallback 兜底。

### 6. `McpConfigStorageTest.kt`
- **路径**：`app/src/test/java/com/loyea/mcp/McpConfigStorageTest.kt`
- **逻辑**：
  - 编写了配置正常保存、读取，以及在 JSON 数据损坏时触发 `editor.remove()` 自愈的完整单元测试。

### 7. `McpRoutingTest.kt`
- **路径**：`app/src/test/java/com/loyea/mcp/McpRoutingTest.kt`
- **逻辑**：
  - 使用 Mockito 对 `McpClient` 状态、配置和工具数据进行模拟注入，测试了多服务器工具命名前缀拼装、前缀直达路由、以及非前缀 Fallback 兜底路由的正确性。

---

## 二、 修改文件 (Modified Files)

### 1. `app/build.gradle.kts`
- 引入 `implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")` 依赖，支持 SSE 监听。
- 引入 Mockito 单元测试依赖，支持不依赖真实设备的 JVM 纯净单元测试。

### 2. `ChatViewModel.kt`
- 引入 `McpManager` 实例，暴露 `mcpStates` Flow 和 `mcpConfigList` Compose State。
- 在 `init {}` 初始化中调用 `mcpManager.start()` 并从 Storage 中读取配置；在 `onCleared()` 时安全释放并断开连接。

### 3. `MainActivity.kt`
- 从 `ChatViewModel` 中提取 `mcpConfigs` 和 `mcpStates`（通过 `collectAsState()` 挂载），无缝传递到 `SettingsScreen`。

### 4. `SettingsScreen.kt`
- 在 `SettingsSubPage` 二级页面枚举中新增 `MCP_CONFIG`。
- 在 `SettingsMainLayout` 中绘制了高颜值、带磨砂感和 Switch 提示的“MCP 赛博插件管理”入口。
- 新增 `McpConfigLayout` 整体面板，使用莫兰迪配色与圆角微光边框，添加了连接中状态下呼吸闪烁的 Amber 黄指示灯。
- Tools 列表在卡片展开时伴有高阻尼的 `expandVertically` / `shrinkVertically` 顺滑折叠与展开动画，并使用 `FlowRow` 自动换行排版。

### 5. `CHANGELOG.md`
- 新增了 Milestone 1 完整的变更历史描述，维持了闭环审计要求。
