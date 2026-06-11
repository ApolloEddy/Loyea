# 5部式交接报告 (Handoff Report)

## 1. 观察 (Observation)
经过对 `worker_mcpclient_3` 修改加固后的代码进行深度审查，我们直接观察到以下几点：
1. **SSRF 大写协议校验绕过**：
   在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 第 124 行：
   ```kotlin
   val resolvedEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
       val parsedEndpoint = endpoint.toHttpUrlOrNull() ?: throw IOException("Invalid endpoint URL: $endpoint")
       if (parsedEndpoint.host != parsedSseUrl.host || parsedEndpoint.port != parsedSseUrl.port) {
           throw SecurityException("SSRF Detected: Redirect host/port (${parsedEndpoint.host}:${parsedEndpoint.port}) does not match sseUrl host/port (${parsedSseUrl.host}:${parsedSseUrl.port})")
       }
       endpoint
   } else {
       parsedSseUrl.resolve(endpoint)?.toString() ?: throw IOException("Failed to resolve endpoint: $endpoint")
   }
   ```
   代码中使用的 `startsWith` 为大小写敏感判断。同时，在 `else` 分支中直接执行了 `parsedSseUrl.resolve(endpoint)` 并将其转换为 String，而没有进行任何 Host 和 Port 校验。
2. **异步状态更新导致 UI 防重点击失效**：
   在 `app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt` 第 396 行 `sendMessage` 启动了异步 IO 写入协程，但 `isThinking.value = true` 仅在第 483 行的 `startAiResponseStream`（写盘结束并回调主线程后启动的另一个协程）的开头设置。在写盘 I/O 期间，主线程仍响应点击且 `isThinking` 为 `false`。
3. **前后台读写事务非原子化导致并发覆盖丢失**：
   在 `app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt` 第 770-791 行的 `mergeAndSaveMessages` 和 `GreetingWorker.kt` 第 125-132 行：
   ViewModel 与 Worker 在更新消息和会话时，均使用独立的 `load` 与 `save` 步骤。虽然 `ChatStorageManager` 的个别操作有 `Mutex` 锁，但整个“读-改-写”链路在业务层不具备事务原子性。尤其是 Worker 在加载会话列表到覆写会话列表之间，插入了耗时数秒的 LLM 流式对话。
4. **共享变量可见性隐患**：
   在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 中，`endpointDeferred`（由 OkHttp 线程和连接协程共享）与 `messageEndpoint`（由连接协程和 IO 发送协程共享）未添加 `@Volatile` 修饰。
5. **单元测试命令运行超时**：
   运行命令 `.\gradlew.bat testDebugUnitTest` 时，由于环境权限提示在无交互控制台下等待超时，输出为：
   `Permission prompt for action 'command' on target '.\gradlew.bat testDebugUnitTest' timed out waiting for user response. The user was not able to provide permission on time.`

## 2. 逻辑链 (Logic Chain)
1. **SSRF 绕过**：攻击者提供以大写 scheme 开头的绝对 URL（如 `HTTP://attacker.com/`）作为端点。由于 `startsWith` 大小写敏感，它不会进入 `if` 同源校验块，而是跳入 `else` 分支。因为 OkHttp 的 `resolve` 兼容大写并能将其正常解析为指向恶意服务器的绝对路径，且 `else` 分支完全没有 host/port 校验，该恶意地址最终作为 `messageEndpoint` 被客户端采纳，达成 SSRF 并外泄 API Key。
2. **UI 双击绕过**：发送按钮点击后启动 `mergeAndSaveMessages` 进行文件写入，这属于耗时 I/O。在写盘协程返回主线程前，`isThinking` 为 `false` 且按钮可点，导致高频点击直接绕过置灰拦截，产生并发多发消息与磁盘冲突。
3. **并发更新丢失**：由于“读-改-写”的非原子性，在 Worker 调用大模型数秒期间，如果用户通过 UI 新建/修改了会话列表，Worker 结束后持有的过期列表快照覆写磁盘，会直接抹去用户新建的会话；同理，写入消息时两端并发读取相同基底，ViewModel 的回写会直接覆盖抹去 Worker 刚刚写入的问候语。
4. **线程可见性**：非 volatile 变量在跨线程（EventSource 线程、协程调度线程）读写时，若无内存屏障，会导致读线程无法实时获取最新赋值，产生空指针或未连接异常。

## 3. 注意事项 (Caveats)
由于系统环境权限限制导致本地单元测试命令 `.\gradlew.bat testDebugUnitTest` 授权超时，无法获取实际的测试控制台输出。本报告的所有对抗验证结论与挑战点均基于代码逻辑的**静态推导与形式化校验**。

## 4. 结论 (Conclusion)
`worker_mcpclient_3` 的加固修复未能完全物理封堵上述高危缺陷。系统依然存在：
- **CRITICAL** 级的 SSRF 协议大小写绕过漏洞；
- **HIGH** 级的前后台并发覆盖与会话擦除数据丢失漏洞；
- **MEDIUM** 级的 UI 异步状态更新双击发送绕过缺陷。
整体风险评级为 **CRITICAL (危急)**。

## 5. 验证方法 (Verification Method)
1. **SSRF 验证**：
   - 编写测试用例，将 mock 服务端 `endpoint` 事件下发的 payload 设为 `"HTTP://evil-attacker.com/message"`。
   - 运行连接，观察 `McpClient` 是否抛出 `SecurityException`。在当前代码下，它将成功连接并解析为 `http://evil-attacker.com/message`，向其发送 `initialize` 请求。
2. **并发覆盖与会话擦除验证**：
   - 在 `GreetingWorker` 加载会话列表后（`doWork` 第 53 行），插入一个 `delay(5000)` 模拟大模型调用延迟。
   - 在此 5 秒内，在 UI 上点击“新建会话”。
   - 等待 Worker 运行完毕，检查会话列表，可发现刚才新建的会话被 Worker 覆写完全抹除。
3. **UI 快速双击验证**：
   - 将 `ChatStorageManager` 的消息写盘操作加上人工延迟以模拟低性能设备上的 I/O。
   - 快速双击发送按钮，观察是否会发送两条相同的消息。
