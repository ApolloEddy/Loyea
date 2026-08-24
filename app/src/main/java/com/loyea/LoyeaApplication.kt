package com.loyea

import android.app.Application
import com.loyea.plugin.host.PluginManager
import com.loyea.ui.chat.AppTavernPersonaRepository
import com.loyea.ui.chat.TavernPlugin

/** Application-wide plugin composition root shared by UI and background workers. */
class LoyeaApplication : Application() {
    private val tavernPersonaRepositoryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppTavernPersonaRepository(this)
    }

    val tavernPersonaRepository: AppTavernPersonaRepository by tavernPersonaRepositoryDelegate

    private val pluginManagerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginManager().apply {
            register(TavernPlugin(tavernPersonaRepository), enabled = true)
        }
    }

    val pluginManager: PluginManager by pluginManagerDelegate

    override fun onTerminate() {
        if (pluginManagerDelegate.isInitialized()) {
            pluginManager.close()
        }
        if (tavernPersonaRepositoryDelegate.isInitialized()) {
            tavernPersonaRepository.clearPendingTurns()
        }
        super.onTerminate()
    }
}
