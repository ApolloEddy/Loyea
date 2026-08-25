package com.loyea.plugins.tavern.core

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TavernChatLifecyclePlannerTest {

    // 构造一个带分支链接元数据 header、含 swipe 与 extra 的源会话
    private fun sampleSource(): TavernChatFile {
        val metadata = JsonObject().apply {
            addProperty("scenario", "Inn")
            addProperty("main_chat", "parent-chat")
            addProperty("spring_chat", "spring-parent")
        }
        return TavernChatFile(
            header = TavernChatHeader(
                userName = "StreamUser",
                characterName = "Rui",
                createDate = "2026-08-24T01:02:03Z",
                chatMetadataJson = metadata.toString()
            ),
            messages = listOf(
                TavernChatMessageRecord(
                    name = "Rui",
                    message = "first",
                    isUser = false,
                    swipes = listOf("first", "second", "third"),
                    swipeId = 1,
                    extraJson = "{\"reasoning\":\"kept\"}",
                    rawJson = "{\"name\":\"Rui\",\"mes\":\"first\",\"extra\":{\"reasoning\":\"kept\"}}"
                ),
                TavernChatMessageRecord(
                    name = "StreamUser",
                    message = "follow up",
                    isUser = true,
                    sendDate = "1724451723000"
                )
            ),
            chatName = "source-chat"
        )
    }

    @Test
    fun 克隆全量保留消息与swipes() {
        // 准备：构造带 swipes 与 extra 的源会话并克隆
        val source = sampleSource()
        val clone = TavernChatLifecyclePlanner.cloneChat(source, "clone-chat")

        // 断言：消息条数与顺序一致，swipes 与选中 swipe 内容原样保留
        assertEquals(source.messages.size, clone.messages.size)
        assertEquals("first", clone.messages[0].message)
        assertEquals(listOf("first", "second", "third"), clone.messages[0].swipes)
        assertEquals(1, clone.messages[0].swipeId)
        assertEquals("second", clone.messages[0].selectedMessage)
        // 断言：extraJson / rawJson 字符串内容原样保留
        assertEquals("{\"reasoning\":\"kept\"}", clone.messages[0].extraJson)
        assertEquals(source.messages[0].rawJson, clone.messages[0].rawJson)
        // 断言：新的 chatName 生效
        assertEquals("clone-chat", clone.chatName)
    }

    @Test
    fun 克隆header不携带分支链接() {
        // 准备：源 header 含 main_chat / spring_chat 等分支链接
        val source = sampleSource()

        // 执行：克隆会话
        val clone = TavernChatLifecyclePlanner.cloneChat(source, "clone-chat")
        val metadata = clone.header.metadata()

        // 断言：main_chat / spring_chat 已被剔除，纯净的 scenario 保留
        assertFalse(metadata.has("main_chat"))
        assertFalse(metadata.has("spring_chat"))
        assertEquals("Inn", metadata["scenario"].asString)
        // 断言：userName / characterName 配置仍保留
        assertEquals("StreamUser", clone.header.userName)
        assertEquals("Rui", clone.header.characterName)
    }

    @Test
    fun 修改克隆消息不影响源() {
        // 准备：克隆源会话
        val source = sampleSource()
        val clone = TavernChatLifecyclePlanner.cloneChat(source, "clone-chat")

        // 执行：对克隆列表做改动（改消息文本、替换 swipes）
        val mutated = clone.copy(
            messages = clone.messages.map { record ->
                if (!record.isUser) record.copy(message = "mutated", swipes = listOf("only"))
                else record
            }
        )

        // 断言：源会话完全不受影响，消息与 swipes 仍是原值
        assertEquals("first", source.messages[0].message)
        assertEquals(listOf("first", "second", "third"), source.messages[0].swipes)
        assertEquals(2, source.messages.size)
        // 断言：克隆与源不共享底层 List（不是同一实例）
        assertTrue(clone.messages !== source.messages)
        assertTrue(clone.messages[0].swipes !== source.messages[0].swipes)
        // 断言：改动确实只体现在克隆副本上
        assertEquals("mutated", mutated.messages[0].message)
    }

    @Test
    fun 重启后消息清空配置保留() {
        // 准备：构造源会话
        val source = sampleSource()

        // 执行：重启会话
        val restarted = TavernChatLifecyclePlanner.restartChat(source, Instant.parse("2026-08-24T00:00:00Z"))

        // 断言：消息全部清空
        assertTrue(restarted.messages.isEmpty())
        assertEquals(0, restarted.messages.size)
        // 断言：header 配置（userName / characterName / chatMetadata）原样保留
        assertEquals("StreamUser", restarted.header.userName)
        assertEquals("Rui", restarted.header.characterName)
        assertEquals("Inn", restarted.header.metadata()["scenario"].asString)
    }

    @Test
    fun createDate重置为指定now值() {
        // 准备：指定的确定性时刻
        val now = Instant.parse("2026-08-24T12:34:56.789Z")

        // 执行：使用指定 now 重置 createDate
        val restarted = TavernChatLifecyclePlanner.restartChat(sampleSource(), now)

        // 断言：createDate 恰为该时刻的 ISO 字符串
        assertEquals("2026-08-24T12:34:56.789Z", restarted.header.createDate)
        // 断言：默认参数分支也使用 UTC ISO 字符串（非空且可回读）
        val defaulted = TavernChatLifecyclePlanner.restartChat(sampleSource())
        assertTrue(defaulted.header.createDate != null)
        assertEquals(Instant.parse(defaulted.header.createDate).toString(), defaulted.header.createDate)
    }

    @Test
    fun 重启后可追加消息() {
        // 准备：重启得到一个空会话
        val restarted = TavernChatLifecyclePlanner.restartChat(sampleSource(), Instant.parse("2026-08-24T00:00:00Z"))

        // 执行：向重启后的会话追加一条新消息
        val newMessage = TavernChatMessageRecord(name = "StreamUser", message = "new", isUser = true)
        val appended = restarted.copy(messages = restarted.messages + newMessage)

        // 断言：追加后消息条数 = 1，且重启源仍保持空（不可变追加不污染原会话）
        assertEquals(1, appended.messages.size)
        assertEquals("new", appended.messages.single().message)
        assertTrue(restarted.messages.isEmpty())
    }
}