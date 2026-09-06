package com.loyea.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.TimeZone

class PromptAssemblerTest {

    @Test
    fun explicitCharacterActionRulePrecedesGenericFormattingRule() {
        val actionBan = "严禁输出任何动作描写、身体细节或心理活动，只回复口头话语。"
        val prompt = PromptAssembler.assembleSystemPrompt(
            card = card(systemPrompt = actionBan),
            userName = "Eddy",
            enableVoice = false,
            trustedCard = true
        )

        val cardRuleIndex = prompt.indexOf(actionBan)
        val priorityIndex = prompt.indexOf("[ROLEPLAY STYLE PRIORITY / 角色扮演风格优先级]")
        val outputConstraintIndex = prompt.indexOf("[OUTPUT FORMAT CONSTRAINT / 严格输出格式约束]")

        assertTrue("角色卡动作规则应保留在最终 prompt 中", cardRuleIndex >= 0)
        assertTrue("显式优先级应在角色卡规则之后声明", priorityIndex > cardRuleIndex)
        assertTrue("优先级应先于通用输出格式，防止后者被理解为授权", outputConstraintIndex > priorityIndex)
        assertTrue(prompt.contains("if the character settings explicitly forbid action descriptions"))
        assertTrue(prompt.contains("This formatting rule is not permission to add actions."))
    }

    @Test
    fun blankAndLongCharacterSettingsRemainSafeToAssemble() {
        val blankPrompt = PromptAssembler.assembleSystemPrompt(
            card = card(systemPrompt = "   "),
            userName = "",
            enableVoice = false,
            trustedCard = false
        )
        assertTrue(blankPrompt.contains("[ROLEPLAY STYLE PRIORITY / 角色扮演风格优先级]"))

        val longRule = "禁止动作描写。".repeat(10_000)
        val longPrompt = PromptAssembler.assembleSystemPrompt(
            card = card(systemPrompt = longRule),
            userName = "Eddy",
            enableVoice = false,
            trustedCard = true
        )
        assertTrue("极长角色卡文本不应被静默截断", longPrompt.contains(longRule))
    }

    @Test
    fun bottomDynamicContextDoesNotRewriteStableSystemPrompt() {
        val first = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "保持角色设定"),
            userName = "Eddy",
            useSystemTime = true,
            physicalContext = "Battery: 80%",
            graphMemory = "[Recall Memory: first]",
            worldInfo = "first world",
            worldInfoPosition = "bottom",
            enableVoice = false,
            trustedCard = true,
            snapshotTimeMillis = 1_725_000_000_000L,
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        )
        val second = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "保持角色设定"),
            userName = "Eddy",
            useSystemTime = true,
            physicalContext = "Battery: 20%",
            graphMemory = "[Recall Memory: second]",
            worldInfo = "second world",
            worldInfoPosition = "bottom",
            enableVoice = false,
            trustedCard = true,
            snapshotTimeMillis = 1_725_003_600_000L,
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        )

        assertEquals("易变上下文不得改写缓存前缀", first.stableSystemPrompt, second.stableSystemPrompt)
        assertFalse(first.stableSystemPrompt.contains("Battery: 80%"))
        assertFalse(first.stableSystemPrompt.contains("first world"))
        assertTrue(first.turnContextSnapshot.contains("Battery: 80%"))
        assertTrue(first.turnContextSnapshot.contains("first world"))
        assertFalse(first.turnContextSnapshot == second.turnContextSnapshot)
    }

    @Test
    fun topWorldInfoRetainsExplicitTopPlacementSemantics() {
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "保持角色设定"),
            userName = "Eddy",
            worldInfo = "top world",
            worldInfoPosition = "top",
            enableVoice = false,
            trustedCard = true
        )

        assertTrue(parts.stableSystemPrompt.contains("top world"))
        assertFalse(parts.turnContextSnapshot.contains("top world"))
    }

    @Test
    fun systemTimeLivesInTurnSnapshotNotPerMessageTags() {
        // 时间元数据唯一来源 = 回合快照 System Time；逐条 [MESSAGE TIME] 前缀已退役
        // （模型会模仿自己的输出格式导致标签复述泄露，2026-09-06 根治）
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "保持角色设定"),
            userName = "Eddy",
            useSystemTime = true,
            includeSystemTimeInSnapshot = true,
            enableVoice = false,
            trustedCard = true
        )

        assertTrue(parts.turnContextSnapshot.contains("System Time:"))
        assertFalse(parts.stableSystemPrompt.contains("[MESSAGE TIME: ...]"))
        // 全局方括号禁令已删除：卡片自定义格式（状态面板等）不再被宿主压制
        assertFalse(parts.stableSystemPrompt.contains("Never output any bracketed text"))
    }

    private fun card(systemPrompt: String) = CharacterCard(
        id = "test-card",
        name = "Test Character",
        shortIntro = "test",
        systemPrompt = systemPrompt,
        personality = "温和",
        scenario = "测试场景",
        chatExamples = "<START>\nUser: 你好\nChar: 你好"
    )
}
