package com.loyea.character.core.api

/**
 * 单个能力的处置结论（Spec §4.9 导入报告四分类的来源）。
 * kind 取值：
 * - [KIND_ACTIVE]：已保存且参与运行；
 * - [KIND_PRESERVED]：仅保留原文，不参与运行；
 * - [KIND_UNSUPPORTED]：已知能力但本轮不支持，保留数据并在角色详情可见。
 */
data class CharacterCapability(
    val field: String,
    val kind: String,
    val detail: String = ""
) {
    companion object {
        const val KIND_ACTIVE = "active"
        const val KIND_PRESERVED = "preserved"
        const val KIND_UNSUPPORTED = "unsupported"
    }
}

/**
 * 角色的权威文档（Spec §3.2 CharacterDocument）：
 * profile 是运行真源，rawCardJson 是导入底稿，未知字段/扩展原样保留。
 * 列表摘要只从它派生；不与另一份可独立编辑的基本人格真源并存。
 */
data class CharacterDocument(
    val profile: CharacterProfile,
    val spec: String? = null,
    val specVersion: String? = null,
    /** data.extensions 原样 JSON（未知扩展保真）。 */
    val extensionsJson: String = "{}",
    /** 内嵌 character_book 原始 JSON（参与运行的世界书）。 */
    val embeddedBookJson: String? = null,
    /** 导入底稿：编辑以它为底按字段更新，未知字段语义保真（Spec §4.6）。 */
    val rawCardJson: String? = null,
    /** 各能力的处置结论，供导入报告与角色详情展示。 */
    val capabilities: List<CharacterCapability> = emptyList()
) {
    fun withProfile(profile: CharacterProfile): CharacterDocument =
        copy(profile = profile)
}

/** 导入报告（Spec §4.9）：正常成功不弹技术清单；有未执行的扩展才提示。 */
data class ImportReport(
    val active: List<String> = emptyList(),
    val preservedOnly: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val hasHiddenDetails: Boolean
        get() = preservedOnly.isNotEmpty() || unsupported.isNotEmpty() || warnings.isNotEmpty()
}

data class ImportResult(
    val document: CharacterDocument,
    val report: ImportReport
)

/** 导入失败原因：错误信息简洁可理解（Spec §4.1）。 */
sealed class ImportFailure(message: String) : Exception(message) {
    class NotRecognized(detail: String = "") : ImportFailure(if (detail.isBlank()) "无法识别的文件格式，仅支持 JSON 或 PNG 角色卡" else "无法识别的文件格式（$detail）")
    class TooLarge(detail: String) : ImportFailure("文件超出安全解析上限（$detail）")
    class Corrupted(detail: String) : ImportFailure("文件已损坏或数据不完整（$detail）")
}
