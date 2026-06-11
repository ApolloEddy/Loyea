## 2026-06-11T09:19:57Z
针对 D:\CodingProjects\Android\Loyea\.agents\orchestrator\context.md 中的 Synthesis 待修复列表，对 MCP 客户端及会话存储设计进行深度的最终安全与并发机制重构加固：

1. **AndroidManifest.xml 权限补齐**：确认 `app/src/main/AndroidManifest.xml` 中已声明了 `ACCESS_NETWORK_STATE` 权限。若无，请补齐。
2. **SSRF 协议绕过终极加固**：修改 `McpClient.kt`，彻底放弃不严密的大小写敏感 `startsWith` 过滤。先用 `endpoint.trim().toHttpUrlOrNull()` 尝试解析绝对路径；如果解析失败，再使用 `parsedSseUrl.resolve(endpoint.trim())` 得到最终解析的 `HttpUrl` 对象。获得最终的 `HttpUrl` 对象后，直接与原始 `sseUrl`（即 `parsedSseUrl`）的 `host` 和 `port` 进行同源强比对，不一致则立即抛出 `SecurityException`！
3. **连接断开 pendingRequests 释放与 Volatile 变量**：
   - 跨线程和跨协程的共享变量（如 `endpointDeferred`、`messageEndpoint`）加上 `@Volatile` 标志以提供内存屏障 and 可见性。
   - 在 `handleDisconnect()` 中，增加遍历清空并异常结束所有的 pendingRequests，调用 `deferred.completeExceptionally(IOException("Disconnected"))` 释放所有挂起请求，防止重连死锁。
4. **ChatStorageManager 全局静态排他锁与非阻塞挂起改造**：
   - 伴生锁：在 `ChatStorageManager.kt` 中将 `Mutex`（包括 `sessionsMutex`，`messagesMutex`，`cardsMutex`）移入 `companion object`（伴生对象）成为静态单例，使得前台 ViewModel 和后台 Worker 的多实例共享同一把全局锁。
   - 非阻塞挂起：将所有暴露的公有 API（如 `loadSessionMessages`，`saveSessionMessages` 等）改为 `suspend` 函数，完全移去 `runBlocking`，确保不阻塞主线程。
   - 原子读写事务：提供两个原子化的更新接口 `updateSessionMessages` 和 `updateSessionList`，内部在对应的 Mutex 排他锁保护下原子完成 `load` -> 传入闭包处理 -> `save` 的全过程。
5. **ViewModel 与后台 GreetingWorker 读写重构（防数据擦除覆盖）**：
   - 重构 `ChatViewModel.kt` 的 `mergeAndSaveMessages` 方法，使其调用存储管理器的 `updateSessionMessages` 挂起方法，在锁内部原子化完成读取-去重合并-写入的全过程。
   - 检查并重构 `GreetingWorker.kt` 中读写会话列表和消息文件的操作，确保其也通过 `updateSessionList` 和 `updateSessionMessages` 接口进行原子更新，坚决防止后台 Worker 将用户在 UI 上的最新增删改进行过期覆盖抹除。
6. **UI 发送状态前置拦截**：在 `ChatViewModel.sendMessage` 的首行（开始任何 IO、写盘及网络异步任务前），同步执行 `_isThinking.value = true`，立刻置灰锁定发送按钮和输入拦截，防范挂起期间双击。

在开发加固完成后，请在你的工作目录下撰写 changes.md 和 handoff.md 记录修改，并验证项目全量单元测试编译及跑通。
请严格使用中文回复，严禁使用英文。
