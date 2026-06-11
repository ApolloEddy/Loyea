# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-06-11

### Added (新增)
- **角色卡二次编辑功能**：在 `TavernScreen.kt` 中为每张角色卡新增编辑按钮（铅笔图标），点击后打开全屏 `EditPersonaDialog` 弹窗，预填充已有角色数据（名称、简介、性格、场景、首句欢迎词、系统核心设定、少样本范例、头像、背景壁纸、兜底色）。保存时通过 `data class copy()` 保留原始 ID 和内置标记，更新后自动回写到角色列表持久化。
- **LLM 提示词显式用户称呼注入**：在 `PromptAssembler.kt` 的 `assembleSystemPrompt` 中，于角色引导语之后新增 `[User Info]` 段落，显式告知 LLM 用户的称呼（如 `The user's name is "xxx". Address them by this name naturally in conversation.`）。此前仅依赖 `{{user}}` 宏替换，若角色卡未使用该宏则 LLM 无从得知用户名。
- **侧边栏用户名直接编辑功能**：在 `MainScreen.kt` 侧边栏顶部用户信息栏加入水波纹整行点击交互与编辑图标，点击可拉起精致的 `AlertDialog` 弹窗修改并保存用户名，并通过 ViewModel 实现即时持久化，打通“去设置页冗余编辑，保留侧栏唯一编辑入口”的闭环体验。
- **Markdown 表格高颜值渲染支持**：在 `MarkdownText.kt` 的轻量 Markdown 引擎中扩展手写了流式表格（`TableBlock`）解析状态机。配套实现了 `TableLayout` 渲染组件，支持斑马线底色、多列分割线及单元格行内 Markdown 样式；引入了智能自适应宽度机制（$\le 3$ 列充满气泡均分，$> 3$ 列锁定 `120.dp` 宽并支持横向顺滑滚动），解决了对话中大模型输出表格时无法解析乱成一团的排版痛点。
- **Markdown 表格测量错乱与闪退 Bug 修复**：解决了在 $\le 3$ 列时使用 `weight` 布局却被外层 `horizontalScroll` 提供无限宽约束（`Constraints.Infinity`）导致 Compose 无法计算单元格空间分配、进而产生表格折叠或渲染异常的缺陷。重构为仅在列数 $> 3$ 时激活横向滚动容器，彻底防范了布局测量死锁。
- **外观设置页文字底部截断缺陷修复**：在 `SettingsScreen.kt` 中为“外观与语言”设置子页（`ThemeSettingsLayout`）的根 `Column` 挂载了 `verticalScroll(rememberScrollState())` 并增加了底部 `24.dp` 呼吸间距，解决了低分辨率设备或系统导航栏遮挡导致“英文”选项行底部文字被截断的缺陷；同时对全局 Screen 的滚动能力进行了拉网式排查，保障了全局 UI 控件的安全度。

### Changed (变更)
- **Thinking 展开折叠策略优化**：优化了聊天页面 Thinking 推理链的展示逻辑，在开启新一轮会话时自动折叠历史 AI 消息的 Thinking 过程；最新回复在思考时默认展开，在生成完成（`Done` 事件）后自动收缩折叠；若用户在输出期间手动点击了折叠，则会记录干预状态并尊重用户选择，不再强行重新摊开。
- **Thinking 计时精准化修复**：修正了思考时间的统计逻辑，将计时的截止点由“整个流接收完毕”修正为“开始吐出正式回答正文的瞬间”（即首个 Content 帧到达时锁定计时），彻底解决了生成正文期间时间差不断累加导致思考耗时虚高的缺陷。
- **精简设置页用户资料区块**：移除 `SettingsScreen.kt` 中冗余的"个人资料"卡片（含头像和 `InlineEditNameField` 行内编辑框），用户称呼仅保留在侧栏顶部入口统一编辑，避免多处入口造成体验混乱。
- **移除侧栏硬编码邮箱占位符**：删除 `MainScreen.kt` 侧栏用户名下方的 `"loyea@example.com"` 假邮箱文字。本应用主打离线使用，无需登录功能，该占位符无实际意义。

## [Unreleased] - 2026-06-10

- **会话级系统时间提示词选择 (物理感知)**：在 `ChatStorageManager.kt` 的 `ChatSession` 中增加了 `useSystemTime: Boolean` 配置，并在 `PromptAssembler.kt` 中支持将格式化后的真实时间注入系统 Prompt。
- **物理感知交互与顶栏解耦重构**：完全剥离了 `ChatScreen.kt` 顶部模型选择胶囊 DropdownMenu 中的“物理感知”开关，彻底解决真机（如 OPPO Find X6, ColorOS 16 等）由于状态栏通知中心与全局手势热区冲突，导致下拉菜单根本无法点击触发的交互痛点。
- **高档卡片式侧边栏底栏设计**：在侧边栏 `SidebarContent` 底部区域，引入了整合了“物理感知（时间）”、“角色酒馆”、“系统设置”的统一的控制面板卡片（Control Panel Card）。通过双行微观版式（带有各功能的说明性副标题）、统一的高档主色调 Icon 指引、指向性 Chevron 右箭头、以及精细缩放的 Switch 开关，实现了视觉美感的极大飞跃。
- **整行响应与防误触点击**：为“物理感知”行提供全行点击事件消费支持（`.clickable`），即便点击文字也可安全且流畅地切换“系统时间感知”，解决真机上小尺寸 Switch 控件难以点中的操作缺陷。
- **DeepSeek 候选模型升级与平滑迁移**：将 DeepSeek 的候选模型全面升级更新为 `deepseek-v4-pro` 与 `deepseek-v4-flash`。
- **历史模型配置自动清洗**：在 `ChatViewModel` 中添加了 API 配置自动迁移逻辑，在加载已有配置时，自动将历史废弃的 `deepseek-chat` 升级为 `deepseek-v4-pro` 并回写至本地 SharedPreferences，无需用户手动维护，保证平滑过渡。
- **设置与模板参数更新**：在 `SettingsScreen.kt` 中同步更新了 API 配置模板、推荐模型预设列表、模型名称输入框占位符（由 `deepseek-chat` 改为 `deepseek-v4-pro`）及预览数据集中的模型预设，保持全局配置的一致性。
- **真实大模型 SSE 流式输出接入**：重构 `LlmClient.kt` 启用 Server-Sent Events 流式通道，实时按行流解析 `data:` 报文，完全废弃了原本的同步阻塞响应与人工延迟打字机动画；完美提取并展示 DeepSeek 推理链（`reasoning_content`）及内嵌的 `<think>` 标签内容，达到毫秒级交互体验。
- **联网搜索与深度思考开关支持**：在新建或编辑 API 连接表单中引入“联网搜索”与“深度思考”（默认开启）两个物理 Switch 开关，数据挂载进 `ApiConfig` 实体且自动持久化；在通信层依据开关自动注入参数，且针对 DeepSeek 会话智能路由并热切换 `deepseek-chat` 与 `deepseek-reasoner` 推理模型。
- **基于 ChatViewModel 的 MVVM 状态解耦重构**：新建 `ChatViewModel.kt`，集中式接管用户名、主题、语言、API Config 队列、会话列表及当前消息等核心状态，大模型异步接收闭环交由 `viewModelScope` 处理，彻底修复了 Activity 在屏幕旋转、配置重载后内存泄漏与状态全丢的 Bug。
- **高颜值轻量级 Markdown 渲染器优化**：升级 `MarkdownText.kt`，实现行级扫描机制，扩展支持对 **多级标题 (`#`)**、**有序与无序列表 (`-`/`1.`)**、**引用块 (`>`)** 和 **分割线 (`---`)** 的高保真解析及 Compose 精致排版，修复了 `Divider` 的过期 API 调用警告。
- **彻底清除 Git 历史硬编码 API Key 快照**：解除 `MainActivity.kt` 中的 DeepSeek API Key 硬编码限制，对默认参数改写为 `""` 以保护安全；配合专用的 python 脚本，通过 `git filter-branch` 彻底清洗了本地历史提交中的一切快照指纹。
- **自动化 Gradle 编译与签名 APK 发布**：补充集成了 `gradlew.bat` 与 wrapper，并在 Gradle 中添加 release 签名密钥，在 Windows 环境下一键成功编译输出具备正式签名的首个 0.1 版本的 Release APK。
- **SillyTavern 酒馆 V2 规格标准 PNG 角色卡隐写导出**：在 `TavernScreen.kt` 中设计并实现了高保真 PNG 隐写角色卡导出方法。当角色未设置头像时，系统通过 Canvas 在内存中自动渲染出带有角色大名、简介和 "Loyea Persona Card" 微光小标的莫兰迪色渐变 PNG 卡基；当存在头像时，自动调用系统位图引擎转码为 PNG。随后通过流式 Chunk 扫描定位在 IHDR 块后安全注入 Base64 后的 V2 标准 JSON 以及重新计算 CRC32，完美打通第三方酒馆应用导入兼容。
- **SillyTavern 酒馆 V2 规格标准 JSON 配置文件导出**：支持通过原地多格式下拉菜单，一键将角色卡各项属性转换为 SillyTavern 官方 V2 Schema 格式并生成 JSON 文件，利用系统 Action_Send 与安全 FileProvider 导出分享。
- **聊天界面低透明度淡雅背景壁纸渲染**：在 `ChatScreen.kt` 中引入了 `rememberBackgroundPainter`，利用 `Modifier.paint` 以 `alpha = 0.12f` 的超低不透明度和 `ContentScale.Crop` 的模式在聊天流消息主容器背景层渲染角色绑定的本地壁纸图片，不仅完美呼应了人设专属主题，且确保文字对比度优秀，丝丝毫不干扰前台文字阅读。
- **安全跨应用 FileProvider 分享**：在缓存目录下的 `exports/` 分配临时安全共享区域，并配合 Intent Flag 对分享的 PNG 与 JSON 进行临时授权读取，杜绝了 Android 7.0+ 系统上的 FileUriExposedException。
- **人设拼接与占位符宏渲染引擎 (PromptAssembler)**：全新创建了 `PromptAssembler.kt`，实现了将核心设定、性格、情景、少样本对话范例进行标准化酒馆格式拼接的引擎，并支持 `{{char}}` 和 `{{user}}` 等标签的宏替换，实现真正的角色扮演沉浸感。
- **人格自定义表单扩充**：在 `TavernScreen.kt` 的自定义表单弹窗中补充了“性格词汇描述”、“对话场景设定”和“少样本对话范例”三个多行输入域，支持完备的多维度人格信息本地持久化与折叠页中高保真展示。
- **兼容酒馆 (SillyTavern) 角色卡隐写与解析系统**：已全新创建了 `TavernCardParser.kt`，实现轻量流式的 PNG `tEXt` 块扫描与 Base64 隐写提取解析机制，自动兼容 V1 与 V2 `data` 节点人设规范。
- **5 款高品质系统预置人格**：内置了理性助理 Loyea、活泼傲娇猫娘小铃、废土毒舌酒保戴斯、豪放宋代文豪苏东坡以及代码审查导师 Linus 等 5 款个性鲜明、打招呼语与提示词打磨精细的初始角色。
- **符合 Claude 美学的角色选择抽屉 (SelectPersonaSheet)**：新建会话时，不再直接创建空对话，而是自底向上拉起基于 Compose `ModalBottomSheet` 的雅致角色卡片挑选抽屉。选定后，为新会话永久锁定绑定该角色卡，并在消息流头部自动发出一声该角色专属的 `firstMessage` 欢迎语。
- **全功能角色酒馆管理中心 (TavernScreen)**：全新创建了 `TavernScreen.kt`。用户可通过侧边栏底部新增的“角色酒馆”按钮直达。支持：
  - **自定义创建**：包含头像主题色调选择、名称、简介、系统 System Prompt、打招呼欢迎词的完备创建 Dialog 表单。
  - **PNG 与 JSON 文件选择导入**：使用 Android 系统 GetContent 文件选择器选择 PNG 角色卡（提取其中隐写数据，并自动将高清立绘原图复制到应用私有目录 `context.filesDir/avatars` 中持久化为头像）或导入 JSON 配置。
  - **JSON 快捷分享导出**：基于 Action_Send 文本分享 Intent，一键将角色数据序列化导出分享，实现与其它平台和设备的完美流通。
  - **卡片删除与头像缓存回收**：用户可随时移除自定义卡片，删除时会自动清理其占用的本地头像图片缓存，保持应用轻量。
- **人设与大模型驱动全面解耦**：重构了 `LlmClient` 与 `ChatScreen` 的对话连线层。在向远程 LLM 发送聊天包时，自动把绑定角色的 `systemPrompt` 作为 `system` 角色组装到 payload 消息队列 of 第 0 位（即人设 System 提示词）。使得聊天过程中，用户可以在顶部任意热切换底层的大模型驱动配置（如把猫娘的会话由 Deepseek 切换成 Claude 3.5 驱动），而猫娘人设和记忆不受影响。
- **顶栏胶囊双行级联复合选择器**：将原本 of ModelSelector 升级为全新的高颜值胶囊，支持左侧显示角色圆形头像（有本地头像加载与哈希底色首字母兜底）、角色姓名大字标题、底层模型小字副标题以及下拉小箭头，在兼顾解耦与角色品牌代言的同时实现完美的视觉交互。
- **Gson 序列化依赖**：在 `app/build.gradle.kts` 中引入了 `com.google.code.gson:gson:2.10.1` 依赖，以支持聊天数据本地持久化。
- **本地会话及消息存储管理器 (ChatStorageManager)**：全新创建了 `ChatStorageManager.kt`，利用 Android 应用私有目录（`context.filesDir`）以 JSON 格式存储会话列表元数据 (`sessions_metadata.json`) 以及各独立会话的消息历史 (`session_{id}.json`)。
- **多会话隔离与动态切换**：在 `MainActivity` 层级重构成单数据源管理机制，在切换会话时精确动态读取并显示对应历史，彻底隔绝不同会话间的数据。在全部删除会话后能自动生成新的默认会话，提供了高容错边界逻辑。
- **首条用户消息自动生成会话标题**：新创建的会话当发送第一条用户消息时，系统会自动提取该消息的前 15 个字作为该会话的标题并同步持久化到本地，优化了标题生成体验。
- **侧边栏行内快捷删除会话**：在侧边栏 (`SidebarContent`) 的历史会话项中增加了删除按钮，点击即可直接删除该会话及其对应的本地 JSON 文件，并自动重新对准可用会话，提高了会话生命周期管理能力。
- **侧边栏历史会话时间动态分组**：基于会话的最后活动时间（`lastActiveTime`），实现了“今天”、“昨天”、“前 7 天”、“更早”的智能动态分类渲染。
- **网络错误消息字段**：在 `Message.kt` 中为 `Message` 实体类新增了 `isError: Boolean` 属性（默认值为 `false`），以精确感知和存储对话过程中的连接及配置错误。
 
### Removed
- **升级 Pro 广告移出**：从侧边栏（`SidebarContent`）中彻底移除了黄金质感的 "Upgrade to Claude Pro" 广告卡片，移除了对应的 `onUpgradeClick` 事件参数及 Toast 提示逻辑，净化了侧边栏的界面视觉，提升用户体验。
 
### Changed
- **“角色酒馆”重命名为“人格”**：将所有的 UI、侧栏底部导航选项、人格卡管理中心的顶栏标题、PNG 导入的引导语以及内部路由注释等话术全局重命名为“人格 (Personas)”。
- **新会话欢迎语占位符热渲染**：在 `MainActivity` 开启新会话时，利用 `PromptAssembler.formatMessageContent` 自动把欢迎语中的 `{{user}}` 等标签渲染替换为用户的实际名称。
- **发送端拼接参数层打通**：将 `userName` 透传打通至 `ChatScreen` 的网络发送链路，调用 LLM 接口时传入 `PromptAssembler` 精心拼装并替换后的结构化系统 Prompt。
- **全面重命名替换为 Loyea 项目名**：
  - 将所有用户 UI 界面中对 "Claude" 助理和应用的提及全面替换为 "Loyea"，包括 Chat 界面的默认欢迎语、新建会话初始语、输入框占位符（"Talk to Loyea"/"与 Loyea 对话"）、配置页的主题风格描述（"Loyea Warm Amber"）。
  - 将内部代码逻辑（类名、变量名、字体定义名）中的 `Claude` 前缀全面重命名为 `Loyea`（如 `ClaudeTheme` 更改为 `LoyeaTheme`，`ClaudeTypography` 更改为 `LoyeaTypography`，以及 `ClaudeLightBg`、`ClaudeDarkBg` 等系列颜色配置重命名为 `LoyeaLightBg`、`LoyeaDarkBg` 等）。
- **真实大模型网络通信挂载**：在 `ChatScreen.kt` 的 `onSend` 发送消息逻辑中，移除写死的 MCP 多阶段仿真动画，挂接真实的 `LlmClient.sendChatCompletion(...)` 异步请求。现在，在等待期间正常展现全局加载闪烁指示器，接收响应后计算精确的 API 思考时间并赋给 `thoughtDurationSeconds`，然后通过打字机逐字输出。
- **自定义警告错误气泡渲染**：重构了 `MessageItem` 针对 AI 消息的处理逻辑。当消息状态为 `isError = true` 时，AI 回答将不采用通用 Markdown + 动作条排版，而是直接渲染为具有圆角淡红背景（`Color(0xFFFDE8E8)`）、淡红细线边框（`Color(0xFFF8B4B4)`）、警告深红文本（`Color(0xFFE02424)`）和 `Icons.Default.Error` 图标指示的警告卡片，同时剥离了无意义的动作条（复制、发音等），提升交互质量与体验。
 
### Fixed
- **修复国内定制系统顶栏居中组件手势阻挡/无法点击 Bug**：在 [ChatScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatScreen.kt) 顶栏右侧新增了 `MoreVert`（三个点）的“会话配置”动作按钮及 DropdownMenu 下拉菜单，提供“物理感知 (时间)”开关及备用模型列表切换。这彻底解决了国内定制手机系统（如 ColorOS、MIUI 等）因透明状态栏手势拦截热区刚好覆盖屏幕顶部水平正中心区域，导致处于 title 槽位的 `ModelSelector` 居中胶囊的点击事件被系统截断而怎么点都打不开的真机兼容性 Bug。
- **修复 JVM 数据类 copy 方法 null 指针崩溃**：在 [ChatStorageManager.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/chat/ChatStorageManager.kt) 的 `loadSessionList()` 中设计了**防御自愈式数据清洗构造函数**。当读取 JSON 缓存时，凡是检测到旧版本文件缺失的属性（如 `characterId`, `useSystemTime`），均在内存中安全赋予保底默认值并重新通过 Kotlin 的构造器实例化对象，根绝了因 Gson 直接分配未初始化内存而在执行 JVM 数据类 `copy()` 参数非空校验时触发 `NullPointerException` 引发的闪退崩溃。
- **修复 Gson JsonNull 推理正文截断 Bug**：修复了在 `LlmClient.kt` 中从 Gson 元素中获取字符串时因 `JsonNull` 造成的解析异常，解决了开启“深度思考”后大模型只输出思考链而丢失正文回复的 Bug。
- **优化 API 配置卡片 UI 挤压变形**：在 `SettingsScreen.kt` 中，将已保存的连接卡片属性 `Row` 设置为可横向滑动的容器（`horizontalScroll`），并限制每个 `BadgeLabel` 单行显示（`maxLines = 1`），完美解决了因标签超长挤压导致整个卡片纵向拉伸变形的 UI 缺陷。
- **AI 回复行内 Markdown 粗体渲染修复**：重构了 `MarkdownText.kt` 中的 `renderInlineMarkdown` 函数，在行内代码分割以外，支持用双星号 `**` 语法块做奇偶过滤并绑定 `FontWeight.Bold`，彻底解决了 AI 回答时类似 `**Loyea**` 不加粗的展示 Bug。
- **顶栏胶囊双行级联复合选择器**：将原本的 ModelSelector 升级为全新的高颜值胶囊，支持左侧显示角色圆形头像（有本地头像加载与哈希底色首字母兜底）、角色姓名大字标题、底层模型小字副标题以及下拉小箭头，在兼顾解耦与角色品牌代言的同时实现完美的视觉交互。
- **Gson 序列化依赖**：在 `app/build.gradle.kts` 中引入了 `com.google.code.gson:gson:2.10.1` 依赖，以支持聊天数据本地持久化。
- **本地会话及消息存储管理器 (ChatStorageManager)**：全新创建了 `ChatStorageManager.kt`，利用 Android 应用私有目录（`context.filesDir`）以 JSON 格式存储会话列表元数据 (`sessions_metadata.json`) 以及各独立会话的消息历史 (`session_{id}.json`)。
- **多会话隔离与动态切换**：在 `MainActivity` 层级重构成单数据源管理机制，在切换会话时精确动态读取并显示对应历史，彻底隔绝不同会话间的数据。在全部删除会话后能自动生成新的默认会话，提供了高容错边界逻辑。
- **首条用户消息自动生成会话标题**：新创建的会话当发送第一条用户消息时，系统会自动提取该消息的前 15 个字作为该会话的标题并同步持久化到本地，优化了标题生成体验。
- **侧边栏行内快捷删除会话**：在侧边栏 (`SidebarContent`) 的历史会话项中增加了删除按钮，点击即可直接删除该会话及其对应的本地 JSON 文件，并自动重新对准可用会话，提高了会话生命周期管理能力。
- **侧边栏历史会话时间动态分组**：基于会话的最后活动时间（`lastActiveTime`），实现了“今天”、“昨天”、“前 7 天”、“更早”的智能动态分类渲染。
- **网络错误消息字段**：在 `Message.kt` 中为 `Message` 实体类新增了 `isError: Boolean` 属性（默认值为 `false`），以精确感知和存储对话过程中的连接及配置错误。

### Removed
- **升级 Pro 广告移出**：从侧边栏（`SidebarContent`）中彻底移除了黄金质感的 "Upgrade to Claude Pro" 广告卡片，移除了对应的 `onUpgradeClick` 事件参数及 Toast 提示逻辑，净化了侧边栏的界面视觉，提升用户体验。

### Changed
- **全面重命名替换为 Loyea 项目名**：
  - 将所有用户 UI 界面中对 "Claude" 助理和应用的提及全面替换为 "Loyea"，包括 Chat 界面的默认欢迎语、新建会话初始语、输入框占位符（"Talk to Loyea"/"与 Loyea 对话"）、配置页的主题风格描述（"Loyea Warm Amber"）。
  - 将内部代码逻辑（类名、变量名、字体定义名）中的 `Claude` 前缀全面重命名为 `Loyea`（如 `ClaudeTheme` 更改为 `LoyeaTheme`，`ClaudeTypography` 更改为 `LoyeaTypography`，以及 `ClaudeLightBg`、`ClaudeDarkBg` 等系列颜色配置重命名为 `LoyeaLightBg`、`LoyeaDarkBg` 等）。
- **真实大模型网络通信挂载**：在 `ChatScreen.kt` 的 `onSend` 发送消息逻辑中，移除写死的 MCP 多阶段仿真动画，挂接真实的 `LlmClient.sendChatCompletion(...)` 异步请求。现在，在等待期间正常展现全局加载闪烁指示器，接收响应后计算精确的 API 思考时间并赋给 `thoughtDurationSeconds`，然后通过打字机逐字输出。
- **自定义警告错误气泡渲染**：重构了 `MessageItem` 针对 AI 消息的处理逻辑。当消息状态为 `isError = true` 时，AI 回答将不采用通用 Markdown + 动作条排版，而是直接渲染为具有圆角淡红背景（`Color(0xFFFDE8E8)`）、淡红细线边框（`Color(0xFFF8B4B4)`）、警告深红文本（`Color(0xFFE02424)`）和 `Icons.Default.Error` 图标指示的警告卡片，同时剥离了无意义的动作条（复制、发音等），提升交互质量与体验。

### Fixed
- **ChatScreen 多余右大括号删除**：清理了 `ChatScreen.kt` 核心组件大括号尾部多余的闭合花括号，彻底解决 "Expecting a top level declaration" 错误。
- **Preview 预览界面签名一致性修复**：修复了 `ChatScreenPreview` 和 `MainScreenPreview` 预览方法中因未同步匹配角色卡、酒馆路由回调和参数类型造成的编译报错，通过绑定测试 dummy 数据完成闭环。
- **欢迎界面导入与引用修复**：修复了 [WelcomeScreen.kt](file:///D:/CodingProjects/Android/Loyea/app/src/main/java/com/loyea/ui/welcome/WelcomeScreen.kt) 仍在使用已废弃的 `ClaudeTheme` 导入及包装问题，已将其升级替换为最新的 `LoyeaTheme`；同时同步修改了欢迎界面（WelcomeScreen）内部大标题文本、欢迎小标语及底部服务条款中的 "Claude" / "Anthropic" 品牌提及，确保启动欢迎页的品牌一致性。
- **模型选择菜单居中修复**：移除了 `ModelSelector` 中 `Box` 容器的 `fillMaxWidth()` 宽度占满设置，配合 `CenterAlignedTopAppBar` 的自适应机制，彻底修复了下拉菜单弹出位置偏左的问题，实现完美的水平垂直正中心弹出。
- **用户气泡配色绑定修复**：修复了 `ChatScreen.kt` 中渲染用户消息气泡背景色时，因硬编码修饰符背景参数导致自定义底色及文字自适应不生效的 Bug。
- **主界面导入异常修复**：修复了 `MainScreen.kt` 中因缺少 Compose 运行时 `remember` 依赖导入导致的 `Unresolved reference: remember` 编译报错。
- **输入框语言引用异常修复**：修复了 `ChatScreen.kt` 中因 `ChatInputBar` Composable 函数缺少 `appLanguage` 签名而直接使用 `isEn` 造成的 `Unresolved reference` 编译报错。
- **ChatScreen 预览参数同步修复**：修复了 `ChatScreenPreview` 中由于未传入 `apiConfigList` 且使用了废弃的 `onApiConfigChange` 回调所导致的编译错误。
- **Gson 全局包名及引用修复**：修复了 `MainActivity.kt`、`ChatStorageManager.kt` 与 `LlmClient.kt` 中因将 `com.google.gson` 错误引用为 `com.google.code.gson` 导致的大面积 unresolved reference 编译报错。
- **协程 launch 导入缺失及作用域冗余修复**：在 `MainActivity.kt` 中导入了缺失的 `kotlinx.coroutines.launch` 库以支持异步请求任务，并消除了 `setContent` 作用域内多余的 `val scope` 同名声明，解决了声明冲突编译错。
- **泛型类型推导修复**：在 `MainActivity.kt` 的 `initialConfigs` 的 remember 表达式中显式指定了 `<List<ApiConfig>>` 泛型，消除了类型推导不足的报错。
- **ModelSelector 参数不匹配修复**：重构了 `ChatScreen.kt` 内的 `ModelSelector` 顶层胶囊参数及逻辑体，使之接收 `selectedModelName`、`apiConfigList`、`onActiveConfigChange` 并根据用户配置的连接别名进行下拉列表渲染，解决了在 `ChatScreen` 中调用不匹配的报错。
- **SettingsScreen 重复代码及列表导入修复**：删除了 `SettingsScreen.kt` 尾部不慎重复合并的大段多余代码（消除了 `ThemeSettingsLayout` 和 `SettingsScreenPreview` 重复定义报错），并补齐了 `LazyColumn` 和 `items` 导入包，扫清了设置界面全部编译报错。
- **侧边栏物理时间防抖及全屏透明拦截屏障优化**：在 `MainScreen.kt` 中除了在 `onMenuClick` 引入 800ms 点击防抖外，还在最外层设计了全屏无感透明拦截层（PointerInput Barrier）。该层仅在侧边栏滑出动画运行期间（`isAnimationRunning && targetValue == Open`）显形并消费所有的屏幕点击，彻底隔绝了连击时后续点击落入刚显影的 Scrim 遮罩上而自动触发 `close()` 缩回的原生缺陷，彻底实现了连击不丢失、侧栏完美滑出的绝佳体验。
- **用户名设置页回显与编辑状态切换修复**：修复了 `SettingsScreen.kt` 内的 `InlineEditNameField` 中 Viewer（`Text` 与 `Icon`）分支被错误嵌套进 `isEditing` 判断中的嵌套 bug，使其能够正确作为 `else` 分支进行渲染，彻底解决了已保存的用户名在设置页展示空白的缺陷。
- **新空白会话重复创建限制**：在 `ChatScreen.kt` 的右上角操作区中增加了用户是否发言过 (`hasUserSpoken`) 的条件判定。只有当前会话中包含用户发送的消息时，才会显示“新建会话”按钮；新创建会话下默认对该按钮进行隐藏，彻底防止了频繁连击造成重复生成一堆空白会话的交互痛点，也使新用户初始界面更加聚焦清爽。
- **侧边栏收回期间的反复误触拦截**：优化了 `MainScreen.kt` 的透明拦截屏障判定。将全屏拦截的显示条件由 `drawerState.isAnimationRunning && targetValue == Open` 改为在整个 `drawerState.isAnimationRunning`（滑出与缩回动画期间）下通用生效。这彻底阻断了用户在点击 Scrim 收回抽屉动画期间连续点击外部导致动画反复中断、倒退和状态震荡的原生 bug。
- **会话行内快捷删除确认二次弹窗**：在 `MainScreen.kt` 内的 `SidebarContent` 的会话项删除逻辑中设计了二次确认 `AlertDialog`。点击删除图标时弹出具备 Loyea 精致大圆角、中英文多语言自适应及红字强警示的确认卡片，点击确认方可删除，极大提高了对话销毁的容错防误触能力。

### Added
- **多语言（中英文）自适应与参数打通**：全新引入应用语言首选项（支持中文与英文一键动态无缝切换），从 `MainActivity` 读写 SharedPreferences 动态感知，并在 `MainScreen`、`ChatScreen` 问候语/提示语/输入栏及 `SettingsScreen` 全方位绑定，打通了多语言全局响应式更新通路。
- **外观与语言二级配置页 (ThemeSettingsLayout)**：创建了全新的二级配置页，替代了原有的普通对话框。用户可在此单选控制 Light/Dark/System 主题，还能配置用户气泡颜色与应用语言。
- **自定义气泡颜色**：气泡颜色设置支持四种精致美观的预设颜色（琥珀沙黄-Claude风格、莫兰迪灰-ChatGPT风格、微光浅绿、极简天蓝）及系统默认配色，并在 `MessageItem` 进行了完美的气泡底色和文字色动态融合。
- **API 设置与大模型模板扩充**：在二级 API 配置页面中新增了 **Kimi (Moonshot)、Qwen (千问)、MiniMax、MiMo** 服务商的联动预设模板。
- **动态大模型推荐 Chips 组**：在设置页模型名称输入框下方，增加了根据所选服务商动态渲染的**推荐模型快捷选择 Chips**（例如 Kimi 的 `moonshot-v1-8k`，千问的 `qwen-turbo`）。用户点击即可自动填充模型名称，免去手动输入的繁琐。

### Changed
- **顶部候选模型选择胶囊物理居中修复**：在 `ChatScreen.kt` 中引入了 **`CenterAlignedTopAppBar`** 替代原本的 `TopAppBar`，完美锁定了大模型选择器在屏幕左右的水平物理居中。
- **参数传递双向通路打通**：
  - 全局持久化 `apiConfig` 状态从 `MainActivity` 通过 `MainScreen` 逐级向下透传至 `ChatScreen` 的 `ModelSelector` 顶层胶囊，实现默认模型的自动绑定渲染。
  - 在 `ChatScreen` 的模型选择下拉菜单中切换模型时，通过 `onApiConfigChange` 回调反馈给 `MainActivity` 动态更新全局状态并自动持久化保存至 `SharedPreferences`；同时，主设置页 of API 连接条目副标题实时反映此模型变更，打通了整个“双向数据同步”通路。
- **用户名设置控件极简美化与列表整理**：
  - 移除了原有的庞大 `USER PROFILE` 独立卡片，将一级主设置页梳理并归整为两组结构清晰的列表：`ACCOUNT PROFILE`（包含 32.dp 迷你动态头像及行内原地极简编辑框）与 `SYSTEM SETTINGS`（API & Model 连接入口与 Theme & Language 设置）。
  - 对用户名编辑控件高度和 Padding 进行了统一微调，使之与下方条目高度完全一致，支持失焦或按回车键立即原地静默保存，整体设置页面视觉体验极其规整精致，充满 Claude 美学。

## [Unreleased] - 2026-06-09

### Fixed
- **导入异常修复**：补齐了 `SettingsScreen.kt` 中遗漏的 `androidx.compose.ui.tooling.preview.Preview` 导入，彻底消除了 `@Preview` 的 `Unresolved reference` 编译报错。
- **预览适配修复**：修复了 `MainScreen.kt` 与 `SettingsScreen.kt` 中因为 Composable 签名重构、缺少 `userName` 与 `apiConfig` 传参导致的 Preview 编译报错。
- **Gradle 镜像优化**：将 Gradle 下载链接替换为国内腾讯云镜像源，彻底解决了 Gradle distribution 下载导致的 `Read timed out` 连接超时问题。
- **状态委托与类型推导修复**：在 `MainActivity.kt` 中将 `by remember` 委托机制改写为显式的 `val state.value` 访问，彻底解决了由 Kotlin 与 Compose 编译插件的委托解析歧义引发的 `Unexpected type specification` 编译报错。
- **语法错误修正**：修复了 `MainActivity.kt` 中调用 `super.onCreate(savedInstanceState?)` 时误写问号的语法 Bug，彻底解决了 `Unexpected type specification` 的编译报错。
- **参数定义语法修复**：修复了 `ChatScreen.kt` 中 `ChatInputBar` 的 `onValueChange` 参数声明中，将冒号 `:` 误写为等号 `=` 导致的 `Expecting comma or ')'` 编译报错。
- **Markdown 语法溢出修复**：移除了 `Theme.kt` 文件末尾由于大模型导出残留的反引号 ` ``` `，彻底解决了 `Expecting a top level declaration` 的编译报错。
- **冗余导入清理**：移除了 `ChatScreen.kt` 中多余的 `import com.loyea.R` 导入，避免了在项目生成 `R` 类前导致的不必要语法报红。

### Added
- **API 配置与自定义用户名持久化管理 (走向真实业务逻辑)**：
  - **取消登录页**：将 `MainActivity` 导航起点重设为 `main`，移除了欢迎登录页面，实现应用开启秒开直达聊天屏。
  - **自定义用户名**：在 `SettingsScreen.kt` 中设计了用户名输入卡片，联动更新侧边栏（Sidebar）的用户头像与用户名展示。
  - **大模型 API 管理面板**：全新开发了 API 配置卡片，支持主流大模型商预设模板（Anthropic、OpenAI、DeepSeek、Custom）的下拉选择、API Base URL 自动匹配、密码模式 API Key 文本框以及 Model 输入。
  - **SharedPreferences 本地持久化**：在 `MainActivity.kt` 级别集成 `SharedPreferences`，对系统主题、用户名以及所有 API 接口参数提供永久性本地磁盘保存，应用重启后数据不丢失。
- **MCP 与 Thinking 交互自适应优化 (锁定与自动折叠机制)**：
  - **McpCallItem 自适应折叠**：引入 `hasUserInteracted` 逻辑，工具在运行（RUNNING）时默认展开以保证实时动态效果；执行成功（SUCCESS）后自动收起以保持消息流整洁。当且仅当用户手动干预点击后，该卡片锁定当前状态，系统逻辑不再接管折叠。
  - **ThinkingProcessLayout 锁定机制**：扩展 `Message` 状态树添加 `hasUserToggledThoughts`，思考结束后如果用户没有操作过卡片则自动折叠；若用户主动操作过，则锁定用户选定状态，高度尊重用户的主动行为，大幅度提升人机交互细节体验。
- **代码块语法高亮引擎 (Syntax Highlighting)**：在 `MarkdownText.kt` 中手写实现了极轻量、免第三方庞大依赖的 Kotlin/Java 正则高亮解析器。支持对关键字（橙）、注解（黄）、数字（蓝）、字符串字面量（绿）和单/多行注释（斜体灰，且具备最高级覆盖屏蔽逻辑）的精致色彩渲染，大大强化了对话内代码的 1:1 复刻美感。
- **MCP 与 Thinking 推理链集成 (高仿真动画流程)**：
  - 创建了 `ThinkingAndMcpComponents.kt`，设计了高颜值的工具调用与深度思考组件。
  - **McpCallItem**：显示工具状态（运行中齿轮匀速旋转动效，成功为绿勾，失败为红叹号），且支持点击平滑展开展示参数与运行结果。
  - **ThinkingProcessLayout**：完美复刻 Claude 思考框，左侧折叠小三角带有 0 至 90 度的旋转过渡动画，内部思考文字支持高度弹性伸缩（animateContentSize）。
  - **仿真时间线逻辑**：在发送消息后，AI 会执行多阶段推理与工具调度流（“资源加载 -> 跑 read_file 齿轮旋转 -> 开启 Thinking 深度推理 -> 跑 web_search 齿轮旋转 -> 流式打字输出正文”），提供惊艳的真实交互体验。
  - **Message 模型兼容升级**：在 `Message.kt` 中添加了扩展字段（`thoughts`、`mcpCalls` 等），支持旧代码 100% 向后兼容。
- **Claude 官方风格动画与动态交互**：
  - **流式打字机效果**：修改了 AI 模拟回复的协程逻辑，改由流式逐字/字符追加，使消息在 `MarkdownText` 中顺滑渲染。
  - **消息气泡上浮淡入**：利用 `Animatable` 和 `graphicsLayer` 动作，为聊天消息卡片在载入时提供轻盈、平滑的自下而上（FastOutSlowInEasing）淡入漂移效果。
  - **输入栏高度微弹性动效**：对聊天输入框容器添加了 `animateContentSize`，使得换行高度改变时伴有弹性缓冲过渡，消除生硬阶跃。
  - **按钮动态切换动效**：将底部输入为空时的麦克风图标和有文字时的发送圆圈按钮，通过 `AnimatedContent` 和 `animateColorAsState` 绑定，实现了流畅的 Scale Fade 状态转换和背景色渐变。
  - **欢迎页级联载入效果**：在 `WelcomeScreen.kt` 中设计了标题和登录按钮的错落式淡入动画，大标题首先浮现，200ms 后按钮组接续滑入，极具艺术呼吸感。
- **项目骨架初始化**：
  - 创建了项目根目录配置：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle-wrapper.properties`。
  - 创建了 `app` 模块配置：`app/build.gradle.kts`、`AndroidManifest.xml` 以及默认矢量图标 `ic_launcher.xml` 和 `strings.xml`。
- **Claude 视觉风格主题与排版**：
  - 从本地系统目录成功拷贝并重命名了 4 个 Anthropic 官方可变字体文件（`anthropic_sans_romans.ttf`、`anthropic_sans_italics.ttf`、`anthropic_serif_romans.ttf`、`anthropic_serif_italics.ttf`）至项目 `app/src/main/res/font/` 目录。
  - 定义了 `Color.kt`（燕麦白亮色背景、深褐色暗色背景以及对应的文本与辅助色值）。
  - 更新了 `Type.kt`，声明 `AnthropicSans` 和 `AnthropicSerif` 官方字体族，全面替换应用界面及对话文本的排版字体，完美呈现 1:1 官方排版质感。
  - 定义了 `Theme.kt`（提供 `ClaudeTheme`，动态感知系统暗色主题并设置状态栏和导航栏）。
- **Markdown 精致渲染**：
  - 创建了 `MarkdownText.kt`，能够解析 Markdown 中的块级代码（包含带有“Copy”按钮和语言标识的黑色代码容器）、行内代码（等宽字带背景）和常规文本。
- **聊天主界面（ChatScreen）**：
  - 顶部 1:1 复刻胶囊型模型选择下拉菜单（支持切换 "Claude 3.5 Sonnet" 等模型）。
  - 支持发送消息后自动滚动到底部，并带有多级延迟模拟 AI 的思考闪烁动画（ThinkingIndicator）。
  - 聊天气泡：用户气泡带不对称圆角；AI 气泡直接在“纸张”底色上排版，下方带有小巧的动作条（复制、发音、重新生成、赞/踩）。
  - 底部圆角输入栏：左侧加号附件，输入为空时右侧显示麦克风图标，输入文字时动态切换为 Primary 色的向上箭头发送按钮。
- **侧边栏滑出菜单（Sidebar Drawer）**：
  - 顶部显示用户信息，中部按时间（Today, Yesterday, Previous 7 Days）分组展示历史聊天记录，底部设计了黄金质感的 "Upgrade to Claude Pro" 卡片和 "Settings" 入口。
- **欢迎/登录页（WelcomeScreen）**：
  - 极简居中的 "Claude" Serif 大标题，提供 "Continue with Google" 和 "Continue with Email" 圆角扁平化按钮，并附带底部协议声明。
- **设置页（SettingsScreen）**：
  - 包含用户资料卡片、退出登录按钮，并支持通过 AlertDialog 弹窗切换 Theme（亮色、暗色、跟随系统）。
- **页面导航串联（MainActivity）**：
  - 引入 Navigation 控制器，管理 Welcome -> MainChat -> Settings 之间的路由跳转。
  - 全局持有 `currentTheme` 状态，打通了设置页主题更改对整个 App 视觉的实时控制。
