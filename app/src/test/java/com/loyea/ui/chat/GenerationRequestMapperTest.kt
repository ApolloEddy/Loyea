package com.loyea.ui.chat

import com.google.gson.JsonObject
import com.loyea.plugin.api.GenerationPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRequestMapperTest {
    private val patch = GenerationPatch(
        modelHint = "suggested-model",
        temperature = 0.4,
        topP = 0.8,
        topK = 40,
        maxOutputTokens = 512,
        maxContextTokens = 8192,
        frequencyPenalty = 0.2,
        presencePenalty = 0.3,
        repetitionPenalty = 1.1,
        stopStrings = listOf("STOP", "END")
    )

    @Test
    fun `strict providers receive only portable generation fields`() {
        val json = JsonObject()

        GenerationRequestMapper.apply(json, provider = "DeepSeek", patch = patch)

        assertEquals(0.4, json["temperature"].asDouble, 0.001)
        assertEquals(0.8, json["top_p"].asDouble, 0.001)
        assertEquals(512, json["max_tokens"].asInt)
        assertEquals(listOf("STOP", "END"), json["stop"].asJsonArray.map { it.asString })
        assertFalse(json.has("top_k"))
        assertFalse(json.has("repetition_penalty"))
        assertFalse(json.has("model"))
        assertFalse(json.has("max_context_tokens"))
    }

    @Test
    fun `local providers may receive extended generation fields`() {
        val json = JsonObject()

        GenerationRequestMapper.apply(json, provider = "Local", patch = patch)

        assertEquals(40, json["top_k"].asInt)
        assertEquals(1.1, json["repetition_penalty"].asDouble, 0.001)
        assertTrue(json.has("frequency_penalty"))
        assertTrue(json.has("presence_penalty"))
    }
}
