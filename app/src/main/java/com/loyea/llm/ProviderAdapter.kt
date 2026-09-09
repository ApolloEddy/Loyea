package com.loyea.llm

import com.google.gson.JsonObject

/**
 * Provider 协议适配器（Spec §7）。
 *
 * provider 字符串只代表 UI/预设身份；URL、鉴权、payload 方言、协议能力一律由 Adapter 决定。
 * Transport 主体不得出现 `if (provider == "...")` 决定协议细节的分支。
 */
interface ProviderAdapter {
    val id: String

    /** Chat Completion 端点 URL（含补全 /chat/completions 的规范逻辑）。 */
    fun resolveChatUrl(apiUrl: String): String

    /** 鉴权 Header（Bearer / api-key 等；不含 Content-Type）。 */
    fun buildAuthHeaders(apiKey: String): Map<String, String>

    /** 协议能力声明。 */
    fun chatCapabilities(model: String): ChatCapabilities

    /** 非流式请求的内部表达。 */
    val nonStreamEncoding: NonStreamEncoding

    /**
     * 构建请求 payload。mode 决定 stream 字段表达；nativeSearch 请求只有当
     * capabilities.nativeSearch == SUPPORTED 时才会被 Transport 传入 true。
     */
    fun buildChatPayload(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        streamPayload: Boolean,
        nonStreamEncoding: NonStreamEncoding,
        nativeSearch: Boolean,
        messageEncoder: MessageEncoder
    ): JsonObject

    /** 服务商错误细分（httpCode/body 可空；返回 null 表示交给通用分类）。 */
    fun classifyError(httpCode: Int?, errorMessage: String?): LlmErrorKind?
}

/** 消息 → provider payload 的编码回调（多模态部件编码在宿主层实现）。 */
interface MessageEncoder {
    fun encodeMessages(messages: List<ChatMessage>): com.google.gson.JsonArray
    fun encodeTools(tools: List<ToolSchema>): com.google.gson.JsonArray
}

/**
 * OpenAI 兼容基础档位：绝大多数官方 API 与中转的共同分母。
 * 保守策略：不发 stream_options、不发任何 native search 扩展字段
 * （OpenAI 兼容 ≠ 支持任何一种扩展，Spec §16.2）。
 */
open class OpenAiCompatAdapter(override val id: String = "openai-compat") : ProviderAdapter {

    override val nonStreamEncoding: NonStreamEncoding = NonStreamEncoding.STREAM_FALSE

    override fun resolveChatUrl(apiUrl: String): String {
        var base = apiUrl.trim()
        if (!base.endsWith("/chat/completions")) {
            base = if (base.endsWith("/")) base + "chat/completions" else "$base/chat/completions"
        }
        return base
    }

    override fun buildAuthHeaders(apiKey: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $apiKey")

    override fun chatCapabilities(model: String): ChatCapabilities = ChatCapabilities(
        streaming = CapabilityState.SUPPORTED,
        tools = CapabilityState.SUPPORTED,
        nativeSearch = CapabilityState.UNSUPPORTED,
        streamOptions = CapabilityState.UNSUPPORTED
    )

    override fun buildChatPayload(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        streamPayload: Boolean,
        nonStreamEncoding: NonStreamEncoding,
        nativeSearch: Boolean,
        messageEncoder: MessageEncoder
    ): JsonObject = JsonObject().apply {
        addProperty("model", model)
        add("messages", messageEncoder.encodeMessages(messages))
        when {
            streamPayload -> addProperty("stream", true)
            nonStreamEncoding == NonStreamEncoding.OMIT_STREAM_FIELD -> { /* 省略 stream 字段 */ }
            else -> addProperty("stream", false)
        }
        if (tools.isNotEmpty()) {
            add("tools", messageEncoder.encodeTools(tools))
            addProperty("tool_choice", "auto")
        }
    }

    override fun classifyError(httpCode: Int?, errorMessage: String?): LlmErrorKind? = null
}

/** DeepSeek：官方支持 stream_options 流式 usage。 */
class DeepSeekAdapter : OpenAiCompatAdapter("deepseek") {
    override fun chatCapabilities(model: String): ChatCapabilities =
        super.chatCapabilities(model).copy(streamOptions = CapabilityState.SUPPORTED)
}

/** 小米 MiMo：网关要求 api-key 头；不支持 stream_options 与 native search 扩展。 */
class MiMoAdapter : OpenAiCompatAdapter("mimo") {
    override fun buildAuthHeaders(apiKey: String): Map<String, String> =
        mapOf(
            "Authorization" to "Bearer $apiKey",
            "api-key" to apiKey
        )

    override fun chatCapabilities(model: String): ChatCapabilities = ChatCapabilities(
        streaming = CapabilityState.SUPPORTED,
        tools = CapabilityState.SUPPORTED,
        nativeSearch = CapabilityState.UNSUPPORTED,
        streamOptions = CapabilityState.UNSUPPORTED
    )
}

/** 智谱：官方原生 enable_search 联网字段。 */
class ZhipuAdapter : OpenAiCompatAdapter("zhipu") {
    override fun chatCapabilities(model: String): ChatCapabilities = ChatCapabilities(
        streaming = CapabilityState.SUPPORTED,
        tools = CapabilityState.SUPPORTED,
        nativeSearch = CapabilityState.SUPPORTED,
        vision = CapabilityState.SUPPORTED,
        streamOptions = CapabilityState.UNSUPPORTED
    )

    override fun buildChatPayload(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        streamPayload: Boolean,
        nonStreamEncoding: NonStreamEncoding,
        nativeSearch: Boolean,
        messageEncoder: MessageEncoder
    ): JsonObject = super.buildChatPayload(model, messages, tools, streamPayload, nonStreamEncoding, nativeSearch, messageEncoder).apply {
        if (nativeSearch) addProperty("enable_search", true)
    }
}

/**
 * Adapter 注册表：provider 预设字符串 → 协议档位。
 * 未知/Custom 预设一律回落 OpenAI 兼容基础档位（保守分母）。
 */
object ProviderAdapters {

    fun forProvider(provider: String): ProviderAdapter {
        val p = provider.lowercase()
        return when {
            p.contains("deepseek") -> deepseek
            p.contains("mimo") -> mimo
            p.contains("zhipu") || p.contains("智谱") || p.contains("glm") -> zhipu
            else -> openaiCompat
        }
    }

    private val openaiCompat = OpenAiCompatAdapter()
    private val deepseek = DeepSeekAdapter()
    private val mimo = MiMoAdapter()
    private val zhipu = ZhipuAdapter()
}
