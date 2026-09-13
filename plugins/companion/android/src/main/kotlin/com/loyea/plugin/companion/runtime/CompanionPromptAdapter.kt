package com.loyea.plugin.companion.runtime

import com.loyea.plugin.modulator.Context as ModulatorContext
import com.loyea.plugin.modulator.Decision
import com.loyea.plugin.modulator.LoreRule
import com.loyea.plugin.modulator.ModulatorVocab
import com.loyea.plugin.modulator.Row
import com.loyea.plugin.modulator.StateProjection
import com.loyea.plugin.modulator.selectLore

/**
 * 陪伴请求投影适配器（Spec 接入文档 §8.2/§8.3）：纯投影 → 状态行 + 私有 Lore
 * 选择 → 结构化动态模块渲染 + 预算闭合。
 *
 * - 生成层只收到三列表格 + 解释语义 + 选中条目文本；不发送连续量、12 头、
 *   置信度、未选 Lore、算法审计或内部原因码。
 * - 动态模块默认预算 768 预算 tokens（项目初值，estimated 标记）；
 *   状态行是核心规则优先保留，超预算时按选中顺序丢弃 Lore 条目，不回填。
 * - 模块使用稳定标记包裹，工具子轮内可整体替换为更新的 requestRevision。
 */
object CompanionPromptAdapter {

    const val STATE_BLOCK_START = "[CURRENT COMPANION STATE / 当前内部状态]"
    const val STATE_BLOCK_END = "[END CURRENT COMPANION STATE]"
    const val LORE_BLOCK_START = "[COMPANION PRIVATE LORE / 私有情境约束]"
    const val LORE_BLOCK_END = "[END COMPANION PRIVATE LORE]"
    const val PRIVATE_BOOK_ID = "loyea_private_lore_v1"
    const val DEFAULT_DYNAMIC_BUDGET_TOKENS = 768L

    /** 私有书解析（Spec §8.1）：固定 book ID + 版本校验，缺失/损坏返回 null（NONE，无全局回退）。 */
    fun resolvePrivateLore(loreProfileVersion: String?): Pair<String, List<LoreRule>>? {
        if (loreProfileVersion != null && loreProfileVersion != CompanionProfileRepository.LORE_PROFILE_VERSION) {
            return null
        }
        return PRIVATE_BOOK_ID to CompanionProfileRepository.privateLoreRules()
    }

    data class RenderedModule(
        val text: String,
        val selectedLoreIds: List<String>,
        val rows: List<Row>,
        /** 各行的支持证据 id（feeling 行）；预算闭合与诊断用。 */
        val sources: Map<String, List<String>>,
        val budgetTokens: Long,
        val budgetEstimated: Boolean,
    )

    /**
     * 渲染动态模块。visibleEvidence 已由调用方按"最终允许且确实可见"的证据
     * 计算（过滤在核心投影内完成）；本方法不做第二次状态选择。
     */
    fun render(
        projection: StateProjection,
        activeTags: Set<String>,
        budgetTokenCap: Long = DEFAULT_DYNAMIC_BUDGET_TOKENS,
        estimate: (String) -> Long,
    ): RenderedModule {
        val rules = CompanionProfileRepository.privateLoreRules()

        // 确定性条目选择：复用核心 selectLore（≤6 条 / 1600 字符粗上限）。
        val syntheticDecision = Decision("projection", 0L, projection.rows, emptyList())
        val selectedIds = selectLore(
            syntheticDecision,
            ModulatorContext(activeTags = activeTags),
            rules,
            maxEntries = 6,
            charBudget = 1600,
        )
        val rulesById = rules.associateBy { it.entryId }

        val stateBlock = buildString {
            append(STATE_BLOCK_START).append('\n')
            append("| aspect | state | intensity |").append('\n')
            append("|---|---|---|").append('\n')
            for (row in projection.rows) {
                append("| ").append(ModulatorVocab.ASPECT_ZH[row.aspect] ?: row.aspect)
                    .append(" | ").append(ModulatorVocab.STATE_ZH[row.state] ?: row.state)
                    .append(" | ").append(ModulatorVocab.LEVEL_ZH[row.intensity] ?: row.intensity)
                    .append(" |").append('\n')
            }
            append(ModulatorVocab.INTERPRETATION).append('\n')
            append(STATE_BLOCK_END).append('\n')
        }
        var budget = estimate(stateBlock)

        val loreLines = mutableListOf<String>()
        val keptIds = mutableListOf<String>()
        for (id in selectedIds) {
            val rule = rulesById.getValue(id)
            val line = "- " + rule.text + "\n"
            val cost = estimate(line)
            if (budget + cost > budgetTokenCap) continue // 预算闭合：只删减，不回填
            loreLines += line.trimEnd('\n')
            keptIds += id
            budget += cost
        }
        val sb = StringBuilder(stateBlock)
        if (loreLines.isNotEmpty()) {
            sb.append(LORE_BLOCK_START).append('\n')
            loreLines.forEach { sb.append(it).append('\n') }
            sb.append(LORE_BLOCK_END).append('\n')
        }
        return RenderedModule(
            text = sb.toString(),
            selectedLoreIds = keptIds,
            rows = projection.rows,
            sources = projection.sources,
            budgetTokens = budget,
            budgetEstimated = true,
        )
    }

    /** 工具子轮：整体替换既有模块（同一请求内只保留一份当前状态）。 */
    fun replaceModuleInUserContent(content: String, newModule: String): String {
        val sb = StringBuilder(content.length + newModule.length)
        var index = 0
        while (true) {
            val start = content.indexOf(STATE_BLOCK_START, index)
            if (start < 0) {
                sb.append(content, index, content.length)
                break
            }
            val stateEnd = content.indexOf(STATE_BLOCK_END, start)
            if (stateEnd < 0) {
                sb.append(content, index, content.length)
                break
            }
            var stop = stateEnd + STATE_BLOCK_END.length
            val loreStart = content.indexOf(LORE_BLOCK_START, stop)
            if (loreStart == stop + 1 || loreStart == stop) {
                val loreEnd = content.indexOf(LORE_BLOCK_END, loreStart)
                if (loreEnd >= 0) stop = loreEnd + LORE_BLOCK_END.length
            }
            sb.append(content, index, start)
            sb.append(newModule)
            index = stop
        }
        return sb.toString()
    }
}
