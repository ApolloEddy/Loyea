package com.loyea.plugins.tavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernCardUrlResolverTest {

    // ---------- chub：id 提取与 API URL 生成 ----------

    @Test
    fun `chub 角色页标准链接应提取 id 并生成 api url`() {
        val source = requireNotNull(TavernCardUrlResolver.resolve("https://www.chub.ai/characters/abc123"))
        assertEquals("www.chub.ai", source.host)
        assertEquals("abc123", source.sourceId)
        assertEquals("https://www.chub.ai/api/characters/abc123", source.apiUrl)
    }

    @Test
    fun `chub 主域名不含 www 也应识别`() {
        val source = requireNotNull(TavernCardUrlResolver.resolve("https://chub.ai/characters/abc123"))
        assertEquals("abc123", source.sourceId)
        assertEquals("https://www.chub.ai/api/characters/abc123", source.apiUrl)
    }

    @Test
    fun `chub 角色卡 id 可含下划线短横线点`() {
        val source = requireNotNull(TavernCardUrlResolver.resolve("https://www.chub.ai/characters/a_b-1.lie"))
        assertEquals("a_b-1.lie", source.sourceId)
        assertEquals("https://www.chub.ai/api/characters/a_b-1.lie", source.apiUrl)
    }

    @Test
    fun `chub 链接带 query 或 hash 应被剥离`() {
        val query = TavernCardUrlResolver.resolve("https://www.chub.ai/characters/abc123?tab=info")
        val hash = TavernCardUrlResolver.resolve("https://www.chub.ai/characters/abc123#reviews")
        val both = TavernCardUrlResolver.resolve("https://www.chub.ai/characters/abc123?x=1&y=2#notes")
        listOf(query, hash, both).forEach {
            assertEquals("abc123", requireNotNull(it).sourceId)
            assertEquals("https://www.chub.ai/api/characters/abc123", requireNotNull(it).apiUrl)
        }
    }

    @Test
    fun `chub 链接大小写不敏感且相对路径尾斜杠应容忍`() {
        val upper = TavernCardUrlResolver.resolve("HTTPS://WWW.CHUB.AI/CHARACTERS/AbC123")
        assertEquals("AbC123", requireNotNull(upper).sourceId)
        val trailingSlash = TavernCardUrlResolver.resolve("https://www.chub.ai/characters/abc123/")
        assertEquals("abc123", requireNotNull(trailingSlash).sourceId)
    }

    // ---------- chub：非角色卡链接返回 null ----------

    @Test
    fun `chub 非 characters 路径应返回 null`() {
        assertNull(TavernCardUrlResolver.resolve("https://www.chub.ai/users/abc123"))
        assertNull(TavernCardUrlResolver.resolve("https://www.chub.ai/"))
        assertNull(TavernCardUrlResolver.resolve("https://www.chub.ai"))
    }

    @Test
    fun `chub characteristics 前缀不应混淆为 characters`() {
        assertNull(TavernCardUrlResolver.resolve("https://www.chub.ai/charactersx/abc123"))
    }

    // ---------- 非法 / 无关输入返回 null ----------

    @Test
    fun `未知域名或无关链接应返回 null`() {
        assertNull(TavernCardUrlResolver.resolve("https://example.com/characters/abc"))
        assertNull(TavernCardUrlResolver.resolve("not a url"))
        assertNull(TavernCardUrlResolver.resolve(""))
        assertNull(TavernCardUrlResolver.resolve("https://www.chub.ai/characters/"))
    }

    @Test
    fun `含路径分隔符的非法 id 应返回 null`() {
        assertNull(TavernCardUrlResolver.resolve("https://www.chub.ai/characters/ab/cd"))
    }

    // ---------- aicharactercards host 识别 ----------

    @Test
    fun `aicharactercards 链接应识别 host 且 apiUrl 为空`() {
        val source = requireNotNull(TavernCardUrlResolver.resolve("https://www.aicharactercards.com/character/Abc"))
        assertEquals("aicharactercards.com", source.host)
        assertEquals("", source.apiUrl) // 未实现端点，等待宿主扩展
        assertTrue(source.displayName.contains("需宿主扩展"))
    }

    @Test
    fun `aicharactercards 主域名也应识别`() {
        val source = requireNotNull(TavernCardUrlResolver.resolve("https://aicharactercards.com/character/Abc"))
        assertEquals("aicharactercards.com", source.host)
    }

    // ---------- supportedHosts ----------

    @Test
    fun `supportedHosts 应列出所有可识别主机`() {
        val hosts = TavernCardUrlResolver.supportedHosts()
        assertTrue(hosts.contains("www.chub.ai"))
        assertTrue(hosts.contains("chub.ai"))
        assertTrue(hosts.contains("aicharactercards.com"))
    }
}