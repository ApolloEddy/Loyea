package com.loyea.ui.chat

import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationText
import com.loyea.plugin.api.GenerationPatch
import com.loyea.plugin.api.PersonaPluginRuntime
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.api.TextStage
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TavernPluginRuntimeTest {
    @Test
    fun resolvesAndPreparesFrozenPersonaTurnThroughPluginContract() {
        val mutablePlacements = mutableListOf(TavernRegexPlacement.AI_OUTPUT)
        val repository = FakeRepository(
            persona = TavernPersonaRecord(
                personaId = "card-1",
                displayName = "Lya",
                avatarUri = "content://avatar",
                summary = "A companion",
                greetingTemplates = listOf("Hello")
            ),
            turn = TavernTurnSpec(
                prompt = PromptPatch("stable", "turn", "post"),
                presetMessages = listOf(
                    TavernPresetPrompt("Aux", "aux", "aux text", role = "user")
                ),
                worldInfoAtDepth = mapOf(
                    2 to listOf(WorldInfoMatcher.WorldInfoInjectionBlock("deep lore", "system"))
                ),
                generation = GenerationPatch(temperature = 0.4, maxOutputTokens = 512),
                regexScripts = listOf(
                    TavernRegexScript(
                        id = "replace",
                        scriptName = "Replace",
                        findRegex = "/foo/g",
                        replaceString = "{{char}}/{{user}}",
                        placement = mutablePlacements
                    )
                ),
                macroContext = TavernMacroContext("Lya", "A companion", "Eddy"),
                opaqueSnapshot = "snapshot-v1"
            )
        )
        val plugin = TavernPlugin(repository)
        val runtime: PersonaPluginRuntime =
            plugin.createRuntime(PluginRuntimeGeneration(TavernPluginDefinition.ID, 3))
        val ref = PersonaRef.plugin(TavernPluginDefinition.ID, "card-1")

        val projection = runSuspend { runtime.resolve(ref) }
        requireNotNull(projection)
        assertEquals("Lya", projection.displayName)
        assertEquals(listOf("Hello"), projection.greetingTemplates)

        val prepared = runSuspend {
            runtime.prepareTurn(
                ref,
                PluginTurnInput(
                    sessionId = "session-1",
                    turnId = "turn-1",
                    turnIndex = 1,
                    userName = "Eddy",
                    history = listOf(ConversationText("m1", ChatRole.USER, "foo"))
                )
            )
        }
        mutablePlacements.clear()

        assertEquals("stable", prepared.plan.prompt.stablePersonaText)
        assertEquals("snapshot-v1", prepared.plan.opaqueSnapshot)
        assertEquals(2, prepared.plan.insertions.size)
        assertEquals(ChatRole.USER, prepared.plan.insertions[0].role)
        assertEquals("[PRESET SLOT / aux]\naux text", prepared.plan.insertions[0].content)
        assertEquals(ChatRole.SYSTEM, prepared.plan.insertions[1].role)
        assertEquals("[WORLD INFO @ DEPTH / 深度世界书]\ndeep lore", prepared.plan.insertions[1].content)
        assertEquals(512, prepared.plan.generation.maxOutputTokens)
        assertEquals(
            "Lya/Eddy",
            prepared.transform(TextStage.MODEL_OUTPUT, "foo", isMarkdown = true)
        )
    }

    @Test
    fun rejectsWrongOwnerAndClosedRuntime() {
        val repository = FakeRepository(
            persona = TavernPersonaRecord("card-1", "Lya", null, "", emptyList()),
            turn = TavernTurnSpec(macroContext = TavernMacroContext("Lya"))
        )
        val runtime: PersonaPluginRuntime = TavernPlugin(repository)
            .createRuntime(PluginRuntimeGeneration(TavernPluginDefinition.ID, 0))
        val nativeRef = PersonaRef.native("card-1")

        assertNull(runSuspend { runtime.resolve(nativeRef) })
        assertThrows(TavernPersonaUnavailableException::class.java) {
            runSuspend {
                runtime.prepareTurn(
                    nativeRef,
                    PluginTurnInput("session", "turn", 0, "Eddy", emptyList())
                )
            }
        }

        runtime.close()
        assertEquals(listOf(PluginRuntimeGeneration(TavernPluginDefinition.ID, 0)), repository.discarded)
        assertThrows(IllegalStateException::class.java) {
            runSuspend { runtime.resolve(PersonaRef.plugin(TavernPluginDefinition.ID, "card-1")) }
        }
    }

    private class FakeRepository(
        private val persona: TavernPersonaRecord?,
        private val turn: TavernTurnSpec?
    ) : TavernPersonaRepository {
        val discarded = mutableListOf<PluginRuntimeGeneration>()

        override suspend fun resolve(personaId: String): TavernPersonaRecord? =
            persona?.takeIf { it.personaId == personaId }

        override suspend fun prepareTurn(
            personaId: String,
            input: PluginTurnInput,
            restoredSnapshot: String?,
            generation: PluginRuntimeGeneration
        ): TavernTurnSpec? = turn?.takeIf { persona?.personaId == personaId }

        override fun discardGeneration(generation: PluginRuntimeGeneration) {
            discarded += generation
        }
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
