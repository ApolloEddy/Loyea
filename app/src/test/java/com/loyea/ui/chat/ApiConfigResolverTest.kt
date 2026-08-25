package com.loyea.ui.chat

import com.loyea.ui.settings.ApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiConfigResolverTest {

    private fun cfg(id: String, name: String = id) = ApiConfig(
        id = id,
        name = name,
        provider = "DeepSeek",
        apiUrl = "https://api.deepseek.com/v1",
        modelName = "deepseek-chat"
    )

    private val global = cfg("global", "Global")
    private val sessionBound = cfg("sessionApi", "Session Bound")
    private val speakerBound = cfg("speakerApi", "Speaker Bound")
    private val configList = listOf(global, sessionBound, speakerBound)

    // ---------- B4：resolveApiConfigForSession 全分支 ----------

    @Test
    fun `B4 会话无对象时回退全局`() {
        assertEquals(global, ApiConfigResolver.resolveApiConfigForSession(null, "global", configList))
    }

    @Test
    fun `B4 会话绑定命中时用绑定配置而非全局`() {
        val session = ChatSession("s", "S").copy(apiBindingId = "sessionApi")
        assertEquals(
            sessionBound,
            ApiConfigResolver.resolveApiConfigForSession(session, "global", configList)
        )
    }

    @Test
    fun `B4 会话绑定在列表中缺失时回退全局`() {
        val session = ChatSession("s", "S").copy(apiBindingId = "missing-api")
        assertEquals(
            global,
            ApiConfigResolver.resolveApiConfigForSession(session, "global", configList)
        )
    }

    @Test
    fun `B4 会话绑定为空白串视为未绑定并回退全局`() {
        val session = ChatSession("s", "S").copy(apiBindingId = "   ")
        assertEquals(
            global,
            ApiConfigResolver.resolveApiConfigForSession(session, "global", configList)
        )
    }

    @Test
    fun `B4 会话绑定缺省 null 回退全局`() {
        val session = ChatSession("s", "S")
        assertEquals(
            global,
            ApiConfigResolver.resolveApiConfigForSession(session, "global", configList)
        )
    }

    @Test
    fun `B4 全局也未命中返回 null`() {
        val session = ChatSession("s", "S").copy(apiBindingId = "missing-api")
        assertNull(ApiConfigResolver.resolveApiConfigForSession(session, "no-global", configList))
        assertNull(ApiConfigResolver.resolveApiConfigForSession(null, "no-global", configList))
    }

    // ---------- B7：resolveSpeakerApiConfig 选角绑定与回退 ----------

    @Test
    fun `B7 选角绑定命中时用选角专用配置`() {
        val session = ChatSession("s", "S")
            .copy(speakerApiBindingId = "speakerApi", apiBindingId = "sessionApi")
        assertEquals(
            speakerBound,
            ApiConfigResolver.resolveSpeakerApiConfig(session, "global", configList)
        )
    }

    @Test
    fun `B7 选角绑定缺失时回退会话级绑定`() {
        val session = ChatSession("s", "S").copy(apiBindingId = "sessionApi")
        assertEquals(
            sessionBound,
            ApiConfigResolver.resolveSpeakerApiConfig(session, "global", configList)
        )
    }

    @Test
    fun `B7 无任何绑定回退全局`() {
        val session = ChatSession("s", "S")
        assertEquals(global, ApiConfigResolver.resolveSpeakerApiConfig(session, "global", configList))
    }

    @Test
    fun `B7 选角绑定在列表中缺失时逐级回退到全局`() {
        val session = ChatSession("s", "S")
            .copy(speakerApiBindingId = "missing-speaker", apiBindingId = "missing-session")
        assertEquals(
            global,
            ApiConfigResolver.resolveSpeakerApiConfig(session, "global", configList)
        )
    }

    @Test
    fun `B7 全局缺失时选角逐级回退返回 null`() {
        val session = ChatSession("s", "S")
            .copy(speakerApiBindingId = "missing-speaker")
        assertNull(ApiConfigResolver.resolveSpeakerApiConfig(session, "no-global", configList))
    }

    @Test
    fun `B7 会话为空对象时选角回退全局`() {
        assertEquals(
            global,
            ApiConfigResolver.resolveSpeakerApiConfig(null, "global", configList)
        )
    }
}