package com.loyea.plugin.modulator

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * 一次性工具：在 v1.1.0 引擎上重放 v1.0 golden 输入，生成
 * golden_cases_v1.1.json 与逐字段差异登记。生成后人工审计差异，
 * 确认每处不同都可归因于证据归因修订（Spec 接入文档 §7）。
 * 默认 @Ignore：只在需要重新生成 fixture 时手动启用，避免测试改写资源。
 */
@Ignore("manual fixture regeneration tool; see golden_cases_v1.1.json")
class FixtureRegenerationTool {

    @Test
    fun regenerate() {
        val v10 = load("integration/golden_cases.json")
        val out = JsonObject()
        out.addProperty("version", ModulatorVocab.VERSION)
        out.addProperty("numeric_tolerance", 1e-10)
        out.addProperty(
            "source",
            "hand-authored synthetic observations from golden_cases.json (v1.0.0-prototype); " +
                "expected values regenerated under the 1.1.0 evidence-attribution revision and audited field-by-field",
        )
        val divergences = JsonArray()
        val sequences = JsonArray()
        for (seqEl in v10.getAsJsonArray("sequences")) {
            val seq = seqEl.asJsonObject
            val personality = personalityFrom(seq.getAsJsonObject("initial_personality"))
            val engine = Modulator(personality, seq.get("initial_at").asDouble)
            val newSeq = JsonObject()
            newSeq.addProperty("name", seq.get("name").asString)
            newSeq.add("initial_personality", seq.getAsJsonObject("initial_personality").deepCopy())
            newSeq.addProperty("initial_at", seq.get("initial_at").asDouble)
            val steps = JsonArray()
            val divSteps = mutableListOf<String>()
            for (stepEl in seq.getAsJsonArray("steps")) {
                val step = stepEl.asJsonObject
                val input = step.getAsJsonObject("input")
                val oldExp = step.getAsJsonObject("expected")
                val obs = Wire.observation(input.deepCopy())
                val decision = engine.process(obs)
                val expected = JsonObject()
                expected.add("rows", rowsJson(decision.rows))
                expected.add("fast", doubles(engine.state.fast))
                expected.add("mood", doubles(engine.state.mood))
                expected.add("traces", tracesJson(engine.state.traces))
                expected.add("audit", strings(decision.audit))
                val diff = diffFields(oldExp, expected)
                if (diff.isNotEmpty()) {
                    divSteps += "step=${obs.seq}:${diff.joinToString(",")}"
                }
                val newStep = JsonObject()
                newStep.add("input", input.deepCopy())
                newStep.add("expected", expected)
                steps.add(newStep)
            }
            newSeq.add("steps", steps)
            sequences.add(newSeq)
            if (divSteps.isNotEmpty()) {
                val d = JsonObject()
                d.addProperty("sequence", seq.get("name").asString)
                d.add("divergentSteps", strings(divSteps))
                divergences.add(d)
            }
        }
        out.add("sequences", sequences)
        out.add("legacy_divergences", divergences)
        val root = repoRoot()
        val target = File(root, "plugins/modulator/core/src/main/resources/integration/golden_cases_v1.1.json")
        target.writeText(out.toString())
        println("wrote ${target.absolutePath}")
        println(divergences.toString())
    }

    private fun diffFields(old: JsonObject, new: JsonObject): List<String> {
        val diff = mutableListOf<String>()
        if (old.getAsJsonArray("rows").toString() != new.getAsJsonArray("rows").toString()) diff += "rows"
        // 数值逐位比较：Gson 对解析数字保留原词法（小写 e），与 Double.toString 词法不同但数值相同。
        if (!doublesEqual(old.getAsJsonArray("fast"), new.getAsJsonArray("fast"))) diff += "fast"
        if (!doublesEqual(old.getAsJsonArray("mood"), new.getAsJsonArray("mood"))) diff += "mood"
        if (old.getAsJsonArray("audit").toString() != new.getAsJsonArray("audit").toString()) diff += "audit"
        val oldTr = old.getAsJsonArray("traces")
        val newTr = new.getAsJsonArray("traces")
        if (oldTr.size() != newTr.size()) {
            diff += "traces"
        } else {
            for (k in 0 until oldTr.size()) {
                val a = oldTr.get(k).asJsonObject
                val b = newTr.get(k).asJsonObject
                val sameKind = a.get("kind").asString == b.get("kind").asString &&
                    a.get("target").asString == b.get("target").asString &&
                    a.get("topic_id").asString == b.get("topic_id").asString
                val sameIds = a.getAsJsonArray("evidence_ids").map { it.asString } ==
                    b.getAsJsonArray("components").map { it.asJsonObject.get("evidence_id").asString }
                val sameStrength = a.get("strength").asDouble == b.get("strength").asDouble
                if (!sameKind || !sameIds || !sameStrength) {
                    diff += if (sameKind && sameIds) "traces.strength" else "traces"
                    break
                }
            }
        }
        return diff
    }

    private fun doublesEqual(a: com.google.gson.JsonArray, b: com.google.gson.JsonArray): Boolean {
        if (a.size() != b.size()) return false
        for (i in 0 until a.size()) {
            val da = a.get(i).asDouble
            val db = b.get(i).asDouble
            if (da != db) return false
        }
        return true
    }

    private fun rowsJson(rows: List<Row>): JsonArray = JsonArray().apply {
        rows.forEach { r ->
            add(JsonObject().apply {
                addProperty("aspect", r.aspect)
                addProperty("state", r.state)
                addProperty("intensity", r.intensity)
            })
        }
    }

    private fun tracesJson(traces: List<Trace>): JsonArray = JsonArray().apply {
        traces.forEach { t ->
            add(JsonObject().apply {
                add("components", JsonArray().apply {
                    t.components.forEach { c ->
                        add(JsonObject().apply {
                            addProperty("evidence_id", c.evidenceId)
                            addProperty("strength", c.strength)
                            addProperty("last_event_at", c.lastEventAt)
                        })
                    }
                })
                addProperty("half_life", t.halfLife)
                addProperty("kind", t.kind)
                addProperty("strength", t.strength)
                addProperty("target", t.target)
                addProperty("topic_id", t.topicId)
            })
        }
    }

    private fun doubles(values: List<Double>): JsonArray = JsonArray().apply { values.forEach { add(it) } }
    private fun strings(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun personalityFrom(meta: JsonObject): Personality = Personality(
        openness = meta.get("openness").asDouble,
        conscientiousness = meta.get("conscientiousness").asDouble,
        extraversion = meta.get("extraversion").asDouble,
        agreeableness = meta.get("agreeableness").asDouble,
        neuroticism = meta.get("neuroticism").asDouble,
        plasticity = meta.get("plasticity").asDouble,
        totalInteractions = meta.get("total_interactions").asInt,
        profileVersion = meta.get("profile_version").asString,
    )

    private fun load(path: String): JsonObject {
        val stream = FixtureRegenerationTool::class.java.classLoader.getResourceAsStream(path)
            ?: throw AssertionError("$path not found")
        return JsonParser.parseString(stream.readBytes().decodeToString()).asJsonObject
    }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir ?: throw AssertionError("repo root not found")
    }
}
