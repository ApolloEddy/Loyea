package com.loyea.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静默空回复/非 SSE 响应契约测试（2026-09-09 小马中转排障沉淀）：
 * 整条流读不到任何 data 事件时，旧逻辑一律当正常结束——零内容零报错误导排障方向
 * （用户侧表现为"省略号跳几下就停"，控制台 0 请求，真假凶难辨）。
 * 锁定三条不变式：
 * 1. SSE data 行兼容带空格/不带空格两种网关序列化；
 * 2. HTTP 200 非 SSE 体按 Empty/ApiError/Completion/Unrecognized 四分类，不再静默；
 * 3. 渠道忽略 stream 参数回整包 JSON 时降级解析，回复仍可用。
 */
class LlmClientStreamSilentFailureTest {

    // ===== 不变式 1：data 行前缀兼容 =====

    @Test
    fun `sse data line with standard space is extracted`() {
        val payload = extractSseDataPayload("data: {\"choices\":[]}")
        assertEquals("{\"choices\":[]}", payload)
    }

    @Test
    fun `sse data line without space is extracted`() {
        // 部分网关省略冒号后空格——旧实现 substring(6)/startsWith("data: ") 整行丢弃
        val payload = extractSseDataPayload("data:{\"choices\":[]}")
        assertEquals("{\"choices\":[]}", payload)
    }

    @Test
    fun `sse done marker parses in both formats`() {
        assertEquals("[DONE]", extractSseDataPayload("data: [DONE]"))
        assertEquals("[DONE]", extractSseDataPayload("data:[DONE]"))
    }

    @Test
    fun `non data lines return null`() {
        assertNull(extractSseDataPayload("event: message"))
        assertNull(extractSseDataPayload(": heartbeat"))
        assertNull(extractSseDataPayload("<html>"))
    }

    @Test
    fun `bare data prefix yields empty payload not null`() {
        assertEquals("", extractSseDataPayload("data:"))
    }

    // ===== 不变式 2：非 SSE 体四分类 =====

    @Test
    fun `empty body classified as Empty`() {
        val outcome = LlmClient().interpretNonSseBody("")
        assertTrue(outcome is NonSseBodyOutcome.Empty)
    }

    @Test
    fun `error json body classified as ApiError with vendor message`() {
        val outcome = LlmClient().interpretNonSseBody(
            """{"error":{"message":"Insufficient balance","type":"billing"}}"""
        )
        assertTrue(outcome is NonSseBodyOutcome.ApiError)
        assertEquals("Insufficient balance", (outcome as NonSseBodyOutcome.ApiError).message)
    }

    @Test
    fun `html body classified as Unrecognized with sample`() {
        val outcome = LlmClient().interpretNonSseBody("<html><body>502 Bad Gateway</body></html>")
        assertTrue(outcome is NonSseBodyOutcome.Unrecognized)
        assertTrue((outcome as NonSseBodyOutcome.Unrecognized).sample.contains("502"))
    }

    @Test
    fun `whitespace-only body classified as Empty`() {
        // 流读取端 trim 后才进入判定
        val outcome = LlmClient().interpretNonSseBody("   \n  ".trim())
        assertTrue(outcome is NonSseBodyOutcome.Empty)
    }

    // ===== 不变式 3：整包 JSON 降级为非流式解析 =====

    @Test
    fun `full completion json classified as Completion with usable content`() {
        // 渠道忽略 stream=true 直接回整包 JSON——降级后回复内容必须仍可提取
        val body = """
            {"id":"x","choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"你好呀"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":3,"total_tokens":13}}
        """.trimIndent()
        val outcome = LlmClient().interpretNonSseBody(body)
        assertNotNull(outcome)
        val completion = outcome as? NonSseBodyOutcome.Completion
        assertNotNull("整包 choices JSON 必须降级为 Completion 而非报错", completion)
        assertEquals("你好呀", completion!!.response.content)
        assertEquals(3L, completion.response.completionTokens)
    }

    @Test
    fun `full completion json with think tags routes thoughts correctly`() {
        val body = """
            {"choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"<think>推理</think>正文"}}]}
        """.trimIndent()
        val completion = LlmClient().interpretNonSseBody(body) as NonSseBodyOutcome.Completion
        assertEquals("正文", completion.response.content)
        assertEquals("推理", completion.response.thoughts)
    }
}
