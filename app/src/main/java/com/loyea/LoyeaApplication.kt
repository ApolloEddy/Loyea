package com.loyea

import android.app.Application
import com.loyea.plugin.api.PersonaRef
import com.loyea.plugin.api.PluginId
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.host.PersonaRuntimeLease
import com.loyea.plugin.host.PluginManager
import com.loyea.ui.chat.AppTavernPersonaRepository
import com.loyea.plugins.tavern.core.TavernPlugin
import com.loyea.plugins.tavern.core.TavernPluginDefinition
import com.loyea.plugins.tavern.core.TavernTurnSpec

/** Application-wide plugin composition root shared by UI and background workers. */
class LoyeaApplication : Application() {
    private val pluginEnablementStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginEnablementStore(this)
    }

    private val tavernPersonaRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppTavernPersonaRepository(this)
    }

    private val tavernPersonaRepository: AppTavernPersonaRepository by tavernPersonaRepositoryDelegate

    private val pluginControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val manager = PluginManager().apply {
            register(
                TavernPlugin(tavernPersonaRepository),
                enabled = pluginEnablementStore.isEnabled(
                    TavernPluginDefinition.ID,
                    defaultEnabled = true
                )
            )
        }
        PersistentPluginController(pluginEnablementStore, manager)
    }

    private val pluginController: PersistentPluginController by pluginControllerDelegate

    internal fun acquirePersonaRuntime(pluginId: PluginId): PersonaRuntimeLease? =
        pluginController.acquirePersonaRuntime(pluginId)

    internal suspend fun prepareTavernPersonaTurn(
        lease: PersonaRuntimeLease,
        ref: PersonaRef,
        input: PluginTurnInput,
        spec: TavernTurnSpec
    ): PreparedPersonaTurn = tavernPersonaRepository.prepareStagedTurn(lease, ref, input, spec)

    internal fun pluginState(pluginId: PluginId): PersistentPluginState =
        pluginController.state(pluginId, defaultEnabled = pluginId == TavernPluginDefinition.ID)

    /** Persists desired state before mutating the live host, so process restart cannot re-enable it. */
    internal fun setPluginEnabled(pluginId: PluginId, enabled: Boolean): PersistentPluginState =
        pluginController.setEnabled(
            pluginId = pluginId,
            enabled = enabled,
            defaultEnabled = pluginId == TavernPluginDefinition.ID
        )

    override fun onTerminate() {
        if (pluginControllerDelegate.isInitialized()) {
            pluginController.close()
        }
        if (tavernPersonaRepositoryDelegate.isInitialized()) {
            tavernPersonaRepository.clearPendingTurns()
        }
        super.onTerminate()
    }
}
