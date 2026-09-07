package com.loyea.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API 配置加密保险库：API Key 等敏感配置以 AES256 加密存入
 * EncryptedSharedPreferences（密钥托管于 Android Keystore）。
 * 首次访问时自动从 loyea_prefs 的明文 api_config_list 一次性迁移并移除明文。
 * 加密环境异常时返回 null（调用方回落默认配置，不崩溃）。
 */
object ApiConfigVault {
    private const val SECURE_PREFS = "loyea_secure"
    private const val SECURE_KEY = "api_config_list_secure"
    private var migrated = false

    fun loadJson(context: Context): String? {
        migrateIfNeeded(context)
        return securePrefs(context)?.getString(SECURE_KEY, null)
    }

    fun saveJson(context: Context, json: String) {
        migrateIfNeeded(context)
        securePrefs(context)?.edit()?.putString(SECURE_KEY, json)?.apply()
    }

    private fun migrateIfNeeded(context: Context) {
        if (migrated) return
        migrated = true
        runCatching {
            val legacy = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
                .getString("api_config_list", null)
            if (!legacy.isNullOrBlank()) {
                securePrefs(context)?.edit()?.putString(SECURE_KEY, legacy)?.apply()
                context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
                    .edit().remove("api_config_list").apply()
            }
        }
    }

    private fun securePrefs(context: Context): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()
}
