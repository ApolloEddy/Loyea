package com.loyea.perception

import android.content.Context
import com.loyea.mcp.McpTool
import com.loyea.mcp.JsonRpcResponse
import com.loyea.mcp.JsonRpcError
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地物理感知 MCP 服务器，允许 AI 通过工具调用获取传感器数据
 */
class PerceptionMcpServer(private val context: Context) {
    private val perceptionManager = PhysicalContextManager(context)
    private val gson = Gson()

    fun getTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "get_physical_perception",
                description = "Get a comprehensive summary of the user's current physical world context (time, location, and health data). Use this to ground your response in the user's real-world situation.",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_heart_rate",
                description = "Get the user's latest heart rate in BPM (Beats Per Minute).",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            McpTool(
                name = "get_location",
                description = "Get the user's current GPS coordinates (latitude and longitude).",
                inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            )
        )
    }

    suspend fun callTool(name: String, arguments: Map<String, Any>?): JsonRpcResponse = withContext(Dispatchers.IO) {
        try {
            val resultText = when (name) {
                "get_physical_perception" -> perceptionManager.buildPhysicalContextString()
                "get_heart_rate" -> {
                    val contextStr = perceptionManager.buildPhysicalContextString()
                    contextStr.lines().find { it.startsWith("Heart Rate:") } 
                        ?: "Heart Rate: Not found in current context"
                }
                "get_location" -> {
                    "Location: ${perceptionManager.locationProvider.getCurrentLocation()}"
                }
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }
            
            val resultJson = mapOf("content" to listOf(mapOf("type" to "text", "text" to resultText)))
            JsonRpcResponse(
                jsonrpc = "2.0",
                idStr = null,
                result = gson.toJsonTree(resultJson)
            )
        } catch (e: Exception) {
            JsonRpcResponse(
                jsonrpc = "2.0",
                idStr = null,
                error = JsonRpcError(code = -32603, message = e.message ?: "Internal error")
            )
        }
    }
}
