package com.loyea.character.core.api

import com.loyea.character.core.codec.CardBook
import com.loyea.character.core.codec.CardDocument
import com.loyea.character.core.codec.CharacterCardCodec
import com.loyea.character.core.codec.CharxArchive
import com.google.gson.JsonParser
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 导入 facade：把 codec 的卡文档转换为统一 CharacterDocument，并生成导入报告。
 * 本类不做文件路径选择、不发起网络请求、不产生副作用（Spec §3.1）。
 */
object CharacterCardImporter {

    /** 按文件内容识别格式导入：PNG 签名 → PNG；ZIP 签名 → CHARX；其余按 JSON 文本。 */
    fun import(bytes: ByteArray, fileName: String? = null): ImportResult {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return importPng(bytes.inputStream())
        }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            throw ImportFailure.NotRecognized("CHARX 容器")
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        return importJson(text)
    }

    fun importJson(json: String): ImportResult {
        val card = CharacterCardCodec.parseJson(json)
            ?: throw ImportFailure.Corrupted("JSON 角色卡")
        return fromCardDocument(card)
    }

    fun importPng(input: InputStream): ImportResult {
        val card = CharacterCardCodec.parsePng(input)
            ?: throw ImportFailure.Corrupted("PNG 角色卡（未找到有效 chara/ccv3 数据块）")
        return fromCardDocument(card)
    }

    fun importCharx(input: InputStream): ImportResult {
        val archive = CharacterCardCodec.parseCharxWithAssets(input)
            ?: throw ImportFailure.Corrupted("CHARX 容器")
        return fromCardDocument(archive.document)
    }

    /**
     * CardDocument → CharacterDocument。
     * 嵌套 data 的已知字段为运行真源；spec/spec_version 与原始 JSON 全量保留。
     */
    fun fromCardDocument(card: CardDocument): ImportResult {
        val data = card.data
        val profileId = CharacterCardCodec.stableId(card)
        val profile = CharacterProfile(
            id = profileId,
            revision = 1L,
            name = data.name.ifBlank { "未命名角色" },
            description = data.description,
            personality = data.personality,
            scenario = data.scenario,
            systemPrompt = data.systemPrompt,
            postHistoryInstructions = data.postHistoryInstructions,
            firstMessage = data.firstMessage,
            alternateGreetings = data.alternateGreetings,
            mesExample = data.mesExample,
            origin = CharacterOrigin.IMPORTED,
            display = com.loyea.character.core.api.CharacterDisplayInfo(
                shortIntro = data.shortDescription.orEmpty(),
                creatorName = data.creator.ifBlank { null }
            )
        )
        val capabilities = detectCapabilities(card)
        val document = CharacterDocument(
            profile = profile,
            spec = card.spec,
            specVersion = card.specVersion,
            extensionsJson = data.extensionsJson,
            embeddedBookJson = data.characterBook?.rawJson,
            rawCardJson = card.rawJson,
            capabilities = capabilities
        )
        return ImportResult(document, reportOf(capabilities))
    }

    /** 从内嵌书或独立书 JSON 判断世界书是否参与运行（供报告与迁移共用）。 */
    fun hasActiveBook(document: CharacterDocument): Boolean =
        !document.embeddedBookJson.isNullOrBlank() &&
            runCatching {
                val book = JsonParser.parseString(document.embeddedBookJson)
                val entries = book.asJsonObject.get("entries")
                when {
                    entries == null -> false
                    entries.isJsonArray -> entries.asJsonArray.size() > 0
                    entries.isJsonObject -> entries.asJsonObject.size() > 0
                    else -> false
                }
            }.getOrDefault(false)

    private fun reportOf(capabilities: List<CharacterCapability>): ImportReport = ImportReport(
        active = capabilities.filter { it.kind == CharacterCapability.KIND_ACTIVE }.map { it.field },
        preservedOnly = capabilities.filter { it.kind == CharacterCapability.KIND_PRESERVED }.map { it.field },
        unsupported = capabilities.filter { it.kind == CharacterCapability.KIND_UNSUPPORTED }.map { it.field }
    )

    /**
     * 能力盘点：区分参与运行 / 仅保留 / 本轮不支持。
     * 未知能力要保留数据并在角色详情报告，不悄悄当成已支持（Spec §1）。
     */
    private fun detectCapabilities(card: CardDocument): List<CharacterCapability> {
        val result = ArrayList<CharacterCapability>()
        val extensions = runCatching {
            JsonParser.parseString(card.data.extensionsJson).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

        fun extensionKeys(): Set<String> = extensions?.keySet().orEmpty()

        // 世界书参与运行
        if (card.data.characterBook != null) {
            val entryCount = card.data.characterBook.entries.size
            result += CharacterCapability(
                field = "character_book",
                kind = CharacterCapability.KIND_ACTIVE,
                detail = "$entryCount entries"
            )
        }
        // 已知但本轮不支持
        if (extensionKeys().any { it == "regex_scripts" }) {
            result += CharacterCapability(
                field = "extensions.regex_scripts",
                kind = CharacterCapability.KIND_UNSUPPORTED,
                detail = "正则脚本将在有限正则阶段按白名单映射"
            )
        }
        if (extensionKeys().any { it == "tavern_helper" || it == "scripts" }) {
            result += CharacterCapability(
                field = "extensions.tavern_helper",
                kind = CharacterCapability.KIND_UNSUPPORTED,
                detail = "不执行任意脚本"
            )
        }
        val depthPrompt = extensions?.get("depth_prompt")
        if (depthPrompt != null && depthPrompt.isJsonObject &&
            depthPrompt.asJsonObject.get("prompt")?.asString?.isNotBlank() == true
        ) {
            result += CharacterCapability(
                field = "extensions.depth_prompt",
                kind = CharacterCapability.KIND_PRESERVED,
                detail = "深度提示词本轮不注入，原文保留"
            )
        }
        if (card.data.assetsJson != "[]" && card.data.assetsJson.isNotBlank()) {
            result += CharacterCapability(
                field = "assets",
                kind = CharacterCapability.KIND_PRESERVED,
                detail = "V3 资产仅保留引用"
            )
        }
        if (card.data.groupOnlyGreetings.isNotEmpty()) {
            result += CharacterCapability(
                field = "group_only_greetings",
                kind = CharacterCapability.KIND_PRESERVED,
                detail = "群聊开场白本轮不使用"
            )
        }
        if (card.data.characterBook?.entries?.any { it.vectorized == true } == true) {
            result += CharacterCapability(
                field = "world_info.vectorized",
                kind = CharacterCapability.KIND_UNSUPPORTED,
                detail = "不启用向量化检索"
            )
        }
        return result
    }

    /** 供迁移路径复用：把 0.6.1 的完整卡文档转成统一文档（保留原始 ID 语义由调用方处理）。 */
    fun fromRawCardJson(rawJson: String): ImportResult = importJson(rawJson)

    /** CHARX 解包给 UI 落盘资产用；本轮导入不依赖它。 */
    fun parseCharxArchive(input: InputStream): CharxArchive? = CharacterCardCodec.parseCharxWithAssets(input)

    /** 卡书映射供世界书运行时使用（P2 接入）。 */
    fun bookFromCard(card: CardDocument): CardBook? = card.data.characterBook
}
