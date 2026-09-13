package com.loyea.plugin.modulator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.abs

/**
 * v1.1.0 golden 回放 + 旧版差异有界验证。
 *
 * 1. replayAllSequencesWithinTolerance：回放 golden_cases_v1.1.json 的 7 组、50 次观测；
 *    连续量绝对误差 ≤1e-10，状态表、证据分量与审计文本完全一致。
 * 2. legacyFixtureDivergenceIsBounded：把 v1.0 fixture 的同一批输入重放在新引擎上，
 *    断言除 legacy_divergences 登记的字段外，其余输出与 v1.0 期望完全一致——
 *    即“归因修订只影响登记过的序列，其余数学保持原义”由测试本身证明。
 */
class GoldenCasesTest {

    private fun load(path: String): JsonObject {
        val stream = GoldenCasesTest::class.java.getResourceAsStream(path)
            ?: throw AssertionError("$path not found in resources")
        return JsonParser.parseString(stream.readBytes().decodeToString()).asJsonObject
    }

    private val cases: JsonObject by lazy { load("/integration/golden_cases_v1.1.json") }
    private val legacyCases: JsonObject by lazy { load("/integration/golden_cases.json") }

    @Test
    fun replayAllSequencesWithinTolerance() {
        assertEquals("1.1.0", cases.get("version").asString)
        val tolerance = cases.get("numeric_tolerance").asDouble
        var total = 0
        val sequences = cases.getAsJsonArray("sequences")
        assertEquals(7, sequences.size())
        for (seqEl in sequences) {
            val seq = seqEl.asJsonObject
            val name = seq.get("name").asString
            val engine = Modulator(personality(seq.getAsJsonObject("initial_personality")), seq.get("initial_at").asDouble)
            for (stepEl in seq.getAsJsonArray("steps")) {
                val step = stepEl.asJsonObject
                val obs = Wire.observation(step.getAsJsonObject("input"))
                val decision = engine.process(obs)
                val expected = step.getAsJsonObject("expected")
                val where = "$name step=${obs.seq}"

                val expectedRows = expected.getAsJsonArray("rows")
                assertEquals("$where rows size", expectedRows.size().toLong(), decision.rows.size.toLong())
                for (i in 0 until expectedRows.size()) {
                    val er = expectedRows.get(i).asJsonObject
                    val row = decision.rows[i]
                    assertEquals("$where row$i aspect", er.get("aspect").asString, row.aspect)
                    assertEquals("$where row$i state", er.get("state").asString, row.state)
                    assertEquals("$where row$i intensity", er.get("intensity").asString, row.intensity)
                }

                val expectedFast = expected.getAsJsonArray("fast")
                val expectedMood = expected.getAsJsonArray("mood")
                for (j in 0 until 4) {
                    assertEquals("$where fast[$j]", expectedFast.get(j).asDouble, engine.state.fast[j], tolerance)
                    assertEquals("$where mood[$j]", expectedMood.get(j).asDouble, engine.state.mood[j], tolerance)
                }

                val expectedTraces = expected.getAsJsonArray("traces")
                assertEquals("$where traces size", expectedTraces.size().toLong(), engine.state.traces.size.toLong())
                for (k in 0 until expectedTraces.size()) {
                    val et = expectedTraces.get(k).asJsonObject
                    val at = engine.state.traces[k]
                    assertEquals("$where trace$k kind", et.get("kind").asString, at.kind)
                    assertEquals("$where trace$k target", et.get("target").asString, at.target)
                    assertEquals("$where trace$k topic", et.get("topic_id").asString, at.topicId)
                    assertEquals("$where trace$k halfLife", et.get("half_life").asDouble, at.halfLife, tolerance)
                    assertEquals("$where trace$k strength", et.get("strength").asDouble, at.strength, tolerance)
                    val expectedComponents = et.getAsJsonArray("components")
                    assertEquals(
                        "$where trace$k components size",
                        expectedComponents.size().toLong(),
                        at.components.size.toLong(),
                    )
                    for (c in 0 until expectedComponents.size()) {
                        val ec = expectedComponents.get(c).asJsonObject
                        val comp = at.components[c]
                        assertEquals("$where trace$k component$c id", ec.get("evidence_id").asString, comp.evidenceId)
                        assertEquals("$where trace$k component$c strength", ec.get("strength").asDouble, comp.strength, tolerance)
                        assertEquals("$where trace$k component$c at", ec.get("last_event_at").asDouble, comp.lastEventAt, tolerance)
                    }
                }

                val expectedAudit = expected.getAsJsonArray("audit").map { it.asString }
                assertEquals("$where audit", expectedAudit, decision.audit)
                total++
            }
        }
        assertEquals(50, total)
    }

    /**
     * 差异有界：新引擎重放 v1.0 输入，除登记字段外必须逐位复现 v1.0 期望。
     */
    @Test
    fun legacyFixtureDivergenceIsBounded() {
        assertEquals("1.0.0-prototype", legacyCases.get("version").asString)
        val tolerance = legacyCases.get("numeric_tolerance").asDouble
        val registry = HashMap<String, Set<String>>()
        for (el in cases.getAsJsonArray("legacy_divergences")) {
            val d = el.asJsonObject
            val fields = HashMap<String, MutableSet<String>>()
            for (stepEl in d.getAsJsonArray("divergentSteps")) {
                val parts = stepEl.asString.split(":", limit = 2)
                fields.getOrPut(parts[0]) { mutableSetOf() }.addAll(parts[1].split(","))
            }
            registry[d.get("sequence").asString] = fields.entries.flatMap { (step, fs) -> fs.map { "$step|$it" } }.toSet()
        }

        for (seqEl in legacyCases.getAsJsonArray("sequences")) {
            val seq = seqEl.asJsonObject
            val name = seq.get("name").asString
            val engine = Modulator(personality(seq.getAsJsonObject("initial_personality")), seq.get("initial_at").asDouble)
            for (stepEl in seq.getAsJsonArray("steps")) {
                val step = stepEl.asJsonObject
                val obs = Wire.observation(step.getAsJsonObject("input").deepCopy())
                val decision = engine.process(obs)
                val expected = step.getAsJsonObject("expected")
                val exempt = registry[name]?.orElseEmpty()?.filter { it.startsWith("step=${obs.seq}|") }
                    ?.map { it.substringAfter('|') }?.toSet() ?: emptySet()
                val where = "$name step=${obs.seq}"

                if ("rows" !in exempt) {
                    val expectedRows = expected.getAsJsonArray("rows")
                    assertEquals("$where rows size", expectedRows.size(), decision.rows.size)
                    for (i in 0 until expectedRows.size()) {
                        val er = expectedRows.get(i).asJsonObject
                        assertEquals("$where row$i", listOf(er.get("aspect").asString, er.get("state").asString, er.get("intensity").asString),
                            listOf(decision.rows[i].aspect, decision.rows[i].state, decision.rows[i].intensity))
                    }
                }
                if ("fast" !in exempt) {
                    for (j in 0 until 4) {
                        assertEquals("$where fast[$j]", expected.getAsJsonArray("fast").get(j).asDouble, engine.state.fast[j], tolerance)
                    }
                }
                if ("mood" !in exempt) {
                    for (j in 0 until 4) {
                        assertEquals("$where mood[$j]", expected.getAsJsonArray("mood").get(j).asDouble, engine.state.mood[j], tolerance)
                    }
                }
                if ("audit" !in exempt) {
                    assertEquals("$where audit", expected.getAsJsonArray("audit").map { it.asString }, decision.audit)
                }
                if ("traces" !in exempt && "traces.strength" !in exempt) {
                    assertTracesMatchExact("$where", expected.getAsJsonArray("traces"), engine.state.traces, tolerance)
                } else if ("traces.strength" in exempt && "traces" !in exempt) {
                    // 结构与证据归属保持一致，仅组强度允许不同
                    val expectedTraces = expected.getAsJsonArray("traces")
                    assertEquals("$where traces size", expectedTraces.size(), engine.state.traces.size)
                    for (k in 0 until expectedTraces.size()) {
                        val et = expectedTraces.get(k).asJsonObject
                        val at = engine.state.traces[k]
                        assertEquals("$where trace$k kind", et.get("kind").asString, at.kind)
                        assertEquals(
                            "$where trace$k evidence ids",
                            et.getAsJsonArray("evidence_ids").map { it.asString },
                            at.evidenceIds(),
                        )
                    }
                }
            }
        }
    }

    /** v1.1.0 归因语义的定向断言（验收矩阵 A13/A14/A15 的核心层部分）。 */
    @Test
    fun attributionRevisionProperties() {
        // A13：同话题威胁 A/B，显式只解决 A —— A 分量衰减，B 分量不受影响。
        val m = Modulator()
        m.process(Observation("u1", 1, 0.0, facts = listOf(fact("future_threat", evidence = "threatA"))))
        m.process(Observation("u2", 2, 1.0, facts = listOf(fact("future_threat", evidence = "threatB"))))
        val before = m.state.traces.single { it.kind == "future_threat" }.components.associate { it.evidenceId to it.strength }
        m.process(
            Observation(
                "u3", 3, 2.0,
                facts = listOf(fact("threat_resolved", evidence = "done", resolves = listOf("threatA"))),
            ),
        )
        val after = m.state.traces.single { it.kind == "future_threat" }.components.associate { it.evidenceId to it.strength }
        assertTrue(after.getValue("threatA") < before.getValue("threatA"))
        // B 不受修复影响，只按 1 秒经过时间独立衰减（halfLife=360）。
        val decay = exp(-ln(2.0) * 1.0 / 360.0)
        assertEquals(before.getValue("threatB") * decay, after.getValue("threatB"), 1e-12)

        // A14：强证据 A 隐藏、弱证据 B 可见 —— 感受强度只由 B 支持。
        // 第二次事件放在不应期（45s）之外，避免不应期压制本例要考察的弱分量。
        val m2 = Modulator()
        m2.process(Observation("s1", 1, 0.0, facts = listOf(fact("future_threat", 0.9, "strongA"))))
        m2.process(Observation("s2", 2, 60.0, facts = listOf(fact("future_threat", 0.20, "weakB"))))
        val hidden = m2.project(Context(), visibleEvidence = setOf("weakB"))
        val worry = hidden.rows.first { it.aspect == "feeling" && it.state == "worry" }
        val visibleOnly = hidden.sources.getValue("feeling/worry")
        assertTrue("weakB" in visibleOnly)
        assertTrue("strongA" !in visibleOnly)
        // 强度档位只可能来自弱证据（0.20 → mild，而不是隐藏强证据的 strong）。
        assertEquals("mild", worry.intensity)

        // A15：同一状态投影十次 —— 正式状态与输出完全不变。
        val m3 = Modulator()
        m3.process(Observation("p1", 1, 0.0, perception = perception("sadness", target = "self")))
        val dumpBefore = m3.dumps()
        val first = m3.project(Context(topicId = "general"), visibleEvidence = setOf("p1"))
        repeat(10) {
            val again = m3.project(Context(topicId = "general"), visibleEvidence = setOf("p1"))
            assertEquals(first.rows, again.rows)
            assertEquals(first.sources, again.sources)
        }
        assertEquals(dumpBefore, m3.dumps())
        assertEquals(1L, m3.state.lastSeq) // 纯投影未推进 seq
    }

    private fun assertTracesMatchExact(where: String, expected: com.google.gson.JsonArray, actual: List<Trace>, tolerance: Double) {
        assertEquals("$where traces size", expected.size(), actual.size)
        for (k in 0 until expected.size()) {
            val et = expected.get(k).asJsonObject
            val at = actual[k]
            assertEquals("$where trace$k kind", et.get("kind").asString, at.kind)
            assertEquals("$where trace$k target", et.get("target").asString, at.target)
            assertEquals("$where trace$k topic", et.get("topic_id").asString, at.topicId)
            assertEquals("$where trace$k halfLife", et.get("half_life").asDouble, at.halfLife, tolerance)
            assertEquals("$where trace$k strength", et.get("strength").asDouble, at.strength, tolerance)
            assertEquals(
                "$where trace$k evidence",
                et.getAsJsonArray("evidence_ids").map { it.asString },
                at.evidenceIds(),
            )
            assertEquals("$where trace$k lastEventAt", et.get("last_event_at").asDouble, at.lastEventAt, tolerance)
        }
    }

    private fun personality(meta: JsonObject): Personality = Personality(
        openness = meta.get("openness").asDouble,
        conscientiousness = meta.get("conscientiousness").asDouble,
        extraversion = meta.get("extraversion").asDouble,
        agreeableness = meta.get("agreeableness").asDouble,
        neuroticism = meta.get("neuroticism").asDouble,
        plasticity = meta.get("plasticity").asDouble,
        totalInteractions = meta.get("total_interactions").asInt,
        profileVersion = meta.get("profile_version").asString,
    )
}

private fun <T> Set<T>?.orElseEmpty(): Set<T> = this ?: emptySet()
