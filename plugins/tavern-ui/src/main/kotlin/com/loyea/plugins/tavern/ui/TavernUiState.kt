package com.loyea.plugins.tavern.ui

/**
 * 平台无关的 Tavern 控制面状态。
 *
 * 仅描述"互斥类 UI 状态"（对话框/面板开关与待操作 id），不承载任何 Android /
 * Compose / 宿主角色的副作用——SAF 文件选择、Toast、分享、FileProvider、网络下载、
 * 存储写盘等都由宿主持有并按其回调执行。本类只保存布尔/枚举/ID 等纯数据。
 */
data class TavernUiState(
    val showCreateDialog: Boolean = false,
    val showResourceDialog: Boolean = false,
    // URL 导入（粘贴链接）对话框开关，与其它对话框互斥
    val showUrlImportDialog: Boolean = false,
    // D3：URL 导入输入框的当前文本，随状态机持久化（关闭/完成即清空，见 reduce）。
    val urlImportText: String = "",
    val cardToDeleteId: String? = null,
    val cardToEditId: String? = null,
    // null=编辑器未打开；""=新建预设（CREATE）；非空白串=编辑已有预设（EDIT）
    val presetToEditId: String? = null
) {
    init {
        require(cardToDeleteId == null || cardToDeleteId.isNotBlank()) {
            "Tavern delete card id must not be blank"
        }
        require(cardToEditId == null || cardToEditId.isNotBlank()) {
            "Tavern edit card id must not be blank"
        }
        require(
            listOf(
                showCreateDialog,
                showResourceDialog,
                showUrlImportDialog,
                cardToDeleteId != null,
                cardToEditId != null,
                presetToEditId != null
            ).count { it } <= 1
        ) {
            "Tavern UI dialogs must be mutually exclusive"
        }
    }

    fun reduce(event: TavernUiEvent): TavernUiState = when (event) {
        TavernUiEvent.CreateRequested -> TavernUiState(showCreateDialog = true)
        TavernUiEvent.CreateDismissed,
        TavernUiEvent.CreateCompleted -> TavernUiState()
        TavernUiEvent.ResourceRequested -> TavernUiState(showResourceDialog = true)
        TavernUiEvent.ResourceDismissed -> TavernUiState()
        TavernUiEvent.UrlImportRequested -> TavernUiState(showUrlImportDialog = true, urlImportText = "")
        is TavernUiEvent.UrlImportTextChanged ->
            TavernUiState(showUrlImportDialog = true, urlImportText = event.text)
        TavernUiEvent.UrlImportDismissed,
        TavernUiEvent.UrlImportCompleted -> TavernUiState()
        is TavernUiEvent.DeleteRequested -> TavernUiState(cardToDeleteId = event.cardId.requireCardId())
        TavernUiEvent.DeleteDismissed,
        TavernUiEvent.DeleteCompleted -> TavernUiState()
        is TavernUiEvent.EditRequested -> TavernUiState(cardToEditId = event.cardId.requireCardId())
        TavernUiEvent.EditDismissed,
        TavernUiEvent.EditCompleted -> TavernUiState()
        TavernUiEvent.PresetEditorCreateRequested -> TavernUiState(presetToEditId = "")
        is TavernUiEvent.PresetEditorEditRequested -> TavernUiState(presetToEditId = event.presetId.requirePresetId())
        TavernUiEvent.PresetEditorDismissed,
        TavernUiEvent.PresetEditorSaved -> TavernUiState()
    }

    private fun String.requireCardId(): String = trim().also {
        require(it.isNotBlank()) { "Tavern card id must not be blank" }
    }

    private fun String.requirePresetId(): String = trim().also {
        require(it.isNotBlank()) { "Tavern preset id must not be blank" }
    }
}

/** User intents understood by the Tavern control surface. */
sealed interface TavernUiEvent {
    data object CreateRequested : TavernUiEvent
    data object CreateDismissed : TavernUiEvent
    data object CreateCompleted : TavernUiEvent
    data object ResourceRequested : TavernUiEvent
    data object ResourceDismissed : TavernUiEvent
    data object UrlImportRequested : TavernUiEvent
    data object UrlImportDismissed : TavernUiEvent
    data object UrlImportCompleted : TavernUiEvent
    data class UrlImportTextChanged(val text: String) : TavernUiEvent
    data class DeleteRequested(val cardId: String) : TavernUiEvent
    data object DeleteDismissed : TavernUiEvent
    data object DeleteCompleted : TavernUiEvent
    data class EditRequested(val cardId: String) : TavernUiEvent
    data object EditDismissed : TavernUiEvent
    data object EditCompleted : TavernUiEvent
    data object PresetEditorCreateRequested : TavernUiEvent
    data class PresetEditorEditRequested(val presetId: String) : TavernUiEvent
    data object PresetEditorDismissed : TavernUiEvent
    data object PresetEditorSaved : TavernUiEvent
}
