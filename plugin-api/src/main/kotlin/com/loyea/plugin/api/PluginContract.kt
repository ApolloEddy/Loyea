package com.loyea.plugin.api

enum class PluginCapability {
    PERSONAS,
    PROMPT_PIPELINE,
    OUTPUT_PIPELINE,
    PERSISTENCE,
    USER_INTERFACE,
    BACKGROUND_WORK
}

class PluginDescriptor(
    val id: PluginId,
    val displayName: String,
    val apiVersion: Int,
    capabilities: Set<PluginCapability>
) {
    val capabilities: Set<PluginCapability> = capabilities.toSet()

    init {
        require(displayName.isNotBlank()) { "Plugin display name must not be blank" }
        require(apiVersion > 0) { "Plugin API version must be positive" }
    }

    fun isCompatibleWith(hostApiVersion: Int): Boolean = apiVersion == hostApiVersion
}

/** Identifies one immutable runtime generation captured by an in-flight request. */
data class PluginRuntimeGeneration(
    val pluginId: PluginId,
    val revision: Long
) {
    init {
        require(revision >= 0L) { "Plugin runtime revision must not be negative" }
    }

    fun next(): PluginRuntimeGeneration {
        check(revision < Long.MAX_VALUE) { "Plugin runtime revision overflow" }
        return copy(revision = revision + 1L)
    }
}

/** Bundled plugin factory. Android-specific composition stays outside this pure Kotlin API. */
interface LoyeaPlugin {
    val descriptor: PluginDescriptor

    fun createRuntime(generation: PluginRuntimeGeneration): PluginRuntime
}

/** A started plugin runtime. Hosts close it only after all leases for its generation drain. */
interface PluginRuntime : AutoCloseable {
    val descriptor: PluginDescriptor
    val generation: PluginRuntimeGeneration
}

/** Immutable request-scoped view of a runtime; disabling a plugin does not mutate this lease. */
interface PluginRequestLease<out R : PluginRuntime> : AutoCloseable {
    val runtime: R
    val generation: PluginRuntimeGeneration
}
