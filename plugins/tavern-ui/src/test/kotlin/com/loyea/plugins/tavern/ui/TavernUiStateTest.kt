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

    // 新建预设：PresetEditorCreateRequested 打开编辑器（presetToEditId 为空串表示新建），Saved/Dismissed 关闭并回到空状态
    @Test
    fun presetEditorCreateCanOpenAndClose() {
        val created = TavernUiState().reduce(TavernUiEvent.PresetEditorCreateRequested)
        assertEquals("", created.presetToEditId)
        assertFalse(created.showCreateDialog)
        assertFalse(created.showResourceDialog)
        assertNull(created.cardToDeleteId)
        assertNull(created.cardToEditId)

        assertEquals(TavernUiState(), created.reduce(TavernUiEvent.PresetEditorSaved))
        assertEquals(TavernUiState(), created.reduce(TavernUiEvent.PresetEditorDismissed))
    }

    // 编辑预设：PresetEditorEditRequested 携带非空 presetId 打开编辑器，Dismissed/Saved 关闭并清空相关状态
    @Test
    fun presetEditorEditOpensWithIdAndCloses() {
        val edited = TavernUiState().reduce(TavernUiEvent.PresetEditorEditRequested("preset-edit"))
        assertEquals("preset-edit", edited.presetToEditId)
        assertFalse(edited.showCreateDialog)
        assertFalse(edited.showResourceDialog)
        assertNull(edited.cardToDeleteId)
        assertNull(edited.cardToEditId)

        assertEquals(TavernUiState(), edited.reduce(TavernUiEvent.PresetEditorDismissed))
        assertEquals(TavernUiState(), edited.reduce(TavernUiEvent.PresetEditorSaved))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankPresetIdCannotBecomeEditorSelection() {
        TavernUiState().reduce(TavernUiEvent.PresetEditorEditRequested("  "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun presetEditorCannotBeActiveAlongsideAnotherDialog() {
        TavernUiState(showCreateDialog = true, presetToEditId = "")
    }

    // 互斥：同一 TavernUiState 实例至多一个激活操作——打开预设编辑器后再打开其它对话框，会替换（清空）预设编辑器状态
    @Test
    fun openingOtherDialogReplacesPresetEditor() {
        val replaced = TavernUiState()
            .reduce(TavernUiEvent.PresetEditorCreateRequested)
            .reduce(TavernUiEvent.CreateRequested)
        assertTrue(replaced.showCreateDialog)
        assertNull(replaced.presetToEditId)
    }

    // URL 导入对话框：Requested 打开并关掉其它互斥状态，Dismissed/Completed 关闭并回到空状态
    @Test
    fun urlImportDialogCanOpenAndClose() {
        val opened = TavernUiState().reduce(TavernUiEvent.UrlImportRequested)
        assertTrue(opened.showUrlImportDialog)
        assertFalse(opened.showCreateDialog)
        assertFalse(opened.showResourceDialog)
        assertNull(opened.cardToDeleteId)
        assertNull(opened.cardToEditId)
        assertNull(opened.presetToEditId)

        assertEquals(TavernUiState(), opened.reduce(TavernUiEvent.UrlImportDismissed))
        assertEquals(TavernUiState(), opened.reduce(TavernUiEvent.UrlImportCompleted))
    }

    // 互斥：打开 URL 导入对话框会替换其它对话框状态，反之亦然
    @Test
    fun urlImportDialogReplacesAndIsReplacedByOtherDialogs() {
        val replaced = TavernUiState()
            .reduce(TavernUiEvent.CreateRequested)
            .reduce(TavernUiEvent.UrlImportRequested)
        assertTrue(replaced.showUrlImportDialog)
        assertFalse(replaced.showCreateDialog)

        val replacedBack = replaced.reduce(TavernUiEvent.ResourceRequested)
        assertTrue(replacedBack.showResourceDialog)
        assertFalse(replacedBack.showUrlImportDialog)
    }

    // 互斥守卫：URL 导入对话框不可与其它对话框同时激活
    @Test(expected = IllegalArgumentException::class)
    fun urlImportDialogCannotBeActiveAlongsideAnotherDialog() {
        TavernUiState(showUrlImportDialog = true, showResourceDialog = true)
    }

    // D3：URL 导入文本随状态机持久化——打开清空、键入累积、关闭/完成清空。
    @Test
    fun urlImportTextTypingAccumulatesAndResetsOnClose() {
        val opened = TavernUiState().reduce(TavernUiEvent.UrlImportRequested)
        assertEquals("", opened.urlImportText)

        val typed = opened.reduce(TavernUiEvent.UrlImportTextChanged("https://chub.ai"))
        assertEquals("https://chub.ai", typed.urlImportText)
        assertTrue(typed.showUrlImportDialog)

        val more = typed.reduce(TavernUiEvent.UrlImportTextChanged("https://chub.ai/characters/1"))
        assertEquals("https://chub.ai/characters/1", more.urlImportText)

        val closed = more.reduce(TavernUiEvent.UrlImportDismissed)
        assertEquals(TavernUiState(), closed)
        assertEquals("", closed.urlImportText)

        val reopened = TavernUiState().reduce(TavernUiEvent.UrlImportRequested)
        assertEquals("", reopened.urlImportText)
    }

    @Test
    fun urlImportTextClearsOnCompleted() {
        val typed = TavernUiState()
            .reduce(TavernUiEvent.UrlImportRequested)
            .reduce(TavernUiEvent.UrlImportTextChanged("https://chub.ai"))
        assertEquals("", typed.reduce(TavernUiEvent.UrlImportCompleted).urlImportText)
    }
}
