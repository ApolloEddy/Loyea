package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernMacroRuntimeParityTest {
    @Test
    fun exposesReadOnlyHistoryTimeAndRuntimeMacrosFromFrozenContext() {
        val context = TavernMacroContext(
            characterName = "Alice",
            userName = "Eddy",
            lastMessageId = "7",
            timestampMillis = 1_725_000_000_000L,
            lastUserMessageTimestampMillis = 1_724_996_338_999L,
            isMobile = false,
            extensionNames = setOf("Quick Reply")
        )

        assertEquals("0-7", TavernMacroEngine.expand("{{allChatRange}}", context))
        assertEquals("1h 1m 1s", TavernMacroEngine.expand("{{idleDuration}}", context))
        assertEquals("1h 1m 1s", TavernMacroEngine.expand("{{timeDiff::1724996338999::1725000000000}}", context))
        assertEquals("hello", TavernMacroEngine.expand("{{trim::  hello  }}", context))
        assertEquals("false|true", TavernMacroEngine.expand("{{isMobile}}|{{hasExtension::quick reply}}", context))
        assertTrue(
            TavernMacroEngine.expand("{{time::UTC+08:00}}", context)
                .matches(Regex("\\d{2}:\\d{2}:\\d{2} GMT\\+08:00"))
        )
    }

    @Test
    fun randomPickAndRollAreBoundedByTheFrozenRequestSeed() {
        val context = TavernMacroContext(macroSeed = 42L)
        val random = TavernMacroEngine.expand("{{random::red::green::blue}}", context)
        val pick = TavernMacroEngine.expand("{{pick::red::green::blue}}", context)
        val roll = TavernMacroEngine.expand("{{roll::2d6+3}}", context).toInt()

        assertTrue(random in setOf("red", "green", "blue"))
        assertEquals(random, TavernMacroEngine.expand("{{random::red::green::blue}}", context))
        assertEquals(pick, TavernMacroEngine.expand("{{pick::red::green::blue}}", context))
        assertTrue(roll in 5..15)
    }

    @Test
    fun writeMacrosRemainLiteralAndDoNotMutateFrozenVariables() {
        val context = TavernMacroContext(localVariables = mapOf("count" to "1"))

        assertEquals("{{setvar::count::2}}", TavernMacroEngine.expand("{{setvar::count::2}}", context))
        assertEquals("1", TavernMacroEngine.expand("{{getvar::count}}", context))
    }
}
