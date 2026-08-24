package com.loyea

import android.content.Context
import android.content.SharedPreferences
import com.loyea.plugin.api.PluginId

internal interface PluginEnablementPersistence {
    fun isEnabled(pluginId: PluginId, defaultEnabled: Boolean): Boolean
    fun setEnabled(pluginId: PluginId, enabled: Boolean)
}

/** Crash-consistent desired enablement for bundled plugins. */
internal class PluginEnablementStore internal constructor(
    private val preferences: SharedPreferences
) : PluginEnablementPersistence {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    override fun isEnabled(pluginId: PluginId, defaultEnabled: Boolean): Boolean =
        preferences.getBoolean(key(pluginId), defaultEnabled)

    override fun setEnabled(pluginId: PluginId, enabled: Boolean) {
        check(preferences.edit().putBoolean(key(pluginId), enabled).commit()) {
            "Could not persist enablement for plugin $pluginId"
        }
    }

    private fun key(pluginId: PluginId): String = "enabled.${pluginId.value}"

    companion object {
        private const val PREFERENCES_NAME = "loyea_plugin_state"
    }
}
