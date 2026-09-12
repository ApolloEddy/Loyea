package com.loyea.worker

/**
 * 记忆整理核心记忆提交决策（审计 R-02 / AC-14/15/25 的纯逻辑部分）。
 *
 * 合同：
 * - 用户在整理运行期间的任何核心记忆增删改（memoryRevision 变化）→ 本轮模型产出作废，
 *   绝不覆盖用户新状态（旧删除不复活、新增不丢失）；
 * - 模型返回"无新增记忆"（提取为空）→ 严格 no-op，绝不清空既有记忆；
 * - 有新增 → 锁定事实（★ 前缀）永远保留且置顶，模型产出去重后并接。
 *
 * 决策在 updateSessionList 的事务变换内执行（同一把锁内比对修订号并写回），
 * 本对象只负责无副作用的分支计算，便于单元测试。
 */
object MemoryConsolidationPolicy {

    sealed class CoreDecision {
        /** 用户中途编辑过核心记忆：整轮作废（含水位不推进，下轮用最新状态重做）。 */
        object Abort : CoreDecision()

        /** 无新增记忆：不改记忆，仅推进整理水位。 */
        data class AdvanceWatermark(val consolidatedUpTo: Int) : CoreDecision()

        /** 提交新记忆列表并递增修订号、推进水位。 */
        data class Commit(val memories: List<String>, val consolidatedUpTo: Int) : CoreDecision()
    }

    /**
     * @param revisionAtStart 整理任务开始时读取的会话 memoryRevision
     * @param currentRevision 提交时刻（锁内重读）的会话 memoryRevision
     * @param extracted 模型产出、已完成隐私过滤的非锁定事实
     * @param lockedFacts 会话当前的 ★ 锁定事实
     * @param processedCount 本轮实际参与整理的消息数（新水位）
     */
    fun decideCoreCommit(
        revisionAtStart: Long,
        currentRevision: Long,
        extracted: List<String>,
        lockedFacts: List<String>,
        processedCount: Int
    ): CoreDecision = when {
        currentRevision != revisionAtStart -> CoreDecision.Abort
        extracted.isEmpty() -> CoreDecision.AdvanceWatermark(processedCount)
        else -> {
            val lockedContents = lockedFacts.map { it.removePrefix("★").trim() }
            val merged = ArrayList<String>(lockedFacts.size + extracted.size)
            merged += lockedFacts
            for (fact in extracted) {
                val normalized = fact.trim()
                if (normalized.isEmpty()) continue
                val duplicatesLocked = lockedContents.any { it.isNotEmpty() && normalized.contains(it) }
                val duplicated = merged.any { it == normalized }
                if (!duplicatesLocked && !duplicated) merged.add(normalized)
            }
            CoreDecision.Commit(merged, processedCount)
        }
    }
}
