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
    fun userMessageTimeMetadataWithoutAssistantTagImitation() {
        // 时间元数据 = 用户消息逐条 [MESSAGE TIME] + 回合快照 System Time；
        // assistant 消息永远不带标签（切断自我格式模仿的泄露根因，2026-09-06）
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "保持角色设定"),
            userName = "Eddy",
            useSystemTime = true,
            includeSystemTimeInSnapshot = true,
            enableVoice = false,
            trustedCard = true
        )

        assertTrue(parts.turnContextSnapshot.contains("System Time:"))
        // 元数据说明只描述用户消息带标签、assistant 不带
        assertTrue(parts.stableSystemPrompt.contains("[MESSAGE TIME: ...]"))
        assertTrue(parts.stableSystemPrompt.contains("assistant messages are never prefixed"))
        // 时间戳窄禁令：默认不输出，带「角色设定或用户明确要求」例外（用户拍板 2026-09-06）
        assertTrue(parts.stableSystemPrompt.contains("UNLESS the character settings or the user explicitly ask"))
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
