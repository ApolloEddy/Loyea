package com.loyea.ui.chat

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import java.io.File

class ChatStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = mock()
    private lateinit var storageManager: ChatStorageManager

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        `when`(context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)).thenReturn(mock())
        `when`(context.filesDir).thenReturn(filesDir)
        storageManager = ChatStorageManager(context)
    }

    @Test
    fun testUpdateSessionListAtomic() = runBlocking {
        val initialSessions = listOf(
            ChatSession("1", "Session 1", 1000L),
            ChatSession("2", "Session 2", 2000L)
        )
        storageManager.saveSessionList(initialSessions)

        // 原子更新
        storageManager.updateSessionList { currentList ->
            currentList.map { session ->
                if (session.id == "1") {
                    session.copy(title = "Updated Session 1")
                } else {
                    session
                }
            }
        }

        val loaded = storageManager.loadSessionList()
        assertEquals(2, loaded.size)
        assertEquals("Updated Session 1", loaded.first { it.id == "1" }.title)
    }

    @Test
    fun testUpdateSessionMessagesAtomic() = runBlocking {
        val sessionId = "test_session_id"
        val initialMsgs = listOf(
            Message("m1", "Hello", Sender.USER, "char_loyea_default"),
            Message("m2", "World", Sender.AI, "char_loyea_default")
        )
        storageManager.saveSessionMessages(sessionId, initialMsgs)

        // 原子更新
        storageManager.updateSessionMessages(sessionId) { currentMsgs ->
            currentMsgs.map { msg ->
                if (msg.id == "m1") {
                    msg.copy(content = "Hello Atomic")
                } else {
                    msg
                }
            }
        }

        val loaded = storageManager.loadSessionMessages(sessionId)
        assertEquals(2, loaded.size)
        assertEquals("Hello Atomic", loaded.first { it.id == "m1" }.content)
    }
}
