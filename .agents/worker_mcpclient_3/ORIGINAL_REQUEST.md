## 2026-06-11T17:05:32+08:00
你是一个安全与并发加固开发实施代理（Worker）。你的工作目录是 D:\CodingProjects\Android\Loyea\.agents\worker_mcpclient_3。
你的任务是：针对 D:\CodingProjects\Android\Loyea\.agents\orchestrator\context.md 中终期修复列表列出的 6 个高危安全、并发与稳定性缺陷进行代码加固修复：

1. **ACCESS_NETWORK_STATE 权限缺失**：在 `app/src/main/AndroidManifest.xml` 中添加 `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`，确保网络状态监测具备运行期权限。
2. **SSRF 相对协议绕过漏洞**：在 `McpClient.kt` 校验 `messageEndpoint` 时，拒绝以 `//` 开头的 URL（或者对于相对协议 `//` 补齐 `http:` 协议头后，再次做 Host & Port 与原始 `sseUrl` 的同源检验，只有同源才能发送请求），严防敏感数据发送到第三方恶域。
3. **connect() 取消及 handleDisconnect() 竞态/死锁与状态同步**：
   - 在 `connect()` 中抛出任何异常（包括 `CancellationException` 协程取消）时，都在 throw 之前调用 `handleDisconnect()` 清理连接状态并 cancel 当前 `eventSource`。
   - 对 `connect()` 与 `handleDisconnect()` 进行互斥锁保护，或确保状态置换的线程原子性，防止最终状态错乱 and 重连死锁。
4. **会话 JSON 前后台并发读写冲突与消息覆盖丢失**：
   - 并发写锁：在 `ChatStorageManager.kt` 的读写方法中，引入 `Mutex` 锁保护 JSON 缓存文件和 metadata 文件的操作，实现并发读写互斥安全。
   - 覆盖漏洞：在 ViewModel 保存消息前，应先从本地磁盘读取最新的消息列表与内存增量合并后再写入，防止后台 GreetingWorker 写入的消息直接被 ViewModel 内存旧消息完全覆盖抹除。
5. **UI 并发双发拦截**：在 `ChatScreen.kt` 的发送栏及回车点击逻辑中，增加 `isThinking` 和 `isMcpRunning` 校验。如果为 true，则在 Compose 界面上置灰并禁用发送按钮 and 输入框的回车键。
6. **LlmClient 流式连接泄露**：在 `LlmClient.kt` 的 SSE 流式/非流式请求中，加入 `try-finally` 或 `use { ... }` 机制，确保无论是正常读取完成、发生异常还是协程被取消，`Response.body?.close()` 都会被调用释放底层连接。

MANDATORY INTEGRITY WARNING：
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A Forensic Auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

在修复完成后，请在你的工作目录下撰写 changes.md 记录修改，并提交 handoff.md 汇总单元测试运行命令和结果。
请严格使用中文回复，严禁使用英文。
