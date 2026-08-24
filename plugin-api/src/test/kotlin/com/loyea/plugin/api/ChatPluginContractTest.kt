package com.loyea.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatPluginContractTest {
    @Test
    fun `persona and turn inputs defensively copy caller collections`() {
        val greetings = mutableListOf("hello")
        val history = mutableListOf(ConversationText("m1", ChatRole.USER, "hi"))
        val persona = PersonaProjection(
            ref = PersonaRef.plugin(PluginId.of("com.loyea.tavern"), "card-1"),
            displayName = "Card",
            avatarUri = null,
            summary = "summary",
            greetingTemplates = greetings
        )
        val turn = PluginTurnInput(
            sessionId = "session-1",
            turnId = "turn-1",
            turnIndex = 3L,
            userName = "User",
            history = history
        )

        greetings += "changed"
        history += ConversationText("m2", ChatRole.ASSISTANT, "changed")

        assertEquals(listOf("hello"), persona.greetingTemplates)
        assertEquals(listOf("m1"), turn.history.map(ConversationText::id))
    }

    @Test
    fun `generation and insertion collections are immutable snapshots`() {
        val stops = mutableListOf("STOP")
        val insertions = mutableListOf(
            ConversationInsertion(
                anchor = InsertionAnchor.AT_DEPTH_FROM_LATEST,
                role = ChatRole.SYSTEM,
                content = "world info",
                depthFromLatest = 2
            )
        )
        val generation = GenerationPatch(stopStrings = stops)
        val plan = PluginTurnPlan(
            prompt = PromptPatch(stablePersonaText = "persona"),
            insertions = insertions,
            generation = generation
        )

        stops += "MUTATED"
        insertions.clear()

        assertEquals(listOf("STOP"), generation.stopStrings)
        assertEquals(1, plan.insertions.size)
        assertEquals(2, plan.insertions.single().depthFromLatest)
    }

    @Test
    fun `at-depth insertion rejects negative depth`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationInsertion(
                anchor = InsertionAnchor.AT_DEPTH_FROM_LATEST,
                role = ChatRole.SYSTEM,
                content = "invalid",
                depthFromLatest = -1
            )
        }
    }

    @Test
    fun `prepared turn owns the frozen transform behavior`() {
        val suffixHolder = mutableListOf("-v1")
        val frozenSuffix = suffixHolder.single()
        val prepared = object : PreparedPersonaTurn {
            override val plan = PluginTurnPlan(PromptPatch("persona"))

            override fun transform(
                stage: TextStage,
                text: String,
                depth: Int?,
                isMarkdown: Boolean
            ): String = "$text$frozenSuffix"
        }

        suffixHolder[0] = "-v2"

        assertEquals("reply-v1", prepared.transform(TextStage.MODEL_OUTPUT, "reply"))
    }
}
