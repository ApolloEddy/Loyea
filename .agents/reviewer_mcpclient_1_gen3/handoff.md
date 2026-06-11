# Handoff Report — 2026-06-11

## 1. 观察 (Observation)

对代码库中的以下关键加固文件进行了全面审查：
1. **AndroidManifest.xml** (`app/src/main/AndroidManifest.xml`):
   在第 5 行观察到网络状态权限声明：
   ```xml
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```
2. **McpClient.kt** (`app/src/main/java/com/loyea/mcp/McpClient.kt`):
   在第 121-132 行观察到 SSRF 检查逻辑：
   ```kotlin
   if (endpoint.trim().startsWith("//")) {
       throw SecurityException("SSRF Detected: Relative protocol '//' is prohibited")
   }
   val resolvedEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
       val parsedEndpoint = endpoint.toHttpUrlOrNull() ?: throw IOException("Invalid endpoint URL: $endpoint")
       if (parsedEndpoint.host != parsedSseUrl.host || parsedEndpoint.port != parsedSseUrl.port) {
           throw SecurityException("SSRF Detected: Redirect host/port (${parsedEndpoint.host}:${parsedEndpoint.port}) does not match sseUrl host/port (${parsedSseUrl.host}:${parsedSseUrl.port})")
       }
       endpoint
   } else {
       parsedSseUrl.resolve(endpoint)?.toString() ?: throw IOException("Failed to resolve endpoint: $endpoint")
   }
   ```
   并在第 176-180 行观察到对 `CancellationException` 异常的捕获与重抛：
   ```kotlin
   } catch (t: Throwable) {
       Log.e(TAG, "Connection failed for ${config.name}", t)
       handleDisconnect()
       if (t is CancellationException) throw t
       false
   }
   ```
3. **ChatStorageManager.kt** (`app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt`):
   在第 32-34 行定义了独立锁，并在对外的保存/读取方法中使用 `runBlocking` 和独立锁：
   ```kotlin
   private val messagesMutex = Mutex()
   ...
   fun saveSessionMessages(sessionId: String, messages: List<Message>) = runBlocking {
       messagesMutex.withLock {
           saveSessionMessagesInternal(sessionId, messages)
       }
   }
   ```
4. **ChatViewModel.kt** (`app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt`):
   在第 770-782 行观察到消息合并逻辑：
   ```kotlin
   private fun mergeAndSaveMessages(sessionId: String, memoryMsgs: List<Message>): List<Message> {
       val diskMsgs = storageManager.loadSessionMessages(sessionId)
       val mergedMap = LinkedHashMap<String, Message>()
       for (msg in diskMsgs) {
           mergedMap[msg.id] = msg
       }
       for (msg in memoryMsgs) {
           mergedMap[msg.id] = msg
       }
       val finalMsgs = mergedMap.values.toList()
       storageManager.saveSessionMessages(sessionId, finalMsgs)
       return finalMsgs
   }
   ```
5. **ChatScreen.kt** (`app/src/main/java/com/loyea/ui/chat/ChatScreen.kt`):
   在 `ChatInputBar` 的 BasicTextField 拦截回车键逻辑中：
   ```kotlin
   modifier = Modifier
       .fillMaxWidth()
       .onPreviewKeyEvent { keyEvent ->
           if (keyEvent.key == Key.Enter) {
               if (isSendDisabled) {
                   true // Consume and block enter key
               } else {
                   false
               }
           } else {
               false
           }
       }
   ```
   且发送按钮的 clickable 配置为 `enabled = isSendClickable`，其中 `isSendClickable` 在 `isThinking || isMcpRunning` 时为 false。
6. **LlmClient.kt** (`app/src/main/java/com/loyea/ui/chat/LlmClient.kt`):
   在第 116 行、第 366 行和第 562 行观察到 OkHttp 的 `execute().use { response -> ... }` 块的使用，确认 response 被正确自动释放。

---

## 2. 逻辑链 (Logic Chain)

根据上述观察，推导出的审查逻辑如下：
1. **AndroidManifest.xml 权限补齐**：直接观察 [观察 1] 确认 `ACCESS_NETWORK_STATE` 权限已经正确声明，无遗漏。
2. **McpClient SSRF 校验与绕过**：
   - 观察到 `endpoint.startsWith("http://")` 使用了 Kotlin 的区分大小写 API，且无 trim 逻辑。
   - 如果恶意输入为 `"HTTP://attacker.com"`，则不满足前缀条件，将进入 `else` 分支 [观察 2]。
   - `else` 分支使用 `parsedSseUrl.resolve("HTTP://attacker.com")`。由于 OkHttp 在 resolve 时 scheme 检查不区分大小写，它会将其作为绝对路径解析为 `http://attacker.com/`。
   - `else` 分支直接返回此解析结果，没有任何 host/port 比对。
   - 这证明了攻击者可以通过混淆大小写（或添加 leading 空白）完全绕过 SSRF 防御，造成敏感数据外泄。结论：**SSRF 防御存在严重设计遗漏，评级为 FAIL**。
3. **McpClient 连接同步与死锁**：
   - `connectMutex` 用于防止并发重连。
   - `connect()` 的 `catch (t: Throwable)` 捕获任何异常并在重抛 `CancellationException` 之前执行了 `handleDisconnect()` [观察 2]，从而保证了连接异常时能释放连接状态。
   - 所有的 `synchronized` 块只包裹同步状态修改和对象更新，且在 `connectMutex` 锁定期间执行，没有反向请求或挂起操作，排除了死锁发生的可能。
4. **并发 Mutex 与数据合并**：
   - `messagesMutex` 的范围局限于 `loadSessionMessages` 和 `saveSessionMessages` [观察 3]。
   - `mergeAndSaveMessages` [观察 4] 并不是原子的：读取锁在 `loadSessionMessages` 完成时释放，修改后在 `saveSessionMessages` 时重新获取写入锁。
   - 如果在读取和写入之间，`GreetingWorker`（同样经历独立的读和写过程）写入了新数据，那么 `ChatViewModel` 的写入将会覆盖并抹除 `GreetingWorker` 写入的消息。
   - 此外，`runBlocking` 的使用会导致主线程被 I/O 阻塞。结论：**并发设计不合理，评级为 FAIL**。
5. **ChatScreen.kt UI 禁用与拦截**：
   - 发送按钮在 `isThinking || isMcpRunning` 时被 clickable 彻底禁用。
   - BasicTextField 里的键盘回车键通过 `onPreviewKeyEvent` 在处于禁用状态时返回 `true` 进行了全面消费与阻断 [观察 5]。
   - 软键盘 IME 发送动作同样绑定了 `isSendClickable` 条件检查。结论：**UI 禁用与拦截正确，评级为 PASS**。
6. **LlmClient.kt Response 释放**：
   - 所有 OkHttp `execute()` 均包裹在 `.use` 块中 [观察 6]，自动释放底层连接。结论：**释放逻辑加固正确，评级为 PASS**。

---

## 3. 局限性与风险 (Caveats)

- 本次审查为静态代码审查，未能成功在本地 Windows 设备上执行 `.\gradlew.bat testDebugUnitTest` 自动化测试进行动态运行时行为校验，主要依赖严密的静态语义推导。
- 假设 `GreetingWorker` 独立于 ViewModel 所在的进程或线程调度，且后台的并发度可能导致更高频次的 I/O 并发争抢。

---

## 4. 结论 (Conclusion)

- **最终结论**: **FAIL / REQUEST_CHANGES (不通过，需要整改)**
- **主要反馈意见**:
  1. 修复 `McpClient.kt` 中的 SSRF 绕过漏洞。请改用先解析最终 URL、再比对 Host 和 Port 的防御方式，而不是通过基于 `startsWith` 的字符串过滤。
  2. 修复 `ChatStorageManager.kt` 与 `ChatViewModel.kt` 消息读写的竞态条件。建议在 `ChatStorageManager.kt` 中提供一个原子化的 `update` 方法（在同一个锁内部完成读-改-写），并且移除 `runBlocking` 的 Android 主线程阻塞调用，采用挂起函数。

---

## 5. 验证方法 (Verification Method)

1. **SSRF 防御校验**：
   - 构造一个恶意的 SSE Server 返回的 endpoint 为 `"HTTP://12.34.56.78/endpoint"` 或 `" http://12.34.56.78/endpoint"`。
   - 观察 `McpClient` 的 `resolvedEndpoint` 是否仍解析为该恶意 IP，如果是，则 SSRF 防御存在绕过缺陷（安全失败）。
2. **并发数据合并校验**：
   - 在 `mergeAndSaveMessages` 的 `loadSessionMessages` 执行后插入延迟（如 `delay(1000)`），在此期间让 `GreetingWorker` 写入一条问候消息。
   - 检查最终磁盘上的会话消息文件，看该问候消息是否被 ViewModel 之后保存的消息覆盖擦除。如果是，则并发逻辑存在漏洞。
3. **Android 编译命令**：
   - 在终端执行 `.\gradlew assembleDebug` 以确保加固重构后项目整体编译无报错。
