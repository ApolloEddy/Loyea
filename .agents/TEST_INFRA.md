# E2E Test Infra: Loyea (陪伴型 Android 赛博伴侣)

## Test Philosophy
- 需求驱动、黑盒测试。不依赖具体底层技术实现（如网络层框架、本地数据库具体库），而是通过行为和数据接口的注入与模拟来验证系统级业务闭环。
- 架构设计：ComposeTestRule + MockWebServer + mock-watch-repository + WorkManager Test Helper。

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 (Count) | Tier 2 (Count) | Tier 3 | Tier 4 |
|---|---------|---------------------|:--------------:|:--------------:|:------:|:------:|
| 1 | MCP 客户端与多服务器管理 | ORIGINAL_REQUEST R1 | 5 | 5 | ✓ | ✓ |
| 2 | LLM 工具调用链路闭环 | ORIGINAL_REQUEST R2 | 5 | 5 | ✓ | ✓ |
| 3 | 物理感知与手表、定位集成 | ORIGINAL_REQUEST R3 | 5 | 5 | ✓ | ✓ |
| 4 | 后台定时主动问候推送 | ORIGINAL_REQUEST R4 | 5 | 5 | ✓ | ✓ |
| **Total** | | | **20** | **20** | **4** | **5** |

## Test Architecture
- **E2E 运行环境**：
  - 本地 JVM 单元测试：`.\gradlew test`
  - 模拟器/设备仪器化 E2E 测试：`.\gradlew connectedAndroidTest`
- **Mock 服务与接口注入**：
  - **MockWebServer**：拦截大模型 HTTP 请求，模拟 SSE（Server-Sent Events）数据流响应与 `tool_calls`。
  - **Mock MCP 服务端**：在测试用例中拉起本地临时 Mock 服务器，以响应 `tools/list` 与 `tools/call` JSON-RPC 请求。
  - **MockWatchDataRepository**：通过依赖注入（DI）机制，使得测试框架能够随时修改虚拟心率与运动状态数据。
  - **WorkManagerTestInitHelper**：强制并在后台立刻唤醒定时问候的 WorkManager 任务。

## Detailed Test Cases (50 total)
详细测试用例列表与预期结果请参照：
`D:\CodingProjects\Android\Loyea\.agents\explorer_testinfra_0\analysis.md`
包含以下四大 Tier：
- **Tier 1 (Feature Coverage)**: R1-T1-01 到 R4-T1-05 (共 20 个)
- **Tier 2 (Boundary & Corner)**: R1-T2-01 到 R4-T2-05 (共 20 个)
- **Tier 3 (Cross-Feature Combinations)**: T3-01 到 T3-04 (共 4 个)
- **Tier 4 (Real-World Application Scenarios)**: T4-01 到 T4-05 (共 5 个)

## Coverage Thresholds
- Tier 1: ≥5 per feature
- Tier 2: ≥5 per feature
- Tier 3: 覆盖所有主要功能的两两组合
- Tier 4: ≥5 真实陪伴世界应用场景
