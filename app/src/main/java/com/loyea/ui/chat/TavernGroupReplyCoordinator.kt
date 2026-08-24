package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.TavernGroupChat
import com.loyea.plugins.tavern.core.TavernGroupMember
import com.loyea.plugins.tavern.core.TavernGroupPlanner
import com.loyea.plugins.tavern.core.TavernGroupReplyMode
import com.loyea.plugins.tavern.core.TavernGroupTurnPlan
import com.loyea.plugins.tavern.core.TavernGroupTurnRequest

/** A resolved group speaker together with the local card that can generate its reply. */
internal data class TavernGroupSpeakerTarget(
    val member: TavernGroupMember,
    val card: CharacterCard
)

internal data class TavernGroupSpeakerResolution(
    val targets: List<TavernGroupSpeakerTarget>,
    val missingMemberNames: List<String>
)

/**
 * Android-host adapter for the pure Tavern group planner.
 *
 * The planner decides who should speak. This adapter only resolves those stable member IDs or
 * names against locally installed cards and preserves the planner's order for sequential output.
 */
internal object TavernGroupReplyCoordinator {
    fun plan(
        group: TavernGroupChat,
        turnId: String,
        input: String
    ): TavernGroupTurnPlan = TavernGroupPlanner.plan(
        TavernGroupTurnRequest(group = group, turnId = turnId, input = input)
    )

    fun naturalFallback(
        group: TavernGroupChat,
        turnId: String,
        input: String
    ): TavernGroupTurnPlan = plan(group.copy(replyMode = TavernGroupReplyMode.NATURAL_CHAT), turnId, input)

    fun resolve(
        speakers: List<TavernGroupMember>,
        cards: List<CharacterCard>
    ): TavernGroupSpeakerResolution {
        val targets = mutableListOf<TavernGroupSpeakerTarget>()
        val missing = mutableListOf<String>()
        speakers.forEach { member ->
            val card = cards.firstOrNull { candidate -> candidateMatches(member, candidate) }
            if (card == null) {
                missing += member.name
            } else {
                targets += TavernGroupSpeakerTarget(member = member, card = card)
            }
        }
        return TavernGroupSpeakerResolution(targets = targets, missingMemberNames = missing)
    }

    private fun candidateMatches(member: TavernGroupMember, card: CharacterCard): Boolean {
        val memberId = member.id.trim()
        val personaId = member.personaId?.trim().orEmpty()
        val cardId = card.id.trim()
        if (memberId.isNotBlank() && memberId.equals(cardId, ignoreCase = true)) return true
        if (personaId.isNotBlank() && personaId.equals(cardId, ignoreCase = true)) return true
        if (member.name.trim().equals(card.name.trim(), ignoreCase = true)) return true
        return card.nickname?.trim()?.takeIf(String::isNotBlank)
            ?.equals(member.name.trim(), ignoreCase = true) == true
    }
}
