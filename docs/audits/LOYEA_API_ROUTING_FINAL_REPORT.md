# Loyea API 路由重构最终开发报告

- 执行规范：`docs/Loyea_API_Routing_Config_Audit_Refactor_Spec_v1.0.md`
- 起始基线：`612b430`（v0.8.2）；结束 HEAD：见 git log（本轮 6 个阶段提交）
- 执行日期：2026-09-10 夜间自主推进

## 1. 实际完整阅读的生产代码

逐文件完整阅读（核心链路）：LlmClient.kt、ChatViewModel.kt、SettingsScreen.kt（5063 行全量）、ApiConfigVault.kt、ChatStorageManager.kt、GreetingWorker.kt、MemoryConsolidationWorker.kt、ModelCatalogClient.kt、RebuildStorageMigrator.kt、CharacterDocumentStore.kt、WorldInfoLibrary.kt、PromptAssembler.kt、LlmConversationBuilder.kt、WorldInfoBridge/Config、VoiceTextExtractor、TokenEstimator、ThinkingTimer、LlmRequestCanonicalizer、MessageTimeFormatter、ConversationTimelineFormatter、Message.kt、mcp 包全部 5 文件、perception 包全部 14 文件、health 包全部 9 文件、MainActivity、HealthRationaleActivity、TokenUsageWidget、TavernCardParser、WorldInfoConfigStorage。

定点阅读 + 全文 grep 验证（纯展示层，确认无存储/网络/配置交互）：ChatScreen.kt、MainScreen.kt、TavernScreen.kt、WorldInfoLibraryScreen.kt、MarkdownText.kt、HtmlPanel.kt、ThinkingAndMcpComponents.kt、theme 包、WatchBluetoothClient.kt、WorldInfoBookJson/Models.kt。文件覆盖表见审计报告 §5。

## 2. 功能通道与解析路径（重构后）

| 通道 | Config/Model 解析 |
|---|---|
| CHAT（含 Greeting、标题、压缩辅助） | activeConfigId → Resolver（isEnabled 校验） |
| VISION（聊天 + Caption 同源） | 显式 Binding 优先（不被字符串猜测否决）；无绑定继承 CHAT + 能力佐证 |
| AUDIO_INPUT | 继承 CHAT + input_audio 能力校验，不足回落 STT |
| STT / TTS / IMAGE_GENERATION | 显式 Binding；未配置明确不可用（存量行为已回填为显式绑定） |
| MEMORY | 显式 Binding；空则继承 CHAT（规则唯一位于 Resolver） |

HTTP：Chat Completion 仅 `com.loyea.llm.UnifiedChatTransport` 一处；TTS/STT/生图/搜索/网页抓取保持独立客户端（Spec §1 明确允许）；MCP 独立协议不并入。

## 3. 风险统计与处理

- **P0 ×4：全部关闭**（Vault 原子迁移、解析失败覆盖保护、悬空绑定清理、TTS 隐私日志）。
- **P1 ×12：11 项关闭，1 项记录保留**（P1-12 DeepSeek 旧模型名启动迁移，产品决策，见迁移文档 §5）。
- **P2 ×8：4 项关闭，4 项记录**（搜索 Key 明文、过渡期兼容字段、onStop 停止生成、UI 过滤差异——均不属本轮 P0/P1 范围）。
- **审计中新发现**：WorldInfoLibrary 空书单 fresh-install 迁移 ENOENT 无限重试（实机复现后修复并复验）。

## 4. 关键机制说明

- **ApiConfigVault**：迁移序列 = commit 写入 → read-back 校验 → 确认删除；任一步失败保留明文 legacy、不标记完成；迁移未完成时 saveJson 拒绝写入（防默认配置覆盖）；saveJson 写后校验，失败 Toast 可见。7 场景故障注入测试锁定。
- **流式状态机**：`sseTransportObserved` + `semanticOutputObserved` 双状态；AUTO 仅在「结构化 unsupported-stream（HTTP 非 200 / 200 error JSON / SSE error 事件）」或「200 完整整包」时降级/学习；HTML/Empty/malformed 一律报错不降级不学习；无 [DONE] 且无 finish_reason 的静默结束判定为「生成中断」，保留半截内容不整轮重发；FORCE_STREAM 不静默降级；fallback 成功前禁止写缓存。
- **Capability cache**：键 = configId+model+adapterId；仅两种写入路径；进程级共享（object 单例）；配置变更按 configId 失效。
- **ProviderAdapter**：OpenAI 兼容基础档（保守分母：无 stream_options、无 native search 字段）+ DeepSeek（stream_options）/ MiMo（api-key 头）/ Zhipu（enable_search）档位；`web_search`+`enable_search` 双写退役。

## 5. 用户数据迁移

全部自动、幂等：vision 出厂默认值归一化（gpt-4o-mini → 跟随 defaultModel）、STT/TTS/生图存量兜底行为回填为显式绑定、删除配置自动清理绑定。明细见 `docs/migrations/LOYEA_CONFIG_ROUTING_MIGRATION.md`。

## 6. 测试与验证（真实执行结果）

- `./gradlew :app:testDebugUnitTest` 全绿（含新增：Vault 迁移故障注入 7 项、MockWebServer 网络状态机矩阵 24 项、ChannelBinding 矩阵 13 项；历史回归 LlmClientStreamSilentFailureTest 等全部保留通过）。
- `:app:assembleDebug` + `:app:assembleRelease` 双构建成功。
- **实机 smoke（Pixel_10 模拟器 + MiMo 真网 Key）**：
  - ✅ 主聊天流式全链路：发送 → MiMoAdapter 请求（日志：`POST https://api.xiaomimimo.com/** model=mimo-v2.5-pro adapter=mimo msgs=4 tools=14`）→ 深度思考流（"已深度思考 10s"）→ 正文回复 → 落盘；
  - ✅ ConfigurationMissing 显式错误路径（Key 缺失时用户可见、可操作）；
  - ✅ 429/瞬时故障自动重试退避（1s/2s 线性退避在日志可辨）；
  - ✅ 新设置 UI（启用此连接开关、流式传输模式三段选择器）渲染与保存正常；
  - ✅ worldinfo fresh-install ENOENT 修复复验（重装后错误计数归零）。
- **未实机验证（如实标注）**：NON_STREAM 模式实机（MockWebServer 矩阵已覆盖 FORCE_NON_STREAM 与 omit-stream 场景）；STT/TTS 实机（模拟器音频链路受限，未消耗额外 API 费用）；小马/中转真渠道降级（无渠道凭据，HTTP 层矩阵已覆盖 400/422/501/200-error 分支）。

## 7. 交付物清单

1. 代码：`com.loyea.llm` 新模块 6 文件、`storage/ChannelBinding.kt`、`storage/ApiConfigRepository.kt`、LlmClient 重构、ChatViewModel 通道迁移、两 Worker 收编、SettingsScreen 新控件、WorldInfoLibrary 修复。
2. 审计：`docs/audits/LOYEA_RUNTIME_CONFIG_AUDIT.md`（含处理结论回填）。
3. 迁移：`docs/migrations/LOYEA_CONFIG_ROUTING_MIGRATION.md`。
4. 测试：3 个新测试类 + 既有测试适配。
5. 提交：docs(audit) / fix(storage) / refactor(llm) / refactor(config) / feat(settings) 各独立提交。

## 8. 遗留与建议（后续独立 Spec）

- 全局搜索 Key 迁入加密存储（P2-03）。
- 小马/中转真渠道的实机降级验证（需渠道凭据）。
- Custom 连接的协议档位显式选择 UI（当前默认 OpenAI 兼容基础档）。
- onStop 停止生成的产品语义确认（P2-06）。
