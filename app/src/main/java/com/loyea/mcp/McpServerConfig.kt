package com.loyea.mcp

enum class McpServerStatus {
    CONNECTED, CONNECTING, DISCONNECTED
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val sseUrl: String,
    val isEnabled: Boolean = true
)

data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, Any>? = null
)
