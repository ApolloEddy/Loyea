# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-06-10

### Fixed
- **模型选择菜单居中修复**：移除了 `ModelSelector` 中 `Box` 容器的 `fillMaxWidth()` 宽度占满设置，配合 `CenterAlignedTopAppBar` 的自适应机制，彻底修复了下拉菜单弹出位置偏左的问题，实现完美的水平垂直正中心弹出。
- **用户气泡配色绑定修复**：修复了 `ChatScreen.kt` 中渲染用户消息气泡背景色时，因硬编码修饰符背景参数导致自定义底色及文字自适应不生效的 Bug。
- **主界面导入异常修复**：修复了 `MainScreen.kt` 中因缺少 Compose 运行时 `remember` 依赖导入导致的 `Unresolved reference: remember` 编译报错。
- **输入框语言引用异常修复**：修复了 `ChatScreen.kt` 中因 `ChatInputBar` Composable 函数缺少 `appLanguage` 签名而直接使用 `isEn` 造成的 `Unresolved reference` 编译报错。

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
