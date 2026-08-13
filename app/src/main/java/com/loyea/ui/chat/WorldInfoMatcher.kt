package com.loyea.ui.chat

import kotlin.random.Random

/**
 * 世界书匹配引擎（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 语义对齐 SillyTavern World Info v2：
 * - 关键词触发 / constant 常驻 / selective 次级关键词四种逻辑（AND_ANY / NOT_ALL / NOT_ANY / AND_ALL）
 * - 逐条目深度窗口（depth<=0 回退全局 scanDepth）
 * - 概率触发（useProbability + probability）
 * - 递归链（allowRecursion / excludeRecursion / preventRecursion / delayUntilRecursion / recursionDepthCap）
 * - 分组邻接 / 排序模式 / token 预算裁剪
 * - 输出字节稳定：概率只影响「是否注入」，不影响注入块的字节格式
 */
object WorldInfoMatcher {
    const val SOURCE_CHAT = "chat"
    const val SOURCE_USER = "user"
    const val SOURCE_SYSTEM = "system"
    const val SOURCE_WORLD = "world"

    // selectiveLogic 取值（对齐 ST camelCase 语义）
    const val AND_ANY = 0
    const val NOT_ALL = 1
    const val NOT_ANY = 2
    const val AND_ALL = 3

    /**
     * 计算匹配条目，按 config 排序/分组/预算裁剪后渲染注入块；无命中返回 null。
     * @param historyContents 最近消息 content，时间正序（条目 depth 窗口取其尾）
     * @param random 可注入的随机源（生产传会话稳定种子，测试传固定种子）
     */
    fun worldInfoBlockFor(
        entries: List<WorldInfoEntry>,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        random: Random = Random.Default
    ): String? {
        val active = entries.filter { it.enabled }
        if (active.isEmpty()) return null

        // —— 匹配（初始轮 + 递归轮），id -> entry，保持命中顺序 ——
        val matched = linkedMapOf<String, WorldInfoEntry>()

        fun tryActivate(entry: WorldInfoEntry, worldContent: String, pass: Int): Boolean {
            if (pass > 0 && (entry.excludeRecursion || !entry.allowRecursion)) return false
            if (entry.delayUntilRecursion > pass) return false
            if (isActivated(entry, historyContents, userName, systemPrompt, worldContent, config, random)) {
                matched[entry.id] = entry
                return true
            }
            return false
        }

        // 初始轮（pass=0）：delayUntilRecursion>0 的条目推迟到递归轮；constant 直通
        for (e in active) {
            if (e.delayUntilRecursion > 0) continue
            tryActivate(e, "", 0)
        }

        // 递归轮：对已命中条目 content 扫未命中条目；preventRecursion 命中后断链
        if (config.allowRecursion && config.recursionDepthCap > 0) {
            var prevent = matched.values.any { it.preventRecursion }
            var pass = 1
            while (pass <= config.recursionDepthCap && !prevent) {
                val before = matched.size
                // 递归来源：仅 allowRecursion 条目的 content 参与扫描
                val worldContent = matched.values
                    .filter { it.allowRecursion }
                    .sortedWith(entryComparator(config))
                    .joinToString("\n") { it.content }
                for (e in active) {
                    if (e.id in matched) continue
                    if (tryActivate(e, worldContent, pass) && e.preventRecursion) {
                        prevent = true
                        break
                    }
                }
                // 仅当本轮无新增「且」无仍在等待延迟激活的候选时才提前终止：
                // 否则 delayUntilRecursion 条目（其 turn 在后续轮次）会被错误地跳过
                val pendingDelay = active.any { it.id !in matched && it.delayUntilRecursion > pass }
                if (matched.size == before && !pendingDelay) break
                pass++
            }
        }

        if (matched.isEmpty()) return null

        // —— 排序 + 分组邻接（同组保持连续，不被他组/无组条目打断）——
        val sorted = matched.values.sortedWith(entryComparator(config))
        val ordered = ArrayList<WorldInfoEntry>(sorted.size)
        val remaining = sorted.toMutableList()
        while (remaining.isNotEmpty()) {
            val head = remaining.removeAt(0)
            ordered.add(head)
            if (head.group.isNotBlank()) {
                val same = remaining.filter { it.group == head.group }
                if (same.isNotEmpty()) {
                    ordered.addAll(same)
                    remaining.removeAll(same)
                }
            }
        }

        // —— 预算裁剪 + 渲染（字节稳定）——
        val sb = StringBuilder()
        var budgetUsed = 0L
        var lastGroup: String? = null
        for (e in ordered) {
            val content = e.content.trim()
            if (content.isBlank()) continue
            val cost = estimateTokens(content)
            if (budgetUsed + cost > config.tokenBudget) break
            if (config.emitGroupHeaders && e.group.isNotBlank() && e.group != lastGroup) {
                sb.append("# ").append(e.group).append("\n")
                lastGroup = e.group
            }
            sb.append("- ").append(content).append("\n")
            budgetUsed += cost
        }
        val out = sb.toString().trimEnd()
        return out.ifBlank { null }
    }

    /**
     * 判断单个条目是否激活（纯函数，便于单测）。
     * @param worldContent 已命中条目 content 拼接（递归轮来源；初始轮传空串）
     */
    fun isActivated(
        entry: WorldInfoEntry,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        worldContent: String,
        config: WorldInfoConfig,
        random: Random
    ): Boolean {
        val sources = entry.keysContainedIn
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val chatText = historyContents.takeLast(effectiveDepth(entry, config)).joinToString("\n")
        val sourceTexts = buildString {
            // keysContainedIn 为空视为仅扫 chat（ST 默认）
            if (sources.isEmpty() || SOURCE_CHAT in sources) append(chatText)
            if (SOURCE_USER in sources) append("\n").append(userName)
            if (SOURCE_SYSTEM in sources) append("\n").append(systemPrompt)
            if (SOURCE_WORLD in sources) append("\n").append(worldContent)
        }

        fun secondaryAny() = entry.keysecondary.any {
            it.isNotBlank() && sourceTexts.contains(it, ignoreCase = true)
        }

        fun secondaryAll() = entry.keysecondary.isNotEmpty() && entry.keysecondary.all {
            it.isNotBlank() && sourceTexts.contains(it, ignoreCase = true)
        }

        // 主关键词命中（constant 直通）
        val primaryMatched = entry.constant || entry.keywords.any {
            it.isNotBlank() && sourceTexts.contains(it, ignoreCase = true)
        }
        if (!primaryMatched) {
            // AND_ANY：次关键词单独命中也可激活（主或次任一命中）
            if (entry.selective && entry.selectiveLogic == AND_ANY && secondaryAny()) {
                return probabilityRoll(entry, random)
            }
            return false
        }
        if (entry.selective) {
            when (entry.selectiveLogic) {
                NOT_ALL -> if (secondaryAll()) return false // 主命中且非全部次存在
                NOT_ANY -> if (secondaryAny()) return false // 主命中且无任一次存在
                AND_ALL -> if (!secondaryAll()) return false // 主命中且全部次存在
                // AND_ANY：主已命中即通过
            }
        }
        return probabilityRoll(entry, random)
    }

    private fun effectiveDepth(entry: WorldInfoEntry, config: WorldInfoConfig): Int =
        if (entry.depth > 0) entry.depth else config.scanDepth

    private fun probabilityRoll(entry: WorldInfoEntry, random: Random): Boolean {
        if (!entry.useProbability) return true
        if (entry.probability >= 100) return true
        if (entry.probability <= 0) return false
        return random.nextInt(100) < entry.probability
    }

    /** 排序比较器：按 config 排序模式 + 稳定 tie-break（order → uid → id）保证全序。 */
    fun entryComparator(config: WorldInfoConfig): Comparator<WorldInfoEntry> = when (config.insertionOrderMode) {
        WorldInfoInsertionOrder.KEY_LENGTH -> compareByDescending<WorldInfoEntry> {
            it.keywords.firstOrNull()?.length ?: 0
        }.thenBy { it.order }.thenBy { it.uid }.thenBy { it.id }

        WorldInfoInsertionOrder.ALPHABETICAL -> compareBy<WorldInfoEntry> {
            it.content.lowercase()
        }.thenBy { it.order }.thenBy { it.uid }.thenBy { it.id }

        WorldInfoInsertionOrder.INSERT_AT_BOTTOM -> compareByDescending<WorldInfoEntry> { it.order }
            .thenBy { it.uid }.thenBy { it.id }

        WorldInfoInsertionOrder.INSERT_AT_TOP,
        WorldInfoInsertionOrder.ORDER -> compareBy<WorldInfoEntry> { it.order }
            .thenBy { it.uid }.thenBy { it.id }
    }
}
