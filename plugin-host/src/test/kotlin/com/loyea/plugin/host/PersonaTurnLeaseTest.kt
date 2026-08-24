package com.loyea.plugin.host

import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationText
import com.loyea.plugin.api.LoyeaPlugin
import com.loyea.plugin.api.PersonaPluginRuntime
import com.loyea.plugin.api.PersonaProjection
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginCapability
import com.loyea.plugin.api.PluginDescriptor
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginRuntime
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PluginTurnPlan
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.api.TextStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonaTurnLeaseTest {
    private val pluginId = PluginId.of("com.loyea.persona-test")
    private val ref = PersonaRef.plugin(pluginId, "persona-1")
    private val input = PluginTurnInput(
        sessionId = "session-1",
        turnId = "turn-1",
        turnIndex = 1,
        userName = "Eddy",
        history = listOf(ConversationText("m1", ChatRole.USER, "hello"))
    )

    @Test
    fun `lease survives disable and closes after prepared turn finishes`() {
        val runtime = FakePersonaRuntime()
        val manager = PluginManager().apply { register(plugin(runtime), enabled = true) }

        val leased = requireNotNull(runSuspend { manager.preparePersonaTurn(ref, input) })
        assertEquals(1, manager.state(pluginId).activeLeases)

        manager.disable(pluginId)
        assertNull(runSuspend { manager.preparePersonaTurn(ref, input) })
        assertEquals("frozen:reply", leased.preparedTurn.transform(TextStage.MODEL_OUTPUT, "reply"))
        assertEquals(0, runtime.closeCount.get())

        leased.close()
        leased.close()

        assertEquals(0, manager.state(pluginId).activeLeases)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun `capability mismatch closes raw lease`() {
        val manager = PluginManager().apply {
            register(
                object : LoyeaPlugin {
                    override val descriptor = descriptor()
                    override fun createRuntime(generation: PluginRuntimeGeneration): PluginRuntime =
                        PlainRuntime(descriptor, generation)
                },
                enabled = true
            )
        }

        assertThrows(PluginCapabilityMismatchException::class.java) {
            runSuspend { manager.preparePersonaTurn(ref, input) }
        }
        assertEquals(0, manager.state(pluginId).activeLeases)
    }

    @Test
    fun `prepare failure closes raw lease`() {
        val runtime = FakePersonaRuntime(prepareFailure = IllegalStateException("expected"))
        val manager = PluginManager().apply { register(plugin(runtime), enabled = true) }

        assertThrows(IllegalStateException::class.java) {
            runSuspend { manager.preparePersonaTurn(ref, input) }
        }

        assertEquals(0, manager.state(pluginId).activeLeases)
        manager.disable(pluginId)
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun `typed lease can prepare after disable and cancellation releases it`() {
        val runtime = FakePersonaRuntime()
        val manager = PluginManager().apply { register(plugin(runtime), enabled = true) }
        val earlyLease = requireNotNull(manager.acquirePersonaRuntime(pluginId))

        manager.disable(pluginId)
        val prepared = runSuspend { earlyLease.preparePersonaTurn(ref, input) }
        assertEquals("frozen:reply", prepared.transform(TextStage.MODEL_OUTPUT, "reply"))
        earlyLease.close()
        assertEquals(1, runtime.closeCount.get())

        val cancellingRuntime = FakePersonaRuntime(prepareFailure = CancellationException("cancel"))
        val cancellingManager = PluginManager().apply { register(plugin(cancellingRuntime), enabled = true) }
        assertThrows(CancellationException::class.java) {
            runSuspend { cancellingManager.preparePersonaTurn(ref, input) }
        }
        assertEquals(0, cancellingManager.state(pluginId).activeLeases)
        cancellingManager.disable(pluginId)
        assertEquals(1, cancellingRuntime.closeCount.get())
    }

    private fun plugin(runtime: FakePersonaRuntime) = object : LoyeaPlugin {
        override val descriptor = descriptor()
        override fun createRuntime(generation: PluginRuntimeGeneration): PluginRuntime =
            runtime.apply { this.generationValue = generation }
    }

    private fun descriptor() = PluginDescriptor(
        id = pluginId,
        displayName = "Persona test",
        apiVersion = 1,
        capabilities = setOf(PluginCapability.PERSONAS)
    )

    private inner class FakePersonaRuntime(
        private val prepareFailure: RuntimeException? = null
    ) : PersonaPluginRuntime {
        var generationValue = PluginRuntimeGeneration(pluginId, 0)
        val closeCount = AtomicInteger(0)

        override val descriptor = descriptor()
        override val generation: PluginRuntimeGeneration get() = generationValue
        override val providerId: PluginId = pluginId

        override suspend fun resolve(ref: PersonaRef): PersonaProjection? = null

        override suspend fun prepareTurn(
            ref: PersonaRef,
            input: PluginTurnInput,
            restoredSnapshot: String?
        ): PreparedPersonaTurn {
            prepareFailure?.let { throw it }
            return object : PreparedPersonaTurn {
                override val plan = PluginTurnPlan(PromptPatch("stable"))
                override fun transform(
                    stage: TextStage,
                    text: String,
                    depth: Int?,
                    isMarkdown: Boolean
                ): String = "frozen:$text"
            }
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class PlainRuntime(
        override val descriptor: PluginDescriptor,
        override val generation: PluginRuntimeGeneration
    ) : PluginRuntime {
        override fun close() = Unit
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
