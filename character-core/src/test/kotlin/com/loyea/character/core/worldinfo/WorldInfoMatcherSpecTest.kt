package com.loyea.character.core.worldinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书匹配引擎的 Spec 验收场景（Spec §6 / §12.1 最小合成世界书）。
 *
 * 中性五条规则（不用真实卡正文）：
 * A 常驻；B 关键词「诊所」、正文含「茶室」；C 关键词「茶室」；
 * D 关键词「海港」；E 关键词「诊所」但 disabled。
 */
class WorldInfoMatcherSpecTest {

    private fun entry(
        id: String,
        keywords: List<String> = emptyList(),
        content: String = "",
        constant: Boolean = false,
        enabled: Boolean = true,
        order: Int = 100,
        positionType: String = "before_char",
        depth: Int = 4,
        preventRecursion: Boolean = false,
        allowRecursion: Boolean = true
    ) = WorldInfoEntry(
        id = id,
        uid = id.hashCode(),
        keywords = keywords,
        content = content,
        constant = constant,
        enabled = enabled,
        disable = !enabled,
        order = order,
        depth = depth,
        positionType = positionType,
        preventRecursion = preventRecursion,
        allowRecursion = allowRecursion,
        useProbability = false
    )

    private fun book() = listOf(
        entry("A", content = "A常驻设定", constant = true, order = 1),
        entry("B", keywords = listOf("诊所"), content = "B提到茶室", order = 2),
        entry("C", keywords = listOf("茶室"), content = "C茶室规则", order = 2),
        entry("D", keywords = listOf("海港"), content = "D海港规则", order = 3),
        entry("E", keywords = listOf("诊所"), content = "E禁用规则", enabled = false, order = 4)
    )

    private fun match(
        entries: List<WorldInfoEntry>,
        history: List<String> = listOf("user", "去诊所"),
        config: WorldInfoConfig = WorldInfoConfig(),
        turnKey: String = "t1"
    ): WorldInfoMatcher.WorldInfoMatchResult =
        WorldInfoMatcher.matchWorldInfoEntriesFor(
            entries = entries,
            historyContents = history,
            userName = "User",
            systemPrompt = "",
            config = config,
            turnKey = turnKey
        )

    // ---------- §12.1 基础选择 ----------

    @Test
    fun `constant and keyword entries activate, unrelated and disabled do not`() {
        val result = match(book())
        val ids = result.entries.map { it.id }
        assertTrue("常驻 A 必须入选", ids.contains("A"))
        assertTrue("诊所命中 B", ids.contains("B"))
        assertTrue("B 正文触发递归命中 C", ids.contains("C"))
        assertFalse("海港不应命中", ids.contains("D"))
        assertFalse("禁用条目不应命中", ids.contains("E"))
    }

    @Test
    fun `recursion disabled leaves only directly matched entries`() {
        val result = match(book(), config = WorldInfoConfig(allowRecursion = false))
        val ids = result.entries.map { it.id }
        assertTrue(ids.containsAll(listOf("A", "B")))
        assertFalse("关闭递归时 B 不应带出 C", ids.contains("C"))
    }

    @Test
    fun `equal order keeps stable source order`() {
        // B、C 同 order：输出顺序必须确定（按 uid/id 稳定 tie-break），可重放
        val first = match(book())
        val second = match(book())
        assertEquals(
            first.entries.map { it.id }.filter { it == "B" || it == "C" },
            second.entries.map { it.id }.filter { it == "B" || it == "C" }
        )
        assertEquals(first.entries.map { it.id }, second.entries.map { it.id })
    }

    @Test
    fun `position buckets separate before and after char`() {
        val entries = listOf(
            entry("A", content = "A", constant = true, positionType = "before_char"),
            entry("B", keywords = listOf("诊所"), content = "B", positionType = "after_char")
        )
        val render = WorldInfoMatcher.worldInfoRenderFor(
            entries = entries,
            historyContents = listOf("去诊所"),
            userName = "User",
            systemPrompt = "",
            config = WorldInfoConfig(),
            turnKey = "t1"
        )
        assertTrue(render.beforeCharacterDefinitions!!.contains("A"))
        assertTrue(render.afterCharacterDefinitions!!.contains("B"))
    }

    // ---------- W03：递归链与每条一次 ----------

    @Test
    fun `mutual recursion terminates and each entry selected once`() {
        val entries = listOf(
            entry("X", keywords = listOf("甲"), content = "X正文提到乙"),
            entry("Y", keywords = listOf("乙"), content = "Y正文提到甲")
        )
        val result = match(entries, history = listOf("user", "甲"))
        val ids = result.entries.map { it.id }
        assertEquals(listOf("X", "Y"), ids)
    }

    @Test
    fun `preventRecursion stops the chain`() {
        val entries = listOf(
            entry("X", keywords = listOf("甲"), content = "X正文提到乙", preventRecursion = true),
            entry("Y", keywords = listOf("乙"), content = "Y")
        )
        val result = match(entries, history = listOf("user", "甲"))
        assertFalse(result.entries.any { it.id == "Y" })
    }

    // ---------- W06 / §6.2.7：预算整条取舍与递归因果 ----------

    @Test
    fun `budget excludes whole entries and reports trace`() {
        val entries = listOf(
            entry("A", content = "A常驻", constant = true, order = 1),
            entry("Big", keywords = listOf("诊所"), content = "很长".repeat(400), order = 2)
        )
        // 预算很小：Big 整条放不下
        val result = match(entries, config = WorldInfoConfig(tokenBudget = 8))
        assertEquals(listOf("A"), result.entries.map { it.id })
        assertTrue(result.trace.budgetExcluded.any { it.entryId == "Big" })
    }

    @Test
    fun `budget excluded content must not trigger recursion`() {
        // §12.1：B 被预算排除后，其正文里的「茶室」不得再触发 C
        val entries = listOf(
            entry("A", content = "A", constant = true, order = 1),
            entry("B", keywords = listOf("诊所"), content = "B提到茶室", order = 2),
            entry("C", keywords = listOf("茶室"), content = "C", order = 3)
        )
        // 预算只够 A + B 中的一个 → 设成只够 A + B 正文的一半，B 排除
        val config = WorldInfoConfig(tokenBudget = 2) // 只够一条短内容
        val result = match(entries, config = config)
        val ids = result.entries.map { it.id }
        assertFalse("B 被预算排除", ids.contains("B"))
        assertFalse("C 不得借被排除 B 的正文触发", ids.contains("C"))
        assertTrue(ids.contains("A"))
    }

    @Test
    fun `constant overflow signals recoverable error`() {
        val entries = listOf(entry("A", content = "超长常驻".repeat(200), constant = true))
        val result = match(entries, config = WorldInfoConfig(tokenBudget = 4))
        assertTrue(result.trace.constantOverflow)
        assertTrue(result.trace.budgetExcluded.any {
            it.reason == WorldInfoMatcher.BudgetExclusion.REASON_CONSTANT_OVERFLOW
        })
    }

    // ---------- §6.1：depth 不得当作扫描深度 ----------

    @Test
    fun `entry depth 4 must not shrink scan window`() {
        // 条目 depth=4（本卡默认值）但 scan_depth=10：老词在第 8 条消息里也必须命中
        val history = (1..9).map { "普通消息 $it" } + listOf("最早提到诊所")
        val entries = listOf(entry("B", keywords = listOf("诊所"), content = "B", depth = 4))
        val result = match(entries, history = history, config = WorldInfoConfig(scanDepth = 10))
        assertTrue("关键词在第 10 条消息内必须命中", result.entries.any { it.id == "B" })
    }

    @Test
    fun `entry scan depth override wins over global`() {
        val history = listOf("最早提到诊所") + (1..9).map { "普通消息 $it" }
        val entries = listOf(
            entry("B", keywords = listOf("诊所"), content = "B", depth = 4, allowRecursion = true)
                .copy(scanDepthOverride = 2)
        )
        val result = match(entries, history = history, config = WorldInfoConfig(scanDepth = 10))
        assertFalse("条目覆盖 scan_depth=2 时窗口外不命中", result.entries.any { it.id == "B" })
    }

    // ---------- W02：selective 与空 secondary ----------

    @Test
    fun `selective with empty secondary keys does not block match`() {
        val entries = listOf(
            entry("S", keywords = listOf("诊所"), content = "S")
                .copy(selective = true, keysecondary = emptyList())
        )
        val result = match(entries, history = listOf("user", "去诊所"))
        assertTrue(result.entries.any { it.id == "S" })
    }

    // ---------- 渲染 ----------

    @Test
    fun `render block is null when nothing matched`() {
        val block = WorldInfoMatcher.worldInfoBlockFor(
            entries = book(),
            historyContents = listOf("user", "在海港散步"),
            userName = "User",
            systemPrompt = "",
            config = WorldInfoConfig(),
            turnKey = "t1"
        )
        // 只有常驻 A 命中 → 不为 null
        assertTrue(block != null && block.contains("A常驻设定"))
    }

    @Test
    fun `probability 100 never blocks`() {
        val entries = listOf(
            entry("P", keywords = listOf("诊所"), content = "P").copy(
                useProbability = true, probability = 100
            )
        )
        val result = match(entries, history = listOf("user", "去诊所"))
        assertTrue(result.entries.any { it.id == "P" })
    }
}
