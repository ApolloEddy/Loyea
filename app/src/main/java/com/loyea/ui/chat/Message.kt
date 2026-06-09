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
    val isStillThinking: Boolean = false
)
