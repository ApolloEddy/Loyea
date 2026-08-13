package com.loyea.health

/**
 * 一次健康数据快照。所有字段可空：null 表示「无此数据」，与「0 步 / 正常值」天然区分。
 *
 * @param sourceLabel 主数据源标签（Health Connect / Smartwatch Bluetooth / Simulated）
 * @param dataOrigins 写入数据的 App 包名集合（探源，配对面板用）
 */
data class HealthSnapshot(
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val steps: Int? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val sleepMinutes: Int? = null,
    val sleepQuality: String? = null,
    val exerciseMinutes: Int? = null,
    val exerciseCalories: Int? = null,
    val exerciseType: String? = null,
    val collectedAt: Long = System.currentTimeMillis(),
    val sourceLabel: String = "Unknown",
    val dataOrigins: Set<String> = emptySet(),
) {
    /** 是否含任何数值型数据（steps=0 也视为有数据）。 */
    val hasData: Boolean get() = listOf(
        heartRateBpm, restingHeartRateBpm, steps, bpSystolic, bpDiastolic,
        sleepMinutes, exerciseMinutes, exerciseCalories
    ).any { it != null }

    /** 用 other 补齐自身缺失字段；sourceLabel 保留 this（主源）的；dataOrigins/collectedAt 取并集/最大。 */
    fun fillMissingFrom(other: HealthSnapshot): HealthSnapshot = copy(
        heartRateBpm = heartRateBpm ?: other.heartRateBpm,
        restingHeartRateBpm = restingHeartRateBpm ?: other.restingHeartRateBpm,
        steps = steps ?: other.steps,
        bpSystolic = bpSystolic ?: other.bpSystolic,
        bpDiastolic = bpDiastolic ?: other.bpDiastolic,
        sleepMinutes = sleepMinutes ?: other.sleepMinutes,
        sleepQuality = sleepQuality ?: other.sleepQuality,
        exerciseMinutes = exerciseMinutes ?: other.exerciseMinutes,
        exerciseCalories = exerciseCalories ?: other.exerciseCalories,
        exerciseType = exerciseType ?: other.exerciseType,
        dataOrigins = dataOrigins + other.dataOrigins,
        collectedAt = maxOf(collectedAt, other.collectedAt)
    )
}

/** 读取健康数据的类型化结果，替代 String 哨兵。 */
sealed interface HealthDataResult<out T> {
    data class Success<T>(val data: T) : HealthDataResult<T>
    data class NoPermission(val missing: Set<String>) : HealthDataResult<Nothing>
    object Unavailable : HealthDataResult<Nothing>
    object NoData : HealthDataResult<Nothing>
    data class Error(val message: String?) : HealthDataResult<Nothing>
}
