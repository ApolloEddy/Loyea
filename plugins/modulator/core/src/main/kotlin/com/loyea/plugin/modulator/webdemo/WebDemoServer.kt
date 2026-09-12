package com.loyea.plugin.modulator.webdemo

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import com.loyea.plugin.modulator.Context
import com.loyea.plugin.modulator.Decision
import com.loyea.plugin.modulator.Modulator
import com.loyea.plugin.modulator.ModulatorVocab
import com.loyea.plugin.modulator.Observation
import com.loyea.plugin.modulator.Personality
import com.loyea.plugin.modulator.Rules
import com.loyea.plugin.modulator.Wire
import java.io.File
import java.net.InetSocketAddress
import kotlin.math.abs

/**
 * 调制器 web 调试页入口：JDK 自带 HttpServer + 静态页面 + 无状态 JSON API。
 *
 * 协议：客户端持有检查点字符串（dumps/loads 往返无损，已由测试覆盖），
 * 每次请求携带检查点 + 一条命令，服务端用公共 API 推进后返回新检查点与展示数据。
 * 服务端只调用 com.loyea.plugin.modulator 的公共接口，不含任何模型逻辑。
 *
 * 启动：`gradlew :loyea-modulator-core:webDemo`（工作目录须为仓库根，以定位 web/index.html）。
 */
private const val DEFAULT_PORT = 8628

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
    val webRoot = File("plugins/modulator/web").absoluteFile
    val index = File(webRoot, "index.html")
    check(index.isFile) { "未找到 ${index.path}；请从仓库根目录启动 webDemo 任务" }

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { exchange ->
        exchange.use {
            val path = it.requestURI.path
            when {
                (path == "/" || path == "/index.html") && it.requestMethod == "GET" ->
                    respond(it, 200, index.readBytes(), "text/html; charset=utf-8")
                path == "/api" && it.requestMethod == "POST" -> {
                    val body = it.requestBody.readBytes().decodeToString()
                    respond(it, 200, handleApi(body).encodeToByteArray(), "application/json; charset=utf-8")
                }
                else -> respond(it, 404, "not found".encodeToByteArray(), "text/plain; charset=utf-8")
            }
        }
    }
    server.start()
    println("调制器 web 调试页已启动：http://127.0.0.1:$port  （Ctrl+C 退出）")
}

private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, bytes: ByteArray, type: String) {
    exchange.responseHeaders.set("Content-Type", type)
    exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    exchange.responseBody.write(bytes)
}

private fun handleApi(raw: String): String {
    val req = JsonParser.parseString(raw).asJsonObject
    return try {
        when (req.get("cmd").asString) {
            "reset" -> {
                val at = req.get("at")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                val personality = req.getAsJsonObject("personality")?.let { personalityFrom(it) } ?: Personality()
                ok(Modulator(personality, at), null)
            }
            "process" -> {
                val engine = engineFrom(req)
                val decision = engine.process(Wire.observation(req.getAsJsonObject("observation")))
                ok(engine, decision)
            }
            "idle" -> {
                val engine = engineFrom(req)
                engine.idleTo(req.get("at").asDouble)
                ok(engine, null)
            }
            "golden" -> goldenReport()
            else -> errorJson("unknown cmd")
        }
    } catch (t: Throwable) {
        errorJson(t.message ?: t.toString())
    }
}

private fun engineFrom(req: JsonObject): Modulator {
    val checkpoint = req.get("checkpoint")?.takeIf { !it.isJsonNull }?.asString
    return if (checkpoint.isNullOrEmpty()) Modulator() else Modulator.loads(checkpoint)
}

private fun personalityFrom(o: JsonObject): Personality = Personality(
    openness = o.get("openness").asDouble,
    conscientiousness = o.get("conscientiousness").asDouble,
    extraversion = o.get("extraversion").asDouble,
    agreeableness = o.get("agreeableness").asDouble,
    neuroticism = o.get("neuroticism").asDouble,
    plasticity = o.get("plasticity").asDouble,
    totalInteractions = o.get("total_interactions").asInt,
    profileVersion = o.get("profile_version").asString,
)

// ---------------------------------------------------------------------------
// 响应组装（展示字段与 id 一并发送，页面直接渲染）
// ---------------------------------------------------------------------------

private fun ok(engine: Modulator, decision: Decision?): String {
    val root = JsonObject()
    root.addProperty("ok", true)
    root.addProperty("checkpoint", engine.dumps())
    root.add("state", stateJson(engine))
    if (decision != null) root.add("decision", decisionJson(decision))
    return root.toString()
}

private fun errorJson(message: String): String {
    val root = JsonObject()
    root.addProperty("ok", false)
    root.addProperty("error", message)
    return root.toString()
}

private fun stateJson(engine: Modulator): JsonObject {
    val st = engine.state
    val state = JsonObject()
    state.addProperty("at", st.at)
    state.add("fast", JsonArray().apply { st.fast.forEach { add(it) } })
    state.add("mood", JsonArray().apply { st.mood.forEach { add(it) } })
    state.addProperty("moodLabel", st.moodLabel)
    state.add("selectedFeelings", JsonArray().apply { st.selectedFeelings.forEach { add(it) } })
    val traces = JsonArray()
    for (t in st.traces) {
        val tj = JsonObject()
        tj.addProperty("label", Rules.ALL[t.kind]?.let { ModulatorVocab.STATE_ZH[it.label] } ?: t.kind)
        tj.addProperty("kind", t.kind)
        tj.addProperty("target", t.target)
        tj.addProperty("topicId", t.topicId)
        tj.addProperty("strength", t.strength)
        tj.addProperty("halfLife", t.halfLife)
        tj.add("evidenceIds", JsonArray().apply { t.evidenceIds.forEach { add(it) } })
        traces.add(tj)
    }
    state.add("traces", traces)
    return state
}

private fun decisionJson(decision: Decision): JsonObject {
    val dj = JsonObject()
    val rows = JsonArray()
    for (r in decision.rows) {
        val rj = JsonObject()
        rj.addProperty("aspect", r.aspect)
        rj.addProperty("state", r.state)
        rj.addProperty("intensity", r.intensity)
        rj.addProperty("aspectZh", ModulatorVocab.ASPECT_ZH[r.aspect] ?: r.aspect)
        rj.addProperty("stateZh", ModulatorVocab.STATE_ZH[r.state] ?: r.state)
        rj.addProperty("intensityZh", ModulatorVocab.LEVEL_ZH[r.intensity] ?: r.intensity)
        rows.add(rj)
    }
    dj.add("rows", rows)
    dj.add("audit", JsonArray().apply { decision.audit.forEach { add(it) } })
    dj.addProperty("markdown", decision.markdown())
    return dj
}

// ---------------------------------------------------------------------------
// Golden cases 回放（与 core 单测同数据、同容差 1e-10）
// ---------------------------------------------------------------------------

private fun goldenReport(): String {
    val raw = Modulator::class.java.getResourceAsStream("/integration/golden_cases.json")
        ?.readBytes()?.decodeToString()
        ?: throw IllegalArgumentException("golden_cases.json 不在类路径上")
    val cases = JsonParser.parseString(raw).asJsonObject
    val tolerance = cases.get("numeric_tolerance").asDouble
    var allPass = true
    val sequences = JsonArray()
    for (seqEl in cases.getAsJsonArray("sequences")) {
        val seq = seqEl.asJsonObject
        val engine = Modulator(personalityFrom(seq.getAsJsonObject("initial_personality")), seq.get("initial_at").asDouble)
        var maxError = 0.0
        var ok = true
        var steps = 0
        for (stepEl in seq.getAsJsonArray("steps")) {
            val step = stepEl.asJsonObject
            val decision = engine.process(Wire.observation(step.getAsJsonObject("input")))
            val expected = step.getAsJsonObject("expected")
            steps++
            val expRows = expected.getAsJsonArray("rows")
            if (expRows.size() != decision.rows.size) ok = false
            for (i in 0 until minOf(expRows.size(), decision.rows.size)) {
                val er = expRows.get(i).asJsonObject
                val r = decision.rows[i]
                if (er.get("aspect").asString != r.aspect || er.get("state").asString != r.state ||
                    er.get("intensity").asString != r.intensity
                ) ok = false
            }
            for (j in 0 until 4) {
                maxError = maxOf(maxError, abs(expected.getAsJsonArray("fast").get(j).asDouble - engine.state.fast[j]))
                maxError = maxOf(maxError, abs(expected.getAsJsonArray("mood").get(j).asDouble - engine.state.mood[j]))
            }
            val expTraces = expected.getAsJsonArray("traces")
            if (expTraces.size() != engine.state.traces.size) ok = false
            for (k in 0 until minOf(expTraces.size(), engine.state.traces.size)) {
                val et = expTraces.get(k).asJsonObject
                val t = engine.state.traces[k]
                if (et.get("kind").asString != t.kind || et.get("topic_id").asString != t.topicId) ok = false
                maxError = maxOf(maxError, abs(et.get("strength").asDouble - t.strength))
            }
            if (expected.getAsJsonArray("audit").map { it.asString } != decision.audit) ok = false
        }
        val pass = ok && maxError <= tolerance
        allPass = allPass && pass
        sequences.add(JsonObject().apply {
            addProperty("name", seq.get("name").asString)
            addProperty("steps", steps)
            addProperty("maxError", maxError)
            addProperty("pass", pass)
        })
    }
    val root = JsonObject()
    root.addProperty("ok", true)
    root.addProperty("allPass", allPass)
    root.addProperty("tolerance", tolerance)
    root.add("sequences", sequences)
    return root.toString()
}
