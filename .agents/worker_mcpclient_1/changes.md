# Changes — McpClient Milestone 1 缺陷修复与代码加固

针对项目中发现的 9 个核心缺陷，我们已完成一次性完整修复和代码加固，具体修改情况如下：

## 1. 监听协程内存泄漏
- **修改文件**：`McpManager.kt`
- **变更详情**：引入 `statusJobs: ConcurrentHashMap<String, Job>`。在 `stop()` 以及注销/禁用客户端或重建客户端时，对其监听状态的协程进行 `cancel()`，并从 map 移除，避免协程生命周期泄漏。

## 2. Jitter 计算不对称负偏置
- **修改文件**：`McpManager.kt`
- **变更详情**：移除了 `java.util.Random` 实例，将指数退避中的 Jitter 抖动计算公式改为使用 `kotlin.random.Random.nextLong(-jitterRange, jitterRange)`。对于 `jitterRange <= 0` 时保护性返回 `0L`，防范非对称负偏置及崩溃。

## 3. 客户端别名修改不生效
- **修改文件**：`McpManager.kt`
- **变更详情**：修改 `updateConfigs` 逻辑，在对比时加入 `existingClient.config.name != config.name` 条件，如果别名发生改变，也将触发客户端的重建，从而使前缀工具名即时更新。

## 4. 协程 CancellationException 被吞噬
- **修改文件**：`McpClient.kt`
- **变更详情**：在 `connect()` 方法的 `catch (e: Exception)` 块及 `sendRequest` 方法的 `catch (e: Exception)` 块的第一行，增加了 `if (e is CancellationException) throw e` 以恢复结构化并发特性。

## 5. MainActivity 顶层全局重构隐患
- **修改文件**：`MainActivity.kt`
- **变更详情**：去除了 `MainActivity.setContent` 最顶层对状态的直接 `.value` 读取。通过将各路由界面的状态读取下沉至各自 `composable("...")` 的 lambda 内部，并使用委托 `by`（如 `val userName by chatViewModel.userName`）与 Compose 运行时进行绑定，实现了重构范围的局部化隔离，防止 `rememberNavController()` 重新生成导致的状态丢失。

## 6. 并发 connect() 导致 Socket 泄漏
- **修改文件**：`McpClient.kt`
- **变更详情**：在 `McpClient` 中引入 `connectMutex` 保护整个 `connect()` 过程。在创建新的 EventSource 之前显式调用旧 `eventSource?.cancel()`，并对 `endpointDeferred` 进行异常唤醒清理，防止通道重复叠加与 Socket/线程泄漏。

## 7. Gson 无法解析数字 ID 导致挂起崩溃
- **修改文件**：`JsonRpc.kt`、`McpClient.kt`
- **变更详情**：将 `JsonRpcResponse` 的 `id` 属性类型修改为 `com.google.gson.JsonElement?`，并提供 `idAsString` 辅助属性，统一将 Primitive 值转换为 String；同时新增 String 类型的重载构造函数以保证测试用例与历史代码兼容。在 `McpClient.kt` 解析响应时，替换为匹配 `response.idAsString`，规避了由于服务端返回数字 ID 导致解析挂起超时 15 秒的问题。

## 8. SSRF 重定向风险与恶意 JSON 闪退防御
- **修改文件**：`McpClient.kt`
- **变更详情**：
  - 针对绝对路径的 `messageEndpoint` 重定向，校验重定向后的 URL host 与 port 是否与原始配置的 `sseUrl` 相同，实现同源安全性校验（SSRF 防御）。
  - 将 `handleMessage` 的异常捕获范围从 `catch (e: Exception)` 扩大到 `catch (t: Throwable)`，且增加了 Payload 长度限制（超过 10MB 则拦截），防止由于超大 Payload 引起的 OutOfMemoryError 闪退。

## 9. OkHttp 僵尸连接与同步 execute() 线程泄漏
- **修改文件**：`McpManager.kt`、`McpClient.kt`
- **变更详情**：
  - 在 `McpManager.kt` 的 `okhttpClient` 中增加 `pingInterval(30, TimeUnit.SECONDS)`，定期发送心跳规避僵尸长连接。
  - 在 `McpClient.kt` 的 `sendRequest` 与 `sendNotification` 中，使用 `suspendCancellableCoroutine` 对 OkHttp 的异步 `.enqueue()` 调用进行了挂起封装，并在协程被取消时（通过 `invokeOnCancellation` 闭包）调用 `Call.cancel()`，确保底层 Socket 与线程资源完全释放，解决同步 `.execute()` 阻塞导致的线程泄漏问题。
