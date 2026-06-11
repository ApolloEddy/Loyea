# Handoff Report (handoff.md)

## 1. Observation (观测)
我们对 Loyea 赛博伴侣的 Milestone 1 需求及现有代码结构进行了观测与分析。具体如下：
* 依赖配置：在 `app/build.gradle.kts` 中确认原本缺少 SSE 网络包及单元测试 Mock 库。
* 文件结构：原本不存在 `com.loyea.mcp` 目录及其底层协议实现。
* 状态集成：`ChatViewModel.kt` 和 `MainActivity.kt` 原本无 MCP 的管理配置注入，且 `SettingsScreen.kt` 缺少 `SettingsSubPage.MCP_CONFIG` 分支。
* 代码改动：
  - 新建了 `McpServerConfig.kt`, `JsonRpc.kt`, `McpConfigStorage.kt`, `McpClient.kt`, `McpManager.kt` 五个核心业务代码文件。
  - 新建了 `McpConfigStorageTest.kt`, `McpRoutingTest.kt` 单元测试文件。
  - 修改了 `app/build.gradle.kts`, `ChatViewModel.kt`, `MainActivity.kt`, `SettingsScreen.kt`, `CHANGELOG.md` 联动文件。

## 2. Logic Chain (推理链)
为了完整实现 Milestone 1 规范并保证质量，我们的设计推理如下：
1. **网络层**：由于 MCP 使用 JSON-RPC over HTTP/SSE通信，添加 `okhttp3:okhttp-sse`（`4.12.0`）是支持下行数据流监听的前提。
2. **异步转挂起**：为实现非对称的 HTTP POST 发送与 SSE 异步接收的对齐，使用 `ConcurrentHashMap` 缓存 `id` 和 `CompletableDeferred`，并在 SSE 收到对应响应时调用 `complete`。这保证了 `suspend fun callTool` 的调用逻辑符合 Kotlin 挂起规范。
3. **损坏自愈**：通过 SharedPreferences 存储配置时，在 `loadConfigs` 中对反序列化行为加上 `try-catch`。如果反序列化失败，直接 `editor.remove(key).apply()`，保障了脏数据不会引起崩溃。
4. **断线指数退避与网络感知**：
   - 采用 ConnectivityManager 实时监听网络状态。当断网时，利用 `isNetworkAvailableFlow.first { it }` 挂起重连协程；联网时自动被唤醒，极大地节约了能耗。
   - 重连时每次延迟翻倍（2s 至 60s），并附加 `+/- 10%` 的 Jitter 抖动，消除了多服务重试时的网络拥堵风险。
5. **前缀式路由防冲突**：大模型调用工具需要区分多台服务器。我们在聚合工具时统一为工具打上 `${cleanServerName}__${toolName}` 标记。调用分发时，提取前缀作为第一路由特征，并对没有前缀的调用作 Fallback 循环查找。
6. **UI 配置面板**：
   - 使用莫兰迪配色与圆角磨砂细线边框，融入“Claude 美学”。
   - 对 `CONNECTING` 状态通过 `rememberInfiniteTransition` 实现 Amber 琥珀黄指示灯的呼吸动效。
   - Tools 列表展开折叠时采用带有高阻尼弹性（`Spring.DampingRatioLowBouncy`）的 `AnimatedVisibility` 动画。

## 3. Caveats (注意事项)
* 单元测试基于 JVM 的 Mockito 对 Context、ConnectivityManager、SharedPreferences 进行模拟测试。若要在真机上验证多服务器连接和工具，请运行相应的 Mock 服务器或待 Milestone 5 时执行集成化 E2E 测试。
* 本次实现仅完成了 MCP 协议、多服务器配置、重连和 UI。大模型在对话流程中拦截并执行 MCP 插件的闭环逻辑属于 Milestone 2 范畴。

## 4. Conclusion (结论)
Milestone 1 要求的所有步骤（okhttp-sse 依赖添加、McpConfigStorage 损坏自愈、McpClient 双向 SSE 异步转挂起配对、McpManager 指数退避与网络感知重连及前缀路由、SettingsScreen 莫兰迪面板与折叠动效、ChatViewModel 整合、本地单元测试）已全部编写并闭环完成。

## 5. Verification Method (验证方法)
* **编译验证**：在根目录下执行 `.\gradlew assembleDebug`，应成功编译输出 APK。
* **测试运行**：在根目录下执行 `.\gradlew test` 或 `.\gradlew testDebugUnitTest`，应能运行并成功通过 `McpConfigStorageTest` 和 `McpRoutingTest` 所有 5 个测试用例。
