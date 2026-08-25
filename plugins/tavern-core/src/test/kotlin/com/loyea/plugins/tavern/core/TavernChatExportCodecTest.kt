package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernChatExportCodecTest {

    /** 快捷构造带用户名头部的对话。 */
    private fun file(
        userName: String? = "小明",
        messages: List<TavernChatMessageRecord> = emptyList()
    ) = TavernChatFile(
        header = TavernChatHeader(userName = userName),
        messages = messages
    )

    /** 快捷构造一条消息记录。 */
    private fun record(
        name: String = "助手",
        message: String = "",
        isUser: Boolean = false,
        isSystem: Boolean = false,
        sendDate: String? = null,
        swipes: List<String> = emptyList(),
        swipeId: Int = 0
    ) = TavernChatMessageRecord(
        name = name,
        message = message,
        isUser = isUser,
        isSystem = isSystem,
        sendDate = sendDate,
        swipes = swipes,
        swipeId = swipeId
    )

    @Test
    fun 空会话导出的文本应为空() {
        assertEquals("", TavernChatExportCodec.toTxt(TavernChatFile()))
        assertEquals("", TavernChatExportCodec.toTxt(file(messages = emptyList())))
    }

    @Test
    fun 混合用户助手系统角色名导出正确() {
        val chat = file(messages = listOf(
            record(name = "", message = "你好", isUser = true),
            record(name = "助手", message = "你好呀"),
            record(name = "", message = "系统提示", isSystem = true)
        ))
        assertEquals(
            "小明: 你好\n助手: 你好呀\n[system] 系统提示",
            TavernChatExportCodec.toTxt(chat)
        )
    }

    @Test
    fun 用户名为空时回退为You() {
        val chat = file(userName = null, messages = listOf(
            record(name = "", message = "你好", isUser = true)
        ))
        assertEquals("You: 你好", TavernChatExportCodec.toTxt(chat))
    }

    @Test
    fun swipes选中的文本优先于主文本() {
        val chat = file(messages = listOf(
            record(
                name = "助手",
                message = "主文本",
                swipes = listOf("第一条", "第二条"),
                swipeId = 1
            )
        ))
        assertEquals("助手: 第二条", TavernChatExportCodec.toTxt(chat))
    }

    @Test
    fun json导出的字段完整且角色划分正确() {
        val chat = file(messages = listOf(
            record(name = "", message = "你好", isUser = true),
            record(name = "助手", message = "回复你"),
            record(name = "", message = "提示", isSystem = true)
        ))
        val json = TavernChatExportCodec.toJson(chat)
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"role\":\"assistant\""))
        assertTrue(json.contains("\"role\":\"system\""))
        assertTrue(json.contains("\"name\":\"小明\""))
        assertTrue(json.contains("\"name\":\"助手\""))
        assertTrue(json.contains("\"message\":\"回复你\""))
        // 系统消息不使用 name 字段
        assertFalse(json.contains("\"role\":\"system\",\"name"))
    }

    @Test
    fun 毫秒戳按UTC格式化为文本时间前缀() {
        // 1672531200000 == 2023-01-01T00:00:00Z
        val chat = file(messages = listOf(
            record(name = "助手", message = "你好", sendDate = "1672531200000")
        ))
        assertEquals("[2023-01-01 00:00] 助手: 你好", TavernChatExportCodec.toTxt(chat))
    }

    @Test
    fun ISO时间字符串也能格式化为文本时间前缀() {
        val chat = file(messages = listOf(
            record(name = "助手", message = "你好", sendDate = "2023-01-01T00:00:00Z")
        ))
        assertEquals("[2023-01-01 00:00] 助手: 你好", TavernChatExportCodec.toTxt(chat))
    }

    @Test
    fun json中的毫秒戳规范化为数字时间戳() {
        val chat = file(messages = listOf(
            record(name = "助手", message = "你好", sendDate = "1672531200000")
        ))
        val json = TavernChatExportCodec.toJson(chat)
        assertTrue(json.contains("\"timestamp\":1672531200000"))
    }

    @Test
    fun 非法时间在文本中保留原文并在json中省略时间戳() {
        val chat = file(messages = listOf(
            record(name = "助手", message = "你好", sendDate = "not-a-date")
        ))
        assertEquals("[not-a-date] 助手: 你好", TavernChatExportCodec.toTxt(chat))
        val json = TavernChatExportCodec.toJson(chat)
        assertFalse(json.contains("timestamp"))
    }
}