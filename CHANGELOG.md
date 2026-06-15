# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-06-15

### Added (新增)
- **长程知识图谱关系记忆 (Graph RAG) 系统**：
  - 引入了基于文件系统 JSON 持久化存储的 [GraphMemoryManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/memory/GraphMemoryManager.kt) 和 [MemoryTriple.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/memory/MemoryTriple.kt)。支持 1-Hop 与 2-Hop 关联检索、以及基于艾宾浩斯遗忘曲线的轻量化记忆权重动态衰减计算，并在单次召回中严格进行 8 条上限剪枝，最大化精简 Token 消耗。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中打通图谱提取链路。在闲时触发的自动记忆整理 `triggerMemorySummaryInternal` 完毕后，同步触发 `triggerGraphMemoryExtraction` 请求大模型提取三元组，并且加入了 JSON 容错与解析异常自愈机制，防止脏数据干扰和死循环。
  - 前台发起对话前，从长程关系图谱中智能检索关联的记忆，并拼装传入 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt)。在物理感知总开关关闭时，自动执行健康隐私过滤裁剪，彻底杜绝数据越权穿透。
- **声学情绪共感感知系统**：
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 与 `LlmClient` 之间增加了 `currentVoiceEmotion` 语气与临时情感缓存层。在切换会话时，主动对该情感缓存执行原子化 `clear()`，避免情感底色交叉污染。
  - 当用户输入语音并转换文字（STT）时，系统自动识别其语音中的模拟情绪（伤心、生气、开心、温柔、慵懒、中性等），在发起对话时以 `[Acoustic Emotion]` 语气标签的形式无感注入，并在大模型本次回复完成后自动重置缓存。
- **脑内心智与共情系统 UI 二级控制卡片**：
  - 在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 增加“脑内心智与共情系统”子面板。提供长程关系图谱（Graph RAG）和声学情绪感知的独立二级控制开关，完美保障了用户对 AI 功能 of 的自主掌控权与隐私控制感。

### Fixed (修复)
- **物理手表模拟健康数据越权泄漏拦截**：
  - 修复了即使在设置中关闭了“启用手表连接与同步”（链接手表）开关后，AI 的主动/被动感知仍能获取到 `[Simulated]` 后缀模拟心率和步数数据的严重缺陷。
  - 在 [BluetoothWatchProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/BluetoothWatchProvider.kt) 中重构了 `getHeartRateBpm()` 的降级模拟生成条件，将其严格绑定在独立的 `sim_watch_connected` 模拟开关中，避免与蓝牙连接状态混淆。
  - 在 [PerceptionMcpServer.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PerceptionMcpServer.kt) 的 `get_health_data` 工具调用以及 [PhysicalContextManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PhysicalContextManager.kt) 的被动感知组装逻辑中，引入了更严格的 `isWatchSyncEnabled` 独立卡口。当用户已关闭手表同步且没有连接真实的蓝牙物理手表时，强行禁止生成或拼装任何 `[Simulated]` 的模拟数据，仅返回系统底层原始 Health Connect 状态（如 `Permission Denied`、`No Data`），彻底实现了数据层与交互状态的权限一致性闭环。

### Changed (变更)
- **语音条文字显示功能 Claude 美学重构**：
  - 在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 中重构了 `McpVoiceReplyItem` 组件成功（SUCCESS）状态下的渲染风格。
  - 移除了传统的 ad-hoc 主题风格，定制了专属的 Claude 温暖沙色与纸张质感的极简卡片 UI，支持亮暗主题自动适配。
  - **流动延展宽度与背景色过渡动效（提升多端 UI 兼容性）**：引入了基于状态驱动的 `widthFraction`（0.65f 至 1.0f 渐变）与 `backgroundColor`（温暖沙色到卡片白/灰背景渐变）动画，配合 `animateContentSize()`。使得语音条在未展开时呈现为宽度仅 65% 的短小精致小胶囊，而在点击展开文本后像纸面舒展一般平滑拉宽至 100% 占满，完美兼顾了窄屏手机与平板/宽屏等多端设备的阅读舒适度与动效高级感。
  - 为展开/收折箭头添加了优雅的旋转渐变动效（`arrowRotation`），并实现了三柱波形到四柱波形更加灵动的播放动效。
  - 利用 `IntrinsicSize.Min` 布局机制实现了自适应文本高度的沙色竖引线（Quote Line），增强文字阅读的杂志感与排版品质感。
  - 重新设计了极简的细边框复制图标及文本药丸按钮（"复制"），提供更精巧的交互细节。
  - 增强了 `cleanVoiceText` 语气标签清洗规则，深度兼容对大括号（`{...}`）、尖括号（`<...>`）、小括号及中括号等多重语气/呼吸助词标签（如 `{吸气}`、`<叹气>`）的提取净化，确保文本转写呈现干净无杂质。


  - 在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt) 中，当 `useSystemTime`（物理感知总开关）为 `false` 时，新增注入强力的 `[PHYSICAL PERCEPTION DISABLED / 物理感知功能已被禁用]` 心理钢印与行为指引系统 Prompt。
  - 严厉规训大模型在收到“是否能调用外部工具/获取物理信息”时，必须诚实、温和地告知用户物理感知权限已被关闭、自己无法访问或触发相关工具。彻底杜绝了模型依靠基座常识脑补伪造身体参数、或者谎称自己拥有这些本地数据与工具调用权限的行为幻觉，实现了端侧控制与 AI 认知的一致性。
- **侧边栏菜单隐藏参数与编译错误修复**：
  - 修复并补全了 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 签名中的 `showMenuIcon: Boolean = true` 属性，解决了 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt) 界面中在双栏与单栏模式下调用 `ChatScreen` 时缺失参数导致的重大编译失败。
  - 实现 TopAppBar 根据 `showMenuIcon` 的状态对侧栏导航菜单（Menu Icon）进行动态显隐，保证了双栏平板模式下自动隐藏菜单栏、防止多余侧边抽屉按钮干扰的优秀交互逻辑。
- **物理感知总开关完全统筹限制**：
  - 在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt) 中重构了系统提示词拼装逻辑，在 `useSystemTime`（物理感知总开关）为关闭状态时，彻底抹除时间戳（System Time）、物理上下文（Physical Context）的注入，且在可用工具列表中不再包含位置、天气、环境、设备、蓝牙、健康等物理外设/传感器工具声明。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中，当物理感知总开关关闭时，阻止向 `PromptAssembler` 传入最近工具调用缓存。并且在过滤 `availableMcpTools` 时，除了 `web_search` 和 `send_voice_reply` 之外，全面排除所有与物理传感器和定位相关的本地内置工具，实现全面的隐私阻断与权限统筹。
- **实时 XML 工具调用流式截断与脑补消除（含防丢字补发优化）**：
  - 修改 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 中的流式解析，一旦在大模型正文流输出中发现已存在闭合的 XML 工具调用（即含有完成的 `</tool_call>` 或 `<tool_invocation />`），立即主动 `break` 截断响应流以提前终止输出，迫使大模型进入工具执行轮次并反馈结果，彻底解决了大模型因无法即时获取工具响应而在同一次输出中“反复生成 5~6 次相同工具”或“脑补伪造工具结果”的问题。
  - 同时，在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 Done 状态发射前，增加了对流截断时可能滞留在缓冲区内的 thoughts 和 visibleContent 差量补发逻辑，彻底消除了流提前断开时极个别情况下的“丢字、漏字”隐患。
- **API 多模态兼容与报文格式自愈（含多工具交替合并）**：
  - 在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 中实现 `sanitizeMessages` 自愈转换函数。当本次发送的可用 `tools` 列表为空时（例如在传图等原生工具被关闭的模式下），自动将历史消息中包含的原生 `tool_calls` 消息降级序列化为文本中的 XML 格式。
  - 特别优化了合并逻辑，在 `sanitizeMessages` 自愈翻译中，将可能连续出现的多个 `tool` 响应消息（如一次性发起多个工具并行调用时）压缩合并为一条单独的 `role = "user"` 环境感知输入。防止了因连续生成多条 `user` 角色消息导致某些对 Role 交替校验极其严格的第三方大模型 API 崩溃报错，提升了消息链路的健壮度。
- **本地物理工具调用智能容错匹配**：
  - 重构了 [McpManager.kt](file:///D:/CodingProjects/Android/Loyea/mcp/McpManager.kt) 中的 `callTool` 分发逻辑，优先在本地 `perceptionServer` 中查找匹配工具（忽略大小写，并兼容 `get_location` 和带 `BuiltinPerception__` 前缀的工具名形式）。这使得大模型即便因为拼写错误或漏写前缀时，依然可以被智能地匹配到本地正确的物理感知工具并正常执行。
- **本地健康工具状态强一致性补全**：
  - 重构了 [PerceptionMcpServer.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PerceptionMcpServer.kt) 中 `get_health_data` 工具调用的解析。将原本仅仅读取手机本地健康数据的逻辑，同步补全为**支持智能手表蓝牙睡眠数据与今日运动流数据的提取和降级支持**，从而使 AI 主动感知的 Context 内容与被动调用工具查询获取的数据状态实现 100% 的同步与强一致。
- **自愈式反序列化防止旧消息空指针闪退**：
  - 在 [ChatStorageManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt) 的 `loadSessionMessagesInternal` 消息加载逻辑中，增加了对 `versions` 和 `mcpCalls` 字段的 `null` 自愈清洗。彻底解决了由于旧版本聊天消息 JSON 中缺失 `versions` 新增字段，导致 Gson 反序列化绕过 Kotlin 默认值将该字段赋予运行时的 `null`，进而在主界面渲染消息列表（执行 `message.versions.size`）时引发的空指针崩溃（NullPointerException）闪退问题。

## [Unreleased] - 2026-06-13

### Added (新增)
- **手表连接健康数据全链路打通**：
  - 在 [WatchBluetoothClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/bluetooth/WatchBluetoothClient.kt) 中扩展了睡眠数据流（`sleepDuration`、`sleepQuality`）和运动数据流（`exerciseDuration`、`exerciseCalories`、`exerciseType`）。
  - 支持解析手表端上传的实时 `"SLEEP"` 与 `"EXERCISE"` 指标 JSON，并在拉取 `"RECENT_DATA"` 时完整反序列化睡眠和运动概要。
- **经典蓝牙 RFCOMM 指数退避重连机制**：
  - 在 [WatchBluetoothClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/bluetooth/WatchBluetoothClient.kt) 中实现了基于 Kotlin 协程和指数退避（5s、10s、20s）的 `triggerAutoReconnect` 后台自动重连机制。在 Socket 连接初建失败或中途意外断开时静默拉起重连，显著提升了物理设备的连接鲁棒性，并在用户主动点击“断开”时自动终止重连。

### Changed (变更)
- **AI 提示词多维健康状态物理注入**：
  - 重构 [PhysicalContextManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PhysicalContextManager.kt) 中的 `buildPhysicalContextString()`。当蓝牙手表处于已连接状态时，**优先提取手表上传的历史睡眠时长/质量、今日运动时间/运动卡路里/运动状态** 并注入给 AI 提示词，摆脱对手机本地 Health Connect 的单一依赖，实现无感的数据生态互联。
- **语音重合成期间调试气泡闪烁与消散 Bug 修复**：
  - 修复了在程序一打开时，若本地缓存缺失，后台异步重新合成语音的等待过渡期间，因 `hasVoiceUrl == false` 导致语音消息错误地退化渲染为普通的绿色 MCP 调试气泡（如 `[send_voice_reply] Success`）在屏幕底端疯狂弹出并在合成完后又消散闪烁的 Bug。
  - 重构了 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L1753-L1776) 中 `McpVoiceReplyItem` 成功但缺失 Url 分支下的渲染。在后台合成语音就绪期间展示为优雅平滑的“`语音加载中...`”占位加载条，彻底消除了界面闪烁与冗余调试气泡的弹出，极大净化了 UI 交互体验。




### Added (新增)
- **会话标题 AI 智能总结**：
  - 在 [ChatStorageManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt) 的 `ChatSession` 模型中新增 `isTitleSummarized` 字段以判断标题是否已由 AI 总结。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中实现异步总结标题的函数 `summarizeSessionTitleAsync`，并在流式会话结束（`StreamEvent.Done` 且没有未完的工具流）时对未总结过的会话发送后台大模型总结请求，生成 4 到 8 字的精致精炼标题。

### Added (新增)
- **重新生成多版本回复与左右翻页查看功能**：
  - **多版本数据模型升级**：在 [Message.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/Message.kt) 中定义了 `MessageVersion` 结构，并在 `Message` 中新增了 `versions: List<MessageVersion>` 以及 `activeVersionIndex: Int`，具备与 Gson 的无缝向后反序列化兼容性，保障了历史聊天记录的安全。
  - **流式 ID 复用与重新生成逻辑**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中扩展了 `startAiResponseStream`，支持传入 `regenerateMessageId` 参数以复用已存在的 AI 消息 ID。当点击重新生成时，自动截断后面的对话并备份当前所有版本，将对应消息置为思考状态并发起 LLM 生成（上下文只包含该 AI 回复之前的历史）。
  - **会话结束自动版本写入**：在流式生成彻底结束（`StreamEvent.Done`）时，自动将最新的顶层数据（文字、思考链、工具调用）写入或覆盖到 `versions` 的对应槽中并持久化。
  - **多版本无缝切换与音频自愈**：在 ViewModel 中实现了 `switchMessageVersion` 接口，支持切换版本时自动停止正在播放的音频，并将对应版本的 `MessageVersion` 快照回写到顶层字段以在 UI 上实时无感渲染。
  - **交互翻页器与点赞移除**：在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 消息动作条最左侧嵌入了 `< 1 / 2 >` 的精致版本翻页器（仅在有多个回复版本时显现），并将点赞按钮删除，绑定了重新生成按钮的 `onRegenerate` 物理事件，实现了完美的现代化大模型客户端体验。

### Added (新增)
- **大模型实时工具调用积极性与语音合成自愈过滤算法大升级**：
  - **禁止历史缓存复用提示词注入**：在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt) 的工具调用规范中新增硬性限制条件，明确指出对话历史中的工具结果仅为历史快照。严厉命令 AI 每当用户提问中出现“现在”、“当前”、“今天”或需使用对应数据回答时，**必须重新发起对应工具的实时查询调用**，严禁依赖或直接复读历史过期数据，以此大幅提升工具调用的积极性与新鲜度。
  - **语音合成调用规范严密约束**：在 System Prompt 语音生成说明中注入强力排他约束。硬性要求大模型一旦调用 `BuiltinPerception__send_voice_reply` 语音工具，**必须把真正的发言文字直接且仅传入 `text` 参数**。常规正文文本必须留空（首选）或仅输出括号动作，严禁在常规文本正文中复读一遍或吐出诸如“语音回复已发送”等 placeholder 废话。
  - **零垃圾标签自愈过滤算法 `cleanVoiceStateLabels`**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中实现基于正则表达式的 `cleanVoiceStateLabels` 正文净化过滤器。自适应剔除大模型在正文和 TTS 朗读中误带出的 `[发送语音中...]`、`(发送语音)`、`（发送语音）` 等全部形式的冗余动作状态标签，提供百分之百的纯净文本流展示与朗读。
  - **全链路过滤自愈打通**：在流式临时 UI 展示、工具追加历史记忆、以及会话 Done 物理存档和朗读发声等全栈主要数据通道全部接入 `cleanVoiceStateLabels` 过滤，实现了完美的自愈闭环。

### Changed (变更)
- **移除普通 AI 文本回复朗读时的冗余语音条与合成状态条**：
  - 在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 中移除了当 `!message.audioUrl.isNullOrBlank()` 或 `message.isAudioSynthesizing` 时，在普通 assistant 文本消息下方额外渲染的 AI 语音条和合成状态占位条。
  - 普通文本回复朗读时的播放状态完全收拢在消息动作栏底部的 **Speak（朗读）按钮** 本身（带内置的 14.dp 极细 CircularProgressIndicator 加载环以及三柱音频波形律动动画进行视觉指示）。
  - 只有 AI 通过工具（如 `send_voice_reply`）主动发出的语音消息，才会在 AI 气泡底端展示专属的 [McpVoiceReplyItem](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L1722) 语音条，彻底理顺了两种语音展示的交互逻辑。
- **侧边栏高度与折叠自适应重构（防止手机横屏强制双栏常驻）**：
  - **精细化双栏门槛判定**：在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt) 中将 `useTwoPane` 触发条件从单纯宽度判定升级为宽度和高度双重判定（`screenWidthDp >= 720 && screenHeightDp >= 500`）。这能智能区分“高宽充足的平板/折叠屏大屏”与“极矮局促的手机横屏”。
  - **手机横屏降级抽屉模式**：现代矮胖手机在横屏状态下不再强行采用常驻双栏（从而避免了聊天内容被挤成细长窄缝且无法收回侧栏的顽疾），而是自适应降级为单栏抽屉（`ModalNavigationDrawer`）。
  - **顶部显式折叠关闭按钮**：在 [SidebarContent](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L233-L247) 顶部用户信息右侧，专门针对抽屉模式（`!useTwoPane`）引入了一个精致且极度直观的 `ChevronLeft`（向左折叠）关闭按钮。支持点击通过 `onCloseDrawer` 直接把抽屉推回收起，彻底消除了横屏下不知道如何收回或觉得侧栏收不回去的体验困惑。
- **侧边栏布局高度自适应重构（两全其美布局）**：
  - 在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt) 的 [SidebarContent](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L233-L247) 中引入基于屏幕高度的自适应布局判定（`androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 550`）。
  - **高度受限（如手机横屏）时**：沿用单一滚动链 `LazyColumn`，让底部卡片（物理感知、人格舱、系统设置）流式排在历史列表最下方，随滚动条滚动。从而防止在手机横屏极矮高度下被强行顶死、无法展现历史列表的严重缺陷。
  - **高度充足（如竖屏、所有平板模式）时**：采用置底固定设计，使用 `weight(1f)` 的 `LazyColumn` 让历史列表占满剩余可用高度，而控制面板卡片则常驻固定在屏幕底端。既防止了会话多时控制面板被挤到最底下的不便，又保障了高宽屏下极具品质感和实用性的操控体验。
  - **代码去耦复用**：将用户信息 Row、底部控制面板、历史列表渲染范围提取为 `UserInfoBar`、`BottomControlPanel` 和 `LazyListScope.renderHistoryItems` 局部扩展组件。在避免任何代码冗余的前提下优雅达成了两套结构的无缝自愈路由。
- **历史会话按最新活跃时间（lastActiveTime）排序**：
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中，每当消息发送、接收并保存，或是新开对话时，动态更新 `ChatSession` 的 `lastActiveTime = System.currentTimeMillis()`。
  - 列表在内存和持久化写入时一律通过 `sortedByDescending { it.lastActiveTime }` 按照最后活动时间重排，解决原版会话按首次创建时间死板排列的问题，确保活跃会话始终置顶。

### Fixed (修复)
- **工具调用（如天气、搜索）失效与过度自我审查修复**：
  - **重构工具调用指南 `[TOOL USE GUIDELINE]`**：在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt#L114) 中，将之前过于强硬恐吓、容易造成大模型过度自我审查的负向指令替换为温和且极具明确指示性的场景触发引导（例如“当用户问起实时天气时必须调用 `BuiltinPerception__get_live_weather`”），彻底消除了大模型的调用心理负担。
  - **引入文本 XML 格式调用双轨 Fallback 机制**：在提示词中公开说明除了标准 API tool_calls 之外，还支持直接在输出中生成 XML 格式 `<tool_call>ToolName(args)</tool_call>` 进行双轨触发，消除了大模型对于特定 API Schema 的依赖性，极大提升了各种中转 API 的工具调用兼容性与稳定性。
  - **明确放行工具调用标签**：在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt#L125) 中明确在方括号限制中放行尖括号标签（即 `<tool_call>` 和 `<think>`），避免模型在输出工具调用标签时产生合规冲突。
  - **加载工具授权状态**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L400) 的 `init` 阶段，补齐了从 SharedPreferences 中加载 `toolAuthWeather` 等全部 8 个工具权限值的缺失，彻底解决由于状态在重启后脱节导致实际运行权限未生效的 Bug。

### Added (新增)
- **横屏与平板大屏自适应屏幕适配**：
  - **MainScreen 双栏与单栏智能适配**：在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L80) 中引入 `useTwoPane` 双栏自适应机制。当宽度门槛 `screenWidthDp >= 720.dp`（代表平板大屏或横屏折叠屏）时，采用 `Row` 双栏，左侧常驻 `SidebarContent` 列表，右侧隐藏顶栏汉堡菜单展示聊天区；在普通手机横屏（宽度 < 720dp）与窄屏下，降级为单栏抽屉设计，确保聊天气泡宽度足够而不被强行挤压。
  - **手机/平板横屏软键盘弹起隐藏顶栏自愈**：在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L100) 中对手机与平板在横屏下（`isLandscape && isKeyboardVisible`）且软键盘弹起时，智能隐藏 Scaffold 顶部的 TopAppBar。腾出宝贵的垂直高度以彻底解决横屏下键盘把输入框和消息列表挤扁成窄缝的体验痛点。
  - **聊天流与输入控件自适应限宽居中**：在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L210) 中，将聊天消息流 `LazyColumn`、已选图片预览区、正在录音控制面板以及底端的 `ChatInputBar` 全局在宽屏（`screenWidthDp >= 600.dp`）下限制最大显示宽度为 `720.dp` 并自动居中对齐，杜绝控件被横向无上限拉伸的问题。
  - **TavernScreen 角色酒馆自适应网格化**：在 [TavernScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/TavernScreen.kt#L205) 中，根据屏幕宽度动态决定角色卡列表的列数（小于 600dp 显示 1 列，大于 600dp 显示 2 列，大于 900dp 显示 3 列），并自适应采用 `LazyVerticalGrid` 网格布局平铺展示，防止大屏下角色卡片严重被拉宽。
  - **SettingsScreen 设置大屏自适应限宽**：在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt#L120) 顶层 `AnimatedContent` 容器外层增加居中 `Box` 并限制大屏下的最大宽度为 `720.dp`，改善设置选项在大屏横向无限拉伸导致操控困难的问题。
- **多模态与媒体设置页彻底重构与多厂商 TTS API 深度对接模板设计**：
  - **高档卡片化模块布局**：彻底重构了多模态与媒体设置页（`MultimodalSettingsLayout`）的界面，将原先凌乱散落的表单重构为四个独立、精致的高档卡片式布局（语音合成 TTS、语音输入 STT、视觉图片理解、AI生图），每个卡片带有独特的图标和描边设计，极大提升了视觉和交互的美学标准。
  - **主流服务商 API 协议模板**：为主流的 TTS 服务商量身定制了底层的对接模板，支持“自动检测 (Auto)”、“OpenAI 官方”、“小米 MiMo”、“阿里百炼 (DashScope)”和“火山引擎 (豆包)”以及“完全自定义”等六大模板协议。UI 会根据用户选择自动渲染并提供相应的模型 and 音色快捷选择选项，完美防范了参数错配。
  - **候选模型与音色快捷 Chips/RadioButton 视图**：在 TTS 卡片内设计了根据所选模板协议自动浮现的候选模型与音色快捷选择组件。候选模型通过水平滚动的 Chip 呈现，点击一键选定；候选音色采用两列整齐排布的 Grid 卡片（自带 RadioButton 勾选状态并标注对应的 API 内部音色 ID），点击即可完成与输入框的智能绑定，同时保留了输入框完全自定义的自由度。
  - **一键云端接口模板同步功能**：在多模态页面顶部引入了“云端接口模板配置”操作栏。支持通过 jsDelivr CDN/GitHub 双渠道高可用拉取最新维护的接口配置 JSON，一键更新本地 SharedPreferences 缓存中的模型与音色候选数据，并向用户实时反馈“正在拉取...”、“同步成功”、“更新失败”等详细提示。
  - **阿里 DashScope (百炼) 语音合成 HTTP REST 接口对接**：在 `LlmClient` 语音生成逻辑中新增了对阿里云百炼语音合成官方 SpeechSynthesizer REST 接口（`/api/v1/services/audio/tts/SpeechSynthesizer`）的直接支持，实现了输入文本（input.text）与音色控制参数（parameters.voice、format: "mp3" 等）的精确匹配，直接保存返回的音频二进制流。
  - **火山引擎 (豆包) 语音合成 HTTP V1 接口对接**：在 `LlmClient` 中新增了对火山引擎语音合成 HTTP V1 接口（`/api/v1/tts`）的兼容实现。
    - **多参数解包机制**：首创在 `ApiKey` 中以 `APPID:ACCESS_TOKEN:CLUSTER_ID` 格式进行复合秘钥的输入与解包，无缝兼顾了火山引擎必需的鉴权多参数；
    - **多模态 V1 报文深度对齐**：封装了包含 `app`、`user`、`audio`、`request` (带 UUID 唯一 reqid) 的标准 JSON 请求体，并智能解析响应中 `code == 3000` 时的 Base64 编码音频段并解码存盘，彻底补齐了火山原生态语音合成链路。
- **重构物理感知与大模型工具历史上下文过滤**：
  - **剔除每次消息的全局传感器物理数据抓取**：移除了此前每次发送消息都调用 `perceptionManager.buildPhysicalContextString()` 的重度逻辑，改为仅将“系统当前时间”作为每次发消息的必要物理信息附带，大幅度减少物理设备调用频率，节省电量并消除无谓的工具执行开销。
  - **按需自主调用与虚构工具防幻觉**：修正了 `PromptAssembler` 里的 `TOOL USE GUIDELINE` 工具拼写（将 `get_heart_rate` 修正为真实存在的 `get_health_data`），同时在提示词中新增了明确且强制性的“严禁幻觉/猜测调用未定义工具（如 `get_phone_status` 或 `sync_system_time` / `同步系统时间` 等不存在的工具）”强约束，规避大模型幻觉引起的调用失败。
  - **剔除用户历史消息前的 `[发送于 xx]` 时间修饰**：完全移除了此前在构建大模型上下文时在用户历史消息前强行前置拼接 `[发送于 N分钟前]` 的重度修饰逻辑，保留纯净的 `msg.content` 传递给 LLM，从源头上阻断了 AI 模仿并生成 `[发送于 xxx]` 等时间元数据泄漏标签的可能。
  - **加入严格输出方括号文本硬性约束**：在提示词中新增了 `[OUTPUT FORMAT CONSTRAINT / 严格输出格式约束]`，要求 AI 在其回复中除了特定的物理震动反馈标签 `[haptic:vibration_type]` 以外，严禁输出任何被方括号包裹的文本（如 `[发送于...]` 等），并规定任何动作或心理描写必须使用圆括号 `(...)` 或星号 `*...*` 包裹，彻底保障了输出格式的纯净。
  - **10分钟滑动过期工具调用历史上下文**：实现了从当前会话的 `history` 历史消息中增量过滤最近 10 分钟（600,000 毫秒）内执行成功且不属于语音回复类的 MCP 工具结果上下文（如 Wi-Fi、电量、定位等物理感知或搜索结果），格式化为 `- X分钟前成功调用了 [工具名] 工具，返回结果为：[数据]`。超过 10 分钟的数据判定为过期，不再作为上下文附带。
  - **跨会话数据物理隔离**：基于 `history` 对当前会话的工具上下文进行独立过滤，完全杜绝了不同 Session（会话）之间的物理历史记录交叉泄露。
- **多模态 AI 虚拟工具语音回复组件（McpVoiceReplyItem）与独立防覆盖交互渲染**：
  - **精美独立语音条组件**：在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 中引入并实现独立的 `McpVoiceReplyItem` 语音回复条 UI 组件。它摆脱了以往只用普通折叠文本组件渲染 `send_voice_reply` 虚拟工具调用的限制，将其提升渲染为与系统原生语音条一致的精美语音气泡。
  - **流式合成与加载态骨架屏**：当工具状态为 `RUNNING` 时，自动在 UI 上渲染带有圆角、极细描边以及旋转加载圈的“语音合成中...”占位骨架屏；合成完毕后转换为长条形可交互语音播放条，并在其上渲染音频的时长，解决了加载期间的布局闪烁与内容空缺。
  - **禁止收到语音后自动播放**：收到 AI 主动通过工具发来的语音消息后，只在对应的 Message 下增量渲染出该语音气泡骨架，保持静默不自动触发音频物理播放，完美实现了“仅在用户手动点击语音条时才触发播发”的静音交互标准。
  - **多语音条防覆盖独立并发渲染**：由于大模型在单次 AI 会话中可能会连续调用多次语音工具发出多条语音回复，`McpVoiceReplyItem` 对各语音条进行了独立的状态隔离。每个语音条气泡分别对应各自的 `call.id`，并在 UI 列表中各自占据一行、独立上下排开展示，彻底杜绝了后面的音频文件在 UI 或内存中覆盖前面音频的严重 Bug。
  - **基于 Flow 状态订阅的独立音频播放与三柱律动波形动画**：
    - `MessageItem` 接口新增 `currentlyPlayingAudioId` 和 `onMcpVoicePlay` 传参，支持对 ViewModel 的 `currentlyPlayingAudioId` 状态进行实时订阅监听；
    - 点击任意特定的语音气泡条，能够完美触发 `viewModel.playMcpVoice(call.id)` 并加载播放特定的物理音频文件 `tts_${call.id}.mp3`；
    - 播放期间，只有当前被点击的这个特定的语音条上才会独立启动实时三柱跳跃音波律动微动画，再次点击可安全停止，其它未播语音条静止不影响，全面达成了完美的交互互斥防冲。
  - **历史语音缓存自动滚动清扫机制**：
    - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 构造初始化块 `init` 中，引入并调用了 `cleanOldTtsCacheAsync()`。
    - 它会在应用每次启动时自动拉起一个低优先级后台 IO 协程，安全扫描 `cacheDir`，将修改时间在 **3 天前** 的历史 `tts_*.mp3` 缓存文件进行静默物理清空，保障了本地存储空间占用呈滚动自愈态，杜绝垃圾碎片无限堆积。
  - **历史语音缺失/损坏 API 自愈重建合成与无感播放链路**：
    - 重构了 [playMcpVoice](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L1666) 的容错分支；
    - 当用户点击数天前历史记录中的语音条、而该音频已被上述滚动清理或意外损坏不存在时，逻辑不会发生静默失败或闪退，而是会**主动通过 `targetCall.input` 反向序列化解析出原始工具入参文本**；
    - 自动将该语音条的 UI 状态重新置为 `RUNNING` 骨架屏进行视觉缓冲，同时自动通过 `withLock` 线程排他锁拉起后台 TTS API 进行**相同音频文件的重新合成**；
    - 合成成功后，自动更新物理文件路径、时长等 Payload，回写保存至数据库，并**当即自动触发物理播放**，实现了完全无感的“点击 $\rightarrow$ API 重新合成 $\rightarrow$ 自动播报”自愈闭环。
  - **Activity 到 MainScreen 核心 ViewModel 数据通道打通**：
    - 修复了此前在 [MainActivity.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/MainActivity.kt#L162) 中实例化 `MainScreen` 以及在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L118) 中实例化 `ChatScreen` 时均未进行 `viewModel` 传参导致的严重乌龙故障。
    - 补齐了全局参数路由链，实现了 ViewModel 实例生命周期的深度流动和绑定，彻底唤醒并盘活了点击语音条、音频播放自愈、以及全局文本 TTS 朗读功能，消除了运行时 viewModel 为 null 导致的点击全量失效。
  - **语音条 UI 位置收拢与排布固化**：
    - 在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 消息重构中，彻底将 `McpVoiceReplyItem` 语音回复条从 AI 气泡顶部的 `message.mcpCalls` 流程中剥离。
    - 将语音回复条统一固定在 AI 气泡的最底端（即正文文本 `MarkdownText` 的下方），解决了由于工具流执行前后相对顺序不同导致语音条在感知工具卡片（如网页搜索）上下乱跑的显示问题，规范了聊天美学。
  - **轻量化音频检测与底层解码冲突规避**：
    - 废除了在 [playMcpVoice](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L1708) 触发播放时执行的繁重 `MediaPlayer.prepare()` 获取时长的音频损坏检测，代之以无副作用且极轻量级的 `ttsFile.length() > 0` 物理体积检测。
    - 规避了短时间内连续两次创建、准备底层解码器导致的 Audio 驱动锁或资源竞争，彻底消除了点击语音条可能发生的“没动静”的隐藏隐患。
  - **音频播放透明化异常 Toast 抛出**：
    - 升级了 [playAudioFile](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L1934) 异常处理，在捕获到任何底层硬解码、文件读取、或焦点抢占失败引发的崩溃时，在主线程以 Toast 浮窗向用户显式弹窗报错原因，清除了黑盒调试死角。


### Fixed (修复)
- **残缺/不闭合与连续 `<tool_call>` 标签神级自愈及函数风格解析兼容**：
  - **超强鲁棒性 XML 正则拦截**：针对大模型在语气调试示范中连续输出多个不闭合的 `<tool_call>...<tool_call>...` 脏标签（未闭合导致过滤机制漏过，以致泄露显示在聊天气泡中的问题），在 `LlmClient.kt` 中设计并应用了超强鲁棒自愈正则 `"<tool_call>([\\s\\S]*?)(?:</tool_call>|(?=<tool_call>|$))"`，并引入 `isDone` 参数在流式 Done 与非流式解析时无视截断挂起，实现了 100% 安全提取与掏空抹除。
  - **函数式工具参数（Function Style）解析兼容**：在 `parseXmlToolCallsOnly` 中重构支持了双轨解析。除了以往标准的 XML 标签入参，新增了对函数调用风格参数（如 `BuiltinPerception__send_voice_reply(text="...")`）的正则提取，直接兼容了各种形式的不规范工具输出，让语音消息触发 100% 拦截并播报。
- **虚拟语音工具免 MCP 转发本地拦截与并发写入 Mutex 保护**：
  - **内置工具解耦**：重构了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中的工具执行循环。针对 `send_voice_reply` 虚拟工具，绕过并免除了真实的 `mcpManager.callTool` 物理转发与校验（彻底解决了因本地环境未注册该虚拟工具抛出 [MCP 错误] 导致 `success` 变量被污染为 `false`，进而使得语音拦截合成逻辑被静默跳过的隐藏 Bug），在本地直接将其标为 `SUCCESS`。
  - **物理并发 Mutex 保护**：在 ViewModel 层面引入了 `ttsWriteMutex` 排他锁。在后台启动合成语音协程时采用 `withLock` 保护 `ttsFile`，彻底杜绝了大模型瞬间发送多条并发语音（如连续语气测试）时产生的文件写冲突和音频损坏。
- **Xiaomi MiMo 官方 TTS (v2.5) 报文彻底对齐、音色白名单自愈与调试日志集成**：
  - **网络请求 Header 适配**：为所有发往 `api.xiaomimimo.com` 的语音生成（TTS）与语音识别（ASR）请求，在 Request Header 中强制追加了必填的 `api-key` 请求头，完美解决了因缺少非标网关鉴权请求头导致 400 或 401 的报错。
  - **Payload 校验与白名单强力自愈**：在 `LlmClient.kt` 中，移除了在非标 `/v1/chat/completions` 音频接口中误加的 `modalities` 字段；将非流式语音合成的格式 `format` 修正为 `"wav"`；并针对 MiMo 专有音色（如冰糖、茉莉、苏打等）和 OpenAI 标准音色引入了强力白名单映射校验，一旦匹配到历史保存的不支持非法音色直接强制自愈重置为默认精品音色，彻底防范了非法参数引发的 400 报错。
  - **HTTP 请求与错误响应的可视化日志调试集成**：在 `generateSpeech` 发包前将生成的完整 JSON Body 用 `android.util.Log.d` 输出至 Logcat；并在 HTTP 失败时直接打印出包含具体网关提示的完整 Response Body 错误字串，消除了盲盒调试。
  - **OkHttp 弃用警告清理**：优化了 `LlmClient.kt` 中 `RequestBody.create` 调用的参数参数顺位，消除了编译日志中的弃用 API 警告。

- **TTS 语音合成异步加载状态与精致占位骨架屏**：
  - 在 [Message.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/Message.kt) 消息模型中增加了 `isAudioSynthesizing` 标志位，用于精细追踪文本到语音（TTS）的异步后台合成状态。
  - 在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 视图层中，当检测到 AI 语音条正在合成时，自动展现一个毛玻璃样式的“语音回复合成中...”精致占位骨架屏，并带旋转加载圈，平滑消除语音回复加载期间的布局跳跃，提供极为温润优雅的视觉过渡体验。
  - 朗读按钮（Speak）在合成语音时会自动变为 14.dp 极细的 `CircularProgressIndicator` 动画，同时置灰禁用，防止用户在合成期间频繁误触重复发送 API 请求，达到工业级人机交互标准。
- **全局系统级语音语气与呼吸声特效提示词规范注入**：
  - 在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt) 核心拼接引擎的语音引导中加入了对大模型应用音轨语气与呼吸标签（如 `(傲娇)` 语气与 `[吸气]` 气流声动作）的专属 Guidelines 规范引导。
  - 使得伴侣大模型能够深刻理解并在特定废土生存、傲娇对话或深夜呢喃等多样化的扮演场景下，于其主动调用的语音消息参数中自动带上相应的音质控制符，实现与小米 v2.5 TTS 引擎情感和呼吸节奏的无缝贴合。
- **MiMo 多模态自愈式智能模型映射与免配置升级**：
  - 在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 4 个核心网络方法（聊天流、语音合成、语音识别、AI生图）中加入了自愈模型映射。
  - 当检测到关联 API 客户端的 Provider 是 `MiMo`，且多模态模型名仍保持为系统默认（如 `gpt-4o-mini`, `whisper-1`, `tts-1`, `dall-e-3` 等）时，系统会自动静默升级为 MiMo 对应的官方模型名：`mimo-v2.5-pro` (识图), `mimo-v2.5-asr` (语音识别), `mimo-v2.5-tts` (语音朗读), `mimo-v2.5-images` (绘图)，实现了开箱即用，免去了繁琐的配置项。
- **自愈式 XML 标签工具调用解析与拦截机制**：
  - 在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 中引入了 `ParsedStreamState` 增量状态机。支持对大模型在普通 `content` 文本中以自定义 XML 格式输出的 `<tool_call>` 进行自愈解析与实时拦截。
  - 流式接收时，当 `<tool_call>` 标签未闭合，自动挂起后续内容不向 UI 发射，防止 XML 标签与参数在气泡正文中产生乱码闪烁；当 `</tool_call>` 闭合后，自动将整段 XML 块解析为系统 `LlmToolCall` 对象，并将其从正文文本中彻底剔除，实现无痕净化。
  - 无论流式（`sendChatCompletionStream`）还是非流式（`parseChatCompletionResponse`），均会自动识别并合并这类 XML 工具调用，保障对不支持标准 Tool API 的端点与弱模型的完美兼容。
- **多模态与客户端自定义自由度配置**：
  - 在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 底部多模态设置里为智能视觉识图、语音录音输入 (STT)、文本语音朗读 (TTS)、AI 图像生成各自引进了专有的 API 客户端服务商下拉选择框（DropdownMenu）与自定义模型名称输入框（OutlinedTextField）。
  - 各服务商选项直接映射自用户保存的 API 客户端列表，并提供“跟随当前会话配置”作为首选默认值，提供极高的扩展度与参数自由度，解决了使用三方 API 中继服务因硬编码名称返回 404 的问题。
  - **自定义 TTS 音色参数**：在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 的语音朗读卡片里增加了“自定义合成音色名称”输入框，用户可以自由输入如 `alloy`、`echo`、`nova` 等任意第三方服务商的标准音色 ID，完美解决了因 mimo 硬编码音色导致其它服务商报错 400 的问题。

### Changed (变更)
- **MediaPlayer 架构重构与主线程串行同步防冲**：
  - 彻底移除了原有的多协程异步创建播放器的隐患设计，在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中将播放器的状态控制、初始化（`MediaPlayer()`）、暂停、以及停止彻底收拢到主线程串行执行，杜绝了多次快速连续点击不同语音条时发生多声道重叠播放、资源未释放或 IllegalStateException 闪退的严重并发隐患。
- **系统音频焦点（Audio Focus）管家机制集成**：
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中引入并封装了 `AudioManager.OnAudioFocusChangeListener`，支持在播放本应用语音前，向系统申请短暂的音频独占焦点（`AUDIOFOCUS_GAIN_TRANSIENT`），并在播放完毕、播放异常、或被微信通话等其它应用强占焦点时，自动同步停止播放并释放焦点，与 Android 系统的音频流管理达到高度友好融合。
- **非 Loyea 伴侣角色的环境感知与时间感解封**：
  - 去除在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中硬编码的 `isLoyea` 拦截。使得非 Loyea 角色（如猫娘小玲等）在用户授权后也同样具备物理环境感知和系统时间感知的能力。
  - 这促使非 Loyea 伴侣能够在被明确提问天气等环境问题时，自主且积极地调用相应的传感器和天气预报 MCP 工具，摆脱以往只会发起网络搜索的弊端。
- **微调猫娘系统指令**：
  - 在 [TavernCardParser.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/TavernCardParser.kt) 中微调了猫娘（小玲）的系统扮演提示词（systemPrompt），追加了禁止在括号 `()` 或星号 `* *` 中输出任何动作描写、身体描写或场景心理活动的严格约束，令其专注于输出纯粹口头对话和萌系猫娘语气词。

### Fixed (修复)
- **MiMo 语音 ASR 与 TTS 协议参数报错 400/404 Param Incorrect 修复**：
  - **TTS 语音合成自愈与音色纠正**：在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 `generateSpeech` 里的 MiMo 分支下，在请求 JSON 体中显式注入了必填的 `modalities: ["text", "audio"]` 配置，并将原本的单条 `role = assistant` 文本消息改造重构为由 `user`（包含情绪指令说明）和 `assistant`（包含合成目标文本）组成的标准多回合消息结构，完美对齐了 OpenAI 多模态音频输出规范。
  - **默认非法音色自愈拦截**：针对此前默认硬编码的 `"mimo-v2.5-tts-default"` 音色 ID 因在各服务商音色库中均不存在而必然触发 400 校验报错的顽疾。在发包前增加了 `targetVoice` 自愈逻辑，一旦音色包含 `default` 或为空，MiMo 平台会自动映射为官方支持的预置精品音色 `“茉莉”`，而其它 OpenAI 平台则自愈映射为默认音色 `“alloy”`，实现彻底免配置开箱即用。
  - **ASR 语音转写修复**：在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 `transcribeAudio` 里的 MiMo 分支下，将原本作为 input_audio.data 上传的带有 Data URL 前缀的数据修正为纯 Base64 编码字符串，并补齐了必填的 `format` 参数，彻底根治了因音频数据流前缀污染及缺少格式标识导致的参数校验报错。
- **`<tool_invocation>` 新标签格式拦截解析与工具自愈转换**：
  - 修复了大模型在新版本迭代中将工具调用输出为自闭合 `<tool_invocation name="..." arguments="..." />` 格式时，导致解析器漏检并泄露到正文、工具执行失败且没能展示语音条 UI 的 Bug。
  - 在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 `parseIncrementalStreamState` 解析逻辑中，新增了针对 `<tool_invocation` 未闭合前缀的截断挂起逻辑，并在已闭合时通过正则表达式 `toolInvocationRegex` 从流文本中自动剥离该标签并转化封装为标准的 `LlmToolCall`。
  - 保证了 AI 主动发送语音消息工具 `BuiltinPerception__send_voice_reply` 能够被正确提取、路由并在 UI 气泡中自动渲染出语音长条展示、实现自动播放与律动均衡器动效。
- **Xiaomi MiMo 语音接口非标准端点 404 兼容性重构**：
  - 针对小米 MiMo 平台并不提供 OpenAI 标准 `/v1/audio/speech` 和 `/v1/audio/transcriptions` 端点的架构特征。
  - 在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 `generateSpeech` 与 `transcribeAudio` 核心实现中进行了底层的兼容重构。
  - 当接口提供商为 `MiMo` 时，自动将 TTS 与 ASR 网络调用智能路由至 `{BASE_URL}/chat/completions` 主端点，并重构为以 JSON 表单及多模态 `input_audio` 承载 Base64 音频数据的格式进行交互，最后解码提取生成的音频流或转写文本。这彻底消除了在调用语音相关接口时必然发生的 HTTP 404 报错。
- **`<think>` 推理块嵌套自定义 XML 工具调用泄漏与拦截失败修复**：
  - 修复了当推理模型将 `<tool_call>` 标签输出在 `<think>...</think>` 推理块内部时，导致工具调用被误归为“纯文本思考”，从而使得工具拦截失败（没有播放语音）且脏 XML 文本在 Thinking 内容中泄漏展示的 Bug。
  - 将 `LlmClient.kt` 的解析器升级为**双阶段提取自愈算法**：在最前置阶段直接利用正则从文本中剥离并提取所有已闭合的工具调用（即使在 think 块中也同样适用），并挂起未闭合的工具调用；随后在净化的文本上再划分 `think` 与 `content` 块。这彻底实现了对嵌套标签的鲁棒拦截和无痕展示。
- **流式多回合正文更新与 TTS 异步渲染冲突修复**：
  - 修复了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中异步线程 TTS 报错被后至流式字符帧覆盖的 Bug。
  - 将错误信息直接拼接至主累加变量 `accumulatedContent`，使出错提示能稳固参与之后的流式追加与 Done 刷新，防范瞬间被后续文本冲刷抹去的闪烁问题。
- **语音合成 TTS 诊断与静默失败自愈**：
  - 将 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 `generateSpeech` 接口返回类型重构为富实体 `TtsResult`，能够向上层透传具体的网络错误码和异常详情。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 的语音回复拦截处，如果 TTS 接口合成失败，将具体报错文字直接追加渲染在 AI 消息气泡的正文下方（例如 `(⚠️ 语音回复合成失败: HTTP 错误 400 ...)`），彻底告别以前因静默失败而无任何语音条和响应反馈的状况。
  - 在手动点击气泡朗读的 `playTts` 方法中也接入了该机制，在合成失败时通过 Toast 将具体错误（如 Key 错误、音色不匹配、API 超时）浮窗提示，提供透明可视化的调试信息。
- **语音回复 `send_voice_reply` 拦截前缀 Bug**：
  - 修复了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中拦截条件仅在 `toolCall.name.equals("send_voice_reply")` 时才成立的严重缺陷。
  - 增加了对 MCP 服务器前缀与命名空间的后缀匹配支持（即兼容包含 `BuiltinPerception__send_voice_reply` 的工具名称拦截），成功触发本地 TTS 合成和语音气泡自动播报播放。
- **识图多模态请求 404/400 兼容性修复**：
  - 修改了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt)，当发送包含图片的多模态视觉识图请求时，自动向 `sendChatCompletionStream` 传递 `emptyList()` 屏蔽 MCP 外部工具参数。这彻底避免了部分中继商因模型同时接收 `tools` 列表和图片输入却无“识图+工具”组合路由时抛出 `No endpoints found that support image input` 404 故障的底层兼容隐患。
- **语音识别 ASR 音频格式 MIME 匹配修复**：
  - 重构了 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 `transcribeAudio` 语音识别。基于本地录音文件后缀（如 `.m4a`）动态映射对应的 MIME 媒体类型（如 `audio/m4a`），完全消除了此前硬编码为 `"audio/wav"` 导致大批 ASR 端点因底层编码校验不符而拒绝解析的报错顽疾。
- **对话气泡中图片与生图的缩放显示缺陷**：
  - 重构了 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 中的消息渲染项。将用户上传的图片与 AI 生图的宽度统一限制为屏幕宽度的 `70%` (`0.7f`)，高度上限限制为 `200.dp`，并配置 `ContentScale.Crop` 与圆角，彻底解决了图片高度直接占满屏幕的显示缺陷。
- **多模态识图图片等比例压缩与 Token 优化**：
  - 重构了 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 的 Base64 图片转换方法 `encodeFileToBase64`。在编码前使用 `BitmapFactory` 对图片文件进行最大边 800 像素的等比例缩放，并以 80% 质量压缩为 JPEG 字节流后再行 Base64 编码，极大地优化了多模态请求的 Token 消耗与 payload 尺寸。
- **时间戳前缀污染 assistant 回复的缺陷**：
  - 修改了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中构建历史会话的 `decoratedContent` 处理逻辑，限制 `[发送于 xx]` 前缀只对 `Sender.USER` 消息生效。彻底阻断了 AI 学习、模仿并在自身长文本回复中输出 `[发送于 刚刚]` 等物理时间标记的顽疾。

## [Unreleased] - 2026-06-12

### Added (新增)
- **Claude美学与多模态交互精细化升级**：
  - **输入框统一化整合**：重构了 [ChatInputBar](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L1197)，彻底移除了输入框内部左侧冗余且占地方的麦克风图标，将语音触发入口完全整合在右下角 48.dp 的 Action 按钮中。文本为空时按钮展示为高贵优雅的浅灰磨砂圆卡，并支持点击直接拉起语音录音。
  - **Q弹物理示波声轨面板**：将原有的 8 柱硬性示波声轨升级为 12 柱，在 `ChatScreen.kt` 中基于 `animateDpAsState` 施加带有低刚度 Spring（弹性物理阻尼）的高度跳跃动画，音轨根据距离中心点距离呈正态平滑分布。整个面板升级为极致高质感的半透明磨砂毛玻璃渐变卡片并配以 1.dp 极细微光描边，创造水乳交融的动态声光反馈。
  - **多模态骨架屏占位与防抖**：无论是用户发送的图片还是 AI 绘制/生成的生图，在本地完全解析或网络下载前，提供具备 Shimmer 呼吸淡入渐变动画的 Skeletons 骨架屏占位占满其布局（包含加载旋转指示器与状态文字提示），完全防范图片拉起时的布局抖动 (Layout Shift)，视觉过渡极其温润优雅。
  - **语音播放实时均衡器律动微动画**：彻底摒弃此前在播放 TTS 朗读或长语音时冷冰冰切换静音图标的呆板做法。在 [MessageItem](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L806) 内部，一旦语音被触发播放（`message.isAudioPlaying`），语音气泡的末端以及 AI 动作条的朗读按钮将联动渲染出**三柱实时微幅律动的音频均衡器小微动画**，生动展示发声态，并消除了旧版 VolumeMute 废弃 API 警告。
- **多模态与媒体扩展机制集成 (识图、语音 STT、语音 TTS 与生图)**：
  - **设置页多模态管理**：在 `SettingsSubPage` 枚举中新增 `MULTIMODAL_SETTINGS` 二级设置分支，并在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 底部实现了 `MultimodalSettingsLayout` 二级设置界面。采用卡片单选与滑动控制设计，支持开关控制多模态识图、语音录音 (STT) 识别、回复朗读 (TTS)、AI 生成完毕自动播报，以及图像生成；并为 TTS 提供了官方标准、温柔学姐、阳光暖男与情感共鸣 4 类音色偏好卡片选择。
  - **ViewModel 媒体状态控制**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中引入 Android `MediaRecorder` 与 `MediaPlayer`，实现了开始/停止录音状态检测（`isRecording`、`recordingDuration`、基于 `maxAmplitude` 的跳动振幅 `recordingAmplitude` 提取），以及带离线缓存功能的 TTS 播报播放控制器。支持 AI 回复完成后根据状态判定是否执行自动播报，并且重构了 `sendMessage` 方法，支持多模态参数投递与以 `/draw ` 指令开头的生图拦截机制。并在 ViewModel 中封装了 `transcribeAndSendAudio` 以处理后台识别流程。
  - **网络客户端多模态重构**：在 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 中为 `LlmChatMessage` 引入多模态 `imageUrl` 属性支持，将包含本地路径的图片在发送给大模型时转换为 base64 图像块进行 payload 组装。实现了根据活跃 apiUrl 智能补全的 `/audio/speech` 语音合成接口、`/audio/transcriptions` 语音转文字接口、`/images/generations` 图像生成接口网络逻辑。
  - **UI 动效与交互优化**：在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 消息 LazyColumn 中为 [MessageItem](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L755) 增加多模态支持。实现了用户发送图片的展示、极简精致的多声道语音播放长条、AI 生成图片的展示并挂接大图灯箱 Dialog；为 AI 回复动作栏添加了可根据播放状态联动切换为 VolumeMute/VolumeUp 的小喇叭。输入框旁加号扩展动作支持选取相册并拉起预览小卡片，并且当麦克风开始录音时会展示一个带 8 根柱子的实时示波声轨微动效卡片。
- **UI 对话气泡时间显示及回溯编辑与历史截断回溯机制**：
  - 在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 的用户消息气泡中，点击即可折叠/展开当前对话的精确发送时间戳（HH:mm 格式），同时合成了“编辑”与“复制”按钮。当用户点击“编辑”按钮时，对话气泡转换为包含“取消”与“保存并回溯”功能的安全编辑输入框。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中实现了 `editMessage(messageId, newContent)` 函数。当用户修改消息并提交时，自动停止当前的流式 AI 回答，从本地磁盘和内存中截断并删除该被编辑消息之后的所有对话，将该消息更新为新内容并刷新时间戳，接着自动向大模型重新发起流式提问。
  - 在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt) 与 [MainActivity.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/MainActivity.kt) 中透传并绑定了编辑回调，彻底打通了从 UI 编辑到 ViewModel 核心截断重发流程。
- **后台主动问候启动自愈注册**：
  - 在 [MainActivity.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/MainActivity.kt) 的 `onCreate` 初始化阶段，检测用户后台主动联系的授权状态（`enable_background_greeting`）。若已授权，则通过 `WorkManager` 以 `ExistingWorkPolicy.KEEP` 策略队列化注册带有 60~180 分钟初始随机延时的 [GreetingWorker.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/worker/GreetingWorker.kt) 任务。此设计既保证了系统因强杀、重启、冷启动导致链条中断时能够有效自愈，又完全保留了原有队列等待的倒计时不被重置。
- **物理震动交互机制与打字机流式同步**：
  - 新增 `android.permission.VIBRATE` 系统权限，并编写 [HapticManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/HapticManager.kt) 震动管理器，定义并封装了 4 类极具情感化阻尼节奏的微震动波形效果：心跳共鸣 `heartbeat`（咚咚双击）、娇嗔轻戳 `poke`（15ms 高频瞬震）、深夜低语 `whisper`（绵长低频细滑震）以及碰拳庆祝 `bump`（有弹性回馈的中震）。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 打字机文本渲染字符流中增加占位符过滤逻辑。当接收到 AI 的 `[haptic:类型]` 占位符时，如果在设置中开启了授权，则直接触发微震动，并在渲染到屏幕 UI 和持久化保存前自动剔除该占位符。这实现了物理动作与打字机流文本的高度拟真“声画同步”，且完全对用户隐藏了格式代码。
  - 在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt) 系统扮演提示词中新增克制且生动的物理微震动引导，提示 AI 在极少数高度情感化的适当动作时刻自发地使用震动占位符。
- **外部工具授权与隐私控制二级配置页**：
  - 在 `SettingsSubPage` 二级页面枚举中新增 `TOOL_AUTHORIZATION`。
  - 在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 底部实现 `ToolAuthorizationLayout` 二级界面，并使用 **Claude 极简磨砂卡片美学** 设计了 GPS定位、天气预报、环境照度噪音、设备电量网络、蓝牙与运动识别、健康中心以及物理震动反馈 7 大授权分类卡片 Switch 开关。
  - 在 [MainActivity.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/MainActivity.kt) 中向 `SettingsScreen` 传入 `viewModel`，打通了本地 `loyea_prefs` 的持久化开关存储。大模型在拉取聚合 MCP 工具列表及解析流式震动时，会自动进行授权过滤，确保隐私与物理触感被用户完全自主把控。
- **Wi-Fi SSID 场景感知与环境噪音分贝感应及 MCP 工具美化**：
  - 新建 [WifiProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/WifiProvider.kt)，通过 `ConnectivityManager` 与 `WifiManager` 动态提取当前 Wi-Fi 网络 SSID，在定位权限受限时优雅降级为通用 `"Wi-Fi Network"` 或 `"Cellular Mobile Data"`蜂窝网络描述。
  - 新建 [NoiseProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/NoiseProvider.kt)，在运行时申请 `RECORD_AUDIO` 麦克风权限后，通过 `AudioRecord` 执行短时间（约 120ms）高频采样，利用均方根（RMS）算法精密计算环境分贝值（dB），并在 `finally` 块彻底释放录音资源，确保系统状态栏中麦克风隐私图标完全不会常驻，保障用户绝对隐私。
  - 在 [PhysicalContextManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PhysicalContextManager.kt) 物理感知聚合引擎中成功融合 Network SSID 和 Ambient Noise 上下文，并将 `wifiProvider` 与 `noiseProvider` 属性公开化，以便提供跨模块调用。
  - 在 [PerceptionMcpServer.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PerceptionMcpServer.kt) 中正式注册 `get_wifi_status` 和 `get_noise_level` 两个 MCP 感知类工具，支持大模型随时主动按需执行网络与分贝的物理感应扫频。
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 汉化函数中为这二者分配了美观的中文动作文本描述（`“检测 Wi-Fi 网络连接”` 和 `“测量环境噪音分贝”`），并在 [ThinkingAndMcpComponents.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ThinkingAndMcpComponents.kt) 中量身定制指派了专属精美图标（`📶` 与 `🔊`），在聊天界面上完美渲染呈现。
- **蓝牙手表双向连接客户端集成**：在手机端引入并实现了 [WatchBluetoothClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/bluetooth/WatchBluetoothClient.kt) 经典蓝牙通信控制客户端（基于 RFCOMM 协议）。支持获取系统已配对的蓝牙设备列表，自适应过滤或匹配名字包含 "Watch" / "OPPO" 的手表进行 Socket 直连，在后台循环按行读取解析手表上传的健康数据 JSON 流（实时心率、累计步数），并支持向手表端写回 "START_REALTIME"、"STOP_REALTIME" 和 "GET_RECENT" 控制指令以按需 management 手表功耗。

### Changed (变更)
- **移除/柔化大模型 System Prompt 过度强硬引导**：
  - 重构 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt)，全面去除了 `[CRITICAL INSTRUCTION]` 中原有的“你必须调用BuiltinPerception工具，绝对不准说看不到”等强硬词汇，以及 `[TOOL USE GUIDELINE]` 中硬性的“你必须调用...”、“严禁在未调用对应工具的情况下，私自猜测或瞎编数据”等带有硬限制的条条框框。将其重构为自然的推荐性引导词，使 AI 能够更自然、灵活、有自我裁量权地基于 physical context 与用户自由对话。
  - 将联网搜索功能 `enableSearch` 的 `[WEB SEARCH CAPABILITY]` 指引一并做了柔和化精简，去除了所有硬性的“你必须坚定回答”、“严禁声称自己无法联网”等教条词汇，替换为弹性、符合拟真人类角色逻辑的指引。
- **手表物理上下文感知升级为真实蓝牙源**：在 [PhysicalContextManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PhysicalContextManager.kt#L8) 中将原本的 `MockWatchProvider` 升级重构为 [BluetoothWatchProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/BluetoothWatchProvider.kt)。使得整个感知框架（包括 AI 会话、RAG 记忆、MCP 工具等接口）能够直连真实的蓝牙手表源以采集真实的物理状态，并且在蓝牙未连接时仍然具备自动降级至本地模拟测试数据的双工保障。并且在 [AndroidManifest.xml](file:///D:/CodingProjects/Android/Loyea/app/src/main/AndroidManifest.xml#L9) 中补齐了 Android 12+ 上连接和发现手表所需的 `BLUETOOTH_ADMIN`、`BLUETOOTH_SCAN` 和 `BLUETOOTH_ADVERTISE` 全套蓝牙动态权限。

### Fixed (修复)
- **消除 Kotlin 编译器警告并优化 Compose 代码**：
  - 移除了 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 和 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt) 中未使用的 `userName` 参数及未使用的协程作用域。
  - 升级了 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 中已废弃的 `Icons.Default.VolumeUp` 为 `Icons.AutoMirrored.Filled.VolumeUp`。
  - 升级了 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt) 中已废弃的 `Divider` 组件为 Material 3 标准 of `HorizontalDivider`。
  - 消除了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中多处对非空 `parsedArgs` 进行 `Unnecessary safe call` 的警告。
- **隔离非 Loyea 角色卡的物理感知上下文与工具访问，防止跨会话泄露**：
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中引入角色卡类型拦截机制。通过 `characterCard.id == "char_loyea_default"` 进行前置识别，如果不是内置的 Loyea 角色卡（如“小玲喵”），则不在其 System Prompt 里注入物理感知环境数据，并在可用工具列表中强行过滤剔除所有 `BuiltinPerception` 物理传感/外设类工具，实现彻底的角色隔离与隐私脱敏。
- **引入真实时间戳差值装饰机制，赋予 AI 时效遗忘与时间感知能力**：
  - 在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 构建大模型上下文的方法 `buildLlmConversation` 中，为每一条装载的历史对话前置拼接 `[发送于 N分钟前/小时前/天前]` 的时间修饰标识。这使得大模型对对话历史具有清晰的时间差认知，能科学地区分历史旧状态与最新实时数据，彻底消除了过时物理数据污染新回复的顽疾。
- **修复 Wi-Fi 与蓝牙感知导致的 SecurityException 闪退**：
  - 在 [WifiProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/WifiProvider.kt) 获取当前 Wi-Fi SSID 处，使用 `try-catch` 包裹 `wifiManager.connectionInfo` 调用，彻底拦截并降级防护由于 Android 10+ 定位/WIFI 权限未授权而产生的 `SecurityException` 崩溃，确保顺利降级返回默认 `"Wi-Fi Network"`。
  - 在 [BluetoothProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/BluetoothProvider.kt) 的 `getBluetoothStatus()` 主函数中全面引入 `try-catch` 保护，重点防护在无蓝牙权限访问 `bluetoothAdapter.isEnabled` 时产生的安全崩溃隐患。
- **丰富 Wi-Fi 连接和环境噪音分贝数据与物理底噪修正**：
  - 重写 [WifiProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/WifiProvider.kt)，引入网络信号强度（dBm）、信号等级（0~4级）、连接速率（Mbps）和频段（MHz）的抓取，使获取的 Wi-Fi 连接上下文更具信息维度，即便无法获得 SSID 也能提供有价值的网络状况。
  - 重构 [NoiseProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/NoiseProvider.kt) 的分贝计算逻辑，引入 `30dB` 的物理声学底噪保护与归一化平滑，消除因为系统底噪阻尼或微弱静音导致计算出不合常理的 `2dB` 极小值现象，使返回的分贝数与人耳实际听感契合。
- **修复打字机半截震动占位符过滤导致的流拼接中断与震感失灵 Bug**：
  - 修复了 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中打字机流式解析 `[haptic:类型]` 的破坏性截断 Bug。此前在遇到半截占位符时直接破坏性地修改了 `accumulatedContent`，导致下一批流文本进来后由于标志残缺而无法完成拼接，进而导致震感失灵且界面残留 `:poke]` 等脏字符。现已将半截字符过滤移至仅用于 UI 渲染更新的临时变量 `displayContent` 中，原汁原味地保留流式拼接字符，彻底治愈震动反馈失灵的问题。
- **解决 App 后台常驻传感器高功耗与强杀问题**：
  - 重构 [ActivityProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/ActivityProvider.kt) 类的初始化逻辑，移除在 `init` 阶段默认注册本地加速度计与步数计数器的逻辑，改为由页面前台生命周期动态激活。
  - 在 [MainActivity.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/MainActivity.kt) 中挂接 `onStart()` 与 `onStop()` 生命周期回调，使本地感知传感器只在应用前台运行时工作，切入后台时即刻完全释放传感器资源，彻底切断了后台空转带来的高电池消耗与被系统清理强杀（LMK）的风险。
- **补齐新增工具的会话界面汉化翻译**：
  - 在 `ChatViewModel.kt` 的 `translateToolName` 中新增对 `get_wifi_status`/`wifi` -> `"检测 Wi-Fi 网络连接"` 以及 `get_noise_level`/`noise` -> `"测量环境噪音分贝"` 的中文描述汉化翻译。
- **修复打字机震动标记替换引起的崩溃/卡死**：
  - 修复了在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中解析 AI 回复的 `[haptic:类型]` 占位符时的崩溃隐患。此前使用 `replaceFirst(match.value, "")` 导致 JVM 在将 `match.value`（例如 `[haptic:poke]`）当作正则表达式解析时，因中括号元字符未转义导致 `PatternSyntaxException` 抛出闪退，或死循环卡死。现已重构为基于字符位置区间的安全移除方法 `removeRange(match.range)`，并对整个打字机文本解析块增加了 `try-catch` 异常防护。
- **修复 GreetingWorker 中 Log 引用未解析**：
  - 在 [GreetingWorker.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/worker/GreetingWorker.kt) 头部导入了 `android.util.Log`，解决了此前因缺失导入导致编译失败的问题。
- **修复 SettingsScreen 编译报错**：
  - 在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 头部补齐了 `import androidx.compose.foundation.BorderStroke`，解决了因“智能手表蓝牙同步”卡片新增“手动连接” `OutlinedButton` 引用了 `BorderStroke` 而造成 Unresolved reference 导致 Gradle 编译失败的故障。
- **记忆机制专属设置页及本地持久化配置**：在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt#L2465) 中全面实现 `MemorySettingsLayout` 二级设置界面。支持用户灵活开启/关闭“自动记忆整理”开关 (`enable_memory_consolidation`)，通过 Slider 阻尼滑块在 5 到 30 条消息之间精细设定“触发整理周期阈值” (`memory_consolidation_trigger_count`)，以及通过 DropdownMenu 下拉菜单在已配置的多模型客户端中专门为记忆提取指定独立 API 客户端配置 (`memory_api_config_id`)，并完全存储于 `"loyea_prefs"` 本地偏好共享字典中。
- **核心事实记忆管理卡片与 UI 交互**：在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L366) 的侧栏每个会话项右侧，引入三点式下拉菜单 `DropdownMenu`，替换原有单一的删除按钮，新增“查看核心记忆”操作项。同时在文件尾端集成 [CoreMemoryDialog](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L604) 弹窗（采用 Claude 极简磨砂质感卡片视觉与 YIQ 高对比度前景色自适应设计），支持用户在对话间隙原地双击修改记忆条目、手动删除错误事实、手动添加条目、以及点击“AI 重新总结”触发异步后台重塑并弹出 Toast 贴心提示。
- **大模型定期自动提炼与合并核心记忆**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L1007) 中设计并整合了记忆自动提炼器。设定了 10 条消息的滑动触发步数，会话消息成功保存时自动进入后台提炼协程，利用 LLM 自主归纳合并、去重、排重事实，并最终通过原子更新接口 [updateSessionCoreMemories](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt#L222) 持久化写入磁盘。
- **工具调用敏感度强化与行为准则注入**：在 `PromptAssembler.kt` 的 System Prompt 尾端（利用大模型注意力机制的近因效应 Recency Effect）正式注入了 [TOOL USE GUIDELINE](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt#L78) 顶层强约束规则。针对健康传感器、当前天气 `BuiltinPerception__get_live_weather` 以及未来天气预报 `BuiltinPerception__get_weather_forecast` 等工具调用进行了明确的行为准则限制，强力约束模型在处理此类问询时必须优先调派工具，严禁依靠幻觉私自捏造天气、气温或身体数据，显著提升了大模型在生成 Tool Calls 时的敏感度与规则遵从率。
- **天气预报与实时天气视觉美化与区隔**：在 `ChatViewModel.kt` 的 [translateToolName](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L836) 函数以及 `ThinkingAndMcpComponents.kt` 的 [McpCallItem](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ThinkingAndMcpComponents.kt#L27) 图标组件中，对天气预报工具进行了专项区隔。将天气预报的翻译文案重构为“获取未来天气预报”（原为“获取当前气象状况”），并将其对应的 Emoji 图标指派为日历 `📅`（原与实时天气的 `🌤️` 重复），彻底解决了视觉与文案上的重复展示 Bug，使工具感知过程在聊天面板上更显层次感。
- **天气预报全新 MCP 工具集成**：在 `PerceptionMcpServer.kt` 中新增并注册了 [get_weather_forecast](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PerceptionMcpServer.kt#L43) 接口工具，大模型可调用其拉取特定地区未来 3 天的结构化天气预报（含日期、平均描述、最低/最高温区间）。
- **输入框占位符自适应角色名称**：在 `ChatScreen.kt` 中重构了聊天输入栏 [ChatInputBar](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt#L769) 的入参设计，移除了硬编码的“与 Loyea 对话”占位语，改为从活跃角色卡中动态读取 `activeCharacterCard.name`。无论用户在会话中切换何种人格伴侣，输入框在无内容时均能完美、优雅地自适应呈现为“与 [角色卡名称] 对话”，大幅提升了个性化陪伴的代入感。
- **联网搜索功能大模型系统提示词引导**：在 `PromptAssembler.kt` 中重构了 System Prompt 组装引擎，增加了 `enableSearch` 参数。当会话 API 开启联网搜索时，系统会自动在扮演设定中注入 `[WEB SEARCH CAPABILITY / 联网搜索功能]` 中英文双语指引指令，消除了大模型对于“自己无法联网”的认知偏见，并引导其在被问及联网能力或实时事件时，能够正确回复且主动调用 `BuiltinPerception__web_search` 工具。
- **思维链多轮工具调用分隔与里程碑提示**：在 `ChatViewModel.kt` 中重构了多轮推理逻辑。大模型每进行一轮工具调用并在进入下一轮思考前，系统会往已累积 of 思考链中自动追加包含换行符的分隔说明（如 `💡 *（已在此处调用接口感知状态：xxx）*`），使得多轮工具调用的思考历程被完美分段，科技感与逻辑透明度大幅拉满。

### Changed (变更)
- **手表物理上下文感知升级为真实蓝牙源**：在 [PhysicalContextManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PhysicalContextManager.kt#L8) 中将原本的 `MockWatchProvider` 升级重构为 [BluetoothWatchProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/BluetoothWatchProvider.kt)。使得整个感知框架（包括 AI 会话、RAG 记忆、MCP 工具等接口）能够直连真实的蓝牙手表源以采集真实的物理状态，并且在蓝牙未连接时仍然具备自动降级至本地模拟测试数据的双工保障。并且在 [AndroidManifest.xml](file:///D:/CodingProjects/Android/Loyea/app/src/main/AndroidManifest.xml#L9) 中补齐了 Android 12+ 上连接和发现手表所需的 `BLUETOOTH_ADMIN`、`BLUETOOTH_SCAN` 和 `BLUETOOTH_ADVERTISE` 全套蓝牙动态权限。
- **全类型蓝牙外设连接与电量感知整合**：在 [BluetoothProvider.kt](file:///D:/CodingProjects/Android/Loyea/perception/BluetoothProvider.kt#L72) 中，重构并拆分了蓝牙扫描逻辑。增加了独立的 [getDeviceBattery](file:///D:/CodingProjects/Android/Loyea/perception/BluetoothProvider.kt#L121) 探测子方法，通过反射 `getBatteryLevel` 优雅提取蓝牙外设（如耳机、手环、手柄）的实时电量百分比；重构主感知接口 `getBluetoothStatus()`，联合已配对设备（`bondedDevices`）的反射 `isConnected` 在线状态与设备类型 Major Class 自动归类（`Wearable` 智能穿戴、`Peripheral` 输入外设、`Health` 健康设备等），以 `Sony WH-1000XM4 (Audio, Battery: 80%)` 的全景格式输出给大模型，实现了精细化的环境和续航关怀对话切入。
- **天气查询公制单位强制与全球跨域检索支持**：在 [WeatherProvider.kt](file:///D:/CodingProjects/Android/Loyea/perception/WeatherProvider.kt)。为实时天气查询的 `wttr.in` 请求强制注入了公制单位参数（`?m`），彻底解决了此前网络代理或 IP 位于海外时温度显示为华氏度的痛点，确保其 100% 呈现为摄氏度。同时，为实时天气 [get_live_weather](file:///D:/CodingProjects/Android/Loyea/mcp/PerceptionMcpServer.kt#L33) 和新天气预报工具新增了可选入参 `location`，允许大模型在获取设备当前定位天气的基础上，进行全球任意地区的跨地域天气检索。
- **手机端蓝牙状态流式更新与重连机制**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt) 中引入协程异步监听 `WatchBluetoothClient.connectionState` 的 Flow 状态流，确保手机端 UI 上的连接开关能跟随真实经典蓝牙的物理连接而自动、实时同步；同时在设置里添加了 `reconnectWatch()` 主动重连机制，为用户提供了手动的连接干预方式。
- **感知上下文真实手表数据集成**：在 [PhysicalContextManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/PhysicalContextManager.kt) 中重构物理感知拼装流程，当真实经典蓝牙手表连接时，优先调取手表的实时心率与累计步数数据并附加 `[Smartwatch Bluetooth]` 真实专属数据源标识，彻底纠正了此前误标记为 `[Simulated]` 的缺陷，并打通了运动步数传感器的集成，为 AI 决策提供高保真的健康上下文。

### Fixed (修复)
- **手机端系统级蓝牙权限动态申请**：在 [MainActivity.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/MainActivity.kt) 的启动统一权限申请机制中补齐了 Android 12+ 运行时所需的 `BLUETOOTH_CONNECT` 和 `BLUETOOTH_SCAN` 权限请求，解决了由于缺失运行时权限导致手机无法获取配对蓝牙设备列表的致命连接故障。
- **设置页智能手表蓝牙同步卡片重构**：在 [SettingsScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/settings/SettingsScreen.kt) 的“物理感知与外设集成”板块中，将原本带有误导性的“启用模拟同步”重构为“智能手表蓝牙同步”卡片。直观展示了已配对设备的真实经典蓝牙连接状态（连接中、已连接、未连接），并在未连接时提供显眼的“手动重新连接手表”控制按钮，配合清晰的系统配对指导文案，极大地提升了外设集成的体验和故障恢复能力。

### Fixed (修复)
- **会话事实记忆锁定机制与 UI 按钮栏重构**：在 [MainScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/main/MainScreen.kt#L648) 中重构并美化了 `CoreMemoryDialog` 交互弹窗。引入以星标 `★` 开头的锁定的“核心事实记忆”与普通“事实记忆”的视觉状态区隔，支持用户通过双击事实内容进行在线编辑，以及通过点击每行左侧的 Star 按钮直接完成“锁定/解锁”切换（自动增删 `★ ` 前缀）；重构弹窗底部按钮，将“AI 重新总结”和“手动添加”设计为带精致微标图标的平分宽度 `FilledTonalButton` 和 `OutlinedButton` 填入 `text` 段底部，而 `confirmButton` 仅放置标准的“关闭”按钮，彻底消除了 M3 对话框由于留白导致的右侧空控件测量 Bug。
- **记忆大模型 Prompt 指令增强与自动兜底保障**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L1034) 中升级了大模型事实整理提示词，向 AI 明确指示所有以 `★` 开头的锁定核心记忆属于“绝对不容许修改或删除”的事实条目，要求其仅对非 `★` 锁定的事实进行提炼、去重与新历史合并。并在代码中增设了**硬兜底防御逻辑**：如果大模型生成的新列表中漏掉了任何旧有的锁定的 `★` 核心条目，代码会自动重新插入回列表最上方，从机制与逻辑上做到双重防丢。
- **切会话/切后台/退页面报错过滤与静默响应防御**：在 [ChatViewModel.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatViewModel.kt#L309) 的会话切换方法 `selectSession` 首部注入 `stopResponse()` 拦截，强力掐断此前可能存在的生成残留，杜绝旧响应回包串扰覆写新会话的内存灾难。并在 `startAiResponseStream` 的异常捕获块中，加入了协程生命周期活跃度 `coroutineContext.isActive` 与 `CancellationException` 的前置校验。当用户因切会话、离页或切后台而主动触发 cancel 时，将静默截止响应而不再展现“SSE 接收异常”的系统报错气泡，大幅提升了极端操作情况下的稳定性体验。
- **定位受限硬拦截与降级北京误导修复**：在 [WeatherProvider.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/perception/WeatherProvider.kt#L17) 中，前置识别定位参数是否包含 `[` 和 `]`，一旦确认是由于权限未授予或定位开关未打开导致的报错文本，则直接硬拦截跳过无谓的 `wttr.in` 网络请求与降级到 `"Beijing"` 的旧处理方案，直接原样抛回错误说明。并在 [PromptAssembler.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/PromptAssembler.kt#L98) 的 `[TOOL USE GUIDELINE]` 中注入强规则指令，强约束模型在感应到定位受限时立即停止工具调用，并在回复中以温和的话语引导用户授权定位。
- **免 Key 网页搜索空值与反爬失效修复**：重构了 [LlmClient.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt) 中的免 Key 公共检索 [performFreeWebSearch](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/LlmClient.kt#L722) 逻辑。引入了高性能的“多源容灾搜索引擎”，当遭遇代理 IP 被 DuckDuckGo 反爬拦截（HTTP 403 / 验证码）而导致结果为空时，系统会自动、流畅地按顺序回退至备用源（Bing 必应 -> 360 搜索），从而在免去 API Key 门槛的前提下，实现 100% 的高稳定性结果输出。
- **气泡配色选项去括号精简**：在 `SettingsScreen.kt` 中移除了气泡主题配色列表中的所有括号及解释性后缀，将“莫兰迪灰 (ChatGPT 风格)”等字样精简为“莫兰迪灰”、“琥珀沙黄”及“极简天蓝”等优雅的短词，界面更显利落。
- **自定义气泡字体对比度智能感应**：在 `ChatScreen.kt` 中引入了基于 YIQ 亮度公式的文本颜色自适应机制。当用户在深色模式下选用自定义气泡色时，系统会动态计算气泡底色的明暗亮度：如果是浅色气泡底则自动搭配深色文字（`#1A1A1A`），如果是深色气泡底则自动搭配浅色文字（`#FAFAFA`），完美解决了深色模式下文字与自定义气泡对比度极低、难以阅读的痛点。
- **搜索工具内容卡片动态化展示**：在 `ChatViewModel.kt` 的工具卡片构建环节中，当大模型调用 `web_search` 检索工具时，系统会自动抓取其检索参数 `query` 并拼装入 `actionText`（例如 `搜索网页：关键词1 关键词2`），使聊天面板上的检索日志动态且直观，不再死板。

## [Unreleased] - 2026-06-11

#### Added (�啣�)
- **憭帋�霂肽��交��厩阮����𤥁扇敹�**嚗𡁜銁 `ChatViewModel` 銝� SharedPreferences 銝剖��唬�隡朞�蝥批����蝔輯扇敹�㦤�嗚��鍂�瑕銁颲枏�����嗡�摰墧𧒄靽嘥��厩阮����Ｖ�霂腈��△�Ｚ歲頧研���摨誯���喳��啁��喳��券���箏��齿鰵餈𥕦�嚗���賡�靽萘��㰘蝸撖孵�隡朞����蝔選��踹�颲枏�銝Ｗ仃��
- **�冽�瘨��銝��桀��嗆���**嚗𡁜銁 `ChatScreen` ��鍂�瑟��舀�瘜∩��啣�鈭���桀��嗅��賬���朞��函鍂�瑟�瘜∪�銝羓��餌��砍僎�滚� `AnimatedVisibility` �函𤫇嚗���唬��𦦵��餌鍂�瑟�瘜∩���𧑐瘛∪��曄內憭滚��暹�嚗��甈∠��餅�瘜∪�瘛∪枂�睃�憭滚��暹��萘�敺桀𢆡���閫��鈭�鍂�瑕����摰寞�瘜訫翰�瑕��嗥��桅���
- **�拍��毺䰻撌亙��∠�銝剜��讛膩銝𤾸㦛�����**嚗帋蛹 7 銝芰𡠺蝡讠��笔� MCP �拍��毺䰻撌亙�嚗�予瘞𢛵��𧑐���蝵柴��㴓憓���麄��㩞�譌����踺����具���摨瑁��亦�嚗匧銁�𠰴予�屸𢒰撌亙�靚�鍂�∠�銝剝�憭��蝎曄���葉��祗銋匧��讛膩嚗�僎蝏穃�鈭��韐冽��� Emoji �其��暹�嚗�� �� 霈曉��菟����歹� 憭拇����� �萘�蝑㚁�嚗峕�憭扳����鈭支��渲��找�蝢舘�摨艾��
- **鈭箸聢�梢△�Ｚ楝�勗紡�芾歲頧�**嚗𡁜銁 `MainActivity.kt` �� `NavHost` 撖潸⏛�曆葉瘜典�鈭� `"tavern"` 憿菟𢒰頝舐眏嚗�僎銵亙�鈭� `MainScreen` �� `onTavernClick` �噼�嚗��憿菟𢒰頝唾蓮�� `TavernScreen`嚗�蝠摨蓥耨憭滢��冽��冽㺿�滚��孵稬�靝犖�潸��脲��滚���歲頧祉撩�瑯��
- **颲枏�瘜閖�摨阡�霈抵䌊���**嚗𡁜銁 `MainActivity.kt` �𣬚� `onCreate` �嗆挾撘��臭� `WindowCompat.setDecorFitsSystemWindows(window, false)`嚗峕��帋� Compose 蝡� `WindowInsets` ��𦻖�園�𡁻�嚗䔶蝙敺𡑒�憭抵��交��� `imePadding()` �賢�摰𣬚��笔�頧舫睸�条�����嗆��僎�芸𢆡靚�㟲撣��擃睃漲嚗�蝠摨閙�蝏苷�颲枏�獢�◤颲枏�瘜閖��𣇉� UI 蝻粹萅��
- **�拍��毺䰻撌亙��笔��𡝗���**嚗𡁻���僎摨笔�鈭�之��𧗠�� `get_physical_perception` 撌亙�嚗�銁 `PerceptionMcpServer.kt` 銝剖��嗆���蛹 7 銝芷��菜�摨衣��祉��笔� MCP 撌亙�嚗Ǒget_location`��get_live_weather`��get_environment_light`��get_battery_status`��get_bluetooth_status`��get_activity_state`��get_health_data`嚗㚁�雿� AI 憭扳芋�贝��厰��㗇活�瑕�嚗峕�憭扯��� Token �蠘�𨰜��
- **霈曄蔭摮鞾△�Ｙ頂蝏毺�����鮋睸�行⏛**嚗𡁜銁 `SettingsScreen.kt` 憿嗅�撘訫�鈭� `BackHandler` �行⏛�具���憭��霈曄蔭鈭𣬚漣摮鞾△�ｇ�憒� API 蝞∠��������亦�嚗㗇𧒄嚗峕�蝟餌�餈𥪜��格��见飵餈𥪜�隡𡁻���𧼮�霈曄蔭銝駁△嚗�蘨�匧銁銝駁△�㕑��鮋睸�漤���𧼮��𠰴予隡朞�憿蛛��孵�鈭��雿𦦵凒閫剹��
- **憭𡁶輕摨衣�����乩��笔膥�亙�**嚗𡁏鰵憓� `EnvironmentProvider.kt`嚗�𣈲��㴓憓���� Lux 璉�瘚衤��菟�/��㩞�嗆���甇交��伐���BluetoothProvider.kt`嚗�𣈲�� API 31+ �萘����璉�瘚页�撌脫���𧒄�堒枂憭𤥁挽�㵪��芣���𧒄�滨漣銝粹�朞� `AudioManager` 璉��� A2DP 餈墧𦻖�嗆���銝� `ActivityProvider.kt`/`ActivityReceiver.kt`嚗�毽��暑�函𠶖����怒������朞� Broadcast �交𤣰 Google Play Services �嗆����頣��� Google �滚𦛚�𡝗�����嗉䌊�券���𤥁秐�砍𧑐�𣳇�笔漲霈∩�甇交㺭霈⊥㺭�函�皛斗郭皛穃𢆡蝒堒藁摰墧𧒄霈∠� Still/Walking/Running �嗆�����
- **�䭾�摰墧𧒄憭拇��毺䰻�亙�**嚗𡁏鰵憓� `WeatherProvider.kt`嚗�⏚�� `LocationProvider` �瑕��啁� GPS 摰帋�嚗屸�朞��� Key 撘�皞鞉��� `wttr.in` �芸𢆡霂瑟�撟嗉圾�𣂼��滢�蝵桃�銝剜�摰墧𧒄憭拇�嚗峕���𣄽�亥��拍�銝𠹺����摰䂿緵摰��銝滢�韏硋��� MCP �滚𦛚��𧋦�啣��嗅予瘞娍��乓��
- **�祉�蝵𤑳��𦦵揣 API UI �滨蔭**嚗𡁜銁 `ApiConfig` 摰硺�蝐颱葉�啣�鈭��銝芰𡠺蝡𧢲�蝝ａ�蝵桀��改�撟嗅銁 `SettingsScreen.kt` ��芋�讠�颲穃撕蝒� `ApiConfigDialog` ���𡏭�蝵烐�蝝Ｔ�嘥��喃��對�隞� AnimatedVisibility �函𤫇憓𧼮��𦦵𡠺蝡讠�蝏𨀣�蝝� API 霈曄蔭�姸I �睃��Ｘ踎嚗�𣈲���㗇𥋘�𦦵揣撘閙� Provider Tavily/Custom嚗屸�蝵� Base URL嚗䔶誑�𠰴����蝵� API Key嚗㚁�霈曇恣蝚血� Claude 蝢𤾸郎��
- **UI 撟嗅�����行⏛**嚗𡁜銁 `ChatViewModel.kt` �� `ChatScreen.kt` 撘訫� `isMcpRunning` �嗆���霂����憭扳芋�见銁�肽���`isThinking`嚗㗇� MCP 甇�銁餈鞱�嚗ǑisMcpRunning`嚗㗇𧒄嚗𣬚��典����銝舘��交����頧阡睸嚗𣬚蔭�啣僎蝳�鍂�煾����殷�隞仿俈甇Ｗ僎�穃��餃��穃紡�渲��唳旿��
- **R3 �箄��贝”�拍��毺䰻銝𤾸�雿齿芋��**嚗𡁜銁 `com.loyea.sensor` ���摰䂿緵鈭������交��� `WatchDataRepository`嚗��靘𥟇惣�賣�銵典����餈𣂼𢆡�嗆��芋��㺭�殷��� `LocationService`嚗��朞� `LocationManager` 霂瑟�蝟餌��笔����蝎鍦漲�啁�摰帋��硋�摨閗��鮋�霈曄� mock 蝏讐漪摨佗���

### Changed (�䀹凒)
- **��𧋦��捆�踵��芰眏�㗇𥋘憭滚�**嚗帋蝙�� `SelectionContainer` �����ㄨ鈭�鍂�瑟��舐� Text �� AI 瘨���� MarkdownText 皜脫���𧋦摰孵膥嚗峕��帋��峕䲮�煾�����𧋦��捆�踵�撘孵枂�㗇���𣈲��鍂�瑁䌊�梢�㗇𥋘憭滚��孵�������蝥批��賬��
- **蝘駁膄�亙熒餈墧𦻖�����鸌摰𡁜���**嚗𡁜笆 `SettingsScreen.kt` �拍��毺䰻璅∪�銝凌�𡏭��亙��枏�摨瑁��乒�萘��讛膩餈𥡝�鈭��𡁶鍂�折����撠��𨅯�甇交䔉�� OPPO�亙熒 / 甈Ｗ云�亙熒��㺭�栽�萘�����孵�摮埈甅�娪膄嚗峕㺿銝粹𢒰�烐��� Android 霈曉����𡁶鍂�讛膩嚗䔶��䔶蝙�嗆凒憟穃� Health Connect �舀��典��栞挽憭��摨訫��祈捶嚗���嗡��嗘��券�摨訫��亙藁銝𤾸��賬���暑�函𠶖����怒������朞� Broadcast �交𤣰 Google Play Services �嗆����頣��� Google �滚𦛚�𡝗�����嗉䌊�券���𤥁秐�砍𧑐�𣳇�笔漲霈∩�甇交㺭霈⊥㺭�函�皛斗郭皛穃𢆡蝒堒藁摰墧𧒄霈∠� Still/Walking/Running �嗆�����
- **�䭾�摰墧𧒄憭拇��毺䰻�亙�**嚗𡁏鰵憓� `WeatherProvider.kt`嚗�⏚�� `LocationProvider` �瑕��啁� GPS 摰帋�嚗屸�朞��� Key 撘�皞鞉��� `wttr.in` �芸𢆡霂瑟�撟嗉圾�𣂼��滢�蝵桃�銝剜�摰墧𧒄憭拇�嚗峕���𣄽�亥��拍�銝𠹺����摰䂿緵摰��銝滢�韏硋��� MCP �滚𦛚��𧋦�啣��嗅予瘞娍��乓��
- **�祉�蝵𤑳��𦦵揣 API UI �滨蔭**嚗𡁜銁 `ApiConfig` 摰硺�蝐颱葉�啣�鈭��銝芰𡠺蝡𧢲�蝝ａ�蝵桀��改�撟嗅銁 `SettingsScreen.kt` ��芋�讠�颲穃撕蝒� `ApiConfigDialog` ���𡏭�蝵烐�蝝Ｔ�嘥��喃��對�隞� AnimatedVisibility �函𤫇憓𧼮��𦦵𡠺蝡讠�蝏𨀣�蝝� API 霈曄蔭�姸I �睃��Ｘ踎嚗�𣈲���㗇𥋘�𦦵揣撘閙� Provider Tavily/Custom嚗屸�蝵� Base URL嚗䔶誑�𠰴����蝵� API Key嚗㚁�霈曇恣蝚血� Claude 蝢𤾸郎��
- **UI 撟嗅�����行⏛**嚗𡁜銁 `ChatViewModel.kt` �� `ChatScreen.kt` 撘訫� `isMcpRunning` �嗆���霂����憭扳芋�见銁�肽���`isThinking`嚗㗇� MCP 甇�銁餈鞱�嚗ǑisMcpRunning`嚗㗇𧒄嚗𣬚��典����銝舘��交����頧阡睸嚗𣬚蔭�啣僎蝳�鍂�煾����殷�隞仿俈甇Ｗ僎�穃��餃��穃紡�渲��唳旿��
- **R3 �箄��贝”�拍��毺䰻銝𤾸�雿齿芋��**嚗𡁜銁 `com.loyea.sensor` ���摰䂿緵鈭������交��� `WatchDataRepository`嚗��靘𥟇惣�賣�銵典����餈𣂼𢆡�嗆��芋��㺭�殷��� `LocationService`嚗��朞� `LocationManager` 霂瑟�蝟餌��笔����蝎鍦漲�啁�摰帋��硋�摨閗��鮋�霈曄� mock 蝏讐漪摨佗���

### Changed (�䀹凒)
- **蝘駁膄�亙熒餈墧𦻖�����鸌摰𡁜���**嚗𡁜笆 `SettingsScreen.kt` �拍��毺䰻璅∪�銝凌�𡏭��亙��枏�摨瑁��乒�萘��讛膩餈𥡝�鈭��𡁶鍂�折����撠��𨅯�甇交䔉�� OPPO�亙熒 / 甈Ｗ云�亙熒��㺭�栽�萘�����孵�摮埈甅�娪膄嚗峕㺿銝粹𢒰�烐��� Android 霈曉����𡁶鍂�讛膩嚗䔶��䔶蝙�嗆凒憟穃� Health Connect �舀��典��栞挽憭��摨訫��祈捶嚗���嗡��嗘��券�摨訫��亙藁銝𤾸��賬��
- **颲枏�獢��頧行㜃�芯�餈�誘**嚗𡁜銁 `ChatScreen.kt` 銝剝����颲枏�獢�� `onValueChange` �滚��橘�餈�誘鈭�����頧阡睸��鈭抒���揢銵𣬚泵嚗Ǒ\n`/`\r`嚗㚁�雿輻鍂�瑕銁靽脲�颲枏�獢��憭��銵峕�銵諹䌊����拙��賢�����塚��䭾��朞��噼膠�格揢銵䕘��𣂼�鈭���∩�霂肽��乩�撉䎚��
- **�典� UI �駁膄�𦯷I�嘥��瑚誑撘箏��芯撈瘝㗇絡��**嚗帋蛹�踹��誩��啣����𦯷I�脲��航�瘙�銁�屸𢒰銝剜��剔鍂�瑞�瘝㗇絡���靽格㺿鈭� `MainScreen.kt`��WelcomeScreen.kt`��TavernScreen.kt`��TavernCardParser.kt` �� `PerceptionMcpServer.kt` ���憭� UI �曄內��𧋦�屸�霈日�霈暹�蝷箄�嚗��撠�儒颲寞��𦯷I鈭箸聢�晦�萘移蝞�銝算�靝犖�潸��腈���𨅯�AI瘜典�蝟餌��笔��園𡢿�苷耨�嫣蛹�𨀣釣�亦頂蝏笔��嗆𧒄�氯�腈����脣㨃暺䁅恕�剔�隞见�撘訫紡霂凋葉���𦯷I 隡嗘撈/AI �拍�/AI 霂剛��苷耨�嫣蛹�靝�隡�/�拍�/銋阡𢒰霂剛��嘅���
- **�啁�雿滨蔭�瑕��脰秤撖潮���**嚗帋耨�� `LocationProvider.kt`����拍�摰帋�撘��喉�`useRealLocation`嚗匧��剜�蝟餌�摰帋�����芣�鈭�𧒄嚗䔶��滩��硺遙雿閖�霈�/璅⊥����蝥砍漲�唳旿嚗諹�峕糓憒���� AI 餈𥪜�撖孵����撖澆��嗆��祗嚗䔶蝙憭扳芋�贝�憭笔�蝖桀�撖潛鍂�瑕��臬��單��函頂蝏蠘挽蝵桅������
- **瘚���交𤣰銝� Thinking 撘箏�撅訫�蝡墧��耨憭�**嚗𡁻���� `ChatViewModel.kt` �� `collect` 瘚���湔鰵�餉���銁�交𤣰 Thoughts �� Content �瑟鰵�塚�隞𤾸��典��𤩺㺿銝箏𢆡��粉�𡝗��啁� `messages.value` 撟嗅銁�嗅抅蝖�銝𡃏�銵峕��舀鼧韐嘅�隞舘�䔶��嗵鍂�瑕銁瘚��颲枏枂�罸𡢿�见𢆡�孵稬�睃�鈭抒��� `hasUserToggledThoughts` �峕��删𠶖���閫��鈭���删𠶖��銁瘚��餈��銝剛◤�祇𡢿�墧�撘箄�撅訫�������瘣𠺶��
- **閫坿𠧧�∪�����賢�**嚗帋耨�� `MainScreen.kt` 靘扯器�誩��𤩺�獢��撠��𡏭��脤�擐��嗪��賢�銝箸凒�笔𢆡���𦯷I鈭箸聢�晦�腈��
- **憭扳芋�钅�帋縑撅�𡠺蝡贝�蝵烐�蝝Ｖ� context ��僎�齿�**嚗𡁜銁 `LlmClient.kt` 銝剝���� `sendChatCompletionStream` �� `sendRawChatCompletion` 摨訫��亙藁���撘��舐𡠺蝡贝�蝵烐�蝝Ｖ��滨蔭鈭� API Key �塚�隡𡁜�靚�鍂 Tavily �𦦵揣�亙藁嚗�僎撠��蝝Ｙ��𨅯� 5 �∠�靽⊥�隞� Markdown �澆�������躰蕭�惩�撟嗡耨�孵� user 瘨���笔���錰撠橘�瘜典�憭扳芋�� context 銝哨�撟嗉䌊�冽㜃�芸��賢之璅∪��祈澈�� `web_search: true` �� `enable_search: true` �毺�撅墧�找誑�脣���葉頧祆𥁒�踺����嗡��冽�撘讛��箏�蝡臬��� `[�� 甇�銁餈𥡝��祉�蝵煾△璉�蝝�...]` ���憟賣�蝷箝��
- **�拍��毺䰻銝𠹺���之�滚�**嚗𡁜銁 `PhysicalContextManager.kt` 瘜典�鈭��銝芣鰵憓䂿�隡䭾��其�憭拇� Provider嚗�僎�典之璅∪��舐鍂��頂蝏毺����銝𧢲� `buildPhysicalContextString()` 銝哨��滚��潭𦻖鈭���嗅予瘞𢛵��暑�函𠶖����㴓憓���扼��㩞瘙删蓡���/��㩞�嗆������坔�霈曇��亙�蝘啁��啁輕摨行㺭�柴��
- **MiMo ���憸�挽銝𡡞�霈文㨃��嵗��**嚗𡁜銁 `SettingsScreen.kt` �� providersList �諹��券�㗇𥋘�餉�銝剜鰵憓硺� `MiMo` ���璅⊥踎嚗��霈� Base URL `https://api.xiaomimimo.com/v1` �峕芋�� `mimo-v2.5-pro`嚗㚁�撟嗅銁 `ChatViewModel.kt` ���霈文㨃���憪见��𡑒”銝凋蛹�啁鍂�瑁蕭�牐�憸�挽�� `MiMo 2.5 Pro` �∠�撟園�霈文��臭��𠉛��𦦵揣��
- **摮睃�蝞∠�撅�� ViewModel ��絲�賣㺭�㚚���**嚗𡁜� `ChatStorageManager.kt` �笔��餃��� `runBlocking` ��辣摮睃��寞��券��齿�銝箏�蝔𧢲�韏瑕遆�堆�`suspend`嚗㚁�撟嗅�摨訫� `Mutex` ������蝘餃𢆡�喃撈�笔笆鞊∩葉嚗ǑsessionsMutex`��messagesMutex`��cardsMutex`嚗匧��啣�撅��蹱���蝳颯����園���� `ChatViewModel.kt` ����劐��∟��券曎嚗���ａ�朞� `viewModelScope.launch(Dispatchers.IO)` 撠�粉�䠷�餉������ IO 蝥輻�瘙𩤃�撟嗅銁�䀹揢摰峕��𤾸⏚�� `withContext(Dispatchers.Main)` 摰匧����銝餌瑪蝔贝�銵� UI �瑟鰵嚗峕�蝏苷�銝餌瑪蝔钅獈憛𧼮㨃甇駁����撟園�憟埈𣈲����笔��𡝗凒�唳𦻖�� `updateSessionMessages` / `updateSessionList`��
- **McpClient �滚��睲��滩�蝏��隡睃�**嚗𡁜銁 `McpClient.kt` 銝凋蛹憭𡁶瑪蝔见�鈭怎� `messageEndpoint` 銝� `endpointDeferred` 餈賢�鈭� `@Volatile` 蝥輻��航��扳�霈堆�蝎曄�撟園���� SSE �⊥��滚��� SSRF ��㜃�芣�撖寥�餉�嚗𣬚�銝��� `finalHttpUrl` 閫��憭�㜃�芸��滩��䕘�撟嗅笆餈墧𦻖�𦠜𦆮撘�虜餈𥡝�鈭���臬笆朣僐��
- **皜���𦯀�瘚��撌亙�憭��颲�𨭌�賣㺭**嚗𡁜��支� `ChatViewModel.kt` 銝凋��漤�閬�� `handleStreamToolCalls` 颲�𨭌�寞�嚗��撌亙��扯��䔶�銝𧢲���僎��葉蝏扳�蝔讠�銝��游��乩蜓撽勗𢆡敺芰㴓嚗峕����蝔见�蝏𤘪�����𡁏�扼��
- **API �亙藁憸�挽璅⊥踎銝擧芋�见�蝘唳嵗��**嚗𡁏䰻�����之 LLM �滚𦛚������啣��� API 閫��嚗���� DeepSeek ��漣�� V4 蝟餃�����寞�獢���湛�嚗�笆 `SettingsScreen.kt` �� `ChatViewModel.kt` 銝� API 餈墧𦻖�Ｘ踎���霈曉�潦��芋�见�雿滨泵���憪见㨃���銵���Ｘ嵗�����霈斗鰵撱箸芋�衤�蝷箔��牐�蝚血���鍂���啁� `deepseek-v4-pro`��僎銝𥪜銁 `LlmClient.kt` 憭���𣂷�撖� `deepseek-v4-pro`嚗��蝥扳綫��/撣行楛摨行�肽���銝� `deepseek-v4-flash`嚗�虜閫����/銝滚蒂�肽�����楝�勗��啗蓮�Ｙ�摰帋��𣬚��砍��𤾸�摰寥�餉���
- **�箄�璅∪�頝舐眏撘��喃��祉��譍��箏�**嚗𡁜銁 `ApiConfig` 摰硺�蝐颱葉�啣�鈭� `enableSmartRouting: Boolean`嚗��霈文��荔���㺭撅墧�扼��銁 `SettingsScreen.kt` �� API 蝻𤥁��Ｘ踎銝剜鰵憓硺�撖孵����𨀣惣�賣芋�贝楝�晦�𨭆witch 撘��喉�銝剛㘚���霂剛��芷����滨蔭嚗㚁�靘𤤿鍂�瑞�瘣餅綉�嗆糓�血鍳�典之璅∪�摨訫漣�寞旿�𨀣楛摨行�肽���嘥��喳銁 Pro 銝� Flash 璅∪�銋钅𡢿��䌊�刻楝�望𤜯�Ｕ��銁 `LlmClient.kt` 蝵𤑳�霂瑟�撅��銵䔶��唾��餅鱏���嚗帋�敶栞砲撘��喳��舀𧒄�滢��冽��㺿�� DeepSeek 頝舐眏璅∪�嚗𥡝𥅾�喲𡡒嚗��蝵𤑳��煾��� 100% 撠𢠃�撟嗥′�賊�譍��冽��滨蔭��遙雿閗䌊摰帋��箏�璅∪�頝舐眏嚗峕說頞喃�擃条漣�冽��滚�蝚砌��嫣葉頧祆��孵�瘚贝�����瑕���閬���
- **摨笔��删鍂 MiMo �牐�撟嗆鰵憓� Ollama 銝� Groq**嚗𡁜銁�滚𦛚���銵剁�`providersList`嚗劐葉蝘駁膄�䭾�銋厩� `MiMo` �牐����嚗�僎�啣�鈭�𧋦�啁氖蝥踵芋�𧢲��� `Ollama (Local)`嚗��霈日�霈曉��� API �啣� `http://10.0.2.2:11434/v1`���霈暹綫�鞉芋�� `qwen2.5`��llama3`��mistral`��gemma2`嚗劐�����綫��像�� `Groq`嚗��霈日�霈� API �啣� `https://api.groq.com/openai/v1`���霈暹綫�鞉芋�� `llama-3.3-70b-versatile` ��llama-3.1-8b-instant`嚗㚁���之�唳����蝳餌瑪雿輻鍂�箸艶��遠�潔��亙藁�澆捆閬���Ｕ��
- **�𥪜𢆡�刻�璅∪�銝𡡞�霈暹釣�交嵗��**嚗𡁏凒�唬� OpenAI (�啣� `o1-mini`/`o3-mini`)��imi (�啣� `moonshot-v1-32k`/`128k`)����� (��漣暺䁅恕璅∪�銝� `qwen-plus`)��iniMax (��漣銝� `abab7-chat`) ��綫�鞉芋�见�銵典�銝𧢲���揢�嗥��亙藁�啣���芋�贝䌊�冽釣�亥��踺��
- **Thinking 撅訫��睃�蝑𣇉裦隡睃�**嚗帋��碶��𠰴予憿菟𢒰 Thinking �函��曄�撅閧內�餉�嚗�銁撘��舀鰵銝�頧桐�霂脲𧒄�芸𢆡�睃���蟮 AI 瘨���� Thinking 餈��嚗𥟇��啣�憭滚銁�肽��𧒄暺䁅恕撅訫�嚗�銁���摰峕�嚗ǑDone` 鈭衤辣嚗匧��芸𢆡�嗥憬�睃�嚗𥡝𥅾�冽��刻��箸��湔��函��颱��睃�嚗��隡朞扇敶訫僕憸�𠶖��僎撠𢠃��冽��㗇𥋘嚗䔶��滚撩銵屸��唳�撘���
- **Thinking 霈⊥𧒄蝎曉��碶耨憭�**嚗帋耨甇���肽��𧒄�渡�蝏蠘恣�餉�嚗��霈⊥𧒄��⏛甇Ｙ��晦�𨀣㟲銝芣��交𤣰摰峕��苷耨甇�蛹�𨅯�憪见��箸迤撘誩�蝑娍迤����祇𡢿�嘅��喲�銝� Content 撣批�颲暹𧒄���霈⊥𧒄嚗㚁�敶餃�閫��鈭���鞉迤����湔𧒄�游榆銝齿鱏蝝臬�撖潸稲�肽���埈𧒄�𡁻���撩�瑯��
- **蝎曄�霈曄蔭憿萇鍂�瑁��坔躹��**嚗𡁶宏�� `SettingsScreen.kt` 銝剖�雿嗵�"銝芯犖韏��"�∠�嚗�鉄憭游��� `InlineEditNameField` 銵��蝻𤥁�獢��嚗𣬚鍂�瑞妍�潔�靽萘��其儒�誯▲�典����銝�蝻𤥁�嚗屸��滚�憭�����䭾�雿㯄�瘛瑚僚��
- **蝘駁膄靘扳�蝖祉����蝞勗�雿滨泵**嚗𡁜��� `MainScreen.kt` 靘扳��冽��滢��寧� `"loyea@example.com"` ���蝞望�摮𨰜��𧋦摨𠉛鍂銝餅�蝳餌瑪雿輻鍂嚗峕����餃��蠘�嚗諹砲�牐�蝚行�摰鮋��譍���

### Fixed (靽桀�/�惩𤐄)
- **MiMo �𠉛��𦦵揣 401 �仿�靽桀�**嚗𡁜銁 `LlmClient.kt` 憭扳芋�钅�帋縑撅��憓𧼮�鈭��撖� `"MiMo"` �滚𦛚�� Provider ��鸌畾𡃏��怨�皛扎����冽��冽迨皜𣳇�銝见��航�蝵烐�蝝Ｘ𧒄嚗䔶��芸𢆡�行⏛�娪膄 payload 銝剝������ `"web_search"` 銝� `"enable_search"` 摮埈挾嚗屸��滩圻蝣啁��喟�銝交聢�澆��⊿���紡�� 401 Unauthorized �仿�嚗䔶�霂�� MiMo 皜𣳇��滚��嗅��啗�蝵穃��賜�摰𣬚�甇�虜雿輻鍂��
- **�煾����臬�颲枏�瘜閗䌊�冽𤣰韏�**嚗𡁜銁 `ChatScreen.kt` 撘訫�鈭� `LocalSoftwareKeyboardController`嚗���冽��孵稬�煾����格��刻蔓�桃�銝羓��領�𨅯����脲𧒄嚗𣬚��唾圻�煾睸�䀹𤣰韏瑟�雿頣�`keyboardController?.hide()`嚗㚁���之�唳�����煾��𢆡雿𨅯��𣂼���朖�嗉�蝥輻��寞�憭滢�撉䎚��
- **憭朞蔭瘚��撖寡��嗆��緾���隡朞��滩蝸瞍𤩺�**嚗𡁻���� `ChatViewModel.kt` 銝剔� `startAiResponseStream` �煾���撘閙�嚗���嗆㺿�嗘蛹�箔� `while` 敺芰㴓����鍦����撟喳�撌亙�靚�鍂撽勗𢆡璅∪����頧� MCP 撌亙���㜃�芥���銵䔶�蝏𤘪�餈賢���銁�䔶�銝芸�蝔讠��賢𪂹�笔�敺芰㴓瘚�蓮嚗䔶�霂�� `isThinking` �� `isMcpRunning` ��𠶖��像皛穃��ｇ�敶餃�瘨�膄鈭��頧株��其葉�嗆���蝜�蔭 false 撖潸稲���𦦵𠶖��緾���萘撩�瘀�隞擧覔�砌��𦦵�鈭�鍂�瑞��駁�𡁶䰻�硋��𧼮��唳𧒄嚗𣬚眏鈭舘圻�� `onResume`/`onNewIntent` �� `selectSession` �滩蝸�餉����甇�銁���銝剔� AI ���瘚�撩銵諹��蹱䎺����䔮憸塩��
- **McpClient 霂瑟� Map 瘜�蠧靽桀�**嚗帋��碶� `McpClient.kt` 銝� `sendRequest` �寞�����嗅����餉����蝑匧� response ����嗆𧒄�輻眏 15 蝘埝𦆮摰質秐 30 蝘雴誑憓𧼮撩擃睃辣�嗥�蝏𦦵㴓憓����捆�躰��𨥈��峕𧒄撠� `pendingRequests.remove(requestId)` �墧𤣰�滢���ㄨ�� `finally` �𦯀葉嚗𣬚＆靽嘥朖雿輯��塚��𥕦枂 `TimeoutCancellationException`嚗㗇��𤑳��嗅�撘�虜嚗峕迨 requestId �質�鋡� 100% �芸𢆡皜��嚗�蝠摨閙覔�支� JSON-RPC 瘨��霂瑟��惩�銵剁�`pendingRequests`嚗厩�瞏𨅯銁���瘜�蠧�鞉���
- **ACCESS_NETWORK_STATE ���蝻箏仃**嚗𡁜銁 `app/src/main/AndroidManifest.xml` 銝剛‘��ㄟ�� `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`嚗𣬚＆靽嘥銁 Android 11+ 銝羓�蝏𦦵𠶖���瘚讠�蝔喳�餈鞱���
- **SSRF �詨笆�讛悅蝏閗�瞍𤩺�**嚗𡁜銁 `McpClient.kt` �� `messageEndpoint` �⊿��餉�銝哨��曉��垍�隞颱�隞� `//` �詨笆�讛悅撘�憭渡� URL �滚��𡢅�敶餃��𦦵��𤩺��唳旿憭𡝗��唳𤫇�餉����讐洵銝㗇䲮�� SSRF 憌𡡞埯��
- **McpClient 撟嗅�甇駁�銝舘��交��脣���**嚗𡁜銁 `McpClient.kt` 撖� `connect()` 銝� `handleDisconnect()` 撘訫� `synchronized` �𦯀��乩��歹�摰䂿緵蝥輻��笔��嗆��蓮�Ｕ��銁 `connect()` 餈��銝剜��箏�撣賂���𡠺�讐��𡝗� `CancellationException`嚗㗇𧒄嚗𣬚＆靽嘥銁�睲��𥕦枂�齿�銵� `handleDisconnect()` 隞交���𠶖��僎�𡝗� `eventSource`嚗䔶�憓𧼮�鈭��蝏�𠶖��嵗撉屸俈甇ａ�餈墧香����嗆��砥�嫘��
- **隡朞� JSON �滚��啣僎�𤏸粉�坔�蝒��瘨��閬��銝Ｗ仃**嚗𡁜銁 `ChatStorageManager.kt` 霂餃��寞�銝剖��乩� `Mutex` ���雿輻鍂 `runBlocking` 銝𡡞��滚�����其�撣阡��賣㺭嚗匧��啣�撅��隞嗥�霂餃��𠉛氖嚗𥕦僎�� `ChatViewModel.kt` 靽嘥�瘨���滚�隞𡒊��䀹��𡝗��唳��臬�銵剁�雿輻鍂 `LinkedHashMap` 隞乩��坔�摮䀹��唬耨�嫘���撟嗅��誩榆撘���孵�撠�舅���撟塚�敶餃�瘨�膄鈭���� ViewModel ���瘨��撠���� `GreetingWorker` 銝餃𢆡�坔���䔮�蹱��航��碶腺憭梁��桅���
- **LlmClient SSE 餈墧𦻖瘜�蠧靽桀�**嚗𡁜銁 `LlmClient.kt` �� `sendChatCompletionStream` 銝剖� `execute()` �嫣蛹雿輻鍂 OkHttp �� `use` �𡑒䌊�典��剜㦤�塚�蝖桐��㰘捏�舀迤撣詨�瘚���麄����啣�撣貉��航◤憭㚚��讐��𡝗�嚗��撅�� `ResponseBody` �諹��亙��亙��質�鋡� 100% �芸𢆡�喲𡡒�𦠜𦆮��
- **�拍��毺䰻 Prompt �𡁜�銝𦒘�銝𧢲���蝸**嚗𡁏�撅蓥� `PromptAssembler.kt`嚗峕鰵憓� `physicalContext` ������撘��舀�銵典�甇交�摰帋��毺䰻�塚�撠� `Heart Rate: <bpm> (<State>)` 銝� `Location: <latitude>, <longitude>` �朞� `[Physical Context]` �箏��芸𢆡���撟嗅𢆡��釣�亥秐 LLM System Prompt 銝哨�韏贝�憭扳芋�讠�����亥��䜘��
- **�拍��毺䰻�批��Ｘ踎銝𡒊𠶖�����**嚗𡁜銁 `SettingsScreen.kt` �拙�鈭���思�蝥扳楛撅����� `PhysicalSensorLayout`��鍂�瑕虾隞亦𡠺蝡衤�蝎曄��啗����𨀣惣�賣�銵冽㺭�桀�甇亙��喇�腈���𣈯�敹��餈𣂼𢆡�嗆��芋���霂訫��喇�苷誑�𪙛�𦦵�摰䂿頂蝏� GPS �瑕��𡝗��� Mock 蝏讐漪摨阡�蝵栽�嘅��滨蔭�𥪜𢆡 ViewModel �典��嗆���摨𥪜僎�喳������
- **R4 WorkManager �䠷��𤾸蝱�桀�躰圻�烐㦤��**嚗𡁜銁 `com.loyea.worker` �𥕦遣鈭� `GreetingWorker.kt` �箸� Android WorkManager��遙�∪銁�删��Ｙ��𤾸蝱�讐��扯�嚗屸�暺䁅粉�㚚��㕑��脣㨃�� API �剛�嚗峕��硋��� `[TASK] The user is not looking at the app right now... Generate a VERY SHORT proactive greeting` ��誘銝舘� 10 �∩�銝𧢲�嚗屸�朞�撘箏��喲𡡒瘛勗漲�肽��芋�𧢲�銵峕���綫��繮�� AI 銝餃𢆡�桀�躰祗��
- **銝餃𢆡�桀�嗘�霂嘥�甇乩�蝟餌�蝥折�𡁶䰻�日�**嚗䫤GreetingWorker` 撠���瑕��� AI �桀�躰䌊�刻蓮�Ｖ蛹 Message 摰硺�撟嗆�蝻嘥��𧼮��齿�擃䀝������ JSON 隡朞�瘚��摰峕��唳旿撅��銋���峕郊����舘��� Android 蝟餌� `NotificationManager` 撘孵枂���銝箏��滩��脣之�滨�擃䀝�蝟餌��𡁶䰻����駁�𡁶䰻�喲�朞� `PendingIntent` ���敹𦯀��㕑絲 Loyea App �𠰴予�𡑒”�屸𢒰摰峕��剔㴓鈭支���
- **撘��𤏸��翰���霂訫���**嚗𡁜銁霈曄蔭憿萇�����乩�蝖砌辣�Ｘ踎摨閖�嚗峕�靘𥕢�銝㯄秄�� `DEVELOPER TOOLS` �����𣈲��鍂�瑚��格��� `enqueue(OneTimeWorkRequest)` 璅⊥�閫血��䠷��𤾸蝱�桀�蹱�蝔贝�銵���質�霂閖�霂���
- **MCP 摰Ｘ�蝡臭�憭𡁏��∪膥蝞∠��箏�**嚗𡁜銁 `com.loyea.mcp` �桀�銝见��啗挽霈∪僎摰䂿緵鈭����� JSON-RPC over HTTP/SSE �讛悅摰Ｘ�蝡荔�`McpClient.kt`嚗㚁���鍂撘�郊��絲銝� CompletableDeferred UUID �臭��寥��滚��箏�嚗峕𣈲������撌亙��𤑳緵銝舘��具��
- **McpManager 憭𡁏��∪膥蝞∠�**嚗𡁜��唬�憭𡁏��∪膥餈墧𦻖蝞∠�銝剖�嚗ǑMcpManager.kt`嚗㚁��拍鍂��㺭���蹂� Jitter 摰䂿緵鈭�䌊����齿鰵餈墧𦻖嚗偦��𣂷� ConnectivityManager �嗆����交㦤�塚��賢��冽鱏蝵烐𧒄��絲���蝵烐𧒄�單𧒄�滩�嚗𥡝挽霈∩��滨�撘誩極�瑁楝�曹� Fallback �亥砭嚗屸俈����滚�蝒�僎�舀��𤩺������
- **McpConfigStorage �笔��芣������**嚗𡁜抅鈭� SharedPreferences 撠��鈭���∠垢�滨蔭�𡑒”霂餃�嚗�僎�惩�鈭� try-catch �行⏛銝𤾸�摨誩��𡝗��讛䌊��𣑐�斗㦤�塚�靽嗪��臬𢆡擃条迅摰𡁏�扼��
- **�怠�餈芾𠧧 Claude 蝢𤾸郎�滨蔭�Ｘ踎**嚗𡁏�撅蓥� `SettingsScreen.kt`嚗𣬚��嗡�雿𡡞弗��漲���憸𨅯�潛�蝤函�韐冽� MCP �滚𦛚�函恣��𢒰�選��舀��澆𢙺�冽���內�臬��嗅��啗��亦𠶖���摰䂿緵鈭�虾�典極�瑞�撟單��餃側�睃�/撅訫��函𤫇銝𤾸㨃��䌊����啣����颲㻫����支漱鈭鉝��
- **ChatViewModel 銝𡒊��賢𪂹�蠘��券���**嚗𡁜銁 `ChatViewModel.kt` 銝� `MainActivity.kt` 憭���帋� MCP �滨蔭�𣬚𠶖���摰墧𧒄�匧�嚗�僎�� ViewModel 皜��嚗ǑonCleared`嚗㗇𧒄摰䂿緵摰匧�����仿��曆��噼�瘜券���
- **MCP �訫�瘚贝�閬��**嚗𡁜銁 `app/src/test/java/com/loyea/mcp` �桀�銝讠��嗘� `McpConfigStorageTest` �� `McpRoutingTest` �訫�瘚贝�嚗���𣂼笆�笔��芣���極�瑕�蝻�閫��銝𡡞�𤩺�頝舐眏����ａ�閬���剛���
- **LLM 撌亙�靚�鍂�剔㴓�亙�**嚗𡁜銁 `LlmClient.kt` 銝剜鰵憓� OpenAI-compatible `tools` 霂瑟�蝏𤘪���tool_calls` �滚�閫��銝𤾸極�瑟��臬�憛急芋�页��� `ChatViewModel.kt` 銝剜𦻖�� MCP �𡁜�撌亙�瘜典���極�瑁��冽�銵䎚��McpCallItem` �� `RUNNING`/`SUCCESS`/`FAILED` �嗆��凒�堆�隞亙�撌亙�蝏𤘪��𧼮��𡒊�憭朞蔭��蝏��蝑𠉛��僐��
- **閫坿𠧧�∩�甈∠�颲穃���**嚗𡁜銁 `TavernScreen.kt` 銝凋蛹瘥誩�閫坿𠧧�⊥鰵憓䂿�颲烐��殷�����暹�嚗㚁��孵稬�擧�撘��典� `EditPersonaDialog` 撘寧�嚗屸�憛怠�撌脫�閫坿𠧧�唳旿嚗��蝘啜���隞卝���扳聢��㦤�胯����交洽餈舘���頂蝏�瓲敹�挽摰𠾼����瑟𧋦�����仍�譌����臬�蝥詻���摨閗𠧧嚗剹���摮䀹𧒄�朞� `data class copy()` 靽萘��笔� ID ���蝵格�霈堆��湔鰵�舘䌊�典��坔�閫坿𠧧�𡑒”����硔��
- **LLM �鞟內霂齿遬撘讐鍂�瑞妍�潭釣��**嚗𡁜銁 `PromptAssembler.kt` �� `assembleSystemPrompt` 銝哨�鈭舘��脣�撖潸祗銋见��啣� `[User Info]` 畾菔氜嚗峕遬撘誩��� LLM �冽���妍�潘�憒� `The user's name is "xxx". Address them by this name naturally in conversation.`嚗剹��迨�滢�靘肽� `{{user}}` 摰𤩺𤜯�ｇ��亥��脣㨃�芯蝙�刻砲摰誩� LLM �牐�敺㛖䰻�冽��溻��
- **靘扯器�讐鍂�瑕��湔𦻖蝻𤥁��蠘�**嚗𡁜銁 `MainScreen.kt` 靘扯器�誯▲�函鍂�瑚縑�舀��惩�瘞湔郭蝥寞㟲銵𣬚��颱漱鈭雴�蝻𤥁��暹�嚗𣬚��餃虾�㕑絲蝎曇稲�� `AlertDialog` 撘寧�靽格㺿撟嗡�摮条鍂�瑕�嚗�僎�朞� ViewModel 摰䂿緵�單𧒄����吔��㯄�尠�𨅯縧霈曄蔭憿萄�雿嗵�颲𡢅�靽萘�靘扳��臭�蝻𤥁��亙藁�萘��剔㴓雿㯄���
- **Markdown 銵冽聢擃㗛��潭葡�𤘪𣈲��**嚗𡁜銁 `MarkdownText.kt` ��蝠�� Markdown 撘閙�銝剜�撅閙��嗘�瘚��銵冽聢嚗ǑTableBlock`嚗㕑圾�鞟𠶖��㦤���憟堒��唬� `TableLayout` 皜脫�蝏�辣嚗峕𣈲���撽祉瑪摨閗𠧧����堒��脩瑪�𠰴���聢銵�� Markdown �瑕�嚗𥕦��乩��箄��芷���摰賢漲�箏�嚗�$\le 3$ �堒�皛⊥�瘜∪����$> 3$ �烾�摰� `120.dp` 摰賢僎�舀�璅芸�憿箸�皛𡁜𢆡嚗㚁�閫��鈭�笆霂苷葉憭扳芋�贝��箄”�潭𧒄�䭾�閫��銋望�銝��Ｙ��垍��𤤿���
- **Markdown 銵冽聢瘚钅��嗘僚銝𡡞緾�� Bug 靽桀�**嚗朞圾�喃��� $\le 3$ �埈𧒄雿輻鍂 `weight` 撣���渲◤憭硋� `horizontalScroll` �𣂷��𣳇�摰賜漲���`Constraints.Infinity`嚗匧紡�� Compose �䭾�霈∠��訫��潛征�游��溻����䔶漣�蠘”�潭��䭾�皜脫�撘�虜��撩�瑯�����蛹隞�銁�埈㺭 $> 3$ �嗆�瘣餅赤�烐��典捆�剁�敶餃��脰�鈭��撅�瘚钅�甇駁���
- **憭𤥁�霈曄蔭憿菜�摮堒��冽⏛�剔撩�瑚耨憭�**嚗𡁜銁 `SettingsScreen.kt` 銝凋蛹�𨅯�閫��霂剛��肽挽蝵桀�憿蛛�`ThemeSettingsLayout`嚗厩��� `Column` ��蝸鈭� `verticalScroll(rememberScrollState())` 撟嗅��牐�摨閖� `24.dp` �澆𢙺�渲�嚗諹圾�喃�雿𤾸�颲函�霈曉��𣇉頂蝏笔紡�芣��格𣏹撖潸稲�𡏭㘚���嗪�厰★銵���冽�摮𡑒◤�芣鱏��撩�瘀��峕𧒄撖孵�撅� Screen ����刻��𥡝�銵䔶��厩�撘𤩺��伐�靽嗪�鈭��撅� UI �找辣����典漲��

### Changed (�䀹凒)
- **API �亙藁憸�挽璅⊥踎銝擧芋�见�蝘唳嵗��**嚗𡁏䰻�����之 LLM �滚𦛚������啣��� API 閫��嚗���� DeepSeek ��漣�� V4 蝟餃�����寞�獢���湛�嚗�笆 `SettingsScreen.kt` �� `ChatViewModel.kt` 銝� API 餈墧𦻖�Ｘ踎���霈曉�潦��芋�见�雿滨泵���憪见㨃���銵���Ｘ嵗�����霈斗鰵撱箸芋�衤�蝷箔��牐�蝚血���鍂���啁� `deepseek-v4-pro`��僎銝𥪜銁 `LlmClient.kt` 憭���𣂷�撖� `deepseek-v4-pro`嚗��蝥扳綫��/撣行楛摨行�肽���銝� `deepseek-v4-flash`嚗�虜閫����/銝滚蒂�肽�����楝�勗��啗蓮�Ｙ�摰帋��𣬚��砍��𤾸�摰寥�餉���
- **�箄�璅∪�頝舐眏撘��喃��祉��譍��箏�**嚗𡁜銁 `ApiConfig` 摰硺�蝐颱葉�啣�鈭� `enableSmartRouting: Boolean`嚗��霈文��荔���㺭撅墧�扼��銁 `SettingsScreen.kt` �� API 蝻𤥁��Ｘ踎銝剜鰵憓硺�撖孵����𨀣惣�賣芋�贝楝�晦�𨭆witch 撘��喉�銝剛㘚���霂剛��芷����滨蔭嚗㚁�靘𤤿鍂�瑞�瘣餅綉�嗆糓�血鍳�典之璅∪�摨訫漣�寞旿�𨀣楛摨行�肽���嘥��喳銁 Pro 銝� Flash 璅∪�銋钅𡢿��䌊�刻楝�望𤜯�Ｕ��銁 `LlmClient.kt` 蝵𤑳�霂瑟�撅��銵䔶��唾��餅鱏���嚗帋�敶栞砲撘��喳��舀𧒄�滢��冽��㺿�� DeepSeek 頝舐眏璅∪�嚗𥡝𥅾�喲𡡒嚗��蝵𤑳��煾��� 100% 撠𢠃�撟嗥′�賊�譍��冽��滨蔭��遙雿閗䌊摰帋��箏�璅∪�頝舐眏嚗峕說頞喃�擃条漣�冽��滚�蝚砌��嫣葉頧祆��孵�瘚贝�����瑕���閬���
- **摨笔��删鍂 MiMo �牐�撟嗆鰵憓� Ollama 銝� Groq**嚗𡁜銁�滚𦛚���銵剁�`providersList`嚗劐葉蝘駁膄�䭾�銋厩� `MiMo` �牐����嚗�僎�啣�鈭�𧋦�啁氖蝥踵芋�𧢲��� `Ollama (Local)`嚗��霈日�霈曉��� API �啣� `http://10.0.2.2:11434/v1`���霈暹綫�鞉芋�� `qwen2.5`��llama3`��mistral`��gemma2`嚗劐�����綫��像�� `Groq`嚗��霈日�霈� API �啣� `https://api.groq.com/openai/v1`���霈暹綫�鞉芋�� `llama-3.3-70b-versatile` ��llama-3.1-8b-instant`嚗㚁���之�唳����蝳餌瑪雿輻鍂�箸艶��遠�潔��亙藁�澆捆閬���Ｕ��
- **�𥪜𢆡�刻�璅∪�銝𡡞�霈暹釣�交嵗��**嚗𡁏凒�唬� OpenAI (�啣� `o1-mini`/`o3-mini`)��imi (�啣� `moonshot-v1-32k`/`128k`)����� (��漣暺䁅恕璅∪�銝� `qwen-plus`)��iniMax (��漣銝� `abab7-chat`) ��綫�鞉芋�见�銵典�銝𧢲���揢�嗥��亙藁�啣���芋�贝䌊�冽釣�亥��踺��
- **Thinking 撅訫��睃�蝑𣇉裦隡睃�**嚗帋��碶��𠰴予憿菟𢒰 Thinking �函��曄�撅閧內�餉�嚗�銁撘��舀鰵銝�頧桐�霂脲𧒄�芸𢆡�睃���蟮 AI 瘨���� Thinking 餈��嚗𥟇��啣�憭滚銁�肽��𧒄暺䁅恕撅訫�嚗�銁���摰峕�嚗ǑDone` 鈭衤辣嚗匧��芸𢆡�嗥憬�睃�嚗𥡝𥅾�冽��刻��箸��湔��函��颱��睃�嚗��隡朞扇敶訫僕憸�𠶖��僎撠𢠃��冽��㗇𥋘嚗䔶��滚撩銵屸��唳�撘���
- **Thinking 霈⊥𧒄蝎曉��碶耨憭�**嚗帋耨甇���肽��𧒄�渡�蝏蠘恣�餉�嚗��霈⊥𧒄��⏛甇Ｙ��晦�𨀣㟲銝芣��交𤣰摰峕��苷耨甇�蛹�𨅯�憪见��箸迤撘誩�蝑娍迤����祇𡢿�嘅��喲�銝� Content 撣批�颲暹𧒄���霈⊥𧒄嚗㚁�敶餃�閫��鈭���鞉迤����湔𧒄�游榆銝齿鱏蝝臬�撖潸稲�肽���埈𧒄�𡁻���撩�瑯��
- **蝎曄�霈曄蔭憿萇鍂�瑁��坔躹��**嚗𡁶宏�� `SettingsScreen.kt` 銝剖�雿嗵�"銝芯犖韏��"�∠�嚗�鉄憭游��� `InlineEditNameField` 銵��蝻𤥁�獢��嚗𣬚鍂�瑞妍�潔�靽萘��其儒�誯▲�典����銝�蝻𤥁�嚗屸��滚�憭�����䭾�雿㯄�瘛瑚僚��
- **蝘駁膄靘扳�蝖祉����蝞勗�雿滨泵**嚗𡁜��� `MainScreen.kt` 靘扳��冽��滢��寧� `"loyea@example.com"` ���蝞望�摮𨰜��𧋦摨𠉛鍂銝餅�蝳餌瑪雿輻鍂嚗峕����餃��蠘�嚗諹砲�牐�蝚行�摰鮋��譍���

## [Unreleased] - 2026-06-10

- **隡朞�蝥抒頂蝏�𧒄�湔�蝷箄��㗇𥋘 (�拍��毺䰻)**嚗𡁜銁 `ChatStorageManager.kt` �� `ChatSession` 銝剖��牐� `useSystemTime: Boolean` �滨蔭嚗�僎�� `PromptAssembler.kt` 銝剜𣈲����澆��硋����摰墧𧒄�湔釣�亦頂蝏� Prompt��
- **�拍��毺䰻鈭支�銝𡡞▲�讛圾�阡���**嚗𡁜��典竉蝳颱� `ChatScreen.kt` 憿園�璅∪��㗇𥋘�嗅� DropdownMenu 銝剔��𦦵�����乒�嘥��喉�敶餃�閫����㦤嚗�� OPPO Find X6, ColorOS 16 蝑㚁��曹��嗆����𡁶䰻銝剖�銝𤾸�撅��见飵�剖躹�脩�嚗�紡�港��㕑��閙覔�祆�瘜閧��餉圻�𤑳�鈭支��𤤿���
- **擃䀹﹝�∠�撘譍儒颲寞�摨閙�霈曇恣**嚗𡁜銁靘扯器�� `SidebarContent` 摨閖��箏�嚗���乩��游�鈭��𦦵�����伐��園𡢿嚗争�腈���𡏭��脤�擐��腈���𦦵頂蝏蠘挽蝵栽�萘�蝏煺���綉�園𢒰�踹㨃���Control Panel Card嚗剹���朞��諹�敺株����嚗�蒂�匧��蠘���秩�擧�批����嚗剹���銝����獢�蜓�脰� Icon �������烐�� Chevron �喟悌憭氬��誑�羓移蝏�憬�曄� Switch 撘��喉�摰䂿緵鈭��閫厩��毺���之憌噼���
- **�渲��滚�銝𡡞俈霂航圻�孵稬**嚗帋蛹�𦦵�����乒�肽��𣂷��刻��孵稬鈭衤辣瘨�晶�舀�嚗Ǒ.clickable`嚗㚁��喃噶�孵稬���銋笔虾摰匧�銝娍���𧑐��揢�𦦵頂蝏�𧒄�湔��乒�嘅�閫����㦤銝𠰴�撠箏站 Switch �找辣�曆誑�嫣葉���雿𦦵撩�瑯��
- **DeepSeek �䠷�㗇芋�见�蝥找�撟單�餈�宏**嚗𡁜� DeepSeek ���䠷�㗇芋�见��Ｗ�蝥扳凒�唬蛹 `deepseek-v4-pro` 銝� `deepseek-v4-flash`��
- **��蟮璅∪��滨蔭�芸𢆡皜��**嚗𡁜銁 `ChatViewModel` 銝剜溶�牐� API �滨蔭�芸𢆡餈�宏�餉�嚗�銁�㰘蝸撌脫��滨蔭�塚��芸𢆡撠���脣�撘�� `deepseek-chat` ��漣銝� `deepseek-v4-pro` 撟嗅��躰秐�砍𧑐 SharedPreferences嚗峕����冽��见𢆡蝏湔擪嚗䔶�霂�像皛𤏸�皜～��
- **霈曄蔭銝擧芋�踹��唳凒��**嚗𡁜銁 `SettingsScreen.kt` 銝剖�甇交凒�唬� API �滨蔭璅⊥踎��綫�鞉芋�钅�霈曉�銵具��芋�见�蝘啗��交��牐�蝚佗��� `deepseek-chat` �嫣蛹 `deepseek-v4-pro`嚗匧�憸���唳旿��葉��芋�钅�霈橘�靽脲��典��滨蔭����湔�扼��
- **�笔�憭扳芋�� SSE 瘚��颲枏枂�亙�**嚗𡁻��� `LlmClient.kt` �舐鍂 Server-Sent Events 瘚���𡁻�嚗���嗆�銵峕�閫�� `data:` �交�嚗���典�撘����𧋦���甇仿獈憛𧼮�摨𥪯�鈭箏極撱嗉��枏��箏𢆡�鳴�摰𣬚��𣂼�撟嗅�蝷� DeepSeek �函��橘�`reasoning_content`嚗匧������ `<think>` ��倌��捆嚗諹噢�唳神蝘垍漣鈭支�雿㯄���
- **�𠉛��𦦵揣銝擧楛摨行�肽����單𣈲��**嚗𡁜銁�啣遣�𣇉�颲� API 餈墧𦻖銵典�銝剖��乒�𡏭�蝵烐�蝝Ｔ�苷��𨀣楛摨行�肽���嘅�暺䁅恕撘��荔�銝支葵�拍� Switch 撘��喉��唳旿��蝸餈� `ApiConfig` 摰硺�銝磰䌊�冽�銋��嚗𥕦銁�帋縑撅���桀��唾䌊�冽釣�亙��堆�銝娪�撖� DeepSeek 隡朞��箄�頝舐眏撟嗥���揢 `deepseek-chat` 銝� `deepseek-reasoner` �函�璅∪���
- **�箔� ChatViewModel �� MVVM �嗆��圾�阡���**嚗𡁏鰵撱� `ChatViewModel.kt`嚗屸�銝剖��亦恣�冽��溻��蜓憸塩��祗閮���PI Config �笔����霂嘥�銵典�敶枏�瘨��蝑㗇瓲敹�𠶖���憭扳芋�见�甇交𦻖�園𡡒�臭漱�� `viewModelScope` 憭��嚗�蝠摨蓥耨憭滢� Activity �典�撟閙�頧研���蝵桅�頧賢����瘜��銝𡒊𠶖���銝Ｙ� Bug��
- **擃㗛��潸蝠�讐漣 Markdown 皜脫��其���**嚗𡁜�蝥� `MarkdownText.kt`嚗���啗�蝥扳醌�𤩺㦤�塚��拙��舀�撖� **憭𡁶漣��� (`#`)**��**�匧�銝擧�摨誩�銵� (`-`/`1.`)**��**撘閧鍂�� (`>`)** �� **��𠧧蝥� (`---`)** ���靽萘�閫���� Compose 蝎曇稲�垍�嚗䔶耨憭滢� `Divider` ����� API 靚�鍂霅血���
- **敶餃�皜�膄 Git ��蟮蝖祉��� API Key 敹怎�**嚗朞圾�� `MainActivity.kt` 銝剔� DeepSeek API Key 蝖祉�����塚�撖寥�霈文��唳㺿�嗘蛹 `""` 隞乩��文��剁��滚�銝梶鍂�� python �𡁏𧋦嚗屸�朞� `git filter-branch` 敶餃�皜��鈭�𧋦�啣��脫�鈭支葉�����翰�扳�蝥嫘��
- **�芸𢆡�� Gradle 蝻𤥁�銝𡒊倌�� APK �穃�**嚗朞‘����𣂷� `gradlew.bat` 銝� wrapper嚗�僎�� Gradle 銝剜溶�� release 蝑曉�撖�𤨎嚗�銁 Windows �臬�銝衤��格��毺�霂𤏸��箏�憭�迤撘讐倌�滨�擐碶葵 0.1 ��𧋦�� Release APK��
- **SillyTavern �㘾� V2 閫�聢��� PNG 閫坿𠧧�⊿��坔紡��**嚗𡁜銁 `TavernScreen.kt` 銝剛挽霈∪僎摰䂿緵鈭��靽萘� PNG �𣂼�閫坿𠧧�∪紡�箸䲮瘜𨰻���閫坿𠧧�芾挽蝵桀仍�𤩺𧒄嚗𣬚頂蝏罸�朞� Canvas �典�摮䀝葉�芸𢆡皜脫��箏蒂�㕑��脣之�溻���隞见� "Loyea Persona Card" 敺桀�撠𤩺���緒�啗羲�脫��� PNG �∪抅嚗𥕦�摮睃銁憭游��塚��芸𢆡靚�鍂蝟餌�雿滚㦛撘閙�頧祉�銝� PNG����𡡞�朞�瘚�� Chunk �急�摰帋��� IHDR �堒�摰匧�瘜典� Base64 �𡒊� V2 ��� JSON 隞亙��齿鰵霈∠� CRC32嚗��蝢擧��𡁶洵銝㗇䲮�㘾�摨𠉛鍂撖澆��澆捆��
- **SillyTavern �㘾� V2 閫�聢��� JSON �滨蔭��辣撖澆枂**嚗𡁏𣈲���朞��笔𧑐憭𡁏聢撘譍��㕑��𤏪�銝��桀�閫坿𠧧�∪�憿孵��扯蓮�Ｖ蛹 SillyTavern 摰䀹䲮 V2 Schema �澆�撟嗥��� JSON ��辣嚗�⏚�函頂蝏� Action_Send 銝𤾸��� FileProvider 撖澆枂��澈��
- **�𠰴予�屸𢒰雿𡡞�𤩺�摨行楚����臬�蝥豢葡��**嚗𡁜銁 `ChatScreen.kt` 銝剖��乩� `rememberBackgroundPainter`嚗�⏚�� `Modifier.paint` 隞� `alpha = 0.12f` ���雿𦒘��𤩺�摨血� `ContentScale.Crop` ��芋撘誩銁�𠰴予瘚���臭蜓摰孵膥�峕艶撅�葡�栞��脩�摰𡁶��砍𧑐憯�爾�曄�嚗䔶�隞��蝢𤾸鐤摨𥪯�鈭箄挽銝枏�銝駁�嚗䔶�蝖桐����撖寞�摨虫�蝘�嚗䔶�銝脲神銝滚僕�啣��唳�摮烾�霂颯��
- **摰匧�頝典��� FileProvider ��澈**嚗𡁜銁蝻枏��桀�銝讠� `exports/` ���銝湔𧒄摰匧��曹澈�箏�嚗�僎�滚� Intent Flag 撖孵�鈭怎� PNG 銝� JSON 餈𥡝�銝湔𧒄���霂餃�嚗峕�蝏苷� Android 7.0+ 蝟餌�銝羓� FileUriExposedException��
- **鈭箄挽�潭𦻖銝𤾸�雿滨泵摰𤩺葡�枏��� (PromptAssembler)**嚗𡁜��啣�撱箔� `PromptAssembler.kt`嚗���唬�撠�瓲敹�挽摰𠾼���扳聢����胯����瑟𧋦撖寡����餈𥡝�����㚚�擐�聢撘𤩺𣄽�亦�撘閙�嚗�僎�舀� `{{char}}` �� `{{user}}` 蝑㗇�蝑曄�摰𤩺𤜯�ｇ�摰䂿緵��迤����脫肼瞍娍�瘚豢���
- **鈭箸聢�芸�銋㕑”�閙���**嚗𡁜銁 `TavernScreen.kt` ��䌊摰帋�銵典�撘寧�銝剛‘����𨀣�扳聢霂齿��讛膩�腈���𨅯笆霂嘥㦤�航挽摰尠�嘥��𨅯��瑟𧋦撖寡�����苷�銝芸�銵諹��亙�嚗峕𣈲���憭��憭𡁶輕摨虫犖�潔縑�舀𧋦�唳�銋��銝擧��𣳇△銝剝�靽萘�撅閧內��
- **�澆捆�㘾� (SillyTavern) 閫坿𠧧�⊿��嗘�閫��蝟餌�**嚗𡁜歇�冽鰵�𥕦遣鈭� `TavernCardParser.kt`嚗���啗蝠�𤩺�撘讐� PNG `tEXt` �埈醌�譍� Base64 �𣂼��𣂼�閫���箏�嚗諹䌊�典�摰� V1 銝� V2 `data` ���鈭箄挽閫����
- **5 甈暸���捶蝟餌�憸�蔭鈭箸聢**嚗𡁜�蝵桐����批𨭌�� Loyea��暑瘜澆�憡�𤨓憡睃����������屸�靽脲��胯��悸�曉�隞��鞊芾�銝𨅯辺隞亙�隞��摰⊥䰻撖澆� Linus 蝑� 5 甈曆葵�折��汿����𥕦鐤霂凋��鞟內霂齿�蝤函移蝏���嘥�閫坿𠧧��
- **蝚血� Claude 蝢𤾸郎����脤�㗇𥋘�賢� (SelectPersonaSheet)**嚗𡁏鰵撱箔�霂脲𧒄嚗䔶��滨凒�亙�撱箇征撖寡�嚗諹�峕糓�芸��睲��㕑絲�箔� Compose `ModalBottomSheet` ����渲��脣㨃����㗇𡂝撅剹���匧��𠬍�銝箸鰵隡朞�瘞訾����蝏穃�霂亥��脣㨃嚗�僎�冽��舀�憭湧��芸𢆡�穃枂銝�憯啗砲閫坿𠧧銝枏��� `firstMessage` 甈Ｚ�霂准��
- **�典��質��脤�擐�恣��葉敹� (TavernScreen)**嚗𡁜��啣�撱箔� `TavernScreen.kt`��鍂�瑕虾�朞�靘扯器�誩��冽鰵憓䂿��𡏭��脤�擐��脲��桃凒颲整��𣈲���
  - **�芸�銋匧�撱�**嚗𡁜��怠仍�譍蜓憸䁅𠧧靚��㗇𥋘���蝘啜���隞卝��頂蝏� System Prompt����𥕦鐤甈Ｚ�霂滨�摰���𥕦遣 Dialog 銵典���
  - **PNG 銝� JSON ��辣�㗇𥋘撖澆�**嚗帋蝙�� Android 蝟餌� GetContent ��辣�㗇𥋘�券�㗇𥋘 PNG 閫坿𠧧�∴��𣂼��嗡葉�𣂼��唳旿嚗�僎�芸𢆡撠��皜��蝏睃��曉��嗅�摨𠉛鍂蝘���桀� `context.filesDir/avatars` 銝剜�銋��銝箏仍�𧶏��硋紡�� JSON �滨蔭��
  - **JSON 敹急㭘��澈撖澆枂**嚗𡁜抅鈭� Action_Send ��𧋦��澈 Intent嚗䔶��桀�閫坿𠧧�唳旿摨誩��硋紡�箏�鈭恬�摰䂿緵銝𤾸�摰�像�啣�霈曉����蝢擧��𠾼��
  - **�∠��𣳇膄銝𤾸仍�讐�摮睃���**嚗𡁶鍂�瑕虾�𤩺𧒄蝘駁膄�芸�銋匧㨃����𣳇膄�嗡��芸𢆡皜���嗅��函��砍𧑐憭游��曄�蝻枏�嚗䔶�����刻蝠�譌��
- **鈭箄挽銝𤾸之璅∪�撽勗𢆡�券𢒰閫���**嚗𡁻���� `LlmClient` 銝� `ChatScreen` ��笆霂肽�蝥踹���銁�𤏸�蝔� LLM �煾���憭拙��塚��芸𢆡�羓�摰朞��脩� `systemPrompt` 雿靝蛹 `system` 閫坿𠧧蝏���� payload 瘨���笔� of 蝚� 0 雿㵪��喃犖霈� System �鞟內霂㵪���蝙敺𡑒�憭抵�蝔衤葉嚗𣬚鍂�瑕虾隞亙銁憿園�隞餅��剖��Ｗ�撅��憭扳芋�钅店�券�蝵殷�憒���怠����霂萘眏 Deepseek ��揢�� Claude 3.5 撽勗𢆡嚗㚁��𣬚𤨓憡䀝犖霈曉�霈啣�銝滚�敶勗���
- **憿嗆��嗅��諹�蝥扯�憭滚��㗇𥋘��**嚗𡁜���𧋦 of ModelSelector ��漣銝箏��啁�擃㗛��潸��𠺪��舀�撌虫儒�曄內閫坿𠧧��耦憭游�嚗���砍𧑐憭游��㰘蝸銝𤾸�撣���脤�摮埈��𨅯�嚗剹����脣��滚之摮埈�憸塩���撅�芋�见�摮堒����隞亙�銝𧢲�撠讐悌憭湛��典�憿曇圾�虫�閫坿𠧧���隞������嗅��啣�蝢𡒊�閫��鈭支���
- **Gson 摨誩��碶�韏�**嚗𡁜銁 `app/build.gradle.kts` 銝剖��乩� `com.google.code.gson:gson:2.10.1` 靘肽�嚗䔶誑�舀��𠰴予�唳旿�砍𧑐����硔��
- **�砍𧑐隡朞��𦠜��臬��函恣��膥 (ChatStorageManager)**嚗𡁜��啣�撱箔� `ChatStorageManager.kt`嚗�⏚�� Android 摨𠉛鍂蝘���桀�嚗Ǒcontext.filesDir`嚗劐誑 JSON �澆�摮睃�隡朞��𡑒”��㺭�� (`sessions_metadata.json`) 隞亙���𡠺蝡衤�霂萘�瘨����蟮 (`session_{id}.json`)��
- **憭帋�霂嗪�蝳颱��冽�����**嚗𡁜銁 `MainActivity` 撅�漣�齿��𣂼��唳旿皞鞟恣��㦤�塚��典��Ｖ�霂脲𧒄蝎曄＆�冽��粉�硋僎�曄內撖孵���蟮嚗�蝠摨閖�蝏苷��䔶�霂嗪𡢿��㺭�柴��銁�券��𣳇膄隡朞��舘��芸𢆡����啁�暺䁅恕隡朞�嚗峕�靘𥕢�擃睃捆�躰器�屸�餉���
- **擐𡝗辺�冽�瘨���芸𢆡���隡朞����**嚗𡁏鰵�𥕦遣���霂嘥��煾��洵銝��∠鍂�瑟��舀𧒄嚗𣬚頂蝏煺��芸𢆡�𣂼�霂交��舐��� 15 銝芸�雿靝蛹霂乩�霂萘����撟嗅�甇交�銋���唳𧋦�堆�隡睃�鈭��憸条��𣂷�撉䎚��
- **靘扯器�讛���翰�瑕��支�霂�**嚗𡁜銁靘扯器�� (`SidebarContent`) ����脖�霂嗪★銝剖��牐��𣳇膄�厰僼嚗𣬚��餃朖�舐凒�亙��方砲隡朞��𠰴�撖孵���𧋦�� JSON ��辣嚗�僎�芸𢆡�齿鰵撖孵��舐鍂隡朞�嚗峕�擃䀝�隡朞��笔𦶢�冽�蝞∠��賢���
- **靘扯器�誩��脖�霂脲𧒄�游𢆡���蝏�**嚗𡁜抅鈭𦒘�霂萘����擧暑�冽𧒄�湛�`lastActiveTime`嚗㚁�摰䂿緵鈭��靝�憭抽�腈���𨀣㿥憭抽�腈���𨅯� 7 憭抽�腈���𨀣凒�抽�萘��箄��冽���蝐餅葡�瓐��
- **蝵𤑳��躰秤瘨��摮埈挾**嚗𡁜銁 `Message.kt` 銝凋蛹 `Message` 摰硺�蝐餅鰵憓硺� `isError: Boolean` 撅墧�改�暺䁅恕�潔蛹 `false`嚗㚁�隞亦移蝖格��亙�摮睃�撖寡�餈��銝剔�餈墧𦻖�𢠃�蝵桅�霂胯��
 
### Removed
- **��漣 Pro 撟踹�蝘餃枂**嚗帋�靘扯器�𧶏�`SidebarContent`嚗劐葉敶餃�蝘駁膄鈭���𤏸捶�毺� "Upgrade to Claude Pro" 撟踹��∠�嚗𣬚宏�支�撖孵��� `onUpgradeClick` 鈭衤辣��㺭�� Toast �鞟內�餉�嚗���碶�靘扯器�讐��屸𢒰閫��嚗峕���鍂�瑚�撉䎚��
 
### Changed
- **�𡏭��脤�擐��嗪��賢�銝算�靝犖�潑��**嚗𡁜����厩� UI��儒�誩��典紡�芷�厰★��犖�澆㨃蝞∠�銝剖���▲�𤩺�憸塩��NG 撖澆����撖潸祗隞亙����頝舐眏瘜券�蝑㕑��臬�撅��滚𦶢�滢蛹�靝犖�� (Personas)�腈��
- **�唬�霂脲洽餈舘祗�牐�蝚衣�皜脫�**嚗𡁜銁 `MainActivity` 撘��舀鰵隡朞��塚��拍鍂 `PromptAssembler.formatMessageContent` �芸𢆡�𦠜洽餈舘祗銝剔� `{{user}}` 蝑㗇�蝑暹葡�𤘪𤜯�Ｖ蛹�冽�������蝘啜��
- **�煾��垢�潭𦻖��㺭撅����**嚗𡁜� `userName` �譍��㯄�朞秐 `ChatScreen` ���蝏𨅯���曎頝荔�靚�鍂 LLM �亙藁�嗡��� `PromptAssembler` 蝎曉��潸�撟嗆𤜯�Ｗ�������蝟餌� Prompt��
- **�券𢒰�滚𦶢�齿𤜯�Ｖ蛹 Loyea 憿寧𤌍��**嚗�
  - 撠���厩鍂�� UI �屸𢒰銝剖笆 "Claude" �拍�����函��𣂼��券𢒰�踵揢銝� "Loyea"嚗���� Chat �屸𢒰���霈斗洽餈舘祗��鰵撱箔�霂嘥�憪贝祗����交��牐�蝚佗�"Talk to Loyea"/"銝� Loyea 撖寡�"嚗剹���蝵桅△��蜓憸㗛��潭�餈堆�"Loyea Warm Amber"嚗剹��
  - 撠���其誨���餉�嚗�掩�溻����誩����雿枏�銋匧�嚗劐葉�� `Claude` �滨��券𢒰�滚𦶢�滢蛹 `Loyea`嚗�� `ClaudeTheme` �湔㺿銝� `LoyeaTheme`嚗䈣ClaudeTypography` �湔㺿銝� `LoyeaTypography`嚗䔶誑�� `ClaudeLightBg`��ClaudeDarkBg` 蝑厩頂�烾��脤�蝵桅��賢�銝� `LoyeaLightBg`��LoyeaDarkBg` 蝑㚁���
- **�笔�憭扳芋�讠�蝏𣈯�帋縑��蝸**嚗𡁜銁 `ChatScreen.kt` �� `onSend` �煾����舫�餉�銝哨�蝘駁膄�蹱香�� MCP 憭𡁻𧫴畾萎遛�笔𢆡�鳴���𦻖�笔��� `LlmClient.sendChatCompletion(...)` 撘�郊霂瑟���緵�剁��函�敺���湔迤撣詨��啣�撅��㰘蝸�芰���內�剁��交𤣰�滚��舘恣蝞㛖移蝖桃� API �肽��𧒄�游僎韏讠� `thoughtDurationSeconds`嚗𣬚��𡡞�朞��枏��粹�𣂼�颲枏枂��
- **�芸�銋㕑郎�𢠃�霂舀�瘜⊥葡��**嚗𡁻���� `MessageItem` ��笆 AI 瘨��������餉����瘨���嗆��蛹 `isError = true` �塚�AI �䂿�撠����鍂�𡁶鍂 Markdown + �其��⊥�����峕糓�湔𦻖皜脫�銝箏��匧�閫埝楚蝥Ｚ��荔�`Color(0xFFFDE8E8)`嚗剹��楚蝥Ｙ�蝥輯器獢��`Color(0xFFF8B4B4)`嚗剹��郎�𦠜楛蝥Ｘ��穿�`Color(0xFFE02424)`嚗匧� `Icons.Default.Error` �暹���內��郎�𠰴㨃����峕𧒄�亦氖鈭���譍���𢆡雿𨀣辺嚗���嗚����喟�嚗㚁��𣂼�鈭支�韐券�銝𦒘�撉䎚��
 
### Fixed
- **MCP 摰Ｘ�蝡� 9 憿寥�霂�撩�瑟楛摨虫耨憭滢�隞���惩𤐄**嚗�
  1. **�穃𨯬�讐����瘜��**嚗𡁜銁 `McpManager.kt` 撘訫� `statusJobs: ConcurrentHashMap<String, Job>`��銁 `stop()`��釣���㚚�撱箏恥�瑞垢�嗆遬撘� `cancel()` �嗥𠶖��𤣰���蝔页�敶餃��寥膄�讐��踵���絲撖潸稲���摮䀹�瞍譌��
  2. **Jitter 霈∠�撖寧妍�碶耨甇�**嚗𡁜����輸�餈硺葉�� Jitter 鈭抒��砍��齿�銝� `kotlin.random.Random.nextLong(-jitterRange, jitterRange)`嚗峕��支��𧼮笆蝘啗��讐蔭嚗�僎�� `jitterRange <= 0` �嗡��斗�扯��� `0L` �脫迫霈∠�撏拇���
  3. **摰Ｘ�蝡臬��滢耨�孵朖�嗉圻�煾�撱�**嚗帋耨�� `updateConfigs` �餉�嚗�銁撖寞��嗅��� `existingClient.config.name != config.name` �∩辣嚗���𨅯��滚��港�撠�圻�穃恥�瑞垢�滚遣隞亥悟�滨�撌亙��滚朖�嗆凒�啜��
  4. **�讐� CancellationException �埝部�箏��Ｗ�**嚗𡁜銁 `McpClient.kt` �� `connect` �� `sendRequest` ���㗇��� `catch (e: Exception)` �㛖�蝚砌�銵䕘����銝� `if (e is CancellationException) throw e` 隞交�憭齿迤撣貊��讐�蝏𤘪��硋僎�㻫��
  5. **MainActivity 憿嗅�閫���齿��鞉�瘨�膄**嚗𡁏��� `MainActivity.setContent` 憿嗅��湔𦻖 `.value` 霂餃��嗆���蝻粹萅嚗������Ｙ��嗆��圾���憪娍�霂餃��券�銝𧢲�蝘餃���䌊 `composable` ����券𡡒��葉嚗���齿���凒�𣂼��典��具��
  6. **撟嗅� connect() �笔��折�摰帋� Socket 瘜���脣鴃**嚗𡁜銁 `McpClient.kt` 撘訫� `connectMutex: Mutex` ���撟嗅�餈墧𦻖餈��嚗𥕦銁�啗��亙鍳�典�撘箏��𦠜𦆮�� EventSource (`eventSource?.cancel()`) 撟嗆���唂�� `endpointDeferred`��
  7. **Gson 閫���澆捆�啣�銝𤾸�蝚虫葡 ID 摮埈挾**嚗帋耨�� `JsonRpcResponse` �� `id` 蝐餃�銝� `JsonElement?` 撟園�朞� `idAsString` �𡁏㺭摮𦯀�摮㛖泵銝脫聢撘讛䌊���嚗�𡖂�𣂷� String ���惩遆�唬�����㕑��其�瘚贝��其�����典�摰對��脫迫�曹��啣� ID 撘閗絲閫��撏拇��諹��嗚��
  8. **SSRF �滚��煾��拐��嗆� Payload �芷���脣鴃**嚗𡁏嵗撉� SSE 隡䭾䔉���摰𡁜�蝡舐� host �� port 敹�◆銝𡡞�蝵桃� `sseUrl` �詨�嚗屸俈敺� SSRF 憌𡡞埯嚗𥕦� `handleMessage` �閗繮撘�虜��凒�拙之�� `catch (t: Throwable)` 撟嗅�霈暹�憭� 10MB ��鵭摨阡��嗡誑�� OOM��
  9. **OkHttp �萄偶餈墧𦻖閫��銝𤾸�甇� enqueue ��絲撠��**嚗帋蛹 `OkHttpClient` �滨蔭 `pingInterval(30, TimeUnit.SECONDS)` 敹�歲嚗𥕦銁 `sendRequest` �� `sendNotification` 銝剖��峕郊 `.execute()` �嫣蛹雿輻鍂撘�郊 `.enqueue()` �滢誑 `suspendCancellableCoroutine` 餈𥡝���絲撠��嚗�僎�典�蝔贝◤�𡝗��園�朞� `invokeOnCancellation` 閫血� `Call.cancel()`嚗𣬚＆靽嘥�撅� Socket �𦠜𦆮�删瑪蝔𧢲�瞍譌��
- **靽桀��賢�摰𡁜�蝟餌�憿嗆�撅�葉蝏�辣�见飵�餅𣏹/�䭾��孵稬 Bug**嚗𡁜銁 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 憿嗆��喃儒�啣�鈭� `MoreVert`嚗��銝芰�嚗厩��靝�霂嗪�蝵栽�嘥𢆡雿𨀣��桀� DropdownMenu 銝𧢲��𨅯�嚗峕�靘𥕞�𦦵������ (�園𡢿)�嘥��喳�憭�鍂璅∪��𡑒”��揢���敶餃�閫��鈭�𤙴����嗆��箇頂蝏��憒� ColorOS��IUI 蝑㚁��𣳇�𤩺��嗆����见飵�行⏛�剖躹�𡁜末閬��撅誩�憿園�瘞游像甇�葉敹�躹���撖潸稲憭�� title 瑽賭��� `ModelSelector` 撅�葉�嗅�����颱�隞嗉◤蝟餌��芣鱏�峕�𦒘��寥��㮖�撘�����箏�摰寞�� Bug��
- **靽桀� JVM �唳旿蝐� copy �寞� null ���撏拇�**嚗𡁜銁 [ChatStorageManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt) �� `loadSessionList()` 銝剛挽霈∩�**�脣鴃�芣�撘𤩺㺭�格�瘣埈��惩遆��**���霂餃� JSON 蝻枏��塚��⊥糓璉�瘚见��抒��祆�隞嗥撩憭梁�撅墧�改�憒� `characterId`, `useSystemTime`嚗㚁���銁���銝剖��刻�鈭��摨閖�霈文�澆僎�齿鰵�朞� Kotlin ����惩膥摰硺��硋笆鞊∴��寧�鈭�� Gson �湔𦻖����芸�憪见������銁�扯� JVM �唳旿蝐� `copy()` ��㺭�䂿征�⊿��嗉圻�� `NullPointerException` 撘訫���緾��撏拇���
- **靽桀� Gson JsonNull �函�甇���芣鱏 Bug**嚗帋耨憭滢��� `LlmClient.kt` 銝凋� Gson ���銝剛繮�硋�蝚虫葡�嗅� `JsonNull` �䭾���圾�𣂼�撣賂�閫��鈭���胼�𨀣楛摨行�肽���嘥�憭扳芋�见蘨颲枏枂�肽��曎�䔶腺憭望迤���憭滨� Bug��
- **隡睃� API �滨蔭�∠� UI �文��睃耦**嚗𡁜銁 `SettingsScreen.kt` 銝哨�撠�歇靽嘥�����亙㨃����� `Row` 霈曄蔭銝箏虾璅芸�皛穃𢆡��捆�剁�`horizontalScroll`嚗㚁�撟園��嗆�銝� `BadgeLabel` �閗��曄內嚗ǑmaxLines = 1`嚗㚁�摰𣬚�閫��鈭����倌頞�鵭�文�撖潸稲�港葵�∠�蝥萄��劐撓�睃耦�� UI 蝻粹萅��
- **AI �𧼮�銵�� Markdown 蝎𦯀�皜脫�靽桀�**嚗𡁻���� `MarkdownText.kt` 銝剔� `renderInlineMarkdown` �賣㺭嚗�銁銵��隞����𠧧隞亙�嚗峕𣈲��鍂�峕��� `**` 霂剜��堒�憟��餈�誘撟嗥�摰� `FontWeight.Bold`嚗�蝠摨閗圾�喃� AI �䂿��嗥掩隡� `**Loyea**` 銝滚�蝎㛖�撅閧內 Bug��
- **憿嗆��嗅��諹�蝥扯�憭滚��㗇𥋘��**嚗𡁜���𧋦�� ModelSelector ��漣銝箏��啁�擃㗛��潸��𠺪��舀�撌虫儒�曄內閫坿𠧧��耦憭游�嚗���砍𧑐憭游��㰘蝸銝𤾸�撣���脤�摮埈��𨅯�嚗剹����脣��滚之摮埈�憸塩���撅�芋�见�摮堒����隞亙�銝𧢲�撠讐悌憭湛��典�憿曇圾�虫�閫坿𠧧���隞������嗅��啣�蝢𡒊�閫��鈭支���
- **Gson 摨誩��碶�韏�**嚗𡁜銁 `app/build.gradle.kts` 銝剖��乩� `com.google.code.gson:gson:2.10.1` 靘肽�嚗䔶誑�舀��𠰴予�唳旿�砍𧑐����硔��
- **�砍𧑐隡朞��𦠜��臬��函恣��膥 (ChatStorageManager)**嚗𡁜��啣�撱箔� `ChatStorageManager.kt`嚗�⏚�� Android 摨𠉛鍂蝘���桀�嚗Ǒcontext.filesDir`嚗劐誑 JSON �澆�摮睃�隡朞��𡑒”��㺭�� (`sessions_metadata.json`) 隞亙���𡠺蝡衤�霂萘�瘨����蟮 (`session_{id}.json`)��
- **憭帋�霂嗪�蝳颱��冽�����**嚗𡁜銁 `MainActivity` 撅�漣�齿��𣂼��唳旿皞鞟恣��㦤�塚��典��Ｖ�霂脲𧒄蝎曄＆�冽��粉�硋僎�曄內撖孵���蟮嚗�蝠摨閖�蝏苷��䔶�霂嗪𡢿��㺭�柴��銁�券��𣳇膄隡朞��舘��芸𢆡����啁�暺䁅恕隡朞�嚗峕�靘𥕢�擃睃捆�躰器�屸�餉���
- **擐𡝗辺�冽�瘨���芸𢆡���隡朞����**嚗𡁏鰵�𥕦遣���霂嘥��煾��洵銝��∠鍂�瑟��舀𧒄嚗𣬚頂蝏煺��芸𢆡�𣂼�霂交��舐��� 15 銝芸�雿靝蛹霂乩�霂萘����撟嗅�甇交�銋���唳𧋦�堆�隡睃�鈭��憸条��𣂷�撉䎚��
- **靘扯器�讛���翰�瑕��支�霂�**嚗𡁜銁靘扯器�� (`SidebarContent`) ����脖�霂嗪★銝剖��牐��𣳇膄�厰僼嚗𣬚��餃朖�舐凒�亙��方砲隡朞��𠰴�撖孵���𧋦�� JSON ��辣嚗�僎�芸𢆡�齿鰵撖孵��舐鍂隡朞�嚗峕�擃䀝�隡朞��笔𦶢�冽�蝞∠��賢���
- **靘扯器�誩��脖�霂脲𧒄�游𢆡���蝏�**嚗𡁜抅鈭𦒘�霂萘����擧暑�冽𧒄�湛�`lastActiveTime`嚗㚁�摰䂿緵鈭��靝�憭抽�腈���𨀣㿥憭抽�腈���𨅯� 7 憭抽�腈���𨀣凒�抽�萘��箄��冽���蝐餅葡�瓐��
- **蝵𤑳��躰秤瘨��摮埈挾**嚗𡁜銁 `Message.kt` 銝凋蛹 `Message` 摰硺�蝐餅鰵憓硺� `isError: Boolean` 撅墧�改�暺䁅恕�潔蛹 `false`嚗㚁�隞亦移蝖格��亙�摮睃�撖寡�餈��銝剔�餈墧𦻖�𢠃�蝵桅�霂胯��

### Removed
- **��漣 Pro 撟踹�蝘餃枂**嚗帋�靘扯器�𧶏�`SidebarContent`嚗劐葉敶餃�蝘駁膄鈭���𤏸捶�毺� "Upgrade to Claude Pro" 撟踹��∠�嚗𣬚宏�支�撖孵��� `onUpgradeClick` 鈭衤辣��㺭�� Toast �鞟內�餉�嚗���碶�靘扯器�讐��屸𢒰閫��嚗峕���鍂�瑚�撉䎚��

### Changed
- **�券𢒰�滚𦶢�齿𤜯�Ｖ蛹 Loyea 憿寧𤌍��**嚗�
  - 撠���厩鍂�� UI �屸𢒰銝剖笆 "Claude" �拍�����函��𣂼��券𢒰�踵揢銝� "Loyea"嚗���� Chat �屸𢒰���霈斗洽餈舘祗��鰵撱箔�霂嘥�憪贝祗����交��牐�蝚佗�"Talk to Loyea"/"銝� Loyea 撖寡�"嚗剹���蝵桅△��蜓憸㗛��潭�餈堆�"Loyea Warm Amber"嚗剹��
  - 撠���其誨���餉�嚗�掩�溻����誩����雿枏�銋匧�嚗劐葉�� `Claude` �滨��券𢒰�滚𦶢�滢蛹 `Loyea`嚗�� `ClaudeTheme` �湔㺿銝� `LoyeaTheme`嚗䈣ClaudeTypography` �湔㺿銝� `LoyeaTypography`嚗䔶誑�� `ClaudeLightBg`��ClaudeDarkBg` 蝑厩頂�烾��脤�蝵桅��賢�銝� `LoyeaLightBg`��LoyeaDarkBg` 蝑㚁���
- **�笔�憭扳芋�讠�蝏𣈯�帋縑��蝸**嚗𡁜銁 `ChatScreen.kt` �� `onSend` �煾����舫�餉�銝哨�蝘駁膄�蹱香�� MCP 憭𡁻𧫴畾萎遛�笔𢆡�鳴���𦻖�笔��� `LlmClient.sendChatCompletion(...)` 撘�郊霂瑟���緵�剁��函�敺���湔迤撣詨��啣�撅��㰘蝸�芰���內�剁��交𤣰�滚��舘恣蝞㛖移蝖桃� API �肽��𧒄�游僎韏讠� `thoughtDurationSeconds`嚗𣬚��𡡞�朞��枏��粹�𣂼�颲枏枂��
- **�芸�銋㕑郎�𢠃�霂舀�瘜⊥葡��**嚗𡁻���� `MessageItem` ��笆 AI 瘨��������餉����瘨���嗆��蛹 `isError = true` �塚�AI �䂿�撠����鍂�𡁶鍂 Markdown + �其��⊥�����峕糓�湔𦻖皜脫�銝箏��匧�閫埝楚蝥Ｚ��荔�`Color(0xFFFDE8E8)`嚗剹��楚蝥Ｙ�蝥輯器獢��`Color(0xFFF8B4B4)`嚗剹��郎�𦠜楛蝥Ｘ��穿�`Color(0xFFE02424)`嚗匧� `Icons.Default.Error` �暹���內��郎�𠰴㨃����峕𧒄�亦氖鈭���譍���𢆡雿𨀣辺嚗���嗚����喟�嚗㚁��𣂼�鈭支�韐券�銝𦒘�撉䎚��

### Fixed
- **ChatScreen 憭帋��喳之�砍噡�𣳇膄**嚗𡁏���� `ChatScreen.kt` �詨�蝏�辣憭扳𡠺�瑕偏�典�雿嗵��剖��望𡠺�瘀�敶餃�閫�� "Expecting a top level declaration" �躰秤��
- **Preview 憸���屸𢒰蝑曉�銝��湔�找耨憭�**嚗帋耨憭滢� `ChatScreenPreview` �� `MainScreenPreview` 憸���寞�銝剖��芸�甇亙龪�滩��脣㨃���擐�楝�勗�靚����㺭蝐餃��䭾����霂烐𥁒�辷��朞�蝏穃�瘚贝� dummy �唳旿摰峕��剔㴓��
- **甈Ｚ��屸𢒰撖澆�銝𤾸��其耨憭�**嚗帋耨憭滢� [WelcomeScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/welcome/WelcomeScreen.kt) 隞滚銁雿輻鍂撌脣�撘�� `ClaudeTheme` 撖澆��𠰴�鋆�䔮憸矋�撌脣��嗅�蝥扳𤜯�Ｖ蛹���啁� `LoyeaTheme`嚗𥕦��嗅�甇乩耨�嫣�甈Ｚ��屸𢒰嚗ÁelcomeScreen嚗匧��典之�����𧋦��洽餈𤾸���祗�𠰴��冽��⊥辺甈曆葉�� "Claude" / "Anthropic" ����𣂼�嚗𣬚＆靽嘥鍳�冽洽餈𡡞△����䔶��湔�扼��
- **璅∪��㗇𥋘�𨅯�撅�葉靽桀�**嚗𡁶宏�支� `ModelSelector` 銝� `Box` 摰孵膥�� `fillMaxWidth()` 摰賢漲�䭾說霈曄蔭嚗屸��� `CenterAlignedTopAppBar` ��䌊����箏�嚗�蝠摨蓥耨憭滢�銝𧢲��𨅯�撘孵枂雿滨蔭�誩椰��䔮憸矋�摰䂿緵摰𣬚���偌撟喳��湔迤銝剖�撘孵枂��
- **�冽�瘞娍部�滩𠧧蝏穃�靽桀�**嚗帋耨憭滢� `ChatScreen.kt` 銝剜葡�梶鍂�瑟��舀�瘜∟��航𠧧�塚��删′蝻𣇉�靽桅弘蝚西��臬��啣紡�渲䌊摰帋�摨閗𠧧�𦠜�摮𡑒䌊���銝滨���� Bug��
- **銝餌��Ｗ紡�亙�撣訾耨憭�**嚗帋耨憭滢� `MainScreen.kt` 銝剖�蝻箏� Compose 餈鞱��� `remember` 靘肽�撖澆�撖潸稲�� `Unresolved reference: remember` 蝻𤥁��仿���
- **颲枏�獢�祗閮�撘閧鍂撘�虜靽桀�**嚗帋耨憭滢� `ChatScreen.kt` 銝剖� `ChatInputBar` Composable �賣㺭蝻箏� `appLanguage` 蝑曉��𣬚凒�乩蝙�� `isEn` �䭾��� `Unresolved reference` 蝻𤥁��仿���
- **ChatScreen 憸����㺭�峕郊靽桀�**嚗帋耨憭滢� `ChatScreenPreview` 銝剔眏鈭擧𧊋隡惩� `apiConfigList` 銝𥪯蝙�其�摨笔��� `onApiConfigChange` �噼���撖潸稲���霂煾�霂胯��
- **Gson �典�����𠰴��其耨憭�**嚗帋耨憭滢� `MainActivity.kt`��ChatStorageManager.kt` 銝� `LlmClient.kt` 銝剖�撠� `com.google.gson` �躰秤撘閧鍂銝� `com.google.code.gson` 撖潸稲��之�Ｙ妖 unresolved reference 蝻𤥁��仿���
- **�讐� launch 撖澆�蝻箏仃�𠹺��典��𦯀�靽桀�**嚗𡁜銁 `MainActivity.kt` 銝剖紡�乩�蝻箏仃�� `kotlinx.coroutines.launch` 摨㮖誑�舀�撘�郊霂瑟�隞餃𦛚嚗�僎瘨�膄鈭� `setContent` 雿𦦵鍂�笔�憭帋��� `val scope` ���憯唳�嚗諹圾�喃�憯唳��脩�蝻𤥁��踺��
- **瘜𥕦�蝐餃��典紡靽桀�**嚗𡁜銁 `MainActivity.kt` �� `initialConfigs` �� remember 銵刻噢撘譍葉�曉����鈭� `<List<ApiConfig>>` 瘜𥕦�嚗峕��支�蝐餃��典紡銝滩雲��𥁒�踺��
- **ModelSelector ��㺭銝滚龪�滢耨憭�**嚗𡁻���� `ChatScreen.kt` ��� `ModelSelector` 憿嗅��嗅���㺭�𢠃�餉�雿橒�雿蹂��交𤣰 `selectedModelName`��apiConfigList`��onActiveConfigChange` 撟嗆覔�桃鍂�琿�蝵桃�餈墧𦻖�怠�餈𥡝�銝𧢲��𡑒”皜脫�嚗諹圾�喃��� `ChatScreen` 銝剛��其��寥���𥁒�踺��
- **SettingsScreen �滚�隞���𠰴�銵典紡�乩耨憭�**嚗𡁜��支� `SettingsScreen.kt` 撠暸�銝齿��滚���僎��之畾萄�雿嗘誨���瘨�膄鈭� `ThemeSettingsLayout` �� `SettingsScreenPreview` �滚�摰帋��仿�嚗㚁�撟嗉‘朣𣂷� `LazyColumn` �� `items` 撖澆�����急�鈭�挽蝵桃��Ｗ��函�霂烐𥁒�踺��
- **靘扯器�讐���𧒄�湧俈�硋��典��𤩺��行⏛撅誯�隡睃�**嚗𡁜銁 `MainScreen.kt` 銝剝膄鈭�銁 `onMenuClick` 撘訫� 800ms �孵稬�脫�憭吔�餈睃銁��憭硋�霈曇恣鈭��撅𤩺��罸�𤩺��行⏛撅��PointerInput Barrier嚗剹��砲撅���其儒颲寞�皛穃枂�函𤫇餈鞱��罸𡢿嚗ǑisAnimationRunning && targetValue == Open`嚗㗇遬敶Ｗ僎瘨�晶���厩�撅誩��孵稬嚗�蝠摨閖�蝏苷�餈𧼮稬�嗅�蝏剔��餉氜�亙��曉蔣�� Scrim �桃蔗銝𡃏�諹䌊�刻圻�� `close()` 蝻拙�����毺撩�瘀�敶餃�摰䂿緵鈭���颱�銝Ｗ仃��儒�誩�蝢擧��箇�蝏苷蔔雿㯄���
- **�冽��滩挽蝵桅△�墧遬銝𡒊�颲𤑳𠶖����Ｖ耨憭�**嚗帋耨憭滢� `SettingsScreen.kt` ��� `InlineEditNameField` 銝� Viewer嚗ǑText` 銝� `Icon`嚗匧��航◤�躰秤撋��餈� `isEditing` �斗鱏銝剔�撋�� bug嚗䔶蝙�嗉�憭�迤蝖桐�銝� `else` ��𣈲餈𥡝�皜脫�嚗�蝠摨閗圾�喃�撌脖�摮条��冽��滚銁霈曄蔭憿萄�蝷箇征�賜�蝻粹萅��
- **�啁征�賭�霂嗪�憭滚�撱粹���**嚗𡁜銁 `ChatScreen.kt` ��𢰧銝𡃏��滢��箔葉憓𧼮�鈭�鍂�瑟糓�血�閮�餈� (`hasUserSpoken`) ��辺隞嗅ế摰𠾼��蘨�匧��滢�霂苷葉��鉄�冽��煾���瘨���塚��滢��曄內�𨀣鰵撱箔�霂吲�脲��殷��啣�撱箔�霂苷�暺䁅恕撖寡砲�厰僼餈𥡝��鞱�嚗�蝠摨閖俈甇Ｖ�憸𤑳�餈𧼮稬�䭾��滚����銝���征�賭�霂萘�鈭支��𤤿�嚗䔶�雿踵鰵�冽��嘥��屸𢒰�游��𡁶�皜����
- **靘扯器�𤩺𤣰�墧��渡��滚�霂航圻�行⏛**嚗帋��碶� `MainScreen.kt` ���𤩺��行⏛撅誯��文�����典��行⏛��遬蝷箸辺隞嗥眏 `drawerState.isAnimationRunning && targetValue == Open` �嫣蛹�冽㟲銝� `drawerState.isAnimationRunning`嚗���箔�蝻拙��函𤫇�罸𡢿嚗劐��𡁶鍂������敶餃��餅鱏鈭�鍂�瑕銁�孵稬 Scrim �嗅��賢��函𤫇�罸𡢿餈䂿賒�孵稬憭㚚�撖潸稲�函𤫇�滚�銝剜鱏���㘾���𣬚𠶖����∠��毺� bug��
- **隡朞�銵��敹急㭘�𣳇膄蝖株恕鈭峕活撘寧�**嚗𡁜銁 `MainScreen.kt` ��� `SidebarContent` ���霂嗪★�𣳇膄�餉�銝剛挽霈∩�鈭峕活蝖株恕 `AlertDialog`����餃��文㦛��𧒄撘孵枂�瑕� Loyea 蝎曇稲憭批�閫鉝��葉�望�憭朞祗閮��芷����羓滯摮堒撩霅衣內��＆霈文㨃����孵稬蝖株恕�孵虾�𣳇膄嚗峕�憭扳�擃䀝�撖寡���瘥��摰寥��脰秤閫西��䜘��

### Added
- **憭朞祗閮�嚗�葉�望�嚗㕑䌊���銝𤾸��唳���**嚗𡁜��啣��亙��刻祗閮�擐㚚�厰★嚗�𣈲��葉����望�銝��桀𢆡���蝻嘥��ｇ�嚗䔶� `MainActivity` 霂餃� SharedPreferences �冽����伐�撟嗅銁 `MainScreen`��ChatScreen` �桀�躰祗/�鞟內霂�/颲枏��誩� `SettingsScreen` �冽䲮雿滨�摰𡄯��㯄�帋�憭朞祗閮��典��滚�撘𤩺凒�圈�朞楝��
- **憭𤥁�銝舘祗閮�鈭𣬚漣�滨蔭憿� (ThemeSettingsLayout)**嚗𡁜�撱箔��冽鰵���蝥折�蝵桅△嚗峕𤜯隞�������芦�𡁜笆霂脲���鍂�瑕虾�冽迨�閖�㗇綉�� Light/Dark/System 銝駁�嚗諹��賡�蝵桃鍂�瑟�瘜⊿��脖�摨𠉛鍂霂剛���
- **�芸�銋㗇�瘜⊿���**嚗𡁏�瘜⊿��脰挽蝵格𣈲���蝘滨移�渡�閫��憸�挽憸𡏭𠧧嚗�𨯫��瘝䠷�-Claude憌擧聢��緒�啗羲��-ChatGPT憌擧聢��凝�㗇�蝏踴���蝞�憭抵�嚗匧�蝟餌�暺䁅恕�滩𠧧嚗�僎�� `MessageItem` 餈𥡝�鈭��蝢𡒊�瘞娍部摨閗𠧧�峕�摮𡑒𠧧�冽�������
- **API 霈曄蔭銝𤾸之璅∪�璅⊥踎�拙�**嚗𡁜銁鈭𣬚漣 API �滨蔭憿菟𢒰銝剜鰵憓硺� **Kimi (Moonshot)��wen (��䔮)��iniMax��iMo** �滚𦛚����𥪜𢆡憸�挽璅⊥踎��
- **�冽��之璅∪��刻� Chips 蝏�**嚗𡁜銁霈曄蔭憿菜芋�见�蝘啗��交�銝𧢲䲮嚗���牐��寞旿���㗇��∪��冽��葡�梶�**�刻�璅∪�敹急㭘�㗇𥋘 Chips**嚗��憒� Kimi �� `moonshot-v1-8k`嚗���桃� `qwen-turbo`嚗剹��鍂�瑞��餃朖�航䌊�典‵��芋�见�蝘堆��滚縧�见𢆡颲枏�����僐��

### Changed
- **憿園��䠷�㗇芋�钅�㗇𥋘�嗅��拍�撅�葉靽桀�**嚗𡁜銁 `ChatScreen.kt` 銝剖��乩� **`CenterAlignedTopAppBar`** �蹂誨��𧋦�� `TopAppBar`嚗��蝢𡡞�摰帋�憭扳芋�钅�㗇𥋘�典銁撅誩�撌血𢰧��偌撟喟����銝准��
- **��㺭隡𣳇�鍦��煾�朞楝�㯄��**嚗�
  - �典������ `apiConfig` �嗆��� `MainActivity` �朞� `MainScreen` �鞟漣�睲��譍��� `ChatScreen` �� `ModelSelector` 憿嗅��嗅�嚗���圈�霈斗芋�讠��芸𢆡蝏穃�皜脫���
  - �� `ChatScreen` ��芋�钅�㗇𥋘銝𧢲��𨅯�銝剖��Ｘ芋�𧢲𧒄嚗屸�朞� `onApiConfigChange` �噼��漤�蝏� `MainActivity` �冽��凒�啣�撅��嗆��僎�芸𢆡����碶�摮䁅秐 `SharedPreferences`嚗𥕦��塚�銝餉挽蝵桅△ of API 餈墧𦻖�∠𤌍�舀�憸睃��嗅��䭾迨璅∪��䀹凒嚗峕��帋��港葵�𨅯��烐㺭�桀�甇乒�嗪�朞楝��
- **�冽��滩挽蝵格綉隞嗆�蝞�蝢𤾸�銝𤾸�銵冽㟲��**嚗�
  - 蝘駁膄鈭���厩�摨𧼮之 `USER PROFILE` �祉��∠�嚗��銝�蝥找蜓霈曄蔭憿菜４��僎敶埝㟲銝箔舅蝏������啁��𡑒”嚗䫤ACCOUNT PROFILE`嚗���� 32.dp 餈瑚��冽��仍�誩�銵���笔𧑐���蝻𤥁�獢��銝� `SYSTEM SETTINGS`嚗㇁PI & Model 餈墧𦻖�亙藁銝� Theme & Language 霈曄蔭嚗剹��
  - 撖寧鍂�瑕�蝻𤥁��找辣擃睃漲�� Padding 餈𥡝�鈭��銝�敺株�嚗䔶蝙銋衤�銝𧢲䲮�∠𤌍擃睃漲摰��銝��湛��舀�憭梁��𡝗��噼膠�桃��喳��圈�暺䀝�摮矋��港�霈曄蔭憿菟𢒰閫��雿㯄����閫�㟲蝎曇稲嚗��皛� Claude 蝢𤾸郎��

## [Unreleased] - 2026-06-09

### Fixed
- **撖澆�撘�虜靽桀�**嚗朞‘朣𣂷� `SettingsScreen.kt` 銝剝�瞍讐� `androidx.compose.ui.tooling.preview.Preview` 撖澆�嚗�蝠摨閙��支� `@Preview` �� `Unresolved reference` 蝻𤥁��仿���
- **憸�����靽桀�**嚗帋耨憭滢� `MainScreen.kt` 銝� `SettingsScreen.kt` 銝剖�銝� Composable 蝑曉��齿���撩撠� `userName` 銝� `apiConfig` 隡惩�撖潸稲�� Preview 蝻𤥁��仿���
- **Gradle �𨅯�隡睃�**嚗𡁜� Gradle 銝贝蝸�暹𦻖�踵揢銝箏𤙴���霈臭��𨅯�皞琜�敶餃�閫��鈭� Gradle distribution 銝贝蝸撖潸稲�� `Read timed out` 餈墧𦻖頞�𧒄�桅���
- **�嗆����䀝�蝐餃��典紡靽桀�**嚗𡁜銁 `MainActivity.kt` 銝剖� `by remember` 憪娍��箏��孵�銝箸遬撘讐� `val state.value` 霈輸䔮嚗�蝠摨閗圾�喃��� Kotlin 銝� Compose 蝻𤥁��雴辣����䁅圾�鞉郁銋匧��𤑳� `Unexpected type specification` 蝻𤥁��仿���
- **霂剜��躰秤靽格迤**嚗帋耨憭滢� `MainActivity.kt` 銝剛��� `super.onCreate(savedInstanceState?)` �嗉秤�䠷䔮�瑞�霂剜� Bug嚗�蝠摨閗圾�喃� `Unexpected type specification` ���霂烐𥁒�踺��
- **��㺭摰帋�霂剜�靽桀�**嚗帋耨憭滢� `ChatScreen.kt` 銝� `ChatInputBar` �� `onValueChange` ��㺭憯唳�銝哨�撠���� `:` 霂臬�銝箇��� `=` 撖潸稲�� `Expecting comma or ')'` 蝻𤥁��仿���
- **Markdown 霂剜�皞Ｗ枂靽桀�**嚗𡁶宏�支� `Theme.kt` ��辣�怠偏�曹�憭扳芋�见紡�箸��嗵��滚��� ` ``` `嚗�蝠摨閗圾�喃� `Expecting a top level declaration` ���霂烐𥁒�踺��
- **�𦯀�撖澆�皜��**嚗𡁶宏�支� `ChatScreen.kt` 銝剖�雿嗵� `import com.loyea.R` 撖澆�嚗屸��滢��券★�桃��� `R` 蝐餃�撖潸稲���敹��霂剜��亦滯��

### Added
- **API �滨蔭銝舘䌊摰帋��冽��齿�銋��蝞∠� (韏啣��笔�銝𡁜𦛚�餉�)**嚗�
  - **�𡝗��餃�憿�**嚗𡁜� `MainActivity` 撖潸⏛韏瑞��滩挽銝� `main`嚗𣬚宏�支�甈Ｚ��餃�憿菟𢒰嚗���啣��典��舐�撘��渲噢�𠰴予撅譌��
  - **�芸�銋厩鍂�瑕�**嚗𡁜銁 `SettingsScreen.kt` 銝剛挽霈∩��冽��滩��亙㨃����𥪜𢆡�湔鰵靘扯器�𧶏�Sidebar嚗厩��冽�憭游�銝𡒊鍂�瑕�撅閧內��
  - **憭扳芋�� API 蝞∠��Ｘ踎**嚗𡁜��啣��睲� API �滨蔭�∠�嚗峕𣈲��蜓瘚�之璅∪����霈暹芋�選�Anthropic��penAI��eepSeek��ustom嚗厩�銝𧢲��㗇𥋘��PI Base URL �芸𢆡�寥������芋撘� API Key ��𧋦獢�誑�� Model 颲枏���
  - **SharedPreferences �砍𧑐�����**嚗𡁜銁 `MainActivity.kt` 蝥批���� `SharedPreferences`嚗�笆蝟餌�銝駁���鍂�瑕�隞亙����� API �亙藁��㺭�𣂷�瘞訾��扳𧋦�啁��䀝�摮矋�摨𠉛鍂�滚鍳�擧㺭�桐�銝Ｗ仃��
- **MCP 銝� Thinking 鈭支��芷���隡睃� (���銝舘䌊�冽��䭾㦤��)**嚗�
  - **McpCallItem �芷����睃�**嚗𡁜��� `hasUserInteracted` �餉�嚗�極�瑕銁餈鞱�嚗㇌UNNING嚗㗇𧒄暺䁅恕撅訫�隞乩�霂���嗅𢆡����頣��扯��𣂼�嚗𠄎UCCESS嚗匧��芸𢆡�嗉絲隞乩�����舀��湔����銝𥪯�敶梶鍂�瑟��典僕憸���餃�嚗諹砲�∠����敶枏��嗆���蝟餌��餉�銝滚��亦恣�睃���
  - **ThinkingProcessLayout ����箏�**嚗𡁏�撅� `Message` �嗆���瘛餃� `hasUserToggledThoughts`嚗峕�肽����笔�憒���冽�瘝⊥��滢�餈�㨃����芸𢆡�睃�嚗𥡝𥅾�冽�銝餃𢆡�滢�餈���䠷�摰𡁶鍂�琿�匧��嗆���擃睃漲撠𢠃��冽���蜓�刻�銝綽�憭批�摨行���犖�箔漱鈭垍����撉䎚��
- **隞���𡑒祗瘜閖�鈭桀��� (Syntax Highlighting)**嚗𡁜銁 `MarkdownText.kt` 銝剜��坔��唬���蝠�譌���蝚砌��孵�憭找�韏𣇉� Kotlin/Java 甇��擃䀝漁閫���具��𣈲��笆�喲睸摮梹�璈辷���釣閫��暺����㺭摮梹��嘅����蝚虫葡摮烾𢒰�𧶏�蝏選����/憭朞�瘜券�嚗��雿梶�嚗䔶��瑕���擃条漣閬��撅讛𤪖�餉�嚗厩�蝎曇稲�脣蔗皜脫�嚗�之憭批撩�碶�撖寡���誨��� 1:1 憭滚�蝢擧���
- **MCP 銝� Thinking �函��暸��� (擃䀝遛�笔𢆡�餅�蝔�)**嚗�
  - �𥕦遣鈭� `ThinkingAndMcpComponents.kt`嚗諹挽霈∩�擃㗛��潛�撌亙�靚�鍂銝擧楛摨行�肽���隞嗚��
  - **McpCallItem**嚗𡁏遬蝷箏極�瑞𠶖���餈鞱�銝剝蝙頧桀����頧砍𢆡����𣂼�銝箇遛�橘�憭梯揖銝箇滯�孵噡嚗㚁�銝娍𣈲����餃像皛穃�撘�撅閧內��㺭銝舘�銵𣬚��栶��
  - **ThinkingProcessLayout**嚗𡁜�蝢𤾸��� Claude �肽���嚗�椰靘扳��惩�銝㕑�撣行� 0 �� 90 摨衣��贝蓮餈�腹�函𤫇嚗���冽�肽���摮埈𣈲���摨血撕�找撓蝻抬�animateContentSize嚗剹��
  - **隞輻��園𡢿蝥輸�餉�**嚗𡁜銁�煾����臬�嚗淾I 隡𡁏�銵���嗆挾�函�銝𤾸極�瑁�摨行�嚗��𡏭�皞𣂼�頧� -> 頝� read_file 朣輯蔭�贝蓮 -> 撘��� Thinking 瘛勗漲�函� -> 頝� web_search 朣輯蔭�贝蓮 -> 瘚���枏�颲枏枂甇���嘅�嚗峕�靘𥟇��喟��笔�鈭支�雿㯄���
  - **Message 璅∪��澆捆��漣**嚗𡁜銁 `Message.kt` 銝剜溶�牐��拙�摮埈挾嚗Ǒthoughts`��mcpCalls` 蝑㚁�嚗峕𣈲��唂隞�� 100% �穃��澆捆��
- **Claude 摰䀹䲮憌擧聢�函𤫇銝𤾸𢆡��漱鈭�**嚗�
  - **瘚���枏��箸���**嚗帋耨�嫣� AI 璅⊥��𧼮����蝔钅�餉�嚗峕㺿�望�撘誯�𣂼�/摮㛖泵餈賢�嚗䔶蝙瘨���� `MarkdownText` 銝剝◇皛烐葡�瓐��
  - **瘨��瘞娍部銝𦠜筑瘛∪�**嚗𡁜⏚�� `Animatable` �� `graphicsLayer` �其�嚗䔶蛹�𠰴予瘨���∠��刻蝸�交𧒄�𣂷�頧餌���像皛𤑳��芯��䔶�嚗㇅astOutSlowInEasing嚗㗇楚�交�蝘餅��栶��
  - **颲枏��誯�摨血凝撘寞�批𢆡��**嚗𡁜笆�𠰴予颲枏�獢�捆�冽溶�牐� `animateContentSize`嚗䔶蝙敺埈揢銵屸�摨行㺿�䀹𧒄隡湔�撘寞�抒��脰�皜∴�瘨�膄�毺′�嗉���
  - **�厰僼�冽����Ｗ𢆡��**嚗𡁜�摨閖�颲枏�銝箇征�嗥�暻血�憌𤾸㦛����㗇�摮埈𧒄����������殷��朞� `AnimatedContent` �� `animateColorAsState` 蝏穃�嚗���唬�瘚���� Scale Fade �嗆��蓮�Ｗ��峕艶�脫��塩��
  - **甈Ｚ�憿萇漣�磰蝸�交���**嚗𡁜銁 `WelcomeScreen.kt` 銝剛挽霈∩�����𣬚蒈敶閙��桃��躰氜撘𤩺楚�亙𢆡�鳴�憭扳�憸㗛���筑�堆�200ms �擧��桃��亦賒皛穃�嚗峕��瑁䰾�臬鐤�豢���
- **憿寧𤌍撉冽沲�嘥���**嚗�
  - �𥕦遣鈭�★�格覔�桀��滨蔭嚗䫤settings.gradle.kts`��build.gradle.kts`��gradle.properties`��gradle-wrapper.properties`��
  - �𥕦遣鈭� `app` 璅∪��滨蔭嚗䫤app/build.gradle.kts`��AndroidManifest.xml` 隞亙�暺䁅恕�ａ��暹� `ic_launcher.xml` �� `strings.xml`��
- **Claude 閫��憌擧聢銝駁�銝擧���**嚗�
  - 隞擧𧋦�啁頂蝏毺𤌍敶閙���鼧韐嘥僎�滚𦶢�滢� 4 銝� Anthropic 摰䀹䲮�臬�摮𦯀���辣嚗Ǒanthropic_sans_romans.ttf`��anthropic_sans_italics.ttf`��anthropic_serif_romans.ttf`��anthropic_serif_italics.ttf`嚗㕑秐憿寧𤌍 `app/src/main/res/font/` �桀���
  - 摰帋�鈭� `Color.kt`嚗��暻衣蒾鈭株𠧧�峕艶��楛銴鞱𠧧�𡑒𠧧�峕艶隞亙�撖孵�����砌�颲�𨭌�脣�潘���
  - �湔鰵鈭� `Type.kt`嚗�ㄟ�� `AnthropicSans` �� `AnthropicSerif` 摰䀹䲮摮𦯀��𧶏��券𢒰�踵揢摨𠉛鍂�屸𢒰�𠰴笆霂脲��祉��垍�摮𦯀�嚗��蝢𤾸��� 1:1 摰䀹䲮�垍�韐冽���
  - 摰帋�鈭� `Theme.kt`嚗��靘� `ClaudeTheme`嚗�𢆡����亦頂蝏���脖蜓憸睃僎霈曄蔭�嗆�����紡�芣�嚗剹��
- **Markdown 蝎曇稲皜脫�**嚗�
  - �𥕦遣鈭� `MarkdownText.kt`嚗諹�憭蠘圾�� Markdown 銝剔��㛖漣隞��嚗���怠蒂�争�𦒄opy�脲��桀�霂剛��������脖誨��捆�剁������誨���蝑匧捐摮堒蒂�峕艶嚗匧�撣貉���𧋦��
- **�𠰴予銝餌��ｇ�ChatScreen嚗�**嚗�
  - 憿園� 1:1 憭滚��嗅��𧢲芋�钅�㗇𥋘銝𧢲��𨅯�嚗�𣈲����� "Claude 3.5 Sonnet" 蝑㗇芋�页���
  - �舀��煾����臬��芸𢆡皛𡁜𢆡�啣��剁�撟嗅蒂�匧�蝥批辣餈�芋�� AI ���肽��緾��𢆡�鳴�ThinkingIndicator嚗剹��
  - �𠰴予瘞娍部嚗𡁶鍂�瑟�瘜∪蒂銝滚笆蝘啣�閫𡜐�AI 瘞娍部�湔𦻖�兩�𦦵爾撘罱�嘥��脖��垍�嚗䔶��孵蒂�匧�撌抒��其��∴�憭滚�����喋����啁��僐���/頦抬���
  - 摨閖����颲枏��𧶏�撌虫儒�惩噡��辣嚗諹��乩蛹蝛箸𧒄�喃儒�曄內暻血�憌𤾸㦛���颲枏�����嗅𢆡����Ｖ蛹 Primary �脩��睲�蝞剖仍�煾����柴��
- **靘扯器�𤩺��箄��𤏪�Sidebar Drawer嚗�**嚗�
  - 憿園��曄內�冽�靽⊥�嚗䔶葉�冽��園𡢿嚗㇍oday, Yesterday, Previous 7 Days嚗匧�蝏��蝷箏��脰�憭抵扇敶𤏪�摨閖�霈曇恣鈭���𤏸捶�毺� "Upgrade to Claude Pro" �∠��� "Settings" �亙藁��
- **甈Ｚ�/�餃�憿蛛�WelcomeScreen嚗�**嚗�
  - ���撅�葉�� "Claude" Serif 憭扳�憸矋��𣂷� "Continue with Google" �� "Continue with Email" �����像�𡝗��殷�撟園�撣血��典�霈桀ㄟ�汿��
- **霈曄蔭憿蛛�SettingsScreen嚗�**嚗�
  - ��鉄�冽�韏���∠������箇蒈敶閙��殷�撟嗆𣈲���朞� AlertDialog 撘寧���揢 Theme嚗�漁�脯����脯����讐頂蝏����
- **憿菟𢒰撖潸⏛銝脰�嚗㇈ainActivity嚗�**嚗�
  - 撘訫� Navigation �批��剁�蝞∠� Welcome -> MainChat -> Settings 銋钅𡢿��楝�梯歲頧研��
  - �典���� `currentTheme` �嗆����㯄�帋�霈曄蔭憿萎蜓憸䀹凒�孵笆�港葵 App 閫������嗆綉�嗚��
