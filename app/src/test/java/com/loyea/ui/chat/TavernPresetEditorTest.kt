package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.TavernPresetCodec
import com.loyea.plugins.tavern.core.TavernPresetPrompt
import com.loyea.plugins.tavern.core.TavernPresetPromptOrder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2 预设编辑器纯逻辑测试：基础 9 槽位模板、JSON 往返序列化、槽位可用性筛选、保存校验与本地化。
 */
class TavernPresetEditorTest {

    // 基础 9 槽位标识符（对齐 Tavo 预设页语义）
    private val baseIdentifiers = listOf(
        "user_identity", "character_setting", "personality", "scenario", "example_chat",
        "new_chat", "group_chat_progression", "continue_progression", "ai_assistance"
    )

    @Test
    fun defaultSlotsCoversAllNineBaseSlots() {
        val slots = TavernPresetEditor.defaultSlots()
        assertEquals(9, slots.size)
        assertEquals(baseIdentifiers, slots.map { it.identifier })
        assertTrue(slots.all { it.enabled })
    }

    @Test
    fun defaultPresetRoundTripsThroughParser() {
        val preset = TavernPresetEditor.defaultPreset("Test preset")
        // 保存时序列化为 registry 的 rawJson
        val json = TavernPresetEditor.buildPresetJson(preset)
        // 读取端仍走现有 tavern-core codec
        val parsed = requireNotNull(TavernPresetCodec.parse(json))

        assertEquals("Test preset", parsed.name)
        assertEquals(0.7, parsed.temperature!!, 0.001)
        assertEquals(1024, parsed.maxTokens)
        // 全部 9 槽位与顺序经往返后保持一致
        assertEquals(baseIdentifiers, parsed.orderedPrompts().map { it.identifier })
        assertEquals(
            baseIdentifiers,
            parsed.promptOrder.map { it.identifier }
        )
    }

    @Test
    fun buildPresetJsonpreservesEnabledStateAndContent() {
        // 模拟用户关闭一个槽位并重排
        val drafts = TavernPresetEditor.defaultSlots()
            .mapIndexed { index, slot -> if (index == 0) slot.copy(enabled = false, content = "IGNORED") else slot }
        val prompts = drafts.mapIndexed { index, slot -> slot.toPrompt(index) }
        val order = prompts.mapIndexed { index, prompt ->
            TavernPresetPromptOrder(prompt.identifier, prompt.enabled, index)
        }
        val preset = com.loyea.plugins.tavern.core.TavernPromptPreset(
            name = "P", prompts = prompts, promptOrder = order
        )
        val parsed = requireNotNull(TavernPresetCodec.parse(TavernPresetEditor.buildPresetJson(preset)))

        assertFalse(parsed.prompts.first { it.identifier == "user_identity" }.enabled)
        assertTrue(parsed.prompts.first { it.identifier == "personality" }.enabled)
        assertEquals(
            "IGNORED",
            parsed.prompts.first { it.identifier == "user_identity" }.content
        )
    }

    @Test
    fun canAddSlotExcludesSlotAlreadyPresent() {
        val oneEmptyPrompt = TavernPresetPrompt("", "user_identity", "")
        val available = TavernPresetEditor.canAddSlot(listOf(oneEmptyPrompt))
        assertFalse(available.contains("user_identity"))
        assertTrue(available.contains("personality"))
        assertEquals(8, available.size)
    }

    @Test
    fun validatePresetRequiresNameAndAtLeastOneEnabledSlot() {
        assertFalse(TavernPresetEditor.validatePreset("", TavernPresetEditor.defaultSlots().map { it.toPrompt(0) }))
        assertTrue(TavernPresetEditor.validatePreset("P", TavernPresetEditor.defaultSlots().map { it.toPrompt(0) }))
        // 全部槽位关闭但名称非空 → 不可保存
        val allDisabled = TavernPresetEditor.defaultSlots()
            .map { it.copy(enabled = false) }
            .map { it.toPrompt(0) }
        assertFalse(TavernPresetEditor.validatePreset("P", allDisabled))
        // 无槽位同样不可保存
        assertFalse(TavernPresetEditor.validatePreset("P", emptyList()))
    }

    @Test
    fun slotLabelLocalizesBothLanguages() {
        assertEquals("性格", TavernPresetEditor.slotLabel("personality", false))
        assertEquals("Personality", TavernPresetEditor.slotLabel("personality", true))
        // 未知标识符回退到自身
        assertEquals("custom", TavernPresetEditor.slotLabel("custom", false))
    }

    @Test
    fun newSlotBuildsEnabledDraftWithTemplateContent() {
        val slot = TavernPresetEditor.newSlot("scenario", false)
        assertEquals("scenario", slot.identifier)
        assertTrue(slot.enabled)
        assertTrue(slot.content.isNotBlank())
        assertEquals("场景", slot.label)
    }
}