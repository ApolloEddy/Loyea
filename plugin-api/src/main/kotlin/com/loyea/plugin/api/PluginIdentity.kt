package com.loyea.plugin.api

/** Current host/plugin contract. Bump only for an intentionally breaking API change. */
const val LOYEA_PLUGIN_API_VERSION: Int = 1

/** Stable, persistence-safe plugin namespace. */
@JvmInline
value class PluginId private constructor(val value: String) {
    companion object {
        private val VALID_ID = Regex("[a-z][a-z0-9._-]{2,95}")

        fun of(value: String): PluginId {
            require(VALID_ID.matches(value)) {
                "Plugin id must match ${VALID_ID.pattern}"
            }
            return PluginId(value)
        }
    }

    override fun toString(): String = value
}

object PluginIds {
    /** Namespace used by functionality and personas owned by Loyea itself. */
    val NATIVE: PluginId = PluginId.of("loyea.native")
}

/**
 * Persistent persona identity. The owner namespace prevents a disabled plugin persona from
 * silently resolving to a native persona that happens to use the same local id.
 */
data class PersonaRef(
    val ownerId: PluginId,
    val personaId: String
) {
    init {
        require(personaId.isNotBlank()) { "Persona id must not be blank" }
        require(personaId.length <= 256) { "Persona id must not exceed 256 characters" }
        require(personaId.none(Char::isISOControl)) { "Persona id must not contain control characters" }
    }

    val isNative: Boolean
        get() = ownerId == PluginIds.NATIVE

    companion object {
        fun native(personaId: String): PersonaRef = PersonaRef(PluginIds.NATIVE, personaId)

        fun plugin(ownerId: PluginId, personaId: String): PersonaRef {
            require(ownerId != PluginIds.NATIVE) { "Plugin persona must use a plugin namespace" }
            return PersonaRef(ownerId, personaId)
        }
    }
}
