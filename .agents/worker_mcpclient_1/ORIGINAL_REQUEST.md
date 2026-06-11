## 2026-06-11T05:19:01Z
你是一个代码开发与重构代理（Worker）。你的工作目录是 D:\CodingProjects\Android\Loyea\.agents\worker_mcpclient_1。
你的任务是：针对 D:\CodingProjects\Android\Loyea\.agents\orchestrator\context.md 中列出的 Milestone 1 验证中的 9 个验证缺陷进行一次性完整修复和代码加固：

1. **监听协程内存泄漏**：在 `McpManager.kt` 中引入 `statusJobs: ConcurrentHashMap<String, Job>`，在注销或停用客户端时，不仅要调用 `disconnect()`，还要将监听其状态 Flow 的对应 Job `cancel()` 并从 map 移除。
2. **Jitter 计算不对称负偏置**：将指数退避中的 Jitter 抖动计算公式改为使用 `Random.nextLong(-jitterRange, jitterRange)` 或 Java ThreadLocalRandom，注意对于 `jitterRange <= 0` 时保护性返回 0L。
3. **客户端别名修改不生效**：修改 `updateConfigs` 逻辑，在对比时加入 `existingClient.config.name != config.name` 条件，如果名字不同也重建客户端以使前缀工具名即时更新。
4. **协程 CancellationException 被吞噬**：在 `McpClient.kt` 所有异常捕获的 `catch (e: Exception)` 块的第一行，增加 `if (e is CancellationException) throw e` 以恢复结构化并发特性。
5. **MainActivity 顶层全局重构隐患**：在 `MainActivity.kt` 顶层不要直接解包读取 `.value`，而是将 StateFlow 对象以 Collect 的方式或者是让 Compose 运行时感知状态，将重构限制在局部。
6. **并发 connect() 导致 Socket 泄漏**：在 `McpClient.kt` 中使用 `Mutex` 保护整个 `connect()` 过程，并且在启动新的 EventSource 之前确保释放旧 of `eventSource?.cancel()`，防止通道重复叠加。
7. **Gson 无法解析数字 ID 导致挂起崩溃**：修改 `JsonRpcResponse` 的 `id` 属性，支持数字或 String 的兼容（例如使用 `JsonElement?` 或 `Any?` 并自定义序列化/适配器，或者把 id 改为 Gson 的 JsonElement，在解析出后转为 String/数字匹配），确保收到标准 JSON-RPC 的数字 ID 响应时不发生崩溃和 15s 超时。
8. **SSRF 重定向风险与恶意 JSON 闪退防御**：
   - 检验 `messageEndpoint`。若是以 `http/https` 开头的绝对路径重定向，校验其 host 与 port 是否与原始配置的 `sseUrl` 相同，同源才允许网络交互，防止敏感信息流向第三方恶域（SSRF）。
   - 将 `handleMessage` 处的捕获范围扩大至 `catch (t: Throwable)` 以捕获 Error，且给输入 Payload 加设长度限制防 OOM。
9. **OkHttp 僵尸连接与同步 execute() 线程泄漏**：
   - 增加 `pingInterval(30, TimeUnit.SECONDS)` 规避僵尸长连接。
   - 在 `sendRequest` 中，将同步 `.execute()` 改造为使用异步 `.enqueue()`，并通过 `suspendCancellableCoroutine` 进行挂起封装。并在协程被取消时通过 `invokeOnCancellation` 取消 OkHttp Call，防止线程资源泄漏。

MANDATORY INTEGRITY WARNING：
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

开发完成后，请在你的工作目录下写入 changes.md 和 handoff.md 记录修改情况，并验证 `.\gradlew test` 是否全部编译通过。
请严格使用中文回复，严禁使用英文。
