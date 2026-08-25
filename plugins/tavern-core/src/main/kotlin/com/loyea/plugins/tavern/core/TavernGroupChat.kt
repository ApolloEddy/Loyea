package com.loyea.plugins.tavern.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** Tavo/SillyTavern group-chat reply strategies. */
enum class TavernGroupReplyMode {
    NATURAL_CHAT,
    ALL_MEMBERS,
    DESIGNATED_SPEAKER,
    CONTEXTUAL_SPEAKER;

    companion object {
        fun parse(value: String?): TavernGroupReplyMode = when (
            value.orEmpty().trim().lowercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        ) {
            "all", "all_members", "all_members_reply", "everyone" -> ALL_MEMBERS
            "designated", "designated_speaker", "manual", "manual_speaker" -> DESIGNATED_SPEAKER
            "contextual", "contextual_speaker", "context", "llm" -> CONTEXTUAL_SPEAKER
            else -> NATURAL_CHAT
        }
    }

    fun wireName(): String = when (this) {
        NATURAL_CHAT -> "natural_chat"
        ALL_MEMBERS -> "all_members"
        DESIGNATED_SPEAKER -> "designated_speaker"
        CONTEXTUAL_SPEAKER -> "contextual_speaker"
    }
}

data class TavernGroupMember(
    val id: String,
    val name: String,
    val personaId: String? = null,
    val enabled: Boolean = true,
    val muted: Boolean = false,
    val weight: Int = 100
) {
    init {
        require(id.isNotBlank()) { "Tavern group member id must not be blank" }
        require(name.isNotBlank()) { "Tavern group member name must not be blank" }
        require(weight >= 0) { "Tavern group member weight must not be negative" }
    }
}

data class TavernGroupChat(
    val id: String,
    val name: String,
    val members: List<TavernGroupMember>,
    val replyMode: TavernGroupReplyMode = TavernGroupReplyMode.NATURAL_CHAT,
    val designatedSpeakerId: String? = null,
    val contextualSpeakerPrompt: String = DEFAULT_CONTEXTUAL_SPEAKER_PROMPT,
    val maxReplies: Int = 1
) {
    init {
        require(id.isNotBlank()) { "Tavern group id must not be blank" }
        require(name.isNotBlank()) { "Tavern group name must not be blank" }
        require(members.map { it.id.lowercase(Locale.ROOT) }.distinct().size == members.size) {
            "Tavern group member ids must be unique"
        }
        require(maxReplies > 0) { "Tavern group maxReplies must be positive" }
    }

    val enabledMembers: List<TavernGroupMember>
        get() = members.filter { it.enabled }

    val unmutedMembers: List<TavernGroupMember>
        get() = enabledMembers.filterNot { it.muted }

    fun groupMacro(includeMuted: Boolean = false): String = (if (includeMuted) {
        enabledMembers
    } else {
        unmutedMembers
    }).joinToString(", ") { it.name }

    fun findMember(value: String?): TavernGroupMember? {
        val normalized = value.orEmpty().trim().removePrefix("@").lowercase(Locale.ROOT)
        if (normalized.isBlank()) return null
        return members.firstOrNull {
            it.enabled && (
                it.id.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
                )
        }
    }

    companion object {
        const val DEFAULT_CONTEXTUAL_SPEAKER_PROMPT =
            "Choose the next speaker from {{group}}. Return only character names, separated by commas when multiple characters should speak."
    }
}

data class TavernGroupTurnRequest(
    val group: TavernGroupChat,
    val turnId: String,
    val input: String = "",
    val explicitSpeaker: String? = null,
    val randomSeed: Long = stableSeed(turnId, input)
) {
    init {
        require(turnId.isNotBlank()) { "Tavern group turn id must not be blank" }
    }

    companion object {
        private fun stableSeed(turnId: String, input: String): Long =
            ("$turnId\u0000$input").hashCode().toLong()
    }
}

data class TavernGroupTurnPlan(
    val replyMode: TavernGroupReplyMode,
    val speakers: List<TavernGroupMember>,
    val groupMacro: String,
    val selectionPrompt: String? = null
) {
    val isAwaitingSpeakerSelection: Boolean
        get() = replyMode == TavernGroupReplyMode.CONTEXTUAL_SPEAKER && speakers.isEmpty()
}

/**
 * Deterministic group speaker planner.
 *
 * The planner never calls an API and never mutates group state. Contextual mode returns a
 * frozen selection prompt for the host to send through its configured speaker-selection API;
 * [resolveSelection] turns that model result back into known, unmuted members only.
 */
object TavernGroupPlanner {
    private val mentionPattern by lazy { Regex("(?<![\\p{L}\\p{N}_-])@([\\p{L}\\p{N}_-]+)") }

    fun plan(request: TavernGroupTurnRequest): TavernGroupTurnPlan {
        val group = request.group
        val active = group.unmutedMembers
        val mentioned = mentionedMembers(group, request.input)
        val explicit = group.findMember(request.explicitSpeaker)
        val speakers = when (group.replyMode) {
            TavernGroupReplyMode.NATURAL_CHAT -> mentioned
                .ifEmpty { deterministicPick(active, request.randomSeed)?.let(::listOf).orEmpty() }
                .take(group.maxReplies)
            TavernGroupReplyMode.ALL_MEMBERS -> active
            TavernGroupReplyMode.DESIGNATED_SPEAKER -> listOfNotNull(
                explicit
                    ?: group.findMember(group.designatedSpeakerId)
                    ?: mentioned.firstOrNull()
            )
            TavernGroupReplyMode.CONTEXTUAL_SPEAKER -> emptyList()
        }
        val selectionPrompt = if (group.replyMode == TavernGroupReplyMode.CONTEXTUAL_SPEAKER) {
            TavernMacroEngine.expand(
                group.contextualSpeakerPrompt.ifBlank { TavernGroupChat.DEFAULT_CONTEXTUAL_SPEAKER_PROMPT },
                TavernMacroContext(
                    group = group.groupMacro(),
                    groupNotMuted = group.groupMacro()
                )
            )
        } else {
            null
        }
        return TavernGroupTurnPlan(
            replyMode = group.replyMode,
            speakers = speakers,
            groupMacro = group.groupMacro(),
            selectionPrompt = selectionPrompt
        )
    }

    fun resolveSelection(group: TavernGroupChat, modelText: String): List<TavernGroupMember> {
        val candidates = modelText
            .replace('\n', ',')
            .split(',')
            .asSequence()
            .map { it.trim().trim('.', ':', ';', '-', '*').removePrefix("@") }
            .filter { it.isNotBlank() }
            .mapNotNull(group::findMember)
            .distinctBy { it.id.lowercase(Locale.ROOT) }
            .toList()
        return candidates.take(group.maxReplies)
    }

    private fun mentionedMembers(group: TavernGroupChat, input: String): List<TavernGroupMember> {
        if (input.isBlank()) return emptyList()
        val mentionedTokens = mentionPattern.findAll(input)
            .map { it.groupValues[1] }
            .toList()
        return group.unmutedMembers.filter { member ->
            mentionedTokens.any { token ->
                token.equals(member.id, ignoreCase = true) || token.equals(member.name, ignoreCase = true)
            } || input.contains("@${member.name}", ignoreCase = true)
        }
    }

    private fun deterministicPick(
        members: List<TavernGroupMember>,
        seed: Long
    ): TavernGroupMember? {
        if (members.isEmpty()) return null
        val weighted = members.map { it to it.weight.coerceAtLeast(1) }
        val total = weighted.sumOf { it.second }
        var offset = Math.floorMod(seed, total.toLong()).toInt()
        weighted.forEach { (member, weight) ->
            if (offset < weight) return member
            offset -= weight
        }
        return weighted.last().first
    }
}

/** Tavo/SillyTavern-compatible group JSON codec with conservative aliases. */
object TavernGroupCodec {
    fun parse(json: String): TavernGroupChat? = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return@runCatching null
        val membersElement = root["members"] ?: root["groupMembers"] ?: root["group_members"]
        val members = when {
            membersElement?.isJsonArray == true -> membersElement.asJsonArray.mapIndexedNotNull { index, value ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.let { parseMember(it, index) }
            }
            membersElement?.isJsonObject == true -> membersElement.asJsonObject.entrySet().mapIndexedNotNull { index, (key, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject?.let { parseMember(it, index, key) }
            }
            else -> emptyList()
        }
        TavernGroupChat(
            id = root.string("id", "groupId", "group_id") ?: "group",
            name = root.string("name", "title") ?: "Group Chat",
            members = members,
            replyMode = TavernGroupReplyMode.parse(root.string("replyMode", "reply_mode", "mode")),
            designatedSpeakerId = root.string("designatedSpeakerId", "designated_speaker", "speaker"),
            contextualSpeakerPrompt = root.string(
                "contextualSpeakerPrompt",
                "contextual_speaker_prompt",
                "speakerSelectionPrompt",
                "speaker_selection_prompt"
            ) ?: TavernGroupChat.DEFAULT_CONTEXTUAL_SPEAKER_PROMPT,
            maxReplies = root.int("maxReplies", "max_replies")?.coerceAtLeast(1) ?: 1
        )
    }.getOrNull()

    fun toJson(group: TavernGroupChat): String = JsonObject().apply {
        addProperty("id", group.id)
        addProperty("name", group.name)
        addProperty("replyMode", group.replyMode.wireName())
        group.designatedSpeakerId?.let { addProperty("designatedSpeakerId", it) }
        addProperty("contextualSpeakerPrompt", group.contextualSpeakerPrompt)
        addProperty("maxReplies", group.maxReplies)
        add("members", JsonArray().also { array ->
            group.members.forEach { member ->
                array.add(JsonObject().apply {
                    addProperty("id", member.id)
                    addProperty("name", member.name)
                    member.personaId?.let { addProperty("personaId", it) }
                    addProperty("enabled", member.enabled)
                    addProperty("muted", member.muted)
                    addProperty("weight", member.weight)
                })
            }
        })
    }.toString()

    private fun parseMember(obj: JsonObject, index: Int, fallbackId: String = ""): TavernGroupMember? {
        val id = obj.string("id", "memberId", "member_id", "personaId", "persona_id")
            ?: fallbackId.ifBlank { "member_$index" }
        val name = obj.string("name", "displayName", "display_name", "characterName", "character_name")
            ?: id
        return runCatching {
            TavernGroupMember(
                id = id,
                name = name,
                personaId = obj.string("personaId", "persona_id"),
                enabled = obj.boolean("enabled", "active") ?: true,
                muted = obj.boolean("muted", "isMuted", "is_muted") ?: false,
                weight = obj.int("weight", "groupWeight", "group_weight")?.coerceAtLeast(0) ?: 100
            )
        }.getOrNull()
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean

    private fun JsonObject.int(vararg keys: String): Int? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString) }
        ?.let { value ->
            value.asJsonPrimitive.takeIf { it.isNumber }?.asInt
                ?: value.asString.toIntOrNull()
        }
}
