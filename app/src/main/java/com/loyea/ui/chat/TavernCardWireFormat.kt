package com.loyea.ui.chat

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * TODO1：宿主 character_cards.json 的 wire 格式 v2。
 *
 * CharacterCard 末尾承载 SillyTavern/Tavern 完整角色信息的扩展字段
 * （description → originalCardJson，见 [TavernCardParser] 的 TODO(D4) 注释）已由 D2
 * 拆进插件私有 TavernCardDocument 存储（`files/tavern/cards/<sha256(id)>.json`）。
 * v2 起宿主 wire 文件不再携带这些字段：序列化走 [createWireGson]（只排除、不碰读取），
 * 读取仍用普通 gson（对旧格式宽容，迁移期兼容）。
 *
 * 宿主运行时缺失这些字段时由 [TavernFieldDropMigration]（一次性补齐文档库）与
 * [TavernCharacterCardAdapter.overlayTavernFields]（每次加载补回扩展字段）共同恢复。
 */
object TavernCardWireFormat {
    const val SCHEMA_VERSION = 1

    /** 与 CharacterCard 的 Tavern 扩展字段一一对应。native 字段（id/name/avatarUri/shortIntro/…）不在此列。 */
    val TAVERN_EXTENSION_FIELD_NAMES: Set<String> = setOf(
        "description",
        "creatorNotes",
        "postHistoryInstructions",
        "alternateGreetings",
        "groupOnlyGreetings",
        "tags",
        "characterVersion",
        "nickname",
        "source",
        "creationDate",
        "modificationDate",
        "creatorNotesMultilingualJson",
        "assetsJson",
        "extensionsJson",
        "characterBookJson",
        "spec",
        "specVersion",
        "originalCardJson"
    )

    /**
     * 序列化专用 gson：把 [CharacterCard] 的 Tavern 扩展字段排除在 wire 之外。
     * 仅作用于 CharacterCard 自己的字段（[FieldAttributes.declaringClass] 精确限定），
     * 其他类（如 [PersonaSummary]）的同名/任意字段不受影响；反序列化完全不受影响。
     */
    fun createWireGson(): Gson = GsonBuilder()
        .addSerializationExclusionStrategy(TavernExtensionExclusionStrategy)
        .create()

    private object TavernExtensionExclusionStrategy : ExclusionStrategy {
        override fun shouldSkipField(field: FieldAttributes): Boolean =
            field.declaringClass == CharacterCard::class.java &&
                field.name in TAVERN_EXTENSION_FIELD_NAMES

        override fun shouldSkipClass(clazz: Class<*>): Boolean = false
    }
}
