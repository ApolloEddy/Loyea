package com.loyea.plugins.tavern.core

import com.loyea.context.core.*

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/** SillyTavern Regex extension 的 placement 常量。 */
object TavernRegexPlacement {
    const val DISPLAY = 0
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6
}

data class TavernRegexScript(
    val id: String,
    val scriptName: String,
    val findRegex: String,
    val replaceString: String = "",
    val trimStrings: List<String> = emptyList(),
    val placement: List<Int> = listOf(TavernRegexPlacement.AI_OUTPUT),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val runOnEdit: Boolean = false,
    val substituteRegex: Int = 0,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val rawJson: String? = null
)

/** Immutable macro values captured when a Tavern request starts. */
data class TavernMacroContext(
    val characterName: String = "Char",
    val description: String = "",
    val userName: String = "User",
    val personality: String = "",
    val scenario: String = "",
    val personaDescription: String = "",
    val charPrompt: String = "",
    val charInstruction: String = "",
    val charDepthPrompt: String = "",
    val charCreatorNotes: String = "",
    val charVersion: String = "",
    val charFirstMessage: String = "",
    val messageExamples: String = "",
    val lastMessage: String = "",
    val lastUserMessage: String = "",
    val lastCharMessage: String = "",
    val input: String = "",
    val original: String = "",
    val generationType: String = "normal",
    val authorNote: String = "",
    val outlets: Map<String, String> = emptyMap(),
    val localVariables: Map<String, String> = emptyMap(),
    val globalVariables: Map<String, String> = emptyMap(),
    val timestampMillis: Long? = null,
    val alternateGreetings: List<String> = emptyList(),
    val group: String = "",
    val groupNotMuted: String = "",
    val notCharacter: String = "",
    val lastMessageId: String = "",
    val firstIncludedMessageId: String = "",
    val firstDisplayedMessageId: String = "",
    val lastSwipeId: String = "",
    val currentSwipeId: String = "",
    val summary: String = "",
    val model: String = "",
    val maxPrompt: String = "",
    val maxContextTokens: String = "",
    val maxResponseTokens: String = "",
    val customMacros: Map<String, String> = emptyMap()
)

/**
 * Small, deterministic subset of SillyTavern's macro evaluator used by prompt and Regex
 * stages. It is deliberately read-only: setvar/addvar/script execution belongs to the future
 * STscript port and must not mutate a request through an untrusted card.
 */
object TavernMacroEngine {
    private const val MAX_EXPANSION_PASSES = 8
    private val tokenPattern = Regex("\\{\\{\\s*([A-Za-z.$][A-Za-z0-9_.-]*)\\s*(?:::\\s*([^{}]*?))?\\s*}}")
    private val singleColonPattern = Regex("\\{\\{\\s*([A-Za-z.$][A-Za-z0-9_.-]*)\\s*:(?!:)\\s*([^{}]*?)\\s*}}")
    private val spacedTokenPattern = Regex("\\{\\{\\s*([A-Za-z.$][A-Za-z0-9_.-]*)\\s+([^{}]+?)\\s*}}")
    private val conditionalPattern = Regex(
        "\\{\\{\\s*if(?:\\s*::\\s*|\\s+)([^{}]*?)\\s*}}([\\s\\S]*?)\\{\\{\\s*/if\\s*}}",
        RegexOption.IGNORE_CASE
    )
    private const val ESCAPED_OPEN = "\\u0001"
    private const val ESCAPED_CLOSE = "\\u0002"

    fun expand(text: String, context: TavernMacroContext): String {
        if (text.isBlank()) return text
        var result = text
            .replace("<USER>", "{{user}}", ignoreCase = true)
            .replace("<BOT>", "{{char}}", ignoreCase = true)
            .replace("<GROUP>", "{{group}}", ignoreCase = true)
            .replace("<CHARIFNOTGROUP>", "{{charIfNotGroup}}", ignoreCase = true)
            .replace("<CHAR>", "{{char}}", ignoreCase = true)
            .replace("\\{{", ESCAPED_OPEN)
            .replace("\\}}", ESCAPED_CLOSE)
        repeat(MAX_EXPANSION_PASSES) {
            val before = result
            result = singleColonPattern.replace(result) { match ->
                "{{${match.groupValues[1]}::${match.groupValues[2]}}}"
            }
            result = tokenPattern.replace(result) { match ->
                val name = match.groupValues[1].lowercase()
                val argument = match.groupValues.getOrNull(2).orEmpty().trim()
                valueFor(name, argument, context) ?: match.value
            }
            result = conditionalPattern.replace(result) { match ->
                val condition = match.groupValues[1].trim()
                val body = match.groupValues[2]
                val branches = body.split(Regex("\\{\\{\\s*else\\s*}}"), limit = 2)
                val selected = if (isTruthy(resolveCondition(condition, context))) {
                    branches.firstOrNull().orEmpty()
                } else {
                    branches.getOrNull(1).orEmpty()
                }
                expand(selected, context)
            }
            result = spacedTokenPattern.replace(result) { match ->
                val name = match.groupValues[1].lowercase()
                val argument = match.groupValues[2].trim()
                valueFor(name, argument, context) ?: match.value
            }
            if (result == before) return@repeat
        }
        return result
            .replace(ESCAPED_OPEN, "{{")
            .replace(ESCAPED_CLOSE, "}}")
    }

    private fun valueFor(name: String, argument: String, context: TavernMacroContext): String? =
        valueForKnown(name, argument, context)

    private fun valueForKnown(name: String, argument: String, context: TavernMacroContext): String? = when {
        name.startsWith(".") -> context.localVariables[name.drop(1)].orEmpty()
        name.startsWith("$") -> context.globalVariables[name.drop(1)].orEmpty()
        name in context.customMacros -> context.customMacros[name].orEmpty()
        else -> when (name) {
        "char", "bot" -> context.characterName.ifBlank { "Char" }
        "user" -> context.userName.ifBlank { "User" }
        "group" -> context.group.ifBlank { context.characterName.ifBlank { "Char" } }
        "groupnotmuted" -> context.groupNotMuted.ifBlank { context.group.ifBlank { context.characterName.ifBlank { "Char" } } }
        "charifnotgroup" -> if (context.group.isBlank()) context.characterName.ifBlank { "Char" } else ""
        "notchar" -> context.notCharacter
        "description" -> context.description
        "personality" -> context.personality
        "scenario" -> context.scenario
        "persona" -> context.personaDescription.ifBlank { context.userName.ifBlank { "User" } }
        "charprompt" -> context.charPrompt
        "charinstruction" -> context.charInstruction
        "chardepthprompt" -> context.charDepthPrompt
        "charcreatornotes" -> context.charCreatorNotes
        "charversion" -> context.charVersion
        "charfirstmessage" -> context.alternateGreetings.getOrNull(argument.toIntOrNull() ?: 0)
            ?: context.charFirstMessage
        "mesexamples", "mesexamplesraw" -> context.messageExamples
        "lastmessage" -> context.lastMessage
        "lastmessageid" -> context.lastMessageId
        "lastusermessage" -> context.lastUserMessage
        "lastcharmessage" -> context.lastCharMessage
        "firstincludedmessageid" -> context.firstIncludedMessageId
        "firstdisplayedmessageid" -> context.firstDisplayedMessageId
        "lastswipeid" -> context.lastSwipeId
        "currentswipeid" -> context.currentSwipeId
        "summary" -> context.summary
        "input" -> context.input
        "original" -> context.original
        "lastgenerationtype" -> context.generationType.ifBlank { "normal" }
        "time" -> formatTime(context.timestampMillis, argument)
        "date" -> formatDate(context.timestampMillis)
        "weekday" -> formatWeekday(context.timestampMillis)
        "isotime" -> formatTimestamp(context.timestampMillis, "HH:mm")
        "isodate" -> formatTimestamp(context.timestampMillis, "yyyy-MM-dd")
        "datetimeformat" -> formatTimestamp(context.timestampMillis, argument.ifBlank { "yyyy-MM-dd HH:mm:ss" })
        "model" -> context.model
        "maxprompt" -> context.maxPrompt
        "maxcontexttokens" -> context.maxContextTokens
        "maxresponsetokens" -> context.maxResponseTokens
        "authorsnote", "charauthorsnote", "defaultauthorsnote" -> context.authorNote
        "newline" -> "\n".repeat(argument.toIntOrNull()?.coerceIn(1, 64) ?: 1)
        "space" -> " ".repeat(argument.toIntOrNull()?.coerceIn(1, 64) ?: 1)
        "noop" -> ""
        "trim" -> ""
        "reverse" -> argument.reversed()
        "outlet" -> context.outlets.entries
            .firstOrNull { it.key.equals(argument, ignoreCase = true) }
            ?.value
            .orEmpty()
        "getvar" -> context.localVariables[argument].orEmpty()
        "getglobalvar" -> context.globalVariables[argument].orEmpty()
        "hasvar" -> (argument in context.localVariables).toString()
        "hasglobalvar" -> (argument in context.globalVariables).toString()
        "ismobile" -> "true"
        "banned" -> ""
        else -> null
        }
    }

    private fun resolveCondition(condition: String, context: TavernMacroContext): String {
        val trimmed = condition.trim()
        if (trimmed.startsWith("!")) {
            return (!isTruthy(resolveCondition(trimmed.drop(1), context))).toString()
        }
        if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            return expand(trimmed, context)
        }
        return valueForKnown(trimmed.lowercase(), "", context) ?: trimmed
    }

    private fun isTruthy(value: String): Boolean = value.trim().lowercase() !in setOf("", "false", "0", "off", "no", "null")

    private fun formatTimestamp(timestampMillis: Long?, pattern: String): String = runCatching {
        java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(
            java.util.Date(timestampMillis ?: System.currentTimeMillis())
        )
    }.getOrDefault("")

    private fun formatTime(timestampMillis: Long?, argument: String): String {
        val pattern = if (argument.startsWith("UTC", ignoreCase = true)) "HH:mm:ss z" else "HH:mm:ss"
        return formatTimestamp(timestampMillis, pattern)
    }

    private fun formatDate(timestampMillis: Long?): String = formatTimestamp(timestampMillis, "yyyy-MM-dd")

    private fun formatWeekday(timestampMillis: Long?): String = formatTimestamp(timestampMillis, "EEEE")
}

/**
 * 受控的 Regex find/replace 引擎。
 *
 * 兼容 ST 的 `/pattern/flags`、capture group、`{{match}}`、宏替换和 placement，
 * 但对用户导入的模式设置长度上限并捕获编译异常；无效脚本只跳过，不影响整条回复。
 */
object TavernRegexEngine {
    private const val MAX_PATTERN_LENGTH = 4096
    private const val SUBSTITUTE_NONE = 0
    private const val SUBSTITUTE_RAW = 1
    private const val SUBSTITUTE_ESCAPED = 2
    private const val MAX_COMPILED_REGEX_CACHE = 128
    private val compiledRegexCache = ConcurrentHashMap<String, Regex>()
    private val invalidRegexCache = ConcurrentHashMap.newKeySet<String>()
    private val groupPattern = Regex("\\$(\\d+)|\\$<([^>]+)>")

    fun parseScripts(extensionsJson: String): List<TavernRegexScript> {
        val root = runCatching { JsonParser.parseString(extensionsJson) }.getOrNull() ?: return emptyList()
        if (root.isJsonArray) return parseScriptElement(root)
        if (!root.isJsonObject) return emptyList()
        val extensions = root.asJsonObject
        val scripts = extensions["regex_scripts"] ?: extensions["regexScripts"]
        if (scripts != null) return parseScriptElement(scripts)
        // 外部注册资源常直接保存单个脚本对象，而不是 {regex_scripts:[...]}。
        if (extensions.has("findRegex") || extensions.has("find_regex")) {
            return parseScriptElement(JsonArray().also { it.add(extensions) })
        }
        return emptyList()
    }

    fun parseScriptElement(element: com.google.gson.JsonElement): List<TavernRegexScript> = when {
        element.isJsonArray -> element.asJsonArray.mapIndexedNotNull { index, item ->
            item.takeIf { it.isJsonObject }?.asJsonObject?.let { parseScript(it, index) }
        }
        element.isJsonObject -> element.asJsonObject.entrySet().mapIndexedNotNull { index, (id, item) ->
            item.takeIf { it.isJsonObject }?.asJsonObject?.let {
                parseScript(it, index).let { script ->
                    if (script.id.startsWith("regex_") && id.isNotBlank()) script.copy(id = id) else script
                }
            }
        }
        else -> emptyList()
    }

    fun apply(
        text: String,
        scripts: List<TavernRegexScript>,
        placement: Int,
        context: TavernMacroContext = TavernMacroContext(),
        depth: Int? = null,
        isMarkdown: Boolean = false,
        isPrompt: Boolean = false,
        isEdit: Boolean = false
    ): String {
        if (text.isEmpty() || scripts.isEmpty()) return text
        var result = text
        scripts.forEach { script ->
            if (script.disabled || script.findRegex.isBlank() || placement !in script.placement) return@forEach
            if (isEdit && !script.runOnEdit) return@forEach
            // placement 决定脚本在哪个 ST 阶段运行；markdownOnly/promptOnly
            // 只是额外限制。没有这两个限制的脚本必须也能作用于 prompt/world-info，
            // 否则导入的 world_info 正则会被静默跳过。
            val stageAllowed = when {
                script.markdownOnly -> isMarkdown
                script.promptOnly -> isPrompt
                else -> true
            }
            if (!stageAllowed) return@forEach
            if (!withinDepth(script, depth)) return@forEach
            result = applyOne(result, script, context)
        }
        return result
    }

    /** 展示层同时承接 ST 的 DISPLAY(0) 与 AI_OUTPUT(2) placement，但每个脚本只执行一次。 */
    fun applyOutput(
        text: String,
        scripts: List<TavernRegexScript>,
        context: TavernMacroContext,
        depth: Int? = null,
        isMarkdown: Boolean = true,
        isEdit: Boolean = false
    ): String {
        val aiOutputScripts = scripts.filter { TavernRegexPlacement.AI_OUTPUT in it.placement }
        val displayScripts = scripts.filter { TavernRegexPlacement.DISPLAY in it.placement }
        val afterAiOutput = apply(
            text = text,
            scripts = aiOutputScripts,
            placement = TavernRegexPlacement.AI_OUTPUT,
            context = context,
            depth = depth,
            isMarkdown = false,
            isEdit = isEdit
        )
        return apply(
            text = afterAiOutput,
            scripts = displayScripts,
            placement = TavernRegexPlacement.DISPLAY,
            context = context,
            depth = depth,
            isMarkdown = isMarkdown,
            isEdit = isEdit
        )
    }

    /** 将角色卡 scoped regex 应用到世界书的各个注入槽位，避免只处理 legacy 单块。 */
    fun applyToWorldInfoRender(
        render: WorldInfoMatcher.WorldInfoRenderResult,
        scripts: List<TavernRegexScript>,
        context: TavernMacroContext
    ): WorldInfoMatcher.WorldInfoRenderResult {
        if (scripts.isEmpty()) return render
        fun transform(value: String?): String? = value?.let {
            apply(
                text = it,
                scripts = scripts,
                placement = TavernRegexPlacement.WORLD_INFO,
                context = context,
                isPrompt = true
            )
        }
        return render.copy(
            all = transform(render.all),
            legacy = transform(render.legacy),
            beforeCharacterDefinitions = transform(render.beforeCharacterDefinitions),
            afterCharacterDefinitions = transform(render.afterCharacterDefinitions),
            authorNoteTop = transform(render.authorNoteTop),
            authorNoteBottom = transform(render.authorNoteBottom),
            exampleMessagesTop = transform(render.exampleMessagesTop),
            exampleMessagesBottom = transform(render.exampleMessagesBottom),
            atDepth = render.atDepth.mapValues { (_, value) ->
                apply(
                    text = value,
                    scripts = scripts,
                    placement = TavernRegexPlacement.WORLD_INFO,
                    context = context,
                    isPrompt = true
                )
            },
            outlets = render.outlets.mapValues { (_, value) ->
                apply(
                    text = value,
                    scripts = scripts,
                    placement = TavernRegexPlacement.WORLD_INFO,
                    context = context,
                    isPrompt = true
                )
            },
            atDepthBlocks = render.atDepthBlocks.mapValues { (_, blocks) ->
                blocks.map { block ->
                    block.copy(
                        content = apply(
                            text = block.content,
                            scripts = scripts,
                            placement = TavernRegexPlacement.WORLD_INFO,
                            context = context,
                            isPrompt = true
                        )
                    )
                }
            }
        )
    }

    private fun applyOne(
        input: String,
        script: TavernRegexScript,
        context: TavernMacroContext
    ): String {
        val regexSource = substituteMacros(script.findRegex, script.substituteRegex, context)
        if (regexSource.length > MAX_PATTERN_LENGTH) return input
        val parsed = parseRegexSource(regexSource) ?: return input
        val cacheKey = parsed.body + "\u0000" + parsed.options.joinToString(",")
        if (cacheKey in invalidRegexCache) return input
        val regex = compiledRegexCache[cacheKey] ?: runCatching {
            Regex(parsed.body, parsed.options)
        }.getOrNull()?.also {
            if (compiledRegexCache.size >= MAX_COMPILED_REGEX_CACHE) compiledRegexCache.clear()
            compiledRegexCache[cacheKey] = it
        } ?: run {
            if (invalidRegexCache.size >= MAX_COMPILED_REGEX_CACHE) invalidRegexCache.clear()
            invalidRegexCache.add(cacheKey)
            return input
        }
        val replacement = { match: MatchResult ->
            val filteredMatch = script.trimStrings.fold(match.value) { value, trim -> value.replace(trim, "") }
            expandReplacement(script.replaceString, match, filteredMatch, context)
        }
        return if ('g' in parsed.flags) {
            regex.replace(input, replacement)
        } else {
            val first = regex.find(input) ?: return input
            input.replaceRange(first.range, replacement(first))
        }
    }

    private fun expandReplacement(
        template: String,
        match: MatchResult,
        filteredMatch: String,
        context: TavernMacroContext
    ): String {
        var result = template
            .replace("{{match}}", filteredMatch, ignoreCase = true)
            .replace("{{char}}", context.characterName.ifBlank { "Char" }, ignoreCase = true)
            .replace("{{user}}", context.userName.ifBlank { "User" }, ignoreCase = true)
        return groupPattern.replace(result) { group ->
            val index = group.groups[1]?.value?.toIntOrNull()
            val name = group.groups[2]?.value
            when {
                index == 0 -> filteredMatch
                index != null -> runCatching { match.groups[index]?.value.orEmpty() }.getOrDefault("")
                name != null -> runCatching { match.groups[name]?.value.orEmpty() }.getOrDefault("")
                else -> ""
            }
        }
    }

    private fun substituteMacros(
        value: String,
        mode: Int,
        context: TavernMacroContext
    ): String {
        if (mode == SUBSTITUTE_NONE) return value
        val charName = context.characterName.ifBlank { "Char" }
        val safeUser = context.userName.ifBlank { "User" }
        val description = context.description
        return if (mode == SUBSTITUTE_ESCAPED) {
            value
                .replace("{{char}}", Regex.escape(charName), ignoreCase = true)
                .replace("{{user}}", Regex.escape(safeUser), ignoreCase = true)
                .replace("{{description}}", Regex.escape(description), ignoreCase = true)
        } else {
            value
                .replace("{{char}}", charName, ignoreCase = true)
                .replace("{{user}}", safeUser, ignoreCase = true)
                .replace("{{description}}", description, ignoreCase = true)
        }
    }

    private fun withinDepth(script: TavernRegexScript, depth: Int?): Boolean {
        if (depth == null) return true
        if (script.minDepth != null && script.minDepth >= -1 && depth < script.minDepth) return false
        if (script.maxDepth != null && script.maxDepth >= 0 && depth > script.maxDepth) return false
        return true
    }

    private data class ParsedRegex(val body: String, val flags: String, val options: Set<RegexOption>)

    private fun parseRegexSource(value: String): ParsedRegex? {
        val source = value.trim()
        if (source.isEmpty()) return null
        val slash = source.startsWith('/') && source.lastIndexOf('/') > 0
        val body = if (slash) source.substring(1, source.lastIndexOf('/')) else source
        val flags = if (slash) source.substring(source.lastIndexOf('/') + 1) else ""
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }
        return ParsedRegex(body, flags, options)
    }

    private fun parseScript(obj: JsonObject, index: Int): TavernRegexScript {
        fun string(vararg names: String): String = names.asSequence()
            .mapNotNull { obj[it] }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString.orEmpty()

        fun bool(vararg names: String): Boolean = names.asSequence()
            .mapNotNull { obj[it] }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
            ?.asBoolean ?: false

        fun intOrNull(vararg names: String): Int? = names.asSequence()
            .mapNotNull { obj[it] }
            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt

        fun strings(vararg names: String): List<String> {
            val element = names.asSequence().mapNotNull { obj[it] }.firstOrNull() ?: return emptyList()
            return when {
                element.isJsonArray -> element.asJsonArray.mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> listOf(element.asString)
                else -> emptyList()
            }
        }

        fun ints(vararg names: String): List<Int> {
            val element = names.asSequence().mapNotNull { obj[it] }.firstOrNull() ?: return emptyList()
            return if (element.isJsonArray) {
                element.asJsonArray.mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                }
            } else {
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.let(::listOf)
                    ?: emptyList()
            }
        }

        return TavernRegexScript(
            id = string("id").ifBlank { "regex_$index" },
            scriptName = string("scriptName", "name").ifBlank { "Regex $index" },
            findRegex = string("findRegex", "find_regex"),
            replaceString = string("replaceString", "replace_with"),
            trimStrings = strings("trimStrings", "trim_strings"),
            placement = ints("placement").ifEmpty {
                strings("placement").mapNotNull { it.toIntOrNull() }
            }.ifEmpty { listOf(TavernRegexPlacement.AI_OUTPUT) },
            disabled = bool("disabled"),
            markdownOnly = bool("markdownOnly", "markdown_only"),
            promptOnly = bool("promptOnly", "prompt_only"),
            runOnEdit = bool("runOnEdit", "run_on_edit"),
            substituteRegex = intOrNull("substituteRegex", "substitute_regex") ?: SUBSTITUTE_NONE,
            minDepth = intOrNull("minDepth", "min_depth"),
            maxDepth = intOrNull("maxDepth", "max_depth"),
            rawJson = obj.toString()
        )
    }

}
