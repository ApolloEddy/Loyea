package com.loyea.character.core.prompt

import com.loyea.character.core.api.CharacterOrigin
import com.loyea.character.core.api.CharacterProfile
import com.loyea.character.core.api.PromptBlock
import com.loyea.character.core.api.PromptBlockCategory
import com.loyea.character.core.api.TurnInput
import com.loyea.character.core.worldinfo.WorldInfoConfig
import com.loyea.character.core.worldinfo.WorldInfoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词编译顺序合同测试（Spec §5.1 固定顺序 / §12.1 合成世界书）。
 */
class CharacterCompilerTest {

    private fun profile(
        name: String = "测试角色",
        systemPrompt: String = "扮演{{char}}。",
        description: String = "这是{{user}}的朋友。",
        postHistory: String = "始终以动作描写收尾。"
    ) = CharacterProfile(
        id = "char_test",
        name = name,
        description = description,
        personality = "温柔",
        scenario = "茶室",
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistory,
        mesExample = "<START>\n{{user}}: 你好\n{{char}}: 嗯。",
        origin = CharacterOrigin.IMPORTED
    )

    private fun input(
        entries: List<WorldInfoEntry>,
        history: List<String> = listOf("user", "去诊所"),
        seed: Long = 42L
    ) = TurnInput(
        requestId = "req-1",
        sessionId = "sess-1",
        bindingRevision = 1L,
        characterRevision = 1L,
        generationKind = "normal",
        historyContents = history,
        userName = "小明",
        worldInfoEntries = entries,
        worldInfoConfig = WorldInfoConfig(),
        randomSeed = seed
    )

    private fun book() = listOf(
        WorldInfoEntry(id = "b:A", keywords = emptyList(), uid = 1, content = "A常驻设定", constant = true, order = 1, positionType = "before_char"),
        WorldInfoEntry(id = "b:B", uid = 2, keywords = listOf("诊所"), content = "B提到茶室", order = 2, positionType = "before_char"),
        WorldInfoEntry(id = "b:C", uid = 3, keywords = listOf("茶室"), content = "C茶室规则", order = 3, positionType = "after_char"),
        WorldInfoEntry(
            id = "b:D", uid = 4, keywords = listOf("诊所"), content = "D旧版全局条目",
            order = 4, positionType = "legacy"
        )
    )

    private fun compile(
        profile: CharacterProfile = profile(),
        entries: List<WorldInfoEntry> = book(),
        history: List<String> = listOf("user", "去诊所"),
        seed: Long = 42L,
        hostBlocks: List<PromptBlock> = listOf(
            PromptBlock("host.0", PromptBlockCategory.HOST, "[HOST PROTOCOL]", PromptBlock.SLOT_HOST)
        ),
        memoryBlocks: List<PromptBlock> = listOf(
            PromptBlock("memory.0", PromptBlockCategory.MEMORY, "[CORE MEMORY]", PromptBlock.SLOT_MEMORY)
        )
    ) = CharacterCompiler.prepare(profile, input(entries, history, seed), hostBlocks, memoryBlocks)

    @Test
    fun `fixed order contract - host system before_char fields after_char examples memory`() {
        val prepared = compile()
        val ids = prepared.blocks.map { it.sourceId }
        val expected = listOf(
            "host.0",              // 1 宿主
            "char.system_prompt",  // 2 角色指令
            "world.before_char",   // 3 before_char
            "char.description",    // 4 角色字段
            "char.personality",
            "char.scenario",
            "world.after_char",    // 5 after_char
            "world.legacy",        // 5b 旧版书
            "char.mes_example",    // 6 示例
            "memory.0"             // 7 记忆
        )
        assertEquals(expected, ids)
        // 槽位单调不减
        val slots = prepared.blocks.map { it.slot }
        assertEquals(slots, slots.sorted())
    }

    @Test
    fun `post history instructions returned separately after history`() {
        val prepared = compile()
        val phi = prepared.postHistoryBlock
        assertEquals(PromptBlock.SLOT_POST_HISTORY, phi!!.slot)
        assertTrue(phi.text.contains("历史后指令"))
        assertTrue(phi.text.contains("始终以动作描写收尾"))
    }

    @Test
    fun `empty post history generates no block`() {
        val prepared = compile(profile = profile(postHistory = ""))
        assertNull(prepared.postHistoryBlock)
    }

    @Test
    fun `macros expanded in character fields and world content`() {
        val prepared = compile()
        val system = prepared.systemText()
        assertTrue(system.contains("扮演测试角色。"))
        assertTrue(system.contains("这是小明的朋友。"))
        assertFalse(system.contains("{{char}}"))
        assertFalse(system.contains("{{user}}"))
    }

    @Test
    fun `blank character system prompt falls back to modest default`() {
        val prepared = compile(profile = profile(systemPrompt = "  "))
        val block = prepared.blocks.first { it.sourceId == "char.system_prompt" }
        assertTrue(block.text.contains("You are 测试角色"))
    }

    @Test
    fun `before and after char buckets contain matched entries`() {
        val prepared = compile()
        val before = prepared.blocks.first { it.sourceId == "world.before_char" }.text
        val after = prepared.blocks.first { it.sourceId == "world.after_char" }.text
        val legacy = prepared.blocks.first { it.sourceId == "world.legacy" }.text
        assertTrue(before.contains("A常驻设定"))
        assertTrue(before.contains("B提到茶室"))
        assertTrue(after.contains("C茶室规则"))
        assertTrue(legacy.contains("D旧版全局条目"))
        // B 正文里的「茶室」触发 C → C 进 after_char 桶（本合成卡 C 未指定位置默认 after_char）
        assertTrue(prepared.worldInfoTrace.selectedIds.containsAll(listOf("b:A", "b:B", "b:C", "b:D")))
    }

    @Test
    fun `constant overflow surfaces as recoverable error`() {
        val bigConstant = WorldInfoEntry(
            id = "b:BIG", keywords = emptyList(), uid = 9, content = "超长常驻".repeat(3000),
            constant = true, order = 1
        )
        val prepared = compile(entries = listOf(bigConstant), history = listOf("hi"))
        assertTrue(prepared.constantOverflow)
    }

    @Test
    fun `deterministic replay with same seed`() {
        val a = compile(seed = 7L)
        val b = compile(seed = 7L)
        assertEquals(a.systemText(), b.systemText())
        assertEquals(a.selectedWorldInfo.map { it.id }, b.selectedWorldInfo.map { it.id })
    }

    @Test
    fun `greetings expand macros`() {
        val p = CharacterProfile(
            id = "g", name = "小铃", firstMessage = "{{user}}，我是{{char}}！",
            alternateGreetings = listOf("备用：{{char}}在此"), origin = CharacterOrigin.IMPORTED
        )
        val greetings = CharacterCompiler.greetings(p, "阿波罗")
        assertEquals(listOf("阿波罗，我是小铃！", "备用：小铃在此"), greetings)
    }
}
