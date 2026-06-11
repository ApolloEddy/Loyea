# Handoff Report (handoff.md)

## 1. Observation (观测)

通过对项目核心文件 `McpClient.kt` 和 `McpManager.kt` 的静态代码和架构设计进行观测，发现以下具体代码行存在对抗性缺陷：

### A. 并发 `connect()` 导致 Socket 泄漏与 EventSource 重叠
在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 的第 41-47 行和第 85 行：
```kotlin
41:     suspend fun connect(): Boolean {
42:         if (_status.value != McpServerStatus.DISCONNECTED) {
43:             return _status.value == McpServerStatus.CONNECTED
44:         }
...
85:         eventSource = factory.newEventSource(request, listener)
```
类成员变量 `eventSource` 和 `endpointDeferred` 被并发调用直接覆写。

### B. `updateConfigs` 竞态条件导致重复重连协程
在 `app/src/main/java/com/loyea/mcp/McpManager.kt` 的第 124-144 行：
```kotlin
124:         for (config in newConfigs) {
125:             if (config.isEnabled) {
126:                 val existingClient = activeClients[config.id]
127:                 if (existingClient == null || existingClient.config.sseUrl != config.sseUrl) {
...
131:                     val newClient = McpClient(config, okhttpClient)
132:                     activeClients[config.id] = newClient
...
141:                     startConnectionLoop(config)
```
以及 `startConnectionLoop` 中的异步取消：
```kotlin
150:         reconnectJobs[config.id]?.cancel()
151:         reconnectJobs[config.id] = coroutineScope.launch {
```

### C. 恶意外界 JSON 输入导致未捕获 Error (如 OOM) 闪退
在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 的第 154-164 行：
```kotlin
154:     private fun handleMessage(data: String) {
155:         try {
156:             val response = gson.fromJson(data, JsonRpcResponse::class.java)
...
160:             }
161:         } catch (e: Exception) {
162:             Log.e(TAG, "Failed to parse message: $data", e)
163:         }
164:     }
```
此处仅捕获了 `Exception`，未捕获 `OutOfMemoryError`。

### D. 不安全的重定向导致数据泄漏风险 (SSRF)
在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 的第 93-101 行：
```kotlin
95:             messageEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
96:                 endpoint
97:             } else if (parsedSseUrl != null) {
98:                 parsedSseUrl.resolve(endpoint)?.toString() ?: throw IOException("Failed to resolve endpoint: $endpoint")
99:             } else {
100:                 throw IOException("Invalid SSE URL: ${config.sseUrl}")
101:             }
```
此处未对绝对路径重定向的目标 URL 进行任何同源或域名合法性校验。

### E. `Request.Builder().url(endpoint)` 异常导致请求 ID 永久泄漏
在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 的第 166-179 行：
```kotlin
166:     suspend fun sendRequest(method: String, params: Any?): JsonRpcResponse = withContext(Dispatchers.IO) {
...
170:         pendingRequests[requestId] = deferred
171: 
172:         val jsonRequest = gson.toJson(JsonRpcRequest(id = requestId, method = method, params = params))
173:         
174:         val body = jsonRequest.toRequestBody(JSON_MEDIA_TYPE)
175:         val httpRequest = Request.Builder()
176:             .url(endpoint) // 抛出 IllegalArgumentException
177:             .post(body)
178:             .build()
179: 
180:         try {
```
`Request.Builder().url(endpoint)` 抛出的 `IllegalArgumentException` 绕过了第 180 行开始的 `try-catch` 块。

### F. OkHttp 阻塞式 `execute()` 导致的线程泄漏
在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 的第 181-185 行：
```kotlin
181:             okhttpClient.newCall(httpRequest).execute().use { response ->
182:                 if (!response.isSuccessful) {
183:                     throw IOException("HTTP POST failed with code: ${response.code}")
184:                 }
185:             }
```
当外层协程被超时或手动取消时，OkHttp 底层同步 Socket 连接无法被 cancel。

---

## 2. Logic Chain (推理链)

通过上述观测，我们可以推导得出以下结论：
1. **Socket 泄漏**: 由于 `McpClient.connect()` 并发调用时可以直接覆盖 `eventSource` 类变量，导致先前已经运行的 `EventSourceListener` 永远接收不到取消事件。这将导致多条 SSE 物理通道同时在后台运行，引发资源和流量泄漏。
2. **重连冲突**: 因为 `updateConfigs` 逻辑和 `startConnectionLoop` 中的取消操作并非在一个原子同步块中执行，在短时间内多次调用 `updateConfigs` 时，可能会产生“双胞胎重连协程”同时运行，它们互相竞争并且会导致连接状态频繁抖动。
3. **拒绝服务 (OOM崩溃)**: 因为 JSON 数据解析只捕获 `Exception`。Gson 针对恶意超大 Payload 的解析会抛出 `OutOfMemoryError`（继承自 `Error`），这会突破异常防御，使得主应用直接崩溃退出。
4. **重定向泄漏 (SSRF)**: 代码中只要 endpoint 满足以 HTTP/HTTPS 开头，便直接将其用作 `messageEndpoint` 发送请求。这就使得恶意服务器只要在 SSE `endpoint` 事件中返回 `https://attacker.com/leak`，Loyea 客户端便会把当前伴侣的敏感物理环境信息和后续 LLM 的请求直接发送给攻击者。
5. **内存泄漏 (Request ID 泄漏)**: `Request.Builder().url(endpoint)` 如果传入非法格式的 URL，会直接在 `try` 块外部抛出运行时异常，这会导致在 `pendingRequests` 中插入的 `requestId` 彻底失去被 `remove` 的机会，导致内存悬挂泄漏。
6. **线程池耗尽**: 协程取消时 OkHttp 同步 `execute()` 并不知情，因而会继续阻塞工作线程，当发生批量超时时会引起 `Dispatchers.IO` 线程池耗尽，影响 App 整体性能。

---

## 3. Caveats (注意事项)

* 本次验证因本地执行环境安全弹窗超时导致 `.\gradlew test` 运行指令未获得执行。虽然我们通过严密的静态时序推导证实了这些对抗点的成立，但仍建议在修复后，由后继代理结合真实的并发测试用例运行验证。
* 物理传感器和 WorkManager 主动关怀（Milestone 3, Milestone 4）的真实并发环境由于接口未完全打通，尚未进行压力和对抗验证。

---

## 4. Conclusion (结论)

**评估裁决**: **不通过 (FAIL)**。
虽然 Milestone 1 的代码能够通过常规功能的 mock 单元测试，但其在高并发状态下的 Socket 泄漏、未捕获的 Error 闪退风险、不安全的 Endpoint 重定向（SSRF 敏感信息泄露）以及 URL 解析 RuntimeException 导致请求 ID 泄露这几处对抗点，都达到了 **HIGH** 风险级别。建议在进入 Milestone 2 前必须针对这 6 处对抗点进行代码加固。

---

## 5. Verification Method (验证方法)

### A. 检查命令与文件
- 对抗报告文件: `D:\CodingProjects\Android\Loyea\.agents\challenger_mcpclient_0\challenge.md`
- 关键源码路径:
  - `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\mcp\McpClient.kt`
  - `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\mcp\McpManager.kt`

### B. 失效判定条件 (Invalidation Conditions)
- 如果 `McpClient.connect()` 引入了 `Mutex` 互斥，且在每次启动 SSE EventSource 前主动执行了清理逻辑，则 Socket 泄漏假说失效。
- 如果解析处全部修正为 `catch (t: Throwable)` 且加了 Payload 大小限制，则 OOM 拒绝服务漏洞假说失效。
- 如果引入了重定向的主域名/同源 Host 校验，则不安全重定向（SSRF 隐私泄漏）假说失效。
