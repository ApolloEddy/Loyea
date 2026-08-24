package com.loyea.plugins.tavern.core

/**
 * 将角色卡内嵌 CharacterBook 映射为 Loyea 的运行时世界书。
 *
 * 适配器不修改原始 JSON；所有 ST/V3 扩展仍留在 CharacterBookDocument.extensionsJson
 * 和 entry rawJson 中，运行时只把当前引擎需要的字段投影出来。
 */
object TavernCharacterBookAdapter {
    fun toWorldInfoBook(book: CharacterBookDocument, idPrefix: String): WorldInfoBook {
        val entries = book.entries.mapIndexed { index, source ->
            val positionType = source.position ?: source.positionIndex?.let(::positionName) ?: "after_char"
            WorldInfoEntry(
                id = "$idPrefix:${source.id ?: index}",
                keywords = source.keys,
                content = source.content,
                enabled = source.enabled,
                uid = source.id ?: index,
                keysecondary = source.secondaryKeys,
                constant = source.constant,
                // CharacterBook 的 insertion_order 是排序优先级；ST priority/order
                // 若存在则优先保留为选择优先级，输出仍由 order 统一排序。
                order = source.order ?: source.insertionOrder,
                depth = 0,
                comment = source.comment.orEmpty(),
                selective = source.selective,
                disable = !source.enabled,
                selectiveLogic = source.selectiveLogic ?: WorldInfoMatcher.AND_ANY,
                group = source.group.orEmpty(),
                probability = source.probability ?: 100,
                // ST's CharacterBook conversion defaults useProbability to true;
                // probability=100 keeps the normal card-book path unchanged.
                useProbability = source.useProbability ?: true,
                delayUntilRecursion = source.delayUntilRecursion ?: 0,
                preventRecursion = source.preventRecursion ?: false,
                allowRecursion = !(source.excludeRecursion ?: false),
                excludeRecursion = source.excludeRecursion ?: false,
                keysContainedIn = source.keysContainedIn?.ifBlank { WorldInfoMatcher.SOURCE_CHAT }
                    ?: WorldInfoMatcher.SOURCE_CHAT,
                position = source.positionIndex ?: 0,
                weight = source.groupWeight ?: 0,
                useRegex = source.useRegex,
                caseSensitive = source.caseSensitive,
                matchWholeWords = source.matchWholeWords,
                positionType = positionType,
                injectionDepth = source.depth ?: 0,
                role = source.role,
                outletName = source.outletName,
                groupOverride = source.groupOverride ?: false,
                groupWeight = source.groupWeight ?: 100,
                useGroupScoring = source.useGroupScoring ?: false,
                priority = source.priority,
                // 0 is explicit here: no per-entry scanDepth means use book/global scanDepth,
                // not the legacy WorldInfoEntry.depth fallback.
                scanDepthOverride = source.scanDepth ?: 0,
                sticky = source.sticky ?: 0,
                cooldown = source.cooldown ?: 0,
                delay = source.delay ?: 0,
                triggers = source.triggers,
                extensionsJson = source.extensionsJson,
                automationId = source.automationId.orEmpty(),
                vectorized = source.vectorized ?: false,
                matchPersonaDescription = source.matchPersonaDescription ?: false,
                matchCharacterDescription = source.matchCharacterDescription ?: false,
                matchCharacterPersonality = source.matchCharacterPersonality ?: false,
                matchCharacterDepthPrompt = source.matchCharacterDepthPrompt ?: false,
                matchScenario = source.matchScenario ?: false,
                matchCreatorNotes = source.matchCreatorNotes ?: false,
                ignoreBudget = source.ignoreBudget ?: false,
                characterFilterNames = source.characterFilterNames,
                characterFilterTags = source.characterFilterTags,
                characterFilterExclude = source.characterFilterExclude,
                addMemo = source.addMemo ?: true,
                displayIndex = source.displayIndex ?: index,
                rawJson = source.rawJson
            )
        }
        return WorldInfoBook(
            entries = entries,
            config = WorldInfoConfig(
                scanDepth = book.scanDepth?.coerceAtLeast(0) ?: 10,
                tokenBudget = book.tokenBudget?.toLong()?.coerceAtLeast(0) ?: 2048,
                allowRecursion = book.recursiveScanning ?: true
            ),
            name = book.name.orEmpty(),
            description = book.description.orEmpty(),
            extensionsJson = book.extensionsJson,
            rawJson = book.rawJson
        )
    }

    private fun positionName(index: Int): String = when (index) {
        0 -> "before_char"
        1 -> "after_char"
        2 -> "an_top"
        3 -> "an_bottom"
        4 -> "at_depth"
        5 -> "em_top"
        6 -> "em_bottom"
        7 -> "outlet"
        else -> "after_char"
    }
}
