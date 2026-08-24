# Tavern 即时插件重构说明

本文记录 Loyea 将 SillyTavern/Tavern 兼容能力从原生聊天生态中剥离的目标边界、当前实现与后续迁移顺序。它描述开发分支上的架构状态，不代表已经发布的稳定版功能。

## 目标边界

- Loyea 原生会话只依赖稳定的 `PersonaRef`、`PromptPatch`、`GenerationPatch`、文本变换和租约协议，不依赖具体 Tavern 运行时实现。
- Tavern 角色卡、Character Book、World Info、Preset、Regex 与外部资源 codec 由 `:plugins:tavern-core` 提供。
- 插件关闭后立即拒绝新的外部人格任务；已经取得租约的请求继续使用不可变运行时代次，完成或取消后自动排空。
- 插件启停不删除用户导入的数据。重新启用或重启应用后，持久化的期望状态与 live runtime 状态保持一致。
- 原生人格和插件人格即使使用相同本地 ID，也不能共享消息副作用、后台任务、主动问候或图记忆。

## 当前模块与依赖方向

```text
:app
  ├─ :plugin-api
  ├─ :plugin-host
  └─ :plugins:tavern-core

:plugin-host ──> :plugin-api
:plugins:tavern-core ──> :plugin-api
```

- `:plugin-api`：稳定身份、能力、冻结回合、提示词 patch、生成 patch 与输出变换契约。
- `:plugin-host`：插件注册、代次管理、类型化 persona lease、停用排空和失败隔离。
- `:plugins:tavern-core`：不依赖 Android、Compose、ViewModel 或宿主消息模型的 Tavern 纯 Kotlin 运行时。
- `:app`：Android 组装入口、持久化适配器、WorkManager、Compose 控制面，以及迁移期间尚未移出的旧 UI/存储适配层。

核心模块不得依赖 Tavern 实现模块。Android 宿主只可在 composition root 和 Tavern 适配器中引用具体插件类型。

## 热插拔语义

插件状态分为两层：

1. `desiredEnabled` 是同步提交到独立 SharedPreferences 的用户意图。
2. `PluginState` 是 live host 的实际状态，包含 `ENABLED`、`DISABLED`、`FAILED`、`INCOMPATIBLE`、活跃租约数和运行时代次。

关闭流程先持久化意图，再让 host 停止接受新租约。当前代次若仍有请求，不会被强行销毁，而是进入 draining；最后一个租约释放后只关闭一次。再次启用会创建新的 generation，旧 generation 的冻结回合和暂存资源不能被新请求消费。

设置页的“即时插件管理”显示期望状态、实际状态、启动失败和排空数量；连续快速点击在上一次状态变更完成前会被锁定。

## Persona 与后台副作用隔离

每个会话持久化以下身份：

- `personaOwnerId`
- `characterId`
- `sessionIncarnationId`
- `personaBindingRevision`

`sessionIncarnationId` 阻断删除后复用同一公开 session ID；`personaBindingRevision` 阻断 A → B → A 的 ABA 竞态。主聊天、压缩、生图、主动问候、记忆整理和图记忆写回均使用这四个维度检查目标仍是同一绑定。

旧会话会在首次读取时迁移，并保留 `sessions_metadata.pre_persona_binding_v1.json` 原始备份。精确命中 Loyea 内置人格目录的 ID 才迁移为 `loyea.native`；其他非空旧 ID 迁移为 Tavern owner；空白或损坏身份保持不可用，不会静默回退默认 Loyea。

主动问候以 Work ID 作为幂等 operation ID，通过持久化 journal 修复消息文件和会话元数据之间的崩溃窗口。图记忆同时按 owner、persona、incarnation 和 revision 命名空间隔离；旧记录仅在 session 与 persona 都精确匹配时迁移，并保留原始备份。

## 已保留的 Tavern 功能

- V1/V2/V3 JSON、PNG `chara`/`ccv3`、V3 CHARX 导入与标准导出。
- 未知字段、Character Book、资产、扩展字段和第三方字段 round-trip。
- World Info selective 逻辑、正则、递归、分组、概率、sticky/cooldown/delay、深度 role 注入和 token 预算。
- Preset prompt order、生成参数覆盖、角色卡资源绑定与 Regex 输入/输出链。
- 流式回复、MCP 多轮、后台主动问候、长会话压缩、生图和记忆整理的插件租约接入。

## 尚未完成的物理拆分

当前已完成“运行时可拔插”和“核心算法模块化”，但以下代码仍位于 `:app`，不能据此宣称物理剥离全部完成：

- `CharacterCard` 中为旧 JSON 兼容保留的 Tavern 扩展字段。
- `ChatStorageManager` 中的 Tavern 资源注册表与世界书文件适配。
- `TavernScreen`、`WorldInfoSettings` 及其对 `ChatViewModel` 的直接 UI 绑定。
- `:plugins:tavern-core` 暂时沿用历史 package；迁移稳定后需单独改为插件命名空间。

后续按以下顺序推进，避免把数据迁移、包名重写与 UI 拆分混成一次不可回退的大改动：

1. 新增 `:plugins:tavern-storage`，迁移插件私有注册表、卡片原始文档和资源文件；会话/消息仍由宿主持有。
2. 将 `CharacterCard` 拆成原生 `PersonaSummary` 与插件私有 `TavernCardDocument`，通过 adapter 投影，保留一次性旧 JSON 迁移和原始备份。
3. 把 `TavernScreen` 改为 state + callback 控制面，再迁入 `:plugins:tavern-ui`；SAF、分享和 FileProvider 能力由宿主端口提供。
4. 清除宿主核心签名中的 Tavern/WorldInfo/Regex/Preset 具体类型，并增加依赖方向架构测试。
5. 最后独立完成 package rename；不得与存储格式迁移放在同一提交。

## 验收门禁

每个迁移提交都必须满足：

```powershell
.\gradlew.bat --no-daemon --no-build-cache --rerun-tasks :plugin-api:test :plugin-host:test :plugins:tavern-core:test :app:testDebugUnitTest
```

另外必须人工验证：设置页快速连续启停、停用时在途流式请求排空、重启后仍保持停用、旧会话/旧图记忆迁移、真实 PNG/CHARX 导入导出，以及 Android 设备上的后台 WorkManager 恢复。JVM 测试通过不能替代这些真机与系统调度验收。
