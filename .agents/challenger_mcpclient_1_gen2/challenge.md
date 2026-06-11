# 对抗挑战评估报告

## 挑战总结

**整体风险评估**: CRITICAL (严重)

## 对抗挑战点

### [严重] 挑战 1：绕过 SSRF 防御的协议相对重定向漏洞
- **挑战的假设**: 假设通过检查 `endpoint.startsWith("http://")` 和 `https://` 并对比 host/port 就能防止 SSRF 重定向攻击。
- **攻击场景**: 恶意 MCP SSE 服务器在 `endpoint` 事件中返回协议相对 URL `//attacker.com/message`。由于不以 `http://` 或 `https://` 开头，此路径会进入 `else` 分支并调用 `parsedSseUrl.resolve("//attacker.com/message")`。根据 OkHttp 的解析规则，这会被解析为 `http://attacker.com/message`，绕过主机一致性检查。之后客户端的敏感 JSON-RPC 请求（包含工具调用与参数）将被发送至恶意服务器。
- **波及范围**: 导致敏感数据（包括工具参数、用户对话上下文、可能的 API 凭证等）外泄，实现任意 SSRF 攻击。
- **缓解建议**: 在 `else` 分支解析完相对路径后，必须对解析得到的 `resolvedEndpoint` 的 `host` 和 `port` 再次进行检查，确保其与 `parsedSseUrl` 严格一致。

### [高危] 挑战 2：连接循环取消/超时导致的 Socket 及连接泄露漏洞
- **挑战的假设**: 假设在协程取消或超时时，`connectMutex.withLock` 抛出的 `CancellationException` 会被上层连接循环妥善清理。
- **攻击场景**: 在配置更新、超时触发或重连被取消时，`connect()` 方法内的协程抛出 `CancellationException`。由于在 `catch (e: Exception)` 中该异常被直接重新抛出 (`if (e is CancellationException) throw e`) 且未执行 `handleDisconnect()`，导致当前持有的 `eventSource` 未调用 `.cancel()`。
- **波及范围**: 造成后台 OkHttp SSE 网络连接持续存活和 Socket 泄露。同时，客户端状态被永久锁定在 `CONNECTING`，无法再次重连，直到应用进程被杀掉。
- **缓解建议**: 在 `connect()` 的 `try-catch` 块中加入 `finally`，或者在捕获 `CancellationException` 抛出前，显式调用 `handleDisconnect()` 以确保 EventSource 被取消。

### [中危] 挑战 3：handleDisconnect() 缺乏线程同步及可见性导致的竞态条件
- **挑战的假设**: 假设连接状态更新和断开操作在单线程中顺序执行。
- **攻击场景**: `handleDisconnect()` 涉及修改 `eventSource`、`messageEndpoint`、`endpointDeferred` 等共享非 volatile 状态。该方法会被 OkHttp 的 `EventSourceListener` 在后台网络线程中回调（例如 `onFailure` 或 `onClosed`）。在并发重连时，如果一个先前旧连接的失败回调在新的连接尝试中被调用，会无锁清理新连接的生命周期变量，引发状态错乱。
- **波及范围**: 重连机制在网络波动剧烈时频发死锁或连接被异常重置，导致稳定性极差。
- **缓解建议**: 对状态修改逻辑（特别是生命周期控制变量）声明为 `@Volatile`，并将 `handleDisconnect` 放在 `connectMutex` 或独立的同步锁中执行，且必须校验回调源是否为当前的 EventSource 实例。

### [高危] 挑战 4：发送按钮未防抖/未限制并发导致的内存与文件损坏风险
- **挑战的假设**: 假设用户不会在 AI 回答中或极短时间内连续点击发送按钮。
- **攻击场景**: 输入框发送按钮 `.clickable` 仅检查了输入是否为空，未在 `isThinking` 期间禁用。用户在 AI 思考或输出时双击发送，会在 `viewModelScope` 中启动两个并发的 `startAiResponseStream` / `runToolCallLoop` 协程。两个协程同时操作同一个 Compose 状态 `messages.value` 并向同一个 JSON 消息文件 `session_$sessionId.json` 并发写入数据。
- **波及范围**: 导致 Compose UI 消息流内容交叉错乱，且由于 `ChatStorageManager` 无锁无同步机制，并发文件写入会导致 JSON 数据发生损坏。损坏的 JSON 文件在下次加载时会导致整个会话的历史记录丢失。
- **缓解建议**: 在 `ChatInputBar` 或 ViewModel 的 `sendMessage` 中做防抖处理，如果 `isThinking` 状态为 true，则禁用发送按钮或丢弃重复请求。

### [高危] 挑战 5：后台 GreetingWorker 与前台 ViewModel 无同步并发写入冲突
- **挑战的假设**: 假设后台 WorkManager 任务与前台 UI 不会同时读写同一个消息文件。
- **攻击场景**: 当后台问候任务 `GreetingWorker` 被唤醒时，它会读取、追加并保存消息历史到 `session_$sessionId.json`。此时如果用户也处于该会话并发送消息，前台 ViewModel 同样会读写该文件。由于 `ChatStorageManager` 的读写没有任何锁（如 `Mutex` 或文件排他锁），后台与前台并发写入会导致内容被覆盖或 JSON 文件写坏。
- **波及范围**: 导致问候消息丢失、用户发送的消息丢失或本地聊天记录 JSON 文件彻底损坏。
- **缓解建议**: 对 `ChatStorageManager` 的文件读写操作引入进程内/协程级 `Mutex` 锁，确保同一时间对同一会话文件的读写是互斥的。

### [高危] 挑战 6：sendChatCompletionStream 中异常/取消未关闭 Response 导致的 HTTP 连接泄露
- **挑战的假设**: 假设大模型流式输出必然完整且不会在中途取消。
- **攻击场景**: `sendChatCompletionStream` 中执行同步网络请求 `client.newCall(request).execute()` 获取响应。在循环读取 SSE 数据行或进行 Gson 解析时，若发生任何异常（如超时或 JSON 格式错误），会跳转至 `catch (e: Exception)` 块。同样，如果用户退出聊天导致 Flow 收集被取消，也会抛出协程取消异常。在这些异常路径中均没有调用 `response.close()`。
- **波及范围**: 导致 OkHttp `Response` 对象和底层 Socket 连接泄露。多次泄漏后，OkHttp 连接池达到上限，使得应用后续所有大模型和 MCP 请求被无限期挂起。
- **缓解建议**: 必须在获取 `response` 后将其包裹在 `response.use { ... }` 块中，或在 `finally` 块中确保 `response.close()` 得到执行。

## 对抗测试结果预测

*   **场景 1：恶意 MCP 服务端返回 `//attacker.com/message` 作为 endpoint**
    *   预期行为：客户端识别为非法地址或拦截重定向。
    *   实际/预测行为：进入相对路径解析分支，解析为绝对 URL 并向 `attacker.com` 发送 POST 报文。
    *   结果：**不通过 (FAIL)**
*   **场景 2：连接 SSE 时，在 10 秒超时前取消重连任务**
    *   预期行为：连接优雅断开，状态恢复为 DISCONNECTED，下次重连正常。
    *   实际/预测行为：协程被取消并向外抛出 `CancellationException`，但未调用 `handleDisconnect`。SSE 依旧在后台存活，状态卡死在 CONNECTING，下一次重连失效。
    *   结果：**不通过 (FAIL)**
*   **场景 3：前台正在生成 AI 回答时，快速多次点击发送**
    *   预期行为：发送按钮置灰，后续点击无效，保证同一会话仅有单条流式响应。
    *   实际/预测行为：发送按钮可点，UI 插入重复 of AI 回复块，消息流发生混乱，本地 JSON 文件被并发写入损坏。
    *   结果：**不通过 (FAIL)**
*   **场景 4：后台 `GreetingWorker` 执行写入时，用户同时在聊天中输入并发送**
    *   预期行为：前后台互斥排队写入，数据不丢失、文件不损坏。
    *   实际/预测行为：发生并发读写冲突，一个写操作覆盖另一个，或者写冲突导致 JSON 结构破坏。
    *   结果：**不通过 (FAIL)**

## 未挑战的区域

*   手表心率模拟算法和 GPS 定位权限处理逻辑 — 本次静态对抗主要集中于高并发通道（SSE、McpClient、WorkManager、ViewModel 状态流）和数据存储一致性，对于传感器等物理模块的具体底层操作系统权限（如 GPS 动态申请流程）未做深度时序逆向，因其对高并发漏洞和网络安全维度的影响相对较小。
