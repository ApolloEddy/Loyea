package com.loyea.plugin.companion.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.loyea.plugin.companion.perception.LegacyEmotionSensor
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机会话状态种子（自调试工具，非验收矩阵项）：
 * 在设备上已存在的陪伴会话里，用真实感知+调制事务接纳一条致谢消息并落盘，
 * 使 UI/状态可视化能展示带痕迹的完整视图。幂等：相同 messageId 重跑=Reused。
 */
class SeedStateOnDeviceTest {

    @Test
    fun seedThanksTurnIntoRealSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("mimoKey")
        val storage = ChatStorageManager(appContext)
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
        assertTrue(sensor.prepareInfo().state == LegacyEmotionSensor.PrepareState.READY)

        var session = storage.loadSessionList()
            .filter { it.characterId == "char_loyea_companion" }
            .maxByOrNull { it.lastActiveTime }
        if (session == null) {
            // 最小建会话（同 ChatViewModel.ensureCompanionSession 的持久化语义）
            val id = System.currentTimeMillis().toString()
            val created = com.loyea.ui.chat.ChatSession(
                id = id,
                title = "Loyea",
                lastActiveTime = System.currentTimeMillis(),
                characterId = "char_loyea_companion",
                sessionIncarnationId = java.util.UUID.randomUUID().toString(),
                useSystemTime = false,
            )
            assertTrue(storage.saveSessionList(listOf(created)))
            assertTrue(storage.ensureSessionMessageFile(id))
            session = created
        }
        val incarnation = session.sessionIncarnationId
            ?: storage.ensureSessionIncarnation(session.id)
            ?: throw AssertionError("cannot assign incarnation")

        coordinator.ensureOwner(session.characterId, session.id, incarnation, session.bindingRevision)

        val messageId = "seed_thanks_1"
        val existing = coordinator.interactionCount(session.characterId, session.id)
        val probe = coordinator.admitUserTurn(
            AdmitUserTurnRequest(
                characterId = session.characterId,
                sessionId = session.id,
                incarnationId = incarnation,
                messageId = messageId,
                inputRevision = 0,
                text = "谢谢你今天陪我聊了这么久，真的太感谢了",
                contextTurns = emptyList(),
                policy = CompanionPolicySnapshot(0, physicalPerceptionEnabled = false, textPerceptionEnabled = true, memoryRevision = 0),
                userMessageJson = JsonObject().apply {
                    addProperty("id", messageId)
                    addProperty("content", "谢谢你今天陪我聊了这么久，真的太感谢了")
                    addProperty("sender", "USER")
                    addProperty("timestamp", System.currentTimeMillis())
                },
                projectionSink = { _, mid, _ ->
                    // Gson 绕过构造默认值：显式重建，防 null 集合字段
                    val msg = Message(
                        id = mid,
                        content = "谢谢你今天陪我聊了这么久，真的太感谢了",
                        sender = Sender.USER,
                        timestamp = System.currentTimeMillis(),
                        characterId = session.characterId,
                    )
                    storage.updateSessionMessagesFenced(session.id, incarnation) { cur ->
                        if (cur.any { it.id == mid }) cur else cur + msg
                    }
                },
            ),
        )
        assertTrue("admission failed: $probe", probe is AdmitOutcome.Accepted || probe is AdmitOutcome.Reused)
        println("SEED admitted=${probe is AdmitOutcome.Accepted} prevCount=$existing now=${coordinator.interactionCount(session.characterId, session.id)} key=${key != null}")
    }
}
