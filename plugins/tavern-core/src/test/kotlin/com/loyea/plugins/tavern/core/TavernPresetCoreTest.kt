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

    @Test
    fun parsesGenerationTriggersInChatPlacementAndContinueSettings() {
        val preset = TavernPresetCodec.parse(
            """
            {
              "continue_nudge_prompt": "Continue {{char}}",
              "continue_postfix": " ",
              "continue_prefill": true,
              "prompts": [
                {
                  "identifier": "continue_only",
                  "name": "Continue only",
                  "role": "assistant",
                  "content": "{{lastMessage}}",
                  "triggers": ["continue"],
                  "injection_position": 1,
                  "injection_depth": 0
                }
              ]
            }
            """.trimIndent()
        )
        requireNotNull(preset)

        assertEquals("Continue {{char}}", preset.continueNudge)
        assertEquals(" ", preset.continuePostfix)
        assertEquals(true, preset.continuePrefill)
        assertEquals(listOf("continue_only"), preset.orderedPrompts("continue").map { it.identifier })
        assertEquals(emptyList<String>(), preset.orderedPrompts("normal").map { it.identifier })
        val prompt = preset.orderedPrompts("continue").single()
        assertEquals(true, prompt.isInChat())
        assertEquals(0, prompt.injectionDepth)
    }

    @Test
    fun continuePrefillIsFrozenAsTrailingAssistantInsertion() {
        val prepared = TavernPreparedTurnFactory.prepare(
            TavernTurnSpec(
                generationType = "continue",
                continueNudge = "keep going",
                continuePrefill = true
            )
        )

        val nudge = prepared.plan.insertions.single()
        assertEquals(com.loyea.plugin.api.InsertionAnchor.AFTER_HISTORY, nudge.anchor)
        assertEquals(com.loyea.plugin.api.ChatRole.ASSISTANT, nudge.role)
        assertEquals("[CONTINUE NUDGE / 继续提示]\nkeep going", nudge.content)
    }
}
