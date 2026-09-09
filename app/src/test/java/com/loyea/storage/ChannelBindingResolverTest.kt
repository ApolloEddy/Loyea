package com.loyea.storage

import com.loyea.ui.settings.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChannelBindingResolver 通道解析测试（Spec §23.3）：
 * - VISION 换配置后不残留模型 A；
 * - modelOverride=null 正确使用配置 defaultModel；
 * - disabled 配置被拒绝；
 * - 悬空绑定被识别；
 * - STT/TTS/Image 未配置不使用 CHAT；
 * - MEMORY 继承规则唯一且稳定；
 * - Vision 继承路径的能力佐证。
 */
class ChannelBindingResolverTest {

    private fun config(
        id: String,
        provider: String = "OpenAI",
        model: String = "model-$id",
        apiKey: String = "sk-$id",
        enabled: Boolean = true
    ) = ApiConfig(
        id = id, name = id, provider = provider, apiUrl = "https://api.example.com/v1",
        apiKey = apiKey, modelName = model, isEnabled = enabled
    )

    private val configs = listOf(
        config("visionA", provider = "Zhipu (智谱)", model = "glm-4.5v"),
        config("visionB", provider = "OpenAI", model = "gpt-4o"),
        config("chatMain", provider = "DeepSeek", model = "deepseek-v4-pro"),
        config("disabled", enabled = false, model = "gone")
    )

    private fun resolve(
        channel: ChannelId,
        binding: ChannelBinding,
        activeId: String = "chatMain",
        list: List<ApiConfig> = configs
    ) = ChannelBindingResolver.resolve(channel, binding, list, activeId)

    // ===== VISION：换配置不残留 =====

    @Test
    fun `vision switch from config A to B leaves no model residue`() {
        val withA = resolve(ChannelId.VISION, ChannelBinding(ChannelId.VISION, "visionA"))
        assertTrue(withA is ChannelResolution.Ready)
        assertEquals("glm-4.5v", (withA as ChannelResolution.Ready).resolved.model)

        val withB = ChannelBindingResolver.resolve(
            ChannelId.VISION, ChannelBinding(ChannelId.VISION, "visionB"), configs, "chatMain")
        assertTrue(withB is ChannelResolution.Ready)
        // 模型 A（glm-4.5v）不得残留
        assertEquals("gpt-4o", (withB as ChannelResolution.Ready).resolved.model)
    }

    @Test
    fun `vision chat and caption resolve identically for same binding`() {
        val a = ChannelBindingResolver.resolve(
            ChannelId.VISION, ChannelBinding(ChannelId.VISION, "visionA"), configs, "chatMain")
        val b = ChannelBindingResolver.resolve(
            ChannelId.VISION, ChannelBinding(ChannelId.VISION, "visionA"), configs, "chatMain")
        assertEquals((a as ChannelResolution.Ready).resolved, (b as ChannelResolution.Ready).resolved)
    }

    @Test
    fun `null model override uses config default model`() {
        val r = resolve(ChannelId.VISION, ChannelBinding(ChannelId.VISION, "visionB", modelOverride = null))
        assertEquals("gpt-4o", (r as ChannelResolution.Ready).resolved.model)
    }

    // ===== disabled 配置被拒 =====

    @Test
    fun `disabled config is rejected for every channel`() {
        listOf(ChannelId.VISION, ChannelId.STT, ChannelId.TTS, ChannelId.IMAGE_GENERATION, ChannelId.MEMORY).forEach { channel ->
            val binding = ChannelBinding(channel, "disabled")
            val r = ChannelBindingResolver.resolve(channel, binding, configs, "chatMain")
            assertTrue("channel $channel should be ConfigDisabled", r is ChannelResolution.ConfigDisabled)
        }
    }

    // ===== 悬空绑定被识别 =====

    @Test
    fun `dangling binding is recognized not silently replaced`() {
        val r = resolve(ChannelId.VISION, ChannelBinding(ChannelId.VISION, "deleted-id"))
        assertTrue(r is ChannelResolution.DanglingBinding)
        assertEquals("deleted-id", (r as ChannelResolution.DanglingBinding).configId)
    }

    // ===== STT/TTS/Image 未配置不得使用 CHAT =====

    @Test
    fun `stt tts image unconfigured never fall back to chat`() {
        listOf(ChannelId.STT, ChannelId.TTS, ChannelId.IMAGE_GENERATION).forEach { channel ->
            val r = resolve(channel, ChannelBinding(channel, null))
            assertTrue("channel $channel must be Unconfigured", r is ChannelResolution.Unconfigured)
        }
    }

    // ===== MEMORY 继承规则唯一且稳定 =====

    @Test
    fun `memory inherits chat when unbound`() {
        val r = resolve(ChannelId.MEMORY, ChannelBinding(ChannelId.MEMORY, null))
        assertTrue(r is ChannelResolution.Ready)
        assertEquals("chatMain", (r as ChannelResolution.Ready).resolved.config.id)
    }

    @Test
    fun `memory explicit binding wins over chat inheritance`() {
        val r = resolve(ChannelId.MEMORY, ChannelBinding(ChannelId.MEMORY, "visionB"))
        assertTrue(r is ChannelResolution.Ready)
        assertEquals("visionB", (r as ChannelResolution.Ready).resolved.config.id)
    }

    // ===== Vision 继承路径能力佐证 =====

    @Test
    fun `vision inherit from text-only chat model is rejected`() {
        // DeepSeek 文本模型继承 → 无能力佐证 → Unconfigured（图片以占位发送）
        val r = resolve(ChannelId.VISION, ChannelBinding(ChannelId.VISION, null))
        assertTrue(r is ChannelResolution.Unconfigured)
    }

    @Test
    fun `vision inherit from multimodal chat model is allowed`() {
        val multimodal = listOf(config("omni", provider = "OpenAI", model = "gpt-4o"))
        val r = ChannelBindingResolver.resolve(
            ChannelId.VISION, ChannelBinding(ChannelId.VISION, null), multimodal, "omni")
        assertTrue(r is ChannelResolution.Ready)
    }

    // ===== CHAT =====

    @Test
    fun `chat unconfigured when no active config`() {
        val r = resolve(ChannelId.CHAT, ChannelBinding(ChannelId.CHAT, null), activeId = "missing")
        assertTrue(r is ChannelResolution.Unconfigured)
    }

    // ===== Store：归一化 + 引用清理（Mock SharedPreferences 行为用假 Map 模拟） =====

    @Test
    fun `vision legacy default gpt-4o-mini is normalized to empty override`() {
        val store = FakePrefsBackedStore(mapOf(
            "vision_config_id" to "visionA",
            "vision_model_name" to "gpt-4o-mini"
        ))
        store.normalizeLegacyDefaults()
        assertEquals("", store.raw["vision_model_name"])
        // 归一化后 load 出的 override 为 null（跟随 defaultModel）
        val binding = store.load(ChannelId.VISION)
        assertNull(binding.modelOverride)
        assertEquals("visionA", binding.configId)
        // 用户显式改过的模型保留
        val store2 = FakePrefsBackedStore(mapOf(
            "vision_config_id" to "visionA",
            "vision_model_name" to "qwen-vl-max"
        ))
        store2.normalizeLegacyDefaults()
        assertEquals("qwen-vl-max", store2.raw["vision_model_name"])
    }

    @Test
    fun `clearBindingsFor removes all references to deleted config`() {
        val store = FakePrefsBackedStore(mapOf(
            "vision_config_id" to "gone",
            "vision_model_name" to "gpt-4o",
            "stt_config_id" to "alive",
            "memory_api_config_id" to "gone"
        ))
        val referencing = store.bindingsReferencing("gone")
        assertEquals(2, referencing.size)
        store.clearBindingsFor("gone")
        assertNull(store.load(ChannelId.VISION).configId)
        assertEquals("alive", store.load(ChannelId.STT).configId)
        assertNull(store.load(ChannelId.MEMORY).configId)
    }

    /** 极简假 prefs：仅实现 ChannelBindingStore 用到的 getString/putString/putBoolean 行为 */
    private class FakePrefsBackedStore(initial: Map<String, String>) {
        val raw = initial.toMutableMap()
        private val booleans = mutableMapOf<String, Boolean>()

        val store = ChannelBindingStore(object : android.content.SharedPreferences {
            override fun getString(key: String, defValue: String?): String? = raw[key] ?: defValue
            override fun getBoolean(key: String, defValue: Boolean): Boolean = booleans[key] ?: defValue
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
                override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                    if (value == null) raw.remove(key) else raw[key] = value
                    return this
                }
                override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor {
                    booleans[key] = value
                    return this
                }
                override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor = this
                override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor = this
                override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor = this
                override fun putStringSet(key: String, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
                override fun remove(key: String): android.content.SharedPreferences.Editor { raw.remove(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { raw.clear(); return this }
                override fun commit(): Boolean = true
                override fun apply() {}
            }
            override fun getAll(): MutableMap<String, *> = raw
            override fun getInt(key: String, defValue: Int): Int = defValue
            override fun getLong(key: String, defValue: Long): Long = defValue
            override fun getFloat(key: String, defValue: Float): Float = defValue
            override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun contains(key: String): Boolean = raw.containsKey(key) || booleans.containsKey(key)
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        })

        fun normalizeLegacyDefaults() = store.normalizeLegacyDefaults()
        fun load(channel: ChannelId) = store.load(channel)
        fun bindingsReferencing(id: String) = store.bindingsReferencing(id)
        fun clearBindingsFor(id: String) = store.clearBindingsFor(id)
    }
}
