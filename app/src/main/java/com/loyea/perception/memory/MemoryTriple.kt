package com.loyea.perception.memory

/**
 * 图谱关系三元组实体，代表一条长程记忆
 */
data class MemoryTriple(
    val id: Long = 0L,            // 唯一 ID（用于 UI 和删除标识）
    val characterId: String,      // 伴侣角色卡 ID (隔离主键)
    val sessionId: String,        // 会话 ID (隔离主键)
    val subject: String,          // 主语 (如: "主人")
    val predicate: String,        // 谓语 (如: "喜欢")
    val `object`: String,         // 宾语 (如: "抹茶燕麦拿铁")
    val creationTime: Long,       // 记忆形成的时间戳
    val lastMentionedTime: Long,  // 最近一次被提及的时间戳
    val mentionCount: Int = 1,    // 提及次数 (用于强化权重)
    val baseWeight: Float = 1.0f  // 基础初始权重
) {
    /**
     * 动态计算当前记忆强度（基于艾宾浩斯遗忘曲线）
     */
    fun getCalculatedWeight(currentTime: Long): Float {
        val daysElapsed = (currentTime - lastMentionedTime).toFloat() / (1000 * 60 * 60 * 24)
        val strengthFactor = 1.0f + mentionCount * 1.5f
        val decay = Math.exp(-daysElapsed.toDouble() / strengthFactor).toFloat()
        return baseWeight * Math.max(0.1f, decay) // 设定最低底限 0.1f 记忆深度
    }
}
