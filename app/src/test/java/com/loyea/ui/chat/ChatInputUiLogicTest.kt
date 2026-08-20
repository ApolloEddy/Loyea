package com.loyea.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputUiLogicTest {

    @Test
    fun expandButtonAppearsOnlyAfterTheThirdRenderedLine() {
        assertFalse(shouldShowExpandedEditor(0))
        assertFalse(shouldShowExpandedEditor(1))
        assertFalse(shouldShowExpandedEditor(3))
        assertTrue(shouldShowExpandedEditor(4))
        assertTrue(shouldShowExpandedEditor(5))
    }
}
