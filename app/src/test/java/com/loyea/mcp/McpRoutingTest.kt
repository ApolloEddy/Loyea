package com.loyea.mcp

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.kotlin.any
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
            assertEquals(2, aggregated.size)
            assertEquals("ServerA__get_weather", aggregated[0].name)
            assertEquals("Server_B__run_script", aggregated[1].name)
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
