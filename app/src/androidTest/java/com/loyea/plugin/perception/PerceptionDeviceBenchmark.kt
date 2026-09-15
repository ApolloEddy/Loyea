package com.loyea.plugin.perception

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * 真机感知模型基准（MicroEmotionSenser 验收）：
 * 对每个待测模型（原版资产模型 + 候选模型）在真机上逐条推理手工泛化探测集，
 * 测量延迟分布与逐条 logits，写入 app 外部文件供 adb 取回，与桌面侧结果比对。
 *
 * 资产约定（*.onnx 已 gitignore，本地放置）：
 *   app/src/main/assets/perception/model.onnx        —— 原版 April INT8（线上基线）
 *   app/src/androidTest/assets/percbench/model.onnx  —— 候选模型（EXP-024 INT8）
 *   app/src/androidTest/assets/percbench/items.json  —— 手工探测集（token 预编码）
 */
class PerceptionDeviceBenchmark {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private fun benchOne(name: String, modelBytes: ByteArray, items: JSONArray): JSONObject {
        val session = env.createSession(modelBytes, OrtSession.SessionOptions())
        val out = org.json.JSONArray()
        val latencies = ArrayList<Long>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val ids = item.getJSONArray("ids").let { arr -> LongArray(arr.length()) { k -> arr.getLong(k) } }
            val mask = item.getJSONArray("mask").let { arr -> LongArray(arr.length()) { k -> arr.getLong(k) } }
            val shape = longArrayOf(1, ids.size.toLong())
            val inputIds = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(ids), shape)
            val attn = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(mask), shape)
            val t0 = System.nanoTime()
            val result = session.run(mapOf("input_ids" to inputIds, "attention_mask" to attn))
            val logits = (result[0].value as Array<FloatArray>)[0]
            val elapsed = System.nanoTime() - t0
            result.close(); inputIds.close(); attn.close()
            latencies.add(elapsed)
            var argmaxAbs = 0
            for (j in logits.indices) if (logits[j] > logits[argmaxAbs]) argmaxAbs = j
            val o = JSONObject()
            o.put("id", item.getString("id"))
            o.put("latency_ms", elapsed / 1_000_000.0)
            o.put("dominant_index", argmaxAbs)
            val lg = JSONArray()
            for (j in 0 until 8) lg.put(logits[j].toDouble())
            o.put("bp_logits", lg)
            out.put(o)
        }
        session.close()
        val sorted = latencies.sorted()
        fun pct(q: Double) = sorted[(q * (sorted.size - 1)).toInt()] / 1_000_000.0
        return JSONObject()
            .put("n", items.length())
            .put("latency_p50_ms", pct(0.5))
            .put("latency_p95_ms", pct(0.95))
            .put("latency_mean_ms", latencies.sum() / 1_000_000.0 / items.length())
            .put("items", out)
    }

    @Test
    fun runBenchmark() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val items = JSONArray(testCtx.assets.open("percbench/items.json").bufferedReader().readText())

        val models = linkedMapOf<String, ByteArray>(
            "original_asset_i8" to appCtx.assets.open("perception/model.onnx").readBytes(),
        )
        try {
            models["exp024_i8"] = testCtx.assets.open("percbench/model.onnx").readBytes()
        } catch (e: Exception) {
            println("PERCBENCH: candidate model asset missing, only original will run")
        }

        val results = JSONObject()
        for ((name, bytes) in models) {
            val r = benchOne(name, bytes, items)
            r.put("model_mb", bytes.size / 1024.0 / 1024.0)
            results.put(name, r)
            println("PERCBENCH $name p50=${r.getDouble("latency_p50_ms")}ms " +
                "p95=${r.getDouble("latency_p95_ms")}ms mean=${r.getDouble("latency_mean_ms")}ms")
        }
        val summary = JSONObject()
            .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " (Android " + android.os.Build.VERSION.RELEASE + ")")
            .put("n", items.length())
        results.put("_meta", summary)

        val dir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        val f = java.io.File(dir, "percbench_results.json")
        f.writeText(results.toString())
        println("PERCBENCH_SUMMARY $summary")
        println("PERCBENCH_DONE -> $f")
    }
}
