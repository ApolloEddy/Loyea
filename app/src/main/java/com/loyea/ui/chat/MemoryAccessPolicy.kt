package com.loyea.ui.chat

/**
 * 会话级长期记忆开关（B5）解析（纯函数，可单测）。
 *
 * `ChatSession.memoryEnabled` 是一个三态开关：null=未配置、走全局默认；false=本会话关闭记忆；
 * true=本会话显式开启记忆。本对象统一把「会话显式值」与「该维度全局开关」归一化成最终生效的
 * 布尔值，供各个记忆注入 / 写回点消费，避免在各调用点重复三态归一化逻辑。
 *
 * 记忆注入 / 写回落点清单：
 * - 注入：核心记忆（PromptAssembler 的 [PromptAssembler.PromptParts] coreMemories）、
 *   图记忆（graphMemory 检索与快照剥离）、压缩摘要（compressedSummary，会话早期摘要）。
 * - 写回 / 触发：记忆后台提炼（[com.loyea.worker.MemoryConsolidationWorker] 写核心记忆与图记忆）、
 *   长会话滑窗压缩（compressedSummary 写回）。
 */
object MemoryAccessPolicy {

    /**
     * 解析某会话是否应启用长期记忆。
     *
     * 优先级：
     * 1. [session].memoryEnabled == false → 返回 false。该会话不注入任何记忆内容（核心记忆 /
     *    图记忆 / 压缩摘要）且不写入新记忆；
     * 2. [session].memoryEnabled == true  → 返回 true。该会话显式开启，即使全局默认关闭也强制开启；
     * 3. [session].memoryEnabled == null  → 跟随 [globalDefault]，保持「未配置」时的现有行为。
     *
     * 调用方需按「该维度的全局开关」传入 [globalDefault]：
     * - 图记忆维度用 enable_graph_memory 的取值；
     * - 核心记忆提炼维度用 enable_memory_consolidation 的取值；
     * - 无独立全局开关的维度（如核心记忆 / 压缩摘要注入）传 true 以保持现状。
     */
    fun isMemoryEnabledForSession(session: ChatSession?, globalDefault: Boolean): Boolean {
        val sessionOverride = session?.memoryEnabled
        return sessionOverride ?: globalDefault
    }
}