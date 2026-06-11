## 2026-06-11T05:11:00Z
你是一个开发实施代理（Worker）。你的工作目录是 D:\CodingProjects\Android\Loyea\.agents\worker_mcpclient_0。
你的任务是：根据 D:\CodingProjects\Android\Loyea\.agents\PROJECT.md 里的 Milestone 1 要求以及 D:\CodingProjects\Android\Loyea\.agents\orchestrator\context.md 中 Milestone 1 的设计，实现 MCP 客户端协议与多服务器管理。

请完成以下开发步骤：
1. 检查并添加 `okhttp3:okhttp-sse` 依赖至 `app/build.gradle.kts`。
2. 实现 `McpConfigStorage.kt` 进行 SharedPreferences 持久化和反序列化损坏自愈。
3. 实现 `McpClient.kt`，利用 OkHttp SSE 接收与 HTTP POST 发送进行双向 JSON-RPC over HTTP/SSE 的异步配对唤醒（基于 CompletableDeferred）。
4. 实现 `McpManager.kt` 进行多服务器连接管理，加入 Jitter 指数退避的自愈式重连机制，以及同名工具拼前缀路由防冲突逻辑。
5. 扩展 `SettingsScreen.kt` 路由与页面交互，绘制符合“Claude美学”的莫兰迪色磨砂配置面板，支持添加/修改/删除服务器、在线状态实时转换显示及 Tools 详情展开动画。
6. 扩展 `ChatViewModel.kt` 对 `McpManager` 进行初始化与生命周期整合。
7. 编写单元测试或仪器化测试来测试上述逻辑。使用 `.\gradlew assembleDebug` 验证编译。

MANDATORY INTEGRITY WARNING：
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

在开发完成后，请在你的工作目录下撰写 changes.md 详述你修改的文件与逻辑，并提交 handoff.md 提交测试结果与后续交接说明。
请严格使用中文回复，严禁使用英文。
