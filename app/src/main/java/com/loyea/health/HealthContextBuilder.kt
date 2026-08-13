package com.loyea.health

import android.content.Context

/**
 * 健康上下文门面：PhysicalContextManager 与 PerceptionMcpServer 共用的统一入口。
 */
class HealthContextBuilder(context: Context) {
    private val repository = HealthSnapshotRepository(context)

    /** 生成多行健康文本（经统一格式化器）。 */
    suspend fun buildHealthContextString(): String =
        HealthContextFormatter.formatSnapshot(repository.readBestSnapshot())

    /** 透传最优快照（供需要类型化数据的调用方使用）。 */
    suspend fun readBestSnapshot(): HealthSnapshot = repository.readBestSnapshot()
}
