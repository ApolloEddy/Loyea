package com.loyea.plugins.tavern.core

import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernChatFileCodecTest {
    @Test
    fun parsesCurrentHeaderMessagesSwipesExtraAndReportsCorruptLines() {
        val input = listOf(
            "{\"user_name\":\"Eddy\",\"character_name\":\"Alice\",\"create_date\":\"2026-08-24T01:02:03Z\",\"chat_metadata\":{\"scenario\":\"Inn\",\"custom\":{\"keep\":true}}}",
            "{\"name\":\"Eddy\",\"is_user\":true,\"is_system\":false,\"mes\":\"  hello  \",\"send_date\":1724451723000,\"extra\":{\"client\":\"tavo\"}}",
            "{\"name\":\"Alice\",\"is_user\":false,\"mes\":\"first\",\"swipes\":[\"first\",\"second\"],\"swipe_id\":1,\"extra\":{\"reasoning\":\"kept\",\"opaque\":{\"x\":1}}}",
            "not-json",
            ""
        ).joinToString("\n")

        val parsed = TavernChatFileCodec.parse(input)

        assertEquals(2, parsed.chat.messages.size)
        assertEquals(1, parsed.issues.size)
        assertEquals(4, parsed.issues.single().lineNumber)
        assertEquals("Eddy", parsed.chat.header.userName)
        assertEquals("Inn", parsed.chat.header.metadata()["scenario"].asString)
        assertTrue(parsed.chat.header.metadata()["custom"].asJsonObject["keep"].asBoolean)
        assertEquals("  hello  ", parsed.chat.messages[0].message)
        assertEquals("1724451723000", parsed.chat.messages[0].sendDate)
        assertEquals("second", parsed.chat.messages[1].selectedMessage)
        assertEquals("kept", parsed.chat.messages[1].extra()["reasoning"].asString)

        val roundTrip = TavernChatFileCodec.parse(TavernChatFileCodec.toJsonl(parsed.chat))
        assertTrue(roundTrip.issues.isEmpty())
        assertEquals(parsed.chat.header.metadata().toString(), roundTrip.chat.header.metadata().toString())
        assertEquals(parsed.chat.messages[1].swipes, roundTrip.chat.messages[1].swipes)
        assertEquals("second", roundTrip.chat.messages[1].selectedMessage)
        assertEquals("tavo", roundTrip.chat.messages[0].extra()["client"].asString)
    }

    @Test
    fun acceptsLegacyAliasesAndHeaderlessRecordsWithoutInventingAHeader() {
        val parsed = TavernChatFileCodec.parse(
            "{\"role\":\"user\",\"author\":\"Eddy\",\"content\":\"hello\",\"timestamp\":123}\n" +
                "{\"role\":\"system\",\"sender\":\"Narrator\",\"message\":\"scene\"}"
        )

        assertTrue(parsed.issues.isEmpty())
        assertEquals(2, parsed.chat.messages.size)
        assertTrue(parsed.chat.messages[0].isUser)
        assertFalse(parsed.chat.messages[0].isSystem)
        assertTrue(parsed.chat.messages[1].isSystem)
        assertEquals("scene", parsed.chat.messages[1].message)
        assertEquals("unused", TavernChatFileCodec.parse(TavernChatFileCodec.toJsonl(parsed.chat)).chat.header.userName)
    }

    @Test
    fun branchClonesUpToMessageSelectsSwipeAndLinksParent() {
        val source = sampleChat()
        val result = TavernChatForkPlanner.createBranch(
            source = source,
            childChatName = "Inn - Branch #1",
            messageIndex = 1,
            selectedSwipeId = 1
        )

        assertTrue(result.switchedToChild)
        assertEquals(2, result.child.messages.size)
        assertEquals("alternate", result.child.messages.last().message)
        assertEquals(1, result.child.messages.last().swipeId)
        assertEquals("main-chat", result.child.header.metadata()["main_chat"].asString)
        assertEquals(
            listOf("old-branch", "Inn - Branch #1"),
            result.parent.messages.last().extra()["branches"].asJsonArray.map { it.asString }
        )
    }

    @Test
    fun checkpointClonesWithoutSwitchingAndWritesBookmarkLink() {
        val source = sampleChat()
        val result = TavernChatForkPlanner.createCheckpoint(
            source = source,
            childChatName = "Inn - Checkpoint #1",
            messageIndex = 0
        )

        assertFalse(result.switchedToChild)
        assertEquals(1, result.child.messages.size)
        assertEquals("Inn - Checkpoint #1", result.parent.messages[0].extra()["bookmark_link"].asString)
        assertEquals("main-chat", result.child.header.metadata()["main_chat"].asString)
        assertEquals("main-chat", result.parent.chatName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun forkRejectsMessageOutsideChat() {
        TavernChatForkPlanner.createBranch(sampleChat(), "branch", 99)
    }

    private fun sampleChat() = TavernChatFile(
        header = TavernChatHeader(
            userName = "Eddy",
            characterName = "Alice",
            chatMetadataJson = "{\"scenario\":\"Inn\"}"
        ),
        messages = listOf(
            TavernChatMessageRecord(
                name = "Eddy",
                message = "hello",
                isUser = true,
                extraJson = "{}"
            ),
            TavernChatMessageRecord(
                name = "Alice",
                message = "first",
                swipes = listOf("first", "alternate"),
                extraJson = "{\"branches\":[\"old-branch\"]}"
            )
        ),
        chatName = "main-chat"
    )
}
