package com.loyea.plugin.modulator

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v1.1.0 检查点版本化与旧版迁移（Spec 接入文档 §7.1：LEGACY_TRACE_ATTRIBUTION_RESET）。 */
class CheckpointMigrationTest {

    /** 构造一个合法的 1.0.0-prototype 检查点（旧字段布局）。 */
    private fun legacyCheckpoint(): JsonObject {
        val engine = Modulator(Personality(neuroticism = 0.7, profileVersion = "default-1"), 100.0)
        engine.process(
            Observation(
                "u1", 1, 100.0, Context(topicId = "topic"),
                perception = perception("anger", target = "listener", stances = mapOf("hostile" to 0.95)),
            ),
        )
        val raw = JsonParser.parseString(engine.dumps()).asJsonObject
        raw.add("version", JsonPrimitive("1.0.0-prototype"))
        // 旧版 trace 字段：evidence_ids + 顶层 strength/last_event_at（无 components）。
        val traces = raw.getAsJsonObject("state").getAsJsonArray("traces")
        for (el in traces) {
            val t = el.asJsonObject
            val ids = JsonArray()
            t.getAsJsonArray("components").forEach { c -> ids.add(JsonPrimitive(c.asJsonObject.get("evidence_id").asString)) }
            t.add("evidence_ids", ids)
            t.remove("components")
        }
        return raw
    }

    @Test
    fun legacyCheckpointMigratesWithExplicitReset() {
        val report = Modulator.loadCheckpoint(legacyCheckpoint().toString())
        assertEquals("1.0.0-prototype", report.sourceVersion)
        assertEquals(listOf(ModulatorVocab.MIGRATION_LEGACY_TRACE_ATTRIBUTION_RESET), report.migrations)

        val m = report.modulator
        // 可验证的即时量/背景量与人格保留。
        val d = m.dumps()
        val state = JsonParser.parseString(d).asJsonObject.getAsJsonObject("state")
        assertTrue(state.getAsJsonArray("traces").isEmpty)
        assertTrue(state.getAsJsonObject("label_levels").entrySet().isEmpty())
        assertTrue(state.getAsJsonArray("selected_feelings").isEmpty)
        assertEquals(100.0, state.get("at").asDouble, 0.0)
        assertEquals(1L, state.get("last_seq").asLong)
        assertEquals("u1", state.get("last_event_id").asString)
        // 新检查点版本号已升级；迁移后的引擎可继续推进。
        assertEquals("1.1.0", JsonParser.parseString(d).asJsonObject.get("version").asString)
        val decision = m.process(Observation("u2", 2, 160.0, perception = perception("joy")))
        assertTrue(decision.rows.size in 3..5)
    }

    @Test
    fun migratedEngineHasNoInheritedFeelingFromClearedTraces() {
        val m = Modulator.loadCheckpoint(legacyCheckpoint().toString()).modulator
        // 痕迹已清除：同一话题下的投影不得再出现 anger 感受行。
        val projection = m.project(Context(topicId = "topic"), visibleEvidence = setOf("u1"))
        assertTrue(projection.rows.none { it.aspect == "feeling" })
    }

    @Test
    fun v1_1CheckpointRequiresComponents() {
        val engine = Modulator()
        engine.process(Observation("u1", 1, 0.0, perception = perception("joy")))
        val raw = JsonParser.parseString(engine.dumps()).asJsonObject
        val trace = raw.getAsJsonObject("state").getAsJsonArray("traces").get(0).asJsonObject
        trace.remove("components")
        try {
            Modulator.loads(raw.toString())
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun unknownVersionIsRejected() {
        val engine = Modulator()
        val raw = JsonParser.parseString(engine.dumps()).asJsonObject
        raw.add("version", JsonPrimitive("9.9.9"))
        try {
            Modulator.loads(raw.toString())
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
