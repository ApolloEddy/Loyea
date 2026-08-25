package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionQueryTest {

    private fun session(
        id: String,
        characterId: String = "char_a",
        lastActiveTime: Long = 0L,
        isPinned: Boolean = false
    ) = ChatSession(
        id = id,
        title = "s-$id",
        characterId = characterId,
        lastActiveTime = lastActiveTime,
        isPinned = isPinned
    )

    // ---------- matchesCharacter ----------

    @Test
    fun `matchesCharacter 仅比较 characterId 字段`() {
        assertEquals(true, ChatSessionQuery.matchesCharacter(session("1", characterId = "char_a"), "char_a"))
        assertEquals(false, ChatSessionQuery.matchesCharacter(session("1", characterId = "char_a"), "char_b"))
    }

    @Test
    fun `matchesCharacter 空白角色 id 也能精确匹配`() {
        assertEquals(true, ChatSessionQuery.matchesCharacter(session("1", characterId = ""), ""))
        assertEquals(false, ChatSessionQuery.matchesCharacter(session("1", characterId = ""), "char_a"))
    }

    // ---------- sessionsForCharacter 过滤 ----------

    @Test
    fun `sessionsForCharacter 只保留指定角色会话`() {
        val all = listOf(
            session("a1", "char_a"),
            session("b1", "char_b"),
            session("a2", "char_a"),
            session("c1", "char_c")
        )
        val result = ChatSessionQuery.sessionsForCharacter(all, "char_a")
        assertEquals(listOf("a1", "a2"), result.map { it.id })
    }

    @Test
    fun `sessionsForCharacter 无匹配时返回空列表`() {
        val all = listOf(session("a1", "char_a"))
        assertEquals(emptyList<ChatSession>(), ChatSessionQuery.sessionsForCharacter(all, "char_none"))
    }

    @Test
    fun `sessionsForCharacter 空输入返回空列表`() {
        assertEquals(emptyList<ChatSession>(), ChatSessionQuery.sessionsForCharacter(emptyList(), "char_a"))
    }

    @Test
    fun `sessionsForCharacter 不修改入参列表`() {
        val all = listOf(session("a2", "char_a", lastActiveTime = 2), session("a1", "char_a", lastActiveTime = 1))
        val snapshot = all.map { it.id }
        ChatSessionQuery.sessionsForCharacter(all, "char_a")
        assertEquals(snapshot, all.map { it.id })
    }

    // ---------- sessionsForCharacter 排序 ----------

    @Test
    fun `sessionsForCharacter 置顶优先于最近活跃`() {
        val all = listOf(
            session("fresh", "char_a", lastActiveTime = 3000),
            session("pinned-old", "char_a", lastActiveTime = 100, isPinned = true),
            session("normal-old", "char_a", lastActiveTime = 2000),
            session("other-char", "char_b", lastActiveTime = 9999)
        )
        val result = ChatSessionQuery.sessionsForCharacter(all, "char_a")
        // 置顶在最前，其余按 lastActiveTime 降序
        assertEquals(listOf("pinned-old", "fresh", "normal-old"), result.map { it.id })
    }

    @Test
    fun `sessionsForCharacter 多个置顶会话按最近活跃降序`() {
        val all = listOf(
            session("pin-old", "char_a", lastActiveTime = 100, isPinned = true),
            session("pin-new", "char_a", lastActiveTime = 500, isPinned = true),
            session("normal", "char_a", lastActiveTime = 1000)
        )
        val result = ChatSessionQuery.sessionsForCharacter(all, "char_a")
        assertEquals(listOf("pin-new", "pin-old", "normal"), result.map { it.id })
    }

    // ---------- chatFilterOptions 计数 ----------

    @Test
    fun `chatFilterOptions 按角色统计会话数且包含可空空白角色键`() {
        val all = listOf(
            session("a1", "char_a"),
            session("a2", "char_a"),
            session("b1", "char_b"),
            session("legacy", characterId = "")
        )
        val opts = ChatSessionQuery.chatFilterOptions(all)
        assertEquals(3, opts.size)
        assertEquals(2, opts["char_a"])
        assertEquals(1, opts["char_b"])
        assertEquals(1, opts[""])
    }

    @Test
    fun `chatFilterOptions 空输入返回空 Map`() {
        assertEquals(emptyMap<String, Int>(), ChatSessionQuery.chatFilterOptions(emptyList()))
    }

    @Test
    fun `chatFilterOptions 结果按键字典序稳定排序`() {
        val all = listOf(session("c", "char_c"), session("a", "char_a"), session("b", "char_b"))
        assertEquals(listOf("char_a", "char_b", "char_c"), ChatSessionQuery.chatFilterOptions(all).keys.toList())
    }

    // ---------- filterSessionsByCharacter ----------

    @Test
    fun `filterSessionsByCharacter 传 null 返回全部会话原序`() {
        val all = listOf(
            session("a", "char_a", lastActiveTime = 1),
            session("b", "char_b", lastActiveTime = 2),
            session("c", "char_c", lastActiveTime = 3)
        )
        assertEquals(all, ChatSessionQuery.filterSessionsByCharacter(all, null))
    }

    @Test
    fun `filterSessionsByCharacter 传角色 id 时按角色过滤并置顶优先排序`() {
        val all = listOf(
            session("fresh", "char_a", lastActiveTime = 10),
            session("pinned", "char_a", lastActiveTime = 1, isPinned = true),
            session("other", "char_b", lastActiveTime = 100)
        )
        val result = ChatSessionQuery.filterSessionsByCharacter(all, "char_a")
        assertEquals(listOf("pinned", "fresh"), result.map { it.id })
    }

    @Test
    fun `filterSessionsByCharacter 空串角色过滤为精确匹配空白角色会话`() {
        val all = listOf(
            session("blank", characterId = ""),
            session("a", "char_a")
        )
        assertEquals(listOf("blank"), ChatSessionQuery.filterSessionsByCharacter(all, "").map { it.id })
    }
}