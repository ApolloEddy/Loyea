package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.TavernGroupChat
import com.loyea.plugins.tavern.core.TavernGroupMember
import com.loyea.plugins.tavern.core.TavernGroupReplyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernGroupReplyCoordinatorTest {
    private val alice = card(id = "alice", name = "Alice")
    private val bob = card(id = "card-bob", name = "Bob", nickname = "B")

    @Test
    fun resolvesStableIdsBeforeDisplayNamesAndKeepsPlannerOrder() {
        val group = TavernGroupChat(
            id = "group-1",
            name = "Test",
            members = listOf(
                TavernGroupMember("bob-member", "B", personaId = "card-bob"),
                TavernGroupMember("alice", "Different label")
            ),
            replyMode = TavernGroupReplyMode.ALL_MEMBERS
        )

        val plan = TavernGroupReplyCoordinator.plan(group, "turn-1", "hello")
        val resolution = TavernGroupReplyCoordinator.resolve(plan.speakers, listOf(alice, bob))

        assertEquals(listOf("card-bob", "alice"), resolution.targets.map { it.card.id })
        assertTrue(resolution.missingMemberNames.isEmpty())
    }

    @Test
    fun reportsMembersWhoseCardsAreNotInstalledWithoutDroppingOtherTargets() {
        val speakers = listOf(
            TavernGroupMember("alice", "Alice"),
            TavernGroupMember("missing", "Missing")
        )

        val resolution = TavernGroupReplyCoordinator.resolve(speakers, listOf(alice))

        assertEquals(listOf("alice"), resolution.targets.map { it.member.id })
        assertEquals(listOf("Missing"), resolution.missingMemberNames)
    }

    @Test
    fun contextualFallbackUsesNaturalPlannerWhenSelectionCannotResolve() {
        val group = TavernGroupChat(
            id = "group-1",
            name = "Test",
            members = listOf(
                TavernGroupMember("alice", "Alice"),
                TavernGroupMember("bob", "Bob")
            ),
            replyMode = TavernGroupReplyMode.CONTEXTUAL_SPEAKER
        )

        val fallback = TavernGroupReplyCoordinator.naturalFallback(group, "turn-1", "@Bob hi")

        assertEquals(listOf("bob"), fallback.speakers.map { it.id })
    }

    private fun card(id: String, name: String, nickname: String? = null) = CharacterCard(
        id = id,
        name = name,
        shortIntro = name,
        systemPrompt = "You are $name.",
        nickname = nickname
    )
}
