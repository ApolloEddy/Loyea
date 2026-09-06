package com.loyea.ui.chat

import com.loyea.character.core.worldinfo.WorldInfoConfig as CoreWorldInfoConfig
import com.loyea.character.core.worldinfo.WorldInfoEntry as CoreWorldInfoEntry
import com.loyea.character.core.worldinfo.WorldInfoMatcher as CoreWorldInfoMatcher
import kotlin.random.Random

/**
 * 世界书桥：把 0.5.5 本地世界书条目（ui.chat.WorldInfoEntry）投影为
 * character-core 的运行时条目，并统一走唯一匹配实现（Spec §10：只有一个世界书执行实现）。
 *
 * 行为差异说明（均为 Spec §6 要求的修正，非回归）：
 * - entry.depth 不再被当作扫描窗口（扫描深度只由 scan_depth 配置决定）；
 * - 预算按整条取舍，被预算排除的内容不再触发递归。
 */
object WorldInfoBridge {

    fun toCoreEntry(entry: WorldInfoEntry): CoreWorldInfoEntry = CoreWorldInfoEntry(
        id = entry.id,
        keywords = entry.keywords,
        content = entry.content,
        enabled = entry.enabled,
        uid = entry.uid,
        keysecondary = entry.keysecondary,
        constant = entry.constant,
        order = entry.order,
        depth = entry.depth,
        comment = entry.comment,
        selective = entry.selective,
        disable = entry.disable,
        selectiveLogic = entry.selectiveLogic,
        group = entry.group,
        probability = entry.probability,
        useProbability = entry.useProbability,
        delayUntilRecursion = entry.delayUntilRecursion,
        preventRecursion = entry.preventRecursion,
        allowRecursion = entry.allowRecursion,
        excludeRecursion = entry.excludeRecursion,
        keysContainedIn = entry.keysContainedIn,
        position = entry.position,
        weight = entry.weight,
        // 0.5.5 本地书无逐条插入位置语义：legacy 桶整体渲染
        positionType = "legacy"
    )

    fun toCoreEntries(entries: List<WorldInfoEntry>): List<CoreWorldInfoEntry> =
        entries.map(::toCoreEntry)

    fun toCoreConfig(config: WorldInfoConfig): CoreWorldInfoConfig = CoreWorldInfoConfig(
        scanDepth = config.scanDepth,
        position = config.position,
        insertionOrderMode = when (config.insertionOrderMode) {
            WorldInfoInsertionOrder.ORDER -> com.loyea.character.core.worldinfo.WorldInfoInsertionOrder.ORDER
            WorldInfoInsertionOrder.KEY_LENGTH -> com.loyea.character.core.worldinfo.WorldInfoInsertionOrder.KEY_LENGTH
            WorldInfoInsertionOrder.ALPHABETICAL -> com.loyea.character.core.worldinfo.WorldInfoInsertionOrder.ALPHABETICAL
            WorldInfoInsertionOrder.INSERT_AT_TOP -> com.loyea.character.core.worldinfo.WorldInfoInsertionOrder.INSERT_AT_TOP
            WorldInfoInsertionOrder.INSERT_AT_BOTTOM -> com.loyea.character.core.worldinfo.WorldInfoInsertionOrder.INSERT_AT_BOTTOM
        },
        tokenBudget = config.tokenBudget,
        recursionDepthCap = config.recursionDepthCap,
        allowRecursion = config.allowRecursion,
        emitGroupHeaders = config.emitGroupHeaders
    )

    /**
     * 0.5.5 兼容路径：全局/会话书整体渲染为单块（保持既有 bottom/top 注入语义）。
     * 概率随机源由调用方传入稳定种子，同轮重试可复现同一注入集合。
     */
    fun blockFor(
        entries: List<WorldInfoEntry>,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        random: Random
    ): String? = CoreWorldInfoMatcher.worldInfoBlockFor(
        entries = toCoreEntries(entries),
        historyContents = historyContents,
        userName = userName,
        systemPrompt = systemPrompt,
        config = toCoreConfig(config),
        random = random
    )
}
