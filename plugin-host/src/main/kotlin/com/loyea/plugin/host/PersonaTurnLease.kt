package com.loyea.plugin.host

import com.loyea.plugin.api.PersonaPluginRuntime
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginRequestLease
import com.loyea.plugin.api.PluginRuntime
import com.loyea.plugin.api.PluginRuntimeGeneration
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PreparedPersonaTurn
import java.util.concurrent.atomic.AtomicBoolean

class PluginCapabilityMismatchException(
    val pluginId: PluginId,
    expectedCapability: String
) : IllegalStateException("Plugin $pluginId does not provide $expectedCapability")

/** Typed request lease acquired before any provider-specific request preparation side effects. */
class PersonaRuntimeLease internal constructor(
    private val runtime: PersonaPluginRuntime,
    private val rawLease: PluginRequestLease<PluginRuntime>
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val prepared = AtomicBoolean(false)

    val generation: PluginRuntimeGeneration
        get() = rawLease.generation

    suspend fun preparePersonaTurn(
        ref: PersonaRef,
        input: PluginTurnInput,
        restoredSnapshot: String? = null
    ): PreparedPersonaTurn {
        check(!closed.get()) { "Persona runtime lease is closed" }
        check(prepared.compareAndSet(false, true)) { "Persona runtime lease already prepared a turn" }
        return try {
            runtime.prepareTurn(ref, input, restoredSnapshot)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) rawLease.close()
    }
}

/** Owns a typed runtime lease for exactly as long as its frozen persona turn is in use. */
class LeasedPersonaTurn internal constructor(
    val preparedTurn: PreparedPersonaTurn,
    private val lease: PersonaRuntimeLease
) : AutoCloseable {
    val generation: PluginRuntimeGeneration
        get() = lease.generation

    override fun close() = lease.close()
}

fun PluginManager.acquirePersonaRuntime(pluginId: PluginId): PersonaRuntimeLease? {
    val rawLease = acquire(pluginId) ?: return null
    return try {
        val runtime = rawLease.runtime as? PersonaPluginRuntime
            ?: throw PluginCapabilityMismatchException(pluginId, "the persona runtime capability")
        if (runtime.providerId != pluginId) {
            throw PluginCapabilityMismatchException(pluginId, "a matching persona provider")
        }
        PersonaRuntimeLease(runtime, rawLease)
    } catch (failure: Throwable) {
        rawLease.close()
        throw failure
    }
}

/**
 * Acquires and prepares a persona turn as one failure-safe operation. A disabled or missing
 * plugin returns null; capability and preparation failures are surfaced after releasing the lease.
 */
suspend fun PluginManager.preparePersonaTurn(
    ref: PersonaRef,
    input: PluginTurnInput,
    restoredSnapshot: String? = null
): LeasedPersonaTurn? {
    val lease = acquirePersonaRuntime(ref.ownerId) ?: return null
    return try {
        LeasedPersonaTurn(
            preparedTurn = lease.preparePersonaTurn(ref, input, restoredSnapshot),
            lease = lease
        )
    } catch (failure: Throwable) {
        lease.close()
        throw failure
    }
}
