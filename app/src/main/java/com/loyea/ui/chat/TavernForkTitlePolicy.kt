package com.loyea.ui.chat

/** Input boundary shared by the branch/checkpoint dialog and its ViewModel entry point. */
object TavernForkTitlePolicy {
    const val MAX_LENGTH = 80

    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value.length > MAX_LENGTH) return null
        if (value.any(Char::isISOControl)) return null
        return value
    }
}
