package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernPresetCodecTest {
    private val presetJson = """
        {
          "name": "Bound preset",
          "temperature": 0.4,
          "top_p": 0.8,
          "openai_max_context": 8192,
          "wi_format": "<world>{{world_info}}</world>",
          "post_history_instructions": "Stay in character.",
          "prompts": [
            {"name":"Main Prompt","identifier":"main","role":"system","content":"MAIN {{char}}"},
            {"name":"World Rule","identifier":"world_rule","role":"system","content":"WORLD RULE"},
            {"name":"Post History","identifier":"post_history","role":"system","content":"POST SLOT"}
          ],
          "prompt_order": [
            {"character_id": 1, "order": [
              {"identifier":"world_rule","enabled":true},
              {"identifier":"main","enabled":true},
              {"identifier":"post_history","enabled":true}
            ]}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesCommonOpenAiPresetFieldsAndPromptOrder() {
        val card = CharacterCard(
            id = "preset-card",
            name = "Lya",
            shortIntro = "short",
            systemPrompt = "system",
            extensionsJson = """{"preset":$presetJson}"""
        )
        val preset = TavernPresetCodec.fromCard(card)
        requireNotNull(preset)
        assertEquals(0.4, preset.temperature!!, 0.001)
        assertEquals(8192, preset.maxContext)
        assertEquals(listOf("world_rule", "main", "post_history"), preset.orderedPrompts().map { it.identifier })
        assertEquals("Stay in character.\n\nPOST SLOT", preset.explicitPostHistoryInstructions())
    }

    @Test
    fun appliesBoundPresetToPromptStackWorldInfoAndPostHistory() {
        val card = CharacterCard(
            id = "preset-card",
            name = "Lya",
            shortIntro = "short",
            systemPrompt = "system",
            extensionsJson = """{"preset":$presetJson}"""
        )
        val prompt = PromptAssembler.assemblePromptParts(
            card = card,
            userName = "Eddy",
            worldInfo = "ignored",
            worldInfoRender = WorldInfoMatcher.WorldInfoRenderResult(all = "lore")
        )
        assertTrue(prompt.stableSystemPrompt.contains("WORLD RULE"))
        assertTrue(prompt.stableSystemPrompt.contains("MAIN Lya"))
        assertTrue(prompt.turnContextSnapshot.contains("<world>lore</world>"))
        assertTrue(prompt.postHistoryInstructions.contains("Stay in character."))
        assertTrue(prompt.postHistoryInstructions.contains("POST SLOT"))
    }

    @Test
    fun parsesPresetScopedRegexScriptsAlongsidePromptSlots() {
        val preset = TavernPresetCodec.parse(
            """{"regex_scripts":[{"id":"p","findRegex":"/foo/g","replaceString":"bar","placement":[2]}]}"""
        )
        requireNotNull(preset)
        assertEquals(1, preset.regexScripts.size)
        assertEquals("bar", TavernRegexEngine.applyOutput("foo", preset.regexScripts, CharacterCard("c", "C", shortIntro = "", systemPrompt = ""), "U"))
    }
}
