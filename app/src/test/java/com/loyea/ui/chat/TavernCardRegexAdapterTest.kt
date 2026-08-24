package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernCardRegexAdapterTest {
    @Test
    fun findsScriptsInCardExtensionsAndBuildsFrozenMacroContext() {
        val card = CharacterCard(
            id = "regex-card",
            name = "Lya",
            shortIntro = "short",
            systemPrompt = "system",
            nickname = "Loyea",
            description = "",
            extensionsJson = """
                {"regex_scripts":[{"id":"r","findRegex":"/x/g","replaceString":"y","placement":[2]}]}
            """.trimIndent()
        )

        assertEquals("r", TavernCardRegexAdapter.scriptsFrom(card).single().id)
        assertEquals(
            TavernMacroContext("Loyea", "short", "Eddy"),
            TavernCardRegexAdapter.macroContext(card, "Eddy")
        )
    }

    @Test
    fun findsVendorCollectionNestedInOriginalCardJson() {
        val card = CharacterCard(
            id = "nested-regex-card",
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
                        "regex_collection": [
                          {"id":"nested","findRegex":"/x/g","replaceString":"y","placement":[2]}
                        ]
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        assertEquals("nested", TavernCardRegexAdapter.scriptsFrom(card).single().id)
    }
}
