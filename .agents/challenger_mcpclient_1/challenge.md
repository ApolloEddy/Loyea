## 挑战总结 (Challenge Summary)

**整体风险评估**: HIGH (高风险)

## 挑战点 (Challenges)

### [High] 挑战点 1：Kotlin 协程取消异常吞没 (Swallowing Coroutine CancellationException)

- **被挑战的假设**：假设通过捕获 `Exception` 可以安全地处理 `McpClient.connect()` 中的所有连接期异常，而不会影响协程的生命周期管理。
- **攻击/失效场景**：
  在 `McpClient.kt` 的 `connect` 函数中：
  ```kotlin
  } catch (e: Exception) {
      Log.e(TAG, "Connection failed for ${config.name}", e)
      handleDisconnect()
      false
  }
  ```
  在 Kotlin 协程中，取消（Cancellation）是通过抛出 `CancellationException` 实现的。因为 `CancellationException` 继承自 `Exception`，所以上述 `catch (e: Exception)` 块会将其捕获，并执行断开连接的清理逻辑（`handleDisconnect()`），最后返回 `false`。
  这意味着协程的取消异常被吞没了。当调用方（如 `McpManager` 中的 `startConnectionLoop`）取消该 Job 时，`connect()` 依然会继续执行完 `catch` 块并返回 `false`，这可能导致调用方协程在被取消后依然执行一些不需要的清理逻辑，且可能无法正确、快速地响应作用域的取消。
- **波及范围**：破坏了 Kotlin 协程的结构化并发，导致重连循环或后台连接任务在取消时响应滞后，并产生多余的断开连接日志，极端情况下可能引发内存泄漏或僵尸协程。
- **缓解建议**：
  在捕获异常时，必须显式重新抛出 `CancellationException`，或者专门捕获非取消类异常：
  ```kotlin
  } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.e(TAG, "Connection failed for ${config.name}", e)
      handleDisconnect()
      false
  }
  ```

### [High] 挑战点 2：高并发 `connect()` 导致 SSE 泄露与连接冲突

- **被挑战的假设**：假设客户端的状态切换和初始化是线程安全的，且不会被同时重复调用。
- **攻击/失效场景**：
  在 `McpClient.kt` 的 `connect` 函数中：
  ```kotlin
  if (_status.value != McpServerStatus.DISCONNECTED) {
      return _status.value == McpServerStatus.CONNECTED
  }
  ...
  _status.value = McpServerStatus.CONNECTING
  ```
  该状态检查和赋值操作不是原子的。在高并发或多个调用者同时触发 `connect()` 时，两个协程可以同时通过此检查，并行执行后续初始化逻辑。
  随后，它们会覆盖共享的类属性 `endpointDeferred` 和 `eventSource`：
  ```kotlin
  endpointDeferred = CompletableDeferred()
  ...
  eventSource = factory.newEventSource(request, listener)
  ```
  这会导致先发起的连接对应的 `eventSource` 句柄丢失（产生底层的 Socket/SSE 泄露），且旧连接的 SSE 事件监听器仍在后台运行。一旦旧连接的事件监听器触发 `onFailure` 或 `onClosed`，它会调用 `handleDisconnect()`，这将取消新建立的、原本正常的连接，清除新连接的 `pendingRequests`，并将客户端状态再次强制设为 `DISCONNECTED`。
- **波及范围**：在高并发或高频重连下，导致后台 SSE 连接泄漏、端口占用，以及新旧连接监听器互相干扰，引发连接频繁断开、工具调用请求被无故取消。
- **缓解建议**：
  在 `connect` 方法中使用互斥锁（如 `Mutex`）或原子状态机，确保在同一时间只有一个连接操作在进行，并且在开启新连接前，必须先安全地关闭旧的 `eventSource`。

### [High] 挑战点 3：GSON 反序列化 `id` 类型不兼容导致请求无限挂起

- **被挑战的假设**：假设所有 MCP 服务端返回的 JSON-RPC 响应中的 `id` 字段均可表示为 `String` 类型。
- **攻击/失效场景**：
  在 `JsonRpc.kt` 中，`JsonRpcResponse` 的 `id` 字段声明为 `String?`。
  根据 JSON-RPC 2.0 标准规范，响应 ID 可以是 `String`、`Number` 或 `Null`。许多标准的 MCP 服务端（如 Python/Node.js 实现的 SDK 默认行为）会使用递增的整数作为请求/响应 ID（例如 `{"jsonrpc":"2.0", "id": 1, ...}`）。
  当 GSON 尝试将数值 `1` 解析为 Kotlin 的 `String?` 字段时，会抛出 `JsonSyntaxException`（"Expected a string but was NUMBER"）。
  在 `McpClient.kt` 的 `handleMessage` 中：
  ```kotlin
  private fun handleMessage(data: String) {
      try {
          val response = gson.fromJson(data, JsonRpcResponse::class.java)
          ...
      } catch (e: Exception) {
          Log.e(TAG, "Failed to parse message: $data", e)
      }
  }
  ```
  此异常被捕获并记录日志后被吞掉，导致 `pendingRequests` 中对应的 `CompletableDeferred` 永远无法被 `complete()`。调用方在 `sendRequest` 中执行 `deferred.await()` 会一直挂起，直到 15 秒超时抛出异常。
- **波及范围**：与标准的使用数字 ID 的 MCP 服务端完全不兼容。所有工具调用都会因为解析失败而挂起 15 秒并最终因超时而失败，严重损害功能可用性。
- **缓解建议**：
  应将 `JsonRpcResponse` 和 `JsonRpcRequest` 中的 `id` 类型修改为 `JsonElement?`（或自定义的自定义反序列化器，兼容 String 和 Number 转换为 String 存储），在配对时将其统一转换为字符串进行匹配。

### [Medium] 挑战点 4：移动端网络切换/静默丢包导致的 SSE 僵尸连接 (Zombie Connection)

- **被挑战的假设**：假设设置 `readTimeout(0)` 可以无限期安全地等待 SSE 事件推送，且底层的 TCP 连接在网络故障时一定会立刻被感知关闭。
- **攻击/失效场景**：
  `McpManager.kt` 使用的 OkHttpClient 的 `readTimeout` 被设为 `0`（无限等待）。在移动端网络环境下，如果设备进入信号盲区（如电梯）或发生 Wi-Fi/移动数据静默切换，TCP 连接可能会在没有任何 FIN/RST 报文的情况下中断（“半开连接”）。
  由于 `readTimeout` 为 0，且没有实现应用层心跳机制（Keep-Alive / Ping-Pong），客户端无法得知底层连接已经死亡，`McpClient` 的状态仍保持在 `CONNECTED`，但实际上它不会收到任何推送事件。当大模型尝试调用工具时，发送的请求将在 `sendRequest` 中挂起 15 秒超时失败，而客户端在此期间不会触发任何重连逻辑。
- **波及范围**：在移动端网络抖动或环境变化时，导致 MCP 连接处于“僵尸”在线状态，无法自愈，用户工具调用持续超时失败，必须手动开关 App 或配置才能恢复。
- **缓解建议**：
  在客户端配置合理的 `readTimeout`（如 30-60 秒），或者开启 OkHttpClient 的 `pingInterval`（如果服务端支持对应的 ping-pong），或者在应用层实现超时心跳检测。

### [Low] 挑战点 5：服务端名称清理导致的前缀路由碰撞与覆盖

- **被挑战的假设**：假设所有 MCP 服务端在进行非字母数字替换后，名称依然是唯一的。
- **攻击/失效场景**：
  `McpManager.kt` 在聚合和路由工具时，采用以下方式对服务端名称进行清理：
  ```kotlin
  val prefix = client.config.name.replace(Regex("[^a-zA-Z0-9_]"), "_")
  ```
  如果用户添加了两个名字为 `Server-A` 和 `Server_A` 的服务端，清理后的 `prefix` 都会变成 `Server_A`。
  如果这两个服务端提供同名的工具（如 `get_weather`），聚合出的工具名均为 `Server_A__get_weather`。在调用分发时：
  ```kotlin
  val client = activeClients.values.find {
      it.config.name.replace(Regex("[^a-zA-Z0-9_]"), "_") == serverPrefix
  }
  ```
  该逻辑只会找到 Map 中顺序靠前的第一个客户端，而第二个服务端的同名工具将被完全遮蔽，无法被正确调用。
- **波及范围**：当用户添加了名称仅有特殊字符差异的多服务端，且其上有同名工具时，会导致工具路由错误，调用被分发到错误的服务器。
- **缓解建议**：
  使用服务器的唯一 `id`（如 UUID）来生成工具前缀，或者在检测到清理后的前缀碰撞时在 UI/管理层抛出命名冲突警告，防止重复。

## 压力/对抗测试结果 (Stress Test Results)

- **场景 1：协程取消测试**
  - **预期行为**：取消 `startConnectionLoop` 的 Job 后，`connect()` 立即停止，且不执行后续重连的 delay 计数。
  - **预测行为**：`connect()` 捕获 `CancellationException` 并返回 `false`，重连循环代码多执行一次 attempt 递增和重连日志打印，直到 `delay` 挂起时才检测到取消并退出。
  - **判定**：FAIL
- **场景 2：并发连接请求**
  - **预期行为**：并发调用 `connect()` 时只有一个连接被发起，另一个连接直接返回状态结果，无资源泄露。
  - **预测行为**：产生两个 EventSource 实例，其中一个丢失引用导致套接字泄露；旧实例触发的断开事件会强制摧毁新连接的状态。
  - **判定**：FAIL
- **场景 3：数值型 ID 响应测试**
  - **预期行为**：对于标准响应 `{"jsonrpc":"2.0", "id": 42, ...}`，客户端能正确配对并唤醒挂起的工具调用。
  - **预测行为**：GSON 抛出 `JsonSyntaxException`，被静默捕获，连接在此次调用中挂死 15 秒直至超时报错。
  - **判定**：FAIL

## 未挑战区域 (Unchallenged Areas)

- **UI 界面动画与渲染** — 由于本次测试为单元测试及对抗分析，未涉及 UI 图层的手势交互与帧率压力测试。
- **Watch 硬件感知交互** — 物理传感器与 WearOS 模块的实际传感器精度、数据漂移在目前阶段属于模拟器/桩模块，未进行真实硬件层的抗干扰测试。
