package com.loyea.ui.chat

import com.google.gson.JsonParser
import com.loyea.plugins.tavern.core.TavernChatForkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernChatSessionCodecTest {
    @Test
    fun `native fork keeps branch metadata and non-tavern message fields`() {
        val messages = listOf(
            Message(
                id = "user",
                content = "hello",
                sender = Sender.USER,
                tavernName = "Eddy",
                imageUrl = "/private/image.jpg"
            ),
            Message(
                id = "assistant",
                content = "first",
                sender = Sender.AI,
                tavernName = "Alice",
                versions = listOf(MessageVersion("first"), MessageVersion("alternate")),
                activeVersionIndex = 1,
                thoughts = "private reasoning"
            )
        )
        val fork = TavernChatSessionCodec.createFork(
            messages = messages,
            messageId = "assistant",
            mode = TavernChatForkMode.BRANCH,
            childChatName = "Inn branch",
            parentChatName = "Inn",
            userName = "Eddy",
            characterName = "Alice",
            headerRawJson = "{\"user_name\":\"Eddy\",\"character_name\":\"Alice\",\"chat_metadata\":{}}"
        )

        assertTrue(fork.switchedToChild)
        assertEquals(2, fork.childMessages.size)
        assertEquals("alternate", fork.childMessages.last().content)
        assertEquals("/private/image.jpg", fork.childMessages.first().imageUrl)
        assertEquals("private reasoning", fork.childMessages.last().thoughts)
        assertEquals(
            "Inn branch",
            fork.parentMessages.last().tavernExtraJson
                ?.let { JsonParser.parseString(it).asJsonObject["branches"].asJsonArray[0].asString }
        )
        assertEquals(
            "Inn",
            fork.childHeaderJson
                ?.let { JsonParser.parseString(it).asJsonObject["chat_metadata"].asJsonObject["main_chat"].asString }
        )
    }

    @Test
    fun `checkpoint projects only the prefix and does not switch`() {
        val messages = listOf(
            Message("one", "one", Sender.USER),
            Message("two", "two", Sender.AI),
            Message("three", "three", Sender.USER)
        )
        val fork = TavernChatSessionCodec.createFork(
            messages = messages,
            messageId = "two",
            mode = TavernChatForkMode.CHECKPOINT,
            childChatName = "Checkpoint",
            parentChatName = "Main",
            userName = "Eddy",
            characterName = "Alice"
        )

        assertFalse(fork.switchedToChild)
        assertEquals(2, fork.childMessages.size)
        assertEquals("Checkpoint", fork.parentMessages[1].tavernExtraJson
            ?.let { JsonParser.parseString(it).asJsonObject["bookmark_link"].asString })
    }

    @Test
    fun `imports ST metadata swipes system role and unknown fields`() {
        val imported = TavernChatSessionCodec.importJsonl(
            """{"user_name":"Eddy","character_name":"Alice","create_date":"2026-08-24T00:00:00Z","chat_metadata":{"main_chat":"root"}}""" +
                "\n" +
                """{"name":"Eddy","mes":"hello","is_user":true,"send_date":"2026-08-24T00:00:01Z","extra":{"bookmark_link":"child"},"client_field":"kept"}""" +
                "\n" +
                """{"name":"Alice","mes":"old","is_user":false,"swipes":["old","selected"],"swipe_id":1,"swipe_info":{"1":{"gen_started":"x"}},"send_date":"2026-08-24T00:00:02Z"}""" +
                "\n" +
                """{"name":"System","mes":"policy","role":"system","send_date":"2026-08-24T00:00:03Z"}""",
            fallbackTimestampMillis = 1L
        )

        assertTrue(imported.isClean)
        assertEquals("Eddy", imported.header.userName)
        assertEquals("Alice", imported.header.characterName)
        assertEquals(3, imported.messages.size)
        assertEquals(Sender.USER, imported.messages[0].sender)
        assertEquals(
            "child",
            imported.messages[0].tavernExtraJson
                ?.let { JsonParser.parseString(it).asJsonObject["bookmark_link"].asString }
        )
        assertEquals("selected", imported.messages[1].content)
        assertEquals(2, imported.messages[1].versions.size)
        assertEquals(1, imported.messages[1].activeVersionIndex)
        assertTrue(imported.messages[2].tavernIsSystem)
    }

    @Test
    fun `exports and reimports swipes extra system role and raw unknown fields`() {
        val messages = listOf(
            Message(
                id = "user",
                content = "hello",
                sender = Sender.USER,
                timestamp = 1_700_000_000_000L,
                tavernName = "Eddy",
                tavernExtraJson = "{\"x\":1}",
                tavernRawJson = "{\"client_field\":\"kept\"}"
            ),
            Message(
                id = "assistant",
                content = "new",
                sender = Sender.AI,
                timestamp = 1_700_000_001_000L,
                tavernName = "Alice",
                tavernIsSystem = true,
                versions = listOf(MessageVersion("old"), MessageVersion("new")),
                activeVersionIndex = 1
            )
        )
        val jsonl = TavernChatSessionCodec.exportJsonl(messages, "Eddy", "Alice")
        val roundTrip = TavernChatSessionCodec.importJsonl(jsonl, fallbackTimestampMillis = 1L)

        assertTrue(roundTrip.isClean)
        assertEquals(listOf("hello", "new"), roundTrip.messages.map { it.content })
        assertTrue(roundTrip.messages[1].tavernIsSystem)
        assertEquals(2, roundTrip.messages[1].versions.size)
        assertTrue(jsonl.contains("client_field"))
        assertTrue(jsonl.contains("\"x\":1"))
    }

    @Test
    fun `malformed JSONL is a fatal visible issue instead of silent drop`() {
        val imported = TavernChatSessionCodec.importJsonl(
            "{\"chat_metadata\":{}}\nnot-json\n{\"name\":\"Alice\",\"mes\":\"ok\"}",
            fallbackTimestampMillis = 42L
        )

        assertFalse(imported.isClean)
        assertEquals(1, imported.messages.size)
        assertEquals(2, imported.issues.single().lineNumber)
        assertTrue(imported.issues.single().fatal)
    }
}
