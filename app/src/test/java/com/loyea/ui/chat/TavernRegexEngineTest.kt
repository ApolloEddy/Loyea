package com.loyea.ui.chat

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
        val scripts = TavernRegexEngine.fromCard(card)
        assertEquals(1, scripts.size)
        assertEquals(
            "**<speech>hello</speech>** and **<speech>bye</speech>**",
            TavernRegexEngine.apply(
                "<speech>hello</speech> and <speech>bye</speech>",
                scripts,
                TavernRegexPlacement.AI_OUTPUT,
                card
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
        assertEquals("A.B", TavernRegexEngine.apply("A.B", scripts, TavernRegexPlacement.AI_OUTPUT, card, depth = 0))
        assertEquals("[A.B]", TavernRegexEngine.apply("A.B", scripts, TavernRegexPlacement.AI_OUTPUT, card, depth = 1))
        assertEquals("[secret]", TavernRegexEngine.apply("secret", scripts, TavernRegexPlacement.WORLD_INFO, card))
        assertTrue(TavernRegexEngine.apply("secret", scripts, TavernRegexPlacement.AI_OUTPUT, card) == "secret")
    }

    @Test
    fun parsesExternalArrayAndReasoningPlacement() {
        val scripts = TavernRegexEngine.parseScripts(
            """[{"id":"reason","findRegex":"/secret/g","replaceString":"[redacted]","placement":[6]}]"""
        )
        val card = CharacterCard("id", "A", shortIntro = "", systemPrompt = "")
        assertEquals("[redacted]", TavernRegexEngine.apply(
            "secret", scripts, TavernRegexPlacement.REASONING, card
        ))
        assertEquals("secret", TavernRegexEngine.apply(
            "secret", scripts, TavernRegexPlacement.AI_OUTPUT, card
        ))
    }

    @Test
    fun unqualifiedWorldInfoScriptRunsInPromptStage() {
        val scripts = TavernRegexEngine.parseScripts(
            """[{"id":"world","findRegex":"/secret/g","replaceString":"[redacted]","placement":[5]}]"""
        )
        val card = CharacterCard("id", "A", shortIntro = "", systemPrompt = "")
        assertEquals(
            "[redacted]",
            TavernRegexEngine.apply(
                "secret",
                scripts,
                TavernRegexPlacement.WORLD_INFO,
                card,
                isPrompt = true
            )
        )
    }
}
