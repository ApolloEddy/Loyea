# Context Memory

## 任务背景
为 Loyea (陪伴型 Android 赛博伴侣) 实现四个核心模块的生产级开发与测试闭环。

## 目标组件
- MCP 客户端 (JSON-RPC over HTTP/SSE)
- LLM 工具调用链路闭环 (整合已有的 `ThinkingAndMcpComponents.kt`)
- 物理感知模块 (获取真实/模拟定位，提供手表心率模拟器及 `WatchDataRepository` 硬件预留接口)
- 后台问候任务 (WorkManager / AlarmManager)

## 已有代码库分析
- `MainActivity.kt`：App 主入口。
- `SettingsScreen.kt`：设置页面。
- `ChatViewModel.kt`：包含聊天 ViewModel。
- `LlmClient.kt`：大语言模型 API 客户端。
- `PromptAssembler.kt`：System Prompt 拼接组装器。
- `ThinkingAndMcpComponents.kt`：包含 `McpCallItem` Compose 渲染组件。
- `Message.kt`：包含 `McpCall` 与 `Message` 数据定义。

## Milestone 1: MCP 客户端架构 Synthesis 成果
1. **JSON-RPC over SSE/HTTP 协议客户端**
   - 依赖：`okhttp3:okhttp-sse`。
   - 通信机制：OkHttp SSE 监听，POST 发送。
   - 异步转挂起：使用 `ConcurrentHashMap` 与 `CompletableDeferred` 配对。

## Milestone 1 验证缺陷 Synthesis (待修复列表 - 终期)
基于 gen2 验证组的评审与对抗性分析，总结出以下 6 项必须在 Milestone 1 DONE 之前完成修复和硬化的终期缺陷：
1. **ACCESS_NETWORK_STATE 权限缺失**：在 `app/src/main/AndroidManifest.xml` 中漏掉了 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`，这会导致 `McpManager.kt` 中的网络监听在 Android 11+ 上发生崩溃或不可达。
2. **SSRF 相对协议绕过漏洞**：利用协议相对 URL (如 `//attacker.com`) 可绕过同源 host/port 检验造成请求外泄。需在 `McpClient.kt` 校验 `messageEndpoint` 时，对 `//` 开头进行净化校验。
3. **CancellationException 状态竞态与 Socket 泄露**：在 `connect()` 抛出异常（包括协程取消）时没有执行 `handleDisconnect()` 状态清理，导致 `EventSource` 在后台保持运行。且 `connect` 最终状态可能会覆盖 `handleDisconnect` 的状态引起死锁。需使用 Lock 并补全异常状态重置。
4. **会话 JSON 前后台并发读写冲突与消息覆盖丢失**：
   - 并发写锁：后台 `GreetingWorker` 和前台 ViewModel 并发写入 JSON 会话缓存及 `sessions_metadata.json`。需要在 `ChatStorageManager.kt` 的读写方法上加 `Mutex` 锁保护。
   - 覆盖漏洞：在 ViewModel 保存消息前，应先从本地磁盘读取最新的消息列表与内存增量合并后再写入，防止后台 GreetingWorker 写入的消息直接被 ViewModel 内存旧消息完全覆盖抹除。
5. **UI 并发双发拦截**：双击发送会导致脏数据。需在 `ChatScreen.kt` 限制：当大模型正在思考或 MCP 正在运行（`isThinking` 或 `isMcpRunning` 为 true）时，禁用发送按钮和输入框的回车键。
6. **LlmClient 流式连接泄露**：在 `LlmClient.kt` 的 SSE 异常或协程取消时未在 finally 中调用 `Response.body?.close()`，导致网络连接不断泄漏。
