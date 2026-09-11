package com.loyea.plugin.companion

import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-08 备份编解码合同测试：导出/恢复往返、格式与版本拒绝（DATA-03）、
 * 备份不含任何 API Key（DATA-01）、媒体字段剥离（DATA-02）。
 */
class CompanionBackupCodecTest {

    private fun sampleSession() = ChatSession(
        id = "1789154089865",
        title = "陪伴",
        lastActiveTime = 1000L,
        characterId = CompanionContract.COMPANION_CHARACTER_ID,
        bindingRevision = 3L,
        useSystemTime = true,
        coreMemories = listOf("likes quiet weekends"),
        compressedSummary = "早期摘要",
        compressedAtCount = 5,
        promptTokens = 11L,
        completionTokens = 22L
    )

    private fun sampleMessages() = listOf(
        Message(
            id = "m1",
            content = "hi there",
            sender = Sender.USER,
            timestamp = 123L,
            imageUrl = "/data/user/0/app/cache/vision_1.jpg",
            characterId = CompanionContract.COMPANION_CHARACTER_ID
        ),
        Message(
            id = "m2",
            content = "嘿，周五晚上好呀 😊",
            sender = Sender.AI,
            timestamp = 456L,
            thoughts = "思考链",
            characterId = CompanionContract.COMPANION_CHARACTER_ID,
            versions = listOf(com.loyea.ui.chat.MessageVersion(content = "备选回复"))
        )
    )

    private fun exportSample(): String = CompanionBackupCodec.exportJson(
        displayName = "Loyea",
        perceptionEnabled = true,
        proactiveEnabled = false,
        configVersion = 1,
        createdAt = 42L,
        exportedAt = 999L,
        session = sampleSession(),
        messages = sampleMessages()
    )

    @Test
    fun `export then parse roundtrip keeps messages memories and settings`() {
        val json = exportSample()
        val result = CompanionBackupCodec.parse(json)
        assertTrue(result is CompanionBackupCodec.ParseResult.Ok)
        val preview = (result as CompanionBackupCodec.ParseResult.Ok).preview
        assertEquals("Loyea", preview.displayName)
        assertEquals(2, preview.messageCount)
        assertEquals(1, preview.memoryCount)
        assertEquals("likes quiet weekends", preview.session.coreMemories.first())
        assertEquals(123L, preview.firstMessageAt)
        assertEquals(456L, preview.lastMessageAt)
        assertEquals(42L, preview.createdAt)
        assertEquals(999L, preview.exportedAt)
        // 消息正文与多版本保真
        assertEquals("hi there", preview.messages[0].content)
        assertEquals("备选回复", preview.messages[1].versions.first().content)
        assertEquals("思考链", preview.messages[1].thoughts)
    }

    @Test
    fun `export strips media paths and secrets`() {
        val json = exportSample()
        // DATA-02：本地媒体路径不随备份迁移
        assertTrue(!json.contains("vision_1.jpg"))
        // DATA-01：结构上不含 API Key / 授权字段
        assertTrue(!json.contains("apiKey"))
        assertTrue(!json.contains("Authorization"))
    }

    @Test
    fun `wrong type is rejected`() {
        val json = exportSample().replace(CompanionBackupCodec.BACKUP_TYPE, "something_else")
        val result = CompanionBackupCodec.parse(json)
        assertTrue(result is CompanionBackupCodec.ParseResult.Rejected)
    }

    @Test
    fun `wrong version is rejected`() {
        val json = exportSample()
        assertTrue(json.contains("\"version\":" + CompanionBackupCodec.BACKUP_VERSION))
        val result = CompanionBackupCodec.parse(json.replace("\"version\":1", "\"version\":99"))
        assertTrue(result is CompanionBackupCodec.ParseResult.Rejected)
    }

    @Test
    fun `corrupt json is rejected and returns no partial data`() {
        val result = CompanionBackupCodec.parse("{ not valid json !!")
        assertTrue(result is CompanionBackupCodec.ParseResult.Rejected)
    }

    @Test
    fun `markdown export contains both sides but never claims full backup`() {
        val md = CompanionBackupCodec.exportMarkdown("Loyea", sampleMessages(), 999L)
        assertTrue(md.contains("hi there"))
        assertTrue(md.contains("周五晚上好呀"))
        assertTrue(md.contains("不能用于恢复"))
    }
}
