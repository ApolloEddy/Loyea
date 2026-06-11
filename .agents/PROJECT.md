# Project: Loyea (陪伴型 Android 赛博伴侣)

## Architecture
本系统分为以下几大模块：
1. **UI 模块**：
   - `MainActivity.kt`：App 主入口。
   - `ChatScreen.kt`：集成聊天框、思考链折叠布局和 `McpCallItem` 卡片渲染。
   - `SettingsScreen.kt`：提供高颜值（Claude美学）的 MCP 服务端管理面板和物理感知开关。
2. **MCP 客户端与管理模块 (`com.loyea.mcp`)**：
   - 实现标准的 JSON-RPC over HTTP/SSE 客户端。
   - `McpManager`：多服务器并发连接与工具列表管理，提供连接状态流转。
   - `McpConfigStorage`：使用 SharedPreferences 进行持久化。
3. **LLM 与工具调用链路 (`com.loyea.ui.chat.LlmClient` 对接)**：
   - 提取可用工具，转换为 LLM `tools` 参数注入请求。
   - 拦截大模型的 Tool Calls 响应，分发给 MCP 客户端执行，将结果重新喂给大模型进行闭环对话。
   - 在 UI 线程上更新 `Message` 中 `McpCall` 列表的 `RUNNING`/`SUCCESS`/`FAILED` 状态。
4. **物理感知与硬件预留模块 (`com.loyea.sensor`)**：
   - `WatchDataRepository` 接口与 `MockWatchDataRepository`：实现心率模拟（静息/运动，基准 60-100 bpm）和硬件同步接口预留。
   - 定位处理器：获取真实 GPS 权限并返回经纬度，若无权限，则使用模拟坐标（可自定义，默认北京 `39.9042, 116.4074`）。
   - `PromptAssembler`：将 `[Physical Context]` 结构化段落动态拼入 System Prompt。
5. **后台定时与主动关怀模块 (`com.loyea.worker`)**：
   - `GreetingWorker`（基于 `WorkManager`）进行后台定时调度。
   - 静默请求 LLM 并拼接物理上下文，生成 50 字以内关怀语。
   - 状态栏系统通知（Claude式极简设计）。
   - 用户点击通知后拉起 `MainActivity` 并将生成的问候消息插入历史记录与持久化。

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 0 | Milestone 0: TestInfra | 建立 E2E 测试框架，编写 E2E 测试运行脚本，确定 Tiers 1-4 覆盖点，生成 TEST_INFRA.md | none | DONE |
| 1 | Milestone 1: MCPClient | 实现 JSON-RPC over HTTP/SSE 客户端，多服务器管理配置与高颜值 Claude 风格 UI 面板 | Milestone 0: TestInfra | DONE |
| 2 | Milestone 2: ToolCallLoop | LLM 对话中工具声明注入、Tool Call 拦截与执行闭环，McpCallItem 状态流转与可折叠参数展示 | Milestone 1: MCPClient | IN_PROGRESS |
| 3 | Milestone 3: PhysicalSensor | 物理感知参数注入（实时/模拟定位、模拟心率及 WearOS 硬件预留接口设计） | Milestone 2: ToolCallLoop | PLANNED |
| 4 | Milestone 4: BackgroundGreeting | WorkManager 定时唤醒大模型、系统状态栏通知推送、点击拉起 App 并持久化插入消息记录 | Milestone 3: PhysicalSensor | PLANNED |
| 5 | Milestone 5: IntegratedValidation | E2E 测试运行，全案集成，确保通过所有 4 个 Tier 的集成用例 | Milestone 2, 3, 4 | PLANNED |
| 6 | Milestone 6: WhiteBoxHardening | White-box coverage & Adversarial testing (Tier 5) 缺陷检测与修复 | Milestone 5 | PLANNED |

## Interface Contracts
### 1. MCP JSON-RPC 接口
- `tools/list`：获取服务器可用工具。
- `tools/call`：调用指定工具，返回结果。
- 连接状态定义：
  - `CONNECTED` (已连接)
  - `CONNECTING` (连接中)
  - `DISCONNECTED` (已断开)

### 2. WatchDataRepository 接口
```kotlin
interface WatchDataRepository {
    fun getHeartRateBpm(): Int
    fun getMovementState(): String // "Resting" | "Moving"
    fun isWatchConnected(): Boolean
    fun toggleSimulationState(isMoving: Boolean)
}
```

### 3. PromptAssembler 物理数据拼接规范
```
[Physical Context]
Current Time: yyyy-MM-dd HH:mm:ss
Heart Rate: <bpm> bpm (<State>)
Location: <latitude>, <longitude>
```

## Code Layout
- `app/src/main/java/com/loyea/`
  - `mcp/`：多服务器客户端及配置存储
  - `sensor/`：物理感知与手表、定位管理
  - `worker/`：后台 WorkManager 及推送通知逻辑
  - `ui/chat/` & `ui/settings/`：UI 组件整合
