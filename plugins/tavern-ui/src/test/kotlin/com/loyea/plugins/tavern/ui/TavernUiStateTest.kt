package com.loyea.plugins.tavern.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernUiStateTest {
    @Test
    fun createDialogCanOpenAndCloseWithoutLeakingOtherSelection() {
        val state = TavernUiState()
            .reduce(TavernUiEvent.EditRequested("card-edit"))
            .reduce(TavernUiEvent.CreateRequested)

        assertTrue(state.showCreateDialog)
        assertFalse(state.showResourceDialog)
        assertNull(state.cardToDeleteId)
        assertNull(state.cardToEditId)
        assertEquals(TavernUiState(), state.reduce(TavernUiEvent.CreateDismissed))
    }

    @Test
    fun resourceDialogReplacesCreateDialog() {
        val state = TavernUiState()
            .reduce(TavernUiEvent.CreateRequested)
            .reduce(TavernUiEvent.ResourceRequested)

        assertFalse(state.showCreateDialog)
        assertTrue(state.showResourceDialog)
        assertNull(state.cardToDeleteId)
        assertNull(state.cardToEditId)
        assertEquals(TavernUiState(), state.reduce(TavernUiEvent.ResourceDismissed))
    }

    @Test
    fun cardSelectionIsIdBasedAndCompletionClosesDialog() {
        val deleteState = TavernUiState().reduce(TavernUiEvent.DeleteRequested("card-delete"))
        assertEquals("card-delete", deleteState.cardToDeleteId)
        assertFalse(deleteState.showCreateDialog)
        assertFalse(deleteState.showResourceDialog)

        val editState = deleteState.reduce(TavernUiEvent.EditRequested("card-edit"))
        assertEquals("card-edit", editState.cardToEditId)
        assertNull(editState.cardToDeleteId)
        assertEquals(TavernUiState(), editState.reduce(TavernUiEvent.EditCompleted))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCardIdCannotBecomeDialogSelection() {
        TavernUiState().reduce(TavernUiEvent.DeleteRequested("  "))
    }
}
