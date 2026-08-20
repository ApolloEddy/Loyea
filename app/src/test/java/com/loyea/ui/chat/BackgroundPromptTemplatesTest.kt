package com.loyea.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPromptTemplatesTest {

    @Test
    fun memoryAndCompressionPutChangingConversationDataInUserPayload() {
        val history = listOf(Message("u", "private-history-marker", Sender.USER, timestamp = 1L))
        val memoryInput = BackgroundPromptTemplates.memoryConsolidationInput(
            coreFacts = listOf("★ locked-marker"),
            normalFacts = listOf("normal-marker"),
            history = history
        )
        val compressionInput = BackgroundPromptTemplates.compressionInput(
            existingSummary = "old-summary-marker",
            segmentText = "new-segment-marker"
        )

        assertFalse(BackgroundPromptTemplates.MEMORY_CONSOLIDATION_SYSTEM.contains("private-history-marker"))
        assertFalse(BackgroundPromptTemplates.CONVERSATION_COMPRESSION_SYSTEM.contains("new-segment-marker"))
        assertTrue(memoryInput.contains("private-history-marker"))
        assertTrue(memoryInput.contains("locked-marker"))
        assertTrue(compressionInput.contains("old-summary-marker"))
        assertTrue(compressionInput.contains("new-segment-marker"))
    }

    @Test
    fun titleAndGreetingKeepTaskRulesStableWhileInputsVary() {
        val titleInput = BackgroundPromptTemplates.smartTitleInput("user-marker", "assistant-marker")
        val greetingSystem = BackgroundPromptTemplates.greetingSystem("stable-role", "Eddy")
        val greetingEvent = BackgroundPromptTemplates.greetingEventInput("time-and-physical-marker")

        assertFalse(BackgroundPromptTemplates.SMART_TITLE_SYSTEM.contains("user-marker"))
        assertTrue(titleInput.contains("user-marker"))
        assertTrue(titleInput.contains("assistant-marker"))
        assertTrue(greetingSystem.startsWith("stable-role"))
        assertFalse(greetingSystem.contains("time-and-physical-marker"))
        assertTrue(greetingEvent.contains("time-and-physical-marker"))
    }
}
