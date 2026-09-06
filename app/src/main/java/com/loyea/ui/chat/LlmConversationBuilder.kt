package com.loyea.ui.chat

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
        compressedSummary: String = "",
        includeMessageTimestamps: Boolean = false,
        allowPhysicalContext: Boolean = true,
        allowGraphContext: Boolean = true,
        timeZone: TimeZone = TimeZone.getDefault(),
        postHistoryInstructions: String = ""
    ): List<LlmChatMessage> {
        val result = mutableListOf<LlmChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            result.add(LlmChatMessage(role = "system", content = systemPrompt))
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
                    !it.content.startsWith("[Error]")
            }
            .toList()
            .takeLast(20)

        recentHistory.forEachIndexed { index, message ->
            val effectiveImage = if (includeVision && !message.imageUrl.isNullOrBlank()) message.imageUrl else null
            val effectiveAudio = if (
                includeAudio && index == recentHistory.lastIndex && !message.audioUrl.isNullOrBlank()
            ) {
                message.audioUrl
            } else {
                null
            }

            var textContent = message.content
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
                append(textContent)
            }

            result.add(
                LlmChatMessage(
                    role = if (message.sender == Sender.USER) "user" else "assistant",
                    content = providerContent,
                    imageUrl = effectiveImage,
                    audioUrl = effectiveAudio
                )
            )
        }
        // Spec 5.1.9：角色历史后指令必须位于历史之后，不得并入前部常驻字符串
        if (postHistoryInstructions.isNotBlank()) {
            result.add(LlmChatMessage(role = "system", content = postHistoryInstructions))
        }
        return result
    }

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
