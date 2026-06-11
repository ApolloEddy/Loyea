## 2026-06-11T17:24:56+08:00
你是一个代码审查代理（Reviewer 1）。你的工作目录是 D:\CodingProjects\Android\Loyea\.agents\reviewer_mcpclient_1_gen4。
请对 `worker_mcpclient_4` 进行的最终安全与并发机制重构加固代码进行审查。重点核对：
1. `ChatStorageManager.kt` 中的 `Mutex` 锁是否已改为全局伴生单例，以消除前后台多实例锁失效的漏洞。
2. `ChatStorageManager.kt` 对外接口是否彻底升级为 `suspend` 函数且移除了 `runBlocking` 以防主线程 ANR。
3. 新增 of `updateSessionMessages` 和 `updateSessionList` 原子更新闭包是否真正实现了“读-改-写”的原子锁事务。
4. `McpClient.kt` 中的 SSRF 强校验是否先用 `toHttpUrlOrNull()` 和 `resolve` 获取最终 `HttpUrl` 对象，再与 `sseUrl` 强比对 `host` 和 `port`，以防大小写绕过和相对路径绕过。
5. `McpClient.kt` 中 `handleDisconnect` 连接断开时是否已遍历并利用 `completeExceptionally` 异常结束并释放所有挂起的 pendingRequests 以防死锁。
6. `ChatViewModel.kt` 的 `sendMessage` 是否在第一行同步置 `_isThinking.value = true` 以防挂起期间双击发送。
在你的工作目录下撰写 review.md 并提交 handoff.md 汇总审查 verdict (PASS 或 FAIL) 和反馈意见。
请严格使用中文回复，严禁使用英文。
