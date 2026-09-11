package com.loyea.plugin.companion

/**
 * 陪伴模式插件对外契约：宿主（app 模块）允许引用的最小符号面。
 *
 * 非侵入约定：宿主只通过本契约判断陪伴归属；陪伴功能的全部实现都收敛在
 * com.loyea.plugin.companion 命名空间内。宿主触碰点清单见 plugins/companion/README.md。
 */
object CompanionContract {
    /** 陪伴对象内部资料 ID（FUN-03 独立副本标识，不复用普通角色卡）。 */
    const val COMPANION_CHARACTER_ID = "char_loyea_companion"

    /** 陪伴插件自有偏好文件（不与宿主 loyea_prefs 混用）。 */
    const val PREFS_NAME = "loyea_companion_prefs"

    /** 会话 / 角色卡是否属于陪伴模式归属。 */
    fun isCompanionCharacter(characterId: String?): Boolean =
        characterId == COMPANION_CHARACTER_ID
}

/** App 模式（Spec §9 最小合同：AppMode）。缺失/未开启时按旧版普通模式解析。 */
enum class CompanionAppMode { NORMAL, COMPANION }
