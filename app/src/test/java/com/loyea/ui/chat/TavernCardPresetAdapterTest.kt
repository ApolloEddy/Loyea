package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernCardPresetAdapterTest {
    @Test
    fun findsPresetNestedInOriginalCardVendorMetadata() {
        val card = CharacterCard(
            id = "nested-preset-card",
            name = "Lya",
            shortIntro = "short",
            systemPrompt = "system",
            extensionsJson = "{}",
            originalCardJson = """
                {
                  "spec": "chara_card_v2",
                  "data": {
                    "extensions": {
                      "vendor": {
                        "prompt_preset": "{\"name\":\"Nested\",\"temperature\":0.25}"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val preset = TavernCardPresetAdapter.presetFrom(card)
        requireNotNull(preset)
        assertEquals("Nested", preset.name)
        assertEquals(0.25, preset.temperature!!, 0.001)
    }
}
