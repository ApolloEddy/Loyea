package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavernForkTitlePolicyTest {
    @Test
    fun `trims safe title`() {
        assertEquals("Branch", TavernForkTitlePolicy.normalize("  Branch  "))
    }

    @Test
    fun `rejects empty oversized and control input`() {
        assertNull(TavernForkTitlePolicy.normalize(" \t\n"))
        assertNull(TavernForkTitlePolicy.normalize("x".repeat(TavernForkTitlePolicy.MAX_LENGTH + 1)))
        assertNull(TavernForkTitlePolicy.normalize("bad\u0000name"))
    }
}
