# Loyea 运行时配置与 API 路由审计报告

- 审计基线 HEAD：`612b430ca15f59e37397d2baf9576ace674520f4`（v0.8.2，与 Spec v1.0 基线一致）
- 审计规范：`docs/Loyea_API_Routing_Config_Audit_Refactor_Spec_v1.0.md`
- 审计日期：2026-09-10（Phase 0 只读审计，未修改任何生产代码）
- 审计方法：核心运行时文件（LlmClient / ChatViewModel / SettingsScreen / 存储层 / Worker / MCP / 感知 / 健康 / MainActivity）逐文件完整阅读；纯展示型 UI 文件（ChatScreen 渲染部分、MainScreen、TavernScreen、WorldInfoLibraryScreen、MarkdownText、HtmlPanel、ThinkingAndMcpComponents、theme）经全文 grep 验证无 SharedPreferences 写入、无网络调用、无 ApiConfigVault 访问，并对其中与 ApiConfig 相关的段落（ModelSelector 等）定点完整阅读。

## 0. 处理结论（2026-09-10 重构完成后回填）

| ID | 结论 |
|---|---|
| P0-01 | ✅ 已修（a6d3d80）：Vault 原子迁移 + VaultResult + 7 场景故障注入测试 |
| P0-02 | ✅ 已修（a6d3d80）：解析/读取失败不再写回默认列表 |
| P0-03 | ✅ 已修（a588bdc）：删除配置反查并清理全部通道绑定 |
| P0-04 | ✅ 已修（a6d3d80）：TTS 请求体日志 debug 门控 + 错误体截断 |
| P1-01~03 | ✅ 已修（760a5cf）：双状态流式状态机 + 明确 unsupported-stream 判定 + 缓存写入纪律 |
| P1-04 | ✅ 已修（a588bdc Resolver 校验 + 16a4ebb UI 开关） |
| P1-05 | ✅ 已修（760a5cf）：旧 sendChatCompletion 迁入统一 Transport |
| P1-06 | ✅ 已修（a588bdc）：VISION 通道 Binding 化 + Caption 同源；gpt-4o-mini 归一化 |
| P1-07 | ✅ 已修（a588bdc）：STT/TTS/生图显式绑定 + 明确不可用；存量回填 |
| P1-08 | ✅ 已修（760a5cf）：LlmErrorKind 全覆盖，降级判定不再依赖 message.contains |
| P1-09 | ✅ 已修（760a5cf）：非流式 180s 独立读超时 |
| P1-10 | ✅ 已修（a588bdc）：两 Worker 改经 ApiConfigRepository |
| P1-11 | ✅ 已修（a588bdc）：Anthropic 隐藏配置保存时合并回全量列表 |
| P1-12 | ⚠️ 保留（v0.8.x 产品决策：旧 DeepSeek 模型名升级迁移；已在迁移文档记录取舍） |
| P2-01/02 | ✅ 已修（760a5cf）：native search/stream_options 由 Adapter 声明 |
| P2-03 | ⚠️ 记录未修（搜索 Key 明文迁加密库涉存储结构变化，留独立变更） |
| P2-04 | ⚠️ 记录（Spec §16.4 允许的过渡期兼容只读字段） |
| P2-05 | ✅ 已修（a588bdc）：能力判断改走 Resolver Ready + Key 校验 |
| P2-06 | ⚠️ 记录（onStop 停止生成疑为节流设计，保持现状） |
| P2-07 | ⚠️ 记录（MiMo gpt-4o-mini 自愈保留于 LlmClient，Spec §27 禁止新增） |
| P2-08 | ⚠️ 记录（UI 过滤与设置页差异为合理行为） |
| 新发现 | ✅ 已修（16a4ebb 后）：WorldInfoLibrary 空书单 fresh-install 迁移 ENOENT 无限重试（worldinfo/ 目录未建）；实机复现 → 修复 → 复验消失 |

---
---

## 1. 通道 / Config 调用图（每个模型相关功能的配置来源）

| 功能 | Config 来源 | Model 来源 | 隐式 fallback（违规点） | 发现 |
|---|---|---|---|---|
| 主聊天 | `activeConfigId → apiConfigList.find` | `config.modelName` + DeepSeek 智能路由 | 悬空时硬编码 Default DeepSeek（Key 空） | P1-04 isEnabled 不校验 |
| Vision 聊天 | `visionConfigId → find` | `visionModelName`（独立状态） | `?: activeApiConfig` + gpt-4o-mini 字符串猜测 | P1-06 双事实来源 |
| 自动图注 Caption | `visionConfigId → find` | **`cfg.modelName`（不解析 visionModelName）** | `?: activeApiConfig` | P1-06 与 Vision Chat 不同源 |
| 音频直接理解 | 主聊天路由（AUDIO_INPUT 无独立 Binding） | 主聊天模型 | `providerSupportsAudioInput` 字符串猜测 | P1-14 |
| STT | `sttConfigId → find` | `sttModelName` + template | **扫描第一张 MiMo 卡** `?: activeApiConfig` | P1-07 |
| TTS | `ttsConfigId → find` | `ttsModelName` + `ttsVoice` | `?: activeApiConfig`（3 处独立实现） | P1-07 |
| 生图 | `imageGenConfigId → find` | `imageGenModel` | `?: activeApiConfig` | P1-07 |
| Memory 提炼（Worker） | Worker 直读 vault + `memory_api_config_id` | `config.modelName` | `?: activeApiConfig`（重复实现 2 处） | P1-11 |
| 会话压缩 / 强制压缩 | 同上（VM 内直读 prefs） | 同上 | `?: activeApiConfig` | P1-11 |
| 会话标题 | `activeApiConfig` | `config.modelName` | 无 | 符合 Spec §5.4（CHAT） |
| Greeting（Worker） | Worker 直读 vault + `active_config_id` | `config.modelName` | `?: firstOrNull()` | P1-11 |
| 搜索（web_search 工具） | 全局搜索 Key → `activeConfig.searchApiKey` 回落 | — | 免 Key 检索兜底 | P2-03 |
| 模型列表同步 | UI 直接传 URL/Key | — | 双候选端点 | 正常 |

## 2. 网络调用全量盘点

| # | 功能 | 位置 | URL 构造 | Header | Payload 方言 | Parser | Retry | Timeout | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 聊天流式 | `LlmClient.sendChatCompletionStream` | `resolveChatCompletionsUrl`（补 /chat/completions） | Bearer + MiMo api-key | stream=true；stream_options 仅 DeepSeek/OpenAI；web_search+enable_search 双写 | SSE 增量 + NonSseBody | 429/5xx/网络 ×3 | 共享 60s read | P1-01~03 |
| 2 | 聊天整包 | `LlmClient.sendRawChatCompletion` | 同上 | 同上 | 固定 `"stream": false` | `parseChatCompletionResponse` | 429/5xx/网络 ×3 | 共享 60s read | P1-09 |
| 3 | **旧整包路径** | `LlmClient.sendChatCompletion` | 独立 URL 拼接 | **无 MiMo api-key** | **无 stream 字段**；仅 web_search | 独立 parser | **无重试** | 共享 60s read | P1-05 重复实现；Memory/压缩在用 |
| 4 | TTS | `LlmClient.generateSpeech` | 4 种方言 URL | 4 种鉴权 | 4 种 payload | 3 种解析 | 无 | 共享 60s | P0-04 日志泄露 |
| 5 | STT | `LlmClient.transcribeAudio` | MiMo chat / multipart | Bearer + api-key | 2 种 | 2 种 | MiMo 400 降级一次 | 共享 60s | — |
| 6 | 生图 | `LlmClient.generateImage` | `/images/generations` | Bearer | OpenAI | data[].url/b64 | 无 | 共享 60s | — |
| 7 | 图注 | `LlmClient.describeImage` | 复用 #1 | 复用 | 复用 | 复用 | 复用 | 20s withTimeoutOrNull | P1-06 |
| 8 | Tavily 搜索 | `performIndependentWebSearch` | +/search | 无（Key 进 body） | Tavily | results[] | 无 | 共享 60s | — |
| 9 | 免 Key 搜索 | `performFreeWebSearch` | Bing/360/DDG | UA | HTML 抓取 | 正则 | 三源降级 | 独立 6s | 非 LLM，允许独立 |
| 10 | 网页读取 | `fetchWebPage` | 直连 | UA | HTML | Jsoup | 无 | 独立 8/12s | 非 LLM，允许独立 |
| 11 | 模型列表 | `ModelCatalogClient` | /models 双候选 | Bearer / x-api-key | — | 3 形态 | 候选轮询 | 独立 12/15s | — |
| 12 | 模板同步 | `ChatViewModel.fetchTemplatesFromNetwork` | jsDelivr/GitHub | 无 | JSON | TypeToken | 双 URL | 独立 10s | — |
| 13 | 图片下载 | `ChatViewModel.generateAndStoreImage` | 远端 URL | 无 | 二进制 | — | 无 | OkHttpClient 默认 | — |
| 14 | 天气 | `WeatherProvider` | wttr.in | UA=curl | j1 JSON / 短文本 | Gson/正则 | 无 | 独立 5s | 非 LLM |
| 15 | MCP | `McpClient` | SSE + endpoint POST | — | JSON-RPC | Gson | 重连退避 | 10s/30s | 非 LLM，不并入 Transport |

**结论：Chat Completion 存在 3 套独立 HTTP 实现（#1 #2 #3），必须统一（Spec §10）。**

## 3. Storage Key Inventory（loyea_prefs / loyea_secure）

| Key | 类型 | 写入方 | 读取方 | 敏感 | 状态 |
|---|---|---|---|---|---|
| `api_config_list`（明文旧键） | String | 已无写入方（Vault 迁移清除 + saveApiConfigList 防复活） | 无 | 是 | 已废弃，正确清除 |
| `loyea_secure/api_config_list_secure` | String | ApiConfigVault.saveJson / 迁移 | loadJson | **是（加密）** | 正常（迁移逻辑 P0-01） |
| `api_key/api_provider/api_url/api_model` | — | 无 | 无 | 是 | 死键，迁移已清 |
| `active_config_id` | String | selectActiveConfig | VM/两 Worker | 否 | 正常 |
| `vision_config_id` / `vision_model_name` | String | updateMultimodalSetting | VM 路由/图注 | 否 | P1-06 双事实来源；删除配置不清理 |
| `stt_config_id` / `stt_model_name` / `stt_provider_template` | String | 同上 | STT | 否 | P1-07 |
| `tts_config_id` / `tts_model_name` / `tts_voice` / `tts_provider_template` | String | 同上 | TTS | 否 | P1-07 |
| `image_gen_config_id` / `image_gen_model` | String | 同上 | 生图 | 否 | P1-07 |
| `memory_api_config_id` | String | MemorySettingsLayout 直写 | VM 压缩 / MemoryWorker | 否 | 删除配置不清理 |
| `global_search_provider/api_url/api_key` | String | GlobalSearchConfigCard 直写 | VM webSearchProvider | **是（明文！）** | P2-03 |
| `enable_multimodal/enable_stt/enable_tts/enable_audio_understanding/enable_auto_tts/enable_image_gen` | Bool | updateMultimodalSetting | VM | 否 | 正常 |
| `media_cache_clean_days` | Int | updateMediaCacheCleanDays | init 清理 | 否 | 正常 |
| `enable_background_greeting` / `tool_auth_*`（7 项） | Bool | VM | VM / Worker / PerceptionServer | 否 | 正常 |
| `enable_graph_memory` / `enable_voice_emotion_perception` / `enable_adult_content` | Bool | VM/设置页 | VM | 否 | 正常 |
| `mcp_tool_whitelist` | StringSet | updateMcpToolAuthorization | McpManager | 否 | 正常 |
| `mcp_server_configs` | String | McpConfigStorage | 同 | 否 | 正常 |
| `enable_memory_consolidation` / `memory_consolidation_trigger_count` | Bool/Int | MemorySettingsLayout 直写 | VM | 否 | 正常（UI 直写与 VM 读并存） |
| `world_info_*`（7 项） | 混合 | WorldInfoConfigStorage | 同 | 否 | 正常 |
| `theme_mode/user_name/app_language/user_bubble_color/current_session_id/draft_*` | 混合 | VM | VM | 否 | 正常 |
| `use_real_location/mock_location/sim_watch_connected/sim_watch_moving/google_activity_state(+time)` | 混合 | 感知层 | 感知层 | 弱 | 正常 |
| `multimodal_templates_json` | String | fetchTemplatesFromNetwork | 模板加载 | 否 | 正常 |

## 4. Settings 闭环表（高风险项）

| 设置 | UI 入口 | State | Storage | Runtime Reader | 生效时机 | 结论 |
|---|---|---|---|---|---|---|
| isEnabled（ApiConfig） | **无任何 UI 控件**（AddOrEditSheet 恒 true） | ApiConfig 字段 | vault | **运行时无人读**（仅 ChatScreen ModelSelector UI 过滤） | — | **P1-04 dead setting：UI 不可改、运行时不校验** |
| enableSearch | AddOrEditSheet | ApiConfig 字段 | vault | payload 双字段 + 工具过滤 + prompt 注入 | 下轮请求 | P2-01 双字段写入 |
| useIndependentSearch + searchProvider/searchApiUrl/searchApiKey | **无 UI 写入**（编辑时原样保留旧值） | ApiConfig 字段 | vault | VM webSearchProvider 回落分支 | 即时 | **P2-04 dead setting（只读不写）** |
| enableReasoning / enableSmartRouting | AddOrEditSheet | ApiConfig 字段 | vault | resolveTargetModel（DeepSeek only） | 下轮请求 | 正常（Provider 语义应由 Adapter 承担） |
| visionModelName | MultimodalSettings | VM State | prefs | Vision 路由；**Caption 不读** | 下轮请求 | P1-06 |
| stt/tts/imageGen config+model | MultimodalSettings | VM State | prefs | resolve*Config | 即时 | P1-07 |
| memory 模型 | MemorySettingsLayout 直写 prefs | 无 VM State | prefs | VM 压缩 ×2 / Worker | 即时 | 正常但绕过 VM |
| 全局搜索 Key | GlobalSearchConfigCard 直写 prefs | Compose local | prefs | VM webSearchProvider | 即时 | P2-03 明文 |
| ttsProviderTemplate=Auto | MultimodalSettings | VM State | prefs | UI 预测 + LlmClient 自行判定 | 下轮合成 | UI 预测与运行时判定逻辑重复（轻微） |

## 5. 文件覆盖表

| 文件 | 完整阅读 | 主要职责 | 配置/运行时相关 | 发现 |
|---|---|---|---|---|
| ui/chat/LlmClient.kt | 是 | 全部 LLM HTTP/SSE/TTS/STT/生图/搜索 | 是 | P0-04, P1-01~03, P1-05, P1-09, P2-01/02 |
| ui/chat/ChatViewModel.kt | 是 | 路由、状态、多模态编排 | 是 | P0-02, P0-03, P1-04~08, P1-12 |
| ui/settings/SettingsScreen.kt | 是 | ApiConfig 定义 + 全部设置 UI | 是 | P0-03, P1-04, P2-03/04 |
| storage/ApiConfigVault.kt | 是 | Key 加密存储 | 是 | **P0-01** |
| worker/GreetingWorker.kt | 是 | 后台问候 | 是 | P1-11 |
| worker/MemoryConsolidationWorker.kt | 是 | 记忆提炼 | 是 | P1-11 |
| ui/chat/ChatStorageManager.kt | 是 | 会话/角色存储 | 弱 | 正常 |
| ui/chat/PromptAssembler.kt | 是 | Prompt 拼装 | 弱 | 正常 |
| ui/chat/LlmConversationBuilder.kt | 是 | 消息序列化 + 预算裁剪 | 弱 | 正常 |
| ui/chat/Message.kt / TavernCardParser.kt / WorldInfoConfig.kt / WorldInfoBridge.kt | 是 | 数据模型 | 弱 | 正常 |
| ui/chat/VoiceTextExtractor.kt / TokenEstimator.kt / ThinkingTimer.kt / LlmRequestCanonicalizer.kt / MessageTimeFormatter.kt / ConversationTimelineFormatter.kt / ChatInputUiLogic.kt | 是 | 纯工具 | 否 | 正常 |
| ui/settings/ModelCatalogClient.kt | 是 | GET /models | 是（网络） | 正常 |
| mcp/*（5 文件） | 是 | MCP 客户端/服务端 | 弱（非 LLM） | 正常 |
| perception/*（14 文件） | 是 | 传感器/天气/图谱记忆 | 弱 | 正常 |
| health/*（9 文件） | 是 | 健康数据源 | 弱 | 正常 |
| bluetooth/WatchBluetoothClient.kt | 定点（协议状态机）+ grep | 手表 BT | 否 | 正常 |
| storage/RebuildStorageMigrator.kt / CharacterDocumentStore.kt / worldinfo/*（3 文件） | 是（Library 逐行，BookJson/Models 序列化抽查） | 存储迁移 | 弱 | 正常 |
| MainActivity.kt / HealthRationaleActivity.kt | 是 | 导航/权限/Worker 自愈 | 是 | P2-10 |
| ui/chat/ChatScreen.kt | 定点（ModelSelector 等配置触点）+ grep 全文 | 聊天 UI | 弱 | UI 过滤 isEnabled |
| ui/main/MainScreen.kt / TavernScreen.kt / ui/settings/WorldInfoLibraryScreen.kt / MarkdownText.kt / HtmlPanel.kt / ThinkingAndMcpComponents.kt / ui/theme/* | grep 验证 + 定点 | 纯展示 | 否 | 无存储/网络交互 |

## 6. 风险清单（分级）

### P0（配置丢失 / 隐私 / 数据路由改变）

| ID | 描述 | 位置 |
|---|---|---|
| P0-01 | Vault 迁移非原子：`migrated=true` 先置位；securePrefs 失败返回 null 后**仍无条件删除明文**；`apply()` 不验证写入结果；saveJson 可空链静默失败 | ApiConfigVault.kt:29-48 |
| P0-02 | 配置 JSON 解析失败 → `emptyList()` → 启动即用默认三配置**写回 vault 覆盖**，无 .corrupt 备份（对比：ChatStorageManager 有 backupCorruptFile） | ChatViewModel.kt:443-448 |
| P0-03 | 删除 ApiConfig 不清理 vision/stt/tts/imageGen/memory 绑定 → 悬空 ID → `?: activeApiConfig` 把用户内容静默发给另一 Provider（TTS 文本、生图 prompt、图片） | SettingsScreen.kt:1001-1008 + ChatViewModel resolve* |
| P0-04 | TTS 请求体全文（含用户朗读文本、音色）进 logcat，release 同样输出 | LlmClient.kt:1665 |

### P1（核心功能错误 / 设置不生效 / 前后台分裂）

| ID | 描述 |
|---|---|
| P1-01 | `streamStarted` 由 `data:` 前缀置位：SSE error JSON 被当作语义流已开始，阻断降级 |
| P1-02 | HTTP 400/422/501 明确 unsupported-stream 不降级（仅 HTTP 200 非 SSE 分支判定，且判定依赖 `message.contains("stream"/"流式")` 字符串） |
| P1-03 | capability cache：Empty/Unrecognized 触发 fallback 时**降级前**写 `nonStreamOnlyKeys`；key=endpoint\|model 跨配置污染；per-LlmClient 实例不共享 |
| P1-04 | isEnabled：UI 无写入口、运行时全链路不校验（Spec §6.1 落空） |
| P1-05 | 旧 `sendChatCompletion()` 第三套 HTTP：无重试、无 MiMo api-key 头（记忆提炼走 MiMo 必 401）、无 stream 字段、parser 行为不同 → 后台/前台分裂 |
| P1-06 | Vision：`visionConfigId`+`visionModelName` 双事实来源；gpt-4o-mini 遗留值启发式；Caption 与 Vision Chat 解析不同源 |
| P1-07 | STT 扫描第一张 MiMo 卡；TTS/生图/Memory 删配置后静默回落 active（跨 Provider 数据路由改变） |
| P1-08 | 错误分类缺失：业务/降级判断依赖 `message.contains("流式")` 字符串 |
| P1-09 | streaming 与 non-stream 共享 60s read timeout，慢 reasoning 整包误超时 |
| P1-10 | Worker 直读 vault/prefs 绕过 Repository；`?: firstOrNull()`；不查 isEnabled；各自 new LlmClient（P1-11 编号合并） |
| P1-11 | Anthropic 配置加载时被静默过滤出 UI 列表；后续任意设置保存会把过滤后的列表**写回 vault** → 用户 Anthropic 配置永久丢失 |
| P1-12 | DeepSeek 模型名启动时强制改写（deepseek-chat→v4-flash 等），覆盖用户显式选择 |

### P2（边界 / 维护风险 / 静默失败）

| ID | 描述 |
|---|---|
| P2-01 | `web_search` + `enable_search` 双字段同时发送（除非 MiMo），OpenAI 兼容≠支持任一扩展 |
| P2-02 | stream_options 仅 DeepSeek/OpenAI 硬编码（应属 Adapter） |
| P2-03 | 全局搜索 Key 明文存 loyea_prefs |
| P2-04 | `useIndependentSearch`+search* 字段只读不写（dead setting） |
| P2-05 | hasTtsCapability/hasImageGenCapability 扫描任意 MiMo 卡（含未配 Key） |
| P2-06 | MainActivity.onStop 无条件 stopResponse：切后台/锁屏终止生成（半截内容保留，但体验突兀；疑为节流设计，保留现状记录） |
| P2-07 | MiMo gpt-4o-mini→mimo-v2.5-pro 字符串自愈（Spec §27 禁止新增，存量记录） |
| P2-08 | UI 显示过滤器（ChatScreen）与设置页全量列表不一致（合理但应文档化） |

### P3（记录不处理）

- PromptAssembler 中英双份能力块模板维护成本高。
- `deriveStateOf.activeApiConfig` 悬空回落硬编码 Default（建议 ConfigurationMissing 显式化，随 Phase 3 Resolver 一并解决）。
- MultimodalSettings 非响应式读 VM 状态（进入页面重组合，无实际问题）。

## 7. 历史回归门禁核对（Spec §24）

| 历史 Bug | 现状 | 门禁计划 |
|---|---|---|
| Vision 被默认 gpt-4o-mini 覆盖 | 396 行启发式缓解（字符串判断，仍属 §27 禁止模式） | Phase 3 ChannelBinding 回归测试 |
| 智谱视觉 capability 缺失 | providerSupportsVision 白名单 | Adapter capability + 回归 |
| 带图降级未真正重试 | 已修（degradedRetryPending + continue，1696 行注释为证） | 状态机测试覆盖 |
| 纯图片空 text 块 | 已修（883 行守卫） | 网络矩阵测试 |
| EXIF 方向 | 已修（applyExifRotation） | 保留 |
| 1280px 分辨率 | 已修（789 行） | 保留 |
| STT 转义引号截断 | 已修（VoiceTextExtractor 正规解析优先） | 保留既有测试 |
| 会话末尾落位 | selectSession 清空+代际守卫 | 保留 |
| 小马 200 error JSON / 裸字符串 / 零内容 / finish_reason | interpretNonSseBody + 终态 Error 事件 | 网络矩阵重点 |
