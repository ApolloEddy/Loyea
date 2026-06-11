# 代码改动说明 (Changes)

本项目对 MCP 客户端（`McpClient.kt`）、存储管理器（`ChatStorageManager.kt`）、会话视图模型（`ChatViewModel.kt`）以及后台工作器（`GreetingWorker.kt`）进行了深入的最终安全与并发机制重构加固。以下是具体改动点：

## 1. AndroidManifest.xml 权限补齐
- **验证结论**：经检查，`app/src/main/AndroidManifest.xml` 中已声明 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`，无需额外补齐。

## 2. McpClient.kt 安全与并发加固
- **SSRF 防御终极加固**：
  - 彻底移除了不安全的 `startsWith` 大小写敏感过滤。
  - 改为使用 `okhttp3.HttpUrl` 的解析机制：先将 `endpoint` 解析为绝对路径，若失败则利用 `parsedSseUrl.resolve(endpoint)` 获得最终解析出的绝对 `HttpUrl` 对象。
  - 对最终得到的 `HttpUrl` 与原始 `sseUrl` 进行主机（`host`）和端口（`port`）强比对。若不一致，则立即抛出 `SecurityException`，从根本上防止了 SSRF 协议绕过。
- **Volatile 共享变量**：
  - 为跨线程/协程共享的 `messageEndpoint` 和 `endpointDeferred` 变量添加了 `@Volatile` 标志，以提供内存屏障和可见性保障。
- **防止连接断开重连死锁**：
  - 在 `handleDisconnect()` 方法中，遍历并异常结束 `pendingRequests` 中的所有挂起请求，调用 `deferred.completeExceptionally(IOException("Disconnected"))` 释放挂起请求，防止由于重连导致旧请求卡死及死锁。

## 3. ChatStorageManager.kt 全局静态锁与挂起重构
- **静态伴生锁**：
  - 将原来的 `sessionsMutex`、`messagesMutex`、`cardsMutex` 从实例变量移入 `companion object`（伴生对象）成为静态单例。使得前台 ViewModel 和后台 Worker 的多实例共享同一把全局静态排他锁。
- **非阻塞挂起改造**：
  - 将所有暴露的公有 API（如 `loadSessionMessages`、`saveSessionMessages`、`loadSessionList` 等）全部改为 `suspend` 函数，并移除了原先存在的所有 `runBlocking` 调用，确保不会发生阻塞主线程的情况。
- **原子更新接口**：
  - 提供了 `updateSessionMessages` 和 `updateSessionList` 两个事务型更新接口。内部在 Mutex 排他锁的保护下，原子化地完成 `load` -> `updateBlock` 传入闭包处理 -> `save` 的全过程，解决了并发竞态问题。

## 4. ChatViewModel.kt 读写原子更新与发送拦截前置
- **读写原子更新重构**：
  - 重构了 `mergeAndSaveMessages` 方法，使其调用存储管理器的 `updateSessionMessages` 挂起方法，在锁内部原子化完成读取-去重合并-写入的全过程。
  - 将 `deleteSession`、`toggleCurrentSessionSystemTime`、`updateSessionTitleIfNeeded` 重构为通过协程机制（`Dispatchers.IO` 与 `Dispatchers.Main` 切换）调用 `updateSessionList` 和 `updateSessionMessages` 进行原子读写更新，避免数据在 UI 与后台 Worker 间被过期覆盖抹除。
  - 将初始化加载数据（如 `loadCharacterCards` 和 `loadSessions`）以及 `selectSession` 中的消息加载改为在 `viewModelScope` 协程中异步执行，不阻塞主线程。
- **UI 发送状态前置拦截**：
  - 在 `sendMessage` 的第一行同步执行 `_isThinking.value = true`，立刻置灰锁定发送按钮和输入拦截，防范网络及 IO 挂起期间由于用户双击导致的重复发送与请求。

## 5. GreetingWorker.kt 读写原子更新重构
- **防数据擦除覆盖**：
  - 检查并重构了后台 Worker 中读写会话列表和消息文件的操作。
  - 删除了直接调用 `saveSessionMessages` 和 `saveSessionList` 的操作，改为通过 `updateSessionMessages` 和 `updateSessionList` 接口进行原子更新。这保证了在锁的范围内完成修改，防止后台工作器把用户在前台 UI 上最新的增删改操作进行过期覆盖抹除。

## 6. 新增单元测试
- **ChatStorageManagerTest.kt**：
  - 在 `app/src/test/java/com/loyea/ui/chat` 目录下新增了针对 `ChatStorageManager` 的单元测试，验证原子更新会话列表和原子更新会话消息功能在协程中的正确性与可靠性。
