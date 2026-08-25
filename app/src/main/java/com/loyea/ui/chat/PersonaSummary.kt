package com.loyea.ui.chat

/**
 * 宿主原生人格最小集（D2 边界类型第一半）。
 *
 * 这是 Loyea 业务运行时（提示词组装、成人格绑定、列表展示等）应当消费的独立人格视图，
 * 只承载不依赖 SillyTavern/Tavern 语义的原生字段。SillyTavern/Tavern 扩展字段
 * （creatorNotes、postHistoryInstructions、alternateGreetings、tags、nickname、source、
 * spec、characterBook、extensions、原始 JSON 等）**不落在本类型**，而是进入插件私有
 * 的 [com.loyea.plugins.tavern.core.TavernCardDocument]（D2 边界类型第二半）。
 *
 * `CharacterCard` 是迁移期宿主侧的遗留桥类型（host legacy bridge），它仍保留 Tavern 扩展
 * 字段用于旧序列化/导入导出的 wire 兼容；一旦 D4 完成宿主核心签名清理，宿主核心将只依赖
 * 本类型与 `PersonaProjection`，不再引用 Tavern 具体类型。
 */
data class PersonaSummary(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val avatarColor: String = "#E5D3B3",
    val shortIntro: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    /** 对应 Tavern `mes_example`（对话示例），宿主提示词组装使用。 */
    val mesExample: String = "",
    val isBuiltIn: Boolean = false,
    val creatorName: String? = null,
    val backgroundUri: String? = null
)