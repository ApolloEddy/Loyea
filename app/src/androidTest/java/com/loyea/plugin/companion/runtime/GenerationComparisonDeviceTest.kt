package com.loyea.plugin.companion.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.plugin.companion.perception.LegacyEmotionSensor
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 启用/禁用调制的多轮生成对照（Spec §11 最后一项的机器可执行部分）。
 *
 * - 20 组多轮场景（androidTest assets/generation_scenarios.json）。
 * - 每组：第 1 轮共享回复 → 探针轮同一用户输入在“状态模块开/关”两种条件下生成，
 *   唯一差异是 [CURRENT COMPANION STATE] 模块（真实感知+调制+Lore 的产物）。
 * - 真实链路：真 ONNX 感知 + 真 SQLite 账本 + LlmClient 真网络栈（MiMo 网关）。
 * - 断言仅限基础设施（拿到非空回复）；行为信号记录完整数据集，分析在验收报告，
 *   失败案例全量保留。
 * - 需要 -Pandroid.testInstrumentationRunnerArguments.mimoKey=... ；缺失时跳过。
 */
class GenerationComparisonDeviceTest {

    private val gson = Gson()

    private fun scenarios(): JsonArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val raw = ctx.assets.open("generation_scenarios.json").use { it.readBytes().decodeToString() }
        return JsonParser.parseString(raw).asJsonObject.getAsJsonArray("groups")
    }

    private fun config(key: String) = ApiConfig(
        name = "mimo-compare",
        provider = "mimo",
        apiUrl = "https://api.xiaomimimo.com/v1",
        apiKey = key,
        modelName = "mimo-v2.5-pro",
        enableReasoning = false,
    )

    private val persona = "你是 Loyea，用户身边长期的陪伴者。你用自然、温暖、简洁的口语与用户交谈，" +
        "像一位熟悉且值得信赖的朋友。回复保持适合聊天气泡阅读的短段落。"

    private fun runBucket(from: Int, until: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val key = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mimoKey")
        assumeTrue("mimoKey instrumentation argument required", !key.isNullOrBlank())
        val appContext = instrumentation.targetContext
        val config = config(key!!)
        val client = LlmClient()
        val coordinator = CompanionRuntimeCoordinator(
            SqliteCompanionStateStore.getInstance(appContext),
            SystemCompanionClock(appContext),
            PrefsCompanionPolicyBook(appContext),
        )
        val sensor = LegacyEmotionSensor.getInstance(appContext)
        coordinator.textPerception = sensor
        sensor.prepareAsync()
        var waited = 0L
        while (sensor.prepareInfo().state != LegacyEmotionSensor.PrepareState.READY && waited < 120_000) {
            kotlinx.coroutines.delay(200); waited += 200
        }
        assertEqualsReady(sensor)

        val storage = ChatStorageManager(appContext)
        val results = JsonArray()
        for (index in from until until) {
            val group = scenarios().get(index).asJsonObject
            val name = group.get("name").asString
            val sessionId = "gen_${name}_${System.currentTimeMillis()}"
            val incarnation = "gen_inc_${System.currentTimeMillis()}"
            val charId = "char_loyea_companion"
            try {
                coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)
                val turn1 = group.get("first").asString
                val probe = group.get("probe").asString

                // 第 1 轮：ON 条件生成共享回复（两组条件复用同一历史，隔离探针变量）
                val r1 = admitAndReply(coordinator, config, client, charId, sessionId, incarnation, name, 1, turn1, withModule = true)
                val sharedReply = r1.get("reply").asString

                // 探针轮：开/关两种条件，同一历史 + 同一输入
                val on = admitAndReply(coordinator, config, client, charId, sessionId, incarnation, name, 2, probe, withModule = true)
                val history = mutableListOf(
                    Message(id = "${name}_u1", content = turn1, sender = Sender.USER),
                    Message(id = "${name}_a1", content = sharedReply, sender = Sender.AI),
                    Message(id = "${name}_u2", content = probe, sender = Sender.USER),
                )
                val offReply = client.sendChatCompletion(config, persona, history).content ?: ""

                val onShould = group.getAsJsonArray("onShouldContainAny")?.map { it.asString } ?: emptyList()
                val onShouldNot = group.getAsJsonArray("onShouldNotContainAny")?.map { it.asString } ?: emptyList()
                val influenced = onShould.any { on.get("reply").asString.contains(it) }
                val violation = onShouldNot.any { on.get("reply").asString.contains(it) }
                results.add(JsonObject().apply {
                    addProperty("name", name)
                    addProperty("note", group.get("note")?.asString ?: "")
                    addProperty("turn1_user", turn1)
                    addProperty("turn1_reply_shared", sharedReply)
                    addProperty("probe_user", probe)
                    addProperty("reply_on", on.get("reply").asString)
                    addProperty("reply_off", offReply)
                    addProperty("module_present", on.get("modulePresent").asBoolean)
                    addProperty("module_rows", on.get("moduleRows")?.asString ?: "")
                    addProperty("sensor_status", on.get("sensorStatus").asString)
                    add("onShouldContainAny", com.google.gson.JsonArray().apply { onShould.forEach { add(it) } })
                    add("onShouldNotContainAny", com.google.gson.JsonArray().apply { onShouldNot.forEach { add(it) } })
                    addProperty("influenced", influenced)
                    addProperty("violation", violation)
                })
                println("GEN $name influenced=$influenced violation=$violation modulePresent=${on.get("modulePresent").asBoolean}")
                // 完整数据集经 logcat 逐组输出（AGP 测试后卸载应用会连带删除外部目录）
                println("GEN_JSON " + results.get(results.size() - 1).asJsonObject.toString())
            } finally {
                coordinator.resetCompanion(charId, sessionId)
                storage.deleteSession(sessionId)
            }
        }
        // 全量数据集落盘（外部 files 目录，adb pull）
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val out = java.io.File(dir, "generation_results_$from" + "_${until - 1}.json")
        out.writeText(results.toString())
        println("GEN wrote ${out.absolutePath} (${results.size()} groups)")
    }

    private fun assertEqualsReady(sensor: LegacyEmotionSensor) {
        assertTrue(
            "sensor not ready: ${sensor.prepareInfo()}",
            sensor.prepareInfo().state == LegacyEmotionSensor.PrepareState.READY,
        )
    }

    /** 接纳一轮（真实感知+调制事务）→ 投影模块 → 生成回复。 */
    private suspend fun admitAndReply(
        coordinator: CompanionRuntimeCoordinator,
        config: ApiConfig,
        client: LlmClient,
        charId: String,
        sessionId: String,
        incarnation: String,
        messageId: String,
        seq: Int,
        text: String,
        withModule: Boolean,
    ): JsonObject {
        val outcome = coordinator.admitUserTurn(
            AdmitUserTurnRequest(
                characterId = charId,
                sessionId = sessionId,
                incarnationId = incarnation,
                messageId = "${messageId}_t$seq",
                inputRevision = 0,
                text = text,
                contextTurns = emptyList(),
                policy = CompanionPolicySnapshot(0, physicalPerceptionEnabled = false, textPerceptionEnabled = true, memoryRevision = 0),
                userMessageJson = JsonObject().apply { addProperty("id", "${messageId}_t$seq") },
                projectionSink = { _, _, _ -> true },
            ),
        )
        val accepted = outcome as? AdmitOutcome.Accepted
        var moduleText = ""
        if (withModule) {
            val visible = coordinator.visibleEvidenceIds(
                charId, sessionId, incarnation,
                (1..seq).map { "${messageId}_t$it" }.toSet(),
            )
            val projection = coordinator.projectCurrent(
                charId, sessionId, incarnation, visible,
                coordinator.activeTagsForTurn(charId, sessionId, incarnation, "${messageId}_t$seq"),
            )
            if (projection != null) {
                moduleText = CompanionPromptAdapter.render(
                    projection,
                    activeTags = coordinator.activeTagsForTurn(charId, sessionId, incarnation, "${messageId}_t$seq"),
                    estimate = { t -> (t.length / 2).toLong() },
                ).text
            }
        }
        val system = if (withModule && moduleText.isNotBlank()) "$persona\n$moduleText" else persona
        val reply = client.sendChatCompletion(
            config, system,
            listOf(Message(id = "u", content = text, sender = Sender.USER)),
        ).content ?: ""
        return JsonObject().apply {
            addProperty("reply", reply)
            addProperty("modulePresent", moduleText.isNotBlank())
            addProperty("moduleRows", moduleText.lines().filter { it.startsWith("|") }.joinToString(" "))
            addProperty("sensorStatus", accepted?.sensorStatus?.name ?: "NONE")
        }
    }

    @Test fun compareGroups1() = runBucket(0, 4)
    @Test fun compareGroups2() = runBucket(4, 8)
    @Test fun compareGroups3() = runBucket(8, 12)
    @Test fun compareGroups4() = runBucket(12, 16)
    @Test fun compareGroups5() = runBucket(16, 20)
}
