package com.loyea.health

/**
 * 通用健康数据源接口。任何能提供健康数据的来源（Health Connect、直连手表、模拟器）都实现它。
 * [HealthSnapshotRepository] 负责按优先级合并各来源。
 */
interface HealthDataSource {
    /** 来源标签，写入 [HealthSnapshot.sourceLabel]。 */
    val sourceLabel: String

    /** 读取一次快照。 */
    suspend fun readSnapshot(): HealthDataResult<HealthSnapshot>
}
