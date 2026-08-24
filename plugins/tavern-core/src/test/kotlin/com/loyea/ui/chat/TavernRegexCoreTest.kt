package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TavernRegexCoreTest {
    @Test
    fun appliesMacrosCaptureGroupsPlacementsAndDepthWithoutHostCard() {
        val scripts = TavernRegexEngine.parseScripts(
            """
            {
              "regex_scripts": [
                {
                  "id": "macro",
                  "findRegex": "({{char}})",
                  "replaceString": "[$1 by {{user}}]",
                  "placement": [2],
                  "substituteRegex": 2,
                  "minDepth": 1,
                  "maxDepth": 2
                },
                {
                  "id": "reason",
                  "findRegex": "/secret/g",
                  "replaceString": "[redacted]",
                  "placement": [6]
                }
              ]
            }
            """.trimIndent()
        )
        val context = TavernMacroContext(
            characterName = "A.B",
            description = "host-independent",
            userName = "Eddy"
        )

        assertEquals(
            "A.B",
            TavernRegexEngine.apply(
                "A.B",
                scripts,
                TavernRegexPlacement.AI_OUTPUT,
                context,
                depth = 0
            )
        )
        assertEquals(
            "[A.B by Eddy]",
            TavernRegexEngine.apply(
                "A.B",
                scripts,
                TavernRegexPlacement.AI_OUTPUT,
                context,
                depth = 1
            )
        )
        assertEquals(
            "[redacted]",
            TavernRegexEngine.apply(
                "secret",
                scripts,
                TavernRegexPlacement.REASONING,
                context
            )
        )
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
                TavernMacroContext(),
                isPrompt = true
            )
        )
    }
}
