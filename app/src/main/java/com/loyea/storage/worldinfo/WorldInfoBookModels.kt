package com.loyea.storage.worldinfo

import com.loyea.character.core.worldinfo.WorldInfoConfig as CoreWorldInfoConfig
import com.loyea.character.core.worldinfo.WorldInfoEntry as CoreWorldInfoEntry
import com.loyea.ui.chat.WorldInfoConfig
import com.loyea.ui.chat.WorldInfoEntry

/** 书来源（WorldInfo 2.0 Spec §3.2 origin）。 */
enum class WorldInfoBookOrigin {
    /** 用户在书库中自建。 */
    CREATED,

    /** 从 SillyTavern World Info JSON 导入。 */
    IMPORTED,

    /** 角色卡内嵌书的引用（内容实时读卡，不落副本）。 */
    CARD
}

/**
 * 生效域（Spec §3.2 scope）。
 * card 书的 scope 无匹配意义（解析走 originCharacterId），恒 GLOBAL。
 */
enum class WorldInfoBookScope { GLOBAL, SESSION }

/** 单一生效书的解析来源层（Spec §4.1）。 */
enum class ActiveBookSource { SESSION_BOUND, CARD_FOLLOW, GLOBAL_ACTIVE, NONE }

/**
 * 书库文档（worldinfo/books/<bookId>.json 的 typed 映射，Spec §3.2）。
 *
 * - owned 书（CREATED/IMPORTED）：entries 为可编辑条目；
 * - card 书（CARD）：entries 恒空，内容实时读 originCharacterId 的 embeddedBookJson，
 *   disabledUids 是唯一可变状态（条目开关 override，不修改卡原文）。
 */
data class WorldInfoBookDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: WorldInfoBookOrigin,
    val originCharacterId: String? = null,
    val scope: WorldInfoBookScope = WorldInfoBookScope.GLOBAL,
    val sessionIds: List<String> = emptyList(),
    val isGlobalActive: Boolean = false,
    val entries: List<WorldInfoEntry> = emptyList(),
    val disabledUids: List<Int> = emptyList(),
    /** null = 继承全局默认配置（Spec §4.3）。 */
    val config: WorldInfoConfig? = null
) {
    val isOwned: Boolean get() = origin != WorldInfoBookOrigin.CARD
}

/**
 * resolveActiveBook 的结果（Spec §3.3）。
 * entries 为已应用 override 过滤的运行时条目（character-core 格式，
 * 原生前缀路径与导入卡编译路径共用）；config 为已解析合并的匹配配置。
 * 解析结果只读、一次请求内冻结（父 Spec §5.3 同轮一致性）。
 */
data class ActiveBookResolution(
    val source: ActiveBookSource,
    val book: WorldInfoBookDocument?,
    val entries: List<CoreWorldInfoEntry>,
    val config: CoreWorldInfoConfig
)

/** 世界书 2.0 一次性迁移结果（Spec §5）。 */
data class WorldInfoMigrationOutcome(
    val performed: Boolean,
    val booksCreated: Int,
    val notes: List<String>
)

/**
 * 书库行摘要（Spec §6.2 列表行数据）：条目计数、卡书来源状态、会话绑定冲突。
 */
data class WorldInfoBookSummary(
    val book: WorldInfoBookDocument,
    val totalEntries: Int,
    val constantEntries: Int,
    val disabledEntries: Int,
    /** card 书：来源卡已删除或内嵌书不可解析（灰显「来源已删除」）。 */
    val sourceDeleted: Boolean = false,
    /** 与其他书重复绑定同一会话的会话 ID（非空 = 冲突徽章，Spec §4.1 tie-break）。 */
    val conflictingSessions: List<String> = emptyList()
)
