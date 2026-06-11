## Review Summary

**Verdict**: REQUEST_CHANGES

## Findings

### Critical Finding 1

- What: `McpClient.kt` 存在编译错误，导致 Kotlin 编译器编译失败。
- Where: `app/src/main/java/com/loyea/mcp/McpClient.kt` 第 103 行与第 105 行
- Why: 编译器提示 `Using 'parse(String): HttpUrl?' is an error. moved to extension function`。在当前 OkHttp 的版本中，`HttpUrl.parse(url)` 已不再是静态方法，而改为了 String 扩展函数或 `HttpUrl.Companion.parse(url)`。由于此项编译错误，项目整体无法编译，单元测试也无法执行。
- Suggestion: 将 `HttpUrl.parse(config.sseUrl)` 更改为 `config.sseUrl.toHttpUrlOrNull()`（需要导入 `okhttp3.HttpUrl.Companion.toHttpUrlOrNull`）或者 `HttpUrl.Companion.parse(config.sseUrl)` 或使用当前 OkHttp 版本支持的扩展属性 `toHttpUrl()`。

### Minor Finding 1

- What: `McpManager.kt` 中的退避重连 attempt 增加在极端极长断网情况下可能导致其数值无限上升而引起 `Math.pow` 的计算值极度膨胀甚至超出 Double/Long 的表示范围（产生 Double.POSITIVE_INFINITY 转换为 Long.MAX_VALUE 的现象）。
- Where: `McpManager.kt` 中的 `startConnectionLoop` 函数 (第 193-194 行)
- Why: 尽管因为最后有 `coerceAtMost(maxDelayMs)` 的约束，所以实际的 delay 时间会被限制在 maxDelayMs（60秒）而不会发生负数溢出或逻辑错误，但让 `attempt` 在重连失败时无限累加是不太雅致的设计，在极长时期断连下会对 JVM 溢出行为存在隐式依赖。
- Suggestion: 可以在 `attempt++` 之后对其数值进行上限封顶，比如 `attempt = attempt.coerceAtMost(30)`，或者在 `Math.pow` 之前进行截断。由于这属于极端边界条件且已有溢出保护，评级为 Minor。

### Minor Finding 2

- What: `McpManager.kt` 中退避重连的 Jitter 计算中，虽然 `nextLong(-jitterRange, jitterRange)` 避免了重连波峰的偏置，但在极小的概率下，由于 until 边界是 exclusive（排他的），生成的随机数实际范围是 `[-jitterRange, jitterRange - 1]`。
- Where: `McpManager.kt` 中的 `startConnectionLoop` 函数 (第 198 行)
- Why: 理论上此区间存在微小的负向偏置，若要达到完美的无偏对称，生成区间应该使用 `nextLong(-jitterRange, jitterRange + 1)`。
- Suggestion: 可以将其修改为 `kotlin.random.Random.nextLong(-jitterRange, jitterRange + 1)`，以保证绝对对称。但鉴于这在实际网络延时重试的随机分布中几乎没有任何实质负面影响，评级为 Minor。

## Verified Claims

- McpManager 状态 Flow 监听协程的取消 → verified via 代码静态分析，确认当客户端被重建、停用、删除或 McpManager 停止时，都调用了 `statusJob.cancel()`，不存在泄漏隐患 → PASS
- Jitter 抖动计算公式是否对称、无偏且安全 → verified via 代码静态分析，确认拥有 `jitterRange > 0` 防崩溃判定和 `baseDelay` 溢出封顶保护，在零点两侧波动实现了无偏抖动 → PASS
- 服务端别名更名即时重建客户端及前缀工具名更新 → verified via 代码分析，验证了别名更改能立刻触发 existingClient?.disconnect()、重建 McpClient、并在 getAggregateTools 中动态合成包含新别名工具名前缀（测试因编译失败未运行） → FAIL (编译失败)
- 协程 CancellationException 被吞噬问题彻底解决 → verified via 代码静态分析，在 McpClient.kt 及 McpManager.kt 中的所有 catch(e: Exception) 块内，第一行均加上了 `if (e is CancellationException) throw e` 以恢复正常的协程结构化并发 → PASS
- MainActivity.kt 顶层全局重组性能反模式重构 → verified via 代码静态分析，除 currentTheme 订阅留置 setContent 顶层外，所有 UI 数据流订阅（messages, isThinking 等）均下沉至 NavHost 内 composable 闭包，避免了全局重组 → PASS
- 并发 connect() 导致 Socket 泄漏与 EventSource 重叠问题解决 → verified via 代码静态分析，已引入 connectMutex: Mutex 并发互斥锁，并在新连接前对 eventSource 与 endpointDeferred 执行了主动 cancel 及 completeExceptionally 强制释放 → PASS
- Gson 数字 ID 挂起崩溃问题解决 → verified via 代码静态分析，已将 Response id 统一改为 JsonElement?，并在 idAsString getter 中使用 asJsonPrimitive.asString 安全提取，规避了由于数字 ID 导致的类型解析崩溃或 pendingRequests 无法注销引起的无限挂起 → PASS
- SSRF 重定向同源校验及恶意 Payload 导致的 OOM 闪退防御 → verified via 代码静态分析，已在 McpClient.kt 内对 messageEndpoint 的协议前缀进行了 host 与 port 同源校验，并在 handleMessage 中限制 payload <= 10MB 且捕获 Throwable（覆盖 Error 与 OOM 级） → PASS
- OkHttp 僵尸连接规避与异步 enqueue 协程化改造 → verified via 代码静态分析，已配置 pingInterval 为 30 秒心跳，并在 McpClient 内使用 suspendCancellableCoroutine 和 call.cancel() 实现了底层的异步非阻塞式包装与即时取消释放 → PASS

## Coverage Gaps

- LlmClient 中的同步网络请求 — 在 LlmClient.kt 的 sendChatCompletionStream 和 sendChatCompletion 中，仍然使用的是同步的 `client.newCall(request).execute()`。尽管它被放在了 Dispatchers.IO 协程上下文中，但这依然是一个阻塞式的调用。当大模型接口非常慢或连接异常时，可能会一直阻塞底层 IO 线程。 risk level: low。因为大模型 API 通常没有并发数非常高且高频的场景，且在 dispatchers.IO 中运行，但这依然属于一个未彻底协程化的区域。建议未来可以也对其使用 suspendCancellableCoroutine 异步 enqueue 化改造。 — recommendation: accept risk

## Unverified Items

- 单元测试运行结果 — 由于编译报错 `compileReleaseKotlin` 与 `compileDebugKotlin` 失败，单元测试未能成功执行，这属于待验证项。
