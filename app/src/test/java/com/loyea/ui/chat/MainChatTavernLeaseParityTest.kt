package com.loyea.ui.chat

import com.loyea.context.core.*
import com.loyea.plugins.tavern.core.*

import com.google.gson.JsonObject
import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationText
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.api.TextStage
import com.loyea.plugin.host.PluginManager
import com.loyea.plugin.host.preparePersonaTurn
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainChatTavernLeaseParityTest {
    @Test
    fun `leased Tavern turn preserves legacy prompt conversation generation and output bytes`() {
        val card = CharacterCard(
            id = "card-1",
            name = "Lya",
            shortIntro = "Companion",
            systemPrompt = "stable {{user}}",
            personality = "kind",
            postHistoryInstructions = "card post"
        )
        val preset = TavernPromptPreset(
            name = "Preset",
            temperature = 0.3,
            maxTokens = 321,
            prompts = listOf(
                TavernPresetPrompt("Aux", "aux", "preset bytes", role = "assistant")
            )
        )
        val worldInfoRender = WorldInfoMatcher.WorldInfoRenderResult(
            all = "world bytes",
            atDepthBlocks = mapOf(
                1 to listOf(WorldInfoMatcher.WorldInfoInjectionBlock("world bytes", "user"))
            )
        )
        val promptParts = PromptAssembler.assemblePromptParts(
            card = card,
            userName = "Eddy",
            useSystemTime = false,
            includeSystemTimeInSnapshot = false,
            worldInfo = worldInfoRender.all,
            worldInfoRender = worldInfoRender,
            preset = preset,
            trustedCard = false,
            snapshotTimeMillis = 1_700_000_000_000L
        )
        val spec = LegacyTavernTurnAdapter.spec(
            card = card,
            userName = "Eddy",
            regexScripts = listOf(
                TavernRegexScript(
                    id = "replace",
                    scriptName = "Replace",
                    findRegex = "/foo/g",
                    replaceString = "bar",
                    placement = listOf(
                        TavernRegexPlacement.USER_INPUT,
                        TavernRegexPlacement.AI_OUTPUT
                    )
                )
            ),
            presetMessages = promptParts.presetMessages,
            worldInfoAtDepth = promptParts.worldInfoAtDepth,
            generation = preset.generationOverrides(),
            prompt = PromptPatch(
                promptParts.stableSystemPrompt,
                promptParts.turnContextSnapshot,
                promptParts.postHistoryInstructions
            )
        )
        val direct = TavernPreparedTurnFactory.prepare(spec)
        val repository = AppTavernPersonaRepository(loadCards = { emptyList() })
        val manager = PluginManager().apply {
            register(TavernPlugin(repository), enabled = true)
        }
        repository.stage(
            "session-1",
            card.id,
            "ai-1",
            PluginRuntimeGeneration(TavernPluginDefinition.ID, 0),
            spec
        )
        val leased = requireNotNull(
            runSuspend {
                manager.preparePersonaTurn(
                    ref = PersonaRef.plugin(TavernPluginDefinition.ID, card.id),
                    input = PluginTurnInput(
                        sessionId = "session-1",
                        turnId = "ai-1",
                        turnIndex = 1,
                        userName = "Eddy",
                        history = listOf(ConversationText("u1", ChatRole.USER, "foo"))
                    ),
                    restoredSnapshot = null
                )
            }
        )

        manager.disable(TavernPluginDefinition.ID)
        assertNull(manager.acquire(TavernPluginDefinition.ID))

        assertEquals(direct.plan.prompt, leased.preparedTurn.plan.prompt)
        assertEquals(promptParts.stableSystemPrompt, leased.preparedTurn.plan.prompt.stablePersonaText)
        assertEquals(promptParts.turnContextSnapshot, leased.preparedTurn.plan.prompt.turnContextText)
        assertEquals(promptParts.postHistoryInstructions, leased.preparedTurn.plan.prompt.postHistoryText)
        assertEquals(direct.plan.insertions, leased.preparedTurn.plan.insertions)
        assertEquals(321, leased.preparedTurn.plan.generation.maxOutputTokens)
        assertEquals(0.3, leased.preparedTurn.plan.generation.temperature!!, 0.0)

        val history = listOf(
            Message("u1", "foo", Sender.USER, llmContextSnapshot = promptParts.turnContextSnapshot),
            Message("a1", "answer", Sender.AI)
        )
        fun build(prepared: com.loyea.plugin.api.PreparedPersonaTurn) = LlmConversationBuilder.build(
            systemPrompt = prepared.plan.prompt.stablePersonaText,
            history = history,
            postHistoryInstructions = prepared.plan.prompt.postHistoryText,
            preparedTurn = prepared
        )
        assertEquals(build(direct), build(leased.preparedTurn))
        assertEquals(
            direct.transform(TextStage.MODEL_OUTPUT, "foo", isMarkdown = true),
            leased.preparedTurn.transform(TextStage.MODEL_OUTPUT, "foo", isMarkdown = true)
        )
        val directJson = JsonObject()
        val leasedJson = JsonObject()
        GenerationRequestMapper.apply(directJson, "DeepSeek", direct.plan.generation)
        GenerationRequestMapper.apply(leasedJson, "DeepSeek", leased.preparedTurn.plan.generation)
        assertEquals(directJson, leasedJson)

        leased.close()
        assertEquals(0, manager.state(TavernPluginDefinition.ID).activeLeases)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        })
        return requireNotNull(outcome) { "Test coroutine suspended unexpectedly" }.getOrThrow()
    }
}
