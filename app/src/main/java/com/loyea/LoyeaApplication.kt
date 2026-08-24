package com.loyea

import android.app.Application
import com.loyea.plugin.host.PluginManager

/** Application-wide plugin composition root shared by UI and background workers. */
class LoyeaApplication : Application() {
    private val pluginManagerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginManager()
    }

    val pluginManager: PluginManager by pluginManagerDelegate

    override fun onTerminate() {
        if (pluginManagerDelegate.isInitialized()) {
            pluginManager.close()
        }
        super.onTerminate()
    }
}
