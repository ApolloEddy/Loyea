# Loyea API 路由、配置通道与全功能代码审计重构规范

版本：v1.0  
修订日期：2026-09-09  
代码基线：`ApolloEddy/Loyea`，`main`，审计时 HEAD `612b430ca15f59e37397d2baf9576ace674520f4`（v0.8.2）  
适用范围：Android App 中 API 配置、模型路由、LLM/多模态调用、后台调用、持久化、设置项与相关运行时状态  
主要目标：修复小马 / New API 流式兼容问题，并对 Loyea 现有功能、设置与配置进行一次代码级闭环审计，降低可预见的跨层 Bug

---

## 1. 范围、目标与设计决定

本规范不是“小马 API 不支持流式”的单点修复说明。

近期连续出现的视觉模型覆盖、带图降级未真正重试、纯图片空文本块、MiMo 鉴权差异、小马非 SSE / 裸字符串响应、API Key 加密迁移漏改等问题，说明 Loyea 已经从“单一聊天 API”演化为多能力、多通道、多 Provider 的应用，但配置模型和请求层仍保留较强的早期单 API 假设。

本次工作同时包含两部分：

1. **修复已经确认的 API Routing / Storage 问题。**
2. **对现有生产代码进行全功能、全设置、全配置审计，主动寻找尚未实机暴露、但从代码结构已经可以预见的问题。**

本规范做出以下设计决定：

- 不推倒重写 Loyea，不重做现有 UI，不借机扩大成插件框架。
- 保留现有 `ApiConfig` 用户数据，并通过兼容迁移逐步收窄其职责。
- 新增统一的 `ChannelBinding`，解决“配置卡 ID 与模型名分散保存、互相残留”的问题。
- 引入轻量 `ProviderAdapter` / `ProtocolProfile`，将 Provider 品牌与协议能力分开。
- Chat Completion 最终只能存在一套实际 HTTP Transport。
- 流式兼容使用明确状态机，不再通过继续叠加特殊响应格式分支维护。
- 后台 Worker、前台聊天、记忆、视觉等必须使用相同的配置解析与 Transport。
- 所有自动 fallback 都必须有明确的数据路由边界；不得因为配置失效就把用户内容静默发送到另一个 Provider。
- 审计以当前代码为事实来源；README、CHANGELOG、commit message 和本规范只能作为线索。
- 测试“全绿”不是完成条件；关键路径必须有网络级状态机测试和配置闭环测试。

本规范不要求：

- 重写聊天 UI。
- 引入大型 DI 框架。
- 建立通用插件市场或通用 Provider DSL。
- 一次性统一所有非 LLM 网络客户端。
- 为所有第三方中转实现无限兼容。
- 对当前完全没有真实使用路径的未来能力做预设计。

---

## 2. 当前代码核对结果与证据边界

### 2.1 已确认的当前基线

审计时最新 release commit 为：

`612b430ca15f59e37397d2baf9576ace674520f4`

近期与本规范直接相关的提交包括：

| Commit | 已声明修复内容 | 本规范判断 |
|---|---|---|
| `ff71171` | 非 SSE、空回复、`data:` 无空格、截断提示 | 修复方向正确，但不是完整协议状态机 |
| `994c27d` | 流式不支持时自动降级非流式 | 当前降级条件仍不完整 |
| `3fea03c` | 裸 JSON 字符串、非流式快速路径 | parser 增强有效，但 capability 学习策略有风险 |
| `c44d602` | 视觉模型默认值覆盖、智谱能力识别 | 暴露“configId 与 modelName 分离保存”的结构问题 |
| `d54bf8f` | 视觉降级分支未真正重试 | 暴露控制流测试不足 |
| `b307ce2` | 纯图片空文本块 | 暴露 provider payload 契约不足 |
| `7cd7edf` | 识图 1280px、EXIF、诊断日志 | 应保留并进入回归测试 |

commit message 只能证明开发者意图，不能证明代码路径已经完整覆盖。本任务必须重新阅读最新代码。

### 2.2 已确认的 ApiConfig 现状

当前 `ApiConfig` 至少包含：

```kotlin
data class ApiConfig(
    val id: String,
    val name: String,
    val provider: String,
    val apiUrl: String,
    val apiKey: String,
    val modelName: String,
    val isEnabled: Boolean,
    val enableSearch: Boolean,
    val enableReasoning: Boolean,
    val enableSmartRouting: Boolean,
    val useIndependentSearch: Boolean,
    val searchProvider: String,
    val searchApiUrl: String,
    val searchApiKey: String
)
```

现结构同时承担：

- 服务连接信息；
- 默认模型；
- Provider 身份；
- 聊天行为策略；
- 搜索策略；
- 一部分协议能力推断。

这不是要求立即删除所有旧字段，而是本次重构需要逐步建立正确职责边界。

### 2.3 已确认的配置通道分散

当前代码已经存在至少以下状态来源：

| 功能 | 当前已观察到的配置来源 |
|---|---|
| 主聊天 | `activeConfigId -> ApiConfig` |
| 视觉 | `visionConfigId + visionModelName` |
| STT | `sttConfigId + sttModelName + sttProviderTemplate` |
| TTS | `ttsConfigId + ttsModelName + ttsProviderTemplate + ttsVoice` |
| 生图 | `imageGenConfigId + imageGenModel` |
| Memory | `memory_api_config_id`，并有 active config fallback |
| Greeting / 后台文本 | 部分直接使用 active config |
| 搜索 | 全局搜索配置 + ApiConfig 内搜索配置并存 |
| 音频直接理解 | 与当前聊天路由和 capability guess 交叉 |

这里的表格是已知入口，不代表完整清单。Agent 必须通过全仓审计补齐。

### 2.4 已确认的关键风险

已确认或从当前实现直接可推出的高风险包括：

1. 流式 fallback 主要集中在 HTTP 200 非 SSE 分支，HTTP 400/422/501 的明确 stream unsupported 可能不会降级。
2. 看到 `data:` 就将 `streamStarted = true`，可能把 SSE error 当作有效流已开始。
3. `Empty` / `Unrecognized` 等异常有机会触发 fallback，并在 fallback 成功前写入 `nonStreamOnlyKeys`。
4. `nonStreamOnlyKeys` 使用 endpoint + model，可能发生不同配置间的能力污染。
5. 非流式 fallback 仍固定发送 `"stream": false`，没有“省略 stream 字段”的协议表达。
6. streaming 与 non-stream 共用较短 read timeout，慢 reasoning 模型整包返回存在误超时风险。
7. 旧 `sendChatCompletion()` 与新的 raw/stream 路径存在重复 HTTP 实现，Memory 等路径可能继续使用旧 parser / header / retry 行为。
8. `ApiConfigVault` 迁移时先设置 `migrated = true`，而 secure storage 初始化失败会返回 null；旧明文之后仍可能被删除，存在配置丢失风险。
9. `isEnabled` 在 UI 与运行时 resolver 之间语义不完全一致。
10. 删除专用 ApiConfig 后，部分通道可能静默 fallback 到 active config。
11. Vision 正常聊天与自动 caption 的模型解析路径可能不完全一致。
12. `provider` 字符串承担身份、header、能力、payload 方言等过多职责。
13. native search 可能同时发送多个非标准字段。
14. TTS 等路径存在记录完整请求正文到日志的隐私风险。

本规范后续给出明确修复方案。

---

## 3. 强制全仓审计协议

### 3.1 审计必须先于重构

Agent 不得拿到本规范后立即修改 `LlmClient.kt`。

在第一处生产代码修改之前，必须创建：

`docs/audits/LOYEA_RUNTIME_CONFIG_AUDIT.md`

并完成首轮只读审计。

除自动生成代码、第三方 vendored 代码和二进制资源外，至少逐文件阅读：

`app/src/main/java/com/loyea/**/*.kt`

同时检查与运行行为有关的：

- `AndroidManifest.xml`
- Gradle 配置
- ProGuard / R8 配置
- Room / DataStore / SharedPreferences 相关代码
- Worker / Service / Receiver
- 网络客户端
- 设置页
- ChatViewModel
- Prompt / Memory / MCP / WorldBook 等会改变模型请求的模块

不能只依赖搜索命中片段。搜索用于定位，完整文件用于理解调用关系。

### 3.2 文件阅读覆盖表

审计文档中必须包含“文件覆盖表”。

格式至少为：

| 文件 | 已完整阅读 | 主要职责 | 与配置/运行时相关 | 发现问题 | 后续动作 |
|---|---:|---|---:|---|---|
| `.../ChatViewModel.kt` | 是 | 会话、路由、运行状态 | 是 | P1-xx | 修改 |
| `.../SomeUi.kt` | 是 | UI | 否/弱 | 无 | 无 |

第一阶段结束时，生产 Kotlin 文件必须做到可解释的全覆盖。

“搜索没有命中”不能替代“已阅读”。

### 3.3 设置项闭环审计

必须从 Settings UI 顶部开始，逐项建立：

`UI 控件 -> State -> 持久化 -> Resolver -> Runtime Consumer -> Request / Side Effect`

每个用户可见设置至少记录：

| 字段 | 含义 |
|---|---|
| 设置名称 | UI 实际名称 |
| UI 文件与控件 | 写入入口 |
| State | Compose State / StateFlow / derivedState |
| Storage Key | 若无则标记 none |
| 默认值 | UI、VM、Storage 各自默认 |
| Runtime Reader | 谁实际读取 |
| 生效时机 | 立即 / 下轮请求 / 重启 |
| 失效条件 | 配置删除、禁用、权限变化等 |
| 测试 | 已有 / 新增 / 缺失 |
| 结论 | 正常 / dead setting / hidden state / 不一致 |

必须主动查找：

- UI 有设置但运行时不读；
- 运行时有参数但 UI 无来源；
- UI 默认值与 VM 默认值不同；
- 同一 key 多处使用不同默认值；
- 保存成功但重启恢复错误；
- 开关关闭后后台仍使用；
- 配置删除后保存着悬空 ID；
- 旧版本 key 仍在写；
- 同一含义同时由多个 key 控制。

### 3.4 SharedPreferences / Secure Storage 全量盘点

全局追踪：

- `getString`
- `putString`
- `getBoolean`
- `putBoolean`
- `getInt`
- `putInt`
- `remove`
- `EncryptedSharedPreferences`
- 任何封装后的 preferences helper

建立 Key Inventory：

| Key | 类型 | 写入方 | 读取方 | 默认值 | 是否敏感 | 是否仍有效 | 迁移策略 |
|---|---|---|---|---|---:|---:|---|

发现以下情况必须报告：

- 只写不读；
- 只读不写；
- 多个写入方语义不同；
- 旧 key 已废弃但仍更新；
- 敏感值明文；
- Worker 绕过新 Repository 直接读历史 key；
- 设置删除后残留无效引用。

### 3.5 网络调用全量盘点

必须列出所有真实网络调用入口，而不是只列 `LlmClient`。

至少记录：

| 功能 | 调用函数 | URL 构造 | Header | Payload 方言 | Parser | Retry | Timeout |
|---|---|---|---|---|---|---|---|

所有直接出现以下行为的位置都应追踪：

- `Request.Builder`
- `OkHttpClient`
- `.execute()`
- `.enqueue()`
- Retrofit（若有）
- URL 拼接
- `Authorization`
- `api-key`
- request body JSON

如果 Chat Completion 在一个以上位置独立构造 HTTP 请求，必须列为重构对象。

---

## 4. 目标职责模型

本次重构后的主链路固定为：

```text
Feature
  ↓
ChannelBindingResolver
  ↓
ApiConfigRepository
  ↓
ProviderAdapter
  ↓
ResolvedRequest
  ↓
Unified Transport
  ↓
Unified Response / Error
  ↓
Feature Consumer
```

各层职责如下：

| 层 | 只负责 | 不负责 |
|---|---|---|
| Feature | 业务意图、消息、图片、工具等 | 拼 URL、猜 Header |
| ChannelBindingResolver | 选择 config + model | 发网络请求 |
| ApiConfigRepository | 配置存取、启用状态、迁移 | Provider 协议判断 |
| ProviderAdapter | 协议方言、能力、URL/Header/Payload | 用户设置 UI |
| Unified Transport | HTTP、retry、timeout、stream 状态机 | 决定用哪个业务通道 |
| Feature Consumer | 消费 content/tool/usage/error | 重新 parse 原始响应 |

---

## 5. ApiConfig 与 ChannelBinding

### 5.1 ApiConfig 的目标职责

`ApiConfig` 表示“如何连接一个模型服务账户/中转”。

目标结构：

```kotlin
data class ApiConfig(
    val id: String,
    val name: String,
    val providerPreset: String,
    val protocolProfile: ProtocolProfile,
    val apiUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val isEnabled: Boolean = true,
    val streamMode: StreamMode = StreamMode.AUTO
)
```

`providerPreset` 是 UI / 预设身份。

`protocolProfile` 是协议行为。

两者不能再被视为同一个概念。

例如“OpenAI”品牌可以使用 OpenAI 官方协议；“Custom”中转也可能使用 OpenAI-compatible 协议。

### 5.2 兼容旧 ApiConfig

本次不得直接删除当前字段导致旧数据无法恢复。

旧字段中的：

- `enableReasoning`
- `enableSmartRouting`
- `enableSearch`
- `useIndependentSearch`
- `searchProvider`
- `searchApiUrl`
- `searchApiKey`

先通过 migration 进入对应 Feature Policy / Search Settings。

如果为了降低本轮风险，需要暂时保留旧字段：

- 可继续序列化；
- 新运行时代码不得再从多个地方分别读取；
- migration 完成后由统一 Repository 暴露新结构；
- 不允许旧、新两套配置同时成为真实来源。

### 5.3 ChannelBinding

新增：

```kotlin
enum class ChannelId {
    CHAT,
    VISION,
    AUDIO_INPUT,
    STT,
    TTS,
    IMAGE_GENERATION,
    MEMORY
}

data class ChannelBinding(
    val channel: ChannelId,
    val configId: String?,
    val modelOverride: String? = null
)
```

语义固定：

- `configId == null`：该通道未独立绑定；是否继承由该 Channel 的明确规则决定。
- `modelOverride == null`：使用 `ApiConfig.defaultModel`。
- 不允许用某个具体模型字符串表达“默认值”。

### 5.4 各功能的继承规则

本版直接确定：

| 功能 | 使用通道 | 无独立 Binding 时 |
|---|---|---|
| 普通聊天 | `CHAT` | 必须存在可用 CHAT |
| Vision 聊天 | `VISION` | 可继承 CHAT，但必须先验证 CHAT adapter/model 支持 vision |
| 自动图片 Caption | `VISION` | 与 Vision 聊天完全同源 |
| 音频直接理解 | `AUDIO_INPUT` | 可继承 CHAT，但必须验证 audio-input capability |
| STT | `STT` | 不得偷偷使用任意 CHAT；未配置则明确不可用 |
| TTS | `TTS` | 不得偷偷使用任意 CHAT；未配置则明确不可用 |
| 生图 | `IMAGE_GENERATION` | 未配置则明确不可用 |
| Memory | `MEMORY` | 可按产品现状允许继承 CHAT，但该规则只能在 Resolver 中存在一次 |
| Greeting | `CHAT` | 使用 CHAT，不单独建立隐藏配置通道 |
| 标题/摘要类聊天辅助 | `CHAT` 或 `MEMORY` | 必须在代码中统一指定，不得各自临时找 active config |

STT/TTS/Image 不允许“找不到绑定 -> 拿 active DeepSeek 试一下”。

### 5.5 ResolvedChannel

所有调用在进入 Transport 前必须得到不可歧义的结果：

```kotlin
data class ResolvedChannel(
    val channel: ChannelId,
    val config: ApiConfig,
    val model: String,
    val adapter: ProviderAdapter
)
```

Resolver 负责：

1. 找 Binding。
2. 应用继承规则。
3. 查 ApiConfig。
4. 验证 `isEnabled`。
5. 解析最终 model。
6. 获取 adapter。
7. 验证能力。

失败时返回结构化 Configuration Error，不得到网络层再猜。

---

## 6. isEnabled 与配置删除语义

### 6.1 isEnabled

统一规定：

`isEnabled = false` 表示该配置不得被任何新的普通运行任务使用。

包括：

- 主聊天；
- Vision；
- STT；
- TTS；
- Image；
- Memory；
- Greeting；
- Worker；
- 自动 Provider 选择。

UI 隐藏或过滤不等于运行时禁用。

Resolver 必须再次验证。

### 6.2 删除 ApiConfig

删除配置前必须查询反向引用。

推荐 Repository 提供：

```kotlin
fun bindingsReferencing(configId: String): List<ChannelBinding>
```

删除后的规则：

- 所有引用该 config 的 Binding 必须同步清理或进入 `UNCONFIGURED`。
- 不允许保留 dangling ID。
- 不允许在真正发请求时才 fallback。
- UI 应能显示“该能力未配置”。

如果产品决定某通道允许继承 CHAT，只能在 Binding 被清理后由 Resolver 的固定继承规则接管，而不是 `find ?: activeConfig` 的局部兜底。

---

## 7. ProviderAdapter / ProtocolProfile

### 7.1 目的

`provider == "OpenAI"` 不再同时意味着：

- 某种 URL；
- Bearer Header；
- 支持 `stream_options`；
- 支持 tools；
- 支持 web search；
- 支持 vision；
- 支持 audio。

这些能力必须由 Adapter 决定。

### 7.2 最小接口

推荐：

```kotlin
interface ProviderAdapter {
    val id: String

    fun resolveChatUrl(config: ApiConfig): String
    fun buildAuthHeaders(config: ApiConfig): Map<String, String>

    fun chatCapabilities(
        config: ApiConfig,
        model: String
    ): ChatCapabilities

    fun buildChatPayload(
        request: ChatRequest,
        mode: RequestResponseMode
    ): JsonObject

    fun classifyError(
        httpCode: Int?,
        body: String?
    ): LlmErrorKind
}
```

能力结构至少包含：

```kotlin
data class ChatCapabilities(
    val streaming: CapabilityState,
    val tools: CapabilityState,
    val nativeSearch: CapabilityState,
    val vision: CapabilityState,
    val audioInput: CapabilityState,
    val streamOptions: CapabilityState
)
```

`CapabilityState` 建议：

```kotlin
enum class CapabilityState {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}
```

UNKNOWN 不等于 UNSUPPORTED。

### 7.3 首批 Profile

Agent 必须根据当前仓库实际 Provider 预设核对后建立 Profile。

至少预计需要：

- OpenAI / OpenAI-compatible
- DeepSeek
- MiMo
- Zhipu
- Custom OpenAI-compatible

如果代码中还有 Anthropic、Gemini 等真实独立协议实现，必须按实际代码补充。

如果当前所谓 Anthropic 只是把兼容中转当 OpenAI Chat Completion 使用，则不得仅凭名称虚构 Anthropic Messages API Adapter；应在审计报告中明确事实。

### 7.4 Custom API

Custom API 设置必须能表达“它使用哪种兼容协议”。

最低方案是在 Custom 配置中选择 compatibility profile。

不要让 `provider = Custom` 变成“所有 capability 都靠猜”。

---

## 8. 流式与非流式请求状态机

### 8.1 用户级模式

```kotlin
enum class StreamMode {
    AUTO,
    STREAM,
    NON_STREAM
}
```

语义：

- `AUTO`：优先流式，确证不支持时安全降级。
- `STREAM`：强制流式；不支持时直接报错，不静默改变用户选择。
- `NON_STREAM`：直接整包请求。

默认 `AUTO`。

### 8.2 内部非流式表达

Adapter 内部还应区分：

```kotlin
enum class NonStreamEncoding {
    STREAM_FALSE,
    OMIT_STREAM_FIELD
}
```

这不是用户设置。

不同协议可声明默认表达。

### 8.3 AUTO 状态机

必须按以下顺序实现：

```text
Resolve Channel
  ↓
Validate Capability
  ↓
Attempt STREAM
  ↓
┌──────────────────────────────────────────────┐
│ 标准 SSE content/thought/tool → 正常继续    │
│ 标准 SSE [DONE] → 成功                       │
│ HTTP 200 full completion → 直接作为成功整包 │
│ Explicit UnsupportedStreaming → fallback    │
│ HTML/Empty/Malformed → error，不学习能力    │
│ 其他 4xx/5xx → 按错误类型处理               │
└──────────────────────────────────────────────┘
  ↓
若 Explicit UnsupportedStreaming 且尚未产生有效输出
  ↓
Attempt NON_STREAM
  ↓
成功
  ↓
记录 runtime capability：该 config+model 本进程优先 non-stream
```

### 8.4 必须拆分的流状态

当前类似 `streamStarted` 的单 Boolean 不足。

至少需要：

```kotlin
var sseTransportObserved = false
var semanticOutputObserved = false
```

以下事件可使 `semanticOutputObserved = true`：

- 非空 content；
- 非空 thoughts / reasoning；
- 有效 tool call；
- 其他实际会被上层消费的模型输出。

仅看到：

`data:`

空 delta

usage

error

不能视为已经产生用户可见语义输出。

如果已经产生 semantic output 后连接中断：

- 不自动从头 fallback 重发；
- 保留已有内容；
- 给出“生成中断”的结构化错误；
- 避免重复回复或重复工具调用。

### 8.5 Explicit UnsupportedStreaming 判定

必须通过 `ErrorClassifier` 得到结构化类型：

`UnsupportedStreaming`

允许来源：

- HTTP 4xx/5xx 的结构化 error；
- HTTP 200 error JSON；
- SSE `data: {"error": ...}`；
- Adapter 已知的明确错误码。

不得仅凭：

- body 为空；
- HTML；
- JSON parse 失败；
- 网络 timeout；
- connection reset；

判断“不支持流式”。

### 8.6 HTTP 200 full completion

若请求 `stream=true`，服务端返回标准完整 `choices[].message` JSON：

- 不需要再请求一次；
- 直接视为本轮成功；
- 可将该 route 的 runtime capability 记为 prefer non-stream。

这是“成功的非 SSE 响应”，与 Empty/HTML 不同。

---

## 9. Runtime Capability Cache

### 9.1 缓存用途

缓存只用于减少同一进程内重复试错。

不能持久化成用户配置事实。

### 9.2 Key

推荐：

```kotlin
data class CapabilityCacheKey(
    val configId: String,
    val model: String,
    val adapterId: String
)
```

ApiConfig 修改以下字段时必须清除该 config 的缓存：

- URL；
- Key；
- Provider/Profile；
- default model；
- stream mode。

无需把 API Key 明文或 hash 打入日志。

### 9.3 写入条件

允许写入“prefer non-stream”只有两种情况：

1. `stream=true` 请求直接成功返回完整 completion；
2. 明确得到 `UnsupportedStreaming`，随后 non-stream 请求成功。

禁止在 fallback 之前写。

禁止因为：

- Empty；
- HTML；
- malformed JSON；
- timeout；

写 capability cache。

---

## 10. 统一 Chat Completion Transport

### 10.1 禁止重复 HTTP 实现

重构后：

- `sendChatCompletion`
- `sendChatCompletionStream`
- Memory
- Greeting
- Worker
- Caption
- 其他文本辅助任务

可以有不同业务包装函数，但 Chat Completion 的实际 HTTP 请求只能进入一个 Transport。

推荐：

```kotlin
interface LlmTransport {
    fun executeChat(
        request: ResolvedChatRequest
    ): Flow<TransportEvent>

    suspend fun executeChatOnce(
        request: ResolvedChatRequest
    ): LlmResponse
}
```

`executeChatOnce()` 也应复用同一底层 request builder、adapter、error classifier 和 parser。

不能复制一份 HTTP 逻辑。

### 10.2 Transport 负责

- OkHttp request；
- timeout；
- retry；
- streaming parser；
- full-body parser；
- error classification；
- usage；
- cancellation；
- capability cache。

### 10.3 Transport 不负责

- active config 选择；
- Vision config 选择；
- memory config 选择；
- Soul / Lorebook；
- 是否该调用 MCP；
- UI Toast；
- SharedPreferences。

---

## 11. Timeout 与 Retry

### 11.1 分离策略

至少拆分：

- streaming read policy；
- non-stream read policy。

推荐原则：

Streaming：

- connect/write 保持现有保守值；
- read timeout 可较长或允许持续 chunk；
- 一旦有 semantic output，不做整轮自动重试。

Non-stream：

- reasoning model 允许明显长于当前 60 秒；
- 推荐 180 秒作为首版保守上限；
- 必须支持 coroutine / call cancellation；
- 不使用无限 timeout。

180 秒是工程默认，不是模型 SLA；若当前应用已有用户可配置 timeout，则优先接入已有配置。

### 11.2 Retry

允许自动 retry：

- 429；
- 明确可恢复的 5xx；
- 尚未产生 semantic output 的瞬时网络错误。

禁止自动 retry：

- 401/403；
- 参数错误；
- capability mismatch（应走明确 fallback）；
- 已执行 tool call 后的整轮重发；
- 已向用户输出部分正文后的整轮重发。

---

## 12. Unified Response Parser 与 Error Model

### 12.1 Parser

必须保留近期新增的兼容能力：

- 标准 SSE；
- `data:{...}`；
- 标准 full completion；
- 顶层 JSON string；
- `choices[0].message` 为 string 的网关变体。

但 parser 不应决定业务 fallback。

Parser 负责“这是什么”。

State Machine 负责“接下来做什么”。

### 12.2 错误类型

新增统一内部错误：

```kotlin
sealed interface LlmErrorKind {
    data object Authentication : LlmErrorKind
    data object RateLimit : LlmErrorKind
    data object Quota : LlmErrorKind
    data object InvalidRequest : LlmErrorKind
    data object UnsupportedStreaming : LlmErrorKind
    data object UnsupportedTools : LlmErrorKind
    data object UnsupportedSearch : LlmErrorKind
    data object UnsupportedModality : LlmErrorKind
    data object Timeout : LlmErrorKind
    data object Network : LlmErrorKind
    data object Server : LlmErrorKind
    data object MalformedResponse : LlmErrorKind
    data object EmptyResponse : LlmErrorKind
    data object ConfigurationMissing : LlmErrorKind
    data object ConfigurationDisabled : LlmErrorKind
    data object StorageFailure : LlmErrorKind
    data object Unknown : LlmErrorKind
}
```

UI 可继续显示中文自然语言。

业务逻辑不得依赖：

`message.contains("流式")`

这类字符串判断作为唯一机制。

Adapter 可以用 provider error code + message 辅助分类。

---

## 13. ApiConfigVault P0 修复

### 13.1 当前风险

当前迁移流程中：

- `migrated = true` 在迁移成功前设置；
- `securePrefs()` 失败会返回 null；
- legacy 清理不以 encrypted write 成功为前提；
- `saveJson()` 使用可空链静默失败。

这可能导致：

“新加密库没写进去，但旧明文已经被删除”。

### 13.2 新迁移流程

必须改为：

```text
load secure prefs
  ↓
失败 → 返回 StorageFailure；保留 legacy；不标记 migrated
  ↓
读取 legacy
  ↓
无 legacy → 标记本进程 migration checked
  ↓
有 legacy
  ↓
同步写 secure storage
  ↓
写失败 → 保留 legacy；不标记完成
  ↓
read-back verify
  ↓
不一致 → 保留 legacy；不标记完成
  ↓
删除 legacy
  ↓
确认删除
  ↓
标记 migration complete
```

迁移阶段优先使用能够返回成功状态的 `commit()`，不要依赖 `apply()` 后立即假设成功。

### 13.3 API

推荐：

```kotlin
sealed class VaultResult<out T> {
    data class Success<T>(val value: T) : VaultResult<T>()
    data class Failure(val cause: Throwable?) : VaultResult<Nothing>()
}
```

`saveJson()` 必须返回结果。

设置页保存失败时：

- 不得只更新内存然后假装持久化成功；
- 应给用户可理解提示；
- 保留旧可用数据。

### 13.4 Migration 测试

必须故障注入：

| 场景 | 预期 |
|---|---|
| secure init 失败 | legacy 保留 |
| secure write 失败 | legacy 保留 |
| read-back 不一致 | legacy 保留 |
| legacy 删除失败 | secure 已存在，但迁移状态不得伪报完整 |
| 成功 | secure 存在，legacy 删除 |
| 重复执行 | 幂等 |
| save 失败 | 上层收到失败，不静默 |

---

## 14. Vision / Caption 路径统一

### 14.1 已知根因

近期曾出现：

选中智谱视觉 Config，但全局 `visionModelName = gpt-4o-mini` 覆盖 Config 自带模型。

这种 Bug 的根因不是默认值选错，而是：

`visionConfigId` 与 `visionModelName` 是彼此独立状态。

### 14.2 修复

改为：

```kotlin
ChannelBinding(
    channel = ChannelId.VISION,
    configId = "...",
    modelOverride = null
)
```

用户切换视觉 Config 时：

- 如果 modelOverride 本来为 null，继续使用新 Config.defaultModel；
- 如果 UI 提供显式模型覆盖，则该 override 明确属于当前 Binding；
- 不通过“它是否等于 gpt-4o-mini”猜用户有没有改过。

### 14.3 Caption

自动图片 caption 必须调用同一个：

`resolve(ChannelId.VISION)`

不得自己：

`targetVisionCfg ?: activeApiConfig`

然后绕过 vision model resolution。

### 14.4 回归

必须验证：

- Config A + default model A；
- 切到 Config B；
- 不出现 model A 残留；
- Vision Chat 与 Caption 最终 resolved config/model 完全一致。

---

## 15. STT / TTS / Audio / Image 路径

### 15.1 STT

现有类似“找不到显式 STT -> 找第一个 MiMo -> active config”的行为必须审计并收敛。

首版规则：

- 显式 STT Binding 优先；
- 没有 Binding：显示未配置；
- 如果产品保留“一键使用 MiMo STT”，应在设置时写入 Binding，而不是运行时偷偷扫描第一张 MiMo 卡。

### 15.2 TTS

TTS 使用：

- `ChannelBinding(TTS)`
- TTS 专属 voice / format / speed 等设置

voice 不是 ApiConfig 字段。

### 15.3 Audio Input

直接把音频作为聊天模态时：

- 使用 `AUDIO_INPUT` Binding；
- 未独立设置时可继承 CHAT；
- Resolver 必须验证 adapter + model 支持 audio input；
- 不靠 provider 名称硬猜后直接发。

### 15.4 Image Generation

生图使用独立 Binding。

删除生图 Config 后不得 fallback 到普通聊天模型。

---

## 16. Search 设计收敛

### 16.1 分离两种搜索

明确分成：

1. Native Provider Search
2. Independent Search

两者不能因为布尔值组合错误而同时生效。

### 16.2 Native Search

ProviderAdapter 决定具体 payload。

禁止 Transport 通用层同时写：

```json
{
  "web_search": true,
  "enable_search": true
}
```

除非对应 Adapter 明确要求。

OpenAI-compatible 不代表支持任何一种 native search 扩展字段。

### 16.3 Independent Search

独立搜索产生的结果作为业务层上下文进入 Chat Request。

使用独立搜索时，不再向 Chat Provider 添加 native search 字段。

### 16.4 搜索配置归属

`searchProvider/searchApiUrl/searchApiKey` 应移出 ApiConfig 的连接身份。

它们属于 Search Settings。

迁移期间可保留旧字段，只允许 Repository 做一次兼容读取。

---

## 17. Tools / MCP 能力

### 17.1 显式 capability

Adapter 必须告诉上层 tools capability：

- SUPPORTED
- UNSUPPORTED
- UNKNOWN

### 17.2 UNKNOWN

UNKNOWN 时首版可允许尝试，但：

- 400 后若错误明确为 unsupported tools，可分类；
- 不要把任何 400 都永久学习成“不支持 tools”。

### 17.3 Vision + Tools

当前 Vision 路径可能直接把 tools 清空。

Agent 必须核对这是：

- Provider limitation；
- 历史 workaround；
- 还是当前设计决定。

如果只是 workaround，应由 capability 决定。

如果该模型确实不支持 tools：

- sanitise 历史 tool messages；
- 不发送 tools；
- 行为在 audit 中记录。

### 17.4 Tool round-trip

stream -> non-stream fallback 后必须保留：

- tool_calls；
- tool ids；
- arguments；
- tool result；
- final round。

不能只验证正文。

---

## 18. Fallback 与数据路由安全

全仓所有：

- `?: activeApiConfig`
- `?: defaultConfig`
- `find { ... } ?:`
- catch 后 provider 切换
- 模型自动替换

都必须在审计文档登记。

每个 fallback 必须说明：

1. 为什么允许；
2. fallback 到什么；
3. 是否跨 Provider；
4. 是否改变数据接收方；
5. 用户是否可见；
6. 是否会导致能力不匹配。

原则：

**配置故障不得自动改变用户数据的服务商接收方。**

允许的例子：

VISION 未独立绑定，产品明确规定继承 CHAT，且用户从一开始就知道没有独立视觉 Provider。

不允许的例子：

用户明确绑定某视觉服务，后来该 Config 被删除，程序静默把图片发到另一个 active Provider。

---

## 19. Worker 与后台任务

必须逐个审计所有：

- Worker
- Service
- background coroutine
- scheduled task

特别检查：

- `MemoryConsolidationWorker`
- Greeting 相关 Worker / 任务

要求：

- 不自己读取旧 `api_config_list` 绕过 Repository；
- 不自己实现 active config fallback；
- 不自己拼 Chat Completion URL；
- 不自己使用旧 parser；
- 不忽略 `isEnabled`；
- 与前台共享 ProviderAdapter / Transport。

Worker 运行时若配置缺失：

- 返回可追踪失败；
- 不破坏已有数据；
- 不把失败任务标记为成功；
- 不静默换 Provider。

---

## 20. 日志与隐私

全仓搜索：

- `Log.d`
- `Log.i`
- `Log.w`
- `Log.e`
- `println`

Release 构建禁止记录：

- API Key；
- Authorization；
- 完整用户消息；
- 完整 assistant 消息；
- System Prompt；
- Soul；
- Lorebook / WorldBook 正文；
- 完整 TTS 文本；
- STT 原音频/base64；
- 图片 base64；
- 可能包含完整请求的 raw body。

允许的诊断信息：

- configId 的非敏感短标识；
- provider/profile；
- model；
- endpoint host/path；
- message count；
- image count；
- body size；
- HTTP status；
- response format；
- retry 次数；
- fallback reason；
- token usage。

开发构建如果需要更详细日志，也应使用显式 debug gate，而不是默认输出正文。

---

## 21. 全功能主动审计范围

本轮不仅审 API。

必须检查现有主要功能是否存在“设置与运行时不一致、旧路径绕过新实现、静默失败”等可预见 Bug。

至少包括：

- 新建 / 删除 / 切换会话；
- 历史加载与滚动；
- 消息发送；
- 停止生成；
- retry / regenerate；
- reasoning；
- token usage；
- context trimming；
- Session persistence；
- backup / restore；
- 角色卡；
- Soul；
- Lorebook / WorldBook；
- Prompt assembly；
- Memory；
- MCP；
- Search；
- Vision；
- STT；
- Audio Input；
- TTS；
- Image Generation；
- Greeting；
- API 配置管理；
- 配置加密；
- 旧版本迁移；
- 当前 Settings 中所有可见设置。

### 21.1 审计目标

不是把所有模块重构一遍。

只主动修复：

- 明确错误；
- 高可信 race；
- dead setting；
- hidden state；
- 默认值漂移；
- dangling reference；
- 重复实现；
- 无错误提示的静默失败；
- 配置关闭后仍运行；
- 用户选择与实际请求不一致；
- 可以直接由现结构预见的错误。

大型未来优化记入审计报告，不自行扩大 Scope。

---

## 22. 审计输出的风险分级

统一使用：

### P0

可能导致：

- 用户配置丢失；
- API Key / 私密内容泄露；
- 数据被发送到非用户选择的 Provider；
- 数据不可恢复损坏；
- 明显安全问题。

### P1

会导致：

- 核心功能错误；
- 用户设置不生效；
- 错模型 / 错 Provider；
- 请求稳定失败；
- 后台与前台行为分裂；
- 流式 / 非流式重大兼容问题。

### P2

会导致：

- 边界场景错误；
- 不合理 fallback；
- 诊断困难；
- 可预见维护风险；
- 不影响核心数据安全的 dead code / dead setting。

### P3

纯重构、命名、结构优化。

P3 不应挤占本轮主要工作。

---

## 23. 测试体系

### 23.1 网络级状态机测试

使用 MockWebServer 或当前项目等价工具。

必须覆盖：

| 场景 | 预期 |
|---|---|
| 标准 SSE | 正常逐块输出 |
| `data:{...}` | 正常解析 |
| SSE thoughts | 正常输出 |
| SSE tool_calls | 正常组装 |
| SSE error: unsupported stream | AUTO 降级 |
| HTTP 200 error JSON: unsupported stream | AUTO 降级 |
| HTTP 400 unsupported stream | AUTO 降级 |
| HTTP 422 / 501 unsupported stream | AUTO 降级 |
| HTTP 200 full choices | 直接成功，不重复请求 |
| 顶层 JSON string | 正常正文 |
| message 为 string | 正常正文 |
| HTTP 200 HTML | 报 malformed，不学习 non-stream |
| HTTP 200 empty | 报 empty，不学习 non-stream |
| malformed JSON | 报 malformed，不学习 non-stream |
| stream 中途断开但已输出正文 | 保留正文，不整轮重发 |
| fallback non-stream 成功 | 写 runtime capability |
| fallback non-stream 失败 | 不写 runtime capability |
| FORCE_STREAM 遇 unsupported | 明确报错，不降级 |
| FORCE_NON_STREAM | 不先发 stream |
| omit stream profile | payload 不出现 stream |
| tools fallback | tool_calls 完整 |
| usage | 两种模式均正确 |
| cancellation | 请求及时取消 |

### 23.2 Capability Cache 测试

必须覆盖：

- Config A 学到 non-stream，不影响 Config B；
- 同 Config 改 URL 后缓存失效；
- 同 Config 改 key 后缓存失效；
- 同 Config 改 model 后使用新 key；
- Empty/HTML 不写缓存；
- fallback 失败不写缓存；
- app/process 重启后不依赖旧缓存。

### 23.3 ChannelBinding 测试

必须覆盖：

- VISION Config A -> Config B 后不残留 model A；
- `modelOverride=null` 正确使用 defaultModel；
- Vision Chat 与 Caption resolution 相同；
- disabled Config 被拒绝；
- dangling Binding 被识别；
- 删除 Config 后 Binding 被清理；
- STT 未配置不使用 CHAT；
- TTS 未配置不使用 CHAT；
- Image 未配置不使用 CHAT；
- Memory 继承规则唯一且稳定；
- 旧 preferences 正确迁移。

### 23.4 Vault 测试

按第 13.4 节完整故障注入。

### 23.5 Settings 闭环测试

对高风险设置至少验证：

`UI change -> persisted value -> app restart -> runtime consumer`

不要求 Compose 每个视觉控件都写 UI instrumentation。

但涉及路由、模型、开关、Provider 的核心设置必须至少有 Repository / ViewModel 层闭环测试。

---

## 24. 近期历史 Bug 回归门禁

以下问题必须建立回归保护：

1. Vision Config 被默认 `gpt-4o-mini` 覆盖。
2. 智谱视觉 capability 缺失。
3. 带图请求失败后降级分支没有真正 retry。
4. 纯图片消息携带空 `text` block。
5. EXIF 方向错误。
6. 图片分辨率过低导致小字不可读。
7. STT 文本中的转义引号导致截断。
8. 会话打开没有落位末尾。
9. 小马返回 HTTP 200 error JSON。
10. 小马非流式返回顶层 JSON string。
11. 非 SSE 零内容被静默吞掉。
12. `finish_reason=length/content_filter` 没有提示。

已有单测可以复用。

没有测试的历史 Bug 应尽量补上。

---

## 25. 实施阶段

### Phase 0 — 只读基线与全仓审计

任务：

- 记录 HEAD；
- 确认工作区状态；
- 逐文件阅读生产 Kotlin；
- 建文件覆盖表；
- 建 Settings 闭环表；
- 建 Storage Key Inventory；
- 建 Network Call Inventory；
- 建 Channel / Config 调用图；
- 标记 P0/P1/P2/P3。

允许修改：

- `docs/audits/*`
- 必要测试草案

原则上不改生产逻辑。

完成标准：

审计报告足以回答：

“Loyea 现在每一个模型相关功能到底从哪里取得 Config、Model、URL、Key、Provider 和 capability？”

### Phase 1 — P0 Storage 修复

任务：

- 修 ApiConfigVault；
- 加迁移故障测试；
- 审计明文敏感字段；
- 修 release 隐私日志。

完成标准：

不存在“secure write 失败但 legacy 被删”的路径。

### Phase 2 — Unified Transport + Stream State Machine

任务：

- 建 ErrorKind；
- 重写 stream 状态机；
- 修 HTTP 非 200 unsupported stream；
- 修 SSE error；
- 修 capability cache；
- 分离 timeout；
- 支持 stream=false / omit field；
- 把旧 `sendChatCompletion()` 迁移到统一 Transport。

完成标准：

第 23.1 网络矩阵通过。

### Phase 3 — ChannelBinding

任务：

- 建 ChannelBinding；
- 建 Resolver；
- 迁移 Vision/STT/TTS/Image/Memory；
- 统一 Caption；
- 修 isEnabled；
- 修 dangling reference。

完成标准：

不存在散装 configId + modelName 作为同一通道的两个独立事实来源。

### Phase 4 — ProviderAdapter

任务：

- 抽 URL/Header/Payload/capability；
- 收敛 search；
- 收敛 tools；
- Custom compatibility profile。

完成标准：

Transport 主体不再充斥 `if (provider == "...")` 决定协议细节。

### Phase 5 — 全功能审计问题修复

只处理审计中：

- P0；
- P1；
- 高可信 P2。

P3 记录即可。

### Phase 6 — 回归、实机与文档

完成：

- 单测；
- 网络测试；
- debug/release 构建；
- 实机 smoke test；
- migration note；
- final audit。

---

## 26. 阶段提交协议

建议每 Phase 独立提交。

示例：

```text
docs(audit): map Loyea runtime settings and API routes
fix(storage): make API config vault migration atomic
refactor(llm): unify chat transport and stream fallback state machine
refactor(config): add channel bindings and strict config resolution
refactor(provider): separate provider identity from protocol capabilities
test(runtime): add routing and compatibility regression matrix
docs(runtime): record migration and audit results
```

每阶段提交前：

1. 工作区检查；
2. 测试；
3. `git diff --check`；
4. 记录实际执行结果；
5. 不把用户本地 Key / 聊天数据 / 真实私密日志提交到仓库。

如果仓库本来存在用户未提交修改：

- 不覆盖；
- 不 reset；
- 不 force checkout；
- 在报告中说明。

---

## 27. 不允许的实现

禁止：

- 为每个新中转继续在 `LlmClient` 顶层增加一堆 provider `if`；
- 用 `"gpt-4o-mini"` 等具体模型值判断“用户是否自定义过”；
- 用 `find ?: activeConfig` 解决所有配置缺失；
- 把所有 400 都学习为 capability unsupported；
- 因为 HTML/Empty 就把渠道记为 non-stream；
- fallback 未成功前写 non-stream cache；
- Worker 自己再造 HTTP 客户端；
- 旧、新 Chat Completion Transport 并存；
- 加密失败后继续删除 plaintext；
- 保存失败但 UI 假装成功；
- release log 输出完整对话/TTS/Prompt；
- 为通过测试而扩大 catch、吞掉错误；
- 删除失败测试；
- 用“所有测试通过”替代真实调用链审计；
- 顺手重写角色系统、人格系统或 UI；
- 引入大型 DI / plugin framework 解决当前局部职责问题。

---

## 28. 验收矩阵

### 28.1 API Routing

- [ ] 小马 / New API 不支持流式时 AUTO 可以降级。
- [ ] HTTP 400/422/501 明确 unsupported stream 可以降级。
- [ ] SSE error 不会被误判为 semantic stream started。
- [ ] 已有正文后断流不会整轮重发。
- [ ] HTML / Empty / malformed 不污染 capability cache。
- [ ] fallback 失败不污染 capability cache。
- [ ] Config A 不污染 Config B。
- [ ] FORCE_STREAM 不静默降级。
- [ ] NON_STREAM 不做无意义 stream probe。
- [ ] slow reasoning non-stream 不被 60s 共享 read timeout 误杀。

### 28.2 Config

- [ ] disabled Config 不被运行时使用。
- [ ] 删除 Config 后无 dangling Binding。
- [ ] Vision config/model 不再漂移。
- [ ] Caption 与 Vision 使用同一 resolution。
- [ ] STT/TTS/Image 未配置时明确不可用，不偷用 CHAT。
- [ ] Memory 的继承规则只有一个实现位置。
- [ ] Worker 不绕过 Repository。

### 28.3 Provider

- [ ] Provider 身份与 ProtocolProfile 分离。
- [ ] Custom 可明确选择 compatibility profile。
- [ ] `stream_options` 由 Adapter 决定。
- [ ] tools 由 Adapter capability 决定。
- [ ] native search payload 由 Adapter 决定。
- [ ] URL/Header 不在多个业务函数复制。

### 28.4 Storage / Privacy

- [ ] Vault 迁移原子。
- [ ] 失败保留 legacy。
- [ ] save failure 可见。
- [ ] release log 无 API Key。
- [ ] release log 无完整聊天正文。
- [ ] release log 无完整 TTS 文本。
- [ ] release log 无图片/音频 base64。

### 28.5 审计

- [ ] 生产 Kotlin 文件阅读覆盖表完成。
- [ ] Settings 闭环表完成。
- [ ] Storage Key Inventory 完成。
- [ ] Network Call Inventory 完成。
- [ ] Channel/Config 图完成。
- [ ] 所有 P0/P1 有处理结论。
- [ ] 未修 P2/P3 有明确记录。

---

## 29. 最终交付物

必须交付：

1. 代码修改。
2. `docs/audits/LOYEA_RUNTIME_CONFIG_AUDIT.md`
3. `docs/migrations/LOYEA_CONFIG_ROUTING_MIGRATION.md`
4. 新增/修改测试。
5. 最终开发报告。

最终报告至少回答：

- 实际完整阅读了哪些生产代码；
- 当前 Loyea 有哪些功能通道；
- 每个通道最终怎样解析 Config 和 Model；
- 发现多少 P0/P1/P2/P3；
- 已修哪些；
- 未修哪些以及为什么；
- ApiConfigVault 如何避免丢数据；
- stream 状态机如何决定 fallback；
- capability cache 何时写、何时失效；
- ProviderAdapter 当前有哪些 profile；
- 旧用户配置如何迁移；
- 哪些历史 Bug 已被测试锁住；
- 哪些结论做过实机验证；
- 哪些只经过自动测试；
- 哪些仍未验证。

禁止将“未执行”写成“通过”。

---

## 30. 停止条件与 Scope 控制

当以下条件同时满足，本 Spec 完成：

- P0 全部关闭；
- API Routing / ChannelBinding / ProviderAdapter 主链路完成；
- P1 中属于本规范范围的问题全部关闭；
- 高可信 P2 已修或明确记录；
- 核心测试矩阵通过；
- debug/release 构建成功；
- 至少完成主聊天、Vision、STT/TTS 中实际可配置部分、小马 non-stream fallback 的 smoke test；
- 审计文档能够让后续开发者理解真实配置拓扑。

完成后停止继续“顺手优化”。

以下内容留待后续独立 Spec：

- 陪伴模式人格稳态；
- HDS；
- Soul/Lorebook 新能力；
- 大规模 UI 重构；
- 通用插件生态；
- 与本次配置/运行链路无直接证据关系的架构美化。

---

## 31. 给下游 Agent 的直接执行指令

> 在 `ApolloEddy/Loyea` 最新 `main` 上执行本规范。开始前记录实际 HEAD；如果 HEAD 晚于 `612b430ca15f59e37397d2baf9576ace674520f4`，以最新代码为事实来源，本 Spec 中的具体代码描述只作审计线索。
>
> 第一阶段禁止先修 Bug。先逐文件阅读 `app/src/main/java/com/loyea/**/*.kt` 的生产代码，完成文件覆盖表、Settings 闭环表、Storage Key Inventory、Network Call Inventory 和 Channel/Config 调用图。README、CHANGELOG、commit message 不能代替代码阅读。搜索命中也不能代替完整阅读对应文件。
>
> 审计完成后按 Phase 1–6 实施。已经明确给出方案的部分不要重新自由设计：采用 `ChannelBinding -> ApiConfigRepository -> ProviderAdapter -> Unified Transport` 主链路；stream 使用 AUTO/STREAM/NON_STREAM；AUTO 只在明确 UnsupportedStreaming 或成功 full-body response 时学习 non-stream；fallback 成功前不得写 capability cache；Vision 与 Caption 共用 VISION Binding；STT/TTS/Image 未配置时不得静默拿 CHAT；ApiConfigVault 必须先成功加密写入并 read-back 验证后才删除 legacy；所有 Chat Completion HTTP 实现统一到一个 Transport。
>
> 对审计中新发现的问题，优先判断它是否属于“用户设置与真实运行不一致、数据路由错误、重复实现、静默失败、配置悬空、持久化错误、后台绕过前台新逻辑”这几类。如果属于 P0/P1，应在本轮修复；如果只是大规模架构美化，记录后停止扩张。
>
> 每个阶段提供独立 commit、测试命令与真实结果。不得删除用户数据、不得 reset 用户未提交修改、不得 force-push、不得提交真实 API Key 或聊天内容。不存在的实机证据必须标记为未执行。

---

## 32. 版本记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-09-09 | 基于 Loyea v0.8.2 的小马流式故障与近期视觉/配置问题，建立 API Routing、ChannelBinding、ProviderAdapter、Unified Transport、Vault 原子迁移及全功能设置审计规范。 |
