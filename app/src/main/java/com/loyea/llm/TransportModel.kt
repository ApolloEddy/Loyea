package com.loyea.llm

/**
 * 用户级流式模式（Spec §8.1）：
 * - AUTO：优先流式，确证不支持时安全降级；
 * - STREAM：强制流式，不支持时明确报错，不静默改变用户选择；
 * - NON_STREAM：直接整包请求，不发起流式探测。
 */
enum class StreamMode { AUTO, STREAM, NON_STREAM }

/**
 * 内部非流式表达（Spec §8.2）：非用户设置，由协议档位声明。
 */
enum class NonStreamEncoding { STREAM_FALSE, OMIT_STREAM_FIELD }

/**
 * Transport 层消息模型（与 ui.chat.LlmChatMessage 同形，经 typealias 兼容旧调用点）。
 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val imageUrl: String? = null,
    val audioUrl: String? = null
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** 统一 usage 计量（OpenAI 兼容；DeepSeek 额外带前缀缓存命中/未命中）。 */
data class Usage(
    val promptTokens: Long?,
    val completionTokens: Long?,
    val totalTokens: Long?,
    val promptCacheHitTokens: Long? = null,
    val promptCacheMissTokens: Long? = null
)

/** 整包响应。 */
data class ChatResponse(
    val content: String,
    val thoughts: String? = null,
    val isError: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null,
    /** 错误细分（isError 时供状态机决策）；正常响应为 null。 */
    val errorKind: LlmErrorKind? = null
)

/** Transport 流事件（ui.chat.StreamEvent 的层内镜像）。 */
sealed class TransportEvent {
    data class Content(val text: String) : TransportEvent()
    data class Thoughts(val text: String) : TransportEvent()
    data class ToolCalls(val calls: List<ToolCall>) : TransportEvent()
    data class Notice(val text: String) : TransportEvent()
    /** 命名 UsageEvent 避免与顶层 Usage 类型在嵌套作用域内遮蔽冲突。 */
    data class UsageEvent(val usage: Usage) : TransportEvent()
    /** 结构化错误：kind 供状态机/UI 决策，message 为用户可读文案。 */
    data class Error(val kind: LlmErrorKind, val message: String) : TransportEvent()
    object Done : TransportEvent()
}

/**
 * 已解析的聊天请求：进入 Transport 前必须不可歧义（Spec §5.5）。
 * 谁来选择 config/model 是 Resolver 的职责，Transport 不再做任何配置回退。
 */
data class ChatExecutionRequest(
    val configId: String,
    val apiUrl: String,
    val apiKey: String,
    val providerPreset: String,
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema> = emptyList(),
    val streamMode: StreamMode = StreamMode.AUTO,
    val nativeSearchRequested: Boolean = false
)

/** 工具 schema（与 mcp.McpTool 解耦的最小表达）。 */
data class ToolSchema(
    val name: String,
    val description: String?,
    val inputSchema: Map<String, Any>?
)
