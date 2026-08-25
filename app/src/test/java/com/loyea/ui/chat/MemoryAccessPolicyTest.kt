package com.loyea.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryAccessPolicy.isMemoryEnabledForSession] 三态归一化逻辑测试（B5 会话级记忆开关）。
 */
class MemoryAccessPolicyTest {

    @Test
    fun `B5 会话未配置时跟随全局默认`() {
        val session = ChatSession("s", "S") // memoryEnabled 缺省为 null
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(session, globalDefault = true))
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(session, globalDefault = false))
    }

    @Test
    fun `B5 会话显式关闭时强制关闭即使全局开启`() {
        val session = ChatSession("s", "S").copy(memoryEnabled = false)
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(session, globalDefault = true))
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(session, globalDefault = false))
    }

    @Test
    fun `B5 会话显式开启时强制开启即使全局关闭`() {
        val session = ChatSession("s", "S").copy(memoryEnabled = true)
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(session, globalDefault = false))
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(session, globalDefault = true))
    }

    @Test
    fun `B5 会话对象为 null 时跟随全局默认`() {
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(null, globalDefault = true))
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(null, globalDefault = false))
    }

    @Test
    fun `B5 未配置与显式开启在全局开启时结果一致`() {
        val unset = ChatSession("s", "S")
        val explicitOn = ChatSession("s", "S").copy(memoryEnabled = true)
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(unset, globalDefault = true))
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(explicitOn, globalDefault = true))
    }

    @Test
    fun `B5 区分三态对全局默认的覆盖方向`() {
        val off = ChatSession("s", "S").copy(memoryEnabled = false)
        val unset = ChatSession("s", "S")
        val on = ChatSession("s", "S").copy(memoryEnabled = true)
        // 全局开启时：off 关闭、unset/on 开启
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(off, globalDefault = true))
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(unset, globalDefault = true))
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(on, globalDefault = true))
        // 全局关闭时：off/unset 关闭、on 强制开启
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(off, globalDefault = false))
        assertFalse(MemoryAccessPolicy.isMemoryEnabledForSession(unset, globalDefault = false))
        assertTrue(MemoryAccessPolicy.isMemoryEnabledForSession(on, globalDefault = false))
    }
}