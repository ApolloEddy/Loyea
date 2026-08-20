package com.loyea.ui.chat

import androidx.compose.runtime.Immutable

enum class Sender {
    USER, AI
}

enum class McpStatus {
    RUNNING, SUCCESS, FAILED
}

data class McpCall(
    val id: String,
    val toolName: String,
    val actionText: String,
    val status: McpStatus,
    val input: String = "",
    val output: String = ""
)

/**
 * AI 回复多轮分段：type = "text"（回复文本段）| "tool"（工具卡段，经 mcpCallId 关联 mcpCalls）。
 * 仅新消息携带（空列表走旧渲染路径），扁平数据类保证 Gson 序列化/反序列化安全。
 */
@Immutable
data class MessageContentSegment(
    val type: String,        // "text" | "tool"
    val text: String = "",
    val mcpCallId: String = ""
)

@Immutable
data class Message(
    val id: String,
    val content: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),

    // AI 思考与 MCP 调用信息，提供默认值以向后兼容
    val thoughts: String? = null,
    val isThoughtsExpanded: Boolean = false,
    val hasUserToggledThoughts: Boolean = false, // 标记用户是否手动干预过思考链折叠
    val thoughtDurationSeconds: Int = 0,
    val mcpCalls: List<McpCall> = emptyList(),
    val isStillThinking: Boolean = false,
    val isError: Boolean = false,
    val characterId: String? = null,

    // 仅供 LLM 请求使用的“该用户消息发送当时”上下文快照；不参与 UI 正文渲染。
    // 一旦生成便保持不变，使后续多轮请求可以逐字复用此前输入前缀。
    val llmContextSnapshot: String? = null,
    // 发送时区与 timestamp 一起固化；设备以后切换时区也不会重写历史 provider 前缀。
    val llmTimeZoneId: String? = java.util.TimeZone.getDefault().id,

    // 多模态 Vision 与 Speech 新增字段，带默认值以兼容旧数据
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDuration: Int = 0,
    val isAudioPlaying: Boolean = false,
    val isAudioSynthesizing: Boolean = false,

    // AI 多版本回复与重新生成支持
    val versions: List<MessageVersion> = emptyList(),
    val activeVersionIndex: Int = 0,

    // Agent 式多轮回复分段（仅新消息；空列表走旧渲染路径）
    val contentSegments: List<MessageContentSegment> = emptyList()
)

@Immutable
data class MessageVersion(
    val content: String,
    val thoughts: String? = null,
    val mcpCalls: List<McpCall> = emptyList(),
    val audioUrl: String? = null,
    val audioDuration: Int = 0
)
