package com.loyea.ui.chat

import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PluginIds
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.host.PluginManager
import com.loyea.plugin.host.acquirePersonaRuntime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            CharacterPersonaOwnership.resolveCard(
                PersonaRef.native(forgedNative.id),
                listOf(forgedNative)
            )?.name
        )
        assertNull(
            CharacterPersonaOwnership.resolveCard(
                PersonaRef.plugin(TavernPluginDefinition.ID, forgedNative.id),
                listOf(forgedNative)
            )
        )
        assertNull(
            CharacterPersonaOwnership.resolveCard(
                PersonaRef.plugin(TavernPluginDefinition.ID, "missing-plugin-card"),
                emptyList()
            )
        )
    }

    @Test
    fun `persisted session owner is authoritative when resolving a persona`() {
        val imported = card(id = "external-card", builtIn = true)
        val externalSession = ChatSession(
            id = "session-external",
            title = "External",
            characterId = imported.id,
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        val forgedNativeSession = externalSession.copy(
            id = "session-forged-native",
            personaOwnerId = PluginIds.NATIVE.value
        )
        val invalidOwnerSession = externalSession.copy(
            id = "session-invalid",
            personaOwnerId = "not valid"
        )

        val resolved = CharacterPersonaOwnership.resolveBoundPersona(externalSession, listOf(imported))

        requireNotNull(resolved)
        assertEquals(TavernPluginDefinition.ID, resolved.ref.ownerId)
        assertSame(imported, resolved.card)
        assertNull(CharacterPersonaOwnership.resolveBoundPersona(forgedNativeSession, listOf(imported)))
        assertNull(CharacterPersonaOwnership.resolveBoundPersona(invalidOwnerSession, listOf(imported)))
    }

    @Test
    fun `persona binding snapshot rejects changed or malformed worker targets`() {
        val session = ChatSession(
            id = "session",
            title = "External",
            characterId = "external-card",
            personaOwnerId = TavernPluginDefinition.ID.value
        )
        val snapshot = requireNotNull(PersonaBindingSnapshot.capture(session))

        assertTrue(snapshot.matches(session.copy(title = "Renamed")))
        assertFalse(snapshot.matches(session.copy(characterId = "another-card")))
        assertFalse(snapshot.matches(session.copy(personaOwnerId = PluginIds.NATIVE.value)))
        assertFalse(snapshot.matches(session.copy(personaBindingRevision = session.personaBindingRevision + 1L)))
        assertFalse(snapshot.matches(session.copy(sessionIncarnationId = "another-incarnation")))
        assertFalse(snapshot.matches(null))
        assertTrue(snapshot.matchesExpected(
            TavernPluginDefinition.ID.value,
            "external-card",
            session.sessionIncarnationId,
            session.personaBindingRevision
        ))
        assertFalse(snapshot.matchesExpected(
            PluginIds.NATIVE.value,
            "external-card",
            session.sessionIncarnationId,
            session.personaBindingRevision
        ))
        assertFalse(snapshot.matchesExpected(
            TavernPluginDefinition.ID.value,
            null,
            session.sessionIncarnationId,
            session.personaBindingRevision
        ))
        assertNull(PersonaBindingSnapshot.capture(session.copy(personaOwnerId = "invalid owner")))
    }

    @Test
    fun `staged prepare helper consumes success and discards failure`() {
        val repository = AppTavernPersonaRepository(loadCards = { emptyList() })
        val manager = PluginManager().apply {
            register(TavernPlugin(repository), enabled = true)
        }
        val ref = PersonaRef.plugin(TavernPluginDefinition.ID, "external-card")
        val successLease = requireNotNull(manager.acquirePersonaRuntime(TavernPluginDefinition.ID))
        val spec = TavernTurnSpec(prompt = PromptPatch("stable"))

        val prepared = runSuspend {
            repository.prepareStagedTurn(successLease, ref, input("turn-1"), spec)
        }

        assertEquals("stable", prepared.plan.prompt.stablePersonaText)
        assertEquals(0, repository.pendingTurnCountForTest())
        successLease.close()

        val failureLease = requireNotNull(manager.acquirePersonaRuntime(TavernPluginDefinition.ID))
        val wrongOwner = PersonaRef.plugin(PluginId.of("com.example.other"), "external-card")
        assertThrows(TavernPersonaUnavailableException::class.java) {
            runSuspend {
                repository.prepareStagedTurn(failureLease, wrongOwner, input("turn-2"), spec)
            }
        }
        assertEquals(0, repository.pendingTurnCountForTest())
        assertEquals(0, manager.state(TavernPluginDefinition.ID).activeLeases)
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
