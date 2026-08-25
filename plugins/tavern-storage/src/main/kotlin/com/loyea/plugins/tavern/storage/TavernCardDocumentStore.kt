package com.loyea.plugins.tavern.storage

import java.io.File

/**
 * 插件私有卡片文档库（raw JSON 层，格式无关）。
 *
 * 每个非内置角色卡在 `cards/<sha256(cardId)>.json` 各持一份完整 Tavern 文档。宿主在
 * wire 格式 v2（character_cards.json 不再携带 Tavern 扩展字段）下以本库为扩展字段的
 * 事实来源：写入走 [write]，读取走 [read] / [exists]。
 *
 * 本类刻意保持"只存字节、不解析结构"：它不依赖 tavern-core 的 TavernCardDocument，
 * 解析由宿主/调用方用 TavernCardCodec 完成，从而维持 storage 模块的边界纯净。
 */
class TavernCardDocumentStore(
    private val layout: TavernStorageLayout
) {
    fun exists(cardId: String): Boolean =
        layout.resolve(layout.cardDocumentRelativePath(cardId)).isFile

    /** 读取指定角色的 raw 文档；不存在时返回 null。 */
    fun read(cardId: String): String? =
        TavernStorageMigrator.readUtf8(layout, layout.cardDocumentRelativePath(cardId))

    /** 原子写入（或覆盖）指定角色的 raw 文档，返回指纹记录供校验。 */
    fun write(cardId: String, rawJson: String): TavernStorageFileRecord {
        layout.ensureDirectories()
        return TavernStorageMigrator.writeUtf8(layout, layout.cardDocumentRelativePath(cardId), rawJson)
    }
}
