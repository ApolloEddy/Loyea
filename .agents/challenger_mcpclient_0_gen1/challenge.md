## Challenge Summary

**Overall risk assessment**: HIGH

经过对 `worker_mcpclient_1` 修复重构后的代码进行静态审计与逻辑推理，本代理在并发控制、协程生命周期捕获顺序和结构化并发异常冒泡处理中，发现 3 个依然存留的高并发、并发锁、安全性与稳定性对抗漏洞。由于这些漏洞直接与 Kotlin 协程的取消机制（`CancellationException`）以及高并发竞态条件相关，具有极高的隐蔽性，但在实际恶劣网络或高频操作环境下必然触发，导致应用长连接彻底卡死、后台协程泄漏、僵尸重连泛滥和内存泄漏。

---

## Challenges

### [High] Challenge 1: `McpClient.connect()` 中 `CancellationException` 抛出过早导致连接状态挂死与 EventSource 泄漏

- **Assumption challenged**: 假设在捕获异常时，若遇到 `CancellationException` 应立即直接向上抛出，而不需要先清理长连接状态或释放底层 EventSource 资源。
- **Attack scenario**: 
  1. 客户端发起 `McpClient.connect()` 试图连接 MCP SSE 服务器；
  2. 由于网络延迟，连接流程挂起在 `endpointDeferred!!.await()`（第99行）或 `sendRequest("initialize", ...)`（第118行）；
  3. 此时，超时控制 `withTimeout(10000)` 触发，或用户退出当前界面/切出 App 导致外部协程作用域被 `cancel`，从而抛出 `TimeoutCancellationException`（属于 `CancellationException` 的子类）；
  4. 位于 `connect()` 最外侧的 `catch (e: Exception)` 拦截到此异常。在第 140 行执行了：
     ```kotlin
     if (e is CancellationException) throw e
     ```
     这使得该异常被直接抛出，完全跳过了原本位于其后的 `handleDisconnect()` 清理逻辑。
- **Blast radius**: 
  1. **状态永久卡死**：`_status.value` 依然保留在连接发生时的 `McpServerStatus.CONNECTING` 状态，未能被重置为 `DISCONNECTED`。在此状态下，后续任何试图重新调用 `connect()` 的操作，都会在入口处因为 `if (_status.value != McpServerStatus.DISCONNECTED)` 的判断直接返回 `false`。这意味着整个 App 进程生命周期内，该客户端都彻底失去了重新连接的机会；
  2. **EventSource 资源泄漏**：之前被拉起的 `EventSource`（第 94 行）未被 `cancel()` 释放，在后台继续隐蔽地监听 SSE 事件或维持僵尸网络长连接，导致底层 Socket 连接以及线程资源发生严重泄漏。
- **Mitigation**: 
  在 `connect()` 捕获到异常时，应在最前置位置无条件调用 `handleDisconnect()` 释放长连接资源并重置状态，再向上抛出 `CancellationException`：
  ```kotlin
  } catch (e: Exception) {
      handleDisconnect()
      if (e is CancellationException) throw e
      Log.e(TAG, "Connection failed for ${config.name}", e)
      false
  }
  ```

---

### [Medium] Challenge 2: `McpClient.sendRequest()` 发生超时/取消时未从缓存清理 `pendingRequests` 导致内存泄漏

- **Assumption challenged**: 假设协程被超时取消后，无需主动将并发 Map 中已发出的未决请求信息（CompletableDeferred）手动移除。
- **Attack scenario**: 
  1. `McpClient.sendRequest()` 将一个随机生成的 `requestId` 对应的 `CompletableDeferred<JsonRpcResponse>` 实例注册进并发哈希表 `pendingRequests` 中（第 188 行），并启动 HTTP POST 写入，随后在 `withTimeout(15000) { deferred.await() }` 中挂起等待服务端响应；
  2. 如果由于网络不佳导致 15 秒超时未达，或者调用者协程因其他外部原因被取消，将抛出 `CancellationException`；
  3. `catch (e: Exception)` 拦截该异常，第一句同样为：
     ```kotlin
     if (e is CancellationException) throw e
     ```
     异常直接向上抛出，从而越过了第 232 行的 `pendingRequests.remove(requestId)` 这一关键清理动作。
- **Blast radius**: 
  `pendingRequests` 结构将永远残留该 `requestId` 对应的 `CompletableDeferred` 强引用。每次网络超时或被取消，内存中就会不可逆地多泄漏一个 Deferred 对象以及相关联的协程上下文，造成随着使用时间增长、网络恶化而加剧的长期内存泄漏。
- **Mitigation**: 
  采用 `try ... finally` 结构包裹等待块，确保无论由于何种原因退出（包括成功、抛异常、协程取消），都会在 `finally` 块中立即清理 Map 中的残留项：
  ```kotlin
  try {
      withTimeout(15000) {
          deferred.await()
      }
  } finally {
      pendingRequests.remove(requestId)
  }
  ```

---

### [High] Challenge 3: `McpManager.updateConfigs()` 并发竞态导致悬挂重连协程 (reconnect Job) 无法被注销而永久泄漏

- **Assumption challenged**: 假设并发调用配置更新操作时，指数退避重连协程（Job）在 Map 容器中的增删与协程生命周期取消能够无锁安全地运行。
- **Attack scenario**: 
  `McpManager.updateConfigs(newConfigs)` 用于根据外部配置列表更新/重建连接客户端。然而，此函数本身没有进行任何并发互斥锁（如 `Mutex` 或 `synchronized`）的保护。
  当用户快速点击“保存配置”、在 UI 上发生连续快速点击或配置导入时，可能在多线程或多个协程内并发/以微妙时间差交替调用 `updateConfigs()`。
  1. **线程 A** 执行 `updateConfigs`，发现需要新建 client X，调用 `startConnectionLoop`：
     ```kotlin
     reconnectJobs[config.id]?.cancel() // 此时 reconnectJobs 中该 id 还为空
     reconnectJobs[config.id] = coroutineScope.launch { ... } // 线程 A 启动了 Job1，准备写回 map
     ```
  2. 在线程 A 实际把 `Job1` 写入 `reconnectJobs` map 之前的间隙，**线程 B** 并发触发了 `updateConfigs`。
  3. **线程 B** 在 `startConnectionLoop` 中执行 `reconnectJobs[config.id]?.cancel()`，但因为此时 map 中该 id 还是空（或者为更旧的 Job），所以无法取消线程 A 刚刚拉起的 `Job1`；
  4. 随后，**线程 B** 启动了 `Job2`，并将 `reconnectJobs[config.id] = Job2` 写入了 Map 中；
  5. 最终，线程 A 慢一步的写入操作或者线程 B 覆盖了它，导致 `Job1` 虽然正在全局的 `coroutineScope` 中以 `while(isActive)` 指数退避方式无限重连运行，但它的 Job 引用已从 `reconnectJobs` 缓存中彻底丢失，成为了一只“僵尸 Job”。
- **Blast radius**: 
  1. **后台网络重连协程永久泄露**：这个悬挂的 `Job1` 在后台执行无限重连。即使稍后用户在设置中彻底禁用了该服务器，或者调用 `stop()` 准备关闭所有 MCP 客户端，`McpManager` 依然只能通过 `reconnectJobs[config.id]?.cancel()` 找到并关闭 `Job2`，完全无法触及已丢失引用的 `Job1`。
  2.只要配置存储中 `isEnabled == true` 依然成立，该僵尸协程便会永远存活并在后台无限尝试 `client.connect()`，导致对同一个 SSE 服务端同时有两个甚至多个重连循环交替进行，流量与电量消耗成倍上升，构成隐蔽的本地资源耗尽。
- **Mitigation**: 
  1. 在 `updateConfigs` 级别增加 `Mutex` 锁保护，确保多配置更新操作在同一时间只有一个在执行：
     ```kotlin
     private val configUpdateMutex = Mutex()
     suspend fun updateConfigs(newConfigs: List<McpServerConfig>) = configUpdateMutex.withLock { ... }
     ```
  2. 对重连循环的 Job 管理进行严格的临界区同步，防止协程在 Map 写入和取消时的覆盖和交错。

---

## Stress Test Results

- **测试场景 1（网络请求超时/取消）**：在等待 SSE endpoint 事件时注入 12000ms 延迟，触发 10 秒超时。
  - 预期行为：`connect()` 抛出 `TimeoutCancellationException`，捕获异常后自动清理旧 `eventSource`，重置状态为 `DISCONNECTED`。
  - 实际/预测行为：捕获到 `CancellationException` 后直接向上抛出，`_status` 被永久卡死在 `CONNECTING`，下一次连接直接返回 `false`。
  - 结果：**FAIL (不通过)**

- **测试场景 2（并发快速更新配置）**：模拟用户在 UI 连续快速保存配置，极短时间内两次触发 `updateConfigs`。
  - 预期行为：只保留最新的重连协程，前一次的重连协程被成功 `cancel`。
  - 实际/预测行为：前一次启动的重连协程成为悬挂的“僵尸 Job”，继续在后台无线执行重试，无法通过 `reconnectJobs[id]?.cancel()` 关闭。
  - 结果：**FAIL (不通过)**

- **测试场景 3（消息发送超时）**：调用 `sendRequest()` 等待 15s 响应时注入 20s 延迟，触发 HTTP 超时。
  - 预期行为：`sendRequest` 超时退出，`pendingRequests` 对应 Map 清理干净，不留残余。
  - 实际/预测行为：在异常块捕获 `CancellationException` 直接向上抛出，`pendingRequests.remove(requestId)` 未被执行，注册的 deferred 残留在 Map 中导致泄漏。
  - 结果：**FAIL (不通过)**

---

## Unchallenged Areas

- **WatchDataRepository 心率与模拟传感器** — 属于 Milestone 3 模块，不涉及长连接和网络并发逻辑，当前环境中无可疑高并发调用，暂未进行深入的并发锁应力测试。
- **GreetingWorker 定时主动关怀** — 属于 Milestone 4 模块，采用系统 WorkManager 内部的排队串行机制，基本不涉及用户前台竞态冲突，因此未作为主要对抗测试面。
