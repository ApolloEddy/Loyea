# Tavern 即时插件重构说明

本文记录 Loyea 将 SillyTavern/Tavern 兼容能力从原生聊天生态中剥离的目标边界、当前实现与后续迁移顺序。它描述开发分支上的架构状态，不代表已经发布的稳定版功能。

## 目标边界

- Loyea 原生会话只依赖稳定的 `PersonaRef`、`PromptPatch`、`GenerationPatch`、文本变换和租约协议，不依赖具体 Tavern 运行时实现。
- Tavern 角色卡、Character Book、Preset、Regex 与外部资源 codec 由 `:plugins:tavern-core` 提供；共享知识匹配由 `:knowledge-core` 提供。
- 插件关闭后立即拒绝新的外部人格任务；已经取得租约的请求继续使用不可变运行时代次，完成或取消后自动排空。
- 插件启停不删除用户导入的数据。重新启用或重启应用后，持久化的期望状态与 live runtime 状态保持一致。
- 原生人格和插件人格即使使用相同本地 ID，也不能共享消息副作用、后台任务、主动问候或图记忆。

## 当前模块与依赖方向

```text
:app
  ├─ :plugin-api
  ├─ :plugin-host
  ├─ :knowledge-core
  ├─ :plugins:tavern-core
  └─ :plugins:tavern-storage

:plugin-host ──> :plugin-api
:knowledge-core ──> （无生产依赖）
:plugins:tavern-core ──> :plugin-api + :knowledge-core
:plugins:tavern-storage ──> （无生产依赖）
```

- `:plugin-api`：稳定身份、能力、冻结回合、提示词 patch、生成 patch 与输出变换契约。
- `PersonaProjection`：跨宿主/插件的稳定人格展示投影；Tavern repository 直接返回该契约，不再暴露插件内重复的人格记录类型。
- `:plugin-host`：插件注册、代次管理、类型化 persona lease、停用排空和失败隔离。
- `:knowledge-core`：位于 `com.loyea.context.core` 的中立纯 Kotlin 运行时；当前保留 `WorldInfo*` 兼容命名，承载通用条目模型、匹配、预算、递归和深度注入，不依赖 Android 或 Tavern 实现。
- `:plugins:tavern-core`：位于独立 `com.loyea.plugins.tavern.core` 命名空间，不依赖 Android、Compose、ViewModel 或宿主消息模型；负责 Tavern 文档 codec、CharacterBook/Preset/Regex 和插件回合，并通过 `:knowledge-core` 投影通用知识上下文。
- `:plugins:tavern-storage`：位于 `com.loyea.plugins.tavern.storage` 的纯 Kotlin/JVM 私有存储边界，负责 registry/cards/assets 目录、SHA-256 文件校验、原子复制和迁移标记；不持有会话或消息。
- `:app`：Android 组装入口、持久化适配器、WorkManager、Compose 控制面，以及迁移期间尚未移出的旧 UI/存储适配层。

核心 API 与中立知识模块不得依赖 Tavern 实现模块。Android 宿主仅可在 composition root 和 Tavern 适配器中引用具体插件类型；迁移期间的 World Info 存储/UI 仍暂留 `:app`。

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
- `ChatStorageManager` 中的 Tavern 世界书文件适配，以及迁移期间对 `CharacterCard` 投影的兼容读写。
- `TavernScreen`、`WorldInfoSettings` 及其对 `ChatViewModel` 的直接 UI 绑定。

第一阶段物理抽取已完成：`WorldInfoModels` 和 `WorldInfoMatcher` 已移入 `:knowledge-core`；公开 `WorldInfo*` 名称暂保留，确保本步不改变序列化数据或 Prompt 语义。

第二阶段存储边界已建立：旧 `tavern_resources.json` 首次访问时复制到 `files/tavern/registry` 并写入可校验迁移标记，源文件保留；非内置角色卡的规范化原始文档写入 `files/tavern/cards/<sha256>.json`。会话元数据、消息和会话世界书仍由宿主持有。

人格投影边界已收敛：`TavernCharacterCardAdapter` 同时提供 `CharacterCard → PersonaProjection` 与 `CharacterCard → TavernCardDocument` 两条单向路径；请求运行时只消费 `PersonaProjection`，完整 Tavern 字段和未知 JSON 仍留在文档路径。

后续按以下顺序推进，避免把数据迁移、包名重写与 UI 拆分混成一次不可回退的大改动：

1. 完成 `:plugins:tavern-storage` 的资源文件接管，并为旧 registry/raw card 文档补齐恢复与冲突可观测性；会话/消息仍由宿主持有。
2. 将 `CharacterCard` 中尚未迁出的 Tavern 扩展字段拆成原生 `PersonaSummary` 与插件私有 `TavernCardDocument`，通过 adapter 投影，保留一次性旧 JSON 迁移和原始备份。
3. 把 `TavernScreen` 改为 state + callback 控制面，再迁入 `:plugins:tavern-ui`；SAF、分享和 FileProvider 能力由宿主端口提供。
4. 清除宿主核心签名中的 Tavern/WorldInfo/Regex/Preset 具体类型，并增加依赖方向架构测试。

核心 package rename 已作为独立迁移完成；`:plugins:tavern-core:test` 会先执行命名空间与宿主 import 防回退门禁，不与后续存储格式迁移混在同一提交。

## 验收门禁

每个迁移提交都必须满足：

```powershell
.\gradlew.bat --no-daemon --no-build-cache --rerun-tasks :plugin-api:test :plugin-host:test :knowledge-core:test :plugins:tavern-core:test :plugins:tavern-storage:test :app:testDebugUnitTest
```

其中 `:knowledge-core:test` 会先执行命名空间和宿主/Tavern 禁止导入门禁，确保中立知识核心不会重新依赖具体插件或 Android 宿主实现。

另外必须人工验证：设置页快速连续启停、停用时在途流式请求排空、重启后仍保持停用、旧会话/旧图记忆迁移、真实 PNG/CHARX 导入导出，以及 Android 设备上的后台 WorkManager 恢复。JVM 测试通过不能替代这些真机与系统调度验收。
