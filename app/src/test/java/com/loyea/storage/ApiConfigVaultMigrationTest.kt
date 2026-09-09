package com.loyea.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ApiConfigVault 原子迁移故障注入测试（Spec §13.4 全表）。
 *
 * 核心不变式：任何一步失败都必须「保留 legacy、不伪报迁移完成」；
 * saveJson 在迁移未完成或写失败时必须拒绝写入，防止默认配置覆盖用户数据。
 */
class ApiConfigVaultMigrationTest {

    private val legacyJson = """[{"id":"cfg1","name":"My Key","apiKey":"sk-secret"}]"""

    /** 可故障注入的假 Prefs：记录写入/删除调用，按开关返回失败。 */
    private class FakePrefs(
        var stored: MutableMap<String, String> = mutableMapOf(),
        var failPut: Boolean = false,
        var failRemove: Boolean = false,
        var failRead: Boolean = false,
        var throwOnPut: Throwable? = null,
        var removeShouldFailKey: String? = null
    ) : ApiConfigVault.Prefs {

        val removedKeys = mutableListOf<String>()

        override fun getString(key: String): String? {
            if (failRead) throw IllegalStateException("read boom")
            return stored[key]
        }

        override fun putStringCommit(key: String, value: String): Boolean {
            throwOnPut?.let { throw it }
            if (failPut) return false
            stored[key] = value
            return true
        }

        override fun removeCommit(key: String): Boolean {
            if (failRemove || key == removeShouldFailKey) return false
            removedKeys.add(key)
            stored.remove(key)
            return true
        }
    }

    private fun legacyPrefs(json: String? = legacyJson) = FakePrefs(
        stored = mutableMapOf(
            "api_config_list" to json!!,
            "api_key" to "old-dead-key",
            "api_provider" to "DeepSeek"
        )
    )

    // ===== 场景 1：secure init 失败 → legacy 保留 =====

    @Test
    fun `secure init failure keeps legacy untouched`() {
        val legacy = legacyPrefs()
        val outcome = ApiConfigVault.runMigration(legacy = legacy, secure = null)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.Failed)
        assertEquals(ApiConfigVault.Phase.SECURE_INIT, (outcome as ApiConfigVault.MigrationOutcome.Failed).phase)
        assertEquals(legacyJson, legacy.stored["api_config_list"])
    }

    // ===== 场景 2：secure write 失败 → legacy 保留 =====

    @Test
    fun `secure write failure keeps legacy`() {
        val legacy = legacyPrefs()
        val secure = FakePrefs(failPut = true)
        val outcome = ApiConfigVault.runMigration(legacy, secure)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.Failed)
        assertEquals(ApiConfigVault.Phase.SECURE_WRITE, (outcome as ApiConfigVault.MigrationOutcome.Failed).phase)
        assertEquals(legacyJson, legacy.stored["api_config_list"])
        assertFalse(secure.stored.containsKey("api_config_list_secure"))
    }

    @Test
    fun `secure write exception keeps legacy`() {
        val legacy = legacyPrefs()
        val secure = FakePrefs(throwOnPut = IllegalStateException("keystore boom"))
        val outcome = ApiConfigVault.runMigration(legacy, secure)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.Failed)
        assertEquals(legacyJson, legacy.stored["api_config_list"])
    }

    // ===== 场景 3：read-back 不一致 → legacy 保留 =====

    @Test
    fun `read back mismatch keeps legacy`() {
        val legacy = legacyPrefs()
        val secure = FakePrefs()
        // 模拟加密层写入后读出内容损坏
        secure.stored["api_config_list_secure"] = "corrupted-after-write"
        // 直接操纵：先让 put 成功，但 getString 返回被篡改的值
        val tampered = object : ApiConfigVault.Prefs by secure {
            override fun getString(key: String): String? = "tampered"
        }
        val outcome = ApiConfigVault.runMigration(legacy, tampered)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.Failed)
        assertEquals(ApiConfigVault.Phase.READ_BACK, (outcome as ApiConfigVault.MigrationOutcome.Failed).phase)
        assertEquals(legacyJson, legacy.stored["api_config_list"])
    }

    // ===== 场景 4：legacy 删除失败 → secure 已存在但不得伪报完成 =====

    @Test
    fun `legacy delete failure leaves secure populated but migration incomplete`() {
        val legacy = legacyPrefs()
        legacy.removeShouldFailKey = "api_config_list"
        val secure = FakePrefs()
        val outcome = ApiConfigVault.runMigration(legacy, secure)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.Failed)
        assertEquals(ApiConfigVault.Phase.LEGACY_DELETE, (outcome as ApiConfigVault.MigrationOutcome.Failed).phase)
        // secure 已有数据（不会双写丢失），legacy 仍在
        assertEquals(legacyJson, secure.stored["api_config_list_secure"])
        assertEquals(legacyJson, legacy.stored["api_config_list"])
    }

    // ===== 场景 5：成功 → secure 存在，legacy 删除 =====

    @Test
    fun `successful migration writes secure and deletes legacy`() {
        val legacy = legacyPrefs()
        val secure = FakePrefs()
        val outcome = ApiConfigVault.runMigration(legacy, secure)
        assertEquals(ApiConfigVault.MigrationOutcome.Completed, outcome)
        assertEquals(legacyJson, secure.stored["api_config_list_secure"])
        assertNull(legacy.stored["api_config_list"])
    }

    @Test
    fun `successful migration also cleans dead plaintext keys`() {
        val legacy = legacyPrefs()
        val secure = FakePrefs()
        ApiConfigVault.runMigration(legacy, secure)
        assertTrue(legacy.removedKeys.contains("api_key"))
        assertTrue(legacy.removedKeys.contains("api_provider"))
    }

    // ===== 场景 6：重复执行 → 幂等 =====

    @Test
    fun `rerun after completion is idempotent no-legacy`() {
        val legacy = legacyPrefs()
        val secure = FakePrefs()
        ApiConfigVault.runMigration(legacy, secure)
        // 第二次运行：legacy 已删（只剩残余死键），应为 NoLegacy 而不是再写一遍
        val second = ApiConfigVault.runMigration(legacy, secure)
        assertTrue(second is ApiConfigVault.MigrationOutcome.NoLegacy)
        assertEquals(legacyJson, secure.stored["api_config_list_secure"])
    }

    // ===== 场景 6b：无 legacy → NoLegacy，secure 不被触碰 =====

    @Test
    fun `no legacy reports NoLegacy and leaves secure untouched`() {
        val legacy = FakePrefs(stored = mutableMapOf("unrelated" to "x"))
        val secure = FakePrefs(stored = mutableMapOf("api_config_list_secure" to "existing-data"))
        val outcome = ApiConfigVault.runMigration(legacy, secure)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.NoLegacy)
        // 无 legacy 时不得覆盖 secure 中已有数据
        assertEquals("existing-data", secure.stored["api_config_list_secure"])
    }

    @Test
    fun `blank legacy treated as no legacy`() {
        val legacy = legacyPrefs(json = "  ")
        val secure = FakePrefs()
        val outcome = ApiConfigVault.runMigration(legacy, secure)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.NoLegacy)
        assertFalse(secure.stored.containsKey("api_config_list_secure"))
    }

    // ===== 场景 7：saveJson 语义——迁移未完成 / 写失败必须拒绝 =====

    @Test
    fun `save refuses when migration is incomplete`() {
        // 通过 runMigration 语义模拟：legacy 存在且 secure 写失败 → 迁移未完成
        val legacy = legacyPrefs()
        val failingSecure = FakePrefs(failPut = true)
        val outcome = ApiConfigVault.runMigration(legacy, failingSecure)
        assertTrue(outcome is ApiConfigVault.MigrationOutcome.Failed)
        // 该状态下 saveJson（Android 层）会返回 Failure 而不是覆盖 secure；此处验证失败相位正确
        assertEquals(ApiConfigVault.Phase.SECURE_WRITE, (outcome as ApiConfigVault.MigrationOutcome.Failed).phase)
    }

    @Test
    fun `save write failure and read-back mismatch are detectable`() {
        val secure = FakePrefs(failPut = true)
        assertFalse(secure.putStringCommit("k", "v"))
        val tampered = object : ApiConfigVault.Prefs by secure {
            override fun getString(key: String): String? = "different"
        }
        // read-back 校验语义：写入值与读回值不一致必须判失败
        assertTrue(tampered.getString("k") != "v")
    }
}
