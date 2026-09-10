package com.loyea.plugin.modulator

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨语言移植对照：回放 integration/golden_cases.json 的 7 组、50 次观测。
 * 连续量与痕迹强度绝对误差 ≤1e-10；状态表、痕迹依据与审计文本完全一致。
 */
class GoldenCasesTest {

    private val cases: JsonObject by lazy {
        val stream = GoldenCasesTest::class.java.getResourceAsStream("/integration/golden_cases.json")
            ?: throw AssertionError("golden_cases.json not found in resources")
        JsonParser.parseString(stream.readBytes().decodeToString()).asJsonObject
    }

    @Test
    fun replayAllSequencesWithinTolerance() {
        assertEquals("1.0.0-prototype", cases.get("version").asString)
        val tolerance = cases.get("numeric_tolerance").asDouble
        var total = 0
        val sequences = cases.getAsJsonArray("sequences")
        assertEquals(7, sequences.size())
        for (seqEl in sequences) {
            val seq = seqEl.asJsonObject
            val name = seq.get("name").asString
            val meta = seq.getAsJsonObject("initial_personality")
            val personality = Personality(
                openness = meta.get("openness").asDouble,
                conscientiousness = meta.get("conscientiousness").asDouble,
                extraversion = meta.get("extraversion").asDouble,
                agreeableness = meta.get("agreeableness").asDouble,
                neuroticism = meta.get("neuroticism").asDouble,
                plasticity = meta.get("plasticity").asDouble,
                totalInteractions = meta.get("total_interactions").asInt,
                profileVersion = meta.get("profile_version").asString,
            )
            val engine = Modulator(personality, seq.get("initial_at").asDouble)
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
                    assertEquals(
                        "$where trace$k evidence",
                        et.getAsJsonArray("evidence_ids").map { it.asString },
                        at.evidenceIds,
                    )
                    assertEquals("$where trace$k lastEventAt", et.get("last_event_at").asDouble, at.lastEventAt, tolerance)
                }

                val expectedAudit = expected.getAsJsonArray("audit").map { it.asString }
                assertEquals("$where audit", expectedAudit, decision.audit)
                total++
            }
        }
        assertEquals(50, total)
    }
}
