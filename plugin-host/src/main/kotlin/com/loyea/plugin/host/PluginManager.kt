package com.loyea.plugin.host

import com.loyea.plugin.api.LOYEA_PLUGIN_API_VERSION
import com.loyea.plugin.api.LoyeaPlugin
import com.loyea.plugin.api.PluginDescriptor
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginRequestLease
import com.loyea.plugin.api.PluginRuntime
import com.loyea.plugin.api.PluginRuntimeGeneration
import java.util.concurrent.atomic.AtomicBoolean

enum class PluginStatus {
    MISSING,
    DISABLED,
    ENABLED,
    INCOMPATIBLE,
    FAILED,
    HOST_CLOSED
}

data class PluginState(
    val id: PluginId,
    val descriptor: PluginDescriptor?,
    val status: PluginStatus,
    val generation: PluginRuntimeGeneration? = null,
    val activeLeases: Int = 0,
    val failureType: String? = null
)

/**
 * Thread-safe host for bundled plugins.
 *
 * Disabling is immediate for new acquisitions. Existing leases retain one immutable runtime
 * generation and drain naturally; that runtime is closed exactly once after its last lease ends.
 */
class PluginManager(
    private val hostApiVersion: Int = LOYEA_PLUGIN_API_VERSION
) : AutoCloseable {
    private val lock = Any()
    private val slots = linkedMapOf<PluginId, Slot>()
    private var closed = false

    init {
        require(hostApiVersion > 0) { "Host plugin API version must be positive" }
    }

    fun register(plugin: LoyeaPlugin, enabled: Boolean): PluginState = synchronized(lock) {
        check(!closed) { "Plugin host is closed" }
        val id = plugin.descriptor.id
        require(id !in slots) { "Plugin $id is already registered" }

        val slot = Slot(plugin = plugin, desiredEnabled = enabled)
        slots[id] = slot
        if (enabled && plugin.descriptor.isCompatibleWith(hostApiVersion)) {
            startLocked(slot)
        }
        snapshotLocked(slot)
    }

    fun enable(id: PluginId): PluginState = synchronized(lock) {
        check(!closed) { "Plugin host is closed" }
        val slot = slots[id] ?: return@synchronized missingState(id)
        slot.desiredEnabled = true
        if (slot.plugin.descriptor.isCompatibleWith(hostApiVersion) && slot.current == null) {
            startLocked(slot)
        }
        snapshotLocked(slot)
    }

    fun disable(id: PluginId): PluginState {
        var runtimeToClose: PluginRuntime? = null
        val state = synchronized(lock) {
            val slot = slots[id] ?: return@synchronized missingState(id)
            slot.desiredEnabled = false
            slot.failureType = null
            slot.current?.let { holder ->
                slot.current = null
                holder.accepting = false
                if (holder.activeLeases == 0) {
                    runtimeToClose = holder.runtime
                } else {
                    slot.draining += holder
                }
            }
            snapshotLocked(slot)
        }
        runtimeToClose?.closeSafely()
        return state
    }

    fun acquire(id: PluginId): PluginRequestLease<PluginRuntime>? = synchronized(lock) {
        if (closed) return@synchronized null
        val slot = slots[id] ?: return@synchronized null
        if (!slot.desiredEnabled) return@synchronized null
        val holder = slot.current?.takeIf { it.accepting } ?: return@synchronized null
        holder.activeLeases += 1
        HostPluginLease(holder.runtime, holder.runtime.generation) {
            release(slot, holder)
        }
    }

    fun state(id: PluginId): PluginState = synchronized(lock) {
        if (closed) {
            val slot = slots[id]
            return@synchronized PluginState(
                id = id,
                descriptor = slot?.plugin?.descriptor,
                status = if (slot == null) PluginStatus.MISSING else PluginStatus.HOST_CLOSED,
                generation = slot?.current?.runtime?.generation,
                activeLeases = slot?.activeLeaseCount().orZero()
            )
        }
        slots[id]?.let(::snapshotLocked) ?: missingState(id)
    }

    fun states(): List<PluginState> = synchronized(lock) {
        slots.values.map { slot ->
            if (closed) {
                PluginState(
                    id = slot.plugin.descriptor.id,
                    descriptor = slot.plugin.descriptor,
                    status = PluginStatus.HOST_CLOSED,
                    generation = slot.current?.runtime?.generation,
                    activeLeases = slot.activeLeaseCount()
                )
            } else {
                snapshotLocked(slot)
            }
        }
    }

    override fun close() {
        val runtimesToClose = mutableListOf<PluginRuntime>()
        synchronized(lock) {
            if (closed) return
            closed = true
            slots.values.forEach { slot ->
                slot.desiredEnabled = false
                slot.current?.let { holder ->
                    slot.current = null
                    holder.accepting = false
                    if (holder.activeLeases == 0) {
                        runtimesToClose += holder.runtime
                    } else {
                        slot.draining += holder
                    }
                }
            }
        }
        runtimesToClose.forEach(PluginRuntime::closeSafely)
    }

    private fun startLocked(slot: Slot) {
        check(slot.current == null) { "Plugin runtime is already started" }
        check(slot.nextRevision < Long.MAX_VALUE) { "Plugin runtime revision overflow" }
        val generation = PluginRuntimeGeneration(slot.plugin.descriptor.id, slot.nextRevision)
        val runtime = try {
            slot.plugin.createRuntime(generation)
        } catch (failure: Exception) {
            slot.failureType = failure::class.java.simpleName.ifBlank { "RuntimeFailure" }
            return
        }
        try {
            check(runtime.descriptor.id == slot.plugin.descriptor.id) {
                "Plugin runtime descriptor id does not match its factory"
            }
            check(runtime.generation == generation) {
                "Plugin runtime returned a different generation"
            }
            slot.current = RuntimeHolder(runtime)
            slot.nextRevision += 1L
            slot.failureType = null
        } catch (failure: Exception) {
            runtime.closeSafely()
            slot.current = null
            slot.failureType = failure::class.java.simpleName.ifBlank { "RuntimeFailure" }
        }
    }

    private fun release(slot: Slot, holder: RuntimeHolder) {
        var runtimeToClose: PluginRuntime? = null
        synchronized(lock) {
            check(holder.activeLeases > 0) { "Plugin lease count underflow" }
            holder.activeLeases -= 1
            if (!holder.accepting && holder.activeLeases == 0) {
                slot.draining.remove(holder)
                runtimeToClose = holder.runtime
            }
        }
        runtimeToClose?.closeSafely()
    }

    private fun snapshotLocked(slot: Slot): PluginState {
        val descriptor = slot.plugin.descriptor
        val status = when {
            !descriptor.isCompatibleWith(hostApiVersion) -> PluginStatus.INCOMPATIBLE
            !slot.desiredEnabled -> PluginStatus.DISABLED
            slot.current != null -> PluginStatus.ENABLED
            slot.failureType != null -> PluginStatus.FAILED
            else -> PluginStatus.DISABLED
        }
        return PluginState(
            id = descriptor.id,
            descriptor = descriptor,
            status = status,
            generation = slot.current?.runtime?.generation,
            activeLeases = slot.activeLeaseCount(),
            failureType = slot.failureType
        )
    }

    private fun missingState(id: PluginId): PluginState = PluginState(
        id = id,
        descriptor = null,
        status = PluginStatus.MISSING
    )

    private class Slot(
        val plugin: LoyeaPlugin,
        var desiredEnabled: Boolean,
        var nextRevision: Long = 0L,
        var current: RuntimeHolder? = null,
        val draining: MutableSet<RuntimeHolder> = linkedSetOf(),
        var failureType: String? = null
    ) {
        fun activeLeaseCount(): Int =
            (current?.activeLeases ?: 0) + draining.sumOf(RuntimeHolder::activeLeases)
    }

    private class RuntimeHolder(
        val runtime: PluginRuntime,
        var accepting: Boolean = true,
        var activeLeases: Int = 0
    )

    private class HostPluginLease(
        override val runtime: PluginRuntime,
        override val generation: PluginRuntimeGeneration,
        private val release: () -> Unit
    ) : PluginRequestLease<PluginRuntime> {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0

private fun PluginRuntime.closeSafely() {
    try {
        close()
    } catch (_: Exception) {
        // Plugin teardown must not destabilize the host or block other runtime generations.
    }
}
