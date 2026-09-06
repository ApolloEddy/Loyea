package com.loyea.character.core.regex

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * SillyTavern regex_scripts → 有限正则规则的导入映射（Spec §8）。
 *
 * 只映射已明确理解的 stage/flags；带不支持的深度、编辑时运行、其他 placement
 * 或宏行为的规则整条标为不支持（不能抹去条件扩大执行范围）。源 JSON 原样保留。
 */
object RegexScriptAdapter {

    data class ImportOutcome(
        val rules: List<RegexRule>,
        val rejections: List<RegexRuleRejection>
    )

    /** 从 data.extensions.regex_scripts 数组映射；格式不符返回空结果。 */
    fun fromExtensionsJson(extensionsJson: String): ImportOutcome {
        val root = runCatching {
            JsonParser.parseString(extensionsJson).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: return ImportOutcome(emptyList(), emptyList())
        val array = root.get("regex_scripts")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return ImportOutcome(emptyList(), emptyList())
        return fromScriptArray(array)
    }

    fun fromScriptArray(array: JsonArray): ImportOutcome {
        val rules = ArrayList<RegexRule>()
        val rejections = ArrayList<RegexRuleRejection>()
        // 上限：每角色 32 条有效规则（Spec §8）；超出的整条拒绝
        array.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val id = obj.stringOrNull("id")
                ?: obj.stringOrNull("scriptName")
                ?: "regex_${rules.size + rejections.size}"
            if (rules.size >= BoundedRegexLimits.MAX_RULES_PER_CHARACTER) {
                rejections += RegexRuleRejection(id, "超出每角色 ${BoundedRegexLimits.MAX_RULES_PER_CHARACTER} 条规则上限")
                return@forEach
            }
            val outcome = mapScript(obj, id)
            when (outcome) {
                is MapResult.Mapped -> rules += outcome.rule
                is MapResult.Unmapped -> rejections += RegexRuleRejection(id, outcome.reason)
            }
        }
        return ImportOutcome(rules, rejections)
    }

    private sealed class MapResult {
        data class Mapped(val rule: RegexRule) : MapResult()
        data class Unmapped(val reason: String) : MapResult()
    }

    private fun mapScript(obj: JsonObject, id: String): MapResult {
        if (obj.booleanOrNull("disabled") == true) {
            return MapResult.Unmapped("规则已禁用")
        }
        if (obj.booleanOrNull("runOnEdit") == true) {
            return MapResult.Unmapped("不支持「编辑时运行」")
        }
        if (obj.has("minDepth") || obj.has("maxDepth")) {
            val min = obj.get("minDepth")
            val max = obj.get("maxDepth")
            val isDefault = (min == null || (min.isJsonPrimitive && min.asString == "")) &&
                (max == null || (max.isJsonPrimitive && max.asString == ""))
            if (!isDefault) {
                return MapResult.Unmapped("不支持的深度限制（minDepth/maxDepth）")
            }
        }
        if (!obj.stringOrNull("trimStrings").isNullOrBlank() ||
            (obj.get("trimStrings")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) > 0
        ) {
            return MapResult.Unmapped("不支持的 trimStrings 行为")
        }
        if (obj.booleanOrNull("substituteRegex") == true) {
            return MapResult.Unmapped("不支持的宏替换行为")
        }
        val find = obj.stringOrNull("findRegex") ?: return MapResult.Unmapped("缺少 findRegex")
        val replace = obj.stringOrNull("replaceString") ?: ""
        val markdownOnly = obj.booleanOrNull("markdownOnly") == true
        val promptOnly = obj.booleanOrNull("promptOnly") == true

        // placement: [1]=user input，[2]=AI output（ST 数组语义）
        val placements = obj.get("placement")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asInt }
            ?: listOf(2)
        val stages = LinkedHashSet<RegexStage>()
        if (1 in placements) stages += RegexStage.PROMPT_USER
        if (2 in placements) {
            if (markdownOnly) {
                stages += RegexStage.DISPLAY_ASSISTANT
            } else if (promptOnly) {
                stages += RegexStage.PROMPT_ASSISTANT
            } else {
                stages += RegexStage.PROMPT_ASSISTANT
                stages += RegexStage.DISPLAY_ASSISTANT
            }
        }
        if (stages.isEmpty()) {
            return MapResult.Unmapped("placement 未映射到任何支持阶段")
        }

        // findRegex 支持 /pattern/flags 包装；flags 只认 i/m/s（g → globalReplace）
        var pattern = find
        var flagSet = emptySet<Char>()
        var global = true
        if (find.length >= 2 && find.startsWith("/") && find.lastIndexOf("/") > 0) {
            val lastSlash = find.lastIndexOf('/')
            pattern = find.substring(1, lastSlash)
            val flagText = find.substring(lastSlash + 1)
            flagSet = flagText.filter { it in "ims" }.toSet()
            if ('g' in flagText) global = true else global = false
            val unknown = flagText.filter { it !in "imsg" }
            if (unknown.isNotEmpty()) {
                return MapResult.Unmapped("不支持的 flags: $unknown")
            }
        }
        if (pattern.length > BoundedRegexLimits.MAX_PATTERN_LENGTH) {
            return MapResult.Unmapped("pattern 超过长度上限")
        }

        return MapResult.Mapped(
            RegexRule(
                id = id,
                pattern = pattern,
                replacement = replace,
                stages = stages,
                flags = flagSet,
                globalReplace = global,
                enabled = true,
                rawJson = obj.toString()
            )
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
