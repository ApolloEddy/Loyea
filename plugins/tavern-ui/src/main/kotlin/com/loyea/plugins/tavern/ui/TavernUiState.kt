package com.loyea.plugins.tavern.ui

/** Platform-neutral state for the Tavern control surface. */
data class TavernUiState(
    val showCreateDialog: Boolean = false,
    val showResourceDialog: Boolean = false,
    val cardToDeleteId: String? = null,
    val cardToEditId: String? = null
) {
    init {
        require(cardToDeleteId == null || cardToDeleteId.isNotBlank()) {
            "Tavern delete card id must not be blank"
        }
        require(cardToEditId == null || cardToEditId.isNotBlank()) {
            "Tavern edit card id must not be blank"
        }
        require(listOf(showCreateDialog, showResourceDialog, cardToDeleteId != null, cardToEditId != null).count { it } <= 1) {
            "Tavern UI dialogs must be mutually exclusive"
        }
    }

    fun reduce(event: TavernUiEvent): TavernUiState = when (event) {
        TavernUiEvent.CreateRequested -> TavernUiState(showCreateDialog = true)
        TavernUiEvent.CreateDismissed,
        TavernUiEvent.CreateCompleted -> TavernUiState()
        TavernUiEvent.ResourceRequested -> TavernUiState(showResourceDialog = true)
        TavernUiEvent.ResourceDismissed -> TavernUiState()
        is TavernUiEvent.DeleteRequested -> TavernUiState(cardToDeleteId = event.cardId.requireCardId())
        TavernUiEvent.DeleteDismissed,
        TavernUiEvent.DeleteCompleted -> TavernUiState()
        is TavernUiEvent.EditRequested -> TavernUiState(cardToEditId = event.cardId.requireCardId())
        TavernUiEvent.EditDismissed,
        TavernUiEvent.EditCompleted -> TavernUiState()
    }

    private fun String.requireCardId(): String = trim().also {
        require(it.isNotBlank()) { "Tavern card id must not be blank" }
    }
}

/** User intents understood by the Tavern control surface. */
sealed interface TavernUiEvent {
    data object CreateRequested : TavernUiEvent
    data object CreateDismissed : TavernUiEvent
    data object CreateCompleted : TavernUiEvent
    data object ResourceRequested : TavernUiEvent
    data object ResourceDismissed : TavernUiEvent
    data class DeleteRequested(val cardId: String) : TavernUiEvent
    data object DeleteDismissed : TavernUiEvent
    data object DeleteCompleted : TavernUiEvent
    data class EditRequested(val cardId: String) : TavernUiEvent
    data object EditDismissed : TavernUiEvent
    data object EditCompleted : TavernUiEvent
}
