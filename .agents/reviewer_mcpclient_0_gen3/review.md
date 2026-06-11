# 代码审查报告 — 2026-06-11

## Review Summary

**Verdict**: REQUEST_CHANGES (需要修改)

虽然加固方案在权限配置、OkHttp 响应释放（use 块包装）、UI 双发拦截以及消息去重合并的业务逻辑上做得非常好，但在安全校验与并发锁的设计上存在两个严重的安全与稳定性隐患（SSRF 大小写绕过漏洞、跨实例并发锁失效），必须予以修复。

---

## Findings

### [Critical] Finding 1: McpClient.kt 中存在大小写绕过 SSRF 的安全漏洞

- **What**: McpClient.kt 中同源 host/port 校验可被大小写不同的协议头（如 `HTTPS://`）轻易绕过。
- **Where**: `app/src/main/java/com/loyea/mcp/McpClient.kt` 第 124 行：
  ```kotlin
  val resolvedEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) { ... }
  ```
- **Why**: 该前缀校验是大小写敏感的。如果恶意服务器在 `endpoint` 中返回以大写协议头开头的绝对地址（例如 `HTTPS://attacker.com` 或 `Http://attacker.com`），代码将无法匹配 `startsWith("http://")` 或 `startsWith("https://")`，从而直接跳过 `if` 块中的同源校验，进入 `else` 分支。
  在 `else` 分支中，OkHttp 的 `parsedSseUrl.resolve(endpoint)` 会将此绝对 URL 正常解析，最终返回 `"https://attacker.com/"`，从而使 SSRF 安全拦截失效，向恶意服务器发送包含敏感数据的请求。
- **Suggestion**: 摒弃对输入 `endpoint` 的各种手动前缀匹配，统一在 `resolve` 之后对解析出来的绝对 URL 进行 host/port 同源对比校验。如下代码更加简洁且无缝拦截所有变体：
  ```kotlin
  val resolvedUrl = parsedSseUrl.resolve(endpoint) ?: throw IOException("Failed to resolve endpoint: $endpoint")
  if (resolvedUrl.host != parsedSseUrl.host || resolvedUrl.port != parsedSseUrl.port) {
      throw SecurityException("SSRF Detected: Redirect host/port (${resolvedUrl.host}:${resolvedUrl.port}) does not match sseUrl host/port (${parsedSseUrl.host}:${parsedSseUrl.port})")
  }
  val resolvedEndpoint = resolvedUrl.toString()
  ```

### [Major] Finding 2: ChatStorageManager.kt 的并发 Mutex 锁因为跨组件实例化而失效

- **What**: 本地 JSON 读写的文件互斥锁（`sessionsMutex`、`messagesMutex`、`cardsMutex`）是实例级别的变量，而非共享的单例/静态变量。
- **Where**: `app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt` 第 32-34 行：
  ```kotlin
  private val sessionsMutex = Mutex()
  private val messagesMutex = Mutex()
  private val cardsMutex = Mutex()
  ```
- **Why**: 在 Loyea 应用中，`ChatViewModel`（前台 UI 线程）和 `GreetingWorker`（后台 WorkManager 工作线程）分别通过 `ChatStorageManager(context)` 创建了独立的管理器实例。由于 Mutex 是实例变量，这两个组件使用的其实是不同的锁对象。这导致前后台在同时读写相同的 JSON 数据文件（如 `session_xxx.json` 或 `sessions_metadata.json`）时，互斥锁根本没有起到限制作用，并发读写冲突的隐患依然存在。
- **Suggestion**: 将 Mutex 锁对象移入 `companion object`（静态块），确保在同一个进程中所有 `ChatStorageManager` 实例共享同一组锁，达到真正的线程级互斥效果：
  ```kotlin
  class ChatStorageManager(private val context: Context) {
      // ...
      companion object {
          private val sessionsMutex = Mutex()
          private val messagesMutex = Mutex()
          private val cardsMutex = Mutex()
      }
  }
  ```

---

## Verified Claims

- **AndroidManifest.xml 权限补齐** → 验证通过。`ACCESS_NETWORK_STATE` 权限已在 manifest 中正确声明。
- **McpClient.kt connect() 与 handleDisconnect() 并发加固** → 验证通过。引入了 `synchronized(this)` 对关键状态流转进行线程互斥，且在 `connect()` 的异常/取消捕获中正确执行并向上抛出 `CancellationException`，防止了死锁与状态倒退。
- **ChatViewModel.kt 磁盘消息去重与合并逻辑** → 验证通过。在保存前通过 ID 级去重 (`LinkedHashMap`) 并按时序合并磁盘数据，有效防止了 `GreetingWorker` 后台主动关怀消息被 UI 覆盖丢失。
- **UI 双发与发送拦截** → 验证通过。`isThinking` 和 `isMcpRunning` 状态流被正确传递到 `ChatInputBar`，并通过 `onPreviewKeyEvent` 拦截了 Enter 回车键、置灰了发送按钮并禁用了 IME Action。
- **LlmClient.kt HTTP 响应释放加固** → 验证通过。所有三处 OkHttp 调用均加固为了 `execute().use { ... }`，确保在任何异常、取消或成功流程下底层响应流都会被安全关闭释放。

---

## Coverage Gaps

- **跨实例锁泄露/失效风险** — 评级：**High**。由于 Mutex 非静态，前后台并发写入文件可能直接引发文件损坏或数据覆盖。建议将锁移至静态 companion object。
- **SSRF 绕过风险** — 评级：**High**。目前大小写前缀匹配的防御手段有严重漏洞，一旦被恶意 MCP Server 利用将导致本地用户消息和工具调用上下文泄露。

---

## Unverified Items

- **动态多进程环境下的锁隔离** — 理由：虽然 companion object 在单进程内提供了互斥保障，但如果 `GreetingWorker` 被配置在单独的进程中运行，由于 JVM 隔离，`Mutex` 依然无法实现跨进程锁。考虑到通常 Android 应用的 Worker 运行于主进程，本案目前认为接受此潜在边界风险，但建议在文档中进行说明。
