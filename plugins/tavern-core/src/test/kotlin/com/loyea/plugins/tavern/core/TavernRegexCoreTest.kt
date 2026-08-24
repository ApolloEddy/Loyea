package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRegexCoreTest {
    @Test
    fun expandsRequestScopedCardHistoryGenerationAndReadOnlyVariables() {
        val context = TavernMacroContext(
            characterName = "Lya",
            description = "A companion",
            userName = "Eddy",
            personality = "warm",
            scenario = "at home",
            personaDescription = "patient engineer",
            charPrompt = "core rules",
            charInstruction = "stay concise",
            charCreatorNotes = "creator note",
            charVersion = "2.1",
            charFirstMessage = "Hello",
            messageExamples = "<START>",
            lastMessage = "last turn",
            lastUserMessage = "how are you?",
            lastCharMessage = "I am well.",
            input = "how are you?",
            original = "original prompt",
            generationType = "continue",
            authorNote = "remember the rain",
            outlets = mapOf("Lore" to "secret lore"),
            localVariables = mapOf("mood" to "calm"),
            globalVariables = mapOf("app" to "Loyea")
        )

        assertEquals(
            "Lya|Eddy|A companion|warm|at home|patient engineer|core rules|stay concise|creator note|2.1|Hello|<START>|last turn|how are you?|I am well.|how are you?|original prompt|continue|remember the rain|secret lore|calm|true|Loyea|true",
            TavernMacroEngine.expand(
                "{{char}}|{{user}}|{{description}}|{{personality}}|{{scenario}}|" +
                    "{{persona}}|{{charPrompt}}|{{charInstruction}}|{{charCreatorNotes}}|{{charVersion}}|" +
                    "{{charFirstMessage}}|{{mesExamples}}|{{lastMessage}}|{{lastUserMessage}}|" +
                    "{{lastCharMessage}}|{{input}}|{{original}}|{{lastGenerationType}}|{{authorsNote}}|" +
                    "{{outlet::lore}}|{{getvar::mood}}|{{hasvar::mood}}|{{getglobalvar::app}}|{{hasglobalvar::app}}",
                context
            )
        )
        assertEquals("{{futureMacro::value}}", TavernMacroEngine.expand("{{futureMacro::value}}", context))
    }

    @Test
    fun supportsNestedLegacyConditionalAndFrozenTimeMacros() {
        val context = TavernMacroContext(
            characterName = "Lya",
            userName = "Eddy",
            description = "",
            localVariables = mapOf("Lya_mood" to "calm"),
            alternateGreetings = listOf("first", "second"),
            timestampMillis = 1_725_000_000_000L
        )

        assertEquals("calm", TavernMacroEngine.expand("{{getvar::{{char}}_mood}}", context))
        assertEquals("fallback", TavernMacroEngine.expand("{{if description}}hidden{{else}}fallback{{/if}}", context))
        assertEquals("second", TavernMacroEngine.expand("{{charFirstMessage::1}}", context))
        assertEquals("Lya/Eddy", TavernMacroEngine.expand("<CHAR>/<USER>", context))
        assertTrue(TavernMacroEngine.expand("{{isodate}} {{isotime}}", context).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

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
