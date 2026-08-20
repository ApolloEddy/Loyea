package com.loyea.ui.chat

import com.loyea.mcp.McpTool
import java.util.Locale

/** 为缓存匹配固定工具及 JSON Schema 的序列化顺序，不改变 schema 语义。 */
object LlmRequestCanonicalizer {

    fun canonicalizeTools(tools: List<McpTool>): List<McpTool> = tools
        .map { tool ->
            tool.copy(inputSchema = tool.inputSchema?.let(::canonicalizeSchema))
        }
        .sortedWith(compareBy<McpTool> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })

    @Suppress("UNCHECKED_CAST")
    private fun canonicalizeSchema(schema: Map<String, Any>): Map<String, Any> =
        canonicalizeMap(schema) as Map<String, Any>

    private fun canonicalizeMap(source: Map<*, *>): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        source.entries
            .filter { it.key is String }
            .sortedBy { it.key as String }
            .forEach { entry ->
                result[entry.key as String] = canonicalizeValue(entry.value)
            }
        return result
    }

    private fun canonicalizeValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> canonicalizeMap(value)
        is List<*> -> value.map(::canonicalizeValue)
        else -> value
    }
}
