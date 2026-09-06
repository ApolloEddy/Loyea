package com.loyea.character.core.regex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有限正则引擎与导入映射测试（Spec §8 / 验收矩阵 R01-R04）。
 */
class BoundedRegexEngineTest {

    private fun rule(
        id: String,
        pattern: String,
        replacement: String = "",
        stages: Set<RegexStage> = RegexStage.values().toSet(),
        flags: Set<Char> = emptySet(),
        globalReplace: Boolean = true
    ) = RegexRule(id, pattern, replacement, stages, flags, globalReplace)

    private fun apply(
        text: String,
        rules: List<RegexRule>,
        stage: RegexStage
    ): Pair<String, BoundedRegexEngine.StageReport> =
        BoundedRegexEngine.applyForStage(text, rules, stage)

    // ---------- R01 / R02：阶段隔离 ----------

    @Test
    fun `display rule changes display copy only`() {
        val r = rule("d1", pattern = "\\[隐藏标记]", stages = setOf(RegexStage.DISPLAY_ASSISTANT))
        val raw = "正文[隐藏标记]结尾"
        val (displayOut, _) = apply(raw, listOf(r), RegexStage.DISPLAY_ASSISTANT)
        val (promptOut, _) = apply(raw, listOf(r), RegexStage.PROMPT_ASSISTANT)
        assertEquals("正文结尾", displayOut)
        assertEquals("raw 副本在 prompt 阶段不受显示规则影响", raw, promptOut)
        // 原文（raw 字段）由调用方持有，引擎从不写回——display 改变不改变 raw/provider history
    }

    @Test
    fun `prompt rule affects only its stage`() {
        val userRule = rule("u1", pattern = "敏感词", replacement = "***", stages = setOf(RegexStage.PROMPT_USER))
        val aiRule = rule("a1", pattern = "META:", replacement = "", stages = setOf(RegexStage.PROMPT_ASSISTANT))
        val userCopy = "用户说了敏感词"
        val aiCopy = "META:AI 输出"
        val (uOut, _) = apply(userCopy, listOf(userRule, aiRule), RegexStage.PROMPT_USER)
        val (aOut, _) = apply(aiCopy, listOf(userRule, aiRule), RegexStage.PROMPT_ASSISTANT)
        assertEquals("用户说了***", uOut)
        assertEquals("AI 输出", aOut)
    }

    @Test
    fun `system and tool payloads never enter the engine`() {
        // 引擎只处理调用方传入的单条消息副本；system/工具 JSON 不经过本 API（契约由类型保证），
        // 这里锁定：规则即使匹配到 system 样式文本，也只在显式传入时生效
        val r = rule("s1", pattern = "system", replacement = "X", stages = setOf(RegexStage.PROMPT_USER))
        val (out, _) = apply("tool system json", listOf(r), RegexStage.PROMPT_ASSISTANT)
        assertEquals("非目标阶段不执行", "tool system json", out)
    }

    // ---------- R03：拒绝语义 ----------

    @Test
    fun `lookaround backreference unknown flags and invalid patterns reject whole rule`() {
        val cases = listOf(
            rule("r-look", pattern = "foo(?=bar)"),
            rule("r-backref", pattern = "(\\w)\\1"),
            rule("r-flag", pattern = "abc", flags = setOf('x', 'i'))
        )
        cases.forEach { r ->
            val report = BoundedRegexEngine.compile(r)
            assertTrue("${r.id} 应拒绝", report is RegexCompileOutcome.Rejected)
        }
        // 无效表达式：拒绝且不崩溃
        val bad = BoundedRegexEngine.compile(rule("r-bad", pattern = "[unclosed"))
        assertTrue(bad is RegexCompileOutcome.Rejected)
        // 拒绝后原文保留（配置不执行、不扩大范围）
        val (out, report) = apply("foo(bar)", cases, RegexStage.PROMPT_USER)
        assertEquals("foo(bar)", out)
        assertEquals(cases.size, report.rejected.size)
    }

    // ---------- R04：有界性 ----------

    @Test
    fun `zero length matches advance and terminate`() {
        val r = rule("z", pattern = "x*", replacement = "-", globalReplace = true)
        val (out, report) = apply("bab", listOf(r), RegexStage.PROMPT_USER)
        assertTrue("有界终止", report.appliedRuleIds.contains("z"))
        // 空匹配逐字符推进：每位置一个 "-"，跳过的字面字符保留
        assertEquals("-b-a-b-", out)
    }

    @Test
    fun `oversized input returns original with visible diagnostic`() {
        val big = "a".repeat(BoundedRegexLimits.MAX_INPUT_CHARS + 1)
        val r = rule("big", pattern = "a+", replacement = "b")
        val (out, report) = apply(big, listOf(r), RegexStage.PROMPT_USER)
        assertEquals("超限不截断原文", big, out)
        assertTrue(report.inputOverLimit)
        assertTrue(report.rejected.isNotEmpty())
    }

    @Test
    fun `replacement count is bounded per rule`() {
        val big = "a".repeat(BoundedRegexLimits.MAX_REPLACEMENTS_PER_RULE + 500)
        val r = rule("many", pattern = "a", replacement = "b")
        val (out, _) = apply(big, listOf(r), RegexStage.PROMPT_USER)
        assertEquals(
            BoundedRegexLimits.MAX_REPLACEMENTS_PER_RULE,
            out.count { it == 'b' }
        )
    }

    @Test
    fun `replacement supports literal dollar and group refs`() {
        val r = rule("g", pattern = "(\\d+)x(\\d+)", replacement = "$2x$1 $$ $&")
        val (out, _) = apply("3x4", listOf(r), RegexStage.PROMPT_USER)
        assertEquals("4x3 $ 3x4", out)
    }

    @Test
    fun `rules apply once in original order`() {
        val r1 = rule("1", pattern = "aa", replacement = "b")
        val r2 = rule("2", pattern = "bb", replacement = "c")
        val (out, report) = apply("aaaa", listOf(r1, r2), RegexStage.PROMPT_USER)
        // "aaaa" →(aa→b)→ "bb" →(bb→c)→ "c"（整串匹配一次）
        assertEquals("c", out)
        assertEquals(listOf("1", "2"), report.appliedRuleIds)
    }

    // ---------- 导入映射 ----------

    @Test
    fun `st regex scripts map to whitelisted stages`() {
        val json = """
            {"regex_scripts":[
              {"id":"s1","scriptName":"显示去标记","findRegex":"/\\[TAG\\]/g","replaceString":"",
               "placement":[2],"markdownOnly":true},
              {"id":"s2","findRegex":"用户原文","replaceString":"提示词版","placement":[1],"promptOnly":true},
              {"id":"s3","findRegex":"双方","replaceString":"x","placement":[2]}
            ]}
        """.trimIndent()
        val outcome = RegexScriptAdapter.fromExtensionsJson(json)
        assertEquals(3, outcome.rules.size)
        val s1 = outcome.rules.first { it.id == "s1" }
        assertTrue(s1.stages.contains(RegexStage.DISPLAY_ASSISTANT))
        assertTrue(!s1.stages.contains(RegexStage.PROMPT_ASSISTANT))
        assertTrue(s1.globalReplace)
        val s2 = outcome.rules.first { it.id == "s2" }
        assertTrue(s2.stages.contains(RegexStage.PROMPT_USER))
        val s3 = outcome.rules.first { it.id == "s3" }
        assertTrue(s3.stages.containsAll(setOf(RegexStage.PROMPT_ASSISTANT, RegexStage.DISPLAY_ASSISTANT)))
    }

    @Test
    fun `unsupported script features reject whole rule`() {
        val json = """
            {"regex_scripts":[
              {"id":"depth","findRegex":"a","replaceString":"","placement":[2],"minDepth":2},
              {"id":"edit","findRegex":"a","replaceString":"","placement":[2],"runOnEdit":true},
              {"id":"trim","findRegex":"a","replaceString":"","placement":[2],"trimStrings":["x"]},
              {"id":"macro","findRegex":"a","replaceString":"","placement":[2],"substituteRegex":true},
              {"id":"disabled","findRegex":"a","replaceString":"","placement":[2],"disabled":true},
              {"id":"ok","findRegex":"a","replaceString":"","placement":[2]}
            ]}
        """.trimIndent()
        val outcome = RegexScriptAdapter.fromExtensionsJson(json)
        assertEquals(1, outcome.rules.size)
        assertEquals(5, outcome.rejections.size)
        assertTrue(outcome.rules.single().rawJson != null)
    }

    @Test
    fun `rule count cap is enforced`() {
        val scripts = (1..40).joinToString(",") { i ->
            """{"id":"r$i","findRegex":"a$i","replaceString":"","placement":[2]}"""
        }
        val outcome = RegexScriptAdapter.fromExtensionsJson("""{"regex_scripts":[$scripts]}""")
        assertEquals(BoundedRegexLimits.MAX_RULES_PER_CHARACTER, outcome.rules.size)
        assertEquals(8, outcome.rejections.size)
    }
}
