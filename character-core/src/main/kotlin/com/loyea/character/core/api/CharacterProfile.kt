package com.loyea.character.core.api

/**
 * 角色展示信息：只影响列表/头像/简介等 UI 呈现，不参与权限判断或提示词编译。
 * shortIntro 仅作一句话展示；description 不会被它截断替代（Spec §4.3）。
 */
data class CharacterDisplayInfo(
    val avatarUri: String? = null,
    val avatarColor: String = "#E5D3B3",
    val shortIntro: String = "",
    val creatorName: String? = null,
    val backgroundUri: String? = null
)

/**
 * 角色来源。导入来源仅作元数据，不决定权限（Spec §3.2）：
 * 不存在「非内置角色必须获得租约才能聊天」的条件。
 */
enum class CharacterOrigin {
    /** 用户在应用内从零创建的基本人格。 */
    NATIVE,

    /** 从角色卡文件导入的人格。 */
    IMPORTED,

    /** 内置模板人格；用户保存的覆盖优先，模板只在无已保存文档时应用（Spec §4.8）。 */
    BUILT_IN_TEMPLATE
}

/**
 * 运行时消费的角色基本字段（Spec §3.2 CharacterProfile）。
 * id 与名称解耦：重复导入同名卡不会靠名称覆盖既有角色。
 */
data class CharacterProfile(
    val id: String,
    val revision: Long = 1L,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val mesExample: String = "",
    val origin: CharacterOrigin = CharacterOrigin.NATIVE,
    val display: CharacterDisplayInfo = CharacterDisplayInfo()
) {
    val isBuiltIn: Boolean get() = origin == CharacterOrigin.BUILT_IN_TEMPLATE
}
