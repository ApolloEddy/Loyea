package com.loyea.character.core.api

import com.loyea.character.core.worldinfo.WorldInfoConfig
import com.loyea.character.core.worldinfo.WorldInfoEntry

/**
 * 一个只读回合的输入（Spec §3.2 TurnInput）。
 * 随机源由调用方以 randomSeed 提供，编译过程不读取任何可变全局状态；
 * 归属检查（sessionId + bindingRevision + requestId）由宿主在写回时执行。
 */
data class TurnInput(
    val requestId: String,
    val sessionId: String,
    val bindingRevision: Long,
    val characterRevision: Long,
    /** normal / regenerate / continue / impersonate / quiet */
    val generationKind: String,
    /** 只读消息快照正文（时间正序），世界书扫描窗口取其尾部 */
    val historyContents: List<String>,
    val userName: String,
    /** 本轮参与匹配的世界书条目（角色书 + 全局/会话书，由宿主按来源规则组合） */
    val worldInfoEntries: List<WorldInfoEntry>,
    val worldInfoConfig: WorldInfoConfig,
    val randomSeed: Long
)

/** 提示词块的来源类别（Spec §3.2 PromptBlock）。 */
object PromptBlockCategory {
    const val HOST = "HOST"
    const val CHARACTER = "CHARACTER"
    const val WORLD = "WORLD"
    const val MEMORY = "MEMORY"
    const val EXAMPLES = "EXAMPLES"
    const val POST_HISTORY = "POST_HISTORY"
}

/**
 * 无副作用的结构化提示词块：只有来源、类别、文本与固定插入槽位。
 * 槽位遵循 Spec §5.1 固定顺序合同（1 宿主 → 2 角色指令 → 3 before_char →
 * 4 角色字段 → 5 after_char → 6 示例 → 7 记忆摘要 → [历史] → 9 历史后指令）。
 */
data class PromptBlock(
    val sourceId: String,
    val category: String,
    val text: String,
    val slot: Int
) {
    companion object {
        const val SLOT_HOST = 1
        const val SLOT_CHAR_SYSTEM = 2
        const val SLOT_WORLD_BEFORE_CHAR = 3
        const val SLOT_CHAR_FIELDS = 4
        const val SLOT_WORLD_AFTER_CHAR = 5
        const val SLOT_EXAMPLES = 6
        const val SLOT_MEMORY = 7
        const val SLOT_POST_HISTORY = 9
    }
}
