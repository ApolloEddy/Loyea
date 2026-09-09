package com.loyea.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Vault 操作结果：失败必须可见，调用方不得在 Failure 时假装持久化成功。
 */
sealed class VaultResult<out T> {
    data class Success<T>(val value: T) : VaultResult<T>()
    data class Failure(val cause: Throwable? = null) : VaultResult<Nothing>()
}

/**
 * API 配置加密保险库：API Key 等敏感配置以 AES256 加密存入
 * EncryptedSharedPreferences（密钥托管于 Android Keystore）。
 *
 * 迁移纪律（Spec §13，2026-09-10 原子化重写）：
 * - secure 存储初始化失败 → 保留 legacy、不标记完成、本次操作报 Failure；
 * - legacy 迁移必须「commit 写入 → read-back 校验 → 确认删除」全部成功后才算完成；
 * - 任何一步失败都保留 legacy 明文（宁可多留一份明文，绝不丢配置）；
 * - 迁移未完成时 saveJson 拒绝写入，防止默认配置覆盖尚未迁移的用户数据；
 * - saveJson 写入后 read-back 校验，commit 失败一律报 Failure。
 */
object ApiConfigVault {
    private const val SECURE_PREFS = "loyea_secure"
    private const val SECURE_KEY = "api_config_list_secure"
    private const val LEGACY_PREFS = "loyea_prefs"
    private const val LEGACY_KEY = "api_config_list"

    /** selectActiveConfig 历史散装死键（全仓无读方）：随迁移无条件清理，不存在数据丢失面 */
    internal val legacyDeadKeys = listOf("api_key", "api_provider", "api_url", "api_model")

    @Volatile
    private var migrationComplete = false

    // ---------- 公开 API ----------

    fun loadJson(context: Context): VaultResult<String?> {
        val migration = migrateIfNeeded(context)
        if (migration is MigrationOutcome.Failed) return VaultResult.Failure(migration.cause)
        val secure = securePrefs(context)
            ?: return VaultResult.Failure(IllegalStateException("secure prefs unavailable"))
        return VaultResult.Success(secure.getString(SECURE_KEY))
    }

    fun saveJson(context: Context, json: String): VaultResult<Unit> {
        val migration = migrateIfNeeded(context)
        if (migration is MigrationOutcome.Failed) {
            // legacy 尚未安全落库：此时覆盖写入可能用默认配置顶掉用户数据，拒绝
            return VaultResult.Failure(migration.cause ?: IllegalStateException("migration incomplete"))
        }
        val secure = securePrefs(context)
            ?: return VaultResult.Failure(IllegalStateException("secure prefs unavailable"))
        if (!secure.putStringCommit(SECURE_KEY, json)) {
            return VaultResult.Failure(IllegalStateException("secure commit failed"))
        }
        if (secure.getString(SECURE_KEY) != json) {
            return VaultResult.Failure(IllegalStateException("secure read-back mismatch"))
        }
        return VaultResult.Success(Unit)
    }

    // ---------- 迁移 ----------

    private fun migrateIfNeeded(context: Context): MigrationOutcome {
        if (migrationComplete) return MigrationOutcome.AlreadyComplete
        val outcome = runMigration(
            legacy = legacyPrefs(context),
            secure = securePrefs(context)
        )
        if (outcome is MigrationOutcome.Completed || outcome is MigrationOutcome.NoLegacy) {
            migrationComplete = true
        }
        return outcome
    }

    /** Prefs 最小接口：仅暴露迁移与读写所需操作，commit 返回真实结果（Spec §13.2）。 */
    internal interface Prefs {
        fun getString(key: String): String?
        fun putStringCommit(key: String, value: String): Boolean
        fun removeCommit(key: String): Boolean
    }

    internal sealed class MigrationOutcome {
        /** 进程内已完成过迁移（含本迁移内短路）。 */
        object AlreadyComplete : MigrationOutcome()
        /** 无 legacy 数据：只需清理死键，本进程视为已检查。 */
        data class NoLegacy(val deadKeyCleanupOk: Boolean) : MigrationOutcome()
        /** 迁移全部成功：secure 已有数据且 legacy 已删除。 */
        object Completed : MigrationOutcome()
        /** 失败：legacy 完整保留，迁移状态不得伪报完成。 */
        data class Failed(val phase: Phase, val cause: Throwable? = null) : MigrationOutcome()
    }

    internal enum class Phase { SECURE_INIT, SECURE_WRITE, READ_BACK, LEGACY_DELETE }

    /**
     * 纯迁移内核（JVM 可测）：secure 为 null（初始化失败）即失败；
     * 写入、read-back、删除任一步失败都保留 legacy。
     * legacy 覆盖写入 secure 保留历史自愈语义：旧版本把较新配置明文写回时借此进入加密库。
     */
    internal fun runMigration(legacy: Prefs?, secure: Prefs?): MigrationOutcome {
        if (secure == null) return MigrationOutcome.Failed(Phase.SECURE_INIT)
        val legacyJson = try {
            legacy?.getString(LEGACY_KEY)
        } catch (e: Exception) {
            return MigrationOutcome.Failed(Phase.SECURE_WRITE, e)
        }

        cleanupDeadKeys(legacy)

        if (legacyJson.isNullOrBlank()) return MigrationOutcome.NoLegacy(deadKeyCleanupOk = true)

        val written = try {
            secure.putStringCommit(SECURE_KEY, legacyJson)
        } catch (e: Exception) {
            return MigrationOutcome.Failed(Phase.SECURE_WRITE, e)
        }
        if (!written) return MigrationOutcome.Failed(Phase.SECURE_WRITE)

        val readBack = try {
            secure.getString(SECURE_KEY)
        } catch (e: Exception) {
            return MigrationOutcome.Failed(Phase.READ_BACK, e)
        }
        if (readBack != legacyJson) return MigrationOutcome.Failed(Phase.READ_BACK)

        val deleted = try {
            legacy?.removeCommit(LEGACY_KEY) ?: false
        } catch (e: Exception) {
            return MigrationOutcome.Failed(Phase.LEGACY_DELETE, e)
        }
        if (!deleted) return MigrationOutcome.Failed(Phase.LEGACY_DELETE)

        return MigrationOutcome.Completed
    }

    /** 散装死键清理：无读方，任何结果都不影响迁移正确性。 */
    private fun cleanupDeadKeys(legacy: Prefs?) {
        if (legacy == null) return
        legacyDeadKeys.forEach { key ->
            try {
                legacy.removeCommit(key)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Android 适配 ----------

    private fun legacyPrefs(context: Context): Prefs =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).adapt()

    private fun securePrefs(context: Context): Prefs? = runCatching {
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
    }.getOrNull()?.adapt()

    private fun SharedPreferences.adapt(): Prefs = object : Prefs {
        override fun getString(key: String): String? =
            this@adapt.getString(key, null)

        override fun putStringCommit(key: String, value: String): Boolean = try {
            this@adapt.edit().putString(key, value).commit()
        } catch (e: Exception) {
            false
        }

        override fun removeCommit(key: String): Boolean = try {
            this@adapt.edit().remove(key).commit()
        } catch (e: Exception) {
            false
        }
    }
}
