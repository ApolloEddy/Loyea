package com.loyea.context.core

import kotlin.random.Random

/**
 * Tavern world-book budgeting uses a stable approximation owned by the plugin.
 * It intentionally does not call the host application's fallback usage counter.
 */
internal fun estimateTavernTokens(text: String): Long {
    if (text.isBlank()) return 0L
    var cjk = 0
    var other = 0
    for (character in text) {
        val codePoint = character.code
        if (codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF) {
            cjk++
        } else {
            other++
        }
    }
    return (cjk * 0.5 + other * 0.25).toLong().coerceAtLeast(1L)
}

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

    data class WorldInfoMatchResult(
        val entries: List<WorldInfoEntry>,
        val runtimeState: WorldInfoRuntimeState
    )

    /**
     * 计算匹配条目，按 config 排序/分组/预算裁剪后渲染注入块；无命中返回 null。
     * @param historyContents 最近消息 content，时间正序（条目 depth 窗口取其尾）
     * @param random 可注入的随机源（生产传会话稳定种子，测试传固定种子）
     */
    private fun matchWorldInfoEntriesFor(
        entries: List<WorldInfoEntry>,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        random: Random = Random.Default,
        personaDescription: String = "",
        characterDescription: String = "",
        characterPersonality: String = "",
        characterDepthPrompt: String = systemPrompt,
        scenario: String = "",
        creatorNotes: String = "",
        characterName: String = "",
        characterTags: List<String> = emptyList(),
        runtimeState: WorldInfoRuntimeState = WorldInfoRuntimeState(),
        turnKey: String = "",
        turnIndex: Long = historyContents.size.toLong(),
        generationType: String = GENERATION_NORMAL
    ): WorldInfoMatchResult {
        val active = entries.filter { it.enabled && !it.disable }
        if (active.isEmpty()) return WorldInfoMatchResult(emptyList(), runtimeState)

        val resolvedTurnKey = turnKey.ifBlank {
            "${historyContents.size}:${historyContents.lastOrNull()?.hashCode() ?: 0}"
        }
        val isNewTurn = runtimeState.turnKey != resolvedTurnKey
        val resolvedTurnIndex = when {
            runtimeState.turnKey.isBlank() -> maxOf(runtimeState.turnIndex, turnIndex)
            isNewTurn -> maxOf(runtimeState.turnIndex + 1L, turnIndex)
            else -> runtimeState.turnIndex
        }
        var nextRuntimeState = runtimeState.copy(
            turnKey = resolvedTurnKey,
            turnIndex = resolvedTurnIndex
        )

        fun recordActivation(entry: WorldInfoEntry) {
            nextRuntimeState = nextRuntimeState.copy(
                entries = nextRuntimeState.entries + (entry.id to WorldInfoEntryRuntimeState(
                    lastActivatedTurn = resolvedTurnIndex,
                    stickyUntilTurn = if (entry.sticky > 0) resolvedTurnIndex + entry.sticky else -1L,
                    cooldownUntilTurn = if (entry.cooldown > 0) resolvedTurnIndex + entry.cooldown else -1L
                ))
            )
        }

        // —— 匹配（初始轮 + 递归轮），id -> entry，保持命中顺序 ——
        val matched = linkedMapOf<String, WorldInfoEntry>()

        data class ActivationCandidate(
            val entry: WorldInfoEntry,
            val stickyActive: Boolean,
            val score: Int
        )

        fun groupNames(entry: WorldInfoEntry): List<String> = entry.group
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        fun groupsOverlap(left: WorldInfoEntry, right: WorldInfoEntry): Boolean =
            groupNames(left).any { it in groupNames(right) }

        /**
         * 对一个扫描轮次的候选执行 ST 的 inclusion group 顺序：
         * sticky → groupOverride → group scoring → groupWeight。
         * 已在前一轮选中的组不会被递归轮次的新候选替换。
         */
        fun resolveCandidates(candidates: List<ActivationCandidate>): List<WorldInfoEntry> {
            if (candidates.isEmpty()) return emptyList()
            val added = ArrayList<WorldInfoEntry>()
            val grouped = candidates
                .filter { groupNames(it.entry).isNotEmpty() }
                .flatMap { candidate -> groupNames(candidate.entry).map { group -> group to candidate } }
                .groupBy({ it.first }, { it.second })

            // 无组条目不参与 mutually-exclusive 选择。
            candidates.filter { groupNames(it.entry).isEmpty() }.forEach { candidate ->
                if (candidate.entry.id !in matched) {
                    matched[candidate.entry.id] = candidate.entry
                    added += candidate.entry
                    if (!candidate.stickyActive) recordActivation(candidate.entry)
                }
            }

            val selectedIds = linkedSetOf<String>()
            grouped.values.forEach { groupCandidates ->
                val available = groupCandidates.filter { candidate ->
                    candidate.entry.id !in matched &&
                        matched.values.none { existing -> groupsOverlap(existing, candidate.entry) }
                }
                if (available.isEmpty()) return@forEach

                // ST 会保留同组的 sticky 候选，并跳过后续分组评分/随机。
                val stickyCandidates = available.filter { it.stickyActive }
                if (stickyCandidates.isNotEmpty()) {
                    stickyCandidates.forEach { selectedIds += it.entry.id }
                    return@forEach
                }

                val overrides = available.filter { it.entry.groupOverride }
                val overrideWinner = overrides.maxWithOrNull(
                    compareBy<ActivationCandidate> { it.entry.order }
                        .thenByDescending { it.entry.uid }
                        .thenByDescending { it.entry.id }
                )
                if (overrideWinner != null) {
                    selectedIds += overrideWinner.entry.id
                    return@forEach
                }

                // useGroupScoring 为 null/缺省时继承全局设置；本地模型用 false
                // 表示未启用，因此全局 true 时所有候选都参与评分。
                val scoringEnabled = config.useGroupScoring || available.any { it.entry.useGroupScoring }
                val scoredCandidates = if (scoringEnabled) {
                    val maxScore = available
                        .filter { config.useGroupScoring || it.entry.useGroupScoring }
                        .maxOfOrNull { it.score }
                    if (maxScore == null) available else available.filter {
                        !config.useGroupScoring && !it.entry.useGroupScoring || it.score >= maxScore
                    }
                } else {
                    available
                }

                // ST 的 groupWeight 是最后一步的加权随机；固定随机源保证会话内可复现。
                val totalWeight = scoredCandidates.sumOf { it.entry.groupWeight.coerceAtLeast(1).toLong() }
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                var roll = if (totalWeight > 0L) random.nextInt(totalWeight.toInt()) else 0
                val winner = scoredCandidates.firstOrNull { candidate ->
                    roll -= candidate.entry.groupWeight.coerceAtLeast(1)
                    roll < 0
                } ?: scoredCandidates.lastOrNull()
                winner?.let { selectedIds += it.entry.id }
            }

            // 以扫描顺序写入 matched，保留非组条目与组胜者的稳定顺序。
            candidates.forEach { candidate ->
                if (candidate.entry.id in selectedIds && candidate.entry.id !in matched) {
                    matched[candidate.entry.id] = candidate.entry
                    added += candidate.entry
                    if (!candidate.stickyActive) recordActivation(candidate.entry)
                }
            }
            return added
        }

        fun tryActivate(entry: WorldInfoEntry, worldContent: String, pass: Int): ActivationCandidate? {
            if (pass > 0 && (entry.excludeRecursion || !entry.allowRecursion)) return null
            if (entry.delayUntilRecursion > pass) return null
            if (!entry.matchesGenerationType(generationType)) return null
            val timed = timedEntryState(
                entry = entry,
                historyContents = historyContents,
                userName = userName,
                systemPrompt = systemPrompt,
                config = config,
                personaDescription = personaDescription,
                characterDescription = characterDescription,
                characterPersonality = characterPersonality,
                characterDepthPrompt = characterDepthPrompt,
                scenario = scenario,
                creatorNotes = creatorNotes,
                characterName = characterName,
                characterTags = characterTags,
                runtimeState = runtimeState,
                currentTurnIndex = resolvedTurnIndex,
                generationType = generationType
            )
            if (timed.delayActive) return null
            if (timed.cooldownActive && !timed.stickyActive) return null
            if (timed.stickyActive || isActivated(
                    entry = entry,
                    historyContents = historyContents,
                    userName = userName,
                    systemPrompt = systemPrompt,
                    worldContent = worldContent,
                    config = config,
                    random = random,
                    personaDescription = personaDescription,
                    characterDescription = characterDescription,
                    characterPersonality = characterPersonality,
                        characterDepthPrompt = characterDepthPrompt,
                        scenario = scenario,
                        creatorNotes = creatorNotes,
                        characterName = characterName,
                        characterTags = characterTags,
                        generationType = generationType
                    )) {
                return ActivationCandidate(
                    entry = entry,
                    stickyActive = timed.stickyActive,
                    score = activationScore(
                        entry = entry,
                        historyContents = historyContents,
                        userName = userName,
                        systemPrompt = systemPrompt,
                        worldContent = worldContent,
                        config = config,
                        personaDescription = personaDescription,
                        characterDescription = characterDescription,
                        characterPersonality = characterPersonality,
                        characterDepthPrompt = characterDepthPrompt,
                        scenario = scenario,
                        creatorNotes = creatorNotes,
                        characterName = characterName,
                        characterTags = characterTags
                    )
                )
            }
            return null
        }

        // 初始轮（pass=0）：delayUntilRecursion>0 的条目推迟到递归轮；constant 直通
        val initialCandidates = ArrayList<ActivationCandidate>()
        for (e in active) {
            if (e.delayUntilRecursion > 0) continue
            tryActivate(e, "", 0)?.let { initialCandidates += it }
        }
        resolveCandidates(initialCandidates)

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
                val candidates = ArrayList<ActivationCandidate>()
                for (e in active) {
                    if (e.id in matched) continue
                    tryActivate(e, worldContent, pass)?.let { candidates += it }
                }
                val added = resolveCandidates(candidates)
                if (added.any { it.preventRecursion }) prevent = true
                // 仅当本轮无新增「且」无仍在等待延迟激活的候选时才提前终止：
                // 否则 delayUntilRecursion 条目（其 turn 在后续轮次）会被错误地跳过
                val pendingDelay = active.any { it.id !in matched && it.delayUntilRecursion > pass }
                if (matched.size == before && !pendingDelay) break
                pass++
            }
        }

        if (matched.isEmpty()) return WorldInfoMatchResult(emptyList(), nextRuntimeState)

        // —— 排序 + 分组邻接（同组保持连续，不被他组/无组条目打断）——
        // 预算选择按 ST 优先级：constant 优先，其次 order/priority 较大的条目优先；
        // 选择完成后再按插入顺序渲染，避免低优先级条目占满预算。
        // ST 的 0 token_budget/budget_cap 表示“不额外限制”，不能把整本书裁成空集。
        val budgetLimit = when {
            config.tokenBudget <= 0L && config.budgetCap <= 0L -> Long.MAX_VALUE
            config.tokenBudget <= 0L -> config.budgetCap
            config.budgetCap <= 0L -> config.tokenBudget
            else -> minOf(config.tokenBudget, config.budgetCap)
        }
        val budgetCandidates = matched.values.sortedWith(
            compareByDescending<WorldInfoEntry> { if (it.constant) 1 else 0 }
                .thenByDescending { it.priority ?: it.order }
                .thenByDescending { it.weight }
                .thenBy { it.uid }
                .thenBy { it.id }
        )
        val selected = ArrayList<WorldInfoEntry>(budgetCandidates.size)
        var selectedCost = 0L
        for (candidate in budgetCandidates) {
            if (candidate.ignoreBudget) {
                selected.add(candidate)
                continue
            }
            val cost = estimateTavernTokens(candidate.content.trim())
            if (cost <= 0L || selectedCost + cost > budgetLimit) continue
            selected.add(candidate)
            selectedCost += cost
        }

        val selectedSorted = selected.sortedWith(entryComparator(config))
        val ordered = ArrayList<WorldInfoEntry>(selectedSorted.size)
        val remaining = selectedSorted.toMutableList()
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

        return WorldInfoMatchResult(ordered, nextRuntimeState)
    }

    /** 兼容旧调用方：只返回命中条目，不暴露持久化运行时状态。 */
    fun matchedWorldInfoEntriesFor(
        entries: List<WorldInfoEntry>,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        random: Random = Random.Default,
        personaDescription: String = "",
        characterDescription: String = "",
        characterPersonality: String = "",
        characterDepthPrompt: String = systemPrompt,
        scenario: String = "",
        creatorNotes: String = "",
        characterName: String = "",
        characterTags: List<String> = emptyList(),
        runtimeState: WorldInfoRuntimeState = WorldInfoRuntimeState(),
        turnKey: String = "",
        turnIndex: Long = historyContents.size.toLong(),
        generationType: String = GENERATION_NORMAL
    ): List<WorldInfoEntry> = matchWorldInfoEntriesFor(
        entries = entries,
        historyContents = historyContents,
        userName = userName,
        systemPrompt = systemPrompt,
        config = config,
        random = random,
        personaDescription = personaDescription,
        characterDescription = characterDescription,
        characterPersonality = characterPersonality,
        characterDepthPrompt = characterDepthPrompt,
        scenario = scenario,
        creatorNotes = creatorNotes,
        characterName = characterName,
        characterTags = characterTags,
        runtimeState = runtimeState,
        turnKey = turnKey,
        turnIndex = turnIndex,
        generationType = generationType
    ).entries

    private data class TimedEntryState(
        val stickyActive: Boolean = false,
        val cooldownActive: Boolean = false,
        val delayActive: Boolean = false
    )

    /**
     * ST 的 timed world-info 状态保存在聊天元数据中；Loyea 目前不另建一份易失状态，
     * 而是从已持久化的消息历史重建等价窗口。这样切换进程/会话后 sticky/cooldown
     * 仍然自洽，不会因 ViewModel 重建而凭空失效。
     */
    private fun timedEntryState(
        entry: WorldInfoEntry,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        personaDescription: String,
        characterDescription: String,
        characterPersonality: String,
        characterDepthPrompt: String,
        scenario: String,
        creatorNotes: String,
        characterName: String,
        characterTags: List<String>,
        runtimeState: WorldInfoRuntimeState,
        currentTurnIndex: Long,
        generationType: String
    ): TimedEntryState {
        val currentSize = historyContents.size
        val delayActive = entry.delay > 0 && currentSize < entry.delay

        // 新格式优先读取聊天元数据中的持久状态；不存在时才使用旧版历史回推。
        // 这样 sticky/cooldown 在重启、编辑消息或重新生成后不会因为文本形状变化而漂移。
        if (runtimeState.turnKey.isNotBlank()) {
            val stored = runtimeState.entries[entry.id]
            val stickyActive = stored?.stickyUntilTurn?.let { it >= currentTurnIndex } == true
            val cooldownActive = stored?.cooldownUntilTurn?.let { it >= currentTurnIndex } == true
            return TimedEntryState(
                stickyActive = stickyActive,
                cooldownActive = cooldownActive,
                delayActive = delayActive
            )
        }

        if ((entry.sticky <= 0 && entry.cooldown <= 0) || currentSize <= 1) {
            return TimedEntryState(delayActive = delayActive)
        }

        val prior = historyContents.dropLast(1)
        val lastActivation = prior.indices.reversed().firstOrNull { index ->
            isActivated(
                entry = entry,
                historyContents = prior.take(index + 1),
                userName = userName,
                systemPrompt = systemPrompt,
                worldContent = "",
                config = config,
                random = Random(index.toLong() + entry.id.hashCode().toLong()),
                personaDescription = personaDescription,
                characterDescription = characterDescription,
                characterPersonality = characterPersonality,
                characterDepthPrompt = characterDepthPrompt,
                scenario = scenario,
                creatorNotes = creatorNotes,
                characterName = characterName,
                characterTags = characterTags,
                generationType = generationType
            )
        } ?: return TimedEntryState(delayActive = delayActive)
        val turnsSinceActivation = prior.size - lastActivation
        return TimedEntryState(
            stickyActive = entry.sticky > 0 && turnsSinceActivation <= entry.sticky,
            cooldownActive = entry.cooldown > 0 && turnsSinceActivation <= entry.cooldown,
            delayActive = delayActive
        )
    }

    data class WorldInfoInjectionBlock(
        val content: String,
        val role: String? = null
    )

    data class WorldInfoRenderResult(
        val all: String?,
        val legacy: String? = null,
        val beforeCharacterDefinitions: String? = null,
        val afterCharacterDefinitions: String? = null,
        val authorNoteTop: String? = null,
        val authorNoteBottom: String? = null,
        val exampleMessagesTop: String? = null,
        val exampleMessagesBottom: String? = null,
        val atDepth: Map<Int, String> = emptyMap(),
        val outlets: Map<String, String> = emptyMap(),
        val atDepthBlocks: Map<Int, List<WorldInfoInjectionBlock>> = emptyMap(),
        /** Automation IDs of entries activated during this scan, for Quick Reply hooks. */
        val automationIds: Set<String> = emptySet(),
        val runtimeState: WorldInfoRuntimeState = WorldInfoRuntimeState()
    )

    /** 兼容旧调用方的单块输出。所有位置的条目都保留在 all 中，不会静默丢失。 */
    fun worldInfoBlockFor(
        entries: List<WorldInfoEntry>,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        random: Random = Random.Default,
        personaDescription: String = "",
        characterDescription: String = "",
        characterPersonality: String = "",
        characterDepthPrompt: String = systemPrompt,
        scenario: String = "",
        creatorNotes: String = "",
        characterName: String = "",
        characterTags: List<String> = emptyList(),
        runtimeState: WorldInfoRuntimeState = WorldInfoRuntimeState(),
        turnKey: String = "",
        turnIndex: Long = historyContents.size.toLong(),
        generationType: String = GENERATION_NORMAL
    ): String? = worldInfoRenderFor(
        entries = entries,
        historyContents = historyContents,
        userName = userName,
        systemPrompt = systemPrompt,
        config = config,
        random = random,
        personaDescription = personaDescription,
        characterDescription = characterDescription,
        characterPersonality = characterPersonality,
        characterDepthPrompt = characterDepthPrompt,
        scenario = scenario,
        creatorNotes = creatorNotes,
        characterName = characterName,
        characterTags = characterTags,
        runtimeState = runtimeState,
        turnKey = turnKey,
        turnIndex = turnIndex,
        generationType = generationType
    ).all

    /** 按 ST position/role/outlet 分桶；Prompt 层可据此将同一组命中放到正确位置。 */
    fun worldInfoRenderFor(
        entries: List<WorldInfoEntry>,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        config: WorldInfoConfig,
        random: Random = Random.Default,
        personaDescription: String = "",
        characterDescription: String = "",
        characterPersonality: String = "",
        characterDepthPrompt: String = systemPrompt,
        scenario: String = "",
        creatorNotes: String = "",
        characterName: String = "",
        characterTags: List<String> = emptyList(),
        runtimeState: WorldInfoRuntimeState = WorldInfoRuntimeState(),
        turnKey: String = "",
        turnIndex: Long = historyContents.size.toLong(),
        generationType: String = GENERATION_NORMAL
    ): WorldInfoRenderResult {
        val matchResult = matchWorldInfoEntriesFor(
            entries = entries,
            historyContents = historyContents,
            userName = userName,
            systemPrompt = systemPrompt,
            config = config,
            random = random,
            personaDescription = personaDescription,
            characterDescription = characterDescription,
            characterPersonality = characterPersonality,
            characterDepthPrompt = characterDepthPrompt,
            scenario = scenario,
            creatorNotes = creatorNotes,
            characterName = characterName,
            characterTags = characterTags,
            runtimeState = runtimeState,
            turnKey = turnKey,
            turnIndex = turnIndex,
            generationType = generationType
        )
        val ordered = matchResult.entries
        if (ordered.isEmpty()) return WorldInfoRenderResult(
            all = null,
            automationIds = emptySet(),
            runtimeState = matchResult.runtimeState
        )
        val all = renderEntries(ordered, config)
        val buckets = ordered.groupBy { normalizePosition(it.positionType) }
        fun bucket(name: String): String? = buckets[name]?.let { renderEntries(it, config) }
        val depthBuckets = buckets[POSITION_AT_DEPTH].orEmpty()
            .groupBy { it.injectionDepth.coerceAtLeast(0) }
            .mapNotNull { (depth, value) -> renderEntries(value, config)?.let { depth to it } }
            .toMap()
        val depthBlocks = buckets[POSITION_AT_DEPTH].orEmpty()
            .groupBy { it.injectionDepth.coerceAtLeast(0) }
            .mapValues { (_, value) ->
                value.groupBy { it.role?.lowercase()?.takeIf { role -> role in setOf("system", "user", "assistant") } ?: "system" }
                    .mapNotNull { (role, roleEntries) ->
                        renderEntries(roleEntries, config)?.let { WorldInfoInjectionBlock(it, role) }
                    }
            }
        val outletBuckets = buckets[POSITION_OUTLET].orEmpty()
            .groupBy { it.outletName?.ifBlank { "default" } ?: "default" }
            .mapNotNull { (outlet, value) -> renderEntries(value, config)?.let { outlet to it } }
            .toMap()
        return WorldInfoRenderResult(
            all = all,
            legacy = bucket(POSITION_LEGACY),
            beforeCharacterDefinitions = bucket(POSITION_BEFORE_CHAR),
            afterCharacterDefinitions = bucket(POSITION_AFTER_CHAR),
            authorNoteTop = bucket(POSITION_AN_TOP),
            authorNoteBottom = bucket(POSITION_AN_BOTTOM),
            exampleMessagesTop = bucket(POSITION_EM_TOP),
            exampleMessagesBottom = bucket(POSITION_EM_BOTTOM),
            atDepth = depthBuckets,
            outlets = outletBuckets,
            atDepthBlocks = depthBlocks,
            automationIds = ordered.mapNotNull { it.automationId.takeIf(String::isNotBlank) }.toSet(),
            runtimeState = matchResult.runtimeState
        )
    }

    private fun renderEntries(entries: List<WorldInfoEntry>, config: WorldInfoConfig): String? {
        val sb = StringBuilder()
        var lastGroup: String? = null
        entries.forEach { entry ->
            val content = entry.content.trim()
            if (content.isBlank()) return@forEach
            if (config.emitGroupHeaders && entry.group.isNotBlank() && entry.group != lastGroup) {
                sb.append("# ").append(entry.group).append("\n")
                lastGroup = entry.group
            }
            sb.append("- ").append(content).append("\n")
        }
        return sb.toString().trimEnd().ifBlank { null }
    }

    private const val POSITION_BEFORE_CHAR = "before_char"
    private const val POSITION_AFTER_CHAR = "after_char"
    private const val POSITION_AN_TOP = "an_top"
    private const val POSITION_AN_BOTTOM = "an_bottom"
    private const val POSITION_EM_TOP = "em_top"
    private const val POSITION_EM_BOTTOM = "em_bottom"
    private const val POSITION_AT_DEPTH = "at_depth"
    private const val POSITION_OUTLET = "outlet"
    private const val POSITION_LEGACY = "legacy"
    private const val GENERATION_NORMAL = "normal"

    private fun normalizePosition(value: String): String = when (value.lowercase().replace('-', '_')) {
        "before_char", "before_character", "before_character_definitions" -> POSITION_BEFORE_CHAR
        "after_char", "after_character", "after_character_definitions" -> POSITION_AFTER_CHAR
        "antop", "an_top", "author_note_top" -> POSITION_AN_TOP
        "anbottom", "an_bottom", "author_note_bottom" -> POSITION_AN_BOTTOM
        "emtop", "em_top", "example_messages_top" -> POSITION_EM_TOP
        "embottom", "em_bottom", "example_messages_bottom" -> POSITION_EM_BOTTOM
        "atdepth", "at_depth", "depth" -> POSITION_AT_DEPTH
        "outlet" -> POSITION_OUTLET
        "legacy" -> POSITION_LEGACY
        else -> POSITION_AFTER_CHAR
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
        random: Random,
        personaDescription: String = "",
        characterDescription: String = "",
        characterPersonality: String = "",
        characterDepthPrompt: String = systemPrompt,
        scenario: String = "",
        creatorNotes: String = "",
        characterName: String = "",
        characterTags: List<String> = emptyList(),
        generationType: String = GENERATION_NORMAL
    ): Boolean {
        if (!entry.matchesGenerationType(generationType)) return false
        if (!passesCharacterFilter(entry, characterName, characterTags)) return false
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
            if (entry.matchPersonaDescription) append("\n").append(personaDescription)
            if (entry.matchCharacterDescription) append("\n").append(characterDescription)
            if (entry.matchCharacterPersonality) append("\n").append(characterPersonality)
            if (entry.matchCharacterDepthPrompt) append("\n").append(characterDepthPrompt)
            if (entry.matchScenario) append("\n").append(scenario)
            if (entry.matchCreatorNotes) append("\n").append(creatorNotes)
        }

        fun secondaryAny() = entry.keysecondary.any {
            it.isNotBlank() && matchesKeyword(it, sourceTexts, entry, config)
        }

        fun secondaryAll() = entry.keysecondary.isNotEmpty() && entry.keysecondary.all {
            it.isNotBlank() && matchesKeyword(it, sourceTexts, entry, config)
        }

        // ST 的 triggers 是生成类型过滤，不是额外关键词；空列表表示所有生成类型。
        val primaryMatched = entry.constant || entry.keywords.any {
            it.isNotBlank() && matchesKeyword(it, sourceTexts, entry, config)
        }
        if (!primaryMatched) return false
        if (entry.selective && !entry.constant) {
            when (entry.selectiveLogic) {
                AND_ANY -> if (!secondaryAny()) return false // 主词 + 任一次词
                NOT_ALL -> if (secondaryAll()) return false // 主词 + 非全部次词
                NOT_ANY -> if (secondaryAny()) return false // 主词 + 无任一次词
                AND_ALL -> if (!secondaryAll()) return false // 主词 + 全部次词
            }
        }
        return probabilityRoll(entry, random)
    }

    /**
     * 计算 inclusion group 的关键词命中分数，对齐 ST WorldInfoBuffer.getScore：
     * 主关键词每命中一个得 1 分；AND_ANY/AND_ALL 在满足正向次词条件时再计次词分。
     * NOT_ALL/NOT_ANY 只影响是否激活，不参与正向评分。
     */
    private fun activationScore(
        entry: WorldInfoEntry,
        historyContents: List<String>,
        userName: String,
        systemPrompt: String,
        worldContent: String,
        config: WorldInfoConfig,
        personaDescription: String,
        characterDescription: String,
        characterPersonality: String,
        characterDepthPrompt: String,
        scenario: String,
        creatorNotes: String,
        characterName: String,
        characterTags: List<String>
    ): Int {
        if (!passesCharacterFilter(entry, characterName, characterTags)) return 0
        if (entry.constant || entry.keywords.isEmpty()) return 0
        val sources = entry.keysContainedIn
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val chatText = historyContents.takeLast(effectiveDepth(entry, config)).joinToString("\n")
        val sourceTexts = buildString {
            if (sources.isEmpty() || SOURCE_CHAT in sources) append(chatText)
            if (SOURCE_USER in sources) append("\n").append(userName)
            if (SOURCE_SYSTEM in sources) append("\n").append(systemPrompt)
            if (SOURCE_WORLD in sources) append("\n").append(worldContent)
            if (entry.matchPersonaDescription) append("\n").append(personaDescription)
            if (entry.matchCharacterDescription) append("\n").append(characterDescription)
            if (entry.matchCharacterPersonality) append("\n").append(characterPersonality)
            if (entry.matchCharacterDepthPrompt) append("\n").append(characterDepthPrompt)
            if (entry.matchScenario) append("\n").append(scenario)
            if (entry.matchCreatorNotes) append("\n").append(creatorNotes)
        }
        val primaryScore = entry.keywords.count {
            it.isNotBlank() && matchesKeyword(it, sourceTexts, entry, config)
        }
        if (primaryScore == 0) return 0
        val secondaryScore = entry.keysecondary.count {
            it.isNotBlank() && matchesKeyword(it, sourceTexts, entry, config)
        }
        return when (entry.selectiveLogic) {
            AND_ANY -> primaryScore + secondaryScore
            AND_ALL -> if (entry.keysecondary.isNotEmpty() && secondaryScore == entry.keysecondary.count { it.isNotBlank() }) {
                primaryScore + secondaryScore
            } else {
                primaryScore
            }
            else -> primaryScore
        }
    }

    private fun passesCharacterFilter(
        entry: WorldInfoEntry,
        characterName: String,
        characterTags: List<String>
    ): Boolean {
        val names = entry.characterFilterNames.map { it.trim() }.filter { it.isNotBlank() }
        val tags = entry.characterFilterTags.map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty() && tags.isEmpty()) return true
        val nameMatched = names.any { it.equals(characterName.trim(), ignoreCase = true) }
        val tagSet = characterTags.map { it.trim() }.filter { it.isNotBlank() }
        val tagMatched = tags.any { wanted -> tagSet.any { it.equals(wanted, ignoreCase = true) } }
        val matched = nameMatched || tagMatched
        return if (entry.characterFilterExclude) !matched else matched
    }

    private fun effectiveDepth(entry: WorldInfoEntry, config: WorldInfoConfig): Int =
        entry.scanDepthOverride?.let { if (it > 0) it else config.scanDepth }
            ?: if (entry.depth > 0) entry.depth else config.scanDepth

    private fun matchesKeyword(
        pattern: String,
        text: String,
        entry: WorldInfoEntry,
        config: WorldInfoConfig
    ): Boolean {
        val key = pattern.trim()
        if (key.isEmpty() || text.isEmpty()) return false
        val slashRegex = key.length >= 2 && key.startsWith('/') && key.lastIndexOf('/') > 0
        if (entry.useRegex || slashRegex) {
            val lastSlash = if (slashRegex) key.lastIndexOf('/') else -1
            val body = if (slashRegex) key.substring(1, lastSlash) else key
            val flagText = if (slashRegex) key.substring(lastSlash + 1) else ""
            val options = buildSet {
                if (!entry.caseSensitiveOr(config) || 'i' in flagText) add(RegexOption.IGNORE_CASE)
                if ('m' in flagText) add(RegexOption.MULTILINE)
                if ('s' in flagText) add(RegexOption.DOT_MATCHES_ALL)
            }
            return runCatching { Regex(body, options).containsMatchIn(text) }.getOrDefault(false)
        }
        if (entry.matchWholeWordsOr(config)) {
            val boundaryPattern = "(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])"
            val options = if (entry.caseSensitiveOr(config)) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return runCatching { Regex(boundaryPattern, options).containsMatchIn(text) }.getOrDefault(false)
        }
        return text.contains(key, ignoreCase = !entry.caseSensitiveOr(config))
    }

    private fun WorldInfoEntry.caseSensitiveOr(config: WorldInfoConfig): Boolean =
        caseSensitive ?: config.caseSensitive

    private fun WorldInfoEntry.matchWholeWordsOr(config: WorldInfoConfig): Boolean =
        matchWholeWords ?: config.matchWholeWords

    private fun WorldInfoEntry.matchesGenerationType(generationType: String): Boolean {
        if (triggers.isEmpty()) return true
        val normalizedGenerationType = normalizeGenerationType(generationType)
        return triggers.any { normalizeGenerationType(it) == normalizedGenerationType }
    }

    private fun normalizeGenerationType(value: String): String = value
        .trim()
        .removePrefix(":")
        .lowercase()

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
