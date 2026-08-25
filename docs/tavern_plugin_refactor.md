# Tavern 即时插件重构说明

本文记录 Loyea 将 SillyTavern/Tavern 兼容能力从原生聊天生态中剥离的目标边界、当前实现与后续迁移顺序。它描述开发分支上的架构状态，不代表已经发布的稳定版功能。

## 目标边界

- Loyea 原生会话只依赖稳定的 `PersonaRef`、`PromptPatch`、`GenerationPatch`、文本变换和租约协议，不依赖具体 Tavern 运行时实现。
- Tavern 角色卡、Character Book、Preset、Regex 与外部资源 codec 由 `:plugins:tavern-core` 提供；共享知识匹配由 `:knowledge-core` 提供。
- 插件关闭后立即拒绝新的外部人格任务；已经取得租约的请求继续使用不可变运行时代次，完成或取消后自动排空。
- 插件启停不删除用户导入的数据。重新启用或重启应用后，持久化的期望状态与 live runtime 状态保持一致。
- 原生人格和插件人格即使使用相同本地 ID，也不能共享消息副作用、后台任务、主动问候或图记忆。

## 兼容性基线（2026-08-25）

“兼容”按三层验收，不把 JSON 字段能读写等同于运行时等价：

- **格式层**：角色卡 V1/V2/V3、PNG/JSON/CHARX、World Info、Preset、Regex 和聊天文件可安全导入、导出、未知字段往返。
- **语义层**：关键词/正则、World Info 递归与 timed effects、生成类型触发、Prompt Manager 槽位、宏/变量、Persona、群聊和聊天分支必须在请求链路中产生等价结果。
- **平台层**：STscript、Quick Replies、第三方扩展、向量 Data Bank 等脚本/宿主能力必须经过显式安全端口；无法在 Android 本地安全复刻的能力不得伪装成已支持。

本阶段以 [SillyTavern World Info](https://docs.sillytavern.app/usage/core-concepts/worldinfo/)、[SillyTavern Macros](https://docs.sillytavern.app/usage/core-concepts/macros/)、[SillyTavern Group Chats](https://docs.sillytavern.app/usage/core-concepts/groupchats/)、[Tavo Chat](https://docs.tavoai.dev/en/guides/chat/) 和 [Tavo Group Chat](https://docs.tavoai.dev/en/guides/chat/group-chat/) 的当前公开语义为对照基线；每个后续兼容任务都必须绑定输入 fixture、请求快照和持久化回读断言。

生成类型已经从普通发送、重新生成、Continue、Swipe、Impersonate 和后台 quiet 入口贯通到插件输入与暂存回合；准备阶段会拒绝 spec 与 input 类型不一致的请求。Continue 会追加到当前 AI 消息，Swipe 会生成并保存新的回复版本，Impersonate 则只回填待发送的用户草稿，不伪造已发送消息。

World Info 的 Include Names 已同时影响扫描缓冲和聊天消息前缀，preset 可覆盖默认策略；命名 outlet 通过 `{{outlet::Name}}` 按引用展开。Author’s Note 已支持会话级独立文本、`After Scenario`/`In-chat`、深度和频率，按用户输入回合计数并在请求启动时冻结；默认空文本不注入，频率为 `0` 时禁用，不能用 CharacterBook 的 `an_top/an_bottom` 条目冒充。

Prompt Manager 与宏运行时现在共享请求启动时冻结的 `TavernMacroContext`：角色卡字段、历史最后消息、当前输入、生成类型、请求时间、命名 outlet 和只读变量会同时用于系统提示、preset slot、World Info Regex 和输出 Regex。Preset slot 会按 generation trigger 过滤，并保留 relative / in-chat + depth 的消息位置；Continue 请求会消费 `continue_nudge_prompt`、`continue_prefill` 和 `continue_postfix`，Prefill 以最终 assistant 消息前缀发送。宏引擎支持嵌套读取、条件块、时间/历史宏和 legacy `<USER>/<CHAR>` 标记，并补齐 `allChatRange`、`idleDuration`、`timeDiff`、UTC 偏移、`random/pick/roll`、`trim`、`hasExtension` 等只读子集；随机与掷骰由请求级种子冻结。`setvar`、脚本执行、扩展副作用仍未开放，避免把第三方卡片当作宿主代码执行。

Quick Reply 已接入当前聊天宿主：v2 `qrList` 与旧 `quickReplySlots` 可登记并原始字段往返，启用组会显示在输入栏上方，`disableSend` 组可回填输入草稿，`injectInput`/`placeBeforeInput` 会在手动执行普通文本时合并当前输入。安全 STscript 子集支持 pipe/转义、宏与 local/global 变量持久化、`/if` 闭包、`/return`、`/run`、`/:`、`/qrset`、`/let`/`/var`、安全数学与有界 `/times`/`/while`，普通文本发送、`/send`/`/sendas`/`/sys`/`/comment`、`/addswipe`、Continue/Swipe/Regenerate/Impersonate/Trigger/Gen/GenRaw，以及 startup/chat-change/user/AI、before-generation、群成员草稿和 World Info automationId 自动触发。`/javascript`、文件/网络、第三方扩展命令、交互式 `/input`/popup、调试断点、QR context menu/管理命令和破坏性聊天管理命令仍会返回 blocked/unsupported 诊断；这些能力必须经过后续明确的 Android UI/权限端口，不能以“字段已导入”当作运行时兼容。

群聊核心已按 Tavo 当前公开语义提供 roster 与回合规划：自然聊天按 `@name` 优先、无提及时对未静音成员做稳定选择；全员回复返回全部未静音成员；指定发言者按显式角色、配置的 designated speaker 或提及角色解析；上下文选角返回带 `{{group}}` 的冻结选择提示，并只接受模型返回的已知成员。成员 JSON 支持启用/静音、权重、指定发言者和策略别名。当前 Android 宿主已将 roster 写入会话元数据，并在每次请求启动时解析一次，统一供 `{{group}}`（包含静音成员）、`{{groupNotMuted}}`、`{{notChar}}`、群聊系统块和插件回合工厂消费；同一请求内后续编辑不会改变快照。聊天页已提供会话级成员面板，可编辑群名、成员启用/静音状态、四种回复模式、指定发言者、最大回复数和上下文选角提示词，并可停用群聊；自然/全员/指定模式现在按冻结计划逐成员顺序生成，上下文模式先调用短选角请求后进入同一队列，停止、切会话或人格绑定变化会阻止后续成员继续生成。未安装角色卡会显示警告并跳过。群聊头像/成员专属 UI、Branch/Checkpoint 文件操作和更完整的 Tavo 自动模式仍是后续门禁，当前不宣称完整 UI parity。

聊天文件核心现在按 SillyTavern 当前 `ChatHeader`/`ChatMessage` JSONL 形状读写：首行 `chat_metadata`、消息 `mes/is_user/is_system/send_date/swipes/swipe_id/extra` 和未知字段均可往返，Tavo/旧客户端常见别名也会归一化；坏行不会静默丢失，而是通过行号诊断返回。当前 ST 的 `extra.type=comment`（以及兼容的 `hidden`/`is_hidden` 标记）会在聊天记录中保留，但从 provider 和 World Info 扫描中排除。Branch 会复制到目标消息并切换到新文件，Checkpoint 会复制但留在当前文件，父文件分别回写 `extra.branches` 或 `extra.bookmark_link`，并通过 `main_chat` 建立返回父聊天的链接。Android 宿主现已提供 SAF 导入/导出和消息级 Branch/Checkpoint 操作：system 角色以透明 provenance 字段保留并在 provider 序列化时使用 `system` role，swipes 映射为本地回复版本，原始 ChatHeader 写回会话元数据；分支/检查点创建会保留原生图片、思考、MCP 等字段，父消息与子会话在同一存储锁范围内提交，失败会回滚父消息并清理未登记子文件。导入会按 header 角色名精确匹配本地卡片，找不到时回退当前卡片并显示警告。群聊历史 UI 和导入后的跨设备资源重绑定仍是后续门禁。

## 当前模块与依赖方向

```text
:app
  ├─ :plugin-api
  ├─ :plugin-host
  ├─ :knowledge-core
  ├─ :plugins:tavern-core
  ├─ :plugins:tavern-storage
  └─ :plugins:tavern-ui

:plugin-host ──> :plugin-api
:knowledge-core ──> （无生产依赖）
:plugins:tavern-core ──> :plugin-api + :knowledge-core
:plugins:tavern-storage ──> （无生产依赖）
:plugins:tavern-ui ──> （无生产依赖）
```

- `:plugin-api`：稳定身份、能力、冻结回合、提示词 patch、生成 patch 与输出变换契约。
- `PersonaProjection`：跨宿主/插件的稳定人格展示投影；Tavern repository 直接返回该契约，不再暴露插件内重复的人格记录类型。
- `:plugin-host`：插件注册、代次管理、类型化 persona lease、停用排空和失败隔离。
- `:knowledge-core`：位于 `com.loyea.context.core` 的中立纯 Kotlin 运行时；当前保留 `WorldInfo*` 兼容命名，承载通用条目模型、匹配、预算、递归和深度注入，不依赖 Android 或 Tavern 实现。
- `:plugins:tavern-core`：位于独立 `com.loyea.plugins.tavern.core` 命名空间，不依赖 Android、Compose、ViewModel 或宿主消息模型；负责 Tavern 文档 codec、CharacterBook/Preset/Regex 和插件回合，并通过 `:knowledge-core` 投影通用知识上下文。
- `:plugins:tavern-storage`：位于 `com.loyea.plugins.tavern.storage` 的纯 Kotlin/JVM 私有存储边界，负责 registry/cards/assets 目录、SHA-256 文件校验、原子复制和迁移标记；不持有会话或消息。
- `:plugins:tavern-ui`：位于 `com.loyea.plugins.tavern.ui` 的纯 Kotlin/JVM 状态/事件边界，只保存弹窗和卡片 ID，不依赖 Android、Compose 或宿主 `CharacterCard`；界面副作用仍由 app 组合。
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
- Prompt Manager generation trigger、in-chat/depth slot、Continue Nudge/Prefill/Postfix，以及请求级宏和只读变量读取。
- Quick Reply v2 组/按钮、`disableSend`/`injectInput`、安全 STscript pipe/变量/条件/过程/消息/生成子集，以及已实现的自动触发边界。
- 群聊 roster、成员静音、四种回复模式、上下文选角提示与安全的 speaker 结果解析。
- 聊天 JSONL 首行元数据、消息/swipes/extra/未知字段往返，以及 Branch/Checkpoint 截断、父链和书签链接核心模型。
- Android 宿主提供会话级群聊成员面板与 roster 持久化、多角色顺序生成队列，以及消息级 Branch/Checkpoint 操作；群聊 avatar/member 专属 UI 与 Tavo 自动模式仍由后续请求协调器门禁覆盖。
- 流式回复、MCP 多轮、后台主动问候、长会话压缩、生图和记忆整理的插件租约接入。

## 尚未完成的物理拆分

当前已完成“运行时可拔插”和“核心算法模块化”，但以下代码仍位于 `:app`，不能据此宣称物理剥离全部完成：

- `CharacterCard` 中为旧 JSON 兼容保留的 Tavern 扩展字段。
- `ChatStorageManager` 中的 Tavern 世界书文件适配，以及迁移期间对 `CharacterCard` 投影的兼容读写。
- `TavernScreen`、`WorldInfoSettings` 及其对 `ChatViewModel` 的直接 UI 绑定。

第一阶段物理抽取已完成：`WorldInfoModels` 和 `WorldInfoMatcher` 已移入 `:knowledge-core`；公开 `WorldInfo*` 名称暂保留，确保本步不改变序列化数据或 Prompt 语义。

第二阶段存储边界已建立：旧 `tavern_resources.json` 首次访问时复制到 `files/tavern/registry` 并写入可校验迁移标记，源文件保留；非内置角色卡的规范化原始文档写入 `files/tavern/cards/<sha256>.json`。会话元数据、消息和会话世界书仍由宿主持有。

人格投影边界已收敛：`TavernCharacterCardAdapter` 同时提供 `CharacterCard → PersonaProjection` 与 `CharacterCard → TavernCardDocument` 两条单向路径；请求运行时只消费 `PersonaProjection`，完整 Tavern 字段和未知 JSON 仍留在文档路径。

UI 状态边界已建立：`:plugins:tavern-ui` 的 `TavernUiState`/`TavernUiEvent` 负责创建、资源管理、编辑和删除确认的互斥状态；`TavernScreen` 通过卡片 ID 归并状态，SAF、Toast、分享和 FileProvider 等 Android 副作用仍留在宿主。

后续按以下顺序推进，避免把数据迁移、包名重写与 UI 拆分混成一次不可回退的大改动：

1. 完成 `:plugins:tavern-storage` 的资源文件接管，并为旧 registry/raw card 文档补齐恢复与冲突可观测性；会话/消息仍由宿主持有。
2. 将 `CharacterCard` 中尚未迁出的 Tavern 扩展字段拆成原生 `PersonaSummary` 与插件私有 `TavernCardDocument`，通过 adapter 投影，保留一次性旧 JSON 迁移和原始备份。
3. 将 `TavernScreen` 的剩余渲染与 callback 控制面迁入 `:plugins:tavern-ui`，保留当前已抽出的 state/events；SAF、分享和 FileProvider 能力由宿主端口提供。
4. 清除宿主核心签名中的 Tavern/WorldInfo/Regex/Preset 具体类型，并增加依赖方向架构测试。
5. 不降级验收：按“批次 5 完成情况”的验收矩阵核对稳定版功能在插件挂载 / 停用下均不降级，新增酒馆功能在挂载下可用，并执行真机清单（D/T/C 系列）。

核心 package rename 已作为独立迁移完成；`:plugins:tavern-core:test` 会先执行命名空间与宿主 import 防回退门禁，不与后续存储格式迁移混在同一提交。

### 批次 4 完成情况（宿主核心签名去 Tavern 化 + 依赖方向架构测试）

`WorldInfo*` 类型属于 `:knowledge-core`（中立契约），不在本批次的清除目标内；真正的 tavern 具体类型泄漏集中在下表，已按“持久化核心可清除 / 适配面允许保留”两类处理：

- 已清除（宿主持久化核心 `ChatStorageManager` 的 4 处公开签名改为中性 JSON 传递）：
  - `ChatSession.tavernGroupChat(): TavernGroupChat?` 扩展移入宿主适配器 `TavernGroupReplyCoordinator`。
  - `updateSessionGroupChat(…, TavernGroupChat?)` → `updateSessionGroupChatJson(…, groupChatJson: String?)`；编解码由 Tavern 控制面 `ChatViewModel` 负责。
  - `loadTavernResourceRegistry(): TavernResourceRegistry` / `saveTavernResourceRegistry(TavernResourceRegistry)` → `loadTavernResourceRegistryJson(): String?` / `saveTavernResourceRegistryJson(json: String)`；默认值与解析由控制面补。
  - `WorldInfoConfig.kt` 的通配 `import com.loyea.plugins.tavern.core.*` 是死代码，已删除。
- 已确认零签名耦合（tavern 类型只出现在 private/局部，作为架构测试的正向锁定对象）：`GreetingWorker`、`WorldInfoSettings`、`ChatScreen`。
- 保留为允许边界（适配器 / 组合根 / Tavern 控制面 / 迁移桥，签名可合法携带 tavern 类型）：`AppTavernPersonaRepository`（实现 tavern 仓库契约）、`LoyeaApplication.prepareTavernPersonaTurn(…, TavernTurnSpec)`（组合根桥）、`ChatViewModel` 的 tavern 公开 API（控制面）、`PromptAssembler`（提示词模板迁移桥）及全部 `Tavern*` 宿主适配器文件。后续批次若迁出这些控制面/桥，再收敛白名单。

新增依赖方向架构测试 `app/src/test/.../architecture/HostCoreDependencyDirectionTest`（不靠 import 文本 grep，直接检查编译产物签名）：

1. `stableContractsNeverReferenceTavernTypes`：plugin-api / plugin-host / knowledge-core 的所有类签名不得引用 `com.loyea.plugins.tavern.*`。
2. `hostCoreSignaturesDoNotExposeTavernTypes`：`ChatStorageManager` / `WorldInfoConfig` / `GreetingWorker` / `WorldInfoSettings` / `ChatScreen` 的 public+internal 签名不得携带 tavern 类型（允许 private/局部实现引用）。
3. `onlyAdapterSourcesExposeTavernTypesInSignatures`：app 任意类的 public+internal 签名若引用 tavern 类型，其源文件必须在适配器 / 组合根 / 控制面 / 迁移桥白名单内。

实现要点：从 class 字节读取 `SourceFile` 属性以精确映射“编译类→源文件”；`Modifier.isPublic` 同时覆盖 Kotlin public 与 internal 成员；跳过合成成员、lambda/匿名类（`$<数字>`）与 Kotlin private 顶层类（编译为包私有）；作用域内类加载失败会大声失败而非静默漏检。测试运行时从测试 classpath 定位模块编译产物（目录或 jar 统一处理）。

## 验收门禁

每个迁移提交都必须满足：

```powershell
.\gradlew.bat --no-daemon --no-build-cache --rerun-tasks :plugin-api:test :plugin-host:test :knowledge-core:test :plugins:tavern-core:test :plugins:tavern-storage:test :plugins:tavern-ui:test :app:testDebugUnitTest
```

其中 `:knowledge-core:test` 会先执行命名空间和宿主/Tavern 禁止导入门禁，确保中立知识核心不会重新依赖具体插件或 Android 宿主实现。

### 批次 5 完成情况（不降级验收矩阵 + 真机清单）

“不降级”按核心原则拆成两个方向验收：**稳定版已有功能在插件挂载时行为不变**（拆了再插上，原生功能照旧），**新增酒馆功能在插件挂载时可用**（新能力真实生效）。下表把每个功能域映射到两种验证手段：JVM 锁定测试（进 gate，自动化回归）与真机步骤（人工验收，`D`=稳定回归、`T`=Tavern 新增、`C`=停用回归）。JVM 绿不能替代真机验收，尤其是 WorkManager 调度、文件迁移与快速连点竞态。

| 功能域 | 归属 | 插件状态 | JVM 锁定（gate 内） | 真机 |
| --- | --- | --- | --- | --- |
| 主聊天发送 / 流式渲染 | 原生稳定 | 挂载 | `LlmConversationBuilderTest`、`MainChatTavernLeaseParityTest` | D1 |
| Continue / Swipe / Regenerate | 原生稳定 | 挂载 | `GenerationRequestMapperTest` | D2 |
| Impersonate 代发草稿 | 原生稳定 | 挂载 | （人工） | D3 |
| 消息时间本地化 / 30 分钟分隔 | 原生稳定 | 皆可 | `MessageTimeFormatterTest`、`ConversationTimelineFormatterTest` | D4 |
| LaTeX→Unicode / Markdown | 原生稳定 | 皆可 | `LatexToUnicodeTest` | — |
| 输入框超 3 行扩展 | 原生稳定 | 皆可 | `ChatInputUiLogicTest` | D5 |
| API 配置解析 / 模型 Token 下拉 | 原生稳定 | 皆可 | `ApiConfigResolverTest` | D6 |
| MCP 工具路由 / payload 规范化 | 原生稳定 | 挂载 | `McpRoutingTest`、`McpConfigStorageTest`、`LlmRequestCanonicalizerTest` | D7 |
| 图记忆四维命名空间隔离 | 原生稳定 | 挂载 | `GraphMemoryManagerTest`、`MemoryAccessPolicyTest` | D8 |
| 记忆整理（手动 + 自动） | 原生稳定 | 挂载 | `MemoryConsolidationWorkerTest`、`BackgroundPromptTemplatesTest` | D9 |
| 后台主动问候 | 原生稳定 | 挂载 | `BackgroundPromptTemplatesTest` | D10 |
| 长会话压缩 / 快照失效 | 原生稳定 | 挂载 | `BackgroundPromptTemplatesTest` | D11 |
| 生图（插件人格租约） | 原生稳定 | 挂载 | （人工） | D12 |
| 人格绑定 / 身份围栏 / ABA | 原生稳定 | 挂载 | `AppTavernPersonaRepositoryTest`、`PersonaSummaryProjectionTest`、`PersonaSummarySplitMigrationTest` | D13 |
| 原生世界书匹配 / 提示词组装 | 原生稳定 | 挂载 | `WorldInfoMatcherTest`、`PromptAssemblerTest` | D14 |
| 插件启停 / 排空 / 重启保持 | 框架 | 皆可 | `PluginManagerTest`、`PersistentPluginControllerTest`、`PluginEnablementStoreTest`、`PersonaTurnLeaseTest` | D15 |
| 依赖方向 / 签名边界 | 框架 | 皆可 | `HostCoreDependencyDirectionTest` + 各模块 verify 边界 gate | — |
| 会话列表 / 查询 / 回复输出过滤 | 原生稳定 | 皆可 | `ChatSessionQueryTest`、`ReplyOutputSanitizerTest` | — |
| 角色卡 V1/V2/V3 JSON / PNG / CHARX 导入导出 | Tavern 新增 | 需开 | `TavernCardCodecTest`、`TavernCardParserTest`、`TavernCardWireFormatTest`、`TavernCardCodecIsolationTest` | T1 |
| CharacterBook / World Info 绑定运行时 | Tavern 新增 | 需开 | `TavernResourceCodecTest`、`WorldInfoMatcherTest` | T2 |
| Preset prompt / 生成参数覆盖 | Tavern 新增 | 需开 | `TavernPresetCodecTest`、`TavernPresetCoreTest`、`TavernPresetEditorTest`、`TavernCardPresetAdapterTest` | T3 |
| Regex 输入 / 输出链 | Tavern 新增 | 需开 | `TavernRegexEngineTest`、`TavernRegexCoreTest`、`TavernCardRegexAdapterTest` | T4 |
| Quick Reply / 安全 STscript 子集 | Tavern 新增 | 需开 | `TavernQuickReplyTest` | T5 |
| 请求级宏运行时 | Tavern 新增 | 需开 | `TavernMacroRuntimeParityTest` | T6 |
| 群聊 roster / 四种回复模式 / 选角 | Tavern 新增 | 需开 | `TavernGroupChatTest`、`TavernGroupReplyCoordinatorTest` | T7 |
| 聊天 JSONL / Branch / Checkpoint | Tavern 新增 | 需开 | `TavernChatFileCodecTest`、`TavernChatExportCodecTest`、`TavernChatLifecyclePlannerTest`、`TavernChatSessionCodecTest`、`TavernChatStatisticsTest` | T8 |
| 外部资源注册表 / 下载器 | Tavern 新增 | 需开 | `TavernResourceCodecTest`、`TavernCardDownloaderTest`、`TavernCardUrlResolverTest` | T9 |
| 存储边界 / 迁移 / 恢复 | Tavern 新增 | 需开 | `TavernStorageMigrationTest`、`TavernStorageRecoveryTest`、`TavernCardDocumentStoreTest` | T10 |
| Tavern UI 状态 / 文本边界 | Tavern 新增 | 需开 | `TavernUiStateTest`、`TavernUiTextTest`、`TavernPluginDescriptorTest` | T11 |
| 插件运行时 / 描述符自述 | Tavern 新增 | 需开 | `TavernPluginRuntimeTest`、`TavernPluginDescriptorTest` | — |

真机清单（按序执行，每一项通过才算该功能域不降级）：

**D 系列 —— 插件挂载下的稳定版回归**
- D1 以 Loyea 原生人格发送消息：流式渲染、思考块、Markdown 排版、生成中停止、回复落盘与重启后可见。
- D2 对最后一条 AI 回复依次 Continue / Swipe / Regenerate：气泡追加、版本归并、World Info 生成类型过滤看到真实请求。
- D3 Impersonate：代发草稿只回填输入框，点击发送后才写入聊天，失败不留伪造用户消息。
- D4 同天 / 昨天 / 前天 / 跨年时间格式正确，时间间隔分隔条正确分组。
- D5 输入文本超过三行（含自动折行）才出现全屏编辑按钮。
- D6 多 provider 切换、模型 / Token 下拉布局与用量显示正常。
- D7 启用工具后一轮对话多次 MCP 工具调用，工具结果进入上下文且不重复时间 token。
- D8 图记忆新记录按 owner / persona / incarnation / revision 隔离；删除重建与 A→B→A 重绑不复活旧记忆。
- D9 手动 + 自动记忆总结均入队 WorkManager，失败保留弹窗并提示。
- D10 后台主动问候在杀进程恢复后幂等，不重复生成、不重复计费。
- D11 长会话压缩触发时历史前缀按语义变化正确失效。
- D12 插件人格下生图成功、任务完成写回，身份围栏拒绝跨绑定写回。
- D13 删除重建 / 重绑期间的旧流式写回被拒；旧会话 owner 迁移为 `loyea.native` 后功能正常。
- D14 全局 / 会话世界书匹配、递归、timed effects、Include Names 与预设覆盖符合稳定版行为。
- D15 设置页快速连续启停：停用时在途流式请求排空、重启后保持停用、停用不删除已导入数据、重新启用后 UI 入口与功能恢复。

**T 系列 —— 插件挂载下的 Tavern 新增功能**
- T1 导入真实 PNG（`chara`/`ccv3`）与 V3 CHARX，再标准导出；未知字段、CharacterBook、扩展字段往返不丢。
- T2 内嵌 CharacterBook 与外部世界书合并，`selective`/`constant`/正则/概率/sticky/cooldown/delay 生效。
- T3 导入 preset：prompt order、post-history、采样参数覆盖进入真实请求。
- T4 Regex 各 placement 受控执行，AI 输出 `[MESSAGE TIME: ...]` 元数据被过滤。
- T5 Quick Reply 组登记、`disableSend`/`injectInput`、安全 STscript 命令子集生效，危险命令返回 blocked 诊断。
- T6 `{{group}}`、时间 / 历史宏、请求级种子随机在 Prompt 与 Regex 间保持一致。
- T7 成员面板编辑 roster、四种回复模式顺序生成、上下文选角先选角后入队。
- T8 SAF 导入 / 导出 JSONL、Branch / Checkpoint 截断与父链、坏行行号诊断、system 消息 provenance 保留。
- T9 外部世界书 / preset / Regex collection 导入并绑定卡片资源解析。
- T10 旧 `tavern_resources.json` 首访迁移到 `files/tavern/registry` 且源文件保留；非内置卡写入 `cards/<sha256>.json`；冲突保留 / 恢复可观测。
- T11 创建 / 资源管理 / 编辑 / 删除确认弹窗互斥，不互相泄漏选择状态。

**C 系列 —— 插件停用回归（稳定版不受影响）**
- C1 停用后以原生人格继续主聊天，流式、记忆、问候、压缩、生图全部可用。
- C2 停用后 Tavern 管理 UI 入口隐藏，重启仍保持停用。
- C3 停用不删除已导入的角色卡 / 资源；重新启用后原数据可继续使用。

执行上述清单前，先跑一次全文 gate（见上）并确认全部 JVM 锁定测试绿；任何一项真机失败都按“有功能降级”对待，先修复再合入，不能以“仅 UI 差异”放行。
