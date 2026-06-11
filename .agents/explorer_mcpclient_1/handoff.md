# Handoff Report - MCP 客户端协议与多服务器管理设计

## 1. Observation (直接观察)

通过对 Loyea 项目的现有代码与项目要求的深入探测，观察到以下事实：
- **项目结构与代码空缺**：
  - `PROJECT.md` 声明 MCP 客户端与管理模块的代码布局在 `app/src/main/java/com/loyea/mcp/` 目录下（`PROJECT.md` 第 67 行）。
  - 使用文件检索工具确认，目前在该包下完全没有任何代码文件，这是一项从零开始实现的全新功能。
- **设置页现有架构**：
  - `SettingsScreen.kt` 包含当前设置页面的界面入口与滑动切换逻辑。
  - 设置页第 89 行定义了二级页面路由状态：
    ```kotlin
    var subPage by remember { mutableStateOf(SettingsSubPage.MAIN) }
    ```
  - 设置页第 105-143 行利用 `AnimatedContent` 渲染子页面：
    ```kotlin
    when (currentPage) {
        SettingsSubPage.MAIN -> { SettingsMainLayout(...) }
        SettingsSubPage.API_CONFIG -> { ApiConfigLayout(...) }
        SettingsSubPage.THEME_SETTINGS -> { ThemeSettingsLayout(...) }
    }
    ```
- **测试框架与覆盖点要求**：
  - `TEST_INFRA.md` 和 `explorer_testinfra_0/analysis.md` 提出了 E2E 测试用例，其中针对 R1 (MCP 客户端协议与多服务器管理) 有 explicit 要求：
    - `R1-T1-01` 至 `R1-T1-05` 分别覆盖了：添加 MCP 客户端配置、显示 Tools 列表、编辑配置、在线/断开实时流转状态、删除配置。
    - `R1-T2-01`：验证输入 URL 的非法格式。
    - `R1-T2-03`：两个服务器存在相同名字工具的路由隔离处理。
    - `R1-T2-05`：SharedPreferences 持久化文件损坏后的防崩自愈读取。
    - `T3-03`：断网后指数级自动重连，以及服务器恢复后的自愈连接。

---

## 2. Logic Chain (推理链条)

- **由 Stdio 到 SSE/HTTP 的选择**：
  - Android 系统的进程权限管控使得运行本地 Stdio 子进程极为受限且不稳定，要满足 Milestone 1 要求的“标准 JSON-RPC 客户端”，必须设计基于 **JSON-RPC over HTTP/SSE** 的协议客户端。
- **由 SSE 单向流到协程双向挂起**：
  - MCP 的 SSE 通信是一种单向通知流。客户端通过 HTTP POST 向服务器发送 JSON-RPC 请求，服务器通过 SSE 消息流推送 JSON-RPC 响应。
  - 为实现 Kotlin 中 `suspend fun callTool` 的阻塞式调用，必须在 `McpServerClient` 中设计一个并发请求配对映射 `ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>`，通过请求的唯一自增/UUID `id` 将 HTTP POST 请求与接收到的 SSE 事件进行配对唤醒。
- **由工具同名冲突到命名空间路由**：
  - 多个 MCP 服务器完全有可能暴露同名的 Tool（如都提供 `get_weather`），直接扁平注入 LLM 会导致模型无法抉择，或调用时路由错误。
  - 为确保逻辑隔离，必须在 `McpManager` 统一导出工具池时，对重名或所有工具重构为 `[ServerId]__[ToolName]` 命名空间，在拦截大模型 Tool Call 后拆分路由到特定的 `McpServerClient` 中。
- **持久化防崩设计**：
  - 用户的 SharedPreferences 可能会在异常关机、系统损坏或非法修改时破损。
  - 为防止读取脏 JSON 字符串引发 `JsonSyntaxException` 闪退，`McpConfigStorage.loadConfigs()` 中必须采用 try-catch 拦截，且在异常发生时执行 `prefs.edit().remove(key).apply()` 强制清空自愈。

---

## 3. Caveats (局限与假设)

- **关于 StdIO 的不支持假设**：目前本设计排除了 StdIO 管道连接 MCP 的方案，专注于网络 SSE/HTTP 方式。
- **大模型响应的单向假设**：本设计基于大模型总是调用 `tools` 进行交互。若大模型没有传入特定的参数，或者参数格式错误，由客户端的 `callTool` 抛出特定 JSON-RPC 格式错误（-32602 无效参数）返回给大模型，确保链路容错。

---

## 4. Conclusion (结论与修改方案概要)

Milestone 1 的 MCP 客户端和设置页面开发应分三步实现：
1. **网络与协议层**：新建 `com.loyea.mcp.client.McpServerClient` 及配对容器，用 OkHttp-SSE 监听推送并解析 `event: endpoint` 绑定 POST 路径。
2. **状态机与自愈重连**：使用 `StateFlow` 管理 `CONNECTED/CONNECTING/DISCONNECTED`。利用协程实现指数退避 (Exponential Backoff) 和 Jitter (随机抖动) 的重试处理器。
3. **UI 磨砂质感卡片与动画**：在 `SettingsScreen.kt` 中引入 `SettingsSubPage.MCP_CONFIG`。设计基于 `AnimatedVisibility(expandVertically() + fadeIn())` 的 Tools 折叠卡片，外观使用低饱和度莫兰迪色，交互使用土红色的水波纹效果（`indication`）。

---

## 5. Verification Method (独立验证方法)

1. **本地单元测试验证**：
   - 编写 `McpConfigStorageTest` 注入损坏的 JSON 数据，验证 `loadConfigs()` 会自动清除并返回空列表，无崩溃。
   - 编写 `McpServerClientTest`，使用 `MockWebServer` 模拟 SSE 数据流响应，发送 `tools/list` 与 `tools/call` 并阻塞等待，验证双向响应配对成功。
2. **端到端 (E2E) 仪器化测试验证**：
   - 在真机/模拟器上执行：
     ```powershell
     .\gradlew :app:connectedDebugAndroidTest
     ```
   - 验证配置面板中，当 Mock MCP 服务端断开时，UI 实时变为“已断开”；服务重新开启后，状态自愈回转为“已连接”。

---

## 6. Remaining Work (后续具体实施步骤)

若需要开始实施上述设计，请按照以下步骤开发：
1. 在 `com.loyea.mcp` 包中新建配置类、SharedPreferences 序列化读写类 `McpConfigStorage`。
2. 引入 `okhttp3:okhttp-sse` 依赖，编写 `McpServerClient`，设计配对逻辑与 `EventSourceListener`。
3. 编写 `McpManager` 聚合多客户端连接，并对 Tools 名称进行前缀拼装以防冲突。
4. 扩展 `SettingsScreen.kt` 中的 `SettingsSubPage` 枚举，加入 `MCP_CONFIG` 并新建 `McpConfigLayout` 与 `McpServerCard`，引入莫兰迪色彩。
