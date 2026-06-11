# 对抗性评审报告 (Challenge Report)

**评估时间**：2026-06-11
**评审版本**：`worker_mcpclient_3` 重构加固后版本
**评估人员**：Challenger 1
**整体风险评估**：**CRITICAL (危急)**

---

## 核心挑战与缺陷 (Challenges)

### 🔴 [Critical] 挑战 1: SSRF 相对协议大小写绕过漏洞 (SSRF Case-Sensitivity & Redirect Bypass)

- **被挑战的假设**：假设通过大小写敏感的 `startsWith("http://")` 或 `startsWith("https://")` 可以识别所有绝对重定向 URL，并假设对非此开头的端点直接调用 `HttpUrl.resolve` 只能在同源路径下解析。
- **攻击场景**：
  攻击者搭建并运行一个恶意的 MCP SSE 服务端。在客户端与其连接并接收 `endpoint` 事件时，恶意服务端发送一个经过特殊构造的绝对路径，例如：
  `HTTP://attacker.com/message` 或 `Https://attacker.com/message`（首字母大写）。
  1. 客户端在 `McpClient.kt`（第 124 行）校验重定向协议头：
     ```kotlin
     val resolvedEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) { ... }
     ```
     由于 `startsWith` 是大小写敏感的，`HTTP://...` 或 `Https://...` 均无法匹配，因此**直接跳入 `else` 分支**。
  2. 在 `else` 分支中，直接执行了 OkHttp 的 `resolve` 解析：
     ```kotlin
     parsedSseUrl.resolve(endpoint)?.toString()
     ```
     根据 RFC 3986 及 OkHttp `HttpUrl` 的解析规范，`resolve` 方法在处理带有合法 scheme（大小写无关）的输入时，会将其视为绝对 URL 予以解析和规范化（将 `HTTP` 规范化为 `http`），返回 `http://attacker.com/message`。
  3. **关键漏洞点**：`else` 分支内**完全没有对解析后的 Host 和 Port 进行任何同源一致性校验**！
  4. 最终恶意端点 `http://attacker.com/message` 被成功赋值给 `messageEndpoint`。在此之后，客户端执行 `initialize` 握手及所有后续 Tool Calls，都会直接向恶意服务器发送 POST 请求，导致包括 **API Key**、对话上下文等极为敏感的数据全部泄漏给外部攻击者。
- **危害范围**：极高。允许恶意 MCP 服务端物理绕过同源策略限制，实现完整的 SSRF，窃取用户 API Key 及所有对话敏感数据。
- **修复方案**：
  1. 将判定 scheme 更改为大小写无关的判定（例如 `endpoint.lowercase().startsWith(...)`），或者直接使用 `toHttpUrlOrNull()` 对 endpoint 进行解析。
  2. 统一校验：不管是从 `if` 分支还是 `else` 分支解析出来的 `resolvedEndpoint`，在最终赋值前**必须**统一检验其 Host 与 Port 是否与原始 `sseUrl` 严格一致。

---

### 🟠 [High] 挑战 2: 持久化文件并发更新与会话状态覆盖丢失 (Lost Update in Concurrency & LLM Sleep Window)

- **被挑战的假设**：假设在 `ChatStorageManager` 的个别读取和保存方法内引入 `Mutex` 锁，并在 ViewModel 中使用 `mergeAndSaveMessages` 进行去重合并，就可以安全地隔离后台 Worker 与前台 UI 间的并发文件读写冲突。
- **攻击/失效场景**：
  `ChatStorageManager` 的 `Mutex` 仅保护了单个 `load` 或 `save` 方法的执行，但**并没有提供跨方法的事务性锁**。ViewModel 和后台 `GreetingWorker` 均使用“读取磁盘 -> 内存修改 -> 覆写磁盘”的多步操作，中间存在极大的竞争空隙：
  1. **消息流并发覆盖丢失**：
     - T1：用户在 UI 点击发送，ViewModel 触发 `sendMessage()`，在 `Dispatchers.IO` 上调用 `mergeAndSaveMessages`。VM 读取当前磁盘消息 `[MsgA]`，随后释放读取锁，并在内存中将 `[MsgA]` 与新消息 `[MsgB]` 合并。
     - T2：同时后台 `GreetingWorker` 完成问候语生成，调用 `loadSessionMessages` 得到 `[MsgA]`，并添加 `MsgG`（关怀消息）后，调用 `saveSessionMessages` 将 `[MsgA, MsgG]` 写入磁盘。
     - T3：VM 将本地合并完的 `[MsgA, MsgB]` 调用 `saveSessionMessages` 覆写磁盘。
     - **结果**：`GreetingWorker` 生成的 `MsgG` 瞬间被 VM 的老数据覆盖抹除，导致消息在 UI 和磁盘中彻底丢失。
  2. **会话列表并发彻底破坏（LLM 延迟窗口）**：
     - T1：`GreetingWorker` 启动并调用 `storageManager.loadSessionList()` 获取当前的会话列表。
     - T2：Worker 启动 `llmClient.sendChatCompletionStream(...)` 调用大模型生成关怀问候，此阶段需要流式生成并等待，**耗时可达数秒**。
     - T3：在此期间，用户在 UI 上新建了会话 `SessionNew`，或者修改了某会话设置。VM 立刻将更新后的会话列表写入磁盘。
     - T4：Worker 完成大模型请求，将会话列表中对应会话的 `lastActiveTime` 更新，并将它先前在 T1 读取的（此时已过期的）会话列表覆写回磁盘。
     - **结果**：用户在 Worker 生成期间进行的所有会话操作（如新建会话、删除会话、修改设置）**全部被 Worker 覆写抹除**！新建的会话在磁盘中永久消失。
- **危害范围**：高。导致后台关怀功能与前台 UI 操作冲突时，产生严重的数据丢失或状态回滚，破坏数据一致性。
- **修复方案**：
  1. `ChatStorageManager` 应当提供包含读写事务的原子化接口，如 `updateSessionMessages(sessionId) { currentList -> ... }`，使“读-改-写”整个链条均在 `Mutex` 锁内闭环完成。
  2. 在进行耗时操作（如大模型调用）期间，**严禁持有或保存整份过期的列表快照**，应仅在写盘时在锁内进行增量合并或使用最新的磁盘状态进行瞬时更新。

---

### 🟡 [Medium] 挑战 3: 异步状态更新导致 UI 双发拦截失效 (UI Double-Submit Bypass via Asynchronous State Updates)

- **被挑战的假设**：假设只要在 `ChatInputBar` 对 `isThinking` 或 `isMcpRunning` 状态进行置灰和 key 拦截，就能在 MCP/LLM 运行时完全阻断用户的重复点击提交。
- **失效场景**：
  在 `ChatViewModel.kt` 的 `sendMessage()` 中：
  ```kotlin
  fun sendMessage(inputText: String) {
      if (inputText.isBlank()) return
      ...
      val sessionId = currentSessionId.value
      viewModelScope.launch(Dispatchers.IO) {
          val finalMsgs = mergeAndSaveMessages(sessionId, memoryMsgs) // 耗时 I/O
          withContext(Dispatchers.Main) {
              messages.value = finalMsgs
              updateSessionTitleIfNeeded(sessionId, finalMsgs)
              startAiResponseStream(sessionId, finalMsgs, activeCard)
          }
      }
  }
  ```
  `isThinking` 的状态变更为 `true` 是在 `startAiResponseStream()` 内部通过 `viewModelScope.launch` 异步执行的。
  当用户轻触发送按钮，`sendMessage` 在主线程触发，但它直接启动了 `Dispatchers.IO` 的协程去读写磁盘，并立即退出。在此期间，**主线程空闲，且 `isThinking` 依然为 `false`**。
  在磁盘写入完成并回到主线程回调之前的这段时间内（可能持续数十毫秒，甚至在系统 I/O 阻塞时更长），发送按钮**依然处于激活状态**，用户可以快速多次点击发送按钮。这会启动多个并发的发送任务，产生重复消息并造成严重的并发写入竞争。
- **危害范围**：中。在高频点击或低性能设备上，容易绕过防重复点击逻辑，产生垃圾数据或并发写入 crash。
- **修复方案**：
  在 `sendMessage(inputText)` 被调用的第一时间（在启动任何 IO 协程前，直接在主线程中），同步将 `isThinking.value = true`。

---

### 🟢 [Low] 挑战 4: 内存可见性与线程安全隐患 (Missing Volatile Modifiers / Thread Visibility Issues)

- **被挑战的假设**：假设在连接的协程线程和 OkHttp Dispatcher 线程间共享的非 volatile 字段在没有显式同步 of 读操作下能自动保证可见性。
- **失效场景**：
  在 `McpClient.kt` 中：
  ```kotlin
  private var endpointDeferred: CompletableDeferred<String>? = null
  private var messageEndpoint: String? = null
  ```
  这两个字段在连接协程中进行写操作（有些在 `synchronized` 块中），但是：
  - `endpointDeferred` 在 OkHttp 事件源的回调线程（`onEvent`）中被非同步地读取和完成；
  - `messageEndpoint` 在协程 `Dispatchers.IO` 线程上运行的 `sendRequest` 和 `sendNotification` 中被非同步地读取。
  由于这两个变量未声明为 `@Volatile`，在多核 ARM 架构 of Android 设备上，可能会因为 CPU 缓存未同步或指令重排，导致读线程看见 `null` 或陈旧的引用。例如，`sendRequest` 可能在连接建立后仍然看见 `messageEndpoint` 为 `null` 并抛出 "Not connected" 错误，或者 OkHttp 线程无法正常触发 await 恢复。
- **危害范围**：低。偶发性连接挂起、断连或初始化异常。
- **修复方案**：
  在 `McpClient.kt` 中，对 `endpointDeferred` 和 `messageEndpoint` 变量添加 `@Volatile` 装饰器。

---

## 对抗测试结果 (Stress Test Plan & Verdict)

由于本地 Gradle 单元测试环境权限限制无法直接通过控制台执行指令，以下测试结果基于对修复代码的**静态推导与逻辑推演**：

| 测试场景 (Scenario) | 预期行为 (Expected) | 实际/推演行为 (Actual/Predicted) | 结论 (Verdict) |
| :--- | :--- | :--- | :--- |
| **SSRF 相对协议绕过测试**<br>发送以 `//attacker.com` 重定向 | 拒绝连接，抛出 `SecurityException` | 成功拦截并抛出 `SecurityException` | **PASS (封堵成功)** |
| **SSRF 大写绝对协议绕过测试**<br>发送以 `HTTP://attacker.com` 重定向 | 拒绝连接，抛出 `SecurityException` | **通过 else 分支绕过拦截，并成功建立指向恶意站点的 messageEndpoint** | ❌ **FAIL (漏洞依然存在)** |
| **并发重连测试**<br>在连接中途触发 Cancellation 并重连 | 释放旧 EventSource，状态原子流转至 CONNECTING 最终置为 CONNECTED，无死锁 | 互斥锁 `connectMutex` 与 `synchronized(this)` 保证了连接状态机的并发安全，在 Cancellation 抛出时正常清理状态 | **PASS (无死锁)** |
| **消息流前后台并发更新测试**<br>VM 与 Worker 瞬间同时写盘 | 两者消息互不干扰，合并保存 | ViewModel 的 `save` 动作会直接覆写 Worker 写入的最新消息 | ❌ **FAIL (存在消息覆盖丢失)** |
| **会话列表前后台并发更新测试**<br>Worker 在 LLM 耗时段用户更新会话列表 | 用户新建/删除的会话被完整保存 | Worker 的 LLM 请求完成后，会用过期会话列表覆写磁盘，导致用户新建的会话被擦除 | ❌ **FAIL (存在严重会话擦除漏洞)** |
| **UI 高频双击发送测试**<br>在 I/O 写入中途连续点击发送 | 按钮置灰，拦截第二次及以后的点击与回车 | 在 I/O 写入完成之前，`isThinking` 仍为 `false`，第二次点击被接受并触发重复发送 | ❌ **FAIL (UI 重复发送拦截存在漏洞)** |
| **SSE 异常流关闭测试**<br>流解析中途抛出异常或协程被取消 | 自动关闭 OkHttp 响应连接，无 Socket 泄露 | OkHttp 的 Response 结构已应用 `.use { ... }` 机制，自动调用 close() 释放连接 | **PASS (封堵成功)** |

---

## 未校验的区域 (Unchallenged Areas)

- **物理传感器定位变化高频同步**：由于缺乏真机传感器仿真环境，未在真机上对其 GPS 定位流的并发安全性和 SharedPreferences 持久化性能瓶颈做深度压测。
- **WearOS 硬件数据模拟并发瓶颈**：由于 mock-watch-repository 仅提供了内存态的逻辑模拟，未验证若心率采集接口产生密集硬件回调时是否存在协程上下文切换拥堵风险。
