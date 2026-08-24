package com.loyea.plugins.tavern.core

/**
 * Chat-specific SillyTavern Author's Note captured at request start.
 * Frequency is counted in user-input turns: 0 disables insertion, 1 inserts every turn.
 */
data class TavernAuthorNote(
    val text: String,
    val position: String = POSITION_IN_CHAT,
    val depth: Int = 4,
    val frequency: Int = 1
) {
    init {
        require(text.isNotBlank()) { "Author's Note text must not be blank" }
        require(depth >= 0) { "Author's Note depth must not be negative" }
        require(frequency >= 0) { "Author's Note frequency must not be negative" }
    }

    fun shouldInsert(userTurnIndex: Long): Boolean =
        frequency > 0 && userTurnIndex >= 1L && userTurnIndex % frequency.toLong() == 0L

    fun normalizedPosition(): String = when (position.trim().lowercase()) {
        "after_scenario", "after-scenario", "scenario" -> POSITION_AFTER_SCENARIO
        "in_chat", "in-chat", "chat", "" -> POSITION_IN_CHAT
        else -> POSITION_IN_CHAT
    }

    companion object {
        const val POSITION_AFTER_SCENARIO = "after_scenario"
        const val POSITION_IN_CHAT = "in_chat"
    }
}
