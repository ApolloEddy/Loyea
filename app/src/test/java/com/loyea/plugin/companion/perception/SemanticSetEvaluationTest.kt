package com.loyea.plugin.companion.perception

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.plugin.modulator.Context
import com.loyea.plugin.modulator.Modulator
import com.loyea.plugin.modulator.appraise
import com.loyea.plugin.modulator.Observation
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真实语义集机器可判定部分（Spec §11：逐路命中率 / 误敌意统计）。
 *
 * 83 条样本（覆盖七路由正例 + 否定/假设/转述/第三人称/技术提问/纠错/玩笑/
 * 长文本边界，含 72 条“不应判为对 Loyea 敌意”）以真实 ONNX logits
 * （PC CPU 参考运行时，与设备离散一致已由 LegacySensorOnDeviceTest 证明）
 * 走完整 Kotlin 解码 + 语法修正 + 归因弃权 + AppraisalEngine 评价。
 *
 * 硬门禁：noHostility 样本的错误敌意必须为 0（Spec §11）。
 * 命中率为测量报告值（Spec 目标 ≥80%，逐路报告），不在测试中硬断言。
 * 人工审定部分（标注正确性、自然度）保持 BLOCKED，见验收报告。
 */
class SemanticSetEvaluationTest {

    private fun resourceObject(name: String): JsonObject {
        val stream = SemanticSetEvaluationTest::class.java.classLoader.getResourceAsStream("perception/$name")
            ?: throw AssertionError("perception/$name not found")
        return JsonParser.parseString(stream.readBytes().decodeToString()).asJsonObject
    }

    private fun calibration(): Map<String, LegacyDecoder.Calibration> {
        val file = File("src/main/assets/perception/calibration.json")
            .takeIf { it.isFile } ?: File("app/src/main/assets/perception/calibration.json")
        return LegacyDecoder.loadCalibration(JsonParser.parseString(file.readText()).asJsonObject)
    }

    private val categoryCounts = linkedMapOf<String, Int>()

    /** MISS 主因归因（区分接口门控与模型概率能力，Spec §11 失败类别报告）。 */
    private fun missCause(route: String, decoded: JsonObject, audit: List<String>): String {
        val factuality = decoded.getAsJsonObject("utteranceFactuality")
        val fv = factuality?.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val fc = factuality?.get("confidence")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        if (fv != "asserted" || fc < 0.65) return "factuality_gate($fv/${"%.2f".format(fc)})"
        val emotions = decoded.getAsJsonObject("emotions")
        fun prob(label: String) = emotions?.getAsJsonObject(label)?.get("calibratedProbability")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val stances = decoded.getAsJsonObject("stances")
        fun stance(label: String) = stances?.get(label)?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val act = decoded.getAsJsonObject("dialogueAct")
        val actId = act?.get("primary")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val ac = act?.get("confidence")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val target = decoded.getAsJsonObject("speakerAffectTarget")
        val tv = target?.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val tc = target?.get("confidence")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val toxicity = decoded.getAsJsonObject("dimensions")?.get("toxicity")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        return when (route) {
            "user_distress" -> when {
                tc < 0.65 -> "target_conf(${tv}/${"%.2f".format(tc)})"
                tv !in setOf("self", "event_object", "general") -> "target($tv)"
                maxOf(prob("sadness"), prob("fear")) < 0.70 -> "prob_below_threshold(sad=${"%.2f".format(prob("sadness"))})"
                else -> "other"
            }
            "shared_joy" -> when {
                tc < 0.65 -> "target_conf(${tv}/${"%.2f".format(tc)})"
                tv !in setOf("self", "event_object", "general") -> "target($tv)"
                prob("joy") < 0.70 -> "prob_below_threshold(joy=${"%.2f".format(prob("joy"))})"
                else -> "other"
            }
            "kindness" -> when {
                actId !in setOf("thank_appreciate", "comfort_support") -> "act($actId)"
                ac < 0.70 -> "act_conf(${tv}/${"%.2f".format(ac)})"
                else -> "other"
            }
            "affection" -> when {
                prob("affection") < 0.70 -> "prob_below_threshold(aff=${"%.2f".format(prob("affection"))})"
                stance("affiliative") < 0.60 -> "stance(affiliative=${"%.2f".format(stance("affiliative"))})"
                else -> "other"
            }
            "hostility" -> when {
                stance("hostile") < 0.70 -> "stance(hostile=${"%.2f".format(stance("hostile"))})"
                toxicity < 0.55 -> "toxicity(${"".padStart(0)}${"%.2f".format(toxicity)})"
                tv != "listener" || tc < 0.75 -> "target(${tv}/${"%.2f".format(tc)})"
                else -> "other"
            }
            "repair" -> when {
                actId != "apologize_repair" -> "act($actId)"
                ac < 0.75 -> "act_conf(${actId}/${"%.2f".format(ac)})"
                else -> "no_prior_hostility_trace_in_topic"
            }
            "humor" -> when {
                actId != "joke_irony" -> "act($actId)"
                stance("playful") < 0.70 -> "stance(playful=${"%.2f".format(stance("playful"))})"
                else -> "other"
            }
            else -> "other"
        }
    }

    @Test
    fun evaluateRoutesAndFalseHostility() {
        val cases: JsonArray = JsonParser.parseString(
            SemanticSetEvaluationTest::class.java.classLoader
                .getResourceAsStream("perception/semantic80_logits.json")!!.readBytes().decodeToString(),
        ).asJsonArray
        val cal = calibration()
        val rows = mutableListOf<Map<String, Any?>>()
        var falseHostility = 0
        val routeHit = linkedMapOf("user_distress" to intArrayOf(0, 0), "shared_joy" to intArrayOf(0, 0), "kindness" to intArrayOf(0, 0), "affection" to intArrayOf(0, 0), "hostility" to intArrayOf(0, 0), "repair" to intArrayOf(0, 0), "humor" to intArrayOf(0, 0))

        for (el in cases) {
            val c = el.asJsonObject
            val text = c.get("text").asString
            val logits = c.getAsJsonArray("logits").map { it.asDouble }.toDoubleArray()
            val decoded = LegacyDecoder.decode(logits, cal)
            val waived = LegacyDecoder.attributionConflict(decoded)
            val fixed = LegacyDecoder.applyGrammarFix(decoded, text)

            val engine = Modulator()
            val state = engine.state
            val perceptionForCore: JsonObject? = if (waived != null) null else fixed
            val obs = Observation(
                eventId = "sem:${c.get("name").asString}",
                seq = 0,
                at = 100.0,
                context = Context(topicId = "semantic", scene = "real", visibleEvidence = setOf("sem")),
                perception = perceptionForCore,
            )
            val (events, audit) = appraise(obs, state)
            val fired = events.map { it.kind }.toSet()
            val expect = c.getAsJsonArray("expect")?.map { it.asString } ?: emptyList()
            val notExpect = c.getAsJsonArray("notExpect")?.map { it.asString } ?: emptyList()
            val noHostility = c.get("noHostility")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

            if (noHostility && "hostility" in fired) {
                falseHostility++
                rows += mapOf("name" to c.get("name").asString, "text" to text, "fired" to fired, "type" to "FALSE_HOSTILITY")
            }
            for (r in expect) {
                val bucket = routeHit.getValue(r)
                bucket[1]++
                if (r in fired) {
                    bucket[0]++
                } else {
                    val cause = missCause(r, fixed, audit)
                    categoryCounts.merge(cause, 1, Int::plus)
                    rows += mapOf("name" to c.get("name").asString, "text" to text, "type" to "MISS", "route" to r, "cause" to cause, "audit" to audit)
                }
            }
            for (r in notExpect) {
                if (r in fired) {
                    rows += mapOf("name" to c.get("name").asString, "text" to text, "type" to "FALSE_POSITIVE", "route" to r)
                }
            }
        }

        val totalExpect = routeHit.values.sumOf { it[1] }
        val totalHit = routeHit.values.sumOf { it[0] }
        val report = StringBuilder()
        report.append("# 真实语义集机器判定结果（83 条；真实 ONNX logits + Kotlin 解码/评价）\n\n")
        report.append("## 失败类别分布（MISS 按主因归类）\n\n")
        report.append("| 类别 | 条数 |\n|---|---|\n")
        categoryCounts.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            report.append("| $k | $v |\n")
        }
        report.append("\n")
        report.append("| 路由 | 命中/正例 | 命中率 |\n|---|---|---|\n")
        for ((route, bucket) in routeHit) {
            val rate = if (bucket[1] == 0) 1.0 else bucket[0].toDouble() / bucket[1]
            report.append("| $route | ${bucket[0]}/${bucket[1]} | ${"%.1f".format(rate * 100)}% |\n")
        }
        report.append("| **总体** | **$totalHit/$totalExpect** | **${"%.1f".format(totalHit * 100.0 / totalExpect)}%** |\n")
        report.append("| 错误敌意（应为 0） | $falseHostility | ")
        report.append("${if (falseHostility == 0) "达标" else "未达标"} |\n\n")
        report.append("## 失败/误触发明细\n\n")
        for (r in rows) {
            report.append("- **${r["type"]}** ${r["name"]}: ${r["text"]}")
            (r["route"] as? String)?.let { report.append("（route=$it）") }
            (r["fired"] as? Set<*>)?.let { report.append("（fired=$it）") }
            report.append('\n')
        }
        println(report.toString())
        // 从模块目录或仓库根运行都能落到 docs/audits/
        val outFile = sequenceOf(File("docs/audits"), File("../docs/audits"), File("../../docs/audits"))
            .map { File(it, "Companion-Intelligence-SemanticSet-Results.md") }
            .firstOrNull { it.parentFile.isDirectory }
        outFile?.writeText(report.toString())
        assertTrue("错误敌意必须为 0（Spec §11），实测 $falseHostility", falseHostility == 0)
    }
}
