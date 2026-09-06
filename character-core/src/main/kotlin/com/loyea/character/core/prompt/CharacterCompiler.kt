package com.loyea.character.core.prompt

import com.loyea.character.core.api.CharacterProfile
import com.loyea.character.core.api.PromptBlock
import com.loyea.character.core.api.PromptBlockCategory
import com.loyea.character.core.api.TurnInput
import com.loyea.character.core.worldinfo.WorldInfoEntry
import com.loyea.character.core.worldinfo.WorldInfoMatcher
import kotlin.random.Random

/**
 * 角色提示词编译器（Spec §5.1 固定顺序合同）。
 *
 * 纯函数：固定输入（profile + TurnInput + 宿主/记忆块）得到确定输出，
 * 工具子轮与网络重试复用同一 PreparedCharacterTurn（Spec §5.3）。
 * 本类不发起请求、不写存储、不为任何 provider 开分支。
 */
object CharacterCompiler {

    /** 未实现插入位置的报告（不得重映射成 before_char/after_char，Spec §5.1）。 */
    data class UnsupportedPosition(val entryId: String, val positionType: String)

    data class PreparedCharacterTurn(
        /** 系统侧有序块（宿主/角色/世界书/记忆），宿主按槽位拼接为 system 消息 */
        val blocks: List<PromptBlock>,
        /** 角色历史后指令（槽位 9），为空表示卡未提供，不得生成空块（Spec §4.3） */
        val postHistoryBlock: PromptBlock?,
        val selectedWorldInfo: List<WorldInfoEntry>,
        val worldInfoTrace: WorldInfoMatcher.WorldInfoTrace,
        /** 常驻条目放不进预算：宿主应显示可恢复错误并暂停发送（Spec §6.2.6） */
        val constantOverflow: Boolean,
        val unsupportedPositions: List<UnsupportedPosition>,
        val input: TurnInput
    ) {
        fun systemText(): String = blocks.joinToString("\n\n") { it.text }.trim()
    }

    /**
     * 编译一个回合。
     *
     * @param hostBlocks 宿主能力与工具约束块（槽位 1，按宿主给定顺序；不注入额外人格，Spec §5.1.1）
     * @param memoryBlocks 会话长期摘要 / 本体记忆 / 图谱块（槽位 7，带来源标签）
     */
    fun prepare(
        profile: CharacterProfile,
        input: TurnInput,
        hostBlocks: List<PromptBlock> = emptyList(),
        memoryBlocks: List<PromptBlock> = emptyList()
    ): PreparedCharacterTurn {
        val macros = MacroExpander(profile.name, input.userName)

        // —— 世界书匹配（一次性、可重放；随机源由 randomSeed 固定）——
        val matchResult = WorldInfoMatcher.matchWorldInfoEntriesFor(
            entries = input.worldInfoEntries,
            historyContents = input.historyContents,
            userName = input.userName,
            systemPrompt = profile.systemPrompt,
            config = input.worldInfoConfig,
            random = Random(input.randomSeed),
            characterDescription = profile.description,
            characterPersonality = profile.personality,
            characterDepthPrompt = profile.systemPrompt,
            scenario = profile.scenario,
            turnKey = "${input.sessionId}:${input.characterRevision}",
            generationType = normalizeGeneration(input.generationKind)
        )
        val selected = matchResult.entries
        val unsupportedPositions = selected
            .filter { it.positionType !in SUPPORTED_POSITIONS }
            .map { UnsupportedPosition(it.id, it.positionType) }
        val beforeChar = selected.filter { it.positionType == "before_char" }
        val afterChar = selected.filter { it.positionType == "after_char" }

        // —— 固定顺序块（Spec §5.1）——
        val blocks = ArrayList<PromptBlock>(16)
        blocks += hostBlocks

        // 2. 角色 systemPrompt；为空时才使用适度的通用默认值
        val systemPrompt = profile.systemPrompt.trim().ifBlank {
            "You are ${macros.safeChar}. Stay in character and respond naturally."
        }
        blocks += PromptBlock(
            sourceId = "char.system_prompt",
            category = PromptBlockCategory.CHARACTER,
            text = "[Character System / 角色核心指令]\n${macros.expand(systemPrompt)}",
            slot = PromptBlock.SLOT_CHAR_SYSTEM
        )

        // 3. before_char 世界书（含常驻与条件命中）
        renderWorldBucket("before_char", beforeChar, macros)?.let {
            blocks += it
        }

        // 4. 角色字段：description / personality / scenario 保持字段界限
        if (profile.description.isNotBlank()) {
            blocks += PromptBlock(
                sourceId = "char.description",
                category = PromptBlockCategory.CHARACTER,
                text = "[Character Description / 角色描述]\n${macros.expand(profile.description.trim())}",
                slot = PromptBlock.SLOT_CHAR_FIELDS
            )
        }
        if (profile.personality.isNotBlank()) {
            blocks += PromptBlock(
                sourceId = "char.personality",
                category = PromptBlockCategory.CHARACTER,
                text = "[Personality / 性格]\n${macros.expand(profile.personality.trim())}",
                slot = PromptBlock.SLOT_CHAR_FIELDS
            )
        }
        if (profile.scenario.isNotBlank()) {
            blocks += PromptBlock(
                sourceId = "char.scenario",
                category = PromptBlockCategory.CHARACTER,
                text = "[Scenario / 场景]\n${macros.expand(profile.scenario.trim())}",
                slot = PromptBlock.SLOT_CHAR_FIELDS
            )
        }

        // 5. after_char 世界书
        renderWorldBucket("after_char", afterChar, macros)?.let {
            blocks += it
        }

        // 5b. legacy 位置（0.5.5 全局/会话书条目）：与角色书同轮组合匹配后按固定顺序注入
        val legacyEntries = selected.filter { it.positionType == "legacy" }
        renderWorldBucket("legacy", legacyEntries, macros)?.let {
            blocks += it
        }

        // 6. 示例对话：<START> 是示例分隔标记；按原始分段保留
        if (profile.mesExample.isNotBlank()) {
            blocks += PromptBlock(
                sourceId = "char.mes_example",
                category = PromptBlockCategory.EXAMPLES,
                text = "[Example Dialogs / 对话示例]\n${macros.expand(profile.mesExample.trim())}",
                slot = PromptBlock.SLOT_EXAMPLES
            )
        }

        // 7. 会话长期摘要与本体记忆（宿主提供、带来源）
        blocks += memoryBlocks

        // 9. 历史后指令（独立返回；空不生成）
        val phiText = profile.postHistoryInstructions.trim()
        val postHistoryBlock = if (phiText.isBlank()) {
            null
        } else {
            PromptBlock(
                sourceId = "char.post_history_instructions",
                category = PromptBlockCategory.POST_HISTORY,
                text = "[Post-History Instructions / 历史后指令]\n${macros.expand(phiText)}",
                slot = PromptBlock.SLOT_POST_HISTORY
            )
        }

        return PreparedCharacterTurn(
            blocks = blocks,
            postHistoryBlock = postHistoryBlock,
            selectedWorldInfo = selected,
            worldInfoTrace = matchResult.trace,
            constantOverflow = matchResult.trace.constantOverflow,
            unsupportedPositions = unsupportedPositions,
            input = input
        )
    }

    /** 开场白（默认 + 备用）的宏展开；不参与提示词块，仅用于会话首条消息。 */
    fun greetings(profile: CharacterProfile, userName: String): List<String> {
        val macros = MacroExpander(profile.name, userName)
        val all = listOf(profile.firstMessage) + profile.alternateGreetings
        return all.filter { it.isNotBlank() }.map { macros.expand(it) }
    }

    private fun renderWorldBucket(
        position: String,
        entries: List<WorldInfoEntry>,
        macros: MacroExpander
    ): PromptBlock? {
        if (entries.isEmpty()) return null
        val body = entries.joinToString("\n") { macros.expand(it.content.trim()) }.trim()
        if (body.isEmpty()) return null
        val label = when (position) {
            "before_char" -> "角色定义前"
            "after_char" -> "角色定义后"
            else -> "世界观"
        }
        val slot = if (position == "before_char") PromptBlock.SLOT_WORLD_BEFORE_CHAR else PromptBlock.SLOT_WORLD_AFTER_CHAR
        return PromptBlock(
            sourceId = "world.$position",
            category = PromptBlockCategory.WORLD,
            text = "[World Info / 世界书 · $label]\n$body",
            slot = slot
        )
    }

    private fun normalizeGeneration(kind: String): String = kind.trim().removePrefix(":").lowercase()

    private val SUPPORTED_POSITIONS = setOf("before_char", "after_char", "legacy")
}

/**
 * 已支持宏的最小展开（Spec §8：仅角色/规则允许的文本字段）。
 * 与 0.5.5 语义一致：{{char}}/{{user}} 及所有格、多重花括号包裹；大小写不敏感。
 */
class MacroExpander(charName: String, userName: String) {
    val safeUser = userName.ifBlank { "User" }
    val safeChar = charName.ifBlank { "Char" }

    fun expand(text: String): String = text
        .replace("{{char}}'s", "${safeChar}'s", ignoreCase = true)
        .replace("{{user}}'s", "${safeUser}'s", ignoreCase = true)
        .replace("{{{char}}}", safeChar, ignoreCase = true)
        .replace("{{{user}}}", safeUser, ignoreCase = true)
        .replace("{{char}}", safeChar, ignoreCase = true)
        .replace("{{user}}", safeUser, ignoreCase = true)
}
