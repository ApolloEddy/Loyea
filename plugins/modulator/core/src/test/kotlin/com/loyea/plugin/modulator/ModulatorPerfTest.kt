package com.loyea.plugin.modulator

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * §11 性能目标（待测项的 JVM/CI 基线）：调制器 process + 有界 Lore 选择。
 * 预热后 ≥1,000 次；单列不含感知/IO。目标 p95 < 2 ms（设备性能在验收报告中
 * 以目标设备实测为准；CI 数字仅作回归基线，不冒充手机表现）。
 */
class ModulatorPerfTest {

    @Test
    fun processAndLoreSelectionP95UnderTwoMillis() {
        val engine = Modulator()
        val rules = CompanionProfileLikeLore.rules
        var at = 0.0
        // 预热
        repeat(200) { n ->
            at += 45.0
            engine.process(
                Observation("warm$n", n.toLong() + 1, at, facts = listOf(Fact("novel_topic", 0.6, "w$n"))),
            )
        }
        val samples = mutableListOf<Long>()
        repeat(1200) { n ->
            at += 45.0
            val start = System.nanoTime()
            val decision = engine.process(
                Observation("p$n", n.toLong() + 1000, at, facts = listOf(Fact("novel_topic", 0.6, "e$n"))),
            )
            selectLore(decision, Context(activeTags = setOf("study")), rules, maxEntries = 6, charBudget = 1600)
            samples.add((System.nanoTime() - start) / 1000)
        }
        val sorted = samples.sorted()
        val p95 = sorted[min((sorted.size * 0.95).toInt(), sorted.size - 1)]
        println("PERF modulator+lore p50=${sorted[sorted.size / 2]}us p95=${p95}us max=${sorted.last()}us")
        assertTrue("p95=${p95}us exceeds 2ms budget", p95 < 2000)
    }
}

private object CompanionProfileLikeLore {
    val rules: List<LoreRule> = listOf(
        LoreRule("loyea.support", "先理解具体处境，再按需要提供帮助。", 30, statesAny = setOf("feeling/compassion"), group = "g"),
        LoreRule("loyea.boundary", "清晰表达边界，继续回应具体问题。", 30, statesAny = setOf("feeling/anger"), group = "g"),
        LoreRule("loyea.shared_joy", "接住这件具体的好事。", 20, statesAny = setOf("feeling/joy"), group = "g"),
        LoreRule("loyea.gratitude", "简短回应善意。", 15, statesAny = setOf("feeling/gratitude"), group = "g"),
        LoreRule("loyea.affection", "自然亲近地回应。", 15, statesAny = setOf("feeling/affection"), group = "g"),
        LoreRule("loyea.task_blocked", "说明实际失败与下一步。", 25, statesAny = setOf("feeling/frustration"), contextAll = setOf("host:task_blocked"), group = "t"),
        LoreRule("loyea.humor", "可轻量接梗。", 10, statesAny = setOf("feeling/amusement"), group = "g"),
        LoreRule("loyea.repair", "针对已确认的修复缓和语气。", 10, statesAny = setOf("feeling/relief"), group = "g"),
    )
}
