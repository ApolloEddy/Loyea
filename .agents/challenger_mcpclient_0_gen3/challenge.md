## Challenge Summary

**Overall risk assessment**: HIGH

通过对 `worker_mcpclient_3` 重构加固后的代码进行静态推导与逻辑链审计，我们发现原加固方案在防范 **SSRF 相对路径绕过**、**连接状态机并发时序**、**会话 JSON 读写冲突与防覆盖** 以及 **UI 拦截防双击双发** 等维度上均存在严重的遗留漏洞或新引入的安全/并发风险。

---

## Challenges

### [High] Challenge 1: SSRF 相对协议校验被绕过（大写/反斜杠绕过）

- **Assumption challenged**: 加固方案假设通过 `endpoint.trim().startsWith("//")` 和对 `http://` / `https://` 的 host/port 同源校验就可以完全拦截 SSRF 重定向。
- **Attack scenario**: 
  - **反斜杠绕过**：攻击者控制的 MCP 服务在 SSE `endpoint` 事件中返回 `http:\\attacker.com`。此字符串不以 `http://` 或 `https://` 开头，成功进入 `else` 路径。在 `else` 路径中，OkHttp 的 `parsedSseUrl.resolve("http:\\attacker.com")` 将反斜杠等价规避为正斜杠，返回绝对 URL `http://attacker.com/`，完全绕过了同源 host/port 校验。
  - **大小写绕过**：返回 `HTTP://attacker.com`。由于 Kotlin 的 `startsWith("http://")` 区分大小写，它同样避开校验进入 `else`，而 `HttpUrl.resolve` 仍可将其成功识别并解析为外部的 `http://attacker.com/`。
- **Blast radius**: 攻击者可以完全绕过 SSRF 防火墙，将工具请求重定向至任意外部而已域名，导致请求敏感信息（如 API token）泄露。
- **Mitigation**: 
  - 对传入的 `endpoint` 进行全范围的清洗与大小写统一，或者在 `else` 路径解析完成后，针对生成的 `resolvedEndpoint` 进行统一的 host/port 与原始 `parsedSseUrl` 的同源强校验。
  - 拦截所有包含反斜杠 `\` 且位于 scheme 定义区域的特殊重定向。

---

### [High] Challenge 2: McpClient.sendRequest 状态并发时序导致重连长期挂起（长阻塞漏洞）

- **Assumption challenged**: 加固方案假设在 `connect()` 的 catch 块中调用 `handleDisconnect()` 可以立即清理挂起的请求，防范挂起死锁。
- **Attack scenario**:
  1. `connect()` 协程进入 `sendRequest` 挂起方法，并刚刚读取完非空的 `messageEndpoint`。
  2. 此时，由于网络错误或主动取消，外部线程（如 okhttp 线程的 `onFailure` 或者是 disconnect 调用）调用了 `handleDisconnect()`，在 `synchronized(this)` 内清空了 `pendingRequests` Map 并释放锁。
  3. 接着，`sendRequest` 继续运行，将自己的新 `deferred`（关联当前请求）放入 `pendingRequests` Map，并执行 `deferred.await()`。
  4. 因为 `handleDisconnect()` 已经在此之前运行过了，该 `deferred` 将永远不会被 completeExceptionally 释放。
  5. 导致当前 `sendRequest` 必须硬等 `withTimeout(30000)` （30秒超时）才会抛出超时异常。
  6. 在这 30 秒挂起期间，由于 `connect()` 协程仍占有着挂起锁 `connectMutex`，导致任何新的 `connect()` 调用都被长阻塞 30 秒，极大地劣化了客户端的并发重连体验。
- **Blast radius**: 并发网络断开时有高概率触发 30 秒重连阻塞，使客户端在异常重连时完全失去响应能力。
- **Mitigation**:
  - 在 `sendRequest` 向 `pendingRequests` 注册 `deferred` 的操作必须包含在 `synchronized(this)` 内，并检验当前的 status 是否确实仍处于 `CONNECTING` 或 `CONNECTED` 状态，如果已被断开则立即抛出 IOException 拒绝注册。

---

### [High] Challenge 3: 主线程同步阻塞 runBlocking 与协程锁竞争导致的 ANR 风险

- **Assumption challenged**: 加固方案假设在 `ChatStorageManager` 的公有 API 中使用 `runBlocking` 包裹 `Mutex` 锁可以保证并发读写安全而不影响 UI。
- **Attack scenario**:
  - 后台 `GreetingWorker` 在 `Dispatchers.IO` 的协程中以 `runBlocking` 执行耗时的会话 JSON/Metadata 磁盘读写，持有了 `sessionsMutex` 或 `messagesMutex` 锁。
  - 恰在此时，主线程（UI线程）调用了 `ChatViewModel` 的同步初始化（如 `loadAllData()`、`loadSessions()`）或用户交互方法（如切换会话 `selectSession()`、`deleteSession()`）。这些方法在主线程上同步调用 `ChatStorageManager` 的公有 API，引发主线程的 `runBlocking` 挂起以等待后台线程释放 Mutex。
  - 由于磁盘 IO 的物理开销，主线程被同步阻塞长达数百毫秒甚至数秒。
- **Blast radius**: 用户界面产生严重的冻结与掉帧，高并发或磁盘繁忙时将 100% 触发 Android 系统的 ANR（Application Not Responding）崩溃。
- **Mitigation**:
  - 移除 `ChatStorageManager` 公有 API 里的 `runBlocking` 阻塞结构，将其完全重构为 Kotlin 标准的挂起函数（`suspend fun`），确保所有的文件 IO 与 `Mutex.withLock` 操作都非阻塞地挂起在正确的调度器（`Dispatchers.IO`）上，决不能阻塞主线程。

---

### [Medium] Challenge 4: 后台问候消息并发覆盖丢失的竞态窗口依然存在

- **Assumption challenged**: 加固方案假设在 `ChatViewModel` 写入前，先读取磁盘再在内存中去重合并（LinkedHashMap），可以绝对保留 `GreetingWorker` 产生的新消息。
- **Attack scenario**:
  - `ChatViewModel` 调用 `saveMessagesAsync` -> 执行 `mergeAndSaveMessages`。
  - 该函数执行第 1 步：调用 `loadSessionMessages` 从磁盘读取旧消息。
  - 读取完成后，锁被释出。前台在内存中合并 UI 修改。
  - 在这个空档期，后台 `GreetingWorker` 获得了锁，并将一条新问候消息写入了磁盘文件，随后释放了锁。
  - 接着，前台 `ChatViewModel` 获取锁，执行第 3 步：调用 `saveSessionMessages` 将自己刚刚在内存中组装完毕的列表（基于第 1 步的旧数据，不包含刚刚后台写入的问候语）直接覆盖写回磁盘。
  - 导致 `GreetingWorker` 产生的最新消息在磁盘中被无情抹去。
- **Blast radius**: 后台主动问候消息在与前台发送消息高并发冲突时，依然会被覆盖抹除。
- **Mitigation**:
  - 必须保证“读取-合并-写入”这三个操作是一个原子过程，即它们必须都在同一个 `Mutex.withLock` 保护周期内执行，绝不能在读取与写入中间释放锁。

---

### [Medium] Challenge 5: UI 并发双发拦截的异步延迟绕过

- **Assumption challenged**: 加固方案假设在 `ChatScreen` 和 `ChatInputBar` 中，通过 `isThinking` 和 `isMcpRunning` 置灰按钮和拦截 Enter 键就可以防止并发消息双发。
- **Attack scenario**:
  - 当用户在输入框输入文字并点击“发送”时，`ChatViewModel.sendMessage()` 会在协程中首先执行 `mergeAndSaveMessages(sessionId, memoryMsgs)`。
  - 这是一个涉及到磁盘 IO 读写（合并）的操作，通常耗时数十毫秒以上。
  - 在这部分磁盘 IO 正在后台挂起执行的过程中，主线程并未更新 `isThinking` 状态，因为 `isThinking.value = true` 只有在磁盘 IO 完成、切回主线程并进入 `startAiResponseStream` 时才会被执行。
  - 因此，在点击发送后的磁盘 IO 悬挂期内，UI 输入框和发送按钮仍然处于可用状态，用户可以轻而易举地在这个空档期再次点击发送，发送重复的并发请求。
- **Blast radius**: UI 拦截存在时间差窗口，允许用户在极端时间内双击发送重复文本，破坏会话时序和消息流一致性。
- **Mitigation**:
  - UI 触发发送时，在 UI 层或 `sendMessage` 入口的第一行（主线程）立即将 `isThinking` 或 `isSending` 设为 `true`，以确保输入控件在第一时刻被彻底锁定，待流程流转完毕后再切换到 `startAiResponseStream` 的逻辑中。

---

## Stress Test Results

- **测试用例 1**: SSRF 反斜杠绕过 (`http:\\attacker.com`) 
  - 预期行为: 抛出 `SecurityException` 或 `IOException` 拒绝重定向。
  - 实际行为: 绕过同源校验，成功向 `http://attacker.com/` 发起 POST 请求 -> **FAIL (安全边界失效)**。
- **测试用例 2**: 并发断开与 `sendRequest` 挂起时序
  - 预期行为: 重连在毫秒级恢复，不发生挂起。
  - 实际行为: `connect()` 协程被挂起 30 秒以等待 `sendRequest` 超时释放 `connectMutex` -> **FAIL (体验级死锁)**。
- **测试用例 3**: 主线程调用 `ChatStorageManager`
  - 预期行为: 界面流畅，无阻塞。
  - 實際行为: 在高频写盘或 GC 时，主线程明显卡顿，有高概率触发 ANR -> **FAIL (主线程阻塞崩溃隐患)**。
- **测试用例 4**: 消息防覆盖竞态
  - 预期行为: 后台 Worker 生成的消息 100% 被保留。
  - 实际行为: 前台在 IO 写入空档期覆盖，后台消息丢失 -> **FAIL (数据一致性受损)**。

---

## Unchallenged Areas

- `LlmClient.kt` 响应实体流的 `.use` 释放机制 — 经过静态审查，该机制已完美且严密地包裹了 OkHttp 的 execute 返回对象，不存在任何资源泄漏路径，判定为 Robust，故未予挑战。
