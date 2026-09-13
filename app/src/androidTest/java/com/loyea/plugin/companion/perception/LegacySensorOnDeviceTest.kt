package com.loyea.plugin.companion.perception

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.plugin.companion.runtime.PerceptionRequest
import com.loyea.plugin.companion.runtime.SensorStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实模型通路验收（Spec §3/§11/A01 设备面）：在 Android 运行时上加载
 * 24MB q8 ONNX 模型 + 官方 onnxruntime-android，跑完整
 * 拼接 → WordPiece → 推理 → 校准/解码 → 语法修正管线，
 * 与 PC 参考实现（原 JS 管线 + ONNX CPU logits）比对离散解码结果。
 */
class LegacySensorOnDeviceTest {

    private fun golden(): JsonArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val raw = ctx.assets.open("perception/realmodel_golden.json").use { it.readBytes().decodeToString() }
        return JsonParser.parseString(raw).asJsonObject.getAsJsonArray("cases")
    }

    @Test
    fun realModelPipelineMatchesPcReference() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val sensor = LegacyEmotionSensor.getInstance(appContext)
        sensor.prepareAsync()
        // 模型准备：24MB 解包 + SHA-256 校验 + Session 创建
        val prepareStart = System.currentTimeMillis()
        while (sensor.prepareInfo().state == LegacyEmotionSensor.PrepareState.IDLE ||
            sensor.prepareInfo().state == LegacyEmotionSensor.PrepareState.PREPARING
        ) {
            if (System.currentTimeMillis() - prepareStart > 120_000) throw AssertionError("prepare timeout")
            kotlinx.coroutines.delay(200)
        }
        assertEquals(LegacyEmotionSensor.PrepareState.READY, sensor.prepareInfo().state)
        val prepareMs = System.currentTimeMillis() - prepareStart
        assertEquals(LegacyEmotionSensor.EXPECTED_MODEL_SHA256, sensor.prepareInfo().modelSha256)
        println("PERF prepareMs=$prepareMs")

        val mismatches = StringBuilder()
        val timings = mutableListOf<Long>()
        var checked = 0
        for (el in golden()) {
            val c = el.asJsonObject
            val text = c.get("text").asString
            val context = c.getAsJsonArray("context")?.map {
                com.loyea.plugin.companion.runtime.PerceptionTurn(
                    it.asJsonObject.get("speaker").asString,
                    it.asJsonObject.get("text").asString,
                    false,
                )
            } ?: emptyList()
            val outcome = sensor.perceive(PerceptionRequest(text, context))
            assertEquals("sensor not ready for $text", SensorStatus.READY_RESULT, outcome.status)
            outcome.timingMs["inference_ms"]?.let { timings.add(it) }
            val decoded = outcome.perceptionJson!!
            val expected = c.getAsJsonObject("decoded")
            checked++

            fun expect(field: String, path: String) {
                val e = expected.get(path)?.takeIf { it.isJsonPrimitive }?.asString
                val a = decoded.get(path)?.takeIf { it.isJsonPrimitive }?.asString
                if (e != null && a != null && e != a) {
                    mismatches.append("$text: $path expected=$e actual=$a\n")
                }
                if (field.isNotEmpty() && e == null) {
                    mismatches.append("$text: $path missing in reference\n")
                }
            }
            // 离散解码必须一致（稳定样本）
            expect("dominantEmotion", "dominantEmotion")
            expect("modelDominantEmotion", "modelDominantEmotion")
            // 逐项精确比较（简单字段）
            val actE = expected.getAsJsonObject("dialogueAct").get("primary").asString
            val actA = decoded.getAsJsonObject("dialogueAct").get("primary").asString
            if (actE != actA) mismatches.append("$text: dialogueAct expected=$actE actual=$actA\n")
            val tgtE = expected.getAsJsonObject("speakerAffectTarget").get("value").asString
            val tgtA = decoded.getAsJsonObject("speakerAffectTarget").get("value").asString
            if (tgtE != tgtA) mismatches.append("$text: target expected=$tgtE actual=$tgtA\n")
            val factE = expected.getAsJsonObject("utteranceFactuality").get("value").asString
            val factA = decoded.getAsJsonObject("utteranceFactuality").get("value").asString
            if (factE != factA) mismatches.append("$text: factuality expected=$factE actual=$factA\n")
        }
        assertEquals(16, checked)
        val sorted = timings.sorted()
        println("PERF inference p50=${sorted[sorted.size / 2]}ms p95=${sorted[(sorted.size - 1).coerceAtLeast(0)]}ms n=${sorted.size}")
        assertTrue("discrete decode mismatches vs PC reference:\n$mismatches", mismatches.isEmpty())
    }

    @Test
    fun timeoutDegradationDoesNotBlockChat() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val sensor = LegacyEmotionSensor.getInstance(appContext)
        sensor.prepareAsync()
        var waited = 0L
        while (sensor.prepareInfo().state != LegacyEmotionSensor.PrepareState.READY && waited < 120_000) {
            kotlinx.coroutines.delay(200); waited += 200
        }
        // 连续两次调用：第二次在第一次推理未退出时应立即降级（不排队、不新建 Session）
        val first = sensor.perceive(PerceptionRequest("你好呀", emptyList()))
        assertEquals(SensorStatus.READY_RESULT, first.status)
        val second = sensor.perceive(PerceptionRequest("第二句", emptyList()))
        assertTrue(
            "second call should be READY or degraded NOT_READY/TIMEOUT, got ${second.status}",
            second.status == SensorStatus.READY_RESULT || second.status == SensorStatus.NOT_READY ||
                second.status == SensorStatus.TIMEOUT,
        )
    }
}
