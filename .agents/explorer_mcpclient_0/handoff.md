# Handoff Report — Loyea MCP 客户端协议与多服务器管理设计

本报告作为 Milestone 1 (MCPClient) 的设计与分析成果交付，为 Implementer 角色提供手把手的改造路线与详细方案。

---

## 1. Observation (观察到的现状)

我们通过对 `D:\CodingProjects\Android\Loyea` 项目代码库的只读审计，确认了以下事实：

1. **项目规范与里程碑要求**：
   - 根据 `D:\CodingProjects\Android\Loyea\.agents\PROJECT.md` 第 31 行：
     `| 1 | Milestone 1: MCPClient | 实现 JSON-RPC over HTTP/SSE 客户端，多服务器管理配置与高颜值 Claude 风格 UI 面板 | Milestone 0: TestInfra | PLANNED |`
     明确要求实现标准的 JSON-RPC over HTTP/SSE 客户端、`McpManager`、`McpConfigStorage` 持久化，以及 Claude 风格 UI 面板。

2. **现有的包与代码布局**：
   - 根据 `PROJECT.md` 第 67 行：
     ```
     - app/src/main/java/com/loyea/
       - mcp/：多服务器客户端及配置存储
     ```
     经使用 `list_dir` 检查，当前 `com/loyea/` 目录下不存在 `mcp` 目录，相关协议客户端及管理逻辑全部为空，需要从零开始设计与创建。

3. **色彩与排版设计依据**：
   - 经查看 `app/src/main/java/com/loyea/ui/theme/Color.kt`：
     - 第 7 行：`val LoyeaLightBg = Color(0xFFF9F6F0)`（燕麦白背景）
     - 第 9 行：`val LoyeaLightSurface = Color(0xFFF3EFE6)`（浅黄褐 Surface）
     - 第 11 行：`val LoyeaLightOutline = Color(0xFFE6DFD3)`（淡淡的边框线）
     这些底色与 `SettingsScreen.kt` 里定义的琥珀沙黄（`#EADFD3`）、微光浅绿（`#E2F1E8`）共同构成了低饱和度“莫兰迪色系”的开发底座。
   - 经查看 `app/src/main/java/com/loyea/ui/theme/Type.kt`：
     - 定义了 `AnthropicSans`（无衬线）与 `AnthropicSerif`（衬线）两种字体，我们将使用前者作为配置项和按钮排版，后者作为正文排版。

4. **主界面与 ViewModel 绑定关系**：
   - 经查看 `app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt`：
     - `SettingsScreen` (第 73 行) 目前只定义了三个子页面：`MAIN`、`API_CONFIG`、`THEME_SETTINGS`。需要对其进行扩展以支持 MCP 配置页。
   - 经查看 `app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt`：
     - 包含 `SharedPreferences` 的读取（第 19 行）和 `Gson` 解析。我们需要将 `McpConfigStorage` 接入 `ChatViewModel` 的生命周期，并在 `init` 方法（第 86 行）中执行初始化和连接。

---

## 2. Logic Chain (逻辑链条)

1. **协议选择逻辑**：
   - 移动端（Android）无法直接通过管道和 Node/Python 等宿主进行本地 `Stdio` 交互。
   - 外部服务部署主要采用 **JSON-RPC over HTTP/SSE** 协议。
   - 结论：我们必须基于 OkHttp 和 OkHttp-SSE 库，构建一个异步的双向连接通道：GET 用于拉取 SSE 事件流，POST 用于发送 JSON-RPC 消息。

2. **异步对齐逻辑**：
   - SSE 的接收是异步数据流，而 POST 是即时发送。
   - 要让代码以类似“同步挂起”的直观方式运行（例如 `val tools = client.getTools()`），必须在客户端内部维护一个 `pendingRequests` 映射表。
   - 逻辑：发送时产生自增 `id` 放入 `ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>`。SSE 接收到 message 时，通过 `id` 匹配对应的 `Deferred` 并调用 `complete` 唤醒挂起的协程。

3. **网络重试与电量优化**：
   - 移动端常有进出电梯、切换 WiFi 等导致的瞬时断网。
   - 盲目的无限循环重试会耗尽手机电量并造成 CPU 占用。
   - 逻辑：在重连中使用 **指数退避**，并挂起等待 `ConnectivityManager` 报告网络恢复事件。

4. **Claude美学交互体验**：
   - “Claude美学”的核心在于：**高质感、无负担的微动效与柔和边界**。
   - 逻辑：
     - 磨砂边界：通过 1.dp 透明细边框（`LoyeaLightOutline`）实现。
     - 在线状态：在 `CONNECTING` 状态时，为指示灯点缀基于 `rememberInfiniteTransition` 的 Alpha 呼吸渐变。
     - 折叠动效：用 `AnimatedVisibility` 搭配 `Spring.DampingRatioLowBouncy` 弹簧阻尼，消除直板展开的生硬感。

---

## 3. Caveats (注意事项与假设)

1. **Android 明文网络限制**：
   - 在开发调试阶段，MCP 服务端通常运行在本地电脑的 HTTP 端口上（如 `http://10.0.2.2:3000`）。
   - Android 系统默认禁止明文 HTTP 传输。在实现时，**必须**在 `res/xml/network_security_config.xml` 中配置允许本地网段的明文传输，或者在 `AndroidManifest.xml` 中设置 `android:usesCleartextTraffic="true"`。
2. **模拟器回路 URL**：
   - 客户端连接本机的 SSE 地址时，需将本机的 `127.0.0.1` 替换为 Android 专用网关 `10.0.2.2`。
3. **并发安全限制**：
   - SSE 的事件流分发与 UI 主线程不在同一线程，在更新 UI 状态或 `discoveredTools` 时必须切换至 `Dispatchers.Main`，防止状态竞争引起崩溃。

---

## 4. Conclusion (结论与推荐方案)

针对 Milestone 1 的建设，应当采取如下具体设计：

1. **新建 `com.loyea.mcp` 包**，新增三个核心类：
   - `McpServerConfig` / `McpServerStatus` (数据模型与状态枚举)。
   - `McpClient` (基于 `OkHttpClient` 和 `okhttp3.sse.EventSource` 的 JSON-RPC over SSE 客户端)。
   - `McpConfigStorage` (基于 `SharedPreferences` 的 JSON 读写工具)。
2. **新增 `McpManager` 作为连接管理器**：
   - 持有并发活动客户端映射。
   - 提供状态流 `serverStates` 并实现带指数退避和网络监听的自动重连。
3. **改造 `SettingsScreen.kt`**：
   - 在 `SettingsSubPage` 新增 `MCP_CONFIG`。
   - 为 `SettingsMainLayout` 增加“MCP 赛博插件”入口卡片。
   - 新建 `McpConfigLayout` 页面，包含呼吸灯指示器、可折叠的服务器卡片（`McpServerCardItem`）和添加新服务器的 `BottomSheet`。

---

## 5. Verification Method (验证方法)

Implementer 完成代码实现后，应按照以下步骤进行端到端（E2E）与单元测试验证：

1. **存储持久性验证**：
   - 打开设置页，点击 `+` 增加一个 MCP 配置（如 `http://10.0.2.2:3000/sse`）。
   - 关闭应用，杀进程，重新启动。
   - 进入设置页，验证刚才添加的服务端名称和 URL 能够被正确读取并显示。

2. **状态流转与呼吸灯验证**：
   - 在本地拉起一个简易的 MCP HTTP/SSE 模拟服务端（可以使用 Python-mcp 或 Node.js mcp-server）。
   - 将应用中的该服务器开关打开。
   - 观察状态灯变为 **黄色（呼吸闪烁）**，在建立连接和完成握手后，应瞬间变为 **绿色（常亮）**，并自动渲染展示出该服务器持有的可用工具 Badge。

3. **异常处理与退避重试验证**：
   - 在连接成功（绿色）状态下，手动关停本机的 MCP 服务端。
   - 观察应用的状态指示灯是否在 1-2 秒内退化为 **黄色（呼吸闪烁）**。
   - 检查 Logcat 确认重试的间隔是否为指数增长（2s -> 4s -> 8s -> 16s -> 30s）。
   - 重新开启本机 MCP 服务端，观察应用是否自动完成重试并恢复为 **绿色（常亮）**。
