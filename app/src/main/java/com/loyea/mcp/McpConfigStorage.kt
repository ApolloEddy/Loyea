package com.loyea.mcp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class McpConfigStorage(context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
    private val key = "mcp_server_configs"

    fun loadConfigs(): List<McpServerConfig> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<McpServerConfig>>() {}.type
            gson.fromJson<List<McpServerConfig>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            // 反序列化损坏自愈：清除损坏数据，防止启动崩溃
            try {
                prefs.edit().remove(key).apply()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            emptyList()
        }
    }

    fun saveConfigs(configs: List<McpServerConfig>) {
        try {
            val json = gson.toJson(configs)
            prefs.edit().putString(key, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
