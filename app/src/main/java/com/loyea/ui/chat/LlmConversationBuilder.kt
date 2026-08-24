package com.loyea.ui.chat

import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationInsertion
import com.loyea.plugin.api.InsertionAnchor
import com.loyea.plugin.api.PreparedPersonaTurn
import com.loyea.plugin.api.TextStage
import java.util.TimeZone

/**
 * 纯 Kotlin 的 LLM 会话序列化器。
 *
 * provider-only 元数据均由已持久化的 Message 字段确定；同一条历史消息无论位于滑窗中的
 * 哪个下标，其编码都保持一致，从而避免无意义地破坏模型端前缀缓存。
 */
object LlmConversationBuilder {

    fun build(
        systemPrompt: String?,
        history: List<Message>,
        includeVision: Boolean = true,
        includeAudio: Boolean = true,
        includeNames: Boolean = false,
        userName: String = "User",
        characterName: String = "Character",
        compressedSummary: String = "",
        postHistoryInstructions: String = "",
        preparedTurn: PreparedPersonaTurn? = null,
        maxContextTokens: Int? = null,
        includeMessageTimestamps: Boolean = false,
        allowPhysicalContext: Boolean = true,
        allowGraphContext: Boolean = true,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<LlmChatMessage> {
        val result = mutableListOf<LlmChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            result.add(LlmChatMessage(role = "system", content = systemPrompt))
        }
        val turnInsertions = preparedTurn?.plan?.insertions.orEmpty()
        turnInsertions
            .filter { it.anchor == InsertionAnchor.AFTER_SYSTEM_BEFORE_SUMMARY }
            .sortedBy(ConversationInsertion::order)
            .forEach { insertion ->
                result.add(
                    LlmChatMessage(
                        role = insertion.role.toProviderRole(),
                        content = insertion.content
                    )
                )
            }
        if (compressedSummary.isNotBlank()) {
            result.add(
                LlmChatMessage(
                    role = "system",
                    content = "[EARLY CONVERSATION SUMMARY / 会话早期摘要]\n$compressedSummary"
                )
            )
        }

        val recentHistory = history.asSequence()
            .filter {
                (it.content.isNotBlank() || !it.imageUrl.isNullOrBlank() || !it.audioUrl.isNullOrBlank()) &&
                    !it.content.startsWith("[错误]") &&
                    !it.content.startsWith("[Error]") &&
                    !it.isTavernHiddenComment()
            }
            .toList()
            .takeLast(20)

        val historyMessages = recentHistory.mapIndexed { index, message ->
            val effectiveImage = if (includeVision && !message.imageUrl.isNullOrBlank()) message.imageUrl else null
            val effectiveAudio = if (
                includeAudio && index == recentHistory.lastIndex && !message.audioUrl.isNullOrBlank()
            ) {
                message.audioUrl
            } else {
                null
            }

            var textContent = message.content
            if (message.sender == Sender.USER && preparedTurn != null) {
                textContent = preparedTurn.transform(
                    stage = TextStage.USER_INPUT,
                    text = textContent,
                    isMarkdown = false
                )
            }
            if (effectiveImage == null && !message.imageUrl.isNullOrBlank()) {
                textContent = (if (textContent.isBlank()) "" else "$textContent\n") + "[图片]"
            }
            if (effectiveAudio == null && !message.audioUrl.isNullOrBlank() && textContent.isBlank()) {
                textContent = "[语音消息]"
            }

            val providerContent = buildString {
                if (includeMessageTimestamps) {
                    append(ConversationTimelineFormatter.formatMessageMetadata(message, timeZone))
                    append('\n')
                }
                if (message.sender == Sender.USER && !message.llmContextSnapshot.isNullOrBlank()) {
                    val safeSnapshot = sanitizeSnapshot(
                        snapshot = message.llmContextSnapshot,
                        allowPhysicalContext = allowPhysicalContext,
                        allowGraphContext = allowGraphContext
                    )
                    if (safeSnapshot.isNotBlank()) {
                        append(safeSnapshot)
                        append("\n\n[USER MESSAGE / 用户消息]\n")
                    }
                }
                if (includeNames) {
                    val fallback = when {
                        message.tavernIsSystem -> "System"
                        message.sender == Sender.USER -> "User"
                        else -> "Character"
                    }
                    val displayName = when {
                        !message.tavernName.isNullOrBlank() -> message.tavernName.orEmpty()
                        message.sender == Sender.USER -> userName
                        else -> characterName
                    }
                    append(displayName.ifBlank { fallback })
                    append(": ")
                }
                append(textContent)
            }

            LlmChatMessage(
                role = when {
                    message.tavernIsSystem -> "system"
                    message.sender == Sender.USER -> "user"
                    else -> "assistant"
                },
                content = providerContent,
                imageUrl = effectiveImage,
                audioUrl = effectiveAudio
            )
        }
        val atDepthInsertions = turnInsertions.filter {
            it.anchor == InsertionAnchor.AT_DEPTH_FROM_LATEST
        }
        val effectiveMaxContextTokens = maxContextTokens
            ?: preparedTurn?.plan?.generation?.maxContextTokens
        val boundedHistory = trimHistoryToContextBudget(
            history = historyMessages,
            maxContextTokens = effectiveMaxContextTokens,
            fixedMessages = result,
            atDepthInsertions = atDepthInsertions,
            postHistoryInstructions = postHistoryInstructions
        )
        if (atDepthInsertions.isEmpty()) {
            result.addAll(boundedHistory)
        } else {
            val blocksByBoundary = atDepthInsertions
                .groupBy { insertion ->
                    (boundedHistory.size - insertion.depthFromLatest).coerceIn(0, boundedHistory.size)
                }
            for (boundary in 0..boundedHistory.size) {
                blocksByBoundary[boundary].orEmpty().sortedBy(ConversationInsertion::order).forEach { insertion ->
                    result.add(
                        LlmChatMessage(
                            role = insertion.role.toProviderRole(),
                            content = insertion.content
                        )
                    )
                }
                if (boundary < boundedHistory.size) result.add(boundedHistory[boundary])
            }
        }
        if (postHistoryInstructions.isNotBlank()) {
            result.add(
                LlmChatMessage(
                    role = "system",
                    content = "[POST-HISTORY INSTRUCTIONS / 历史消息后指令]\n$postHistoryInstructions"
                )
            )
        }
        // Continue prefill/nudge insertions are deliberately last: chat-completion
        // providers treat a trailing assistant message as the prefix to continue.
        turnInsertions
            .filter { it.anchor == InsertionAnchor.AFTER_HISTORY }
            .sortedBy(ConversationInsertion::order)
            .forEach { insertion ->
                result.add(
                    LlmChatMessage(
                        role = insertion.role.toProviderRole(),
                        content = insertion.content
                    )
                )
            }
        return result
    }

    private fun ChatRole.toProviderRole(): String = when (this) {
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
        ChatRole.TOOL -> "tool"
        ChatRole.SYSTEM -> "system"
    }

    private fun trimHistoryToContextBudget(
        history: List<LlmChatMessage>,
        maxContextTokens: Int?,
        fixedMessages: List<LlmChatMessage>,
        atDepthInsertions: List<ConversationInsertion>,
        postHistoryInstructions: String
    ): List<LlmChatMessage> {
        val limit = maxContextTokens?.takeIf { it > 0 } ?: return history
        val fixedCost = fixedMessages.sumOf { estimateTokens(it.content) }
        val worldInfoCost = atDepthInsertions.sumOf { estimateTokens(it.content) }
        val postCost = if (postHistoryInstructions.isBlank()) 0 else estimateTokens(postHistoryInstructions)
        val historyBudget = (limit - fixedCost - worldInfoCost - postCost).coerceAtLeast(1)
        val selected = history.toMutableList()
        while (selected.size > 1 && selected.sumOf { estimateTokens(it.content) } > historyBudget) {
            selected.removeAt(0)
        }
        return selected
    }

    private fun estimateTokens(content: String?): Int =
        ((content?.length ?: 0) / 4).coerceAtLeast(1)

    private fun sanitizeSnapshot(
        snapshot: String,
        allowPhysicalContext: Boolean,
        allowGraphContext: Boolean
    ): String {
        val trimmed = snapshot.trim()

        val physicalStartMarker = "[USER'S PHYSICAL STATE (CACHED)]"
        val physicalEndMarker = "[END USER'S PHYSICAL STATE]"
        val physicalStart = if (allowPhysicalContext) -1 else trimmed.indexOf(physicalStartMarker)
        val withoutPhysical = if (allowPhysicalContext || physicalStart < 0) {
            trimmed
        } else {
            val physicalEndStart = trimmed.indexOf(physicalEndMarker, startIndex = physicalStart)
            if (physicalEndStart < 0) {
                // 未知/损坏格式无法证明不含敏感数据，关闭物理感知时宁可舍弃整个旧快照。
                return ""
            }
            val physicalEndExclusive = physicalEndStart + physicalEndMarker.length
            (trimmed.substring(0, physicalStart) + trimmed.substring(physicalEndExclusive)).trim()
        }

        if (allowGraphContext && allowPhysicalContext) return withoutPhysical

        val graphStartMarker = "[GRAPH MEMORY CONTEXT]"
        val graphEndMarker = "[END GRAPH MEMORY CONTEXT]"
        val graphStart = withoutPhysical.indexOf(graphStartMarker)
        if (graphStart < 0) {
            // 兼容开发期无显式边界的快照：发现旧 Recall Memory 时只移除该块；边界不完整则整体舍弃。
            val legacyStart = withoutPhysical.indexOf("[Recall Memory:")
            if (legacyStart < 0) return withoutPhysical
            val legacyEnd = withoutPhysical.indexOf("\n]", startIndex = legacyStart)
            if (legacyEnd < 0) return ""
            return (withoutPhysical.substring(0, legacyStart) + withoutPhysical.substring(legacyEnd + 2)).trim()
        }
        val graphEndStart = withoutPhysical.indexOf(graphEndMarker, startIndex = graphStart)
        if (graphEndStart < 0) {
            // 未知/损坏格式无法证明不含敏感数据，关闭物理感知时宁可舍弃整个旧快照。
            return ""
        }
        val graphEndExclusive = graphEndStart + graphEndMarker.length
        if (!allowGraphContext) {
            return (
                withoutPhysical.substring(0, graphStart) +
                    withoutPhysical.substring(graphEndExclusive)
                ).trim()
        }

        val graphContentStart = graphStart + graphStartMarker.length
        val graphContent = withoutPhysical.substring(graphContentStart, graphEndStart)
        val filteredGraph = graphContent.lineSequence()
            .filter { line ->
                PromptAssembler.SENSITIVE_MEMORY_KEYWORDS.none { keyword ->
                    line.contains(keyword, ignoreCase = true)
                }
            }
            .joinToString("\n")
            .trim()
        return buildString {
            append(withoutPhysical.substring(0, graphContentStart))
            append('\n')
            append(filteredGraph)
            append('\n')
            append(withoutPhysical.substring(graphEndStart))
        }.trim()
    }
}
