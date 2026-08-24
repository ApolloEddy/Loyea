package com.loyea.ui.chat

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernChatSessionCodecTest {
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
