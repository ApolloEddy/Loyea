package com.loyea.plugin.companion

import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-08 备份编解码合同测试：导出/恢复往返、格式与版本拒绝（DATA-03）、
 * 备份不含任何 API Key（DATA-01）、媒体字段剥离（DATA-02）、
 * v2 配置与图谱记忆完整性（审计 R-05）、v1 旧备份兼容。
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

    private fun sampleConfig() = CompanionConfig(
        enabled = true,
        displayName = "Loyea",
        userCalledName = "小艾",
        perceptionEnabled = false,
        proactiveEnabled = true,
        dndStartMinute = 22 * 60,
        dndEndMinute = 7 * 60,
        createdAt = 42L
    )

    private fun sampleTriples() = listOf(
        CompanionBackupCodec.BackupTriple(
            s = "用户", p = "喜欢", o = "抹茶燕麦拿铁",
            creationTime = 10L, lastMentionedTime = 20L, mentionCount = 3, baseWeight = 1.0f
        )
    )

    private fun exportSample(): String = CompanionBackupCodec.exportJson(
        config = sampleConfig(),
        exportedAt = 999L,
        session = sampleSession(),
        messages = sampleMessages(),
        graphTriples = sampleTriples()
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
    fun `v2 backup carries full companion config`() {
        val preview = (CompanionBackupCodec.parse(exportSample()) as CompanionBackupCodec.ParseResult.Ok).preview
        val cfg = preview.companionConfig
        assertTrue(cfg != null)
        // R-05：称呼、免打扰、感知/主动开关都随备份迁移
        assertEquals("小艾", cfg!!.userCalledName)
        assertEquals(22 * 60, cfg.dndStartMinute)
        assertEquals(7 * 60, cfg.dndEndMinute)
        assertEquals(false, cfg.perceptionEnabled)
        assertEquals(true, cfg.proactiveEnabled)
    }

    @Test
    fun `v2 backup carries graph triples with weights`() {
        val preview = (CompanionBackupCodec.parse(exportSample()) as CompanionBackupCodec.ParseResult.Ok).preview
        assertEquals(1, preview.graphTriples.size)
        val t = preview.graphTriples.first()
        assertEquals("用户", t.s)
        assertEquals("喜欢", t.p)
        assertEquals("抹茶燕麦拿铁", t.o)
        assertEquals(3, t.mentionCount)
        assertEquals(10L, t.creationTime)
        assertEquals(20L, t.lastMentionedTime)
    }

    @Test
    fun `legacy v1 backup parses without config and triples`() {
        // 模拟 v1 备份：version=1、无 graphTriples、companion 无称呼/免打扰字段
        val v1 = exportSample()
            .replace("\"version\":2", "\"version\":1")
            .replace(Regex("\"graphTriples\":\\[.*?\\]"), "\"graphTriples\":[]")
        val result = CompanionBackupCodec.parse(v1)
        assertTrue(result is CompanionBackupCodec.ParseResult.Ok)
        val preview = (result as CompanionBackupCodec.ParseResult.Ok).preview
        assertNull(preview.companionConfig)
        assertTrue(preview.graphTriples.isEmpty())
        assertEquals(2, preview.messageCount)
    }

    @Test
    fun `export strips media paths and secrets`() {
        val json = exportSample()
        // DATA-02：本地媒体路径与头像文件不随备份迁移
        assertTrue(!json.contains("vision_1.jpg"))
        assertTrue(!json.contains("avatarUri"))
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
        assertTrue(json.contains("\"version\":${CompanionBackupCodec.BACKUP_VERSION}"))
        val result = CompanionBackupCodec.parse(json.replace("\"version\":2", "\"version\":99"))
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
