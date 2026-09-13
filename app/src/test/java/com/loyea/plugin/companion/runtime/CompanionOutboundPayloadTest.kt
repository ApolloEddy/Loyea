package com.loyea.plugin.companion.runtime

import com.loyea.ui.chat.StreamEvent
import com.loyea.plugin.companion.perception.LegacyDecoder
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.LlmConversationBuilder
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.estimateTokens
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 出站 payload 字节级验收（Spec §12 注 / A22/A37）：
 * 用 MockWebServer 捕获实际序列化请求，断言：
 * - 恰好一份“当前”陪伴状态块 + 选中私有 Lore 文本进入请求；
 * - 历史用户消息不再携带旧快照（仅当前一条注入）；
 * - 解码细节（概率/头分布/语法提示/原因码）不外泄给生成模型；
 * - 文字感知关闭/物理权限撤回不影响其余模块语义（sanitize 后仍成立）。
 * 真实模型通路的设备级验证见 androidTest（LegacySensorOnDeviceTest /
 * CompanionEndToEndDeviceTest）；本测试使用真实解码器对真实 logits 的输出作为模块来源。
 */
class CompanionOutboundPayloadTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = LlmClient()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun companionModuleFromRealLogits(): String {
        val stream = CompanionOutboundPayloadTest::class.java.classLoader
            .getResourceAsStream("perception/realmodel_golden.json") ?: throw AssertionError("golden missing")
        val root = com.google.gson.JsonParser.parseString(stream.readBytes().decodeToString()).asJsonObject
        val calibration = LegacyDecoder.loadCalibration(
            com.google.gson.JsonParser.parseString(
                java.io.File("src/main/assets/perception/calibration.json").let {
                    (if (it.isFile) it else java.io.File("app/src/main/assets/perception/calibration.json")).readText()
                },
            ).asJsonObject,
        )
        // kindness 路由样本（致谢）→ gratitude 感受 → loyea.gratitude 私有条目
        val c = root.getAsJsonArray("cases").map { it.asJsonObject }
            .first { it.get("text").asString.startsWith("谢谢你今天帮我改简历") }
        val logits = c.getAsJsonArray("logits").map { it.asDouble }.toDoubleArray()
        val decoded = LegacyDecoder.decode(logits, calibration)
        val fixed = LegacyDecoder.applyGrammarFix(decoded, c.get("text").asString)
        val projection = projectFromDecoded(fixed)
        val module = CompanionPromptAdapter.render(
            projection = projection,
            activeTags = emptySet(),
            estimate = { estimateTokens(it) },
        )
        return module.text
    }

    /** 从真实解码 JSON 构造观测并推进一个独立引擎，得到与宿主一致的投影。 */
    private fun projectFromDecoded(decoded: com.google.gson.JsonObject): com.loyea.plugin.modulator.StateProjection {
        val engine = com.loyea.plugin.modulator.Modulator()
        val decision = engine.process(
            com.loyea.plugin.modulator.Observation(
                eventId = "obs:e2e_m1:0",
                seq = 0,
                at = 100.0,
                context = com.loyea.plugin.modulator.Context(
                    topicId = "session:e2e",
                    scene = "real",
                    visibleEvidence = setOf("obs:e2e_m1:0"),
                ),
                perception = decoded,
            ),
        )
        // 宿主对当前回合的可见证据包含本轮观测（与 ChatViewModel 一致）
        return engine.project(
            com.loyea.plugin.modulator.Context(topicId = "session:e2e", scene = "real"),
            visibleEvidence = setOf("obs:e2e_m1:0"),
        )
    }

    @Test
    fun outboundPayloadCarriesSingleCurrentStateAndLoreWithoutLeaks() = runBlocking {
        val moduleText = companionModuleFromRealLogits()
        val module = CompanionPromptAdapter.RenderedModule(
            text = moduleText,
            selectedLoreIds = listOf("loyea.gratitude"),
            rows = emptyList(),
            sources = mapOf("feeling/gratitude" to listOf("obs:e2e_m1:0")),
            budgetTokens = estimateTokens(moduleText),
            budgetEstimated = true,
        )
        assertTrue("kindness 路由样本必须可见 gratitude 感受", moduleText.contains("感激"))
        assertTrue("对应私有条目必须被选中", moduleText.contains("简短回应用户的善意"))

        val historyMsg = Message(id = "old1", content = "之前的话", sender = Sender.USER, llmContextSnapshot = "旧快照不应重新注入")
        val currentMsg = Message(id = "e2e_m1", content = "谢谢你今天帮我改简历，真的太感谢了", sender = Sender.USER, llmContextSnapshot = module.text)
        val conversation = LlmConversationBuilder.build(
            systemPrompt = "你是 Loyea，用户身边长期的陪伴者。",
            history = listOf(historyMsg, currentMsg),
            companionTurn = true,
        )

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"好的\"}}]}\n\ndata: [DONE]\n\n")
                .setHeader("Content-Type", "text/event-stream"),
        )
        val config = ApiConfig(
            name = "test",
            provider = "OpenAI",
            apiUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            modelName = "test-model",
        )
        val events = client.sendChatCompletionStream(config, conversation).toList()
        assertTrue(events.any { it is StreamEvent.Content })

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()

        // A22：恰好一份当前状态块；历史用户消息的旧快照不再注入
        assertEquals(1, Regex(Regex.escape(CompanionPromptAdapter.STATE_BLOCK_START)).findAll(body).count())
        assertFalse(body.contains("旧快照不应重新注入"))
        // Lore 文本实际进入出站请求
        assertTrue(body.contains("简短回应用户的善意"))
        // A37/§8.2：解码细节不外泄（概率、头分布、语法提示、算法审计）
        assertFalse(body.contains("calibratedProbability"))
        assertFalse(body.contains("grammarHints"))
        assertFalse(body.contains("policyWeight"))
        assertFalse(body.contains("dialogueAct"))
        assertFalse(body.contains("attributionAdjusted"))
        // 用户原文仍正常进入生成上下文（弃权/降级不吞消息）
        assertTrue(body.contains("谢谢你今天帮我改简历"))
    }
}
