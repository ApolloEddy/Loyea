package com.loyea.mcp

import android.content.Context
import android.net.ConnectivityManager
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class McpRoutingTest {
    private val context: Context = mock()
    private val connectivityManager: ConnectivityManager = mock()
    private lateinit var manager: McpManager

    @Before
    fun setUp() {
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
        val sharedPrefs = mock<android.content.SharedPreferences>()
        val editor = mock<android.content.SharedPreferences.Editor>()
        whenever(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPrefs)
        whenever(sharedPrefs.edit()).thenReturn(editor)
        whenever(editor.putString(anyString(), anyString())).thenReturn(editor)
        whenever(editor.remove(anyString())).thenReturn(editor)
        // 默认：从未管理过 MCP 工具白名单（null）→ 兼容放行全部；个别用例自行覆写
        whenever(sharedPrefs.getStringSet(anyString(), isNull())).thenReturn(null)
        
        manager = McpManager(context)
    }

    @Test
    fun testGetAggregateToolsPrefixes() {
        runBlocking {
            val client1: McpClient = mock()
            val config1 = McpServerConfig("1", "ServerA", "http://localhost/sse1")
            whenever(client1.config).thenReturn(config1)
            whenever(client1.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client1.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("get_weather"))))

            val client2: McpClient = mock()
            val config2 = McpServerConfig("2", "Server B", "http://localhost/sse2")
            whenever(client2.config).thenReturn(config2)
            whenever(client2.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client2.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("run_script"))))

            manager.registerClientForTest("1", client1)
            manager.registerClientForTest("2", client2)

            val aggregated = manager.getAggregateTools()
            val filtered = aggregated.filter { it.name.startsWith("ServerA__") || it.name.startsWith("Server_B__") }
            assertEquals(2, filtered.size)
            assertEquals("ServerA__get_weather", filtered[0].name)
            assertEquals("Server_B__run_script", filtered[1].name)
        }
    }

    @Test
    fun testWhitelistBlocksUnauthorizedTools() {
        runBlocking {
            // 白名单已管理（非 null）：仅授权工具可见可调，未授权工具被过滤与拒绝
            val sharedPrefs = mock<android.content.SharedPreferences>()
            whenever(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPrefs)
            whenever(sharedPrefs.getStringSet(anyString(), isNull())).thenReturn(setOf("ServerA__get_weather"))

            val client1: McpClient = mock()
            val config1 = McpServerConfig("1", "ServerA", "http://localhost/sse1")
            whenever(client1.config).thenReturn(config1)
            whenever(client1.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client1.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("get_weather"))))

            val client2: McpClient = mock()
            val config2 = McpServerConfig("2", "Server B", "http://localhost/sse2")
            whenever(client2.config).thenReturn(config2)
            whenever(client2.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client2.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("run_script"))))

            manager.registerClientForTest("1", client1)
            manager.registerClientForTest("2", client2)

            val aggregated = manager.getAggregateTools()
            assertEquals(1, aggregated.count { it.name.startsWith("ServerA__") || it.name.startsWith("Server_B__") })
            assertEquals("ServerA__get_weather", aggregated.first { it.name.startsWith("ServerA__") }.name)

            // 未授权工具即使被调用也拒绝路由（前缀分发路径命中白名单检查）
            val thrown = try {
                manager.callTool("Server_B__run_script", mapOf())
                null
            } catch (e: IOException) {
                e
            }
            assertNotNull(thrown)
            assertTrue(thrown?.message?.contains("not authorized") == true)
        }
    }

    @Test
    fun testPrefixBasedRouting() {
        runBlocking {
            val client1: McpClient = mock()
            val config1 = McpServerConfig("1", "ServerA", "http://localhost/sse1")
            whenever(client1.config).thenReturn(config1)
            whenever(client1.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client1.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("get_weather"))))

            val client2: McpClient = mock()
            val config2 = McpServerConfig("2", "ServerB", "http://localhost/sse2")
            whenever(client2.config).thenReturn(config2)
            whenever(client2.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client2.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("run_script"))))

            manager.registerClientForTest("1", client1)
            manager.registerClientForTest("2", client2)

            val args = mapOf("location" to "San Francisco")
            val dummyResponse = JsonRpcResponse("2.0", "id", result = com.google.gson.JsonObject())
            whenever(client1.callTool("get_weather", args)).thenReturn(dummyResponse)

            val response = manager.callTool("ServerA__get_weather", args)
            assertEquals(dummyResponse, response)
            verify(client1).callTool("get_weather", args)
            verify(client2, never()).callTool(anyString(), any())
        }
    }

    @Test
    fun testFallbackRouting() {
        runBlocking {
            val client1: McpClient = mock()
            val config1 = McpServerConfig("1", "ServerA", "http://localhost/sse1")
            whenever(client1.config).thenReturn(config1)
            whenever(client1.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client1.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("get_weather"))))

            val client2: McpClient = mock()
            val config2 = McpServerConfig("2", "ServerB", "http://localhost/sse2")
            whenever(client2.config).thenReturn(config2)
            whenever(client2.status).thenReturn(MutableStateFlow(McpServerStatus.CONNECTED))
            whenever(client2.discoveredTools).thenReturn(MutableStateFlow(listOf(McpTool("run_script"))))

            manager.registerClientForTest("1", client1)
            manager.registerClientForTest("2", client2)

            val args = mapOf("script" to "hello.sh")
            val dummyResponse = JsonRpcResponse("2.0", "id", result = com.google.gson.JsonObject())
            whenever(client2.callTool("run_script", args)).thenReturn(dummyResponse)

            val response = manager.callTool("run_script", args)
            assertEquals(dummyResponse, response)
            verify(client2).callTool("run_script", args)
        }
    }
}
