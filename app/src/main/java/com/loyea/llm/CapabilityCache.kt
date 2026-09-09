package com.loyea.llm

/**
 * 协议能力三态（Spec §7.2）：UNKNOWN ≠ UNSUPPORTED。
 */
enum class CapabilityState { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * 渠道协议能力集。
 */
data class ChatCapabilities(
    val streaming: CapabilityState = CapabilityState.UNKNOWN,
    val tools: CapabilityState = CapabilityState.UNKNOWN,
    val nativeSearch: CapabilityState = CapabilityState.UNSUPPORTED,
    val vision: CapabilityState = CapabilityState.UNKNOWN,
    val audioInput: CapabilityState = CapabilityState.UNKNOWN,
    val streamOptions: CapabilityState = CapabilityState.UNKNOWN
)

/**
 * 能力缓存键（Spec §9.2）：configId + model + adapterId 三元组，
 * 防止 endpoint+model 形态下的跨配置能力污染。
 */
data class CapabilityCacheKey(
    val configId: String,
    val model: String,
    val adapterId: String
)

/**
 * 进程内运行时能力缓存（Spec §9）。
 *
 * - 只用于减少同进程重复试错，绝不持久化为用户配置事实；
 * - 仅允许两种写入：stream=true 直接收完整 completion；明确 UnsupportedStreaming 且
 *   随后 non-stream 成功（Spec §9.3）；
 * - Empty/HTML/malformed/timeout 一律不写；fallback 成功前禁止预写；
 * - ApiConfig 的 URL/Key/Profile/defaultModel/streamMode 变更时必须按 configId 失效。
 */
object RuntimeCapabilityCache {

    private val preferNonStream = java.util.Collections.synchronizedSet(HashSet<CapabilityCacheKey>())

    fun prefersNonStream(key: CapabilityCacheKey): Boolean = preferNonStream.contains(key)

    fun markPreferNonStream(key: CapabilityCacheKey) {
        preferNonStream.add(key)
    }

    /** 该配置的任何协议相关字段变更时调用，清除该 configId 下全部学习结果。 */
    fun invalidateConfig(configId: String) {
        synchronized(preferNonStream) {
            preferNonStream.removeAll { it.configId == configId }
        }
    }

    /** 仅供测试：清空全部缓存。 */
    fun clearForTest() {
        synchronized(preferNonStream) {
            preferNonStream.clear()
        }
    }
}
