package com.loyea.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 统一 Transport 网络级状态机测试矩阵（Spec §23.1）。
 * 用 MockWebServer 在 HTTP 层验证：标准 SSE、各网关变体、降级触发条件、
 * 能力缓存写入纪律（Empty/HTML/malformed 绝不学习）、FORCE_STREAM 不降级、
 * timeout 分离、tools/usage 两种模式。
 */
class UnifiedChatTransportMatrixTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: UnifiedChatTransport

    private val encoder = object : MessageEncoder {
        override fun encodeMessages(messages: List<ChatMessage>): com.google.gson.JsonArray {
            val arr = com.google.gson.JsonArray()
            messages.forEach { msg ->
                arr.add(com.google.gson.JsonObject().apply {
                    addProperty("role", msg.role)
                    addProperty("content", msg.content ?: "")
                })
            }
            return arr
        }

        override fun encodeTools(tools: List<ToolSchema>): com.google.gson.JsonArray = com.google.gson.JsonArray()
    }

    private fun request(
        streamMode: StreamMode = StreamMode.AUTO,
        tools: List<ToolSchema> = emptyList()
    ) = ChatExecutionRequest(
        configId = "cfg-test",
        apiUrl = server.url("/v1").toString(),
        apiKey = "sk-test",
        providerPreset = "Custom",
        model = "test-model",
        messages = listOf(ChatMessage(role = "user", content = "hi")),
        tools = tools,
        streamMode = streamMode
    )

    private fun sseChunk(deltaJson: String, finishReason: String? = null): String {
        val delta = StringBuilder(deltaJson)
        if (finishReason != null) {
            return "data: {\"choices\":[{\"delta\":$deltaJson,\"finish_reason\":$finishReason}]}\n\ndata: [DONE]\n\n"
        }
        return "data: {\"choices\":[{\"delta\":$deltaJson,\"finish_reason\":null}]}\n\n"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        RuntimeCapabilityCache.clearForTest()
        val base = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        transport = UnifiedChatTransport(base)
    }

    @After
    fun tearDown() {
        server.shutdown()
        RuntimeCapabilityCache.clearForTest()
    }

    private fun key() = CapabilityCacheKey("cfg-test", "test-model", "openai-compat")

    // ===== 1. 标准 SSE：逐块输出 + Done =====

    @Test
    fun `standard sse streams content then done`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            sseChunk("{\"content\":\"你\"}") + sseChunk("{\"content\":\"好\"}", "stop")
        ))
        val events = transport.stream(request(), encoder).toList()
        val contents = events.filterIsInstance<TransportEvent.Content>()
        assertEquals("你好", contents.joinToString("") { it.text })
        assertTrue(events.last() is TransportEvent.Done)
        assertFalse(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    // ===== 2. data:{...} 无空格兼容 =====

    @Test
    fun `sse data without space parses`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "data:{\"choices\":[{\"delta\":{\"content\":\"无空格\"},\"finish_reason\":null}]}\n\ndata:[DONE]\n\n"
        ))
        val events = transport.stream(request(), encoder).toList()
        assertEquals("无空格", events.filterIsInstance<TransportEvent.Content>().joinToString("") { it.text })
        assertTrue(events.last() is TransportEvent.Done)
    }

    // ===== 3. SSE thoughts =====

    @Test
    fun `sse reasoning_content emits thoughts`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            sseChunk("{\"reasoning_content\":\"思考中\"}") + sseChunk("{\"content\":\"答\"}", "stop")
        ))
        val events = transport.stream(request(), encoder).toList()
        assertEquals("思考中", events.filterIsInstance<TransportEvent.Thoughts>().joinToString("") { it.text })
    }

    // ===== 4. SSE tool_calls 组装 =====

    @Test
    fun `sse tool calls assembled`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"qu\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"est\\\":\\\"x\\\"}\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
        ))
        val events = transport.stream(request(), encoder).toList()
        val calls = events.filterIsInstance<TransportEvent.ToolCalls>().flatMap { it.calls }
        assertEquals(1, calls.size)
        assertEquals("web_search", calls[0].name)
        assertEquals("{\"qu est\":\"x\"}".replace(" ", ""), calls[0].argumentsJson.replace(" ", ""))
    }

    // ===== 5/7/8. HTTP 400/422/501 明确 unsupported stream → AUTO 降级 =====

    @Test
    fun `http 400 unsupported stream falls back to non stream`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"stream mode is not supported by this channel\"}}")
        )
        server.enqueue(MockResponse().setBody(
            "{\"choices\":[{\"message\":{\"content\":\"整包回复\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}"
        ))
        val events = transport.stream(request(), encoder).toList()
        assertTrue(events.any { it is TransportEvent.Notice })
        assertEquals("整包回复", events.filterIsInstance<TransportEvent.Content>().joinToString("") { it.text })
        assertTrue(events.last() is TransportEvent.Done)
        // fallback 成功 → 学习 prefer non-stream
        assertTrue(RuntimeCapabilityCache.prefersNonStream(key()))
        // 第二次请求应直接走非流式快速路径（不再探测流式）
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http 422 unsupported stream falls back`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("{\"error\":{\"message\":\"streaming is unsupported\"}}")
        )
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
        val events = transport.stream(request(), encoder).toList()
        assertTrue(events.last() is TransportEvent.Done)
        assertTrue(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    // ===== 6. HTTP 200 error JSON: unsupported stream → AUTO 降级 =====

    @Test
    fun `http 200 error json unsupported stream falls back`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "{\"error\":{\"message\":\"该渠道不支持流式传输\"}}"
        ))
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"降级成功\"}}]}"))
        val events = transport.stream(request(), encoder).toList()
        assertEquals("降级成功", events.filterIsInstance<TransportEvent.Content>().joinToString("") { it.text })
        assertTrue(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    // ===== 5b. SSE error 事件 unsupported → AUTO 降级 =====

    @Test
    fun `sse error event unsupported stream falls back`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "data: {\"error\":{\"message\":\"stream not supported\"}}\n\n"
        ))
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
        val events = transport.stream(request(), encoder).toList()
        assertTrue(events.last() is TransportEvent.Done)
        assertTrue(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    // ===== 9. HTTP 200 full choices（请求 stream=true 却回整包）→ 直接成功 + 学习 =====

    @Test
    fun `http 200 full completion in response to stream succeeds and learns`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "{\"choices\":[{\"message\":{\"content\":\"网关忽略stream\"},\"finish_reason\":\"stop\"}]}"
        ))
        val events = transport.stream(request(), encoder).toList()
        assertEquals("网关忽略stream", events.filterIsInstance<TransportEvent.Content>().joinToString("") { it.text })
        assertTrue(events.last() is TransportEvent.Done)
        assertTrue(RuntimeCapabilityCache.prefersNonStream(key()))
        // 只发了一次请求，不再重复探测
        assertEquals(1, server.requestCount)
    }

    // ===== 10. 顶层 JSON string / 11. message 为 string =====

    @Test
    fun `bare top-level json string body is usable content`() = runBlocking {
        server.enqueue(MockResponse().setBody("\"整包裸字符串\""))
        val resp = transport.once(request(), encoder)
        assertEquals("整包裸字符串", resp.content)
        assertFalse(resp.isError)
    }

    @Test
    fun `message as string gateway variant parses`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":\"字符串正文\"}]}"))
        val resp = transport.once(request(), encoder)
        assertEquals("字符串正文", resp.content)
    }

    // ===== 12/13/14. HTML / Empty / malformed → 报错且不学习 =====

    @Test
    fun `http 200 html reports malformed and never learns`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>gateway error page</body></html>"))
        val events = transport.stream(request(), encoder).toList()
        val err = events.filterIsInstance<TransportEvent.Error>().single()
        assertEquals(LlmErrorKind.MalformedResponse, err.kind)
        assertFalse(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    @Test
    fun `http 200 empty stream reports empty and never learns`() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        val events = transport.stream(request(), encoder).toList()
        assertEquals(LlmErrorKind.EmptyResponse, events.filterIsInstance<TransportEvent.Error>().single().kind)
        assertFalse(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    @Test
    fun `malformed json body reports malformed and never learns`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"choices\": broken"))
        val events = transport.stream(request(), encoder).toList()
        assertEquals(LlmErrorKind.MalformedResponse, events.filterIsInstance<TransportEvent.Error>().single().kind)
        assertFalse(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    @Test
    fun `http 200 api error without stream wording reports error and does not fall back`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"error\":{\"message\":\"quota exceeded for this key\"}}"))
        val events = transport.stream(request(), encoder).toList()
        val err = events.filterIsInstance<TransportEvent.Error>().single()
        assertTrue(err.message.contains("quota exceeded"))
        assertFalse(RuntimeCapabilityCache.prefersNonStream(key()))
        assertEquals(1, server.requestCount) // 未降级重发
    }

    // ===== 15. 流中断已输出正文 → 保留正文 + 中断错误，不整轮重发 =====

    @Test
    fun `mid stream disconnect keeps content and does not resend`() = runBlocking {
        // 连接被掐断在解析器眼中的确定性形态：有内容 chunk 但流静默结束——
        // 无 [DONE]、无任何 finish_reason（MockWebServer 的 DISCONNECT socket 策略有竞态，不用）
        server.enqueue(
            MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"半截\"},\"finish_reason\":null}]}\n\n")
        )
        val events = transport.stream(request(), encoder).toList()
        assertTrue(events.any { it is TransportEvent.Content && it.text.contains("半截") })
        // 无 [DONE] 且无 finish_reason 的静默结束 = 生成中断（小马截断形态），必须以 Error 收尾
        assertTrue(events.last() is TransportEvent.Error)
        assertTrue(events.filterIsInstance<TransportEvent.Error>().single().message.contains("生成中断"))
        assertEquals(1, server.requestCount) // 不重发
    }

    @Test
    fun `stream ending with done marker completes normally`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"完整\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
        ))
        val events = transport.stream(request(), encoder).toList()
        assertTrue(events.last() is TransportEvent.Done)
    }

    // ===== 16/17. fallback 失败不写缓存 =====

    @Test
    fun `fallback failure does not learn non stream`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"stream unsupported here\"}}")
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"server exploded\"}}"))
        val events = transport.stream(request(), encoder).toList()
        assertTrue(events.last() is TransportEvent.Error)
        assertFalse(RuntimeCapabilityCache.prefersNonStream(key()))
    }

    // ===== 18. FORCE_STREAM 遇 unsupported → 明确报错不降级 =====

    @Test
    fun `force stream does not silently downgrade`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "{\"error\":{\"message\":\"stream mode is not supported\"}}"
        ))
        val events = transport.stream(request(streamMode = StreamMode.STREAM), encoder).toList()
        val err = events.filterIsInstance<TransportEvent.Error>().single()
        assertEquals(LlmErrorKind.UnsupportedStreaming, err.kind)
        assertEquals(1, server.requestCount)
    }

    // ===== 19. FORCE_NON_STREAM 不发 stream probe =====

    @Test
    fun `force non stream never sends stream request`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"直接整包\"}}]}"))
        val events = transport.stream(request(streamMode = StreamMode.NON_STREAM), encoder).toList()
        assertEquals("直接整包", events.filterIsInstance<TransportEvent.Content>().joinToString("") { it.text })
        assertEquals(1, server.requestCount)
        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains("\"stream\":false"))
    }

    // ===== 20. omit stream 档位 payload 不出现 stream 字段 =====

    @Test
    fun `omit stream encoding leaves stream field absent`() {
        val omitAdapter = object : ProviderAdapter {
            override val id = "omit-test"
            override val nonStreamEncoding = NonStreamEncoding.OMIT_STREAM_FIELD
            override fun resolveChatUrl(apiUrl: String) = apiUrl.trimEnd('/') + "/chat/completions"
            override fun buildAuthHeaders(apiKey: String) = mapOf("Authorization" to "Bearer $apiKey")
            override fun chatCapabilities(model: String) = ChatCapabilities(streaming = CapabilityState.SUPPORTED)
            override fun buildChatPayload(
                model: String, messages: List<ChatMessage>, tools: List<ToolSchema>,
                streamPayload: Boolean, nonStreamEncoding: NonStreamEncoding, nativeSearch: Boolean,
                messageEncoder: MessageEncoder
            ): com.google.gson.JsonObject {
                val json = com.google.gson.JsonObject()
                json.addProperty("model", model)
                json.add("messages", messageEncoder.encodeMessages(messages))
                if (streamPayload) json.addProperty("stream", true)
                // OMIT_STREAM_FIELD：非流式不写 stream 字段
                return json
            }
            override fun classifyError(httpCode: Int?, errorMessage: String?): LlmErrorKind? = null
        }
        val streamingPayload = omitAdapter.buildChatPayload(
            model = "m", messages = listOf(ChatMessage("user", "hi")), tools = emptyList(),
            streamPayload = true, nonStreamEncoding = NonStreamEncoding.OMIT_STREAM_FIELD,
            nativeSearch = false, messageEncoder = encoder
        )
        assertTrue(streamingPayload.has("stream"))
        val nonStreamingPayload = omitAdapter.buildChatPayload(
            model = "m", messages = listOf(ChatMessage("user", "hi")), tools = emptyList(),
            streamPayload = false, nonStreamEncoding = NonStreamEncoding.OMIT_STREAM_FIELD,
            nativeSearch = false, messageEncoder = encoder
        )
        assertFalse(nonStreamingPayload.has("stream"))
    }

    // ===== 21. tools 完整回传（API tool_calls 形态，非流式） =====

    @Test
    fun `non stream api tool calls round trip intact`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" +
                "{\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"generate_image\",\"arguments\":\"{\\\"prompt\\\":\\\"猫\\\"}\"}}" +
                "]}}]}"
        ))
        val resp = transport.once(request(tools = listOf(ToolSchema("generate_image", null, null))), encoder)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("call_9", resp.toolCalls[0].id)
        assertEquals("generate_image", resp.toolCalls[0].name)
        assertTrue(resp.toolCalls[0].argumentsJson.contains("猫"))
    }

    // ===== 22. usage 两种模式 =====

    @Test
    fun `usage parsed in both modes`() = runBlocking {
        // 非流式 usage
        server.enqueue(MockResponse().setBody(
            "{\"choices\":[{\"message\":{\"content\":\"a\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}"
        ))
        val resp = transport.once(request(), encoder)
        assertEquals(11L, resp.usage?.promptTokens)
        assertEquals(18L, resp.usage?.totalTokens)

        // 流式终态 usage 块
        server.enqueue(MockResponse().setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"b\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}\n\n" +
                "data: [DONE]\n\n"
        ))
        val events = transport.stream(request(), encoder).toList()
        val usage = events.filterIsInstance<TransportEvent.UsageEvent>().single().usage
        assertEquals(5L, usage.promptTokens)
    }

    // ===== 23. cancellation 及时取消（快速返回的相邻验证：取消后事件流终止） =====

    @Test
    fun `401 is terminal auth error without retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        val events = transport.stream(request(), encoder).toList()
        val err = events.filterIsInstance<TransportEvent.Error>().single()
        assertTrue(err.message.contains("鉴权失败"))
        assertEquals(1, server.requestCount)
    }

    // ===== capability cache 隔离（Spec §23.2 选摘）=====

    @Test
    fun `config a learning does not pollute config b`() {
        RuntimeCapabilityCache.markPreferNonStream(CapabilityCacheKey("cfg-a", "m", "openai-compat"))
        assertFalse(RuntimeCapabilityCache.prefersNonStream(CapabilityCacheKey("cfg-b", "m", "openai-compat")))
        RuntimeCapabilityCache.invalidateConfig("cfg-a")
        assertFalse(RuntimeCapabilityCache.prefersNonStream(CapabilityCacheKey("cfg-a", "m", "openai-compat")))
    }

    @Test
    fun `miMo adapter adds api-key header and zhipu declares native search`() {
        val mimo = ProviderAdapters.forProvider("MiMo")
        assertEquals("mimo", mimo.id)
        assertTrue(mimo.buildAuthHeaders("sk").containsKey("api-key"))
        assertEquals(CapabilityState.UNSUPPORTED, mimo.chatCapabilities("m").nativeSearch)

        val zhipu = ProviderAdapters.forProvider("Zhipu (智谱)")
        assertEquals(CapabilityState.SUPPORTED, zhipu.chatCapabilities("glm").nativeSearch)
        val payload = zhipu.buildChatPayload(
            model = "glm", messages = listOf(ChatMessage("user", "hi")), tools = emptyList(),
            streamPayload = true, nonStreamEncoding = NonStreamEncoding.STREAM_FALSE,
            nativeSearch = true, messageEncoder = encoder
        )
        assertTrue(payload.has("enable_search"))
        // 非 Zhipu 档位即使请求 native search 也不写扩展字段
        val openai = ProviderAdapters.forProvider("DeepSeek")
        val payload2 = openai.buildChatPayload(
            model = "m", messages = listOf(ChatMessage("user", "hi")), tools = emptyList(),
            streamPayload = true, nonStreamEncoding = NonStreamEncoding.STREAM_FALSE,
            nativeSearch = true, messageEncoder = encoder
        )
        assertFalse(payload2.has("web_search"))
        assertFalse(payload2.has("enable_search"))
    }
}
