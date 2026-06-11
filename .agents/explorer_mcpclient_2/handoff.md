# Handoff Report — explorer_mcpclient_2 交付报告

本报告为 **Milestone 1: MCPClient** 的分析与设计交付报告，由 `explorer_mcpclient_2`（只读探索代理）生成，用于指导 Implementer 代理进行具体的代码实现。

---

## 1. Observation (观察到的事实)
- **O1: 项目接口规约与包结构**：
  在 `D:\CodingProjects\Android\Loyea\.agents\PROJECT.md` 中定义了 Milestone 1 的范围为：*“实现 JSON-RPC over HTTP/SSE 客户端，多服务器管理配置与高颜值 Claude 风格 UI 面板”*，其包名约定为 `com.loyea.mcp`（用于多服务器客户端与配置存储）。
- **O2: 代码目录现状**：
  通过 `list_dir` 检查 `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea` 发现，目前仅有 `MainActivity.kt` 与 `ui` 目录，暂不存在 `mcp` 目录及其相关的 Kotlin 源文件。
- **O3: 设置页面 `SettingsScreen.kt` 现状**：
  通过 `view_file` 检查 `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\ui\settings\SettingsScreen.kt`（第 52-55 行），当前页面路由分发仅支持三个二级页面（`MAIN`, `API_CONFIG`, `THEME_SETTINGS`），没有任何关于 MCP 服务器或相关配置的逻辑和 UI 卡片。
- **O4: 全局状态与持久化现状**：
  通过 `view_file` 检查 `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\ui\chat\ChatViewModel.kt`（第 18-20 行），ViewModel 继承自 `AndroidViewModel`，通过 `loyea_prefs` 作为全局 SharedPreferences 存储（比如在第 99 行读取 `api_config_list`）。当前尚无 `McpManager` 或 `McpConfigStorage` 相关的声明与初始化调用。

---

## 2. Logic Chain (推导逻辑)
- **L1 (协议实现)**：由于 **O1** 规定了客户端需实现标准 JSON-RPC over HTTP/SSE 协议，结合 **O2** 目前无相关代码的现状，我们必须在 `com.loyea.mcp` 下新建 `McpClient.kt`，基于 OkHttp 的 `EventSourceListener` 监听服务端的 SSE 下行隧道，并使用普通的 HTTP POST 异步发送 JSON-RPC 消息。为了在协程中完美配合挂起，我们需要利用 `CompletableDeferred` 将异步接收的 SSE 消息转为挂起函数的返回值。
- **L2 (存储设计)**：根据 **O4**，已有的 API 配置采用了 JSON 序列化并存入 `loyea_prefs` 的形式。为保持代码一致性，我们应当在 `com.loyea.mcp` 下新建 `McpConfigStorage.kt`，利用 `Gson` 序列化 `List<McpServerConfig>` 并将其持久化存入 `loyea_prefs` 中，键名约定为 `mcp_servers_config`。
- **L3 (重连与状态流转)**：为防止网络抖动导致的客户端失效，根据 **O1** 对并发服务器连接与状态流转的要求，我们需要在 `McpManager.kt` 中设计以 `clientPool` 存储所有 `McpClient` 的方案，并在 `client.status` 收到 `DISCONNECTED` 时自动启动指数退避策略（在 2s 到 60s 之间翻倍延时）进行重新连接，当配置切换为停用或主动调用 `disconnect()` 时终止重连。
- **L4 (UI 界面嵌入)**：根据 **O3**，现有的 `SettingsScreen` 并没有 MCP 配置的入口。我们应当在 `SettingsSubPage` 中加入 `MCP_CONFIG` 分支，并在 `SettingsScreen.kt` 中引入符合“Claude美学”的面板设计，包含磨砂边框卡片、基于 `AnimatedVisibility` 的工具详情折叠动画、淡雅莫兰迪色系（草绿指示灯、灰褐文本）的状态指示器，并带有呼吸灯/旋转脉冲等物理动效，使用莫兰迪色水波纹作为触控反馈。

---

## 3. Caveats (注意事项与假设)
- Android 模拟器访问本地主机的 localhost/127.0.0.1 会产生回路错误，必须假设并使用 `10.0.2.2` 作为宿主机 IP 的默认映射配置（已在内置配置中规划）。
- SSE 连接在 Android 后台可能因系统省电策略或 Doze 模式被挂起，本设计阶段仅关注 App 在前台活动及正常运行时的连接生命周期，后续若需后台保活，需配合 Foreground Service。
- 由于是只读探索，尚未测试高并发调用（例如同时调用多个服务器的 tool-calls）对协程调度的影响，需要在具体编码时注意 `Mutex` 锁与并发安全性。

---

## 4. Conclusion (设计结论)
- 我们提供了一套在 Android/Kotlin 中实现标准 MCP SSE 客户端的完整方案。该方案使用 OkHttp 监听 SSE 长连接，利用异步转挂起机制支持 `tools/list` 与 `tools/call` 的同步编写；
- 方案使用 `McpManager` 统一管理多客户端生命周期，提供了基于指数退避的自动重连策略；
- 在 `SettingsScreen.kt` 中嵌入符合“Claude美学”（磨砂边框、莫兰迪色调、高阻尼弹性动效）的 MCP 管理界面，保障高水准的 UI/UX。

---

## 5. Verification Method (独立验证方法)
- **协议测试**：使用本地运行的 Node.js 示例 MCP 服务器，检查 Android 客户端连接时的抓包或 Logcat，验证是否正确接收到了包含 `event: endpoint` 消息，且 POST 初始化后能否在 SSE 中成功收到响应，并正常流转到 `CONNECTED` 状态。
- **重试测试**：当连接建立后，手动杀掉本地 MCP 服务端进程，验证客户端是否能在 Logcat 中观测到按照 2s, 4s, 8s, 16s 递增的自动重连尝试；并在重新启动服务端后，是否能自动握手恢复。
- **UI 质感测试**：在 UI 界面反复折叠和展开服务器卡片、点击测试连接，验证波纹效果（`#EADFD3`）与状态指示器脉动缩放动效是否存在卡顿。

---

## 6. Remaining Work (残留及后续工作)
- **W1 (代码实现)**：在 `app/src/main/java/com/loyea/` 下创建 `mcp` 文件夹，并实现 `McpClient.kt`, `McpConfigStorage.kt`, `McpManager.kt`。
- **W2 (ViewModel 扩展)**：修改 `ChatViewModel.kt`，声明 `McpManager` 实例，将 MCP 状态及可用工具合并暴露为 `StateFlow` 供 UI 和 LLM 通信层订阅。
- **W3 (Settings UI 实现)**：扩展 `SettingsScreen.kt` 的路由，新增 `McpConfigLayout` 及其子卡片、添加/编辑 BottomSheet，并调试物理视觉效果。
- **W4 (单元测试与集成测试)**：为 `McpClient` 和 `McpManager` 编写相关的 mock 测试，验证状态流转。
