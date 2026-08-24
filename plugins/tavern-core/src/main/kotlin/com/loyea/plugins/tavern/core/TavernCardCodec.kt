package com.loyea.plugins.tavern.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * 角色卡的规范化数据层。
 *
 * 这里故意同时保存结构化字段和原始 JSON：结构化字段供 Loyea 运行时使用，
 * 原始 JSON 则用于往返导出时保留 ST/Tavern 未认识的字段和第三方 extensions。
 */
data class TavernCardDocument(
    val spec: String,
    val specVersion: String,
    val data: TavernCardData,
    val rawJson: String? = null
)

/** CHARX 中 card.json 与安全读取后的资源表。key 始终是归一化 ZIP 相对路径。 */
data class TavernCharxArchive(
    val document: TavernCardDocument,
    val assets: Map<String, ByteArray>
)

data class TavernCardData(
    val name: String = "",
    val description: String = "",
    val shortDescription: String? = null,
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val mesExample: String = "",
    val creatorNotes: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val groupOnlyGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val nickname: String? = null,
    val source: List<String> = emptyList(),
    val creationDate: Long? = null,
    val modificationDate: Long? = null,
    val creatorNotesMultilingualJson: String = "{}",
    val assetsJson: String = "[]",
    val extensionsJson: String = "{}",
    val characterBook: CharacterBookDocument? = null
)

/** Character Card V2/V3 的 CharacterBook，兼容 ST 扩展字段。 */
data class CharacterBookDocument(
    val name: String? = null,
    val description: String? = null,
    val scanDepth: Int? = null,
    val tokenBudget: Int? = null,
    val recursiveScanning: Boolean? = null,
    val extensionsJson: String = "{}",
    val entries: List<CharacterBookEntryDocument> = emptyList(),
    val rawJson: String? = null
)

data class CharacterBookEntryDocument(
    val id: Int? = null,
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val insertionOrder: Int = 100,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val useRegex: Boolean = false,
    val constant: Boolean = false,
    val name: String? = null,
    val priority: Int? = null,
    val order: Int? = null,
    val comment: String? = null,
    val selective: Boolean = false,
    val selectiveLogic: Int? = null,
    val position: String? = null,
    val positionIndex: Int? = null,
    val depth: Int? = null,
    val role: String? = null,
    val scanDepth: Int? = null,
    val group: String? = null,
    val groupOverride: Boolean? = null,
    val groupWeight: Int? = null,
    val probability: Int? = null,
    val useProbability: Boolean? = null,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val delayUntilRecursion: Int? = null,
    val preventRecursion: Boolean? = null,
    val excludeRecursion: Boolean? = null,
    val keysContainedIn: String? = null,
    val outletName: String? = null,
    val triggers: List<String> = emptyList(),
    val useGroupScoring: Boolean? = null,
    val automationId: String? = null,
    val vectorized: Boolean? = null,
    val matchPersonaDescription: Boolean? = null,
    val matchCharacterDescription: Boolean? = null,
    val matchCharacterPersonality: Boolean? = null,
    val matchCharacterDepthPrompt: Boolean? = null,
    val matchScenario: Boolean? = null,
    val matchCreatorNotes: Boolean? = null,
    val ignoreBudget: Boolean? = null,
    val characterFilterNames: List<String> = emptyList(),
    val characterFilterTags: List<String> = emptyList(),
    val characterFilterExclude: Boolean = false,
    val addMemo: Boolean? = null,
    val displayIndex: Int? = null,
    val extensionsJson: String = "{}",
    val rawJson: String? = null
)

/**
 * 安全解析和序列化角色卡。解析输入来自用户文件，因此所有大小、整数和 PNG
 * 边界都在这里统一限制，不把不可信的输入直接交给业务层。
 */
object TavernCardCodec {
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    private const val MAX_PNG_CHUNK_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_INFLATED_BYTES = 8 * 1024 * 1024
    private const val MAX_CHARX_ENTRIES = 512
    private const val MAX_CHARX_TOTAL_BYTES = 32 * 1024 * 1024

    private val pngSignature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun parseJson(json: String): TavernCardDocument? {
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) return null
        return try {
            val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: return null
            val dataObject = root.objectOrNull("data") ?: root
            val spec = root.stringOrNull("spec")
                ?: if (root.has("data")) "chara_card_v2" else "chara_card_v1"
            val specVersion = root.stringOrNull("spec_version")
                ?: if (spec.contains("v3", ignoreCase = true)) "3.0" else "2.0"

            TavernCardDocument(
                spec = spec,
                specVersion = specVersion,
                data = parseData(dataObject),
                rawJson = json
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 解析角色卡内嵌的 CharacterBook，供运行时与全局世界书合并。 */
    fun parseCharacterBook(json: String): CharacterBookDocument? =
        parseObjectOrNull(json)?.let(::parseBook)

    /**
     * 解析 Character Card V3 的 CHARX 容器，只读取根目录 card.json。
     * 资源文件不落盘、不自动执行，避免 ZIP 路径穿越与压缩炸弹影响导入。
     */
    fun parseCharx(input: InputStream): TavernCardDocument? {
        return try {
            ZipInputStream(input).use { zip ->
                var entryCount = 0
                var totalBytes = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_CHARX_ENTRIES) return null
                    val normalizedName = entry.name.replace('\\', '/')
                    if (normalizedName == "card.json") {
                        val bytes = readBounded(zip, MAX_JSON_BYTES) ?: return null
                        totalBytes += bytes.size
                        if (totalBytes > MAX_CHARX_TOTAL_BYTES) return null
                        return parseJson(String(bytes, StandardCharsets.UTF_8))
                    }
                    // 只推进到下一个 entry；不把 CHARX 资源解压到内存或文件。
                    if (entry.size > MAX_CHARX_TOTAL_BYTES) return null
                    val remainingBudget = (MAX_CHARX_TOTAL_BYTES - totalBytes).coerceAtLeast(0)
                    val skippedBytes = drainBounded(zip, remainingBudget) ?: return null
                    totalBytes += skippedBytes
                    zip.closeEntry()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 读取 V3 CHARX 的 card.json 及其资源文件。
     * 只接受相对路径、限制 entry 数量和总解压大小；资源保留在内存中交给 UI 写入
     * 应用私有目录，绝不按 ZIP 原始路径落盘。
     */
    fun parseCharxWithAssets(input: InputStream): TavernCharxArchive? {
        return try {
            ZipInputStream(input).use { zip ->
                var entryCount = 0
                var totalBytes = 0
                var cardJson: String? = null
                val files = linkedMapOf<String, ByteArray>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_CHARX_ENTRIES) return null
                    val normalizedName = entry.name.replace('\\', '/')
                    if (!isSafeCharxPath(normalizedName) || entry.isDirectory) {
                        if (drainBounded(zip, (MAX_CHARX_TOTAL_BYTES - totalBytes).coerceAtLeast(0)) == null) {
                            return null
                        }
                        zip.closeEntry()
                        continue
                    }
                    val remaining = (MAX_CHARX_TOTAL_BYTES - totalBytes).coerceAtLeast(0).toLong()
                    val bytes = readBounded(zip, minOf(MAX_JSON_BYTES.toLong(), remaining).toInt())
                        ?: return null
                    totalBytes += bytes.size
                    if (totalBytes > MAX_CHARX_TOTAL_BYTES) return null
                    if (normalizedName == "card.json") {
                        cardJson = String(bytes, StandardCharsets.UTF_8)
                    } else {
                        files[normalizedName] = bytes
                    }
                    zip.closeEntry()
                }
                val document = cardJson?.let(::parseJson) ?: return null
                val referenced = referencedCharxAssetNames(document)
                val selected = if (referenced.isEmpty()) {
                    emptyMap()
                } else {
                    files.filterKeys { path ->
                        referenced.any { name -> path == name || path.endsWith("/$name") }
                    }
                }
                TavernCharxArchive(document, selected)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parsePng(input: InputStream): TavernCardDocument? {
        return try {
            val signature = ByteArray(pngSignature.size)
            if (!readFully(input, signature) || !signature.contentEquals(pngSignature)) return null

            val candidates = linkedMapOf<String, MutableList<String>>()
            val lengthBytes = ByteArray(4)
            val typeBytes = ByteArray(4)
            val crcBytes = ByteArray(4)

            while (readFully(input, lengthBytes)) {
                val length = readUInt32(lengthBytes)
                if (length > MAX_PNG_CHUNK_BYTES) {
                    // 此时 chunk type 尚未读取：跳过 type + data + CRC，保持下一轮对齐。
                    if (!skipFully(input, length + 8L)) return null
                    continue
                }
                if (!readFully(input, typeBytes)) return null
                val type = String(typeBytes, StandardCharsets.US_ASCII)
                val chunkData = ByteArray(length.toInt())
                if (!readFully(input, chunkData) || !readFully(input, crcBytes)) return null

                val storedCrc = readUInt32(crcBytes)
                val crc = CRC32().apply {
                    update(typeBytes)
                    update(chunkData)
                }
                val crcValid = crc.value == storedCrc

                if (crcValid && (type == "tEXt" || type == "zTXt" || type == "iTXt")) {
                    val text = parseTextChunk(type, chunkData)
                    if (text != null && text.second.toByteArray(StandardCharsets.UTF_8).size <= MAX_TEXT_BYTES) {
                        candidates.getOrPut(text.first) { mutableListOf() }.add(text.second)
                    }
                }
                if (type == "IEND") break
            }

            val orderedKeys = listOf("ccv3", "chara")
            for (keyword in orderedKeys) {
                for (text in candidates[keyword].orEmpty().asReversed()) {
                    decodeCandidate(text)?.let { return it }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 将文档导出为 V2 或 V3，尽量以 rawJson 为基底保留未知字段。 */
    fun toJson(document: TavernCardDocument, targetSpec: String = document.spec): String {
        val rawRoot = document.rawJson?.let(::parseObjectOrNull)
        val wrapped = rawRoot?.objectOrNull("data") != null
        val root = if (wrapped) rawRoot!! else JsonObject()
        val data = rawRoot?.objectOrNull("data") ?: rawRoot?.deepCopy() ?: JsonObject()
        writeData(data, document.data, targetSpec)

        if (targetSpec == "chara_card_v1") {
            return data.toString()
        }
        root.addProperty("spec", targetSpec)
        root.addProperty("spec_version", if (targetSpec.contains("v3", ignoreCase = true)) "3.0" else "2.0")
        root.add("data", data)
        return root.toString()
    }

    /** 稳定 ID，避免同一张卡重复导入时每次都产生时间戳 ID。 */
    fun stableId(document: TavernCardDocument): String {
        val identity = listOf(
            document.data.name,
            document.data.creator,
            document.data.characterVersion,
            document.data.description,
            document.data.firstMessage
        ).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        return "char_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun parseData(data: JsonObject): TavernCardData {
        val book = data.objectOrNull("character_book") ?: data.objectOrNull("characterBook")
        return TavernCardData(
            name = data.stringOrNull("name").orEmpty(),
            description = data.stringOrNull("description").orEmpty(),
            shortDescription = data.stringOrNull("short_description"),
            personality = data.stringOrNull("personality").orEmpty(),
            scenario = data.stringOrNull("scenario").orEmpty(),
            firstMessage = data.stringOrNull("first_mes").orEmpty(),
            mesExample = data.stringOrNull("mes_example").orEmpty(),
            creatorNotes = data.stringOrNull("creator_notes").orEmpty(),
            systemPrompt = data.stringOrNull("system_prompt").orEmpty(),
            postHistoryInstructions = data.stringOrNull("post_history_instructions").orEmpty(),
            alternateGreetings = data.stringList("alternate_greetings"),
            groupOnlyGreetings = data.stringList("group_only_greetings"),
            tags = data.stringList("tags"),
            creator = data.stringOrNull("creator").orEmpty(),
            characterVersion = data.stringOrNull("character_version").orEmpty(),
            nickname = data.stringOrNull("nickname"),
            source = data.stringList("source"),
            creationDate = data.longOrNull("creation_date"),
            modificationDate = data.longOrNull("modification_date"),
            creatorNotesMultilingualJson = data.objectOrNull("creator_notes_multilingual")?.toString() ?: "{}",
            assetsJson = data.arrayOrNull("assets")?.toString() ?: "[]",
            extensionsJson = data.objectOrNull("extensions")?.toString() ?: "{}",
            characterBook = book?.let(::parseBook)
        )
    }

    private fun parseBook(book: JsonObject): CharacterBookDocument {
        val entriesElement = book["entries"]
        val entries = when {
            entriesElement?.isJsonArray == true -> entriesElement.asJsonArray.mapIndexedNotNull { index, element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { parseEntry(it, index) }
            }
            entriesElement?.isJsonObject == true -> entriesElement.asJsonObject.entrySet().mapIndexedNotNull { index, (key, element) ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    parseEntry(it, key.toIntOrNull() ?: index)
                }
            }
            else -> emptyList()
        }
        return CharacterBookDocument(
            name = book.stringOrNull("name"),
            description = book.stringOrNull("description"),
            scanDepth = book.intOrNull("scan_depth") ?: book.intOrNull("scanDepth"),
            tokenBudget = book.intOrNull("token_budget") ?: book.intOrNull("tokenBudget"),
            recursiveScanning = book.booleanOrNull("recursive_scanning")
                ?: book.booleanOrNull("recursiveScanning"),
            extensionsJson = book.objectOrNull("extensions")?.toString() ?: "{}",
            entries = entries,
            rawJson = book.toString()
        )
    }

    private fun parseEntry(entry: JsonObject, fallbackId: Int): CharacterBookEntryDocument {
        val positionElement = entry["position"]
        val position = positionElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val positionIndex = positionElement?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        val extensions = entry.objectOrNull("extensions")
        val characterFilter = entry.objectOrNull("characterFilter")
            ?: entry.objectOrNull("character_filter")
            ?: extensions?.objectOrNull("characterFilter")
            ?: extensions?.objectOrNull("character_filter")
        fun extensionString(vararg names: String): String? = names.asSequence()
            .mapNotNull { extensions?.stringOrNull(it) }
            .firstOrNull()
        fun extensionBoolean(vararg names: String): Boolean? = names.asSequence()
            .mapNotNull { extensions?.booleanOrNull(it) }
            .firstOrNull()
        fun extensionInt(vararg names: String): Int? = names.asSequence()
            .mapNotNull { extensions?.intOrNull(it) }
            .firstOrNull()
        fun extensionList(vararg names: String): List<String> = names.asSequence()
            .map { extensions?.stringList(it).orEmpty() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        fun intOrBoolean(vararg names: String): Int? = names.asSequence()
            .mapNotNull { entry[it] ?: extensions?.get(it) }
            .firstOrNull { it.isJsonPrimitive }
            ?.let { value ->
                when {
                    value.asJsonPrimitive.isNumber -> value.asInt
                    value.asJsonPrimitive.isBoolean -> if (value.asBoolean) 1 else 0
                    else -> null
                }
            }
        fun roleName(): String? = (entry["role"] ?: extensions?.get("role"))?.let { value ->
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
            .mapNotNull { entry[it] ?: extensions?.get(it) }
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
            .ifEmpty { characterFilter?.stringList("names").orEmpty() }
        val filterTags = filterStrings("characterFilterTags", "character_filter_tags")
            .ifEmpty { characterFilter?.stringList("tags").orEmpty() }
        val filterExclude = entry.booleanOrNull("characterFilterExclude", "character_filter_exclude")
            ?: characterFilter?.booleanOrNull("isExclude", "exclude", "characterFilterExclude")
            ?: extensions?.booleanOrNull("characterFilterExclude", "character_filter_exclude")
            ?: false
        return CharacterBookEntryDocument(
            id = entry.intOrNull("id") ?: fallbackId,
            keys = entry.stringList("keys").ifEmpty { entry.stringList("key") },
            secondaryKeys = entry.stringList("secondary_keys").ifEmpty { entry.stringList("secondaryKeys") },
            content = entry.stringOrNull("content").orEmpty(),
            enabled = (entry.booleanOrNull("enabled") ?: true) && !(entry.booleanOrNull("disable") ?: false),
            insertionOrder = entry.intOrNull("insertion_order")
                ?: entry.intOrNull("insertionOrder")
                ?: entry.intOrNull("order")
                ?: 100,
            caseSensitive = entry.booleanOrNull("case_sensitive") ?: entry.booleanOrNull("caseSensitive")
                ?: extensionBoolean("case_sensitive", "caseSensitive"),
            matchWholeWords = entry.booleanOrNull("match_whole_words")
                ?: entry.booleanOrNull("matchWholeWords")
                ?: extensionBoolean("match_whole_words", "matchWholeWords"),
            useRegex = entry.booleanOrNull("use_regex") ?: entry.booleanOrNull("useRegex")
                ?: extensionBoolean("use_regex", "useRegex") ?: false,
            constant = entry.booleanOrNull("constant") ?: false,
            name = entry.stringOrNull("name"),
            priority = entry.intOrNull("priority") ?: extensionInt("priority"),
            order = entry.intOrNull("order") ?: extensionInt("order"),
            comment = entry.stringOrNull("comment"),
            selective = entry.booleanOrNull("selective") ?: false,
            selectiveLogic = entry.intOrNull("selectiveLogic") ?: entry.intOrNull("selective_logic")
                ?: extensionInt("selectiveLogic", "selective_logic"),
            position = position,
            positionIndex = positionIndex ?: extensionInt("position"),
            depth = entry.intOrNull("depth") ?: extensionInt("depth"),
            role = roleName(),
            scanDepth = entry.intOrNull("scanDepth") ?: entry.intOrNull("scan_depth")
                ?: extensionInt("scanDepth", "scan_depth"),
            group = entry.stringOrNull("group") ?: extensionString("group"),
            groupOverride = entry.booleanOrNull("groupOverride") ?: entry.booleanOrNull("group_override")
                ?: extensionBoolean("groupOverride", "group_override"),
            groupWeight = entry.intOrNull("groupWeight") ?: entry.intOrNull("group_weight")
                ?: extensionInt("groupWeight", "group_weight"),
            probability = entry.intOrNull("probability") ?: extensionInt("probability"),
            useProbability = entry.booleanOrNull("useProbability") ?: entry.booleanOrNull("use_probability")
                ?: extensionBoolean("useProbability", "use_probability"),
            sticky = entry.intOrNull("sticky") ?: extensionInt("sticky"),
            cooldown = entry.intOrNull("cooldown") ?: extensionInt("cooldown"),
            delay = entry.intOrNull("delay") ?: extensionInt("delay"),
            delayUntilRecursion = intOrBoolean("delayUntilRecursion", "delay_until_recursion"),
            preventRecursion = entry.booleanOrNull("preventRecursion")
                ?: entry.booleanOrNull("prevent_recursion")
                ?: extensionBoolean("preventRecursion", "prevent_recursion"),
            excludeRecursion = entry.booleanOrNull("excludeRecursion")
                ?: entry.booleanOrNull("exclude_recursion")
                ?: extensionBoolean("excludeRecursion", "exclude_recursion"),
            keysContainedIn = entry.stringOrNull("keys_contained_in")
                ?: entry.stringOrNull("keysContainedIn")
                ?: extensionString("keys_contained_in", "keysContainedIn"),
            outletName = entry.stringOrNull("outletName") ?: entry.stringOrNull("outlet_name")
                ?: extensionString("outletName", "outlet_name"),
            triggers = entry.stringList("triggers").ifEmpty { extensionList("triggers") },
            useGroupScoring = entry.booleanOrNull("useGroupScoring")
                ?: entry.booleanOrNull("use_group_scoring")
                ?: extensionBoolean("useGroupScoring", "use_group_scoring"),
            automationId = entry.stringOrNull("automationId") ?: extensionString("automation_id", "automationId"),
            vectorized = entry.booleanOrNull("vectorized") ?: extensionBoolean("vectorized"),
            matchPersonaDescription = entry.booleanOrNull("matchPersonaDescription")
                ?: extensionBoolean("match_persona_description", "matchPersonaDescription"),
            matchCharacterDescription = entry.booleanOrNull("matchCharacterDescription")
                ?: extensionBoolean("match_character_description", "matchCharacterDescription"),
            matchCharacterPersonality = entry.booleanOrNull("matchCharacterPersonality")
                ?: extensionBoolean("match_character_personality", "matchCharacterPersonality"),
            matchCharacterDepthPrompt = entry.booleanOrNull("matchCharacterDepthPrompt")
                ?: extensionBoolean("match_character_depth_prompt", "matchCharacterDepthPrompt"),
            matchScenario = entry.booleanOrNull("matchScenario")
                ?: extensionBoolean("match_scenario", "matchScenario"),
            matchCreatorNotes = entry.booleanOrNull("matchCreatorNotes")
                ?: extensionBoolean("match_creator_notes", "matchCreatorNotes"),
            ignoreBudget = entry.booleanOrNull("ignoreBudget")
                ?: extensionBoolean("ignore_budget", "ignoreBudget"),
            characterFilterNames = filterNames,
            characterFilterTags = filterTags,
            characterFilterExclude = filterExclude,
            addMemo = entry.booleanOrNull("addMemo", "add_memo")
                ?: extensionBoolean("addMemo", "add_memo"),
            displayIndex = entry.intOrNull("displayIndex", "display_index")
                ?: extensionInt("displayIndex", "display_index"),
            extensionsJson = entry.objectOrNull("extensions")?.toString() ?: "{}",
            rawJson = entry.toString()
        )
    }

    private fun writeData(data: JsonObject, value: TavernCardData, targetSpec: String) {
        data.addProperty("name", value.name)
        data.addProperty("description", value.description)
        value.shortDescription?.let { data.addProperty("short_description", it) }
        data.addProperty("personality", value.personality)
        data.addProperty("scenario", value.scenario)
        data.addProperty("first_mes", value.firstMessage)
        data.addProperty("mes_example", value.mesExample)
        data.addProperty("creator_notes", value.creatorNotes)
        data.addProperty("system_prompt", value.systemPrompt)
        data.addProperty("post_history_instructions", value.postHistoryInstructions)
        data.add("alternate_greetings", stringArray(value.alternateGreetings))
        data.addProperty("creator", value.creator)
        data.addProperty("character_version", value.characterVersion)
        data.add("tags", stringArray(value.tags))
        data.add("extensions", parseObjectOrNull(value.extensionsJson) ?: JsonObject())
        value.characterBook?.let { data.add("character_book", bookToJson(it)) }

        if (targetSpec.contains("v3", ignoreCase = true)) {
            value.nickname?.let { data.addProperty("nickname", it) }
            data.add("group_only_greetings", stringArray(value.groupOnlyGreetings))
            data.add("source", stringArray(value.source))
            value.creationDate?.let { data.addProperty("creation_date", it) }
            value.modificationDate?.let { data.addProperty("modification_date", it) }
            data.add("creator_notes_multilingual", parseObjectOrNull(value.creatorNotesMultilingualJson) ?: JsonObject())
            data.add("assets", parseArrayOrNull(value.assetsJson) ?: JsonArray())
        }
    }

    private fun bookToJson(book: CharacterBookDocument): JsonObject {
        val result = book.rawJson?.let(::parseObjectOrNull) ?: JsonObject()
        book.name?.let { result.addProperty("name", it) }
        book.description?.let { result.addProperty("description", it) }
        book.scanDepth?.let { result.addProperty("scan_depth", it) }
        book.tokenBudget?.let { result.addProperty("token_budget", it) }
        book.recursiveScanning?.let { result.addProperty("recursive_scanning", it) }
        result.add("extensions", parseObjectOrNull(book.extensionsJson) ?: JsonObject())
        result.add("entries", book.entries.map { entryToJson(it) }.let { JsonArray().also { array -> it.forEach(array::add) } })
        return result
    }

    private fun entryToJson(entry: CharacterBookEntryDocument): JsonObject {
        val result = entry.rawJson?.let(::parseObjectOrNull) ?: JsonObject()
        val extensions = parseObjectOrNull(entry.extensionsJson) ?: JsonObject()
        entry.id?.let { result.addProperty("id", it) }
        result.add("keys", stringArray(entry.keys))
        result.add("secondary_keys", stringArray(entry.secondaryKeys))
        result.addProperty("content", entry.content)
        result.addProperty("enabled", entry.enabled)
        result.addProperty("insertion_order", entry.insertionOrder)
        entry.caseSensitive?.let { result.addProperty("case_sensitive", it) }
        entry.matchWholeWords?.let { result.addProperty("match_whole_words", it) }
        result.addProperty("use_regex", entry.useRegex)
        result.addProperty("constant", entry.constant)
        entry.name?.let { result.addProperty("name", it) }
        entry.priority?.let { result.addProperty("priority", it) }
        entry.order?.let { result.addProperty("order", it) }
        entry.comment?.let { result.addProperty("comment", it) }
        result.addProperty("selective", entry.selective)
        entry.selectiveLogic?.let { result.addProperty("selectiveLogic", it) }
        entry.position?.let { result.addProperty("position", it) }
        entry.depth?.let { result.addProperty("depth", it) }
        entry.role?.let { result.addProperty("role", it) }
        entry.scanDepth?.let { result.addProperty("scanDepth", it) }
        entry.group?.let { result.addProperty("group", it) }
        entry.groupOverride?.let { result.addProperty("groupOverride", it) }
        entry.groupWeight?.let { result.addProperty("groupWeight", it) }
        entry.probability?.let { result.addProperty("probability", it) }
        entry.useProbability?.let { result.addProperty("useProbability", it) }
        entry.sticky?.let { result.addProperty("sticky", it) }
        entry.cooldown?.let { result.addProperty("cooldown", it) }
        entry.delay?.let { result.addProperty("delay", it) }
        entry.delayUntilRecursion?.let { result.addProperty("delayUntilRecursion", it) }
        entry.preventRecursion?.let { result.addProperty("preventRecursion", it) }
        entry.excludeRecursion?.let { result.addProperty("excludeRecursion", it) }
        entry.keysContainedIn?.let { result.addProperty("keysContainedIn", it) }
        entry.outletName?.let { result.addProperty("outletName", it) }
        result.add("triggers", stringArray(entry.triggers))
        entry.useGroupScoring?.let { result.addProperty("useGroupScoring", it) }
        entry.automationId?.let { result.addProperty("automationId", it) }
        entry.vectorized?.let { result.addProperty("vectorized", it) }
        entry.matchPersonaDescription?.let { result.addProperty("matchPersonaDescription", it) }
        entry.matchCharacterDescription?.let { result.addProperty("matchCharacterDescription", it) }
        entry.matchCharacterPersonality?.let { result.addProperty("matchCharacterPersonality", it) }
        entry.matchCharacterDepthPrompt?.let { result.addProperty("matchCharacterDepthPrompt", it) }
        entry.matchScenario?.let { result.addProperty("matchScenario", it) }
        entry.matchCreatorNotes?.let { result.addProperty("matchCreatorNotes", it) }
        entry.ignoreBudget?.let { result.addProperty("ignoreBudget", it) }
        if (entry.characterFilterNames.isNotEmpty() || entry.characterFilterTags.isNotEmpty() || entry.characterFilterExclude) {
            result.add("characterFilter", JsonObject().apply {
                add("names", stringArray(entry.characterFilterNames))
                add("tags", stringArray(entry.characterFilterTags))
                addProperty("isExclude", entry.characterFilterExclude)
            })
        }
        entry.addMemo?.let { result.addProperty("addMemo", it) }
        entry.displayIndex?.let { result.addProperty("displayIndex", it) }

        // SillyTavern reads its CharacterBook runtime extensions from
        // entry.extensions. Keep the spec fields above, but mirror the
        // implementation-specific fields here so an exported card behaves the
        // same in ST/Tavo instead of merely looking correct in raw JSON.
        entry.caseSensitive?.let { extensions.addProperty("case_sensitive", it) }
        entry.matchWholeWords?.let { extensions.addProperty("match_whole_words", it) }
        extensions.addProperty("use_regex", entry.useRegex)
        entry.selectiveLogic?.let { extensions.addProperty("selectiveLogic", it) }
        entry.positionIndex?.let { extensions.addProperty("position", it) }
        entry.depth?.let { extensions.addProperty("depth", it) }
        entry.role?.let { extensions.addProperty("role", it) }
        entry.scanDepth?.let { extensions.addProperty("scan_depth", it) }
        entry.group?.let { extensions.addProperty("group", it) }
        entry.groupOverride?.let { extensions.addProperty("group_override", it) }
        entry.groupWeight?.let { extensions.addProperty("group_weight", it) }
        entry.probability?.let { extensions.addProperty("probability", it) }
        entry.useProbability?.let { extensions.addProperty("useProbability", it) }
        entry.sticky?.let { extensions.addProperty("sticky", it) }
        entry.cooldown?.let { extensions.addProperty("cooldown", it) }
        entry.delay?.let { extensions.addProperty("delay", it) }
        entry.delayUntilRecursion?.let { extensions.addProperty("delay_until_recursion", it) }
        entry.preventRecursion?.let { extensions.addProperty("prevent_recursion", it) }
        entry.excludeRecursion?.let { extensions.addProperty("exclude_recursion", it) }
        entry.keysContainedIn?.let { extensions.addProperty("keysContainedIn", it) }
        entry.outletName?.let { extensions.addProperty("outlet_name", it) }
        extensions.add("triggers", stringArray(entry.triggers))
        entry.useGroupScoring?.let { extensions.addProperty("use_group_scoring", it) }
        entry.automationId?.let { extensions.addProperty("automation_id", it) }
        entry.vectorized?.let { extensions.addProperty("vectorized", it) }
        entry.matchPersonaDescription?.let { extensions.addProperty("match_persona_description", it) }
        entry.matchCharacterDescription?.let { extensions.addProperty("match_character_description", it) }
        entry.matchCharacterPersonality?.let { extensions.addProperty("match_character_personality", it) }
        entry.matchCharacterDepthPrompt?.let { extensions.addProperty("match_character_depth_prompt", it) }
        entry.matchScenario?.let { extensions.addProperty("match_scenario", it) }
        entry.matchCreatorNotes?.let { extensions.addProperty("match_creator_notes", it) }
        entry.ignoreBudget?.let { extensions.addProperty("ignore_budget", it) }
        if (entry.characterFilterNames.isNotEmpty()) {
            extensions.add("character_filter_names", stringArray(entry.characterFilterNames))
        }
        if (entry.characterFilterTags.isNotEmpty()) {
            extensions.add("character_filter_tags", stringArray(entry.characterFilterTags))
        }
        extensions.addProperty("character_filter_exclude", entry.characterFilterExclude)
        entry.addMemo?.let { extensions.addProperty("add_memo", it) }
        entry.displayIndex?.let { extensions.addProperty("display_index", it) }
        result.add("extensions", extensions)
        return result
    }

    private fun decodeCandidate(value: String): TavernCardDocument? {
        val trimmed = value.trim()
        parseJson(trimmed)?.let { return it }
        val decoded = try {
            Base64.getMimeDecoder().decode(trimmed)
        } catch (_: Exception) {
            null
        } ?: return null
        parseJson(String(decoded, StandardCharsets.UTF_8))?.let { return it }
        inflate(decoded)?.let { inflated ->
            parseJson(String(inflated, StandardCharsets.UTF_8))?.let { return it }
        }
        return null
    }

    private fun parseTextChunk(type: String, data: ByteArray): Pair<String, String>? {
        val keywordEnd = data.indexOf(0)
        if (keywordEnd <= 0) return null
        val keyword = String(data, 0, keywordEnd, StandardCharsets.US_ASCII)
        if (keyword != "chara" && keyword != "ccv3") return null

        return when (type) {
            "tEXt" -> keyword to String(data, keywordEnd + 1, data.size - keywordEnd - 1, StandardCharsets.UTF_8)
            "zTXt" -> {
                val compressedStart = keywordEnd + 2
                if (keywordEnd + 1 >= data.size || data[keywordEnd + 1].toInt() != 0 || compressedStart > data.size) {
                    null
                } else {
                    inflate(data.copyOfRange(compressedStart, data.size))?.let {
                        keyword to String(it, StandardCharsets.UTF_8)
                    }
                }
            }
            "iTXt" -> {
                var position = keywordEnd + 1
                if (position + 2 > data.size) return null
                val compressionFlag = data[position].toInt() and 0xFF
                val compressionMethod = data[position + 1].toInt() and 0xFF
                position += 2
                position = data.indexOf(0, position).takeIf { it >= 0 }?.plus(1) ?: return null
                position = data.indexOf(0, position).takeIf { it >= 0 }?.plus(1) ?: return null
                if (position > data.size) return null
                val text = data.copyOfRange(position, data.size)
                val decoded = if (compressionFlag == 1 && compressionMethod == 0) inflate(text) else text
                decoded?.let { keyword to String(it, StandardCharsets.UTF_8) }
            }
            else -> null
        }
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        inflater.setInput(data)
        val output = ByteArrayOutputStream(minOf(data.size * 2, 64 * 1024))
        val buffer = ByteArray(8192)
        return try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    if (output.size() + count > MAX_INFLATED_BYTES) return null
                    output.write(buffer, 0, count)
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    return null
                }
            }
            output.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun isSafeCharxPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains(':')) return false
        return path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun referencedCharxAssetNames(document: TavernCardDocument): Set<String> {
        val assets = parseArrayOrNull(document.data.assetsJson) ?: return emptySet()
        return assets.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
        }.flatMap { asset ->
            val uri = asset.stringOrNull("uri").orEmpty()
            val name = asset.stringOrNull("name").orEmpty()
            listOf(uri, name).flatMap { value ->
                if (value.isBlank()) emptyList() else {
                    val normalized = value.substringAfter("://", value)
                        .substringBefore('?')
                        .trimStart('/')
                    listOf(normalized, normalized.substringAfterLast('/'))
                }
            }
        }.filter { it.isNotBlank() }.toSet()
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun drainBounded(input: InputStream, maxBytes: Int): Int? {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            if (count == 0) continue
            total += count
            if (total > maxBytes) return null
        }
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) return false
            remaining--
        }
        return true
    }

    private fun readUInt32(bytes: ByteArray): Long =
        ((bytes[0].toLong() and 0xFF) shl 24) or
            ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or
            (bytes[3].toLong() and 0xFF)

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arrayOrNull(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean && !it.asJsonPrimitive.isNumber }
            ?.asString

    private fun JsonObject.stringList(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (value.isJsonArray) {
            return value.asJsonArray.mapNotNull { element ->
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
        }
        return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.let { listOf(it) }
            ?: emptyList()
    }

    private fun JsonObject.intOrNull(vararg names: String): Int? = names.asSequence()
        .mapNotNull { get(it) }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.booleanOrNull(vararg names: String): Boolean? = names.asSequence()
        .mapNotNull { get(it) }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean

    private fun ByteArray.indexOf(value: Int, start: Int = 0): Int {
        for (index in start until size) {
            if ((get(index).toInt() and 0xFF) == value) return index
        }
        return -1
    }

    private fun parseObjectOrNull(json: String): JsonObject? = try {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    } catch (_: Exception) {
        null
    }

    private fun parseArrayOrNull(json: String): JsonArray? = try {
        JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) {
        null
    }

    private fun stringArray(values: List<String>): JsonArray = JsonArray().also { array ->
        values.forEach(array::add)
    }

}
