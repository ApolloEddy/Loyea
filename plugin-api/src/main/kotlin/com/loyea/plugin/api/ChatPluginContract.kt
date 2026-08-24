package com.loyea.plugin.api

enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class ConversationText(
    val id: String,
    val role: ChatRole,
    val content: String
) {
    init {
        require(id.isNotBlank()) { "Conversation item id must not be blank" }
    }
}

class PersonaProjection(
    val ref: PersonaRef,
    val displayName: String,
    val avatarUri: String?,
    val summary: String,
    greetingTemplates: Collection<String>
) {
    val greetingTemplates: List<String> = greetingTemplates.toList()

    init {
        require(displayName.isNotBlank()) { "Persona display name must not be blank" }
    }
}

class PluginTurnInput(
    val sessionId: String,
    val turnId: String,
    val turnIndex: Long,
    val userName: String,
    history: Collection<ConversationText>
) {
    val history: List<ConversationText> = history.toList()

    init {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(turnId.isNotBlank()) { "Turn id must not be blank" }
        require(turnIndex >= 0L) { "Turn index must not be negative" }
    }
}

data class PromptPatch(
    val stablePersonaText: String,
    val turnContextText: String = "",
    val postHistoryText: String = ""
)

enum class InsertionAnchor {
    AFTER_SYSTEM_BEFORE_SUMMARY,
    AT_DEPTH_FROM_LATEST,
    AFTER_HISTORY
}

data class ConversationInsertion(
    val anchor: InsertionAnchor,
    val role: ChatRole,
    val content: String,
    val depthFromLatest: Int = 0,
    val order: Int = 0
) {
    init {
        require(content.isNotBlank()) { "Conversation insertion must not be blank" }
        if (anchor == InsertionAnchor.AT_DEPTH_FROM_LATEST) {
            require(depthFromLatest >= 0) { "At-depth insertion depth must not be negative" }
        }
    }
}

class GenerationPatch(
    val modelHint: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val maxContextTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val repetitionPenalty: Double? = null,
    stopStrings: Collection<String> = emptyList()
) {
    val stopStrings: List<String> = stopStrings.toList()

    init {
        listOfNotNull(temperature, topP, frequencyPenalty, presencePenalty, repetitionPenalty)
            .forEach { value -> require(value.isFinite()) { "Generation values must be finite" } }
    }
}

class PluginTurnPlan(
    val prompt: PromptPatch,
    insertions: Collection<ConversationInsertion> = emptyList(),
    val generation: GenerationPatch = GenerationPatch(),
    val opaqueSnapshot: String? = null
) {
    val insertions: List<ConversationInsertion> = insertions.toList()
}

enum class TextStage {
    USER_INPUT,
    MODEL_OUTPUT,
    REASONING,
    GREETING
}

/** Frozen, request-scoped plugin behavior. Implementations must not read live plugin state. */
interface PreparedPersonaTurn {
    val plan: PluginTurnPlan

    fun transform(
        stage: TextStage,
        text: String,
        depth: Int? = null,
        isMarkdown: Boolean = false
    ): String
}

interface PersonaProvider {
    val providerId: PluginId

    suspend fun resolve(ref: PersonaRef): PersonaProjection?

    suspend fun prepareTurn(
        ref: PersonaRef,
        input: PluginTurnInput,
        restoredSnapshot: String? = null
    ): PreparedPersonaTurn
}

/** Capability-specific runtime acquired through a PluginManager request lease. */
interface PersonaPluginRuntime : PluginRuntime, PersonaProvider
