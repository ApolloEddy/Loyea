package com.loyea.plugins.tavern.core

import com.loyea.context.core.*

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 角色卡可以引用的外部酒馆资源。
 *
 * 资源保存原始 JSON，而不是只保存 Loyea 的结构化投影。这样未知字段、第三方
 * extensions 和以后新增的 ST 字段不会在导入注册时丢失。
 */
data class TavernWorldBookResource(
    val id: String,
    val name: String,
    val rawJson: String,
    val enabled: Boolean = true,
    val source: String = ""
)

data class TavernPresetResource(
    val id: String,
    val name: String,
    val rawJson: String,
    val enabled: Boolean = true,
    val source: String = ""
)

data class TavernRegexResource(
    val id: String,
    val name: String,
    val rawJson: String,
    val enabled: Boolean = true,
    val source: String = ""
)

data class TavernResourceRegistry(
    val worldBooks: List<TavernWorldBookResource> = emptyList(),
    val presets: List<TavernPresetResource> = emptyList(),
    val regexCollections: List<TavernRegexResource> = emptyList(),
    val revision: Long = 0L
)

/** JSON 编解码和角色卡外部绑定发现。 */
object TavernResourceRegistryCodec {
    fun parse(json: String): TavernResourceRegistry? = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return@runCatching null
        val worldBooks = parseWorldBooks(root)
        val presets = parsePresets(root)
        val regex = parseRegexCollections(root)
        TavernResourceRegistry(
            worldBooks = worldBooks,
            presets = presets,
            regexCollections = regex,
            revision = root.longOrNull("revision") ?: System.currentTimeMillis()
        )
    }.getOrNull()

    fun toJson(registry: TavernResourceRegistry): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        root.addProperty("revision", registry.revision)
        root.add("worldBooks", JsonArray().also { array ->
            registry.worldBooks.forEach { resource ->
                array.add(JsonObject().apply {
                    addProperty("id", resource.id)
                    addProperty("name", resource.name)
                    addProperty("rawJson", resource.rawJson)
                    addProperty("enabled", resource.enabled)
                    addProperty("source", resource.source)
                })
            }
        })
        root.add("presets", JsonArray().also { array ->
            registry.presets.forEach { resource ->
                array.add(JsonObject().apply {
                    addProperty("id", resource.id)
                    addProperty("name", resource.name)
                    addProperty("rawJson", resource.rawJson)
                    addProperty("enabled", resource.enabled)
                    addProperty("source", resource.source)
                })
            }
        })
        root.add("regexCollections", JsonArray().also { array ->
            registry.regexCollections.forEach { resource ->
                array.add(JsonObject().apply {
                    addProperty("id", resource.id)
                    addProperty("name", resource.name)
                    addProperty("rawJson", resource.rawJson)
                    addProperty("enabled", resource.enabled)
                    addProperty("source", resource.source)
                })
            }
        })
        return root.toString()
    }

    fun worldBookResource(id: String, name: String, rawJson: String, source: String = "") =
        TavernWorldBookResource(
            id = id.ifBlank { stableResourceId("world", name, rawJson) },
            name = name.ifBlank { "World Book" },
            rawJson = rawJson,
            source = source
        )

    fun presetResource(id: String, name: String, rawJson: String, source: String = "") =
        TavernPresetResource(
            id = id.ifBlank { stableResourceId("preset", name, rawJson) },
            name = name.ifBlank { "Preset" },
            rawJson = rawJson,
            source = source
        )

    fun regexResource(id: String, name: String, rawJson: String, source: String = "") =
        TavernRegexResource(
            id = id.ifBlank { stableResourceId("regex", name, rawJson) },
            name = name.ifBlank { "Regex Scripts" },
            rawJson = rawJson,
            source = source
        )

    private fun parseWorldBooks(root: JsonObject): List<TavernWorldBookResource> =
        parseResourceArray(root, "worldBooks", "world_books", "worldInfo", "world_info") { obj, index ->
            TavernWorldBookResource(
                id = obj.stringOrBlank("id", "uid", "name").ifBlank { "world_$index" },
                name = obj.stringOrBlank("name", "title", "id").ifBlank { "World Book $index" },
                rawJson = obj.rawJsonOrSelf("rawJson", "raw_json", "json"),
                enabled = obj.booleanOrDefault("enabled", true),
                source = obj.stringOrBlank("source", "file")
            )
        }

    private fun parsePresets(root: JsonObject): List<TavernPresetResource> =
        parseResourceArray(root, "presets", "promptPresets", "prompt_preset") { obj, index ->
            TavernPresetResource(
                id = obj.stringOrBlank("id", "uid", "name").ifBlank { "preset_$index" },
                name = obj.stringOrBlank("name", "title", "id").ifBlank { "Preset $index" },
                rawJson = obj.rawJsonOrSelf("rawJson", "raw_json", "json"),
                enabled = obj.booleanOrDefault("enabled", true),
                source = obj.stringOrBlank("source", "file")
            )
        }

    private fun parseRegexCollections(root: JsonObject): List<TavernRegexResource> =
        parseResourceArray(root, "regexCollections", "regex_collections", "regexScripts") { obj, index ->
            TavernRegexResource(
                id = obj.stringOrBlank("id", "uid", "name").ifBlank { "regex_$index" },
                name = obj.stringOrBlank("name", "title", "id").ifBlank { "Regex $index" },
                rawJson = obj.rawJsonOrSelf("rawJson", "raw_json", "json"),
                enabled = obj.booleanOrDefault("enabled", true),
                source = obj.stringOrBlank("source", "file")
            )
        }

    private fun <T> parseResourceArray(
        root: JsonObject,
        vararg keys: String,
        mapper: (JsonObject, Int) -> T
    ): List<T> {
        val element = keys.asSequence().mapNotNull { root[it] }.firstOrNull() ?: return emptyList()
        return when {
            element.isJsonArray -> element.asJsonArray.mapIndexedNotNull { index, item ->
                item.takeIf { it.isJsonObject }?.asJsonObject?.let { mapper(it, index) }
            }
            element.isJsonObject -> element.asJsonObject.entrySet().mapIndexedNotNull { index, (_, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.let { mapper(it, index).let { mapped ->
                    mapped
                } }
            }
            else -> emptyList()
        }
    }

    private fun stableResourceId(prefix: String, name: String, rawJson: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$name\u0000$rawJson".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "${prefix}_$digest"
    }

    private fun JsonObject.stringOrBlank(vararg names: String): String = names.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        .orEmpty()

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
        this[name]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

    private fun JsonObject.rawJsonOrSelf(vararg names: String): String =
        names.asSequence()
            .mapNotNull { this[it] }
            .firstOrNull { value ->
                value.isJsonObject || value.isJsonArray ||
                    (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank())
            }
            ?.let { value ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
            }
            ?: toString()

    private fun JsonObject.longOrNull(name: String): Long? =
        this[name]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}

/** 将外部世界书 JSON 转换为 Loyea 世界书模型。支持 ST kind:0、entries 数组和对象。 */
object TavernWorldBookCodec {
    fun parse(json: String): WorldInfoBook? = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return@runCatching null
        val entriesElement = root["entries"] ?: root["worldInfo"] ?: root["world_info"]
        val entries = when {
            entriesElement?.isJsonObject == true -> entriesElement.asJsonObject.entrySet()
                .mapIndexedNotNull { index, (key, value) ->
                    value.asObjectOrNull()?.let { parseEntry(it, index, key) }
                }
            entriesElement?.isJsonArray == true -> entriesElement.asJsonArray.mapIndexedNotNull { index, value ->
                value.asObjectOrNull()?.let { parseEntry(it, index, "") }
            }
            else -> emptyList()
        }
        val config = WorldInfoConfig(
            scanDepth = root.intOrNull("scan_depth", "scanDepth") ?: 10,
            tokenBudget = (root.intOrNull("token_budget", "tokenBudget") ?: 2048).toLong(),
            position = root.stringOrBlank("position", "insertion_position", "insertionPosition")
                .ifBlank { "bottom" },
            insertionOrderMode = root.stringOrBlank("loyea_insertion_order_mode", "insertionOrderMode")
                .let { value -> runCatching { WorldInfoInsertionOrder.valueOf(value) }.getOrDefault(WorldInfoInsertionOrder.ORDER) },
            recursionDepthCap = root.intOrNull("recursion_depth_cap", "recursionDepthCap") ?: 3,
            allowRecursion = root.booleanOrNull("recursive_scanning", "recursiveScanning") ?: true,
            caseSensitive = root.booleanOrNull("case_sensitive", "caseSensitive") ?: false,
            matchWholeWords = root.booleanOrNull("match_whole_words", "matchWholeWords") ?: false,
            useGroupScoring = root.booleanOrNull("use_group_scoring", "useGroupScoring") ?: false,
            budgetCap = (root.intOrNull("budget_cap", "budgetCap") ?: 0).toLong(),
            emitGroupHeaders = root.booleanOrNull("loyea_emit_group_headers", "emitGroupHeaders") ?: false
        )
        WorldInfoBook(
            entries = entries,
            config = config,
            name = root.stringOrBlank("name", "title"),
            description = root.stringOrBlank("description"),
            extensionsJson = root["extensions"]?.asObjectOrNull()?.toString() ?: "{}",
            rawJson = json
        )
    }.getOrNull()

    fun export(book: WorldInfoBook): String {
        val root = parseObjectOrNull(book.rawJson) ?: JsonObject()
        root.apply {
            addProperty("kind", 0)
            if (book.name.isNotBlank()) addProperty("name", book.name)
            if (book.description.isNotBlank()) addProperty("description", book.description)
            addProperty("scan_depth", book.config.scanDepth)
            addProperty("token_budget", book.config.tokenBudget)
            addProperty("recursive_scanning", book.config.allowRecursion)
            addProperty("position", book.config.position)
            addProperty("recursion_depth_cap", book.config.recursionDepthCap)
            addProperty("case_sensitive", book.config.caseSensitive)
            addProperty("match_whole_words", book.config.matchWholeWords)
            addProperty("use_group_scoring", book.config.useGroupScoring)
            addProperty("budget_cap", book.config.budgetCap)
            addProperty("loyea_insertion_order_mode", book.config.insertionOrderMode.name)
            addProperty("loyea_emit_group_headers", book.config.emitGroupHeaders)
            add("extensions", parseObjectOrNull(book.extensionsJson) ?: JsonObject())
        }
        root.add("entries", JsonObject().also { entries ->
            book.entries.forEachIndexed { index, entry ->
                entries.add(entry.id.ifBlank { "entry_${index + 1}" }, entryToJson(entry))
            }
        })
        return root.toString()
    }

    private fun parseEntry(obj: JsonObject, index: Int, objectKey: String): WorldInfoEntry {
        val extensions = obj["extensions"]?.asObjectOrNull()
        val characterFilter = obj["characterFilter"]?.asObjectOrNull()
            ?: obj["character_filter"]?.asObjectOrNull()
            ?: extensions?.get("characterFilter")?.asObjectOrNull()
            ?: extensions?.get("character_filter")?.asObjectOrNull()
        fun string(vararg names: String): String = names.asSequence()
            .mapNotNull { obj[it] ?: extensions?.get(it) }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString.orEmpty()
        fun bool(vararg names: String): Boolean? = names.asSequence()
            .mapNotNull { obj[it] ?: extensions?.get(it) }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean
        fun int(vararg names: String): Int? = names.asSequence()
            .mapNotNull { obj[it] ?: extensions?.get(it) }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt
        fun strings(vararg names: String): List<String> = names.asSequence()
            .mapNotNull { obj[it] ?: extensions?.get(it) }
            .map { element ->
                when {
                    element.isJsonArray -> element.asJsonArray.mapNotNull { item ->
                        item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    }
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> listOf(element.asString)
                    else -> emptyList()
                }
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        fun intOrBoolean(vararg names: String): Int? = names.asSequence()
            .mapNotNull { obj[it] ?: extensions?.get(it) }
            .firstOrNull { it.isJsonPrimitive }
            ?.let { value ->
                when {
                    value.asJsonPrimitive.isNumber -> value.asInt
                    value.asJsonPrimitive.isBoolean -> if (value.asBoolean) 1 else 0
                    else -> null
                }
            }
        fun roleName(): String? = (obj["role"] ?: extensions?.get("role"))?.let { value ->
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> when (value.asInt) {
                    0 -> "system"
                    1 -> "user"
                    2 -> "assistant"
                    else -> null
                }
                else -> null
            }
        }
        fun filterStrings(vararg names: String): List<String> = names.asSequence()
            .mapNotNull { obj[it] ?: extensions?.get(it) }
            .map { value ->
                when {
                    value.isJsonArray -> value.asJsonArray.mapNotNull { item ->
                        item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    }
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
                    else -> emptyList()
                }
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val filterNames = filterStrings("characterFilterNames", "character_filter_names")
            .ifEmpty { characterFilter?.get("names")?.let { value ->
                if (value.isJsonArray) value.asJsonArray.mapNotNull { it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString }
                else emptyList()
            }.orEmpty() }
        val filterTags = filterStrings("characterFilterTags", "character_filter_tags")
            .ifEmpty { characterFilter?.get("tags")?.let { value ->
                if (value.isJsonArray) value.asJsonArray.mapNotNull { it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString }
                else emptyList()
            }.orEmpty() }
        val filterExclude = obj.booleanOrNull("characterFilterExclude", "character_filter_exclude")
            ?: characterFilter?.booleanOrNull("isExclude", "exclude", "characterFilterExclude")
            ?: extensions?.booleanOrNull("characterFilterExclude", "character_filter_exclude")
            ?: false
        val legacyPosition = int("position") ?: 0
        val positionType = string("positionType", "position_type").ifBlank {
            when (legacyPosition) {
                0 -> "before_char"
                1 -> "after_char"
                2 -> "an_top"
                3 -> "an_bottom"
                4 -> "at_depth"
                5 -> "em_top"
                6 -> "em_bottom"
                7 -> "outlet"
                else -> "legacy"
            }
        }
        val disabled = bool("disable") ?: false
        return WorldInfoEntry(
            id = string("id").ifBlank { objectKey.ifBlank { "entry_${index + 1}" } },
            uid = int("uid") ?: index + 1,
            keywords = strings("key", "keys"),
            keysecondary = strings("keysecondary", "secondary_keys", "secondaryKeys"),
            content = string("content"),
            enabled = (bool("enabled") ?: true) && !disabled,
            disable = disabled,
            constant = bool("constant") ?: false,
            selective = bool("selective") ?: false,
            selectiveLogic = int("selectiveLogic", "selective_logic") ?: WorldInfoMatcher.AND_ANY,
            order = int("order", "insertion_order", "insertionOrder") ?: 100,
            depth = int("depth") ?: 4,
            comment = string("comment"),
            group = string("group"),
            probability = int("probability") ?: 100,
            // SillyTavern's native entry default is probability-gated; 100% remains
            // behaviorally identical for ordinary entries while preserving 0% imports.
            useProbability = bool("useProbability", "use_probability") ?: true,
            delayUntilRecursion = intOrBoolean("delayUntilRecursion", "delay_until_recursion") ?: 0,
            preventRecursion = bool("preventRecursion", "prevent_recursion") ?: false,
            allowRecursion = bool("allowRecursion", "allow_recursion") ?: true,
            excludeRecursion = bool("excludeRecursion", "exclude_recursion") ?: false,
            keysContainedIn = string("keysContainedIn", "keys_contained_in").ifBlank { "chat" },
            position = legacyPosition,
            weight = int("weight") ?: 0,
            useRegex = bool("useRegex", "use_regex") ?: false,
            caseSensitive = bool("caseSensitive", "case_sensitive"),
            matchWholeWords = bool("matchWholeWords", "match_whole_words"),
            positionType = positionType,
            injectionDepth = int("injectionDepth", "injection_depth") ?: int("depth") ?: 0,
            role = roleName(),
            outletName = string("outletName", "outlet_name").ifBlank { null },
            groupOverride = bool("groupOverride", "group_override") ?: false,
            groupWeight = int("groupWeight", "group_weight") ?: 100,
            useGroupScoring = bool("useGroupScoring", "use_group_scoring") ?: false,
            priority = int("priority"),
            scanDepthOverride = int("scanDepth", "scan_depth"),
            sticky = int("sticky") ?: 0,
            cooldown = int("cooldown") ?: 0,
            delay = int("delay") ?: 0,
            triggers = strings("triggers"),
            extensionsJson = extensions?.toString() ?: "{}",
            automationId = string("automationId", "automation_id"),
            vectorized = bool("vectorized") ?: false,
            matchPersonaDescription = bool("matchPersonaDescription", "match_persona_description") ?: false,
            matchCharacterDescription = bool("matchCharacterDescription", "match_character_description") ?: false,
            matchCharacterPersonality = bool("matchCharacterPersonality", "match_character_personality") ?: false,
            matchCharacterDepthPrompt = bool("matchCharacterDepthPrompt", "match_character_depth_prompt") ?: false,
            matchScenario = bool("matchScenario", "match_scenario") ?: false,
            matchCreatorNotes = bool("matchCreatorNotes", "match_creator_notes") ?: false,
            ignoreBudget = bool("ignoreBudget", "ignore_budget") ?: false,
            characterFilterNames = filterNames,
            characterFilterTags = filterTags,
            characterFilterExclude = filterExclude,
            addMemo = bool("addMemo", "add_memo") ?: true,
            displayIndex = int("displayIndex", "display_index") ?: index,
            rawJson = obj.toString(),
        )
    }

    private fun entryToJson(entry: WorldInfoEntry): JsonObject = (parseObjectOrNull(entry.rawJson) ?: JsonObject()).apply {
        addProperty("uid", entry.uid)
        add("key", JsonArray().also { entry.keywords.forEach(it::add) })
        add("keysecondary", JsonArray().also { entry.keysecondary.forEach(it::add) })
        addProperty("content", entry.content)
        addProperty("comment", entry.comment)
        addProperty("constant", entry.constant)
        addProperty("selective", entry.selective)
        addProperty("selectiveLogic", entry.selectiveLogic)
        addProperty("order", entry.order)
        addProperty("depth", entry.depth)
        addProperty("disable", !entry.enabled || entry.disable)
        addProperty("enabled", entry.enabled && !entry.disable)
        addProperty("useRegex", entry.useRegex)
        addProperty("position", entry.position)
        addProperty("positionType", entry.positionType)
        addProperty("injectionDepth", entry.injectionDepth)
        entry.role?.let { addProperty("role", roleIndex(it)) }
        entry.outletName?.let { addProperty("outletName", it) }
        addProperty("group", entry.group)
        addProperty("groupOverride", entry.groupOverride)
        addProperty("groupWeight", entry.groupWeight)
        addProperty("useGroupScoring", entry.useGroupScoring)
        addProperty("probability", entry.probability)
        addProperty("useProbability", entry.useProbability)
        addProperty("delayUntilRecursion", entry.delayUntilRecursion)
        addProperty("preventRecursion", entry.preventRecursion)
        addProperty("allowRecursion", entry.allowRecursion)
        addProperty("excludeRecursion", entry.excludeRecursion)
        addProperty("keysContainedIn", entry.keysContainedIn)
        addProperty("sticky", entry.sticky)
        addProperty("cooldown", entry.cooldown)
        addProperty("delay", entry.delay)
        entry.caseSensitive?.let { addProperty("caseSensitive", it) }
        entry.matchWholeWords?.let { addProperty("matchWholeWords", it) }
        entry.priority?.let { addProperty("priority", it) }
        addProperty("automationId", entry.automationId)
        addProperty("vectorized", entry.vectorized)
        addProperty("matchPersonaDescription", entry.matchPersonaDescription)
        addProperty("matchCharacterDescription", entry.matchCharacterDescription)
        addProperty("matchCharacterPersonality", entry.matchCharacterPersonality)
        addProperty("matchCharacterDepthPrompt", entry.matchCharacterDepthPrompt)
        addProperty("matchScenario", entry.matchScenario)
        addProperty("matchCreatorNotes", entry.matchCreatorNotes)
        addProperty("ignoreBudget", entry.ignoreBudget)
        if (entry.characterFilterNames.isNotEmpty() || entry.characterFilterTags.isNotEmpty() || entry.characterFilterExclude) {
            add("characterFilter", JsonObject().apply {
                add("names", JsonArray().also { values -> entry.characterFilterNames.forEach(values::add) })
                add("tags", JsonArray().also { values -> entry.characterFilterTags.forEach(values::add) })
                addProperty("isExclude", entry.characterFilterExclude)
            })
        }
        addProperty("addMemo", entry.addMemo)
        addProperty("displayIndex", entry.displayIndex)
        add("triggers", JsonArray().also { entry.triggers.forEach(it::add) })
        add("extensions", parseObjectOrNull(entry.extensionsJson) ?: JsonObject())
    }

    private fun roleIndex(role: String): Int = when (role.lowercase()) {
        "user", "1" -> 1
        "assistant", "2" -> 2
        else -> 0
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.intOrNull(vararg names: String): Int? = names.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

    private fun JsonObject.stringOrBlank(vararg names: String): String = names.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        .orEmpty()

    private fun parseObjectOrNull(json: String?): JsonObject? = runCatching {
        json?.takeIf { it.isNotBlank() }?.let(JsonParser::parseString)
            ?.takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun JsonObject.booleanOrNull(vararg names: String): Boolean? = names.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean
}
