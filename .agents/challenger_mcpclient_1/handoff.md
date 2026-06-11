# 交接报告 (handoff.md)

## 1. 观测 (Observation)

我们对 `D:\CodingProjects\Android\Loyea` 进行了详细的代码和环境观测，获取了以下一手数据：
- **文件与路径**：
  - `McpClient.kt` 位于 `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\mcp\McpClient.kt`
  - `McpManager.kt` 位于 `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\mcp\McpManager.kt`
  - `JsonRpc.kt` 位于 `D:\CodingProjects\Android\Loyea\app\src\main\java\com\loyea\mcp\JsonRpc.kt`
  - `McpRoutingTest.kt` 位于 `D:\CodingProjects\Android\Loyea\app\src\test\java\com\loyea\mcp\McpRoutingTest.kt`
- **执行命令与错误**：
  - 我们尝试在根目录下执行 `.\gradlew test` 以运行本地单元测试，但终端执行请求在等待用户授权时超时，错误如下：
    ```
    Permission prompt for action 'command' on target '.\gradlew test' timed out waiting for user response.
    ```
    由于权限限制与网络限制，后续未再发起相同的终端命令，所有验证均基于深度的静态代码审计与对抗点推演。

## 2. 推理链 (Logic Chain)

基于对上述文件的静态审计，我们推导出了以下安全与并发风险链条：
1. **协程取消吞没**：在 `McpClient.kt` 第 127 行起：
   ```kotlin
   } catch (e: Exception) {
       Log.e(TAG, "Connection failed for ${config.name}", e)
       handleDisconnect()
       false
   }
   ```
   因为 `CancellationException` 是 `Exception` 的子类，任何协程取消信号（如外部 Scope 取消或超时）都会在此处被静默捕获并返回 `false`。这直接打破了 Kotlin 协程的结构化并发原则，使得 Job 取消时调用方无法立即获知，甚至会继续执行后续重连的无效日志和计数操作。
2. **并发连接覆盖与套接字泄露**：在 `McpClient.kt` 中：
   - 第 41-47 行没有锁保护状态校验，导致并发调用 `connect()` 可以通过 `DISCONNECTED` 校验并同时进入连接逻辑。
   - 第 85 行：`eventSource = factory.newEventSource(request, listener)`
     新连接的实例直接覆盖了成员变量 `eventSource`，造成前一次未完成或已连接的套接字被丢弃，造成连接/套接字泄露。且旧监听器中的 `onFailure`（第 79 行）被触发时会调用 `handleDisconnect`，这会直接取消新连接上的所有挂起请求并使新连接强行中断。
3. **数字 ID 解析失败**：在 `JsonRpc.kt` 第 14 行：
   `val id: String?`
   与 `McpClient.kt` 第 156-160 行：
   `val response = gson.fromJson(data, JsonRpcResponse::class.java)`
   当标准的 MCP 服务端使用数字类型的 `id`（例如 `id: 1`）响应时，GSON 会因为期望 `String` 但拿到 `NUMBER` 而抛出 `JsonSyntaxException`。该异常在 `handleMessage` 被 `catch` 后吞掉，使得 `pendingRequests` 中对应的 `CompletableDeferred` 永远处于挂起状态，导致工具调用卡死 15 秒并抛出超时异常。
4. **移动端僵尸连接**：在 `McpManager.kt` 第 25-29 行：
   OkHttpClient 的 `readTimeout` 被设置为 `0`。在 Android 移动网络切换、进出电梯发生静默丢包时，TCP 链路由于没有 FIN/RST 报文会变为半开状态。由于没有配置超时和心跳机制，套接字将无限期阻塞在读取事件上，导致 `McpClient` 的状态始终保持为 `CONNECTED`，无法自动重连。

## 3. 注意事项 (Caveats)

- 本次测试由于终端执行超时，未能通过 `.\gradlew test` 的实时运行输出进行实时的测试断言覆盖率统计。
- 目前的单元测试文件 `McpRoutingTest.kt` 和 `McpConfigStorageTest.kt` 并没有覆盖上述的并发 `connect`、协程取消异常吞没、数值 `id` 解析以及静默断网等异常边界，测试用例均基于 Mock 成功路径。

## 4. 结论 (Conclusion)

- 本地单元测试文件经静态审计，在成功路径下逻辑正确，符合当前 Milestone 1 的设计要求。
- 然而，在对抗性分析中，实现代码中存在 **协程取消吞没**、**并发连接导致 SSE 套接字泄露**、**JSON-RPC 数字 ID 兼容性崩溃导致挂起**、**移动端僵尸连接** 等 4 处高/中风险设计缺陷，亟需修复以确保生产环境的稳定与标准 MCP 协议的兼容性。

## 5. 验证方法 (Verification Method)

1. **协程取消验证**：
   在 `McpRoutingTest.kt` 中编写一个测试，在一个被取消的 `CoroutineScope` 中运行 `McpClient.connect()`。验证它是否会立刻抛出 `CancellationException`。如果它返回 `false` 且不抛出异常，说明发生了协程取消吞没。
2. **数字 ID 解析验证**：
   在单元测试中调用 `McpClient.handleMessage` 并传入带有数字 ID 的响应文本（例如 `{"jsonrpc":"2.0","id":1,"result":{}}`）。检查 `pendingRequests` 中对应的 `Deferred` 是否能正常完成。如果发生挂起超时，说明反序列化存在兼容性问题。
3. **并发连接验证**：
   在同一个 `McpClient` 实例上同时并发启动 2 个 `connect()` 协程，检查底层的 `eventSource` 实例是否被重复创建，以及其中一个的断开事件是否会影响另一个。
