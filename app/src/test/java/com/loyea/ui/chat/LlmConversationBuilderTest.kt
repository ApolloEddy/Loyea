package com.loyea.ui.chat

import com.loyea.context.core.*
import com.loyea.plugins.tavern.core.*

import com.loyea.plugin.api.GenerationPatch
import com.loyea.plugin.api.TextStage
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
    fun includeNamesPrefixesPromptHistoryWithoutChangingGenericDefault() {
        val history = listOf(
            Message("u", "hello", Sender.USER),
            Message("a", "hi", Sender.AI)
        )
        val unnamed = LlmConversationBuilder.build("stable", history)
        val named = LlmConversationBuilder.build(
            "stable",
            history,
            includeNames = true,
            userName = "Eddy",
            characterName = "Lya"
        )

        assertEquals("hello", unnamed[1].content)
        assertEquals("Eddy: hello", named[1].content)
        assertEquals("Lya: hi", named[2].content)
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

    @Test
    fun postHistoryInstructionsArePlacedAfterConversationHistory() {
        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(Message("u", "hello", Sender.USER)),
            postHistoryInstructions = "stay in character"
        )
        assertEquals("user", built[1].role)
        assertEquals("system", built[2].role)
        assertTrue(built[2].content!!.contains("stay in character"))
    }

    @Test
    fun continuePrefillIsTheFinalAssistantPrefixAfterPostHistory() {
        val preparedTurn = TavernPreparedTurnFactory.prepare(
            TavernTurnSpec(
                generationType = "continue",
                continueNudge = "continue without repeating",
                continuePrefill = true
            )
        )
        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(
                Message("u", "hello", Sender.USER),
                Message("a", "partial reply", Sender.AI)
            ),
            postHistoryInstructions = "stay in character",
            preparedTurn = preparedTurn
        )

        assertEquals(listOf("stable", "hello", "partial reply", "[POST-HISTORY INSTRUCTIONS / 历史消息后指令]\nstay in character", "[CONTINUE NUDGE / 继续提示]\ncontinue without repeating"), built.map { it.content })
        assertEquals("assistant", built.last().role)
    }

    @Test
    fun worldInfoAtDepthUsesMessageBoundaryAndRole() {
        val preparedTurn = LegacyTavernTurnAdapter.prepare(
            card = CharacterCard("card", "Card", shortIntro = "", systemPrompt = ""),
            userName = "User",
            regexScripts = emptyList(),
            presetMessages = emptyList(),
            worldInfoAtDepth = mapOf(
                1 to listOf(WorldInfoMatcher.WorldInfoInjectionBlock("before latest", "assistant")),
                0 to listOf(WorldInfoMatcher.WorldInfoInjectionBlock("after latest", "user"))
            ),
            generation = GenerationPatch()
        )
        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(
                Message("u1", "one", Sender.USER),
                Message("a1", "two", Sender.AI),
                Message("u2", "three", Sender.USER)
            ),
            preparedTurn = preparedTurn
        )
        assertEquals(
            listOf(
                "stable",
                "one",
                "two",
                "[WORLD INFO @ DEPTH / 深度世界书]\nbefore latest",
                "three",
                "[WORLD INFO @ DEPTH / 深度世界书]\nafter latest"
            ),
            built.map { it.content }
        )
        assertEquals("assistant", built[3].role)
        assertEquals("user", built[5].role)
    }

    @Test
    fun frozenTavernTurnPreservesPresetOrderAndRegexAcrossStages() {
        val mutableScripts = mutableListOf(
            TavernRegexScript(
                id = "replace",
                scriptName = "replace",
                findRegex = "/foo/g",
                replaceString = "bar",
                placement = listOf(
                    TavernRegexPlacement.USER_INPUT,
                    TavernRegexPlacement.AI_OUTPUT,
                    TavernRegexPlacement.REASONING
                )
            )
        )
        val preparedTurn = LegacyTavernTurnAdapter.prepare(
            card = CharacterCard("card", "Card", shortIntro = "", systemPrompt = ""),
            userName = "User",
            regexScripts = mutableScripts,
            presetMessages = listOf(
                TavernPresetPrompt("Rule", "rule", "preset text", role = "assistant")
            ),
            worldInfoAtDepth = emptyMap(),
            generation = GenerationPatch(maxContextTokens = 4096)
        )
        mutableScripts.clear()

        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(Message("u", "foo", Sender.USER)),
            compressedSummary = "summary",
            preparedTurn = preparedTurn
        )

        assertEquals(
            listOf(
                "stable",
                "[PRESET SLOT / rule]\npreset text",
                "[EARLY CONVERSATION SUMMARY / 会话早期摘要]\nsummary",
                "bar"
            ),
            built.map { it.content }
        )
        assertEquals("assistant", built[1].role)
        assertEquals("bar", preparedTurn.transform(TextStage.MODEL_OUTPUT, "foo", isMarkdown = true))
        assertEquals("bar", preparedTurn.transform(TextStage.REASONING, "foo"))
        assertEquals(4096, preparedTurn.plan.generation.maxContextTokens)
    }

    @Test
    fun presetContextBudgetDropsOldestHistoryFirst() {
        val built = LlmConversationBuilder.build(
            systemPrompt = "stable",
            history = listOf(
                Message("old", "old old old old", Sender.USER),
                Message("middle", "middle middle middle", Sender.AI),
                Message("latest", "latest", Sender.USER)
            ),
            maxContextTokens = 5
        )
        assertFalse(built.any { it.content?.contains("old old") == true })
        assertTrue(built.any { it.content == "latest" })
    }

    @Test
    fun importedTavernSystemMessageKeepsSystemProviderRoleAndName() {
        val built = LlmConversationBuilder.build(
            systemPrompt = null,
            history = listOf(
                Message(
                    id = "system",
                    content = "Follow the imported policy.",
                    sender = Sender.AI,
                    tavernName = "System",
                    tavernIsSystem = true
                )
            ),
            includeNames = true,
            characterName = "Alice"
        )

        assertEquals(1, built.size)
        assertEquals("system", built.single().role)
        assertEquals("System: Follow the imported policy.", built.single().content)
    }
}
