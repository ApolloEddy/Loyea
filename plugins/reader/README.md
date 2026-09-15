# Loyea 伴读插件（plugin/reader）

依据 `docs/Loyea_Reader_Companion_Plugin_Spec_v0.1_DRAFT.md` 实现的「伴读」无障碍插件：
在用户阅读网页小说时，读屏采集正文 → 提纯 → 章节缓冲 → 滚动摘要与实体卡，
悬浮球一键唤出对话面板向 Loyea 提问，回答基于**已读内容**且**不剧透未读部分**。

## 非侵入边界

全部实现收敛在 `com.loyea.plugin.reader` 命名空间（`plugins/reader/android/src/main/kotlin`），
经 `app/build.gradle.kts` 的 sourceSets 挂载进 app 编译（与陪伴插件同构）。

宿主触碰点（全量清单）：

| 文件 | 改动 | 说明 |
|---|---|---|
| app/build.gradle.kts | sourceSets srcDir（2 行） | 组织挂载（main + androidTest） |
| AndroidManifest.xml | `SYSTEM_ALERT_WINDOW` 权限 + 服务声明（13 行） | 悬浮窗权限；`ReaderAccessService` 以无障碍服务声明（`reader_accessibility_service.xml`），授权由用户在系统设置完成 |

除此之外零宿主触碰：不进聊天链路、不改 ChatViewModel / 存储层 / 设置页；
面板问答直连 `ApiConfigRepository.resolve(CHAT)` 与最简 HTTP 请求，与宿主对话互不感知。

## 运行链路

- **采集**：`ReaderAccessService` 仅采样白名单包名（默认含 Chrome 供验证，用户可在设置面板追加）；
  深度受限 DFS 取文本节点，800ms 去抖，密码框/编辑框跳过。
- **提纯**：`ReaderTextPurifier` 行合并、广告/进度条剥离（两遍法：重复 ≥3 次的样板文本整段剔除）、
  标点折叠、章节标题启发式识别；`ReaderPurifierSession` 按页面维护。
- **缓冲**：2 章 LRU；`visibleCursor` 单调推进——**防剧透屏障物理截断**送入提示词的正文，
  模型只能看到用户已读到的位置（`ReaderChapterBuffer`）。
- **记忆**：`ReaderContextMemory` 滚动摘要 + 实体卡（首次出现章节追踪），跨章延续、预算裁剪。
- **提问**：悬浮球面板（含状态行：正在读/章节/已读段数/摘要字数/实体数）➜ `ReaderPromptAssembler`
  以「稳定前缀 + 动态模块」顺序组装（利于服务商前缀缓存），已读上下文 + 问题发往当前对话渠道配置；
  防剧透开启时，涉及未读内容的提问走 `ReaderBubbleChat` 本地拒绝模板，不发起网络请求。

## UI

- **悬浮球**：`SYSTEM_ALERT_WINDOW` 覆盖层，可拖动、点击展开面板；球内为 `ReaderNeuralBallView`
  渲染的 NeuralLiving 迷你画布（15fps Handler 驱动，节点/边实时演化）。
- **对话面板**：状态行 + 问题输入框 + 问 Loyea / 伴读设置 / 收起；会话行即时回显。
- **设置面板**：防剧透开关（默认开）、采样节流分钟、白名单展示。

## 隐私边界

- 正文只在内存缓冲，**服务销毁即清空**；不落盘、不进备份（备份 v3 runtime 不含 reader 数据）。
- 白名单外应用一个字节都不读；密码框/编辑框永远跳过。
- 提问仅把已读正文（受防剧透游标截断）发送给用户自己配置的对话渠道；无任何遥测。

## 测试

- `ReaderCoreTest`：提纯（行合并/广告剥离/标点折叠/章节识别）、白名单、防剧透游标、提示词组装与预算裁剪。
- `ReaderContextMemoryTest`：滚动摘要、实体卡、增量喂入、跨章延续、防剧透游标联动。
- 模拟器实测：服务绑定、悬浮球覆盖 Chrome、小说页采样提纯、面板问答端到端
  （提问 → 对话渠道 200 → 回复正确引用已读原文）见 `docs/audits/Companion-Intelligence-Acceptance.md`。

## 已知边界

- 真机（OPPO Find X6）性能/温升与厂商杀后台行为未实测；无障碍服务在深度定制 ROM 上的存活策略待验证。
- 对依赖 JavaScript 动态渲染才出正文的站点，无障碍树可能取不到正文（与宿主 `read_url` 同源限制）。
- 采样节流与白名单发布前默认值可能收窄（Chrome 为验证用默认项）。
