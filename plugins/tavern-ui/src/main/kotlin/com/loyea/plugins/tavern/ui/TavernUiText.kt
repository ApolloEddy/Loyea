package com.loyea.plugins.tavern.ui

import com.google.gson.JsonParser

/**
 * D3：Tavern 控制面的纯文本/校验逻辑（从宿主导出，供 Compose 渲染面与 JVM 测试共用）。
 *
 * 只做字符串/列表/JSON 判定等纯函数，不接触 Android、Compose 或宿主 [CharacterCard]；
 * SAF、Toast、分享、FileProvider 等界面副作用仍由宿主端口持有。
 */
object TavernUiText {

    /** 头像兜底色板，与旧 UI 保持一致。 */
    val AVATAR_PALETTE: List<String> =
        listOf("#E5D3B3", "#D3E2CD", "#CBE3F5", "#E2D3F5", "#F2D4D7")

    /** 将编辑器中的逗号/换行列表稳定转换为 ST 数组，忽略空项并去重。 */
    fun parseTavernListInput(value: String): List<String> = value
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    /** 可选的扩展 JSON 只接受完整 JSON 对象，避免导出后破坏角色卡结构。 */
    fun isOptionalJsonObjectValid(value: String): Boolean = value.isBlank() || runCatching {
        JsonParser.parseString(value).isJsonObject
    }.getOrDefault(false)

    /** 角色卡编辑器中的多条问候语以独立行保存；保留空行外的可见内容。 */
    fun parseGreetingInput(value: String): List<String> = value
        .split("\n---\n", "\n<GREETING>\n")
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
}
