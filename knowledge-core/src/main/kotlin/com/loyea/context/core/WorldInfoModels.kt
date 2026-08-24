package com.loyea.context.core

enum class WorldInfoInsertionOrder {
    ORDER,
    KEY_LENGTH,
    ALPHABETICAL,
    INSERT_AT_TOP,
    INSERT_AT_BOTTOM
}

data class WorldInfoEntryRuntimeState(
    val lastActivatedTurn: Long = -1L,
    val stickyUntilTurn: Long = -1L,
    val cooldownUntilTurn: Long = -1L
)

data class WorldInfoRuntimeState(
    val turnKey: String = "",
    val turnIndex: Long = 0L,
    val entries: Map<String, WorldInfoEntryRuntimeState> = emptyMap(),
    val bookSignature: String = ""
)

data class WorldInfoConfig(
    val scanDepth: Int = 10,
    val position: String = "bottom",
    val insertionOrderMode: WorldInfoInsertionOrder = WorldInfoInsertionOrder.ORDER,
    val tokenBudget: Long = 2048,
    val recursionDepthCap: Int = 3,
    val allowRecursion: Boolean = true,
    val emitGroupHeaders: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val useGroupScoring: Boolean = false,
    val budgetCap: Long = 0
)

/** Full SillyTavern-compatible world-info entry owned by the Tavern plugin. */
data class WorldInfoEntry(
    val id: String,
    val keywords: List<String>,
    val content: String,
    val enabled: Boolean = true,
    val uid: Int = 0,
    val keysecondary: List<String> = emptyList(),
    val constant: Boolean = false,
    val order: Int = 100,
    val depth: Int = 4,
    val comment: String = "",
    val selective: Boolean = false,
    val disable: Boolean = false,
    val selectiveLogic: Int = 0,
    val group: String = "",
    val probability: Int = 100,
    val useProbability: Boolean = false,
    val delayUntilRecursion: Int = 0,
    val preventRecursion: Boolean = false,
    val allowRecursion: Boolean = true,
    val excludeRecursion: Boolean = false,
    val keysContainedIn: String = "chat",
    val position: Int = 0,
    val weight: Int = 0,
    val useRegex: Boolean = false,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val positionType: String = "legacy",
    val injectionDepth: Int = 0,
    val role: String? = null,
    val outletName: String? = null,
    val groupOverride: Boolean = false,
    val groupWeight: Int = 100,
    val useGroupScoring: Boolean = false,
    val priority: Int? = null,
    val scanDepthOverride: Int? = null,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val delay: Int = 0,
    val triggers: List<String> = emptyList(),
    val extensionsJson: String = "{}",
    val automationId: String = "",
    val vectorized: Boolean = false,
    val matchPersonaDescription: Boolean = false,
    val matchCharacterDescription: Boolean = false,
    val matchCharacterPersonality: Boolean = false,
    val matchCharacterDepthPrompt: Boolean = false,
    val matchScenario: Boolean = false,
    val matchCreatorNotes: Boolean = false,
    val ignoreBudget: Boolean = false,
    val characterFilterNames: List<String> = emptyList(),
    val characterFilterTags: List<String> = emptyList(),
    val characterFilterExclude: Boolean = false,
    val addMemo: Boolean = true,
    val displayIndex: Int = 0,
    val rawJson: String? = null
)

data class WorldInfoBook(
    val entries: List<WorldInfoEntry> = emptyList(),
    val config: WorldInfoConfig = WorldInfoConfig(),
    val name: String = "",
    val description: String = "",
    val extensionsJson: String = "{}",
    val rawJson: String? = null
)
