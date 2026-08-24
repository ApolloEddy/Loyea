package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRegexEngineTest {
    @Test
    fun parsesCardScopedScriptsAndAppliesGlobalCaptureReplacement() {
        val card = CharacterCard(
            id = "regex-card",
            name = "Lya",
            shortIntro = "short",
            systemPrompt = "system",
            extensionsJson = """
                {
                  "regex_scripts": [
                    {
                      "id": "bold",
                      "scriptName": "Bold tags",
                      "findRegex": "/<(speech)>(.*?)<\\/\\1>/gi",
                      "replaceString": "**{{match}}**",
                      "placement": [2]
                    }
                  ]
                }
            """.trimIndent()
        )
        val scripts = TavernCardRegexAdapter.scriptsFrom(card)
        assertEquals(1, scripts.size)
        assertEquals(
            "**<speech>hello</speech>** and **<speech>bye</speech>**",
            TavernRegexEngine.apply(
                "<speech>hello</speech> and <speech>bye</speech>",
                scripts,
                TavernRegexPlacement.AI_OUTPUT,
                TavernCardRegexAdapter.macroContext(card, "User")
            )
        )
    }

    @Test
    fun supportsMacrosTrimCaptureGroupsPlacementAndDepth() {
        val scripts = TavernRegexEngine.parseScripts(
            """
            {
              "regex_scripts": [
                {
                  "id": "macro",
                  "findRegex": "({{char}})",
                  "replaceString": "[$1]",
                  "placement": [2],
                  "substituteRegex": 2,
                  "minDepth": 1,
                  "maxDepth": 2
                },
                {
                  "id": "group",
                  "findRegex": "/(?<word>secret)/",
                  "replaceString": "[$<word>]",
                  "placement": [5]
                }
              ]
            }
            """.trimIndent()
        )
        val card = CharacterCard("id", "A.B", shortIntro = "", systemPrompt = "")
        val context = TavernCardRegexAdapter.macroContext(card, "User")
        assertEquals("A.B", TavernRegexEngine.apply("A.B", scripts, TavernRegexPlacement.AI_OUTPUT, context, depth = 0))
        assertEquals("[A.B]", TavernRegexEngine.apply("A.B", scripts, TavernRegexPlacement.AI_OUTPUT, context, depth = 1))
        assertEquals("[secret]", TavernRegexEngine.apply("secret", scripts, TavernRegexPlacement.WORLD_INFO, context))
        assertTrue(TavernRegexEngine.apply("secret", scripts, TavernRegexPlacement.AI_OUTPUT, context) == "secret")
    }

    @Test
    fun parsesExternalArrayAndReasoningPlacement() {
        val scripts = TavernRegexEngine.parseScripts(
            """[{"id":"reason","findRegex":"/secret/g","replaceString":"[redacted]","placement":[6]}]"""
        )
        val context = TavernMacroContext(characterName = "A")
        assertEquals("[redacted]", TavernRegexEngine.apply(
            "secret", scripts, TavernRegexPlacement.REASONING, context
        ))
        assertEquals("secret", TavernRegexEngine.apply(
            "secret", scripts, TavernRegexPlacement.AI_OUTPUT, context
        ))
    }

    @Test
    fun unqualifiedWorldInfoScriptRunsInPromptStage() {
        val scripts = TavernRegexEngine.parseScripts(
            """[{"id":"world","findRegex":"/secret/g","replaceString":"[redacted]","placement":[5]}]"""
        )
        assertEquals(
            "[redacted]",
            TavernRegexEngine.apply(
                "secret",
                scripts,
                TavernRegexPlacement.WORLD_INFO,
                TavernMacroContext(characterName = "A"),
                isPrompt = true
            )
        )
    }
}
