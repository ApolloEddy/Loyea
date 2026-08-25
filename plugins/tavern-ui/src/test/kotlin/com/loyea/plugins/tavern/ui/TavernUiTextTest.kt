package com.loyea.plugins.tavern.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3：Tavern 控制面纯文本/校验逻辑的表征测试。 */
class TavernUiTextTest {

    @Test
    fun parseTavernListInputSplitsOnCommaNewlineAndDeduplicates() {
        assertEquals(listOf("a", "b", "c"), TavernUiText.parseTavernListInput("a, b\nc,,b"))
        assertEquals(emptyList<String>(), TavernUiText.parseTavernListInput(" , \n "))
    }

    @Test
    fun parseGreetingInputKeepsVisibleLinesOutsideSeparators() {
        assertEquals(
            listOf("hello", "again"),
            TavernUiText.parseGreetingInput("hello\n---\n\nagain\n")
        )
        assertEquals(emptyList<String>(), TavernUiText.parseGreetingInput("\n---\n"))
    }

    @Test
    fun optionalJsonValidationAcceptsOnlyBlankOrWholeObjects() {
        assertTrue(TavernUiText.isOptionalJsonObjectValid(""))
        assertTrue(TavernUiText.isOptionalJsonObjectValid("  "))
        assertTrue(TavernUiText.isOptionalJsonObjectValid("""{"k":1}"""))
        assertFalse(TavernUiText.isOptionalJsonObjectValid("not json"))
        assertFalse(TavernUiText.isOptionalJsonObjectValid("""[1,2]"""))
        assertFalse(TavernUiText.isOptionalJsonObjectValid("""{"unclosed":1"""))
    }

    @Test
    fun avatarPaletteIsStableAndNonEmpty() {
        assertTrue(TavernUiText.AVATAR_PALETTE.isNotEmpty())
        assertEquals(TavernUiText.AVATAR_PALETTE, TavernUiText.AVATAR_PALETTE.distinct())
    }
}
