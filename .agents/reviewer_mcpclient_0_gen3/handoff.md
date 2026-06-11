# 交接报告 (Handoff Report)

## 1. 观察 (Observation)

通过对项目文件进行细致的代码审查，我们直接观察到以下具体实现：

1. **AndroidManifest.xml 权限声明**：在 `app/src/main/AndroidManifest.xml` 中第 5 行：
   ```xml
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```
   权限声明正确补齐。
2. **McpClient.kt SSRF 校验**：在 `app/src/main/java/com/loyea/mcp/McpClient.kt` 第 124 行：
   ```kotlin
   val resolvedEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) { ... }
   ```
   此校验为大小写敏感匹配。同时在第 121 行检查了 `endpoint.trim().startsWith("//")`。
3. **McpClient.kt connect 与 disconnect 锁机制**：在 `McpClient.kt` 中引入了 `connectMutex` (协程 Mutex) 以及多处对 `synchronized(this)` (JVM 锁) 的复合使用。在 `connect()` 的 `catch` 块中捕获了 `t: Throwable` 并执行 `handleDisconnect()`，如果是 `CancellationException` 则会进行 rethrow。
4. **ChatStorageManager.kt 的并发锁声明**：在 `app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt` 第 32-34 行定义了实例级锁：
   ```kotlin
   private val sessionsMutex = Mutex()
   private val messagesMutex = Mutex()
   private val cardsMutex = Mutex()
   ```
   而 `ChatViewModel` 和 `GreetingWorker` 中各自创建了不同的 `ChatStorageManager` 实例：
   - `ChatViewModel.kt` 第 30 行：`private val storageManager = ChatStorageManager(context)`
   - `GreetingWorker.kt` 第 38 行：`val storageManager = ChatStorageManager(context)`
5. **ChatViewModel.kt 消息保存合并机制**：在 `ChatViewModel.kt` 中设计了 `mergeAndSaveMessages` 方法，先加载磁盘消息，后通过 `LinkedHashMap` 按照 ID 进行排重合并。
6. **ChatScreen.kt UI 状态禁用拦截**：在 `app/src/main/java/com/loyea/ui/chat/ChatScreen.kt` 中，`isSendDisabled = isThinking || isMcpRunning` 控制了发送按钮是否可以点击，并且通过 `onPreviewKeyEvent` 显式拦截并消耗了 `Key.Enter` 按键事件。
7. **LlmClient.kt HTTP 响应关闭**：在 `app/src/main/java/com/loyea/ui/chat/LlmClient.kt` 中，所有的 OkHttp 调用（`sendChatCompletionStream`、`sendChatCompletion`、`sendRawChatCompletion`）均使用了 `.use { response -> ... }` 包裹。

---

## 2. 逻辑链 (Logic Chain)

根据上述观察，推导出以下逻辑结论：

1. **SSRF 绕过漏洞**：因为 `McpClient.kt` 使用的是 `startsWith("http://")` 大小写敏感前缀判断，如果恶意 MCP 服务端返回 `HTTPS://attacker.com`，该字符串无法通过 `if` 条件，从而漏过同源主机与端口的防御校验。随后在 `else` 中使用 `parsedSseUrl.resolve(endpoint)` 解析后，将生成一个合法的外部恶意地址，从而彻底绕过了 SSRF 的防护逻辑。
2. **多实例并发锁失效**：因为 `ChatStorageManager.kt` 的 Mutex 是实例变量（`private val`），而前台 ViewModel 与后台 Worker 实例化的是两个不同的 `ChatStorageManager` 对象，这两个对象的 Mutex 锁在内存中相互隔离。因此，前后台并发调用 `saveSessionMessages` 时，持有的是各自的 Mutex 锁，无法起到真正的进程内互斥效果。
3. **connect() 取消与死锁防御**：`connectMutex` 和 `synchronized(this)` 没有反向依赖关系，且不包含挂起函数的死锁调用。异常和协程取消均通过 `handleDisconnect()` 进行了正常状态复位，协程取消异常也得到了向上抛出，逻辑正确。
4. **消息丢失防御**：在写入磁盘前，ViewModel 会在 Dispatchers.IO 协程中加载最新磁盘数据并根据 ID 通过 `LinkedHashMap` 合并，这有效地防止了后台 Worker 生成的主动关怀消息被前台覆盖丢失的问题。
5. **UI 按钮与回车拦截**：输入框拦截和回车消费均与 `isMcpRunning` 状态绑定，能够彻底避免工具链路或模型思考期间双击重复发送。
6. **HTTP 连接泄露防御**：`use` 块可以自动在退出时对 Response 调用 `close()`，即使发生抛出异常或协程取消，也能 100% 避免 Socket 连接泄露。

---

## 3. 注意事项 (Caveats)

- **多进程锁限制**：本交接报告基于 Android 单进程运行假设。如果 `GreetingWorker` 被配置在 Android 的独立进程中，即便将 Mutex 改造为伴生对象（静态）锁，依然由于 JVM 进程隔离而失效。通常本应用的 Worker 跑在主进程内，因此该 Caveat 属于理论上的低风险边界条件，但需要记录。

---

## 4. 结论 (Conclusion)

代码加固工作整体质量非常高，但有以下两个关键问题需要修正：
- **SSRF 大小写绕过漏洞 (Critical)**：McpClient.kt 中的前缀同源拦截逻辑不完备。
- **跨组件并发锁失效 (Major)**：ChatStorageManager.kt 的文件读写 Mutex 锁应改造为静态共享锁。

因此，本次代码审查的最终裁决 (Verdict) 为：**REQUEST_CHANGES (FAIL)**。

---

## 5. 验证方法 (Verification Method)

1. **验证大小写 SSRF 绕过**：
   - 查看 `McpClient.kt` 第 120-132 行的逻辑，验证在 `endpoint` 传入 `"HTTPS://attacker.com"` 时，是否能跳过同源检测直接进入 `else` 块解析。
2. **验证并发锁机制**：
   - 查看 `ChatStorageManager.kt` 中 `sessionsMutex`、`messagesMutex`、`cardsMutex` 的定义，确认其是否为普通实例属性，并确认 `GreetingWorker` 和 `ChatViewModel` 是否使用了独立的 `ChatStorageManager` 实例。
