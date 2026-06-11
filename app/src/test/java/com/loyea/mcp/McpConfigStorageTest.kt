package com.loyea.mcp

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class McpConfigStorageTest {
    private val context: Context = mock()
    private val sharedPreferences: SharedPreferences = mock()
    private val editor: SharedPreferences.Editor = mock()
    private lateinit var storage: McpConfigStorage

    @Before
    fun setUp() {
        whenever(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences)
        whenever(sharedPreferences.edit()).thenReturn(editor)
        whenever(editor.putString(anyString(), anyString())).thenReturn(editor)
        whenever(editor.remove(anyString())).thenReturn(editor)
        storage = McpConfigStorage(context)
    }

    @Test
    fun testSaveAndLoadConfigsSuccess() {
        val configs = listOf(
            McpServerConfig("1", "Server 1", "http://localhost/sse", true),
            McpServerConfig("2", "Server 2", "http://localhost/sse2", false)
        )

        val json = """[{"id":"1","name":"Server 1","sseUrl":"http://localhost/sse","isEnabled":true},{"id":"2","name":"Server 2","sseUrl":"http://localhost/sse2","isEnabled":false}]"""
        whenever(sharedPreferences.getString(eq("mcp_server_configs"), anyOrNull())).thenReturn(json)

        val loaded = storage.loadConfigs()
        assertEquals(2, loaded.size)
        assertEquals("Server 1", loaded[0].name)
        assertEquals("http://localhost/sse", loaded[0].sseUrl)
        assertTrue(loaded[0].isEnabled)
        assertEquals("Server 2", loaded[1].name)
        assertEquals("http://localhost/sse2", loaded[1].sseUrl)
        assertTrue(!loaded[1].isEnabled)

        storage.saveConfigs(configs)
        verify(editor).putString(eq("mcp_server_configs"), anyString())
        verify(editor).apply()
    }

    @Test
    fun testLoadConfigsSelfHealingOnCorruptedJson() {
        whenever(sharedPreferences.getString(eq("mcp_server_configs"), anyOrNull())).thenReturn("invalid json structure")

        val loaded = storage.loadConfigs()
        assertTrue(loaded.isEmpty())

        verify(editor).remove("mcp_server_configs")
        verify(editor).apply()
    }
}
