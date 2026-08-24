package com.loyea.plugins.tavern.core

import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.InsertionAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernGroupChatTest {
    private val alice = TavernGroupMember("alice", "Alice")
    private val bob = TavernGroupMember("bob", "Bob")
    private val muted = TavernGroupMember("muted", "Muted", muted = true)

    @Test
    fun naturalChatPrefersMentionsAndDoesNotSelectMutedMembers() {
        val group = group(TavernGroupReplyMode.NATURAL_CHAT)
        val mentioned = TavernGroupPlanner.plan(
            TavernGroupTurnRequest(group, turnId = "turn-1", input = "@Bob 你怎么看？")
        )

        assertEquals(listOf("bob"), mentioned.speakers.map { it.id })
        val fallback = TavernGroupPlanner.plan(
            TavernGroupTurnRequest(group, turnId = "turn-2", input = "没有点名", randomSeed = 1L)
        )
        assertTrue(fallback.speakers.single().id == "alice" || fallback.speakers.single().id == "bob")
        assertFalse(fallback.speakers.any { it.muted })
    }

    @Test
    fun allMembersAndDesignatedModesRespectExplicitSpeaker() {
        val all = TavernGroupPlanner.plan(
            TavernGroupTurnRequest(group(TavernGroupReplyMode.ALL_MEMBERS), "all")
        )
        assertEquals(listOf("alice", "bob"), all.speakers.map { it.id })

        val designatedGroup = group(
            replyMode = TavernGroupReplyMode.DESIGNATED_SPEAKER,
            designatedSpeakerId = "bob"
        )
        val designated = TavernGroupPlanner.plan(
            TavernGroupTurnRequest(designatedGroup, "designated", explicitSpeaker = "alice")
        )
        assertEquals(listOf("alice"), designated.speakers.map { it.id })
        val missing = TavernGroupPlanner.plan(
            TavernGroupTurnRequest(group(TavernGroupReplyMode.DESIGNATED_SPEAKER), "missing")
        )
        assertTrue(missing.speakers.isEmpty())

        val defaultDesignated = TavernGroupPlanner.plan(
            TavernGroupTurnRequest(
                group(TavernGroupReplyMode.DESIGNATED_SPEAKER, designatedSpeakerId = "bob"),
                "default-designated"
            )
        )
        assertEquals(listOf("bob"), defaultDesignated.speakers.map { it.id })
    }

    @Test
    fun contextualModeProducesSelectionPromptAndAcceptsOnlyKnownMembers() {
        val group = group(
            replyMode = TavernGroupReplyMode.CONTEXTUAL_SPEAKER,
            maxReplies = 2,
            contextualSpeakerPrompt = "Pick from {{group}}"
        )
        val plan = TavernGroupPlanner.plan(TavernGroupTurnRequest(group, "context"))

        assertTrue(plan.isAwaitingSpeakerSelection)
        assertEquals("Pick from Alice, Bob", plan.selectionPrompt)
        assertEquals(listOf("alice", "bob"), TavernGroupPlanner.resolveSelection(group, "Alice, Unknown, @Bob" ).map { it.id })
    }

    @Test
    fun groupCodecRoundTripsMembersModesAndMuteState() {
        val group = group(
            replyMode = TavernGroupReplyMode.ALL_MEMBERS,
            designatedSpeakerId = "alice",
            maxReplies = 2
        )
        val parsed = requireNotNull(TavernGroupCodec.parse(TavernGroupCodec.toJson(group)))

        assertEquals(group.id, parsed.id)
        assertEquals(group.replyMode, parsed.replyMode)
        assertEquals(group.designatedSpeakerId, parsed.designatedSpeakerId)
        assertEquals(group.maxReplies, parsed.maxReplies)
        assertEquals(listOf(true, true, false), parsed.members.map { !it.muted })
    }

    @Test
    fun preparedTurnFreezesGroupInsertionAndGroupMacroForRegex() {
        assertEquals(
            "Alice, Bob",
            TavernMacroEngine.expand("{{group}}", TavernMacroContext(group = "Alice, Bob"))
        )
        val prepared = TavernPreparedTurnFactory.prepare(
            TavernTurnSpec(
                groupChat = group(TavernGroupReplyMode.DESIGNATED_SPEAKER),
                macroContext = TavernMacroContext(characterName = "Alice"),
                regexScripts = listOf(
                    TavernRegexScript(
                        id = "group",
                        scriptName = "group",
                        findRegex = "(\\{\\{group}})",
                        replaceString = "[$1]",
                        placement = listOf(TavernRegexPlacement.AI_OUTPUT)
                    )
                )
            )
        )

        val insertion = prepared.plan.insertions.single()
        assertEquals(InsertionAnchor.AFTER_SYSTEM_BEFORE_SUMMARY, insertion.anchor)
        assertEquals(ChatRole.SYSTEM, insertion.role)
        assertTrue(insertion.content.contains("Group members: Alice, Bob, Muted"))
        assertTrue(insertion.content.contains("Reply-capable members: Alice, Bob"))
        assertEquals(
            "[Alice, Bob, Muted]",
            prepared.transform(com.loyea.plugin.api.TextStage.MODEL_OUTPUT, "{{group}}")
        )
    }

    private fun group(
        replyMode: TavernGroupReplyMode,
        designatedSpeakerId: String? = null,
        contextualSpeakerPrompt: String = TavernGroupChat.DEFAULT_CONTEXTUAL_SPEAKER_PROMPT,
        maxReplies: Int = 1
    ) = TavernGroupChat(
        id = "group-1",
        name = "Test Group",
        members = listOf(alice, bob, muted),
        replyMode = replyMode,
        designatedSpeakerId = designatedSpeakerId,
        contextualSpeakerPrompt = contextualSpeakerPrompt,
        maxReplies = maxReplies
    )
}
