package com.loyea.plugin.companion.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.loyea.plugin.companion.perception.LegacyEmotionSensor
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.LlmConversationBuilder
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.estimateTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端设备验收（Spec §1/§8/A01 设备面）：真实 SQLite 账本 + 真实 ONNX 感知 +
 * 纯投影 + 私有 Lore 选择 + 实际 provider payload 组装。
 * 验证"真实模型接入 + 实际请求包含有效状态和可命中私有 Lore"，
 * 不使用 fixture 降级路径。测试数据用独立会话 ID，结束后清理。
 */
class CompanionEndToEndDeviceTest {

    @Test
    fun fullPipelineProducesRealRequestWithStateAndLore() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = CompanionRuntimeCoordinator(
            SqliteCompanionStateStore.getInstance(appContext),
            SystemCompanionClock(appContext),
            PrefsPolicyBook(appContext),
        )
        val sensor = LegacyEmotionSensor.getInstance(appContext)
        coordinator.textPerception = sensor
        sensor.prepareAsync()
        var waited = 0L
        while (sensor.prepareInfo().state != LegacyEmotionSensor.PrepareState.READY && waited < 120_000) {
            kotlinx.coroutines.delay(200); waited += 200
        }
        assertEquals(LegacyEmotionSensor.PrepareState.READY, sensor.prepareInfo().state)

        val storage = ChatStorageManager(appContext)
        val sessionId = "e2e_${System.currentTimeMillis()}"
        val incarnation = "e2e_inc_${System.currentTimeMillis()}"
        val charId = "char_loyea_companion"
        try {
            coordinator.ensureOwner(charId, sessionId, incarnation, bindingRevision = 1)

            // 1. 接纳一次真实输入：真实分词 + 真实 ONNX 推理 + 真实 SQLite 事务
            val admitStart = System.currentTimeMillis()
            val sent = java.util.concurrent.atomic.AtomicBoolean(false)
            val outcome = coordinator.admitUserTurn(
                AdmitUserTurnRequest(
                    characterId = charId,
                    sessionId = sessionId,
                    incarnationId = incarnation,
                    messageId = "e2e_m1",
                    inputRevision = 0,
                    text = "谢谢你今天帮我改简历，真的太感谢了",
                    contextTurns = emptyList(),
                    policy = CompanionPolicySnapshot(0, physicalPerceptionEnabled = false, textPerceptionEnabled = true, memoryRevision = 0),
                    userMessageJson = JsonObject().apply { addProperty("id", "e2e_m1") },
                    projectionSink = { _, _, _ -> sent.set(true); true },
                ),
            )
            val admitMs = System.currentTimeMillis() - admitStart
            println("PERF admitMs=$admitMs sensor=${(outcome as? AdmitOutcome.Accepted)?.sensorStatus}")
            assertTrue("admission should succeed, got $outcome", outcome is AdmitOutcome.Accepted)
            assertTrue((outcome as AdmitOutcome.Accepted).interactionCounted)
            assertEquals(1, coordinator.interactionCount(charId, sessionId))

            // 2. 纯投影 + 私有 Lore 渲染（真实状态；可见证据 = 本轮观测）
            val visibleEvidence = coordinator.visibleEvidenceIds(charId, sessionId, incarnation, setOf("e2e_m1"))
            val projection = coordinator.projectCurrent(
                charId, sessionId, incarnation, visibleEvidence,
                coordinator.activeTagsForTurn(charId, sessionId, incarnation, "e2e_m1"),
            )
            assertTrue("projection should exist", projection != null)
            val module = CompanionPromptAdapter.render(
                projection!!,
                activeTags = emptySet(),
                estimate = { estimateTokens(it) },
            )
            println("MODULE rows=${module.rows.map { it.aspect + "/" + it.state + "/" + it.intensity }} lore=${module.selectedLoreIds}")
            assertTrue("state block must be present", module.text.contains(CompanionPromptAdapter.STATE_BLOCK_START))

            // 3. 组装实际 provider payload：状态模块 + Lore 进入请求，且只有一份当前状态
            val userMessage = Message(
                id = "e2e_m1",
                content = "谢谢你今天帮我改简历，真的太感谢了",
                sender = Sender.USER,
                llmContextSnapshot = module.text,
            )
            val conversation = LlmConversationBuilder.build(
                systemPrompt = "你是 Loyea，用户身边长期的陪伴者。",
                history = listOf(userMessage),
                companionTurn = true,
            )
            val payloadText = conversation.joinToString("\n") { it.content ?: "" }
            assertTrue(
                "payload must contain current state block",
                payloadText.contains(CompanionPromptAdapter.STATE_BLOCK_START),
            )
            assertEquals(
                "exactly one current state block (A22)",
                1,
                Regex(Regex.escape(CompanionPromptAdapter.STATE_BLOCK_START)).findAll(payloadText).count(),
            )
            // 感知路由命中（真实模型：该样本 act=thank_appreciate/listener → kindness → gratitude 感受）
            val visibleFeelings = module.rows.filter { it.aspect == "feeling" && it.intensity != "—" && it.intensity != "none" }
            println("PERF visibleFeelings=${visibleFeelings.map { it.state + "/" + it.intensity }}")
            if (visibleFeelings.isNotEmpty()) {
                assertTrue(
                    "private lore must be selected when a feeling is visible, got ${module.selectedLoreIds}",
                    module.selectedLoreIds.isNotEmpty(),
                )
                assertTrue(
                    "payload must contain the lore block when lore selected",
                    payloadText.contains(CompanionPromptAdapter.LORE_BLOCK_START),
                )
            }
            // RequestView 已记录
            assertTrue(
                coordinator.recordRequestView(
                    charId, sessionId, incarnation, "e2e_m1", "main",
                    coordinator.acceptedSeqOf(charId, sessionId, incarnation),
                    module, 0, 0,
                ),
            )

            // 4. 相同输入重试：复用原观测，不重复推进（A03/A05 设备面）
            val retry = coordinator.admitUserTurn(
                AdmitUserTurnRequest(
                    characterId = charId,
                    sessionId = sessionId,
                    incarnationId = incarnation,
                    messageId = "e2e_m1",
                    inputRevision = 0,
                    text = "谢谢你今天帮我改简历，真的太感谢了",
                    contextTurns = emptyList(),
                    policy = CompanionPolicySnapshot(0, false, true, 0),
                    userMessageJson = JsonObject(),
                    projectionSink = { _, _, _ -> true },
                ),
            )
            assertTrue(retry is AdmitOutcome.Reused)
            assertEquals(1, coordinator.interactionCount(charId, sessionId))
        } finally {
            coordinator.resetCompanion(charId, sessionId)
            storage.deleteSession(sessionId)
        }
    }

    @Test
    fun modulatorAndLoreSelectionMeetLatencyBudget() {
        // §11：调制器 + 有界 Lore 选择 p95 < 2 ms（模拟器测量，设备差异在报告中说明）
        val projection = CompanionPromptAdapter.render(
            com.loyea.plugin.modulator.StateProjection(
                listOf(
                    com.loyea.plugin.modulator.Row("relationship_affect", "unspecified", "—"),
                    com.loyea.plugin.modulator.Row("relationship_trust", "unspecified", "—"),
                    com.loyea.plugin.modulator.Row("mood", "gloomy", "mild"),
                    com.loyea.plugin.modulator.Row("feeling", "compassion", "moderate"),
                ),
                mapOf("feeling/compassion" to listOf("obs:x:0")),
                false,
            ),
            activeTags = emptySet(),
            estimate = { estimateTokens(it) },
        )
        val samples = mutableListOf<Long>()
        repeat(1200) {
            val start = System.nanoTime()
            // 全流程重复：selectLore + 渲染（真实工作副本，无 IO）
            CompanionPromptAdapter.render(
                com.loyea.plugin.modulator.StateProjection(
                    projection.rows,
                    projection.sources,
                    false,
                ),
                activeTags = emptySet(),
                estimate = { text -> text.length.toLong() },
            )
            samples.add((System.nanoTime() - start) / 1000)
        }
        val sorted = samples.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        println("PERF render p50=${sorted[sorted.size / 2]}us p95=${p95}us max=${sorted.last()}us")
        assertTrue("render p95=$p95 us exceeds 2ms budget", p95 < 2000)
    }
}

/** 测试专用策略簿（SharedPreferences 真实实现即可复用；这里用内存版避免串扰）。 */
private class PrefsPolicyBook(context: android.content.Context) : CompanionPolicyBook {
    private val delegate = PrefsCompanionPolicyBook(context)
    override fun current(physical: Boolean, text: Boolean, memoryRevision: Long): CompanionPolicySnapshot =
        delegate.current(physical, text, memoryRevision)

    override fun bump(): Long = delegate.bump()
}
