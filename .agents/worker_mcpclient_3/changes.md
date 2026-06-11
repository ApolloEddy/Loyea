# 代码加固修复记录

针对终期修复列表列出的 6 个高危安全、并发与稳定性缺陷，已完成以下加固修复工作：

## 1. ACCESS_NETWORK_STATE 权限缺失
*   **修改文件**：`app/src/main/AndroidManifest.xml`
*   **修复内容**：添加了 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`。
*   **设计决策**：确保网络状态监测具备必要的运行期权限，防范在 Android 11+ 上发生权限缺失崩溃或网络状态不可达的隐患。

## 2. SSRF 相对协议绕过漏洞
*   **修改文件**：`app/src/main/java/com/loyea/mcp/McpClient.kt`
*   **修复内容**：在解析和校验 `messageEndpoint` 时，显式拦截并拒绝以 `//` 相对协议开头的端点重定向，抛出 `SecurityException`。
*   **设计决策**：阻断攻击者通过 `//attacker.com` 相对协议绕过同源 host/port 校验，防止敏感信息与请求外泄。

## 3. connect() 取消及 handleDisconnect() 竞态/死锁与状态同步
*   **修改文件**：`app/src/main/java/com/loyea/mcp/McpClient.kt`
*   **修复内容**：
    *   在 `connect()` 的 `try-catch(t: Throwable)` 块中拦截所有异常与协程取消事件，确保在 throw 异常或 CancellationException 前调用 `handleDisconnect()` 清理连接状态。
    *   为 `connect()` 的状态流转与 `handleDisconnect()` 的断开清理逻辑引入 `synchronized(this)` 线程级互斥保护。
    *   在 `connect()` 最终状态变更时进行严格检查，如中途已被 concurrent 断开则不设为 `CONNECTED`，规避重连死锁与最终状态错乱。
    *   在 `eventSource` 实例化后通过 synchronized 块校验状态，如已被断开则立即关闭连接。

## 4. 会话 JSON 前后台并发读写冲突与消息覆盖丢失
*   **修改文件**：
    *   `app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt`
    *   `app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt`
*   **修复内容**：
    *   在 `ChatStorageManager.kt` 中引入 `Mutex` 锁（`sessionsMutex`、`messagesMutex`、`cardsMutex`）保护 JSON 缓存文件和 metadata 文件的读写操作。
    *   重构 `ChatStorageManager`，将底层读写提取到 private 不带锁的 Internal 方法中，公有方法通过 `runBlocking` 调用 Internal 方法并配合 `Mutex.withLock` 进行控制，防止内部互相调用产生不可重入死锁。
    *   在 `ChatViewModel.kt` 的 `sendMessage()`、`toggleThoughtsExpanded()`、`saveMessagesAsync()` 等写盘操作前，采用协程挂起并在 Dispatchers.IO 线程先从磁盘读取最新的 `session_xxx.json` 消息。
    *   实现双端消息 ID 级去重合并（以内存修改为最新版本、以 LinkedHashMap 保证原相对时间线顺序），并将合并后的完整历史回写 UI 状态及磁盘。
*   **设计决策**：彻底避免了后台 `GreetingWorker` 与前台 ViewModel 在读写 metadata 和消息 JSON 时的冲突，并防止 ViewModel 的旧内存数据完全抹除覆盖后台 Worker 写入的问候语。

## 5. UI 并发双发拦截
*   **修改文件**：
    *   `app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt`
    *   `app/src/main/java/com/loyea/ui/chat/ChatScreen.kt`
    *   `app/src/main/java/com/loyea/ui/main/MainScreen.kt`
    *   `app/src/main/java/com/loyea/MainActivity.kt`
*   **修复内容**：
    *   在 `ChatViewModel.kt` 中声明 `isMcpRunning` 状态流，包裹 `runToolCallLoop` 并在执行时自动更新此状态。
    *   层层透传 `isMcpRunning` 与 `isThinking` 到 `ChatScreen` 和 `ChatInputBar` 中。
    *   在 `ChatInputBar` 中，当 `isThinking` 或 `isMcpRunning` 为 true 时，使发送按钮处于 Disabled 置灰状态，并拦截 BasicTextField 的 Enter 回车键（通过 `onPreviewKeyEvent` 拦截以及 `keyboardActions` 禁用）。
*   **设计决策**：防范用户在 AI 思考或 MCP 执行期间快速重复点击发送造成数据重复或状态错乱。

## 6. LlmClient 流式连接泄露
*   **修改文件**：`app/src/main/java/com/loyea/ui/chat/LlmClient.kt`
*   **修复内容**：在 `sendChatCompletionStream` SSE 响应中，使用 OkHttp 响应实体的 `.use { ... }` 机制包装 `client.newCall(request).execute()`。
*   **设计决策**：确保连接在抛出异常、正常解析完成或协程被取消等所有情况下都能 100% 自动关闭底层连接，防范套接字句柄和连接池泄漏。
