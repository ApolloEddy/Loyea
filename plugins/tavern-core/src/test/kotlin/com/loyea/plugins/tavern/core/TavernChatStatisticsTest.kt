package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * TavernChatStatistics 的单元测试。
 * 覆盖空会话、仅系统消息、正常混合会话、跨年陪伴天数、以及含 swipes 选中文本的字数统计。
 */
class TavernChatStatisticsTest {

    /**
     * 构造一条规范化消息的便捷方法，便于测试专注统计语义本身。
     *
     * @param text 消息原文（对应 message 字段）。
     * @param isUser 是否为用户消息。
     * @param isSystem 是否为系统消息。
     * @param sendDate 发送时间（ISO 8601 字符串或毫秒时间戳）。
     * @param swipes 可选的情感回退（swipes）文本列表。
     * @param swipeId 当前选中的情感下标。
     */
    private fun msg(
        text: String,
        isUser: Boolean = false,
        isSystem: Boolean = false,
        sendDate: String? = null,
        swipes: List<String> = emptyList(),
        swipeId: Int = 0
    ) = TavernChatMessageRecord(
        message = text,
        isUser = isUser,
        isSystem = isSystem,
        sendDate = sendDate,
        swipes = swipes,
        swipeId = swipeId
    )

    @Test
    fun 空会话返回全零统计() {
        // 空输入：天数、双方消息数、总字数都应为 0。
        val result = TavernChatStatistics.calculate(emptyList(), LocalDate.of(2024, 1, 5))

        assertEquals(0L, result.companionshipDays)
        assertEquals(0L, result.userMessageCount)
        assertEquals(0L, result.characterMessageCount)
        assertEquals(0L, result.totalCharacterCount)
        assertEquals(null, result.firstMessageDate)
    }

    @Test
    fun 仅系统消息同样全零() {
        // 只有系统消息时，任何统计都不应计入，天数同样为 0。
        val messages = listOf(
            msg(text = "系统提示", isSystem = true, sendDate = "2024-01-01T08:00:00Z"),
            msg(text = "另一条系统消息", isSystem = true, sendDate = "1704067200000")
        )

        val result = TavernChatStatistics.calculate(messages, LocalDate.of(2024, 1, 5))

        assertEquals(0L, result.companionshipDays)
        assertEquals(0L, result.userMessageCount)
        assertEquals(0L, result.characterMessageCount)
        assertEquals(0L, result.totalCharacterCount)
    }

    @Test
    fun 正常混合会话正确统计双方数量与总字数() {
        // 用户消息 1 条、角色消息 1 条、系统消息 1 条，统计日与首条消息同一天。
        val messages = listOf(
            msg(text = "你好", isUser = true, sendDate = "2024-01-01T09:00:00Z"),
            msg(text = "你好呀", isUser = false, sendDate = "2024-01-01T09:05:00Z"),
            msg(text = "系统消息不计入", isSystem = true, sendDate = "2024-01-01T09:10:00Z")
        )

        val result = TavernChatStatistics.calculate(messages, LocalDate.of(2024, 1, 1))

        // 用户 1 条、角色 1 条，系统消息被排除。
        assertEquals(1L, result.userMessageCount)
        assertEquals(1L, result.characterMessageCount)
        // 只有用户消息与角色消息参与总字数；「你好」2 个字 + 「你好呀」3 个字 = 5。
        assertEquals(5L, result.totalCharacterCount)
        // 与首条消息同日统计，陪伴天数为 1（含首日）。
        assertEquals(1L, result.companionshipDays)
        assertEquals(LocalDate.of(2024, 1, 1), result.firstMessageDate)
    }

    @Test
    fun 跨年陪伴天数按自然日计算() {
        // 首条消息在 2023-12-31，统计日为 2024-01-03，跨年。系统消息不计入最早日期。
        val messages = listOf(
            msg(text = "跨年前的消息", isUser = true, sendDate = "2023-12-31T23:00:00Z"),
            msg(text = "系统消息被忽略", isSystem = true, sendDate = "2023-12-30T00:00:00Z"),
            msg(text = "跨年后的消息", isUser = false, sendDate = "2024-01-01T00:30:00Z")
        )

        val result = TavernChatStatistics.calculate(messages, LocalDate.of(2024, 1, 3))

        // 最早的非系统消息日期是 2023-12-31，到 2024-01-03 相差 3 天，含首日共 4 天。
        assertEquals(4L, result.companionshipDays)
        assertEquals(LocalDate.of(2023, 12, 31), result.firstMessageDate)
        assertEquals(1L, result.userMessageCount)
        assertEquals(1L, result.characterMessageCount)
    }

    @Test
    fun 字数统计使用swipes选中文本而非message原文() {
        // 选中第 1 条情感（较长文本），字数必须使用选中文本，而非 message 字段的短文本。
        val messages = listOf(
            msg(
                text = "短原文",
                isUser = false,
                sendDate = "1704067200000",
                swipes = listOf("短原文", "这是很长的一段选中文本"),
                swipeId = 1
            )
        )

        val result = TavernChatStatistics.calculate(messages, LocalDate.of(2024, 1, 1))

        // 「这是很长的一段选中文本」为 11 个字符，必须使用选中文本长度而非 message 原文。
        assertEquals(11L, result.totalCharacterCount)
    }

    @Test
    fun 无法解析sendDate的消息被容错跳过() {
        // sendDate 格式非法时应跳过该消息，不因解析失败抛出异常。
        val messages = listOf(
            msg(text = "消息一", isUser = true, sendDate = "not-a-date"),
            msg(text = "消息二", isUser = false, sendDate = "2024-01-02T00:00:00Z")
        )

        // 不抛异常，且只有能解析的消息参与计算。
        val result = TavernChatStatistics.calculate(messages, LocalDate.of(2024, 1, 3))

        assertEquals(1L, result.userMessageCount)
        assertEquals(1L, result.characterMessageCount)
        // 「消息一」(3字) + 「消息二」(3字) = 6 个字符。
        assertEquals(6L, result.totalCharacterCount)
        assertEquals(LocalDate.of(2024, 1, 2), result.firstMessageDate)
        assertEquals(2L, result.companionshipDays)
    }
}