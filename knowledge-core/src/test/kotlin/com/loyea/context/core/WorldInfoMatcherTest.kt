package com.loyea.context.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * WorldInfoMatcher 纯函数单测（JUnit4，无 Android 依赖）。
 * 覆盖 SillyTavern v2 匹配语义：constant / 关键词 / depth 窗口 / keysContainedIn /
 * selective 四种逻辑 / 概率门控 / 分组邻接 / 排序模式 / 预算裁剪 / 递归链。
 */
class WorldInfoMatcherTest {

    private val cfg = WorldInfoConfig()

    private fun entry(
        id: String,
        keywords: List<String> = emptyList(),
        content: String = "",
        enabled: Boolean = true,
        uid: Int = 0,
        keysecondary: List<String> = emptyList(),
        constant: Boolean = false,
        order: Int = 100,
        depth: Int = 4,
        comment: String = "",
        selective: Boolean = false,
        disable: Boolean = false,
        selectiveLogic: Int = 0,
        group: String = "",
        probability: Int = 100,
        useProbability: Boolean = false,
        delayUntilRecursion: Int = 0,
        preventRecursion: Boolean = false,
        allowRecursion: Boolean = true,
        excludeRecursion: Boolean = false,
        keysContainedIn: String = "chat",
        position: Int = 0,
        weight: Int = 0,
        sticky: Int = 0,
        cooldown: Int = 0,
        delay: Int = 0
    ) = WorldInfoEntry(
        id = id, keywords = keywords, content = content, enabled = enabled, uid = uid,
        keysecondary = keysecondary, constant = constant, order = order, depth = depth,
        comment = comment, selective = selective, disable = disable,
        selectiveLogic = selectiveLogic, group = group, probability = probability,
        useProbability = useProbability, delayUntilRecursion = delayUntilRecursion,
        preventRecursion = preventRecursion, allowRecursion = allowRecursion,
        excludeRecursion = excludeRecursion, keysContainedIn = keysContainedIn,
        position = position, weight = weight, sticky = sticky, cooldown = cooldown, delay = delay
    )

    // ---------- 基础触发 ----------

    @Test
    fun constantEntryAlwaysInjected() {
        val e = entry("c1", keywords = listOf("zzz"), content = "Const lore", constant = true)
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(e), emptyList(), "U", "S", cfg, Random(1))
        assertNotNull(out)
        assertTrue(out!!.contains("Const lore"))
    }

    @Test
    fun keywordMatchesCaseInsensitive() {
        val e = entry("k1", keywords = listOf("MagicStone"), content = "Stone lore")
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("the magicstone is here"), "U", "S", cfg, Random(1))
        assertTrue(out!!.contains("Stone lore"))
    }

    @Test
    fun regexAndWholeWordMatchingFollowEntryAndGlobalSettings() {
        val regexEntry = entry("rx", keywords = listOf("/magic\\d+/i"), content = "Regex lore")
            .copy(useRegex = true)
        assertTrue(
            WorldInfoMatcher.worldInfoBlockFor(
                listOf(regexEntry), listOf("MAGIC42"), "U", "S", cfg, Random(1)
            )!!.contains("Regex lore")
        )

        val wholeWordEntry = entry("word", keywords = listOf("cat"), content = "Whole-word lore")
        assertNull(
            WorldInfoMatcher.worldInfoBlockFor(
                listOf(wholeWordEntry), listOf("concatenate"), "U", "S",
                cfg.copy(matchWholeWords = true), Random(1)
            )
        )
        assertTrue(
            WorldInfoMatcher.worldInfoBlockFor(
                listOf(wholeWordEntry), listOf("a cat sleeps"), "U", "S",
                cfg.copy(matchWholeWords = true), Random(1)
            )!!.contains("Whole-word lore")
        )
    }

    @Test
    fun noMatchReturnsNull() {
        val e = entry("n1", keywords = listOf("absent"), content = "X")
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("nothing"), "U", "S", cfg, Random(1)))
        assertNull(WorldInfoMatcher.worldInfoBlockFor(emptyList(), listOf("k"), "U", "S", cfg, Random(1)))
    }

    @Test
    fun disabledEntriesExcluded() {
        val e = entry("dis", keywords = listOf("kw"), content = "D", enabled = false)
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("kw"), "U", "S", cfg, Random(1)))
    }

    // ---------- depth 窗口 ----------

    @Test
    fun depthWindowLimitsChatScan() {
        val hist = listOf("ancient msg0", "msg1", "msg2")
        val e1 = entry("d1", keywords = listOf("ancient"), content = "A", depth = 1)
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e1), hist, "U", "S", cfg, Random(1)))
        val e2 = entry("d2", keywords = listOf("ancient"), content = "B", depth = 4)
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e2), hist, "U", "S", cfg, Random(1))!!.contains("B"))
        // depth=0 → 回退全局 scanDepth
        val e0 = entry("d0", keywords = listOf("ancient"), content = "C", depth = 0)
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e0), hist, "U", "S", cfg, Random(1))!!.contains("C"))
    }

    // ---------- keysContainedIn 来源 ----------

    @Test
    fun keysContainedInSystemSource() {
        val e1 = entry("s1", keywords = listOf("STARTOFWORLD"), content = "sys", keysContainedIn = "system")
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e1), listOf("chat text"), "U", "STARTOFWORLD here", cfg, Random(1))!!.contains("sys"))
        val e2 = entry("s2", keywords = listOf("STARTOFWORLD"), content = "chatOnly", keysContainedIn = "chat")
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e2), listOf("chat text"), "U", "STARTOFWORLD here", cfg, Random(1)))
    }

    @Test
    fun keysContainedInUserSource() {
        val e = entry("u1", keywords = listOf("Eddy"), content = "user lore", keysContainedIn = "user")
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("no keywords"), "Eddy", "S", cfg, Random(1))!!.contains("user lore"))
    }

    @Test
    fun characterGlobalScanFlagsMatchCardFields() {
        val e = entry("character", keywords = listOf("archivist"), content = "character lore")
            .copy(matchCharacterDescription = true)
        assertTrue(
            WorldInfoMatcher.worldInfoBlockFor(
                entries = listOf(e),
                historyContents = listOf("unrelated"),
                userName = "U",
                systemPrompt = "S",
                config = cfg,
                random = Random(1),
                characterDescription = "An archivist who guards a library"
            )!!.contains("character lore")
        )
    }

    @Test
    fun keysContainedInWorldSourceRecursion() {
        // A 在聊天命中；B 的关键词在 A 的 content 中，且 B 只扫 world 源
        val a = entry("A", keywords = listOf("surface"), content = "the deep key is nested", order = 1)
        val b = entry("B", keywords = listOf("deep"), content = "nested lore", order = 2, keysContainedIn = "world")
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(a, b), listOf("surface"), "U", "S", cfg, Random(1))
        assertTrue(out!!.contains("nested lore"))
    }

    // ---------- selective 四种逻辑 ----------

    @Test
    fun selectiveAndAnyRequiresPrimaryAndSecondary() {
        val e = entry("sa", keywords = listOf("PRIMARY"), keysecondary = listOf("secword"), content = "X",
            selective = true, selectiveLogic = WorldInfoMatcher.AND_ANY)
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("secword appears"), "U", "S", cfg, Random(1)))
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("PRIMARY appears"), "U", "S", cfg, Random(1)))
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("PRIMARY and secword"), "U", "S", cfg, Random(1))!!.contains("X"))
    }

    @Test
    fun selectiveNotAll() {
        val e = entry("na", keywords = listOf("primary"), keysecondary = listOf("s1", "s2"), content = "NA",
            selective = true, selectiveLogic = WorldInfoMatcher.NOT_ALL)
        // 全部次词存在 → 不注入
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("primary s1 s2"), "U", "S", cfg, Random(1)))
        // 仅一个次词存在 → 注入
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("primary s1"), "U", "S", cfg, Random(1))!!.contains("NA"))
    }

    @Test
    fun selectiveNotAny() {
        val e = entry("nz", keywords = listOf("primary"), keysecondary = listOf("s1"), content = "NZ",
            selective = true, selectiveLogic = WorldInfoMatcher.NOT_ANY)
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("primary s1"), "U", "S", cfg, Random(1)))
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("primary"), "U", "S", cfg, Random(1))!!.contains("NZ"))
    }

    @Test
    fun selectiveAndAll() {
        val e = entry("aa", keywords = listOf("primary"), keysecondary = listOf("s1", "s2"), content = "AA",
            selective = true, selectiveLogic = WorldInfoMatcher.AND_ALL)
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("primary s1 s2"), "U", "S", cfg, Random(1))!!.contains("AA"))
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("primary s1"), "U", "S", cfg, Random(1)))
    }

    @Test
    fun selectiveDisabledIgnoresSecondary() {
        val e = entry("sf", keywords = listOf("primary"), keysecondary = listOf("s1"), content = "SF",
            selective = false, selectiveLogic = WorldInfoMatcher.AND_ANY)
        // selective=false：忽略次词，主词未命中 → 不注入
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e), listOf("s1"), "U", "S", cfg, Random(1)))
    }

    // ---------- 概率门控 ----------

    @Test
    fun probabilityGating() {
        val e0 = entry("p0", keywords = listOf("kw"), content = "P0", useProbability = true, probability = 0)
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(e0), listOf("kw here"), "U", "S", cfg, Random(42)))
        val e100 = entry("p100", keywords = listOf("kw"), content = "P100", useProbability = true, probability = 100)
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(e100), listOf("kw here"), "U", "S", cfg, Random(42))!!.contains("P100"))
        // useProbability=false → 概率不生效（probability=0 也不拦截）
        val eOff = entry("poff", keywords = listOf("kw"), content = "POFF", useProbability = false, probability = 0)
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(eOff), listOf("kw here"), "U", "S", cfg, Random(42))!!.contains("POFF"))
    }

    @Test
    fun timedStickyCooldownAndDelayAreReconstructedFromHistory() {
        val sticky = entry(
            "sticky", keywords = listOf("trigger"), content = "sticky lore", depth = 1, sticky = 1
        )
        assertTrue(
            WorldInfoMatcher.worldInfoBlockFor(listOf(sticky), listOf("trigger", "quiet"), "U", "S", cfg, Random(1))!!
                .contains("sticky lore")
        )
        assertNull(
            WorldInfoMatcher.worldInfoBlockFor(listOf(sticky), listOf("trigger", "quiet", "quiet"), "U", "S", cfg, Random(1))
        )

        val cooldown = entry(
            "cooldown", keywords = listOf("trigger"), content = "cooldown lore", depth = 1, cooldown = 1
        )
        assertNull(
            WorldInfoMatcher.worldInfoBlockFor(listOf(cooldown), listOf("trigger", "quiet"), "U", "S", cfg, Random(1))
        )

        val delayed = entry(
            "delay", keywords = listOf("never"), content = "delayed lore", constant = true, delay = 2
        )
        assertNull(WorldInfoMatcher.worldInfoBlockFor(listOf(delayed), listOf("first"), "U", "S", cfg, Random(1)))
        assertTrue(
            WorldInfoMatcher.worldInfoBlockFor(listOf(delayed), listOf("first", "second"), "U", "S", cfg, Random(1))!!
                .contains("delayed lore")
        )
    }

    @Test
    fun timedStateIsReturnedAndCanBeReusedAcrossTurns() {
        val sticky = entry(
            "persisted-sticky", keywords = listOf("trigger"), content = "persisted lore",
            depth = 1, sticky = 1
        )
        val first = WorldInfoMatcher.worldInfoRenderFor(
            entries = listOf(sticky),
            historyContents = listOf("trigger"),
            userName = "U",
            systemPrompt = "S",
            config = cfg,
            random = Random(1),
            turnKey = "user-1",
            turnIndex = 1
        )
        assertTrue(first.all!!.contains("persisted lore"))
        assertEquals("user-1", first.runtimeState.turnKey)

        val second = WorldInfoMatcher.worldInfoRenderFor(
            entries = listOf(sticky),
            historyContents = listOf("quiet"),
            userName = "U",
            systemPrompt = "S",
            config = cfg,
            random = Random(1),
            runtimeState = first.runtimeState,
            turnKey = "user-2",
            turnIndex = 2
        )
        assertTrue(second.all!!.contains("persisted lore"))

        val third = WorldInfoMatcher.worldInfoRenderFor(
            entries = listOf(sticky),
            historyContents = listOf("quiet"),
            userName = "U",
            systemPrompt = "S",
            config = cfg,
            random = Random(1),
            runtimeState = second.runtimeState,
            turnKey = "user-3",
            turnIndex = 3
        )
        assertNull(third.all)
    }

    @Test
    fun characterFilterRestrictsWorldInfoToMatchingCard() {
        val entry = WorldInfoEntry(
            id = "filtered",
            keywords = listOf("signal"),
            content = "filtered lore",
            characterFilterNames = listOf("Allowed")
        )
        val config = WorldInfoConfig(tokenBudget = 100)
        assertNull(
            WorldInfoMatcher.worldInfoBlockFor(
                entries = listOf(entry),
                historyContents = listOf("signal"),
                userName = "User",
                systemPrompt = "System",
                config = config,
                characterName = "Other"
            )
        )
        assertTrue(
            WorldInfoMatcher.worldInfoBlockFor(
                entries = listOf(entry),
                historyContents = listOf("signal"),
                userName = "User",
                systemPrompt = "System",
                config = config,
                characterName = "Allowed"
            )!!.contains("filtered lore")
        )
    }

    // ---------- 分组邻接 ----------

    @Test
    fun groupEntriesAreMutuallyExclusiveAndOverrideWins() {
        val a1 = entry("a1", keywords = listOf("k"), content = "A1", order = 200, group = "G")
        val a2 = entry("a2", keywords = listOf("k"), content = "A2", order = 201, group = "G")
            .copy(groupOverride = true)
        val ng = entry("ng", keywords = listOf("k"), content = "NG", order = 100)
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(ng, a1, a2), listOf("k"), "U", "S", cfg, Random(1))!!
        assertTrue(out.contains("NG"))
        assertFalse(out.contains("A1"))
        assertTrue(out.contains("A2"))
    }

    @Test
    fun groupScoringKeepsHighestActivationScoreBeforeWeightedRandom() {
        val stronger = entry(
            "strong",
            keywords = listOf("k1", "k2"),
            content = "strong lore",
            group = "G"
        )
        val weaker = entry(
            "weak",
            keywords = listOf("k1"),
            content = "weak lore",
            group = "G"
        )
        val out = WorldInfoMatcher.worldInfoBlockFor(
            listOf(stronger, weaker),
            listOf("k1 k2"),
            "U",
            "S",
            cfg.copy(useGroupScoring = true),
            Random(1)
        )!!
        assertTrue(out.contains("strong lore"))
        assertFalse(out.contains("weak lore"))
    }

    @Test
    fun groupHeaderEmittedWhenEnabled() {
        val a = entry("a1", keywords = listOf("k"), content = "A1", order = 1, group = "G")
        val cfgH = cfg.copy(emitGroupHeaders = true)
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(a), listOf("k"), "U", "S", cfgH, Random(1))!!
        assertTrue(out.startsWith("# G\n- A1"))
    }

    // ---------- 排序模式 ----------

    @Test
    fun insertionOrderModes() {
        val e1 = entry("k1", keywords = listOf("short"), content = "CCC", order = 100, constant = true)
        val e2 = entry("k2", keywords = listOf("averylongkeywordhere"), content = "AAA", order = 200, constant = true)
        val e3 = entry("k3", keywords = listOf("mid"), content = "BBB", order = 50, constant = true)
        val all = listOf(e1, e2, e3)

        fun orderString(mode: WorldInfoInsertionOrder): String =
            WorldInfoMatcher.worldInfoBlockFor(all, listOf("k"), "U", "S", cfg.copy(insertionOrderMode = mode), Random(1))!!

        // ORDER / INSERT_AT_TOP：order 升序 → BBB, CCC, AAA
        val top = orderString(WorldInfoInsertionOrder.ORDER)
        assertTrue(top.indexOf("BBB") < top.indexOf("CCC") && top.indexOf("CCC") < top.indexOf("AAA"))

        // KEY_LENGTH：首主词长度降序（averylongkeywordhere=19 > short=5 > mid=3）→ AAA, CCC, BBB
        val key = orderString(WorldInfoInsertionOrder.KEY_LENGTH)
        assertTrue(key.indexOf("AAA") < key.indexOf("CCC") && key.indexOf("CCC") < key.indexOf("BBB"))

        // ALPHABETICAL：content 字典序 → AAA, BBB, CCC
        val alpha = orderString(WorldInfoInsertionOrder.ALPHABETICAL)
        assertTrue(alpha.indexOf("AAA") < alpha.indexOf("BBB") && alpha.indexOf("BBB") < alpha.indexOf("CCC"))

        // INSERT_AT_BOTTOM：order 降序 → AAA, CCC, BBB
        val bottom = orderString(WorldInfoInsertionOrder.INSERT_AT_BOTTOM)
        assertTrue(bottom.indexOf("AAA") < bottom.indexOf("CCC") && bottom.indexOf("CCC") < bottom.indexOf("BBB"))
    }

    // ---------- 预算裁剪 ----------

    @Test
    fun tokenBudgetTrims() {
        val e1 = entry("b1", keywords = listOf("k"), content = "Alpha lore one", constant = true, order = 1)
        val e2 = entry("b2", keywords = listOf("k"), content = "Beta lore two", constant = true, order = 2)
        val cfgSmall = cfg.copy(tokenBudget = estimateTavernTokens("Alpha lore one") + 1)
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(e1, e2), listOf("k"), "U", "S", cfgSmall, Random(1))!!
        // ST 的预算优先保留 order 更大的条目，再按输出顺序渲染。
        assertFalse(out.contains("Alpha lore one"))
        assertTrue(out.contains("Beta lore two"))
    }

    // ---------- 递归链 ----------

    @Test
    fun recursionExcludeRecursion() {
        // 递归激活需要目标条目 keysContainedIn 含 "world"（ST 语义：world 源 = 其他条目 content）
        val a = entry("A", keywords = listOf("surface"), content = "The hidden key is in A", order = 1)
        val bExcluded = entry("Bx", keywords = listOf("hidden"), content = "B excluded", order = 2, excludeRecursion = true, keysContainedIn = "world")
        assertFalse(WorldInfoMatcher.worldInfoBlockFor(listOf(a, bExcluded), listOf("surface"), "U", "S", cfg, Random(1))!!.contains("B excluded"))
        val bOk = entry("Bok", keywords = listOf("hidden"), content = "B ok", order = 2, keysContainedIn = "world")
        assertTrue(WorldInfoMatcher.worldInfoBlockFor(listOf(a, bOk), listOf("surface"), "U", "S", cfg, Random(1))!!.contains("B ok"))
    }

    @Test
    fun recursionDelayUntil() {
        val a = entry("A", keywords = listOf("surface"), content = "step1 chain", order = 1)
        val d = entry("D", keywords = listOf("chain"), content = "delayed", order = 2, delayUntilRecursion = 2, keysContainedIn = "world")
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(a, d), listOf("surface"), "U", "S", cfg, Random(1))
        assertTrue(out!!.contains("delayed"))
    }

    @Test
    fun recursionPreventBreaksChain() {
        // A 初始命中；P 的 key 在 A content（递归轮命中，preventRecursion）；X 的 key 只在 P 的 content
        val a = entry("A", keywords = listOf("surface"), content = "has keyP and gamma", order = 1)
        val p = entry("P", keywords = listOf("keyP"), content = "has keyX and omega", order = 2, preventRecursion = true, keysContainedIn = "world")
        val x = entry("X", keywords = listOf("keyX"), content = "X should not appear", order = 3, keysContainedIn = "world")
        val out = WorldInfoMatcher.worldInfoBlockFor(listOf(a, p, x), listOf("surface"), "U", "S", cfg, Random(1))
        assertTrue(out!!.contains("has keyP and gamma")) // A
        assertTrue(out.contains("has keyX and omega"))   // P（递归轮命中）
        assertFalse(out.contains("X should not appear")) // X：链已断，未检查
    }

    @Test
    fun recursionDepthCap() {
        val a = entry("A", keywords = listOf("surface"), content = "has bkey", order = 1)
        val b = entry("B", keywords = listOf("bkey"), content = "has ckey", order = 2, keysContainedIn = "world")
        val c = entry("C", keywords = listOf("ckey"), content = "C appears", order = 3, keysContainedIn = "world")
        val cap1 = cfg.copy(recursionDepthCap = 1)
        val outCap1 = WorldInfoMatcher.worldInfoBlockFor(listOf(a, b, c), listOf("surface"), "U", "S", cap1, Random(1))!!
        assertTrue(outCap1.contains("has bkey"))  // A
        assertTrue(outCap1.contains("has ckey"))  // B（pass1）
        assertFalse(outCap1.contains("C appears")) // C 需要 pass2，被 cap 拦截
        val outFull = WorldInfoMatcher.worldInfoBlockFor(listOf(a, b, c), listOf("surface"), "U", "S", cfg, Random(1))!!
        assertTrue(outFull.contains("C appears"))
    }

    @Test
    fun recursionAllowRecursionFalseEntryNotSource() {
        // A 初始命中但 allowRecursion=false → 其 content 不作为递归来源 → B 不命中
        val a = entry("A", keywords = listOf("surface"), content = "has bkey", order = 1, allowRecursion = false)
        val b = entry("B", keywords = listOf("bkey"), content = "B ok", order = 2, keysContainedIn = "world")
        assertFalse(WorldInfoMatcher.worldInfoBlockFor(listOf(a, b), listOf("surface"), "U", "S", cfg, Random(1))!!.contains("B ok"))
    }

    // ---------- 输出确定性 ----------

    @Test
    fun outputIsDeterministicForSameInput() {
        val entries = listOf(
            entry("x", keywords = listOf("k"), content = "X content", order = 1),
            entry("y", keywords = listOf("k"), content = "Y content", order = 2)
        )
        val r1 = WorldInfoMatcher.worldInfoBlockFor(entries, listOf("k"), "U", "S", cfg, Random(7))
        val r2 = WorldInfoMatcher.worldInfoBlockFor(entries, listOf("k"), "U", "S", cfg, Random(7))
        assertEquals(r1, r2)
    }
}
