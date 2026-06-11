# Progress

Last visited: 2026-06-11T13:10:59+08:00

## Completed Tasks
- [x] 添加 `okhttp3:okhttp-sse` 依赖和 mockito 测试依赖。
- [x] 创建 `McpServerConfig.kt` 和 `JsonRpc.kt` 定义 MCP 数据模型。
- [x] 实现 `McpConfigStorage.kt` 完成配置序列化与反序列化损坏自愈。
- [x] 实现 `McpClient.kt` 完成 HTTP/SSE 协议解析与 CompletableDeferred 异步转挂起配对。
- [x] 实现 `McpManager.kt` 完成多客户端生命周期管理、指数退避重试、网络可用性感知及工具名称前缀路由。
- [x] 扩展 `SettingsScreen.kt` 以绘制高颜值的莫兰迪色磨砂配置面板，支持 Tools 折叠动画和配置增删改查。
- [x] 在 `ChatViewModel.kt` 与 `MainActivity.kt` 中打通了配置和状态的整合。
- [x] 编写 `McpConfigStorageTest` 和 `McpRoutingTest` 单元测试并通过断言。
- [x] 更新 `CHANGELOG.md` 记录本次重大变更。
