package com.loyea.ui.chat

import com.loyea.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmRequestCanonicalizerTest {

    @Test
    fun toolAndSchemaMapOrderProduceSameCanonicalPayload() {
        val first = listOf(
            McpTool("zeta", inputSchema = linkedMapOf("type" to "object", "description" to "z")),
            McpTool(
                "Alpha",
                inputSchema = linkedMapOf(
                    "required" to listOf("query"),
                    "properties" to linkedMapOf(
                        "query" to linkedMapOf("description" to "q", "type" to "string")
                    ),
                    "type" to "object"
                )
            )
        )
        val second = listOf(
            McpTool(
                "Alpha",
                inputSchema = linkedMapOf(
                    "type" to "object",
                    "properties" to linkedMapOf(
                        "query" to linkedMapOf("type" to "string", "description" to "q")
                    ),
                    "required" to listOf("query")
                )
            ),
            McpTool("zeta", inputSchema = linkedMapOf("description" to "z", "type" to "object"))
        )

        assertEquals(
            LlmRequestCanonicalizer.canonicalizeTools(first),
            LlmRequestCanonicalizer.canonicalizeTools(second)
        )
    }
}
