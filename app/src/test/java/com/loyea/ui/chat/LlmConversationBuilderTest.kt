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
    fun timestampsOnlyOnUserMessagesAssistantNeverTagged() {
        // 泄露根因契约：assistant 历史回复绝不带 [MESSAGE TIME] 标签
        // （模型会模仿自己的输出格式导致回复泄露标签），用户消息时间戳保留
        val user = Message("u", "question", Sender.USER, timestamp = 1_725_000_000_000L)
        val assistant = Message("a", "answer", Sender.AI, timestamp = 1_725_000_030_000L)

        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(user, assistant, user.copy(id = "u2")),
            includeMessageTimestamps = true,
            timeZone = chinaTime
        )

        assertTrue(built[1].content!!.contains("[MESSAGE TIME:"))
        assertFalse(built[2].content!!.contains("MESSAGE TIME"))
        assertTrue(built[3].content!!.contains("[MESSAGE TIME:"))
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
    fun visionRouteCarriesImageAndTextWithoutPlaceholder() {
        // 视觉通路契约：includeVision=true 时图片与文字原样进入 payload 消息，
        // 不注入 [图片] 占位（占位只允许出现在降级/无视觉路径）——
        // 带字拍照场景（图 + 行测题文字）逐字段验证
        val photo = Message(
            id = "u1",
            content = "这道行测题选什么？",
            sender = Sender.USER,
            imageUrl = "/cache/vision_123.jpg",
            timestamp = 1_725_000_000_000L
        )

        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(photo),
            includeVision = true,
            includeMessageTimestamps = false
        )

        assertEquals(2, built.size)
        assertEquals("user", built[1].role)
        assertEquals("/cache/vision_123.jpg", built[1].imageUrl)
        assertTrue(built[1].content!!.contains("这道行测题选什么？"))
        assertFalse(built[1].content!!.contains("[图片]"))
    }

    @Test
    fun visionDisabledKeepsPlaceholderAndStripsImageUrlFromPayload() {
        // 降级契约：includeVision=false → 文本占位 + imageUrl 置空（图片不得进入 payload）
        val photo = Message("u1", "看图答题", Sender.USER, imageUrl = "/cache/vision_123.jpg")

        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(photo),
            includeVision = false
        )

        assertEquals(null, built[1].imageUrl)
        assertTrue(built[1].content!!.contains("看图答题\n[图片]"))
    }

    @Test
    fun imageMessageSurvivesBudgetSelectionAsLastMessage() {
        // 预算契约：最后一条（当前输入）无条件保留——带图消息不会被 token 预算裁剪出请求
        val photo = Message("u1", "看图答题", Sender.USER, imageUrl = "/cache/vision_123.jpg", timestamp = 1L)

        assertEquals(1, LlmConversationBuilder.selectWithinBudget(listOf(photo), 0L).size)
        assertEquals(1, LlmConversationBuilder.selectWithinBudget(listOf(photo), 1L).size)
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
