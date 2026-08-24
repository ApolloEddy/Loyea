package com.loyea

import com.loyea.plugin.api.PluginId
import com.loyea.plugin.host.PersonaRuntimeLease
import com.loyea.plugin.host.PluginManager
import com.loyea.plugin.host.PluginState
import com.loyea.plugin.host.acquirePersonaRuntime

internal data class PersistentPluginState(
    val desiredEnabled: Boolean,
    val effective: PluginState
)

/** Single mutation boundary keeping persisted intent and the live plugin host linearizable. */
internal class PersistentPluginController(
    private val persistence: PluginEnablementPersistence,
    private val manager: PluginManager
) : AutoCloseable {
    private val lock = Any()

    fun state(pluginId: PluginId, defaultEnabled: Boolean): PersistentPluginState = synchronized(lock) {
        PersistentPluginState(
            desiredEnabled = persistence.isEnabled(pluginId, defaultEnabled),
            effective = manager.state(pluginId)
        )
    }

    fun setEnabled(
        pluginId: PluginId,
        enabled: Boolean,
        defaultEnabled: Boolean
    ): PersistentPluginState = synchronized(lock) {
        persistence.setEnabled(pluginId, enabled)
        val effective = if (enabled) manager.enable(pluginId) else manager.disable(pluginId)
        PersistentPluginState(
            desiredEnabled = persistence.isEnabled(pluginId, defaultEnabled),
            effective = effective
        )
    }

    fun acquirePersonaRuntime(pluginId: PluginId): PersonaRuntimeLease? = synchronized(lock) {
        manager.acquirePersonaRuntime(pluginId)
    }

    override fun close() = synchronized(lock) { manager.close() }
}
