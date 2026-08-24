package com.loyea.plugins.tavern.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** A loss-aware header from a SillyTavern/Tavo JSONL chat file. */
data class TavernChatHeader(
    val userName: String? = null,
    val characterName: String? = null,
    val createDate: String? = null,
    val chatMetadataJson: String = "{}",
    val rawJson: String? = null
) {
    fun metadata(): JsonObject = parseObject(chatMetadataJson) ?: JsonObject()

    fun withMetadataValue(key: String, value: String?): TavernChatHeader {
        require(key.isNotBlank()) { "Chat metadata key must not be blank" }
        val metadata = metadata()
        if (value == null) metadata.remove(key) else metadata.addProperty(key, value)
        return copy(chatMetadataJson = metadata.toString())
    }

    companion object {
        private fun parseObject(json: String): JsonObject? = runCatching {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
    }
}

/** A normalized ST ChatMessage that still retains the original JSON for unknown fields. */
data class TavernChatMessageRecord(
    val name: String = "",
    val message: String = "",
    val isUser: Boolean = false,
    val isSystem: Boolean = false,
    val sendDate: String? = null,
    val generationStarted: String? = null,
    val generationFinished: String? = null,
    val title: String? = null,
    val forceAvatar: String? = null,
    val originalAvatar: String? = null,
    val swipes: List<String> = emptyList(),
    val swipeInfoJson: String? = null,
    val swipeId: Int = 0,
    val extraJson: String? = null,
    val rawJson: String? = null
) {
    val selectedMessage: String
        get() = swipes.getOrNull(swipeId) ?: message

    fun withSelectedSwipe(index: Int): TavernChatMessageRecord {
        require(index in swipes.indices) { "Swipe index $index is outside ${swipes.size} swipes" }
        return copy(message = swipes[index], swipeId = index)
    }

    fun withExtraValue(key: String, value: JsonElement?): TavernChatMessageRecord {
        require(key.isNotBlank()) { "Chat extra key must not be blank" }
        val extra = parseObject(extraJson) ?: JsonObject()
        if (value == null) extra.remove(key) else extra.add(key, value)
        return copy(extraJson = extra.toString())
    }

    fun extra(): JsonObject = parseObject(extraJson) ?: JsonObject()

    companion object {
        private fun parseObject(json: String?): JsonObject? = json?.let {
            runCatching { JsonParser.parseString(it).takeIf { value -> value.isJsonObject }?.asJsonObject }
                .getOrNull()
        }
    }
}

data class TavernChatFile(
    val header: TavernChatHeader = TavernChatHeader(),
    val messages: List<TavernChatMessageRecord> = emptyList(),
    /** Local file name used only for branch/checkpoint navigation; never required in JSONL. */
    val chatName: String? = null
)

data class TavernChatParseIssue(
    val lineNumber: Int,
    val reason: String,
    val rawLine: String
)

data class TavernChatParseResult(
    val chat: TavernChatFile,
    val issues: List<TavernChatParseIssue> = emptyList()
)

/**
 * Parser/exporter for the current SillyTavern ChatHeader + ChatMessage JSONL shape.
 *
 * The parser accepts common legacy aliases used by Tavo and other ST-compatible clients,
 * while [rawJson] keeps fields that this core does not interpret. It intentionally reports
 * malformed lines instead of dropping them silently, which is important for recoverability.
 */
object TavernChatFileCodec {
    private val messageKeys = setOf(
        "name", "mes", "message", "content", "is_user", "isUser", "is_system", "isSystem",
        "send_date", "sendDate", "timestamp", "swipes", "swipe_id", "swipeId", "extra", "role"
    )

    fun parse(jsonl: String): TavernChatParseResult {
        var header: TavernChatHeader? = null
        val messages = mutableListOf<TavernChatMessageRecord>()
        val issues = mutableListOf<TavernChatParseIssue>()

        jsonl.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed

            val obj = runCatching { JsonParser.parseString(line) }
                .getOrElse {
                    issues += TavernChatParseIssue(lineNumber, "invalid JSON", rawLine)
                    return@forEachIndexed
                }
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: run {
                    issues += TavernChatParseIssue(lineNumber, "record is not a JSON object", rawLine)
                    return@forEachIndexed
                }

            val isHeader = isHeaderRecord(obj)
            if (isHeader && header == null) {
                header = parseHeader(obj)
                return@forEachIndexed
            }
            if (!isMessageRecord(obj)) {
                issues += TavernChatParseIssue(lineNumber, "unsupported JSONL record", rawLine)
                return@forEachIndexed
            }
            messages += parseMessage(obj)
        }

        return TavernChatParseResult(
            chat = TavernChatFile(header = header ?: TavernChatHeader(), messages = messages),
            issues = issues
        )
    }

    fun toJsonl(chat: TavernChatFile): String = buildList {
        add(headerToJson(chat.header).toString())
        chat.messages.forEach { add(messageToJson(it).toString()) }
    }.joinToString("\n")

    private fun isHeaderRecord(obj: JsonObject): Boolean {
        if (obj.has("chat_metadata")) return true
        val hasLegacyNames = obj.has("user_name") || obj.has("character_name") || obj.has("create_date")
        return hasLegacyNames && obj.entrySet().none { it.key in messageKeys }
    }

    private fun isMessageRecord(obj: JsonObject): Boolean =
        obj.entrySet().any { it.key in messageKeys } || obj.has("role")

    private fun parseHeader(obj: JsonObject): TavernChatHeader = TavernChatHeader(
        userName = obj.string("user_name", "userName"),
        characterName = obj.string("character_name", "characterName"),
        createDate = obj.wireString("create_date", "createDate"),
        chatMetadataJson = obj["chat_metadata"]
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.toString()
            ?: "{}",
        rawJson = obj.toString()
    )

    private fun parseMessage(obj: JsonObject): TavernChatMessageRecord {
        val role = obj.string("role")?.lowercase(Locale.ROOT)
        val isSystem = obj.boolean("is_system", "isSystem", "system") ?: (role == "system")
        val isUser = obj.boolean("is_user", "isUser", "user") ?: (role == "user")
        val swipes = obj.stringList("swipes")
        return TavernChatMessageRecord(
            name = obj.string("name", "characterName", "character_name", "author", "sender") ?: "",
            message = obj.stringPreservingWhitespace("mes", "message", "content") ?: "",
            isUser = isUser,
            isSystem = isSystem,
            sendDate = obj.wireString("send_date", "sendDate", "timestamp"),
            generationStarted = obj.wireString("gen_started", "genStarted"),
            generationFinished = obj.wireString("gen_finished", "genFinished"),
            title = obj.stringPreservingWhitespace("title"),
            forceAvatar = obj.string("force_avatar", "forceAvatar"),
            originalAvatar = obj.string("original_avatar", "originalAvatar"),
            swipes = swipes,
            swipeInfoJson = obj["swipe_info"]?.toString() ?: obj["swipeInfo"]?.toString(),
            swipeId = obj.int("swipe_id", "swipeId", "activeSwipe", "currentSwipe")?.coerceAtLeast(0) ?: 0,
            extraJson = obj["extra"]?.toString(),
            rawJson = obj.toString()
        )
    }

    private fun headerToJson(header: TavernChatHeader): JsonObject {
        val root = parseObject(header.rawJson)?.deepCopy() ?: JsonObject()
        if (!root.has("user_name")) root.addProperty("user_name", header.userName ?: "unused")
        if (!root.has("character_name")) root.addProperty("character_name", header.characterName ?: "unused")
        header.userName?.let { root.addProperty("user_name", it) }
        header.characterName?.let { root.addProperty("character_name", it) }
        header.createDate?.let { root.addProperty("create_date", it) }
        root.add("chat_metadata", header.metadata())
        return root
    }

    private fun messageToJson(message: TavernChatMessageRecord): JsonObject {
        val root = parseObject(message.rawJson)?.deepCopy() ?: JsonObject()
        if (message.name.isNotEmpty()) root.addProperty("name", message.name)
        root.addProperty("mes", message.message)
        root.addProperty("is_user", message.isUser)
        root.addProperty("is_system", message.isSystem)
        message.sendDate?.let { root.addProperty("send_date", it) }
        message.generationStarted?.let { root.addProperty("gen_started", it) }
        message.generationFinished?.let { root.addProperty("gen_finished", it) }
        message.title?.let { root.addProperty("title", it) }
        message.forceAvatar?.let { root.addProperty("force_avatar", it) }
        message.originalAvatar?.let { root.addProperty("original_avatar", it) }
        if (message.swipes.isNotEmpty()) {
            root.add("swipes", JsonArray().also { array -> message.swipes.forEach(array::add) })
            root.addProperty("swipe_id", message.swipeId.coerceIn(0, message.swipes.lastIndex))
        }
        message.swipeInfoJson?.let { parseElement(it)?.let { value -> root.add("swipe_info", value) } }
        message.extraJson?.let { parseElement(it)?.let { value -> root.add("extra", value) } }
        return root
    }

    private fun parseObject(json: String?): JsonObject? = json?.let {
        runCatching { JsonParser.parseString(it).takeIf { value -> value.isJsonObject }?.asJsonObject }
            .getOrNull()
    }

    private fun parseElement(json: String): JsonElement? = runCatching { JsonParser.parseString(json) }.getOrNull()

    private fun JsonObject.string(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringPreservingWhitespace(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.wireString(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }
        ?.asString

    private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && (it.asJsonPrimitive.isBoolean || it.asJsonPrimitive.isString) }
        ?.let { value ->
            value.asJsonPrimitive.takeIf { it.isBoolean }?.asBoolean
                ?: value.asString.toBooleanStrictOrNull()
        }

    private fun JsonObject.int(vararg keys: String): Int? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString) }
        ?.let { value ->
            value.asJsonPrimitive.takeIf { it.isNumber }?.asInt
                ?: value.asString.toIntOrNull()
        }

    private fun JsonObject.stringList(vararg keys: String): List<String> {
        val value = keys.asSequence().mapNotNull { this[it] }.firstOrNull() ?: return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull {
                it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString
            }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
            else -> emptyList()
        }
    }
}

enum class TavernChatForkMode {
    BRANCH,
    CHECKPOINT
}

data class TavernChatForkResult(
    val mode: TavernChatForkMode,
    val parent: TavernChatFile,
    val child: TavernChatFile,
    val messageIndex: Int,
    val switchedToChild: Boolean
)

/** Pure branch/checkpoint planner mirroring ST's clone-and-link semantics. */
object TavernChatForkPlanner {
    fun createBranch(
        source: TavernChatFile,
        childChatName: String,
        messageIndex: Int,
        parentChatName: String = source.chatName.orEmpty(),
        selectedSwipeId: Int? = null
    ): TavernChatForkResult = fork(
        source = source,
        mode = TavernChatForkMode.BRANCH,
        childChatName = childChatName,
        messageIndex = messageIndex,
        parentChatName = parentChatName,
        selectedSwipeId = selectedSwipeId
    )

    fun createCheckpoint(
        source: TavernChatFile,
        childChatName: String,
        messageIndex: Int,
        parentChatName: String = source.chatName.orEmpty(),
        selectedSwipeId: Int? = null
    ): TavernChatForkResult = fork(
        source = source,
        mode = TavernChatForkMode.CHECKPOINT,
        childChatName = childChatName,
        messageIndex = messageIndex,
        parentChatName = parentChatName,
        selectedSwipeId = selectedSwipeId
    )

    private fun fork(
        source: TavernChatFile,
        mode: TavernChatForkMode,
        childChatName: String,
        messageIndex: Int,
        parentChatName: String,
        selectedSwipeId: Int?
    ): TavernChatForkResult {
        require(childChatName.isNotBlank()) { "Child chat name must not be blank" }
        require(parentChatName.isNotBlank()) { "Parent chat name must not be blank" }
        require(messageIndex in source.messages.indices) {
            "Message index $messageIndex is outside ${source.messages.size} messages"
        }

        val selected = selectedSwipeId?.let { source.messages[messageIndex].withSelectedSwipe(it) }
            ?: source.messages[messageIndex]
        val childMessages = source.messages.take(messageIndex + 1).toMutableList().apply {
            this[messageIndex] = selected
        }
        val childHeader = source.header.withMetadataValue("main_chat", parentChatName)
        val child = source.copy(
            header = childHeader,
            messages = childMessages,
            chatName = childChatName
        )

        val parentMessage = when (mode) {
            TavernChatForkMode.BRANCH -> {
                val branches = parentMessageBranches(source.messages[messageIndex])
                source.messages[messageIndex].withExtraValue(
                    "branches",
                    JsonArray().also { array -> branches.forEach(array::add); if (childChatName !in branches) array.add(childChatName) }
                )
            }
            TavernChatForkMode.CHECKPOINT -> source.messages[messageIndex]
                .withExtraValue("bookmark_link", com.google.gson.JsonPrimitive(childChatName))
        }
        val parentMessages = source.messages.toMutableList().apply { this[messageIndex] = parentMessage }
        return TavernChatForkResult(
            mode = mode,
            parent = source.copy(messages = parentMessages),
            child = child,
            messageIndex = messageIndex,
            switchedToChild = mode == TavernChatForkMode.BRANCH
        )
    }

    private fun parentMessageBranches(message: TavernChatMessageRecord): List<String> {
        val value = message.extra()["branches"] ?: return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapNotNull {
                it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString
            }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
            else -> emptyList()
        }
    }
}
