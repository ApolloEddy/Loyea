package com.loyea.ui.chat

/**
 * 会话按角色查询的纯函数集合（B3 宿主逻辑，无 Compose 依赖，可单测）。
 *
 * 角色匹配字段说明：
 * 会话在创建/续聊时，`ChatSession.characterId` 被显式写入当前绑定角色的 personaId
 * （见 ChatViewModel 中 `chatSession.characterId = selectedRef.personaId`、`characterId = activeCard.id`
 * 以及 `characterId = characterCard.id`）。因此判定"某角色的会话"采用
 * **精确匹配 `session.characterId == characterId`** 即可，无需再叠前缀规则。
 *
 * `personaOwnerId` 只用于区分归属（原生角色 = PluginIds.NATIVE / 酒馆角色 = Tavern 插件），
 * 角色 id 本身在各自命名空间内唯一，故匹配时只比较 `characterId` 一个字段。
 */
object ChatSessionQuery {

    /** 判断某会话是否归属于指定角色：精确比较 characterId。 */
    fun matchesCharacter(session: ChatSession, characterId: String): Boolean =
        session.characterId == characterId

    /**
     * 返回该角色的全部会话（供角色过滤 / 角色维度"历史会话"页使用），
     * 排序规则：置顶(isPinned=true)优先，其次按最近活动时间 lastActiveTime 降序。
     * 不修改入参列表、不改变各会话内部状态。
     */
    fun sessionsForCharacter(allSessions: List<ChatSession>, characterId: String): List<ChatSession> =
        allSessions
            .filter { it.characterId == characterId }
            .sortedWith(
                compareByDescending<ChatSession> { it.isPinned }
                    .thenByDescending { it.lastActiveTime }
            )

    /**
     * 生成"角色ID → 会话计数"的过滤选项表，供过滤 UI 渲染。
     * 键为角色 characterId；历史/遗留数据中 characterId 为空白串的会话统一归入空串键 "" 下
     * （Kotlin 层面字段非空，故不使用 null 键），不影响绝大多数已有角色会话。
     * 结果按键字典序排序，保证 UI 展示顺序稳定。
     */
    fun chatFilterOptions(allSessions: List<ChatSession>): Map<String, Int> =
        allSessions
            .groupingBy { it.characterId }
            .eachCount()
            .toSortedMap()

    /**
     * 按角色过滤会话列表：非 null 时等价于 [sessionsForCharacter]（带排序与置顶优先），
     * 传 null 表示不过滤、返回全部会话原序（列表本身由外部按最近活跃维护）。
     */
    fun filterSessionsByCharacter(
        allSessions: List<ChatSession>,
        characterIdOrNull: String?
    ): List<ChatSession> =
        if (characterIdOrNull == null) {
            allSessions
        } else {
            sessionsForCharacter(allSessions, characterIdOrNull)
        }
}