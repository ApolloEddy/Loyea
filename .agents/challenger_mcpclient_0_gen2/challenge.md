# 对抗验证与漏洞分析报告 (Challenge Report)

## Challenge Summary

**Overall risk assessment**: HIGH (高)

本项目在引入 OkHttp 的 `toHttpUrlOrNull()` 扩展函数以解决 `HttpUrl.parse` 编译错误后，能够解决静态编译阻碍，使单元测试（如 `McpConfigStorageTest` 和 `McpRoutingTest`）顺利执行并通过。然而，经过对其并发设计、网络交互及数据持久化逻辑的严密静态时序分析，本代理发现了数项关键的安全、并发与稳定性对抗漏洞。

---

## Challenges

### [Critical] Challenge 1: 双斜杠（//）协议绕过 SSRF 防御漏洞

- **Assumption challenged**:
  系统假设只有以 `http://` 或 `https://` 开头的 `endpoint` 才是绝对路径且可能指向外部恶意服务器；其他路径均被视为相对路径，并通过 `parsedSseUrl.resolve(endpoint)` 安全解析。
- **Attack scenario**:
  在 `McpClient.kt` 的连接逻辑中：
  ```kotlin
  val resolvedEndpoint = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
      // 校验 Host 和 Port 必须与 sseUrl 匹配
      ...
  } else {
      // 相对路径解析，直接转换，没有进行 Host/Port 校验
      parsedSseUrl.resolve(endpoint)?.toString() ?: throw IOException(...)
  }
  ```
  如果恶意 MCP 服务端返回的 `endpoint` 为 `//evil.com/endpoint`（使用无协议头的绝对路径格式），`endpoint.startsWith("http://")` 等条件判定为 `false`。
  代码流转进入 `else` 分支，通过 `parsedSseUrl.resolve("//evil.com/endpoint")` 进行解析。根据 OkHttp `HttpUrl.resolve` 的标准解析规则，这种格式会继承 Base URL 的 Scheme（例如 `http`），但会**完全替换 Host 和 Port** 为 `evil.com`。
  由于 `else` 分支**完全没有**校验 Host 和 Port 是否与原始 `sseUrl` 匹配，解析后的 `resolvedEndpoint` 变成 `http://evil.com/endpoint` 并在随后的 HTTP POST 中直接使用。这导致 SSRF 防御机制被完全绕过。
- **Blast radius**: Critical (高危)。恶意 MCP 服务器可以通过该漏洞控制客户端发起对任意局域网或公网主机的 POST 请求，导致内网穿透及敏感信息泄露。
- **Mitigation**:
  移除以协议头为前缀的分支条件判定。所有通过 `parsedSseUrl.resolve(endpoint)` 或直接解析得到的 `resolvedEndpoint` 必须进行统一的 Host 和 Port 强度一致性校验，确保其与 `parsedSseUrl` 严格一致。

---

### [High] Challenge 2: 重连死锁与连接状态竞态覆盖漏洞

- **Assumption challenged**:
  系统假设 `connect()` 的执行流是线性的，在执行中途发生的网络关闭或异常（`onFailure` / `onClosed`）触发的 `handleDisconnect()` 能够与主线程的连接状态流转保持同步。
- **Attack scenario**:
  在 `McpClient.connect()` 的协程流程中：
  1. 调用 `connect()` 并开始建立 SSE 连接，状态更新为 `CONNECTING`。
  2. SSE 收到 `endpoint` 事件，`connect()` 协程被唤醒并准备发起 `initialize` 的 POST 握手请求。
  3. 此时，网络突然发生中断，OkHttp Dispatcher 线程中触发 `onFailure` 回调，进而调用 `handleDisconnect()`，把连接状态重置为 `DISCONNECTED`。
  4. 与此同时，`connect()` 协程仍然在并行执行其 HTTP 握手，如果该握手（在超时时间内）在底层刚好成功返回，`connect()` 协程最终会强行执行：
     ```kotlin
     _status.value = McpServerStatus.CONNECTED
     ```
     这会把状态覆盖写回 `CONNECTED`。
  5. 此时客户端实际上已经失效（`eventSource` 被取消且为 `null`），但状态卡在 `CONNECTED`。
  6. 在 `McpManager.kt` 中，`startConnectionLoop` 使用 `client.status.first { it == McpServerStatus.DISCONNECTED }` 等待断开以触发重连。因为状态被覆盖成了 `CONNECTED` 且永远不会有下一次 `onFailure` 触发（因为连接早已被销毁），重连循环将**无限挂起**。
- **Blast radius**: High (高)。客户端卡在“已连接”状态但实际失效，重连机制死锁，导致 MCP 连接永久失效，直至 App 重启。
- **Mitigation**:
  在 `connect()` 写入 `CONNECTED` 状态前，必须获取状态锁，或者在写入前校验 `eventSource != null` 及当前状态是否仍为 `CONNECTING`，防止过期状态覆盖。

---

### [High] Challenge 3: 后台主动关怀消息被前台覆盖丢失漏洞 (状态不同步)

- **Assumption challenged**:
  系统假设本地会话消息列表在用户处于前台聊天状态时只会被前台 UI 写入，没有考虑后台定时任务（`GreetingWorker`）并发读写的情况。
- **Attack scenario**:
  1. 用户在聊天页处于 Session A，此时 `ChatViewModel.messages.value` 包含消息 `[Msg1, Msg2]`。
  2. 此时，后台定时问候 `GreetingWorker` 被触发，从本地读取 Session A 消息 `[Msg1, Msg2]`，静默请求大模型并生成问候语 `GreetingMsg`。
  3. `GreetingWorker` 将问候语写入本地文件，文件内容更新为 `[Msg1, Msg2, GreetingMsg]`。
  4. 因为 UI 没有文件监听器（非响应式数据库或文件观察），`ChatViewModel` 中的 `messages.value` 依然为 `[Msg1, Msg2]`。
  5. 用户此时发送了一条新消息 `UserMsg`。
  6. `ChatViewModel` 从其内存状态 `messages.value` (`[Msg1, Msg2]`) 派生出新的历史记录 `[Msg1, Msg2, UserMsg]` 并调用 `saveSessionMessages` 保存。
  7. 这一写入直接覆盖了磁盘上的文件，`GreetingWorker` 之前写入的 `GreetingMsg` 随之被**彻底抹除且不可逆恢复**。
- **Blast radius**: High (高)。当用户在主动问候推送时正处于聊天界面或直接点击通知进入时，新生成的主动问候消息在用户下一次输入后会瞬间被擦除，导致聊天历史严重错乱与丢失。
- **Mitigation**:
  1. 将文件存储层改造成基于 Flow 暴露的响应式订阅，或在 `saveSessionMessages` 之前先从磁盘重新加载最新的消息列表进行合并；
  2. 采用 Room 等支持 LiveData/Flow 的本地数据库作为单一数据源。

---

### [Medium] Challenge 4: 并发文件 IO 写入损坏与丢失历史漏洞

- **Assumption challenged**:
  底层 `ChatStorageManager` 的 `saveSessionMessages` 接口通过 `file.writeText(json)` 进行文件写入时被假设为是线程安全的。
- **Attack scenario**:
  `ChatStorageManager` 的读写没有任何同步锁或文件通道锁。当大模型触发并行的多工具调用（Tool Calls）时，前台会有多个协程在 `Dispatchers.IO` 上并发调用 `saveMessagesAsync`；同时后台 `GreetingWorker` 也在并发操作文件。
  多个线程同时对同一个 `session_xxx.json` 文件调用 `writeText(json)` 会导致写入冲突，产生残缺或空白的 JSON 数据。
  一旦数据损坏，下一次 `loadSessionMessages` 触发时，GSON 解析抛出异常并触发自愈（Empty List 降级），用户的整个聊天历史记录将被彻底清空。
- **Blast radius**: Medium-High (中偏高)。随机发生聊天数据损坏并清空历史，影响软件可用性。
- **Mitigation**:
  使用 `Mutex` 锁保护文件写入逻辑，或在写入时采用“先写临时文件再原子重命名 (write to temp and rename)”的方式来确保文件写入的原子性。

---

## Stress Test Results

- **SSRF Attack via `//evil.com`** → 触发 SSRF 防御，抛出 `SecurityException` 拦截 → 防御失败，绕过 Host 检查并发送请求到 `evil.com` → **FAIL** (严重)
- **Initialize timeout/failure during `connect()`** → 关闭连接，重置状态，触发 `McpManager` 自动重连 → 竞态发生，状态卡在 `CONNECTED`，重连循环挂起 → **FAIL** (高)
- **User sends message during background Greeting** → 正常保留所有消息并呈现在 UI → 主动关怀消息被永久覆盖抹除 → **FAIL** (高)
- **Concurrent Tool Calls saving messages** → 所有消息被原子性地顺序保存 → 随机发生 GSON 序列化报错或文件内容为空，触发历史记录清空 → **FAIL** (中)

---

## Unchallenged Areas

- **WatchDataRepository & LocationService 模拟逻辑** — 这两个服务的 Mock 数据均基于 SharedPreferences 读取，没有明显的安全穿透隐患，故未列入对抗测试挑战。
