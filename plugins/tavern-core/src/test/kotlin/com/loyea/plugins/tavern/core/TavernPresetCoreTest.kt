package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernPresetCoreTest {
    @Test
    fun parsesPromptOrderGenerationPatchAndScopedRegexWithoutHostCard() {
        val preset = TavernPresetCodec.parse(
            """
            {
              "name": "Bound preset",
              "temperature": 0.4,
              "top_p": 0.8,
              "openai_max_context": 8192,
              "openai_max_tokens": 512,
              "name_behavior": "never",
              "stop": ["STOP", "END"],
              "post_history_instructions": "Stay in character.",
              "prompts": [
                {"name":"Main Prompt","identifier":"main","content":"MAIN"},
                {"name":"World Rule","identifier":"world_rule","content":"WORLD"},
                {"name":"Post History","identifier":"post_history","content":"POST SLOT"}
              ],
              "prompt_order": [{"order": [
                {"identifier":"world_rule","enabled":true},
                {"identifier":"main","enabled":true},
                {"identifier":"post_history","enabled":true}
              ]}],
              "regex_scripts": [
                {"id":"p","findRegex":"/foo/g","replaceString":"bar","placement":[2]}
              ]
            }
            """.trimIndent()
        )
        requireNotNull(preset)

        assertEquals(listOf("world_rule", "main", "post_history"), preset.orderedPrompts().map { it.identifier })
        assertEquals("Stay in character.\n\nPOST SLOT", preset.explicitPostHistoryInstructions())
        assertEquals(512, preset.generationOverrides().maxOutputTokens)
        assertEquals(false, preset.includeNames)
        assertEquals(8192, preset.generationOverrides().maxContextTokens)
        assertEquals(listOf("STOP", "END"), preset.generationOverrides().stopStrings)
        assertEquals(
            "bar",
            TavernRegexEngine.applyOutput(
                "foo",
                preset.regexScripts,
                TavernMacroContext(characterName = "C", userName = "U")
            )
        )
    }
}
