package com.loyea.character.core.regex

import com.google.re2j.Matcher
import com.google.re2j.Pattern

/**
 * 有限文本正则（Spec §8 / P5）：只做明确作用域的有界替换，不宣称 JS Regex 全兼容。
 *
 * 作用域仅三阶段：PROMPT_USER / PROMPT_ASSISTANT（提示词副本）与 DISPLAY_ASSISTANT（仅显示）。
 * system、工具 JSON、健康数据、思考链与永久存储不进入本引擎。
 *
 * 引擎选择 RE2/J（Google，BSD 许可，版本锁定于 character-core/build.gradle.kts）：
 * 线性时间匹配，天然拒绝回溯引用与 lookaround；未知 flag / 超限规则整条拒绝并保留配置。
 */
enum class RegexStage { PROMPT_USER, PROMPT_ASSISTANT, DISPLAY_ASSISTANT }

/** 一条显示/提示词替换规则（按阶段白名单展开，不做任意管线）。 */
data class RegexRule(
    val id: String,
    val pattern: String,
    val replacement: String,
    val stages: Set<RegexStage>,
    /** 支持的 flags：i / m / s；g 用 [globalReplace] 表达；其余整条拒绝 */
    val flags: Set<Char> = emptySet(),
    val globalReplace: Boolean = true,
    val enabled: Boolean = true,
    val rawJson: String? = null
)

/** 规则被拒绝的原因（保留配置、诊断可见，不静默扩大执行范围）。 */
data class RegexRuleRejection(val ruleId: String, val reason: String)

/** 规则编译结果。 */
sealed class RegexCompileOutcome {
    data class Ok(val pattern: Pattern) : RegexCompileOutcome()
    data class Rejected(val reason: String) : RegexCompileOutcome()
}

object BoundedRegexLimits {
    const val MAX_RULES_PER_CHARACTER = 32
    const val MAX_PATTERN_LENGTH = 2048
    /** 每条消息每阶段输入的字符量级上限；超限返回原文 + 可见诊断，不截断 */
    const val MAX_INPUT_CHARS = 64 * 1024
    const val MAX_REPLACEMENTS_PER_RULE = 1000
}

object BoundedRegexEngine {

    private val SUPPORTED_FLAGS = setOf('i', 'm', 's')

    /**
     * 编译一条规则。RE2/J 编译器本身拒绝回溯引用与 lookaround；
     * 本层额外拒绝：超长 pattern、未知 flag、JS 专有构造的显式诊断。
     */
    fun compile(rule: RegexRule): RegexCompileOutcome {
        if (rule.pattern.length > BoundedRegexLimits.MAX_PATTERN_LENGTH) {
            return RegexCompileOutcome.Rejected("pattern 超过 ${BoundedRegexLimits.MAX_PATTERN_LENGTH} 字符上限")
        }
        val unknownFlags = rule.flags - SUPPORTED_FLAGS
        if (unknownFlags.isNotEmpty()) {
            return RegexCompileOutcome.Rejected("不支持的 flags: ${unknownFlags.joinToString("")}")
        }
        // 显式诊断 RE2 不支持的 JS 语义（RE2/J 也会拒绝，这里给出可读原因）
        val jsOnly = listOf("(?=", "(?!", "?<=", "(?<!", "\\1", "\\2", "\\9", "(?<", "\\k<")
        jsOnly.firstOrNull { rule.pattern.contains(it) }?.let {
            return RegexCompileOutcome.Rejected("不支持的回溯/lookaround 语法: $it")
        }
        val re2Flags = buildSet {
            if ('i' in rule.flags) add(Pattern.CASE_INSENSITIVE)
            if ('m' in rule.flags) add(Pattern.MULTILINE)
            if ('s' in rule.flags) add(Pattern.DOTALL)
        }.fold(0) { acc, f -> acc or f }
        return try {
            RegexCompileOutcome.Ok(Pattern.compile(rule.pattern, re2Flags))
        } catch (e: Exception) {
            RegexCompileOutcome.Rejected("正则表达式无效: ${e.message?.take(120) ?: "语法错误"}")
        }
    }

    data class StageReport(
        val appliedRuleIds: List<String>,
        val rejected: List<RegexRuleRejection>,
        val inputOverLimit: Boolean
    )

    /**
     * 对单条消息副本按规则原顺序各应用一次（禁止反复运行到不再变化）。
     * 零长匹配按字符推进防死循环；每规则替换次数有界；超限返回原文与诊断。
     */
    fun applyForStage(
        input: String,
        rules: List<RegexRule>,
        stage: RegexStage,
        compiled: Map<String, Pattern> = emptyMap()
    ): Pair<String, StageReport> {
        if (input.isEmpty() || rules.isEmpty()) {
            return input to StageReport(emptyList(), emptyList(), false)
        }
        val applied = ArrayList<String>()
        val rejected = ArrayList<RegexRuleRejection>()
        val overLimit = input.length > BoundedRegexLimits.MAX_INPUT_CHARS
        if (overLimit) {
            rules.forEach { rejected += RegexRuleRejection(it.id, "输入超过 ${BoundedRegexLimits.MAX_INPUT_CHARS} 字符上限，规则未执行") }
            return input to StageReport(emptyList(), rejected, true)
        }
        var text = input
        for (rule in rules) {
            if (!rule.enabled) continue
            if (stage !in rule.stages) continue
            if (rejected.any { it.ruleId == rule.id }) continue
            val pattern = compiled[rule.id] ?: when (val outcome = compile(rule)) {
                is RegexCompileOutcome.Ok -> outcome.pattern
                is RegexCompileOutcome.Rejected -> {
                    rejected += RegexRuleRejection(rule.id, outcome.reason)
                    continue
                }
            }
            text = replaceBounded(text, pattern, rule)
            applied += rule.id
        }
        return text to StageReport(applied, rejected, false)
    }

    /** replacement 适配层：字面文本、$$、$&、$1…$99；其余按字面处理。 */
    private fun replaceBounded(text: String, pattern: Pattern, rule: RegexRule): String {
        val result = StringBuilder()
        var pos = 0
        var count = 0
        // 显式扫描（窗口子串，RE2/J 无 region API）：零长匹配按一个字符推进，保证有界终止（Spec §8）
        while (pos <= text.length && count < BoundedRegexLimits.MAX_REPLACEMENTS_PER_RULE) {
            val window = text.substring(pos)
            val matcher = pattern.matcher(window)
            if (!matcher.find()) break
            result.append(window, 0, matcher.start())
            result.append(expandReplacement(matcher, rule.replacement))
            count++
            val relEnd = matcher.end()
            val absoluteEnd = pos + relEnd
            pos = if (relEnd == matcher.start()) {
                if (absoluteEnd < text.length) {
                    result.append(text[absoluteEnd])
                    absoluteEnd + 1
                } else {
                    break
                }
            } else {
                absoluteEnd
            }
            if (!rule.globalReplace) break
        }
        if (pos < text.length) result.append(text.substring(pos))
        return result.toString()
    }

    private fun expandReplacement(matcher: Matcher, replacement: String): String {
        if (!replacement.contains('$')) return replacement
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c != '$') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= replacement.length) {
                sb.append('$')
                break
            }
            when (val next = replacement[i + 1]) {
                '$' -> {
                    sb.append('$')
                    i += 2
                }
                '&' -> {
                    sb.append(matcher.group() ?: "")
                    i += 2
                }
                in '0'..'9' -> {
                    var j = i + 1
                    while (j < replacement.length && replacement[j] in '0'..'9') j++
                    // $99 上限：取最长可解析组号（≤ 99）
                    var num = replacement.substring(i + 1, j).toIntOrNull() ?: 0
                    if (num > 99) num = 99
                    val group = runCatching { matcher.group(num.coerceAtMost(matcher.groupCount().coerceAtLeast(0))) }.getOrNull()
                    if (num in 1..99 || num == 0) {
                        sb.append(group ?: "")
                    } else {
                        sb.append("")
                    }
                    i = j
                }
                else -> {
                    sb.append('$')
                    i++
                }
            }
        }
        return sb.toString()
    }
}
