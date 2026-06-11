# MCP Client 代码审查报告 (review.md)

## 审查总结 (Review Summary)

**最终结论 (Verdict)**: FAIL (REQUEST_CHANGES)

根据对 `worker_mcpclient_0` 提交的代码的静态审查，依赖引入、损坏自愈、UI 美学与 Compose 动画实现均达到了极高的质量。但是在协程挂起、退避算法与多服务器配置管理中，发现了 2 个 Major 级别的逻辑缺陷和 3 个 Minor 级别的性能/规范问题。为了保证 MCP 模块的绝对健壮性，建议 verdict 设为 FAIL 并退回修改。

---

## 发现问题 (Findings)

### [Major] 发现 1: 指数退避 Jitter（抖动）计算公式存在负数不对称漏洞
- **什么问题**: 在 `McpManager.startConnectionLoop` 中，随机抖动（Jitter）的计算公式存在数学逻辑漏洞，导致抖动不对称且平均延迟偏低。
- **具体位置**: `McpManager.kt` 第 185-188 行：
  ```kotlin
  val jitterRange = (baseDelay * 0.1).toLong()
  val jitter = if (jitterRange > 0) random.nextLong() % (2 * jitterRange) - jitterRange else 0L
  val delayTime = (baseDelay + jitter).coerceAtLeast(1000L)
  ```
- **原因分析**: `random.nextLong()` 会生成负数。在 Java/Kotlin 中，对负数取余数结果仍为负数或零。当为负数时，`random.nextLong() % (2 * jitterRange)` 落在 `(-2 * jitterRange, 0]` 区间，再减去 `jitterRange` 后落在 `(-3 * jitterRange, -jitterRange]` 区间（即减少延迟 10% 到 30%）。当为正数时，结果落在 `[-jitterRange, jitterRange)` 区间（即减少或增加延迟 10%）。
  这导致整体抖动区间为 `-30%` 到 `+10%`，平均抖动为 `-10%`，背离了对称抖动（`+/- 10%`）的初衷，且重连间隔平均缩短了 10%，增加了请求拥堵的风险。
- **整改建议**: 使用 `random.nextDouble()` 替代 `random.nextLong()` 来生成对称的抖动范围：
  ```kotlin
  val jitter = if (jitterRange > 0) (random.nextDouble() * 2 * jitterRange).toLong() - jitterRange else 0L
  ```

### [Major] 发现 2: 更新配置时仅修改别名无法即时生效的漏洞
- **什么问题**: 当用户在设置界面仅修改 MCP 服务端的“别名”（name）而不修改 URL 时，更新后的别名不会应用到活动的客户端中，导致工具名前缀路由与 UI 显示不一致。
- **具体位置**: `McpManager.kt` 第 126-128 行：
  ```kotlin
  val existingClient = activeClients[config.id]
  if (existingClient == null || existingClient.config.sseUrl != config.sseUrl) {
      // 重建客户端...
  }
  ```
- **原因分析**: 判断条件仅检查了 `existingClient == null` 或 `sseUrl` 变更。如果用户仅在 UI 中修改了服务端别名（例如从 "ServerA" 改为 "ServerB"），由于 `sseUrl` 未变，已有的 `McpClient` 实例不会被重新创建。而在聚合工具 `getAggregateTools()` 时，前缀仍然使用 client 中缓存的旧别名。这就导致修改别名后，工具调用仍然必须使用旧别名作为前缀，直到应用重启。
- **整改建议**: 将判断条件修改为检查整个 `config` 对象是否发生变化：
  ```kotlin
  if (existingClient == null || existingClient.config != config) { ... }
  ```

### [Minor] 发现 3: McpClient.connect 吞掉协程取消异常 (CancellationException)
- **什么问题**: 在 `McpClient.connect` 的异常捕获中，直接使用了 `catch (e: Exception)`，这会无意中捕获并吞掉 `CancellationException`，破坏协程的取消传播机制。
- **具体位置**: `McpClient.kt` 第 127-131 行：
  ```kotlin
  } catch (e: Exception) {
      Log.e(TAG, "Connection failed for ${config.name}", e)
      handleDisconnect()
      false
  }
  ```
- **原因分析**: 在 Kotlin 协程中，超时（`withTimeout`）或作用域取消会抛出 `CancellationException`。如果将其吞掉并返回 `false`，协程的结构化并发取消逻辑将无法正常向上层传递。
- **整改建议**: 在 `catch` 块中判断如果是 `CancellationException` 则直接重新抛出：
  ```kotlin
  } catch (e: Exception) {
      if (e is CancellationException) throw e
      Log.e(TAG, "Connection failed for ${config.name}", e)
      handleDisconnect()
      false
  }
  ```

### [Minor] 发现 4: sendRequest 中的 OkHttp 同步阻塞调用不可取消
- **什么问题**: `McpClient.sendRequest` 中在 `Dispatchers.IO` 上直接使用了同步的 `okhttpClient.newCall(httpRequest).execute()`。
- **具体位置**: `McpClient.kt` 第 181 行。
- **原因分析**: 同步的 `execute()` 是阻塞的，在协程被取消时（例如 `sendRequest` 发生超时），该阻塞的底层网络连接无法立即被中断或释放。
- **整改建议**: 推荐使用 `suspendCancellableCoroutine` 封装 OkHttp 的异步 `enqueue`，并在 `invokeOnCancellation` 中调用 `call.cancel()`，以支持真正的协程协同取消。

### [Minor] 发现 5: MainActivity 中 top-level 状态读取导致全局不必要重构
- **什么问题**: `MainActivity.kt` 在 `setContent` 的顶层读取了 ViewModel 的所有 `MutableState.value`。
- **具体位置**: `MainActivity.kt` 第 43-57 行。
- **原因分析**: Compose 的重构范围是基于状态读取的位置确定的。在 `MainActivity` 顶层读取所有状态，意味着一旦有任何一个小状态改变（例如大模型在流式输出、打字机效果、或是思考计时器在跳动），整个 `MainActivity` 及其底下的 `NavHost`、`MainScreen`、`SettingsScreen` 都会发生全局重构，这属于 Compose 性能反模式。
- **整改建议**: 尽量将状态读取下移到具体的叶子 Composable 中，或者在顶层传递 State 包装，避免在最外层直接解包读取 `.value`。

---

## 已验证的声明 (Verified Claims)

1. **`app/build.gradle.kts` 中引入依赖是否正确**：
   - 引入了 `com.squareup.okhttp3:okhttp:4.12.0` 与 `com.squareup.okhttp3:okhttp-sse:4.12.0`。
   - 引入了 Mockito 相关的单元测试库。
   - 验证结论：**验证通过**。依赖完整且版本对齐，支持 SSE 机制。

2. **`McpConfigStorage.kt` 损坏自愈逻辑是否无缝**：
   - 验证方法：走查 `loadConfigs` 中的 `try-catch` 反序列化防崩设计，并检查 `testLoadConfigsSelfHealingOnCorruptedJson` 单元测试。
   - 验证结论：**验证通过**。当检测到损坏 JSON 数据时，它能够通过 `prefs.edit().remove(key).apply()` 自动擦除损坏的脏数据并返回 `emptyList()`，完全避免了应用启动闪退。

3. **`SettingsScreen.kt` 的莫兰迪美学及展开折叠 Compose 动画细节**：
   - 验证方法：检查 `SettingsScreen.kt` 中的配色和动画参数。
   - 验证结论：**验证通过**。
     - 配色：已连接（`0xFF84A98C` - 莫兰迪绿）、连接中（`0xFFEADFD3` - 琥珀黄）、已断开（`0xFF9E998F` - 灰色）和删除按钮（`0xFFC97A7A` - 莫兰迪红），完全符合莫兰迪美学。
     - 动画：展开时使用了带有弹性阻尼（`Spring.DampingRatioLowBouncy`）的 `expandVertically` 与 `fadeIn` 组合，非常顺滑自然；二级页面的推拉过场也极为优雅。

4. **`McpClient` 与 `McpManager` 协程挂起与指数退避及网络监听设计**：
   - 验证结论：**部分通过**。协程挂起匹配 UUID 能够很好地将异步 SSE 转为挂起调用；网络状态通过注册 `NetworkCallback` 结合 `_isNetworkAvailableFlow.first { it }` 实现了完美地挂起与按需唤醒；但存在上述 Jitter 计算错误与别名更新不即时等重大功能性缺陷（见 Findings）。
