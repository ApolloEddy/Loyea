package com.loyea.ui.chat

import com.loyea.plugin.api.PersonaRef

/** Exact ownership map for legacy cards until persisted sessions carry an explicit PersonaRef. */
object CharacterPersonaOwnership {
    private val nativeCardsById: Map<String, CharacterCard> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TavernCardParser.getBuiltInCards().associateBy(CharacterCard::id)
    }

    fun refFor(card: CharacterCard): PersonaRef =
        if (card.id in nativeCardsById) {
            PersonaRef.native(card.id)
        } else {
            PersonaRef.plugin(TavernPluginDefinition.ID, card.id)
        }

    /** Canonical native cards win ID collisions; missing plugin-owned cards remain unavailable. */
    fun resolveBoundCard(characterId: String, storedCards: List<CharacterCard>): CharacterCard? =
        nativeCardsById[characterId] ?: storedCards.firstOrNull { it.id == characterId }

    fun defaultNativeCard(): CharacterCard =
        checkNotNull(nativeCardsById[DEFAULT_NATIVE_PERSONA_ID]) { "Default Loyea persona is missing" }

    private const val DEFAULT_NATIVE_PERSONA_ID = "char_loyea_default"
}
