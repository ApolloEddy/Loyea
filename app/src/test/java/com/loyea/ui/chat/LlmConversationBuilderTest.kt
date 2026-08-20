package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LlmConversationBuilderTest {

    private val chinaTime = TimeZone.getTimeZone("GMT+08:00")

    @Test
    fun nextTurnKeepsPreviousRequestInputAsExactPrefix() {
        val userOne = Message(
            id = "u1",
            content = "first question",
            sender = Sender.USER,
            timestamp = 1_725_000_000_000L,
            llmContextSnapshot = "[TURN CONTEXT SNAPSHOT]\nSystem Time: first"
        )
        val assistantOne = Message(
            id = "a1",
            content = "first answer",
            sender = Sender.AI,
            timestamp = 1_725_000_030_000L
        )
        val userTwo = Message(
            id = "u2",
            content = "second question",
            sender = Sender.USER,
            timestamp = 1_725_000_060_000L,
            llmContextSnapshot = "[TURN CONTEXT SNAPSHOT]\nSystem Time: second"
        )

        val firstRequest = LlmConversationBuilder.build(
            systemPrompt = "stable system",
            history = listOf(userOne),
            includeMessageTimestamps = true,
            timeZone = chinaTime
        )
        val secondRequest = LlmConversationBuilder.build(
            systemPrompt = "stable system",
            history = listOf(userOne, assistantOne, userTwo),
            includeMessageTimestamps = true,
            timeZone = chinaTime
        )

        assertEquals(firstRequest, secondRequest.take(firstRequest.size))
        assertTrue(secondRequest[1].content!!.contains("System Time: first"))
        assertTrue(secondRequest.last().content!!.contains("System Time: second"))
    }

    @Test
    fun sameMessageEncodingDoesNotDependOnSlidingWindowIndex() {
        val retained = Message("same", "retained", Sender.USER, timestamp = 1_725_000_000_000L)
        val inShortWindow = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(retained),
            includeMessageTimestamps = true,
            timeZone = chinaTime
        )[1]
        val inLongWindow = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(Message("old", "old", Sender.AI, timestamp = 1L), retained),
            includeMessageTimestamps = true,
            timeZone = chinaTime
        )[2]

        assertEquals(inShortWindow, inLongWindow)
    }

    @Test
    fun mediaFallbackAndOriginalTextBehaviorRemainCompatible() {
        val image = Message("image", "caption", Sender.USER, imageUrl = "/tmp/image.png")
        val audio = Message("audio", "", Sender.USER, audioUrl = "/tmp/audio.wav")

        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(image, audio),
            includeVision = false,
            includeAudio = false,
            includeMessageTimestamps = false
        )

        assertTrue(built[1].content!!.contains("caption\n[图片]"))
        assertEquals("[语音消息]", built[2].content)
        assertFalse(built[1].content!!.contains("TURN CONTEXT"))
    }

    @Test
    fun disablingPhysicalPerceptionStripsOldPhysicalSnapshotButKeepsWorldContext() {
        val message = Message(
            id = "u",
            content = "hello",
            sender = Sender.USER,
            llmContextSnapshot = """
                [TURN CONTEXT SNAPSHOT / 本轮上下文快照]
                [USER'S PHYSICAL STATE (CACHED)]
                System Time: secret-time
                Battery: secret-battery
                [END USER'S PHYSICAL STATE]

                [GRAPH MEMORY CONTEXT]
                [Recall Memory:
                - Relationship: 主人 -> 位置 -> secret-location
                - Relationship: 主人 -> 喜欢 -> keep-coffee
                ]
                [END GRAPH MEMORY CONTEXT]

                [WORLD INFO / 世界观]
                keep-world
                [END TURN CONTEXT SNAPSHOT]
            """.trimIndent()
        )

        val content = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(message),
            allowPhysicalContext = false
        )[1].content!!

        assertFalse(content.contains("secret-time"))
        assertFalse(content.contains("secret-battery"))
        assertFalse(content.contains("secret-location"))
        assertTrue(content.contains("keep-coffee"))
        assertTrue(content.contains("keep-world"))
    }

    @Test
    fun disablingGraphMemoryRemovesPersistedGraphSnapshotButKeepsWorldContext() {
        val message = Message(
            id = "u",
            content = "hello",
            sender = Sender.USER,
            llmContextSnapshot = """
                [TURN CONTEXT SNAPSHOT / 本轮上下文快照]
                [GRAPH MEMORY CONTEXT]
                private-graph-fact
                [END GRAPH MEMORY CONTEXT]

                [WORLD INFO / 世界观]
                keep-world
                [END TURN CONTEXT SNAPSHOT]
            """.trimIndent()
        )

        val content = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(message),
            allowPhysicalContext = true,
            allowGraphContext = false
        )[1].content!!

        assertFalse(content.contains("private-graph-fact"))
        assertFalse(content.contains("GRAPH MEMORY CONTEXT"))
        assertTrue(content.contains("keep-world"))
    }
}
