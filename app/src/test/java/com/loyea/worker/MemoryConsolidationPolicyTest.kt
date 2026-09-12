package com.loyea.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-02 记忆整理条件提交合同测试（审计 AC-14/15/25）：
 * 修订冲突作废、空结果 no-op 不清空、锁定事实保留置顶、去重不丢新增。
 */
class MemoryConsolidationPolicyTest {

    @Test
    fun `revision conflict aborts regardless of extraction`() {
        val d = MemoryConsolidationPolicy.decideCoreCommit(
            revisionAtStart = 5L,
            currentRevision = 6L, // 用户在整理运行期间编辑过
            extracted = listOf("新事实"),
            lockedFacts = listOf("★锁定"),
            processedCount = 30
        )
        assertTrue(d is MemoryConsolidationPolicy.CoreDecision.Abort)
    }

    @Test
    fun `empty extraction is a no-op that only advances watermark`() {
        val d = MemoryConsolidationPolicy.decideCoreCommit(
            revisionAtStart = 5L,
            currentRevision = 5L,
            extracted = emptyList(), // 模型返回"无新增记忆"等不含方括号的文本
            lockedFacts = listOf("★锁定"),
            processedCount = 30
        )
        assertTrue(d is MemoryConsolidationPolicy.CoreDecision.AdvanceWatermark)
        // 绝不清空：既有记忆保持原样（决策不携带新记忆列表）
        assertEquals(30, (d as MemoryConsolidationPolicy.CoreDecision.AdvanceWatermark).consolidatedUpTo)
    }

    @Test
    fun `locked facts always survive and lead the list`() {
        val d = MemoryConsolidationPolicy.decideCoreCommit(
            revisionAtStart = 5L,
            currentRevision = 5L,
            extracted = listOf("喜欢抹茶拿铁"),
            lockedFacts = listOf("★生日是 3 月 5 日"),
            processedCount = 30
        )
        val commit = d as MemoryConsolidationPolicy.CoreDecision.Commit
        assertEquals(listOf("★生日是 3 月 5 日", "喜欢抹茶拿铁"), commit.memories)
        assertEquals(30, commit.consolidatedUpTo)
    }

    @Test
    fun `model restating locked content does not duplicate it`() {
        val d = MemoryConsolidationPolicy.decideCoreCommit(
            revisionAtStart = 5L,
            currentRevision = 5L,
            extracted = listOf("生日是 3 月 5 日", "另一件新事"),
            lockedFacts = listOf("★生日是 3 月 5 日"),
            processedCount = 30
        )
        val commit = d as MemoryConsolidationPolicy.CoreDecision.Commit
        assertEquals(listOf("★生日是 3 月 5 日", "另一件新事"), commit.memories)
    }

    @Test
    fun `exact duplicates within extraction collapse to one`() {
        val d = MemoryConsolidationPolicy.decideCoreCommit(
            revisionAtStart = 5L,
            currentRevision = 5L,
            extracted = listOf("喜欢猫", "喜欢猫", "喜欢狗"),
            lockedFacts = emptyList(),
            processedCount = 30
        )
        val commit = d as MemoryConsolidationPolicy.CoreDecision.Commit
        assertEquals(listOf("喜欢猫", "喜欢狗"), commit.memories)
    }

    @Test
    fun `blank extracted entries are dropped`() {
        val d = MemoryConsolidationPolicy.decideCoreCommit(
            revisionAtStart = 5L,
            currentRevision = 5L,
            extracted = listOf("   ", "有效事实"),
            lockedFacts = emptyList(),
            processedCount = 30
        )
        val commit = d as MemoryConsolidationPolicy.CoreDecision.Commit
        assertEquals(listOf("有效事实"), commit.memories)
    }
}
