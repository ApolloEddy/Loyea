# Loyea 配置路由重构迁移说明

- 关联规范：`docs/Loyea_API_Routing_Config_Audit_Refactor_Spec_v1.0.md`
- 关联审计：`docs/audits/LOYEA_RUNTIME_CONFIG_AUDIT.md`
- 代码基线：v0.8.2（612b430）→ 本轮重构（2026-09-10）
- 适用版本：v0.8.x 及更早的升级用户；无需任何手动操作

---

## 1. 主链路变化

```text
旧：各功能散装读取 vision_config_id / stt_config_id / find ?: activeConfig 兜底
新：Feature → ChannelBindingResolver → ApiConfigRepository → ProviderAdapter
    → UnifiedChatTransport → 统一响应/错误
```

- Chat Completion 全应用只有一个 HTTP 实现：`com.loyea.llm.UnifiedChatTransport`。
  旧 `sendChatCompletion()`（Memory/压缩等后台在用的第三套实现）已迁入，补齐了
  MiMo api-key 头、429/5xx 退避重试与 180s 非流式读超时。
- Provider 身份与协议能力分离：`ProviderAdapters.forProvider(provider)` 提供
  OpenAI 兼容基础档 + DeepSeek / MiMo / Zhipu 档位；URL/鉴权/stream_options/
  native search payload 一律由 Adapter 决定。

## 2. 用户数据迁移（自动、一次性、幂等）

| 数据 | 迁移行为 | 用户可见变化 |
|---|---|---|
| 加密库迁移（ApiConfigVault） | 改为原子序列：commit 写入 → read-back 校验 → 确认删除；任一步失败保留明文 legacy，绝不丢配置 | 无（更安全） |
| `vision_model_name = "gpt-4o-mini"`（出厂默认值未改过） | 一次性清空 = 「跟随连接默认模型」；用户显式改过的值原样保留 | 选卡后识图模型跟随该卡自带模型，不再被 gpt-4o-mini 覆盖 |
| STT 未显式配置 | 存量用户回填一条显式 STT 绑定（首个可用 MiMo 配置） | 行为不变；此后删除该配置 = STT 明确不可用（不再偷偷换） |
| TTS / 生图未显式配置 | 回填当时的激活配置为显式绑定 | 行为不变；此后删除绑定配置 = 明确不可用并提示 |
| `memory_api_config_id` | 解释为 MEMORY 通道绑定；空 = 继承 CHAT（规则只在 Resolver 一处） | 无 |
| 删除 API 配置 | 反查并清理所有引用该配置的通道绑定 | 删除后相关能力明确提示未配置，绝不静默改发其它 Provider |

## 3. 流式行为变化（小马 / New API 场景）

| 场景 | 旧行为 | 新行为 |
|---|---|---|
| HTTP 200 回整包 JSON | 成功（不学习） | 成功 + 学习该配置 prefer non-stream（下次直达整包） |
| HTTP 400/422/501 结构化「不支持流式」 | 报参数错误，不降级 | AUTO 模式自动降级整包并重试 |
| SSE `data: {"error":...}` | 被当作流已开始，吞掉 | 零语义输出时可降级；已有正文时保留正文 + 中断提示 |
| HTTP 200 空响应体 | 触发降级重试（并在降级前写缓存） | 直接报「空回复」错误，不降级、不写缓存 |
| HTTP 200 HTML / 损坏 JSON | 触发降级重试（同上） | 直接报格式错误，不降级、不写缓存 |
| 流中途断开（无 [DONE]、无 finish_reason） | 当作正常结束 | 报「生成中断」，保留已生成内容，不整轮重发 |
| 降级失败 | 缓存已被污染 | 不写能力缓存，下次仍正常探测 |
| 能力缓存键 | endpoint\|model（跨配置污染） | configId+model+adapterId；配置 URL/Key/模型变更即失效 |

新增用户设置（编辑 API 连接底部）：
- **启用此连接**：关闭后聊天与所有后台任务都不会使用该配置（此前无 UI 且运行时不校验）。
- **流式传输模式**：AUTO（默认，自动降级）/ STREAM（强制流式，不支持即报错）/
  NON-STREAM（强制整包）。

## 4. Payload 方言收敛（可能需要留意的点）

- `web_search` + `enable_search` 双字段同时发送的行为已退役。
  现在只有智谱（Zhipu）档位在开启联网搜索时写入官方 `enable_search` 字段；
  其余 Provider（含各类中转）默认不写任何 native search 扩展字段。
  如某中转依赖 `web_search` 字段实现联网，请反馈后按 Adapter 档位显式添加。
- `stream_options.include_usage` 仅 DeepSeek 档位发送（OpenAI 官方亦支持，
  如需为 OpenAI 档位开启请反馈）。
- MiMo 聊天请求继续补发 `api-key` 头（现由 MiMo Adapter 统一声明）。

## 5. 已知未修事项（记录于审计报告）

- 全局搜索 API Key 仍明文存于 loyea_prefs（P2-03）：迁移到加密库涉及存储结构变化，
  留待独立变更；该 Key 仅用于 Tavily 搜索。
- 启动时 DeepSeek 旧模型名（deepseek-chat/reasoner）静默升级为 v4 系列的行为保留
  （v0.8.x 产品决策）；如用户显式需要旧模型名会覆盖，属已知取舍。
- ApiConfig 中 `useIndependentSearch`/`searchProvider/searchApiUrl/searchApiKey`
  为兼容保留的只读字段（无 UI 写入口），按 Spec §16.4 允许过渡期共存。

## 6. 测试与验证

- JVM：`./gradlew :app:testDebugUnitTest`（Vault 故障注入 7 场景、网络状态机矩阵
  24 项、ChannelBinding 矩阵 13 项 + 既有回归全部通过）
- 构建：debug + release 双构建通过
- 实机：模拟器 smoke（主聊天 / 非流式降级 / STT-TTS 可配置面）结果见最终开发报告
