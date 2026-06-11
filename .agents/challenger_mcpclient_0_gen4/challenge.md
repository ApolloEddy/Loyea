# 对抗验证挑战报告 (Challenge Report)

## 挑战总结 (Challenge Summary)

- **总体风险评估 (Overall risk assessment)**: **低 (LOW)**
  经过对 `worker_mcpclient_4` 重构加固后的代码进行静态推导与逻辑走查，项目在 SSRF 防御、重连死锁消除、多实例并发锁共享以及前后台会话/消息数据合并机制上表现出极强的健壮性。目前未发现致命或高风险的安全与并发缺陷。

---

## 挑战点 (Challenges)

### [低风险] 挑战点 1：DNS 重新绑定 (DNS Rebinding) 对 SSRF 校验的潜在绕过
- **被挑战的假设 (Assumption challenged)**: 假设 `finalHttpUrl.host != parsedSseUrl.host` 能够完全阻止针对内网或本地服务的 SSRF 攻击。
- **攻击/失效场景 (Attack scenario)**:
  如果用户配置的 MCP 服务器是一个公共域名（例如 `mcp.external-attacker.com`），攻击者通过控制该域名的 DNS 解析，在 SSE 连接阶段将其解析为外网合法 IP（通过验证）。而在客户端接收到 `endpoint` 事件并试图请求 `messageEndpoint` 时，攻击者迅速将 DNS 解析记录变更为内网敏感 IP（例如 `127.0.0.1` 或 `192.168.1.1`）。由于域名本身未发生改变（`finalHttpUrl.host` 依然是 `mcp.external-attacker.com`），强同源校验将无法拦截此请求，导致客户端向本地内网发送 HTTP POST 请求。
- **影响范围 (Blast radius)**: 攻击者可能通过操纵 MCP 客户端对本地环回接口或局域网内的敏感服务发送请求（例如本地路由器配置页面或未授权的内部控制 API）。
- **缓解措施 (Mitigation)**: 
  虽然 MCP 协议本身需要与目标服务器交互，但若要彻底防御 DNS Rebinding 造成的 SSRF，可以在解析出 URL 后，对底层 Socket 建立连接时的解析 IP 进行验证（即在 `okhttp3` 的 `Dns` 阶段或 `EventListener` 阶段对最终解析出来的 IP 进行私有地址范围过滤，阻断指向内网私有 IP 范围的访问）。

### [低风险] 挑战点 2：内存并发请求的内存溢出或垃圾数据累积风险
- **被挑战的假设 (Assumption challenged)**: 假设在连接异常断开时，仅调用 `deferred.completeExceptionally` 清空 `pendingRequests` 即可完美处理所有并发竞态。
- **攻击/失效场景 (Attack scenario)**:
  若服务端频繁触发 SSE 连接的重连或发送海量未响应的 JSON-RPC 请求，虽然在连接断开时会通过 `handleDisconnect()` 释放全部 `pendingRequests`，但若在快速重连期间（由于网络震荡），大量请求在 `sendRequest` 中被挂起并放入 `pendingRequests`，可能会出现极高频的协程创建和 Map 写入。
- **影响范围 (Blast radius)**: 在极端的高并发高抖动网络环境下，可能会导致临时的内存飙升或协程调度延迟。
- **缓解措施 (Mitigation)**:
  目前已有的 `withTimeout(30000)`（30秒超时机制）能够有效兜底，自动清理任何长期未响应的请求。总体风险较低。

---

## 压力测试与推导结果 (Stress Test Results)

### 场景 1：协议混淆与相对路径 SSRF 绕过攻击
- **输入/行为**: 
  - 原始 `sseUrl` 为 `http://192.168.1.100:8080/sse`。
  - 服务端返回的 `endpoint` 为 `//attacker.com:80/endpoint` (协议相对 URL) 或 `http://192.168.1.200:8080/endpoint` (不同的主机) 或 `http://192.168.1.100:8081/endpoint` (不同的端口)。
- **预期行为**: 强同源校验机制检测到主机或端口不匹配，立即抛出 `SecurityException`，终止连接。
- **实际/推导行为**:
  - `//attacker.com:80/endpoint` 无法被 `toHttpUrlOrNull()` 直接解析为绝对 URL，进而通过 `parsedSseUrl.resolve()` 解析为 `http://attacker.com:80/endpoint`。检测到主机 `attacker.com` 与 `192.168.1.100` 不匹配，抛出 `SecurityException`。
  - `http://192.168.1.200:8080/endpoint` 解析后主机为 `192.168.1.200`，不匹配，抛出 `SecurityException`。
  - `http://192.168.1.100:8081/endpoint` 解析后端口为 `8081`，不匹配，抛出 `SecurityException`。
- **结果**: **通过 (PASS)**

### 场景 2：连接断开与瞬间发起新连接时的并发死锁
- **输入/行为**: 
  - 在客户端正在等待 `endpointDeferred.await()` (最长 10 秒超时) 的过程中，用户或系统在主线程或后台线程中突然调用 `disconnect()`，或者网络异常触发 `onFailure()` 导致重连。
- **预期行为**: 正在挂起等待的协程立即被抛出异常唤醒并优雅退出，没有 Socket 或协程被挂起泄露。
- **实际/推导行为**:
  - `handleDisconnect()` 在 `synchronized(this)` 锁保护下将 `eventSource` 取消并置空，同时将 `endpointDeferred` 通过 `completeExceptionally(IOException("Disconnected"))` 强制中断。
  - 处于 `await()` 的挂起协程收到 `IOException`，从挂起处抛出异常，进入 `connect()` 的 `catch` 分支，执行 `handleDisconnect()` 确保清理干净，最终返回 `false`。由于 `connectMutex` 排他锁保证了同一时间只有一个 `connect` 逻辑运行，因此不会引起并发冲突。
- **结果**: **通过 (PASS)**

### 场景 3：多实例并发修改同一会话数据
- **输入/行为**:
  - 前台 UI 的 `ChatViewModel` 与后台 `GreetingWorker` 几乎在同一时刻对相同的 `sessionId` 发起写入操作。例如：`ChatViewModel` 正在保存用户发出的新消息，而后台 `GreetingWorker` 恰好被唤醒并保存主动问候消息。
- **预期行为**: 两次写操作不会发生相互覆盖，最终的磁盘文件中必须同时包含用户新发送的消息和后台生成的问候消息。
- **实际/推导行为**:
  - `ChatStorageManager` 的 `messagesMutex` 声明在 `companion object` 中，因此不管是 ViewModel 创建的实例还是 Worker 创建的实例，都共享同一把 JVM 级别的 `Mutex` 静态锁。
  - 假设 Worker 优先抢占到锁，Worker 会读取磁盘文件，追加问候消息并存回。
  - 随后 ViewModel 抢占到锁，调用 `mergeAndSaveMessages`，其在锁内部通过 `loadSessionMessagesInternal` 读出刚才已写入问候消息的最新磁盘列表，并与 ViewModel 内存中的用户消息通过 LinkedHashMap (按 ID 去重) 进行合并，最后将合并后的完整列表写入磁盘。
  - 两条消息均被完整保留在磁盘和内存中，未发生任何数据覆盖或抹除。
- **结果**: **通过 (PASS)**

### 场景 4：前后台会话元数据（Sessions）并发修改
- **输入/行为**:
  - 用户在前台删除会话的同时，后台工作器正在更新该会话的最后活动时间（`lastActiveTime`）。
- **预期行为**: 最终会话列表能保持事务一致性，不会出现已被删除的会话被后台写操作意外“复活”，也不会出现会话列表文件损坏。
- **实际/推导行为**:
  - `deleteSession` 会依次获取全局共享的 `sessionsMutex` 和 `messagesMutex`。
  - 后台工作器在 `updateSessionList` 中也会获取 `sessionsMutex`。
  - 在 `sessionsMutex` 的同步保护下，删除操作和更新操作严格串行执行。若删除先执行，更新操作读取到的会话列表已经不含该会话，因此其 `map` 更新不会重新把已删除的会话写入。若更新先执行，删除操作会随即将更新后的该会话从列表中彻底移除。
- **结果**: **通过 (PASS)**

---

## 未挑战区域 (Unchallenged Areas)

- **外部依赖库安全漏洞**: 本挑战未对使用的 OkHttp 和 Gson 进行二进制漏洞审计，默认其工作正常。
- **WearOS 真实硬件同步延迟**: 物理感知模块中关于手表心率同步的数据目前是通过 `MockWatchDataRepository` 在软件层面进行的模拟，未在真实物理 WearOS 硬件上进行并发时序的压力测试。由于该部分目前采用简单的读取逻辑，对核心存储没有写竞态，因此对其挑战级别定为低。
