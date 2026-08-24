package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.TavernMacroContext
import com.loyea.plugins.tavern.core.TavernPresetPrompt
import com.loyea.plugins.tavern.core.TavernPromptPreset
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
    fun messageTimestampCanReplaceRepeatedSystemTimeSnapshot() {
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "保持角色设定"),
            userName = "Eddy",
            useSystemTime = true,
            includeSystemTimeInSnapshot = false,
            enableVoice = false,
            trustedCard = true
        )

        assertFalse(parts.turnContextSnapshot.contains("System Time:"))
        assertTrue(parts.stableSystemPrompt.contains("[MESSAGE TIME: ...]"))
    }

    @Test
    fun fullCardFieldsAndMacrosAreRendered() {
        val card = card(systemPrompt = "设定 {{char}} 应称呼 {{user}}，{{description}}").copy(
            description = "完整描述",
            creatorNotes = "作者备注",
            characterVersion = "2.1",
            postHistoryInstructions = "历史之后仍要记住 {{charCreatorNotes}} / {{charVersion}}",
            alternateGreetings = listOf("备用首句")
        )
        val parts = PromptAssembler.assemblePromptParts(
            card = card,
            userName = "Eddy",
            enableVoice = false,
            trustedCard = true
        )
        assertTrue(parts.stableSystemPrompt.contains("完整描述"))
        assertTrue(parts.stableSystemPrompt.contains("Test Character 应称呼 Eddy，完整描述"))
        assertEquals("历史之后仍要记住 作者备注 / 2.1", parts.postHistoryInstructions)
    }

    @Test
    fun outletMacroResolvesNamedWorldInfoWithoutUnconditionalLeakage() {
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "Use {{outlet::lore}} when relevant."),
            userName = "Eddy",
            worldInfoRender = com.loyea.context.core.WorldInfoMatcher.WorldInfoRenderResult(
                all = "legacy should not be duplicated",
                outlets = mapOf("Lore" to "secret lore")
            ),
            enableVoice = false,
            trustedCard = true
        )

        assertTrue(parts.stableSystemPrompt.contains("secret lore"))
        assertFalse(parts.turnContextSnapshot.contains("WORLD INFO OUTLET"))
        assertFalse(parts.stableSystemPrompt.contains("{{outlet::"))
    }

    @Test
    fun presetSlotsUseTheSameFrozenMacroContextAsTheSystemPrompt() {
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "core {{char}}"),
            userName = "Eddy",
            preset = TavernPromptPreset(
                prompts = listOf(
                    TavernPresetPrompt(
                        name = "User slot",
                        identifier = "user_slot",
                        role = "user",
                        content = "{{char}}/{{user}}/{{lastGenerationType}}/{{lastUserMessage}}"
                    )
                )
            ),
            generationType = "continue",
            macroContext = TavernMacroContext(
                characterName = "Lya",
                userName = "Eddy",
                generationType = "continue",
                lastUserMessage = "继续刚才的话"
            ),
            enableVoice = false,
            trustedCard = true
        )

        assertEquals(
            "Lya/Eddy/continue/继续刚才的话",
            parts.presetMessages.single().content
        )
        assertTrue(parts.stableSystemPrompt.contains("core Lya"))
    }

    @Test
    fun presetSlotsCanReadFrozenWorldInfoOutlets() {
        val parts = PromptAssembler.assemblePromptParts(
            card = card(systemPrompt = "core"),
            userName = "Eddy",
            preset = TavernPromptPreset(
                prompts = listOf(
                    TavernPresetPrompt(
                        name = "Lore slot",
                        identifier = "lore_slot",
                        role = "system",
                        content = "{{outlet::lore}}"
                    )
                )
            ),
            worldInfoRender = com.loyea.context.core.WorldInfoMatcher.WorldInfoRenderResult(
                all = "",
                outlets = mapOf("Lore" to "frozen lore")
            ),
            macroContext = TavernMacroContext(
                characterName = "Lya",
                userName = "Eddy",
                outlets = mapOf("Existing" to "existing outlet")
            ),
            enableVoice = false,
            trustedCard = true
        )

        assertTrue(parts.stableSystemPrompt.contains("frozen lore"))
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
