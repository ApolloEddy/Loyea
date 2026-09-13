package com.loyea.plugin.companion.perception

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 感知管线移植一致性黄金测试（Spec 接入文档 §3.2）。
 *
 * 黄金数据由原始 AprilPerceptionDemo JS 管线（verbatim 切片，Node 运行）产出：
 * - tokenizer_golden.json：原文+上下文 → 拼接 → inputIds/attentionMask 严格一致；
 * - decoder_golden.json：同一组 logits + 原文 → 完成语法修正后的解码 JSON 一致；
 * - realmodel_golden.json：真实 ONNX（PC CPU）logits → 解码/语法修正离散结果一致。
 */
class LegacyPipelineGoldenTest {

    private val tolerance = 1e-9

    private fun resource(name: String): JsonObject {
        val stream = LegacyPipelineGoldenTest::class.java.classLoader.getResourceAsStream("perception/$name")
            ?: throw AssertionError("perception/$name not found in test resources")
        return JsonParser.parseString(stream.readBytes().decodeToString()).asJsonObject
    }

    // ------------------------------------------------------------------

    @Test
    fun tokenizerMatchesOriginalPipelineExactly() {
        val golden = resource("tokenizer_golden.json")
        val tokenizer = LegacyTokenizer.fromJson(
            resourceAsString("tokenizer_asset_probe") ?: tokenizerJsonForTest(),
            96,
        )
        var count = 0
        for (el in golden.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            val context = c.getAsJsonArray("context")?.map {
                (it.asJsonObject.get("speaker").asString to it.asJsonObject.get("text").asString)
            } ?: emptyList()
            val joined = LegacyTokenizer.joinInput(
                c.get("text").asString,
                c.get("speaker")?.asString ?: "speaker_0",
                context,
            )
            assertEquals("joined mismatch in ${c.get("name").asString}", c.get("joined").asString, joined)
            val encoded = tokenizer.encode(joined)
            val expectedIds = c.getAsJsonArray("inputIds").map { it.asLong }
            val expectedMask = c.getAsJsonArray("attentionMask").map { it.asLong }
            assertEquals("inputIds mismatch in ${c.get("name").asString}", expectedIds, encoded.inputIds.toList())
            assertEquals("mask mismatch in ${c.get("name").asString}", expectedMask, encoded.attentionMask.toList())
            assertEquals(
                "tokens mismatch in ${c.get("name").asString}",
                c.getAsJsonArray("tokens").map { it.asString },
                encoded.tokens,
            )
            count++
        }
        assertEquals(9, count)
    }

    // ------------------------------------------------------------------

    @Test
    fun decoderMatchesOriginalPipelineWithinTolerance() {
        val golden = resource("decoder_golden.json")
        val calibration = LegacyDecoder.loadCalibration(
            JsonParser.parseString(calibrationJson()).asJsonObject,
        )
        var count = 0
        for (el in golden.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            val logits = c.getAsJsonArray("logits").map { it.asDouble }.toDoubleArray()
            val actual = LegacyDecoder.applyGrammarFix(
                LegacyDecoder.decode(logits, calibration),
                c.get("text").asString,
            )
            val expected = c.deepCopy()
            expected.remove("logits")
            assertJsonClose("decoder case $count", expected.asJsonObject, actual, tolerance)
            count++
        }
        assertEquals(10, count)
    }

    // ------------------------------------------------------------------

    @Test
    fun realModelLogitsDecodeConsistently() {
        val golden = resource("realmodel_golden.json")
        val calibration = LegacyDecoder.loadCalibration(
            JsonParser.parseString(calibrationJson()).asJsonObject,
        )
        val conflicts = mutableListOf<String>()
        for (el in golden.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            val logits = c.getAsJsonArray("logits").map { it.asDouble }.toDoubleArray()
            val decoded = LegacyDecoder.decode(logits, calibration)
            val actual = LegacyDecoder.applyGrammarFix(decoded, c.get("text").asString)
            val expected = c.getAsJsonObject("decoded")

            // 离散解码必须完全一致。
            assertEquals(
                "dominantEmotion mismatch: ${c.get("text").asString}",
                expected.get("dominantEmotion").asString,
                actual.get("dominantEmotion").asString,
            )
            assertEquals(
                "dialogueAct mismatch: ${c.get("text").asString}",
                expected.getAsJsonObject("dialogueAct").get("primary").asString,
                actual.getAsJsonObject("dialogueAct").get("primary").asString,
            )
            // 连续量在移植容差内。
            assertJsonClose("realmodel ${c.get("text").asString}", expected, actual, tolerance)

            // 归因弃权规则按确定性触发并登记（本集样本中模型自身已判 third_party，无冲突）。
            LegacyDecoder.attributionConflict(actual)?.let { conflicts += it }
        }
        // third_party_anger 样本：模型对象判断必须不是 listener 直述（宿主不会据此对用户发敌意）。
        for (el in golden.getAsJsonArray("cases")) {
            val c = el.asJsonObject
            if (c.get("text").asString.startsWith("我同事")) {
                val target = c.getAsJsonObject("decoded").getAsJsonObject("speakerAffectTarget")
                    .get("value").asString
                assertEquals("third_party", target)
            }
        }
    }

    /** 归因弃权规则的确定性单元验证（Spec §3.2：可测试的弃权策略）。 */
    @Test
    fun attributionConflictRuleIsDeterministic() {
        fun decodedWith(target: String, hostile: Double, toxicity: Double, adjusted: Boolean?): JsonObject {
            val d = JsonObject()
            val stances = JsonObject(); stances.addProperty("hostile", hostile); d.add("stances", stances)
            val dims = JsonObject(); dims.addProperty("toxicity", toxicity); d.add("dimensions", dims)
            val tgt = JsonObject(); tgt.addProperty("value", target); d.add("speakerAffectTarget", tgt)
            if (adjusted != null) d.addProperty("attributionAdjusted", adjusted)
            return d
        }
        // 语法层已改写归属 + 模型仍给 listener 直述敌意 → 弃权。
        assertEquals(
            "grammar_third_party_conflict",
            LegacyDecoder.attributionConflict(decodedWith("listener", 0.80, 0.60, adjusted = true)),
        )
        // 模型自身已判 third_party → 无冲突。
        assertEquals(
            null,
            LegacyDecoder.attributionConflict(decodedWith("third_party", 0.80, 0.60, adjusted = true)),
        )
        // 毒性不足（玩笑/低强度）→ 无冲突。
        assertEquals(
            null,
            LegacyDecoder.attributionConflict(decodedWith("listener", 0.80, 0.40, adjusted = true)),
        )
        // 无语法改写 → 无冲突。
        assertEquals(
            null,
            LegacyDecoder.attributionConflict(decodedWith("listener", 0.80, 0.60, adjusted = null)),
        )
    }

    // ------------------------------------------------------------------
    // 公共小工具
    // ------------------------------------------------------------------

    private fun assertJsonClose(path: String, expected: JsonObject, actual: JsonObject, tol: Double) {
        for ((key, value) in expected.entrySet()) {
            val actualValue = actual.get(key)
            if (value.isJsonObject && actualValue?.isJsonObject == true) {
                assertJsonClose("$path.$key", value.asJsonObject, actualValue.asJsonObject, tol)
            } else if (value.isJsonPrimitive && actualValue?.isJsonPrimitive == true) {
                val p = value.asJsonPrimitive
                val ap = actualValue.asJsonPrimitive
                if (p.isNumber && ap.isNumber) {
                    assertTrue(
                        "$path.$key numeric mismatch: ${ap.asDouble} vs ${p.asDouble}",
                        Math.abs(ap.asDouble - p.asDouble) <= tol,
                    )
                } else {
                    assertEquals("$path.$key", p.asString, ap.asString)
                }
            } else if (value.isJsonArray && actualValue?.isJsonArray == true) {
                assertEquals("$path.$key size", value.asJsonArray.size(), actualValue.asJsonArray.size())
                for (i in 0 until value.asJsonArray.size()) {
                    val e = value.asJsonArray.get(i)
                    val a = actualValue.asJsonArray.get(i)
                    if (e.isJsonObject && a.isJsonObject) {
                        assertJsonClose("$path.$key[$i]", e.asJsonObject, a.asJsonObject, tol)
                    } else if (e.isJsonPrimitive && a.isJsonPrimitive) {
                        if (e.asJsonPrimitive.isNumber && a.asJsonPrimitive.isNumber) {
                            assertTrue(
                                "$path.$key[$i] numeric mismatch",
                                Math.abs(a.asJsonPrimitive.asDouble - e.asJsonPrimitive.asDouble) <= tol,
                            )
                        } else {
                            assertEquals("$path.$key[$i]", e.asJsonPrimitive.asString, a.asJsonPrimitive.asString)
                        }
                    }
                }
            } else if (value.isJsonNull) {
                // null 字段（如 referencedAffect 缺席）允许实际侧为 JsonNull 或缺失。
                assertTrue("$path.$key expected null", actualValue == null || actualValue.isJsonNull)
            }
        }
    }

    private fun resourceAsString(name: String): String? = null

    private fun tokenizerJsonForTest(): String {
        // 测试资源里不重复打包 440KB 词表；直接读 app assets（测试运行时文件系统可见）。
        val file = java.io.File("src/main/assets/perception/tokenizer.json")
        if (file.isFile) return file.readText()
        val fromRoot = java.io.File("app/src/main/assets/perception/tokenizer.json")
        check(fromRoot.isFile) { "tokenizer.json not found for test" }
        return fromRoot.readText()
    }

    private fun calibrationJson(): String {
        val file = java.io.File("src/main/assets/perception/calibration.json")
        if (file.isFile) return file.readText()
        val fromRoot = java.io.File("app/src/main/assets/perception/calibration.json")
        check(fromRoot.isFile) { "calibration.json not found for test" }
        return fromRoot.readText()
    }
}
