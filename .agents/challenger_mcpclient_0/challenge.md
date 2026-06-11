# 对抗性审查报告 (challenge.md)

## Challenge Summary

**总体风险评估**: HIGH (高)

本次审查对 Loyea 陪伴型 Android 赛博伴侣中 MCP 客户端模块 (`com.loyea.mcp`) 的并发安全性、错误容忍度以及潜在的重定向安全隐患进行了深度对抗性评估。尽管系统实现了基础的断线重连、指数退避和路由分发，但在高并发、恶劣网络环境和恶意服务端输入下，仍存在多个高危的对抗缺陷。

---

## Challenges

### [High] 对抗点 1: 并发 `connect()` 导致 EventSource (Socket) 泄漏与连接重叠
- **被挑战的假设**: 假设同一 MCP 客户端的连接生命周期是串行且互斥的。
- **攻击/失效场景**: 
  `McpClient.connect()` 属于 `suspend` 挂起函数，但其内部未加任何同步锁。且关键属性 `eventSource` 和 `endpointDeferred` 为类成员变量。
  若上层在短时间内并发调用两次 `connect()`（如并发网络回调与手动刷新并发触发）：
  1. 两协程均通过状态校验（初始均为 `DISCONNECTED`）。
  2. 协程 A 创建了 `endpointDeferred = CompletableDeferred()` 并启动 `eventSourceA`。
  3. 协程 B 紧接着执行，将 `endpointDeferred` 覆盖为 `CompletableDeferred()` 并启动 `eventSourceB` 覆盖 `eventSource`。
  4. `eventSourceA` 的网络连接已经建立且在后台线程正常接收 SSE 事件，但其引用已被覆盖丢失，导致 `eventSourceA` 永远无法被 cancel。
  5. 当调用 `disconnect()` 时，只能 cancel 当前引用的 `eventSourceB`，已被覆盖的 `eventSourceA` 将发生永久性的 Socket 和网络资源泄露。
- **波及范围**: 导致 App 的后台连接数持续攀升，消耗大量手机电量与移动网络流量，甚至被服务器因连接数超限而拉黑。
- **缓解措施**: 在 `McpClient` 的 `connect` 方法中使用 `Mutex` 互斥锁进行保护，或者在创建新的 EventSource 前强制调用 `handleDisconnect()` 清理历史连接。

### [Medium] 对抗点 2: `updateConfigs` 竞态条件导致重复启动重连协程
- **被挑战的假设**: 假设配置更新操作是顺序进行的，不会产生并发竞态。
- **攻击/失效场景**: 
  `McpManager.updateConfigs` 在对客户端连接池进行状态流转和启动 `startConnectionLoop` 时没有任何互斥保护。
  如果短时间内并发调用 `updateConfigs`（例如用户在配置页面快速连续点击保存，或在配置自动加载的同时网络回调触发了更新）：
  由于 `reconnectJobs` 的取消与重新赋值 `reconnectJobs[config.id] = ...` 不是原子操作，两个并发调用可能会同时看到 `reconnectJobs[id]` 暂不存在，从而为同一个服务器配置拉起两个相互独立的 `startConnectionLoop` 重连协程。
- **波及范围**: 同一个服务器配置会在后台有多个连接循环并发运行，导致频繁重复发起 `connect()` 请求。
- **缓解措施**: 在 `McpManager` 内部引入 `Mutex`，确保 `updateConfigs` 的流转逻辑完全串行化。

### [High] 对抗点 3: 恶意外界 JSON 输入导致未捕获 Error (如 OOM) 闪退
- **被挑战的假设**: 假设通过 `catch (e: Exception)` 可以捕获并处理所有网络反序列化异常。
- **攻击/失效场景**: 
  在 `handleMessage`（SSE 接收）和 `fetchTools`（工具解析）中，JSON 的解析均使用 `catch (e: Exception)` 拦截。
  然而，如果恶意的 MCP 服务端发送了体积异常庞大的恶意 Payload，或者设计了深层嵌套的 JSON，Gson 在反序列化时可能会抛出 `OutOfMemoryError`。因为 `OutOfMemoryError` 继承自 `Error` 而非 `Exception`，该崩溃将无法被 `catch (e: Exception)` 捕获，从而直接导致 Loyea 应用前台闪退崩溃。
- **波及范围**: 远程恶意 MCP 服务端可以通过发送特定报文实现对 Loyea 客户端的远程拒绝服务攻击（RDoS）。
- **缓解措施**: 将关键的反序列化逻辑处的异常捕获范围扩大为 `catch (t: Throwable)`，或对输入 Payload 的大小做硬性上限拦截。

### [Medium] 对抗点 4: 不安全的重定向导致敏感信息泄漏与中间人拦截风险 (SSRF)
- **被挑战的假设**: 假设所有由服务端返回的 `endpoint` 重定向地址都是安全合法的。
- **攻击/失效场景**: 
  `McpClient` 在连接握手时，若 SSE 接口返回的 `endpoint` 是以 `http://` 或 `https://` 开头的绝对路径，代码将不加校验地直接采用：
  ```kotlin
  messageEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) { endpoint }
  ```
  如果用户添加了一个恶意的恶意 MCP 服务器，或者正常服务器被劫持，其可以通过在 SSE `endpoint` 事件中返回一个指向第三方窃听站点的绝对 URL（例如 `https://attacker.com/api/mcp`）。
  Loyea 客户端将毫无知觉地将后续所有的 JSON-RPC 请求（包含工具调用名称、敏感参数、上下文甚至手表的物理心率/定位信息）全部 POST 发送给该窃听站点。
- **波及范围**: 用户敏感的物理上下文（心率、GPS 坐标）、LLM 交互上下文等严重隐私数据被静默窃取。
- **缓解措施**: 对重定向的 `endpoint` 进行域名与同源校验。如果重定向 URL 的 Host 与原始 SSE URL 的 Host 不一致，应当拒绝连接或弹出安全警告。

### [Medium] 对抗点 5: `Request.Builder().url(endpoint)` 抛出 RuntimeException 导致请求 ID 永久泄漏
- **被挑战的假设**: 假设 `messageEndpoint` URL 永远不会触发 okhttp 的 URL 校验失败。
- **攻击/失效场景**: 
  在 `McpClient.sendRequest` 中，`Request.Builder().url(endpoint)` 的执行位于 `try-catch` 块外部：
  ```kotlin
  val requestId = UUID.randomUUID().toString()
  val deferred = CompletableDeferred<JsonRpcResponse>()
  pendingRequests[requestId] = deferred
  val jsonRequest = gson.toJson(JsonRpcRequest(id = requestId, method = method, params = params))
  val body = jsonRequest.toRequestBody(JSON_MEDIA_TYPE)
  val httpRequest = Request.Builder()
      .url(endpoint) // 若 endpoint 格式非法，此处抛出 IllegalArgumentException
      .post(body)
      .build()
  try {
      ...
  ```
  若在连接已建立但在发送请求前，`messageEndpoint` 包含不合规字符（例如因服务端输入污染含有未编码的空格等），`url(endpoint)` 会抛出 `IllegalArgumentException`。
  由于该行代码不在 `try` 块内，异常直接向外抛出，`pendingRequests.remove(requestId)` 永远无法被执行，导致该 `requestId` 对应的 `deferred` 永久留存在内存中，造成内存泄漏。
- **波及范围**: 导致内存中积压大量悬挂的 `CompletableDeferred`，造成内存泄漏。
- **缓解措施**: 将 `Request.Builder().url(endpoint)` 移入 `try` 块内，确保任何抛出的异常都能触发 `pendingRequests.remove(requestId)` 清理逻辑。

### [Low] 对抗点 6: OkHttp 阻塞式 `execute()` 在协程取消时导致线程泄漏
- **被挑战的假设**: 假设在协程挂起和超时时，底层的阻塞式 I/O 会自动释放。
- **攻击/失效场景**: 
  `McpClient.sendRequest` 底层通过 `okhttpClient.newCall(httpRequest).execute()` 发起同步请求。当该协程被取消（如 `withTimeout(15000)` 超时触发）时，协程库会标记其为 Cancelled，但 OkHttp 底层的同步 Socket 读写并不会被中断。底层的 `Call` 仍会继续占用 `Dispatchers.IO` 中的线程，直到其自身的网络超时发生。
- **波及范围**: 高并发请求超时时，短时间内会迅速占满 `Dispatchers.IO` 线程池，导致其他挂起任务排队挂起，系统响应迟钝。
- **缓解措施**: 使用异步 `enqueue` 或在协程取消时监听取消信号（通过 `invokeOnCancellation`）主动调用 `call.cancel()` 释放底层连接。

---

## Stress Test Results

由于本地执行环境的安全策略限制（`.\gradlew test` 运行指令因授权弹窗超时未获执行），我们通过静态代码审计和模拟时序分析完成了如下对抗测试推演：

- **场景 1: 模拟服务器注入非法 JSON-RPC 结果 (例如 result 字段为数组而非对象)**
  - 预期行为: `fetchTools` 应当友好报错，防止应用崩溃。
  - 实际行为: `result.asJsonObject` 抛出 `IllegalStateException`，虽然被 `connect()` 的 catch 拦截，但未能提供针对性的容错降级，且导致连接失败。 (Pass - 已拦截但无细分容错)

- **场景 2: 模拟恶意 Payload (触发 OOM)**
  - 预期行为: 系统捕获异常/错误，保持运行。
  - 实际行为: 抛出 `OutOfMemoryError`，由于只捕获 `Exception`，错误直接上抛至 JVM 导致 Loyea 进程闪退。 (Fail)

- **场景 3: 并发高频点击保存配置 (触发 updateConfigs 并发)**
  - 预期行为: 重连任务平滑交替，只保留最后一个生效，且连接数不增长。
  - 实际行为: 双重 Job 并行运行，各自创建 EventSource 造成 Socket 连接泄露。 (Fail)

---

## Unchallenged Areas

- **WatchDataRepository 和定位感知模块 (Milestone 3)**:
  - 暂未挑战原因: 属于 Milestone 3 的范畴，当前阶段重点校验 Milestone 1 的 MCPClient 基础通信及管理。

- **WorkManager 定时问候逻辑 (Milestone 4)**:
  - 暂未挑战原因: 后台主动关怀逻辑目前在 Milestone 1 代码中仅完成结构预留，待后续阶段实现后再行对抗测试。
