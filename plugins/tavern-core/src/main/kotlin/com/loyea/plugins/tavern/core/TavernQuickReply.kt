package com.loyea.plugins.tavern.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** SillyTavern Quick Reply v2 data model, with source fields retained for round-trip export. */
data class TavernQuickReply(
    val id: Int = 0,
    val label: String = "",
    val title: String = "",
    val message: String = "",
    val isHidden: Boolean = false,
    val preventAutoExecute: Boolean = true,
    val executeOnStartup: Boolean = false,
    val executeOnUserMessage: Boolean = false,
    val executeOnAiMessage: Boolean = false,
    val executeOnChatChange: Boolean = false,
    val executeOnGroupMemberDraft: Boolean = false,
    val executeOnNewChat: Boolean = false,
    val executeBeforeGeneration: Boolean = false,
    val automationId: String = "",
    val rawJson: String? = null
)

data class TavernQuickReplySet(
    val name: String,
    val qrList: List<TavernQuickReply> = emptyList(),
    val enabled: Boolean = true,
    val disableSend: Boolean = false,
    val placeBeforeInput: Boolean = false,
    val injectInput: Boolean = false,
    val color: String? = null,
    val onlyBorderColor: Boolean = false,
    val rawJson: String? = null
)

/** Codec for current Quick Reply v2 JSON and the legacy quickReplySlots shape. */
object TavernQuickReplyCodec {
    fun parseSet(json: String): TavernQuickReplySet? = runCatching {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return@runCatching null
        parseSetObject(root)
    }.getOrNull()

    fun parseSets(json: String): List<TavernQuickReplySet> = runCatching {
        val root = JsonParser.parseString(json)
        when {
            root.isJsonArray -> root.asJsonArray.mapNotNull { it.asObjectOrNull()?.let(::parseSetObject) }
            root.isJsonObject -> {
                val rootObject = root.asJsonObject
                val list = rootObject["quickReplyPresets"] ?: rootObject["quickReplySets"] ?: rootObject["sets"]
                if (list?.isJsonArray == true) {
                    list.asJsonArray.mapNotNull { it.asObjectOrNull()?.let(::parseSetObject) }
                } else {
                    listOfNotNull(parseSetObject(rootObject))
                }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    fun toJson(set: TavernQuickReplySet): String {
        val root = set.rawJson?.let(::parseObjectOrNull)?.deepCopy() ?: JsonObject()
        root.addProperty("version", 2)
        root.addProperty("name", set.name)
        root.addProperty("enabled", set.enabled)
        root.addProperty("disableSend", set.disableSend)
        root.addProperty("placeBeforeInput", set.placeBeforeInput)
        root.addProperty("injectInput", set.injectInput)
        set.color?.let { root.addProperty("color", it) } ?: root.remove("color")
        root.addProperty("onlyBorderColor", set.onlyBorderColor)
        root.add("qrList", JsonArray().also { array ->
            set.qrList.forEach { array.add(replyToJson(it)) }
        })
        return root.toString()
    }

    private fun parseSetObject(root: JsonObject): TavernQuickReplySet? {
        val name = root.stringOrBlank("name", "title", "preset_name")
        val repliesElement = root["qrList"] ?: root["quickReplySlots"] ?: root["quick_reply_slots"]
        if (name.isBlank() && repliesElement == null) return null
        val replies = when {
            repliesElement?.isJsonArray == true -> repliesElement.asJsonArray.mapIndexedNotNull { index, value ->
                value.asObjectOrNull()?.let { parseReply(it, index + 1) }
            }
            else -> emptyList()
        }
        return TavernQuickReplySet(
            name = name.ifBlank { "Quick Replies" },
            qrList = replies,
            enabled = root.booleanOrDefault("enabled", true),
            disableSend = root.booleanOrDefault("disableSend", root.booleanOrDefault("quickActionEnabled", false)),
            placeBeforeInput = root.booleanOrDefault("placeBeforeInput", root.booleanOrDefault("placeBeforeInputEnabled", false)),
            injectInput = root.booleanOrDefault("injectInput", root.booleanOrDefault("AutoInputInject", false)),
            color = root.stringOrBlank("color").takeIf(String::isNotBlank),
            onlyBorderColor = root.booleanOrDefault("onlyBorderColor", false),
            rawJson = root.toString()
        )
    }

    private fun parseReply(root: JsonObject, fallbackId: Int): TavernQuickReply = TavernQuickReply(
        id = root.intOrDefault("id", fallbackId),
        label = root.stringOrBlank("label", "name"),
        title = root.stringOrBlank("title", "tooltip"),
        message = root.stringPreservingWhitespace("message", "mes", "script").orEmpty(),
        isHidden = root.booleanOrDefault("isHidden", root.booleanOrDefault("hidden", false)),
        preventAutoExecute = root.booleanOrDefault("preventAutoExecute", true),
        executeOnStartup = root.booleanOrDefault("executeOnStartup", root.booleanOrDefault("autoExecute_appStartup", false)),
        executeOnUserMessage = root.booleanOrDefault("executeOnUser", root.booleanOrDefault("autoExecute_userMessage", false)),
        executeOnAiMessage = root.booleanOrDefault("executeOnAi", root.booleanOrDefault("autoExecute_botMessage", false)),
        executeOnChatChange = root.booleanOrDefault("executeOnChatChange", root.booleanOrDefault("autoExecute_chatLoad", false)),
        executeOnGroupMemberDraft = root.booleanOrDefault(
            "executeOnGroupMemberDraft",
            root.booleanOrDefault("autoExecute_groupMemberDraft", false)
        ),
        executeOnNewChat = root.booleanOrDefault("executeOnNewChat", root.booleanOrDefault("autoExecute_newChat", false)),
        executeBeforeGeneration = root.booleanOrDefault(
            "executeBeforeGeneration",
            root.booleanOrDefault("autoExecute_beforeGeneration", false)
        ),
        automationId = root.stringOrBlank("automationId"),
        rawJson = root.toString()
    )

    private fun replyToJson(reply: TavernQuickReply): JsonObject {
        val root = reply.rawJson?.let(::parseObjectOrNull)?.deepCopy() ?: JsonObject()
        root.addProperty("id", reply.id)
        root.addProperty("label", reply.label)
        root.addProperty("title", reply.title)
        root.addProperty("message", reply.message)
        root.addProperty("isHidden", reply.isHidden)
        root.addProperty("preventAutoExecute", reply.preventAutoExecute)
        root.addProperty("executeOnStartup", reply.executeOnStartup)
        root.addProperty("executeOnUser", reply.executeOnUserMessage)
        root.addProperty("executeOnAi", reply.executeOnAiMessage)
        root.addProperty("executeOnChatChange", reply.executeOnChatChange)
        root.addProperty("executeOnGroupMemberDraft", reply.executeOnGroupMemberDraft)
        root.addProperty("executeOnNewChat", reply.executeOnNewChat)
        root.addProperty("executeBeforeGeneration", reply.executeBeforeGeneration)
        root.addProperty("automationId", reply.automationId)
        return root
    }

    private fun parseObjectOrNull(json: String): JsonObject? = runCatching {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun com.google.gson.JsonElement.asObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.stringOrBlank(vararg keys: String): String = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        .orEmpty()

    private fun JsonObject.stringPreservingWhitespace(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { this[it] }
        .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        this[key]?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isBoolean || it.asJsonPrimitive.isString) }
            ?.let { value ->
                if (value.asJsonPrimitive.isBoolean) value.asBoolean else value.asString.toBooleanStrictOrNull()
            } ?: default

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        this[key]?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString) }
            ?.let { value -> value.asString.toIntOrNull() ?: default }
            ?: default
}

/** Plain, side-effect-free Quick Reply execution result. Host code decides how to apply effects. */
data class TavernStScriptContext(
    val macroContext: TavernMacroContext = TavernMacroContext(),
    val input: String = macroContext.input,
    val pipe: String = "",
    val selectedQuickReplySet: String = "",
    val localVariables: Map<String, String> = macroContext.localVariables,
    val globalVariables: Map<String, String> = macroContext.globalVariables,
    val plainTextAction: TavernQuickReplyPlainTextAction = TavernQuickReplyPlainTextAction.SEND
)

enum class TavernQuickReplyPlainTextAction { SEND, INSERT }

sealed interface TavernStScriptEffect {
    data class Toast(val text: String) : TavernStScriptEffect
    data class SetInput(val text: String) : TavernStScriptEffect
    data class SendMessage(
        val text: String,
        val speakerName: String? = null,
        val system: Boolean = false,
        val hidden: Boolean = false,
        val at: Int? = null,
        /** Plain text Quick Replies are sent through the normal user-send path; `/send` is append-only. */
        val triggerGeneration: Boolean = false
    ) : TavernStScriptEffect
    data class Generate(val type: TavernStScriptGenerationType, val prompt: String? = null) : TavernStScriptEffect
    data class AddSwipe(val text: String) : TavernStScriptEffect
    data class SelectQuickReplySet(val name: String) : TavernStScriptEffect
}

enum class TavernStScriptGenerationType { TRIGGER, CONTINUE, SWIPE, REGENERATE, IMPERSONATE, GEN, GEN_RAW }

data class TavernStScriptDiagnostic(
    val command: String,
    val reason: String,
    val fatal: Boolean = false
)

data class TavernStScriptResult(
    val pipe: String,
    val input: String,
    val localVariables: Map<String, String>,
    val globalVariables: Map<String, String>,
    val selectedQuickReplySet: String,
    val effects: List<TavernStScriptEffect>,
    val diagnostics: List<TavernStScriptDiagnostic>,
    val commandCount: Int,
    val aborted: Boolean
)

/**
 * Safe STscript subset for Quick Replies. It intentionally has no filesystem, network, JavaScript,
 * extension loading or arbitrary code execution. Unknown commands are diagnostics, never calls.
 */
object TavernStScriptEngine {
    private const val MAX_COMMANDS = 128
    private const val MAX_RECURSION = 8

    fun execute(
        script: String,
        context: TavernStScriptContext = TavernStScriptContext(),
        quickReplySets: List<TavernQuickReplySet> = emptyList()
    ): TavernStScriptResult {
        val effects = mutableListOf<TavernStScriptEffect>()
        val diagnostics = mutableListOf<TavernStScriptDiagnostic>()
        val local = context.localVariables.toMutableMap()
        val global = context.globalVariables.toMutableMap()
        var input = context.input
        var pipe = context.pipe
        var selectedSet = context.selectedQuickReplySet
        var commandCount = 0
        var aborted = false
        var timesIndex: Int? = null
        val namedClosures = mutableMapOf<String, String>()

        fun render(value: String): String {
            val macroContext = context.macroContext.copy(
                input = input,
                localVariables = local.toMap(),
                globalVariables = global.toMap(),
                customMacros = context.macroContext.customMacros + buildMap {
                    put("pipe", pipe)
                    timesIndex?.let { put("timesindex", it.toString()) }
                }
            )
            return TavernMacroEngine.expand(value, macroContext)
        }

        fun findReply(target: String): TavernQuickReply? {
            val normalized = target.trim()
            if (normalized.isBlank()) return null
            val dot = normalized.indexOf('.')
            if (dot > 0) {
                val setName = normalized.substring(0, dot)
                val label = normalized.substring(dot + 1)
                quickReplySets.firstOrNull { it.name.equals(setName, ignoreCase = true) }
                    ?.qrList?.firstOrNull { it.label.equals(label, ignoreCase = true) }
                    ?.let { return it }
            }
            val preferred = quickReplySets.firstOrNull { it.name.equals(selectedSet, ignoreCase = true) }
            return (preferred?.qrList.orEmpty() + quickReplySets.flatMap { it.qrList })
                .firstOrNull { it.label.equals(normalized, ignoreCase = true) }
        }

        fun findReplySet(target: String): TavernQuickReplySet? =
            quickReplySets.firstOrNull { it.name.equals(target.trim(), ignoreCase = true) }

        fun parseArgs(raw: String): ParsedArguments {
            val tokens = tokenize(raw)
            val named = linkedMapOf<String, String>()
            val positional = mutableListOf<String>()
            tokens.forEach { token ->
                val separator = token.indexOf('=')
                if (separator > 0 && token.substring(0, separator).all { it.isLetterOrDigit() || it == '_' }) {
                    named[token.substring(0, separator).lowercase()] = token.substring(separator + 1)
                } else {
                    positional += token
                }
            }
            return ParsedArguments(named, positional.joinToString(" "), positional.toList())
        }

        fun value(args: ParsedArguments, key: String, positionalIndex: Int = 0): String =
            args.named[key.lowercase()] ?: args.positional.split(Regex("\\s+")).getOrNull(positionalIndex).orEmpty()

        fun textArg(args: ParsedArguments): String = args.named["text"]
            ?: args.named["message"]
            ?: args.named["mes"]
            ?: args.named["value"]
            ?: args.positional.ifBlank { pipe }

        fun variableKey(args: ParsedArguments): String =
            args.named["key"] ?: value(args, "key")

        fun positionalValue(args: ParsedArguments, index: Int): String =
            args.positionalTokens.getOrNull(index).orEmpty()

        fun stripClosure(value: String): String = value.trim()
            .removePrefix("{:")
            .removeSuffix(":}")
            .trim()

        fun resolveOperand(raw: String): String {
            val rendered = render(raw)
            return local[rendered] ?: global[rendered] ?: rendered
        }

        fun compare(left: String, right: String, rule: String): Boolean {
            val normalizedRule = rule.trim().lowercase()
            if (normalizedRule == "not") {
                return left.isBlank() || left == "0" || left.equals("false", ignoreCase = true)
            }
            if (normalizedRule == "in" || normalizedRule == "nin") {
                val contains = left.contains(right, ignoreCase = true)
                return if (normalizedRule == "in") contains else !contains
            }
            val leftNumber = left.toDoubleOrNull()
            val rightNumber = right.toDoubleOrNull()
            return when (normalizedRule) {
                "eq" -> left == right
                "neq" -> left != right
                "lt" -> if (leftNumber != null && rightNumber != null) leftNumber < rightNumber else left < right
                "gt" -> if (leftNumber != null && rightNumber != null) leftNumber > rightNumber else left > right
                "lte" -> if (leftNumber != null && rightNumber != null) leftNumber <= rightNumber else left <= right
                "gte" -> if (leftNumber != null && rightNumber != null) leftNumber >= rightNumber else left >= right
                else -> false
            }
        }

        fun executeSource(source: String, depth: Int) {
            if (depth > MAX_RECURSION) {
                diagnostics += TavernStScriptDiagnostic("run", "maximum Quick Reply recursion exceeded", fatal = true)
                aborted = true
                return
            }
            var returnFromSource = false
            splitPipes(source).forEach { rawCommand ->
                if (aborted || returnFromSource) return@forEach
                if (++commandCount > MAX_COMMANDS) {
                    diagnostics += TavernStScriptDiagnostic("script", "maximum command count exceeded", fatal = true)
                    aborted = true
                    return@forEach
                }
                val segment = rawCommand.trim()
                if (segment.isBlank() || segment.startsWith("//") || segment.startsWith("/#")) return@forEach
                if (!segment.startsWith('/')) {
                    val text = render(segment)
                    when (context.plainTextAction) {
                        TavernQuickReplyPlainTextAction.SEND -> effects += TavernStScriptEffect.SendMessage(
                            text,
                            triggerGeneration = true
                        )
                        TavernQuickReplyPlainTextAction.INSERT -> {
                            input = text
                            effects += TavernStScriptEffect.SetInput(text)
                        }
                    }
                    pipe = text
                    return@forEach
                }
                val commandEnd = segment.indexOfFirst { it.isWhitespace() }.let { if (it < 0) segment.length else it }
                val command = segment.substring(1, commandEnd).lowercase()
                val rawArgs = segment.substring(commandEnd).trim()
                val args = parseArgs(rawArgs)
                if (command.startsWith(":")) {
                    val target = render(command.removePrefix(":") + rawArgs.takeIf(String::isNotBlank).orEmpty().let { if (it.isBlank()) "" else " $it" })
                    val closure = namedClosures.entries.firstOrNull {
                        it.key.equals(target.trim(), ignoreCase = true)
                    }?.value
                    if (closure != null) {
                        executeSource(closure, depth + 1)
                    } else {
                        val reply = findReply(target)
                        if (reply == null) diagnostics += TavernStScriptDiagnostic("run", "quick reply '$target' not found")
                        else executeSource(reply.message, depth + 1)
                    }
                    return@forEach
                }
                when (command) {
                    "pass" -> pipe = render(args.positional.ifBlank { pipe })
                    "echo", "toast" -> {
                        pipe = render(textArg(args))
                        effects += TavernStScriptEffect.Toast(pipe)
                    }
                    "setinput" -> {
                        input = render(textArg(args)).ifBlank { pipe }
                        pipe = input
                        effects += TavernStScriptEffect.SetInput(input)
                    }
                    "send" -> {
                        pipe = render(textArg(args))
                        effects += TavernStScriptEffect.SendMessage(pipe, at = value(args, "at").toIntOrNull())
                    }
                    "sendas" -> {
                        pipe = render(textArg(args))
                        val name = render(value(args, "name"))
                        if (name.isBlank()) diagnostics += TavernStScriptDiagnostic(command, "sendas requires name")
                        else effects += TavernStScriptEffect.SendMessage(pipe, speakerName = name, at = value(args, "at").toIntOrNull())
                    }
                    "sys" -> {
                        pipe = render(textArg(args))
                        effects += TavernStScriptEffect.SendMessage(pipe, system = true)
                    }
                    "comment" -> {
                        pipe = render(textArg(args))
                        effects += TavernStScriptEffect.SendMessage(pipe, system = true, hidden = true)
                    }
                    "addswipe" -> {
                        pipe = render(textArg(args)).ifBlank { pipe }
                        effects += TavernStScriptEffect.AddSwipe(pipe)
                    }
                    "setvar", "setglobalvar" -> {
                        val key = variableKey(args)
                        val explicitValue = args.named["value"]
                        val rawValue = explicitValue ?: if (args.named.containsKey("key")) {
                            args.positional
                        } else {
                            args.positional.substringAfter(key, "").trim()
                        }
                        val rendered = render(rawValue).ifBlank { pipe }
                        if (key.isBlank()) diagnostics += TavernStScriptDiagnostic(command, "variable name is required")
                        else if (command == "setvar") local[key] = rendered else global[key] = rendered
                        pipe = rendered
                    }
                    "getvar", "getglobalvar" -> {
                        val key = variableKey(args)
                        pipe = if (command == "getvar") local[key].orEmpty() else global[key].orEmpty()
                    }
                    "addvar", "addglobalvar", "incvar", "incglobalvar", "decvar", "decglobalvar" -> {
                        val key = variableKey(args)
                        val target = if (command.contains("global")) global else local
                        val current = target[key].orEmpty()
                        val increment = when {
                            command.startsWith("inc") -> "1"
                            command.startsWith("dec") -> "-1"
                            else -> {
                                val rawIncrement = args.named["value"] ?: if (args.named.containsKey("key")) {
                                    args.positional
                                } else {
                                    args.positional.substringAfter(key, "").trim()
                                }
                                render(rawIncrement).ifBlank { pipe }
                            }
                        }
                        pipe = addValue(current, increment)
                        if (key.isNotBlank()) target[key] = pipe else diagnostics += TavernStScriptDiagnostic(command, "variable name is required")
                    }
                    "flushvar", "flushglobalvar" -> {
                        val key = variableKey(args)
                        if (command == "flushvar") local.remove(key) else global.remove(key)
                        pipe = ""
                    }
                    "let" -> {
                        val key = positionalValue(args, 0).ifBlank { args.named["key"].orEmpty() }
                        val rawValue = if (args.named.containsKey("key")) {
                            args.positional
                        } else {
                            args.positionalTokens.drop(1).joinToString(" ")
                        }
                        if (key.isBlank()) {
                            diagnostics += TavernStScriptDiagnostic(command, "variable name is required")
                        } else if (rawValue.trim().startsWith("{:")) {
                            namedClosures[key] = stripClosure(rawValue)
                            pipe = key
                        } else {
                            pipe = render(rawValue).ifBlank { pipe }
                            local[key] = pipe
                        }
                    }
                    "var" -> {
                        val key = positionalValue(args, 0).ifBlank { args.named["key"].orEmpty() }
                        if (key.isBlank()) {
                            diagnostics += TavernStScriptDiagnostic(command, "variable name is required")
                        } else if (args.positionalTokens.size > 1 || args.named.containsKey("value")) {
                            val rawValue = if (args.named.containsKey("key")) {
                                args.named["value"] ?: args.positional
                            } else {
                                args.positionalTokens.drop(1).joinToString(" ")
                            }
                            pipe = render(rawValue).ifBlank { pipe }
                            local[key] = pipe
                        } else {
                            pipe = local[key].orEmpty()
                        }
                    }
                    "add", "mul", "max", "min", "sub", "div", "mod", "pow",
                    "sin", "cos", "log", "sqrt", "abs", "round" -> {
                        val values = args.positionalTokens.map { resolveOperand(it).toDoubleOrNull() }
                        val result = when (command) {
                            "add" -> values.takeIf { it.isNotEmpty() && it.all { value -> value != null } }
                                ?.sumOf { it!! }
                            "mul" -> values.takeIf { it.isNotEmpty() && it.all { value -> value != null } }
                                ?.fold(1.0) { acc, value -> acc * value!! }
                            "max" -> values.takeIf { it.isNotEmpty() && it.all { value -> value != null } }
                                ?.maxOf { it!! }
                            "min" -> values.takeIf { it.isNotEmpty() && it.all { value -> value != null } }
                                ?.minOf { it!! }
                            "sub" -> values.binaryNumeric { left, right -> left - right }
                            "div" -> values.binaryNumeric { left, right -> if (right == 0.0) null else left / right }
                            "mod" -> values.binaryNumeric { left, right -> if (right == 0.0) null else left % right }
                            "pow" -> values.binaryNumeric { left, right -> left.pow(right) }
                            "sin" -> values.unaryNumeric(::sin)
                            "cos" -> values.unaryNumeric(::cos)
                            "log" -> values.unaryNumeric { value -> if (value <= 0.0) null else ln(value) }
                            "sqrt" -> values.unaryNumeric { value -> if (value < 0.0) null else sqrt(value) }
                            "abs" -> values.unaryNumeric(::abs)
                            "round" -> values.unaryNumeric(::round)
                            else -> null
                        }
                        pipe = formatNumeric(result ?: 0.0)
                    }
                    "times" -> {
                        val repeatCount = positionalValue(args, 0).let(::resolveOperand).toIntOrNull()
                        val body = args.positionalTokens.drop(1).joinToString(" ")
                        if (repeatCount == null || body.isBlank()) {
                            diagnostics += TavernStScriptDiagnostic(command, "times requires a repeat count and subcommand")
                        } else {
                            val limit = repeatCount.coerceIn(0, 100)
                            val previousIndex = timesIndex
                            for (index in 0 until limit) {
                                if (aborted) break
                                timesIndex = index
                                executeSource(stripClosure(body), depth + 1)
                            }
                            timesIndex = previousIndex
                        }
                    }
                    "while" -> {
                        val body = args.positional
                        if (body.isBlank()) {
                            diagnostics += TavernStScriptDiagnostic(command, "while requires a subcommand")
                        } else {
                            var iterations = 0
                            while (compare(
                                    resolveOperand(args.named["left"].orEmpty()),
                                    resolveOperand(args.named["right"].orEmpty()),
                                    args.named["rule"].orEmpty().ifBlank { "eq" }
                                ) && !aborted
                            ) {
                                if (iterations++ >= 100) {
                                    diagnostics += TavernStScriptDiagnostic(command, "while iteration guard exceeded", fatal = true)
                                    aborted = true
                                    break
                                }
                                executeSource(stripClosure(body), depth + 1)
                            }
                        }
                    }
                    "qrset" -> {
                        val name = render(args.named["name"] ?: args.named["set"] ?: args.positional)
                        if (findReplySet(name) == null) diagnostics += TavernStScriptDiagnostic(command, "quick reply set '$name' not found")
                        else {
                            selectedSet = name
                            effects += TavernStScriptEffect.SelectQuickReplySet(name)
                            pipe = name
                        }
                    }
                    "run" -> {
                        val target = render(args.named["label"] ?: args.named["name"] ?: args.positional)
                        val closure = if (target.trim().startsWith("{:")) stripClosure(target) else namedClosures.entries
                            .firstOrNull { it.key.equals(target.trim(), ignoreCase = true) }
                            ?.value
                        if (closure != null) {
                            executeSource(closure, depth + 1)
                        } else {
                            val reply = findReply(target)
                            if (reply == null) diagnostics += TavernStScriptDiagnostic("run", "quick reply '$target' not found")
                            else executeSource(reply.message, depth + 1)
                        }
                    }
                    "if" -> {
                        val left = resolveOperand(args.named["left"].orEmpty())
                        val right = resolveOperand(args.named["right"].orEmpty())
                        val rule = args.named["rule"].orEmpty().ifBlank { "eq" }
                        val trueSource = stripClosure(args.positional)
                        val falseSource = args.named["else"]?.let(::stripClosure).orEmpty()
                        if (trueSource.isBlank() && falseSource.isBlank()) {
                            diagnostics += TavernStScriptDiagnostic(command, "if requires a subcommand")
                        } else if (compare(left, right, rule)) {
                            executeSource(trueSource, depth + 1)
                        } else if (falseSource.isNotBlank()) {
                            executeSource(falseSource, depth + 1)
                        }
                    }
                    "return" -> {
                        pipe = render(textArg(args))
                        returnFromSource = true
                    }
                    "breakpoint" -> diagnostics += TavernStScriptDiagnostic(command, "debugger breakpoints are not interactive on Android")
                    "trigger" -> {
                        effects += TavernStScriptEffect.Generate(TavernStScriptGenerationType.TRIGGER)
                        pipe = ""
                    }
                    "continue" -> {
                        effects += TavernStScriptEffect.Generate(TavernStScriptGenerationType.CONTINUE)
                        pipe = ""
                    }
                    "swipe" -> {
                        effects += TavernStScriptEffect.Generate(TavernStScriptGenerationType.SWIPE)
                        pipe = ""
                    }
                    "regenerate" -> {
                        effects += TavernStScriptEffect.Generate(TavernStScriptGenerationType.REGENERATE)
                        pipe = ""
                    }
                    "impersonate" -> {
                        effects += TavernStScriptEffect.Generate(
                            TavernStScriptGenerationType.IMPERSONATE,
                            prompt = render(textArg(args)).takeIf(String::isNotBlank)
                        )
                        pipe = ""
                    }
                    "gen", "genraw" -> {
                        effects += TavernStScriptEffect.Generate(
                            if (command == "gen") TavernStScriptGenerationType.GEN else TavernStScriptGenerationType.GEN_RAW,
                            prompt = render(textArg(args))
                        )
                        pipe = ""
                    }
                    "abort" -> aborted = true
                    else -> diagnostics += TavernStScriptDiagnostic(command, "unsupported or blocked command")
                }
            }
        }

        executeSource(script, 0)
        return TavernStScriptResult(
            pipe = pipe,
            input = input,
            localVariables = local.toMap(),
            globalVariables = global.toMap(),
            selectedQuickReplySet = selectedSet,
            effects = effects.toList(),
            diagnostics = diagnostics.toList(),
            commandCount = commandCount,
            aborted = aborted
        )
    }

    private data class ParsedArguments(
        val named: Map<String, String>,
        val positional: String,
        val positionalTokens: List<String>
    )

    private fun List<Double?>.binaryNumeric(operation: (Double, Double) -> Double?): Double? =
        if (size == 2 && all { it != null }) operation(first()!!, last()!!) else null

    private fun List<Double?>.unaryNumeric(operation: (Double) -> Double?): Double? =
        if (size == 1 && first() != null) operation(first()!!) else null

    private fun formatNumeric(value: Double): String = when {
        !value.isFinite() -> "0"
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> value.toString()
    }

    private fun addValue(current: String, increment: String): String {
        val left = current.toDoubleOrNull()
        val right = increment.toDoubleOrNull()
        return if (left != null && right != null) {
            val result = left + right
            if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
        } else {
            current + increment
        }
    }

    private fun splitPipes(script: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var closureDepth = 0
        var escaped = false
        var index = 0
        while (index < script.length) {
            val char = script[index]
            if (escaped) {
                current.append(if (char == '|') '|' else char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (quote != null) {
                current.append(char)
                if (char == quote) quote = null
            } else if (script.startsWith("{:", index)) {
                closureDepth++
                current.append("{:")
                index++
            } else if (script.startsWith(":}", index) && closureDepth > 0) {
                closureDepth--
                current.append(":}")
                index++
            } else if (char == '\'' || char == '"') {
                quote = char
                current.append(char)
            } else if (char == '|' && closureDepth == 0) {
                result += current.toString()
                current.clear()
            } else {
                current.append(char)
            }
            index++
        }
        if (escaped) current.append('\\')
        result += current.toString()
        return result
    }

    private fun tokenize(raw: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var closureDepth = 0
        var escaped = false
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (escaped) {
                current.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (quote != null) {
                if (char == quote) quote = null else current.append(char)
            } else if (raw.startsWith("{:", index)) {
                closureDepth++
                current.append("{:")
                index++
            } else if (raw.startsWith(":}", index) && closureDepth > 0) {
                closureDepth--
                current.append(":}")
                index++
            } else if (char == '\'' || char == '"') {
                quote = char
            } else if (char.isWhitespace()) {
                if (closureDepth == 0) flush() else current.append(char)
            } else {
                current.append(char)
            }
            index++
        }
        if (escaped) current.append('\\')
        flush()
        return result
    }
}
