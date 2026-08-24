package com.loyea.ui.chat

import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PromptPatch
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AppTavernPersonaRepositoryTest {
    @Test
    fun `staged turn is isolated by persona and request then consumed once`() {
        val repository = AppTavernPersonaRepository(loadCards = { emptyList() })
        val spec = TavernTurnSpec(opaqueSnapshot = "frozen")
        val generation = generation(0)
        val stage = repository.stage("session", "card-1", "ai-1", generation, spec)

        assertNull(runSuspend { repository.prepareTurn("card-2", input("ai-1"), null, generation) })
        assertNull(runSuspend { repository.prepareTurn("card-1", input("ai-2"), null, generation) })
        assertNull(runSuspend { repository.prepareTurn("card-1", input("ai-1", "other"), null, generation) })
        assertNull(runSuspend { repository.prepareTurn("card-1", input("ai-1"), null, generation(1)) })
        assertSame(spec, runSuspend { repository.prepareTurn("card-1", input("ai-1"), null, generation) })
        assertNull(runSuspend { repository.prepareTurn("card-1", input("ai-1"), null, generation) })

        stage.close()
        assertEquals(0, repository.pendingTurnCountForTest())
    }

    @Test
    fun `stage handle discards abandoned turn and capacity is bounded`() {
        val repository = AppTavernPersonaRepository(
            loadCards = { emptyList() },
            maxPendingTurns = 1
        )
        val first = repository.stage("session", "card-1", "ai-1", generation(0), TavernTurnSpec())

        assertThrows(IllegalStateException::class.java) {
            repository.stage("session", "card-2", "ai-2", generation(0), TavernTurnSpec())
        }

        first.close()
        repository.stage("session", "card-2", "ai-2", generation(0), TavernTurnSpec()).close()
        assertEquals(0, repository.pendingTurnCountForTest())
    }

    @Test
    fun `byte budget ttl and generation discard bound abandoned stages`() {
        var now = 0L
        val repository = AppTavernPersonaRepository(
            loadCards = { emptyList() },
            maxPendingBytes = 24,
            pendingTtlNanos = 10,
            nanoTime = { now }
        )
        repository.stage(
            "session",
            "card-1",
            "ai-1",
            generation(0),
            TavernTurnSpec(prompt = PromptPatch("12345678"))
        )
        assertThrows(IllegalStateException::class.java) {
            repository.stage(
                "session",
                "card-2",
                "ai-2",
                generation(0),
                TavernTurnSpec(prompt = PromptPatch("x"))
            )
        }

        now = 10
        assertEquals(0, repository.pendingTurnCountForTest())
        repository.stage("session", "card-2", "ai-2", generation(1), TavernTurnSpec()).close()
        repository.stage("session", "card-3", "ai-3", generation(2), TavernTurnSpec())
        repository.discardGeneration(generation(2))
        assertEquals(0, repository.pendingTurnCountForTest())
    }

    @Test
    fun `concurrent consumers can claim a staged turn only once`() {
        val repository = AppTavernPersonaRepository(loadCards = { emptyList() })
        val generation = generation(0)
        val spec = TavernTurnSpec(opaqueSnapshot = "once")
        repository.stage("session", "card-1", "ai-1", generation, spec)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results = Collections.synchronizedList(mutableListOf<TavernTurnSpec?>())

        repeat(32) {
            pool.submit {
                start.await()
                results += runSuspend {
                    repository.prepareTurn("card-1", input("ai-1"), null, generation)
                }
            }
        }
        start.countDown()
        pool.shutdown()

        assertEquals(true, pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, results.count { it === spec })
        assertEquals(31, results.count { it == null })
        assertEquals(0, repository.pendingTurnCountForTest())
    }

    @Test
    fun `resolve exposes imported personas but never claims native cards`() {
        val imported = card(id = "card-imported", builtIn = true)
        val native = card(id = "char_loyea_default", builtIn = false)
        val repository = AppTavernPersonaRepository(loadCards = { listOf(native, imported) })

        val projection = runSuspend { repository.resolve(imported.id) }

        requireNotNull(projection)
        assertEquals(imported.name, projection.displayName)
        assertEquals(listOf("hello", "alternate"), projection.greetingTemplates)
        assertNull(runSuspend { repository.resolve(native.id) })
    }

    @Test
    fun `ownership uses canonical native ids instead of persisted builtIn flag`() {
        val forgedNative = card(id = "char_loyea_default", builtIn = false)
        val flaggedExternal = card(id = "external-card", builtIn = true)

        assertEquals(true, CharacterPersonaOwnership.refFor(forgedNative).isNative)
        assertEquals(TavernPluginDefinition.ID, CharacterPersonaOwnership.refFor(flaggedExternal).ownerId)
        assertEquals(
            "Loyea",
            CharacterPersonaOwnership.resolveBoundCard(forgedNative.id, listOf(forgedNative))?.name
        )
        assertNull(CharacterPersonaOwnership.resolveBoundCard("missing-plugin-card", emptyList()))
    }

    private fun input(turnId: String, sessionId: String = "session") = PluginTurnInput(
        sessionId = sessionId,
        turnId = turnId,
        turnIndex = 0,
        userName = "Eddy",
        history = emptyList()
    )

    private fun generation(revision: Long) =
        PluginRuntimeGeneration(TavernPluginDefinition.ID, revision)

    private fun card(id: String, builtIn: Boolean) = CharacterCard(
        id = id,
        name = "Lya",
        shortIntro = "Companion",
        systemPrompt = "Be kind",
        firstMessage = "hello",
        alternateGreetings = listOf("alternate", "hello", ""),
        isBuiltIn = builtIn
    )

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
