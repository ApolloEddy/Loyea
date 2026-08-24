package com.loyea.ui.chat

import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginIds

data class BoundCharacterPersona(
    val ref: PersonaRef,
    val card: CharacterCard
)

data class PersonaBindingSnapshot(
    val sessionId: String,
    val sessionIncarnationId: String,
    val personaBindingRevision: Long,
    val ref: PersonaRef
) {
    fun matches(session: ChatSession?): Boolean =
        session?.id == sessionId &&
            session.sessionIncarnationId == sessionIncarnationId &&
            session.personaBindingRevision == personaBindingRevision &&
            CharacterPersonaOwnership.refFor(session) == ref

    fun matchesExpected(
        ownerId: String?,
        personaId: String?,
        incarnationId: String?,
        bindingRevision: Long
    ): Boolean = ownerId == ref.ownerId.value &&
        personaId == ref.personaId &&
        incarnationId == sessionIncarnationId &&
        bindingRevision == personaBindingRevision

    companion object {
        fun capture(session: ChatSession): PersonaBindingSnapshot? =
            CharacterPersonaOwnership.refFor(session)?.let {
                session.sessionIncarnationId.takeIf(String::isNotBlank)?.let { incarnationId ->
                    PersonaBindingSnapshot(
                        sessionId = session.id,
                        sessionIncarnationId = incarnationId,
                        personaBindingRevision = session.personaBindingRevision,
                        ref = it
                    )
                }
            }
    }
}

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

    /** Persisted owner is authoritative; malformed identities remain unavailable. */
    fun refFor(session: ChatSession): PersonaRef? = runCatching {
        PersonaRef(
            ownerId = PluginId.of(session.personaOwnerId),
            personaId = session.characterId
        )
    }.getOrNull()

    fun resolveBoundPersona(
        session: ChatSession,
        storedCards: List<CharacterCard>
    ): BoundCharacterPersona? {
        val ref = refFor(session) ?: return null
        val card = resolveCard(ref, storedCards) ?: return null
        return BoundCharacterPersona(ref, card)
    }

    fun resolveCard(ref: PersonaRef, storedCards: List<CharacterCard>): CharacterCard? =
        when (ref.ownerId) {
            PluginIds.NATIVE -> nativeCardsById[ref.personaId]
            TavernPluginDefinition.ID -> {
                if (ref.personaId in nativeCardsById) null
                else storedCards.firstOrNull { it.id == ref.personaId }
            }
            else -> null
        }

    /** One-time migration rule for sessions written before PersonaRef ownership existed. */
    fun legacyOwnerId(characterId: String): String =
        when {
            characterId.isBlank() -> UNRESOLVED_PERSONA_OWNER_ID
            characterId in nativeCardsById -> PluginIds.NATIVE.value
            else -> TavernPluginDefinition.ID.value
        }

    fun defaultNativeCard(): CharacterCard =
        checkNotNull(nativeCardsById[DEFAULT_NATIVE_PERSONA_ID]) { "Default Loyea persona is missing" }

    private const val DEFAULT_NATIVE_PERSONA_ID = "char_loyea_default"
    const val UNRESOLVED_PERSONA_OWNER_ID = "loyea.unresolved"
}
