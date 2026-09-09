package com.loyea.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 统一 Chat Completion Transport（Spec §10）。
 *
 * 全应用唯一的聊天补全 HTTP 实现：前台聊天、Memory、Greeting、Worker、Caption
 * 的业务包装全部收敛到这里。Transport 只负责 HTTP、retry、timeout、流式状态机、
 * 错误分类与运行时能力缓存；不选择配置、不理解 Soul/WorldBook、不碰 SharedPreferences。
 *
 * 流式状态机（Spec §8.3/8.4/8.5）：
 * - sseTransportObserved / semanticOutputObserved 双状态取代旧的单布尔 streamStarted；
 * - 仅 data: 前缀、空 delta、usage、error 不算语义输出；
 * - AUTO 只在「明确 UnsupportedStreaming」或「200 完整整包」时降级/学习；
 * - HTML/Empty/malformed 一律报错，不降级、不写缓存（修正旧实现提前写缓存的问题）；
 * - 已有语义输出后中断：保留内容、结构化「生成中断」错误，绝不整轮重发。
 */
class UnifiedChatTransport(
    baseClient: OkHttpClient,
    private val gson: Gson = Gson(),
    private val parser: ResponseParser = ResponseParser()
) {

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val NON_SSE_CAPTURE_LIMIT = 2_000_000
        /** 非流式 read timeout：reasoning 模型整包返回不再被 60s 共享超时误杀（Spec §11.1）。 */
        private const val NON_STREAM_READ_TIMEOUT_SECONDS = 180L
        /** 流式 read timeout：作用于 chunk 间隔。 */
        private const val STREAM_READ_TIMEOUT_SECONDS = 60L
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val streamClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val nonStreamClient: OkHttpClient = baseClient.newBuilder()
        .readTimeout(NON_STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // ---------- 公开入口 ----------

    /**
     * 流式执行入口。按 streamMode 分派：
     * AUTO（缓存命中则直走非流式）→ 流式探测 → 明确不支持时降级非流式；
     * STREAM 强制流式不降级；NON_STREAM 直走整包。
     */
    fun stream(request: ChatExecutionRequest, encoder: MessageEncoder): Flow<TransportEvent> = flow {
        if (request.apiKey.isBlank()) {
            emit(TransportEvent.Error(LlmErrorKind.ConfigurationMissing, "[错误] API Key 未配置，请在设置中配置您的 Key 后重试。"))
            return@flow
        }
        val adapter = ProviderAdapters.forProvider(request.providerPreset)
        val cacheKey = CapabilityCacheKey(request.configId, request.model, adapter.id)

        when (request.streamMode) {
            StreamMode.NON_STREAM -> {
                emitNonStreamRound(this, request, adapter, encoder, notifyDowngrade = false)
            }
            StreamMode.STREAM -> {
                val outcome = runStreamAttempt(this, request, adapter, encoder, allowFallback = false)
                if (outcome is StreamOutcome.FallbackToNonStream) {
                    // 强制流式遇到不支持：明确报错，不静默改变用户选择（Spec §8.1）
                    emit(TransportEvent.Error(
                        LlmErrorKind.UnsupportedStreaming,
                        "[错误] 当前渠道不支持流式传输（FORCE_STREAM 模式不自动降级）：${outcome.reason.take(200)}"))
                }
            }
            StreamMode.AUTO -> {
                if (RuntimeCapabilityCache.prefersNonStream(cacheKey)) {
                    // 会话内已知非流式渠道：跳过注定失败的流式往返直达整包（快速路径）
                    emitNonStreamRound(this, request, adapter, encoder, notifyDowngrade = false)
                    return@flow
                }
                var attempt = 0
                while (true) {
                    attempt++
                    val outcome = runStreamAttempt(this, request, adapter, encoder, allowFallback = true)
                    when (outcome) {
                        is StreamOutcome.Retryable -> {
                            if (attempt >= MAX_ATTEMPTS) return@flow
                            delay(1000L * attempt)
                            continue
                        }
                        is StreamOutcome.FallbackToNonStream -> {
                            emitNonStreamRound(this, request, adapter, encoder, notifyDowngrade = true, fallbackReason = outcome.reason)
                            return@flow
                        }
                        else -> return@flow // Completed / Terminal 已在 attempt 内发射完毕
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 非流式单轮（Memory / Worker / 降级快速路径共用）。 */
    suspend fun once(request: ChatExecutionRequest, encoder: MessageEncoder): ChatResponse {
        if (request.apiKey.isBlank()) {
            return ChatResponse(
                content = "[错误] API Key 未配置，请在设置中配置您的 Key 后重试。",
                isError = true,
                errorKind = LlmErrorKind.ConfigurationMissing
            )
        }
        val adapter = ProviderAdapters.forProvider(request.providerPreset)
        var attempt = 0
        while (true) {
            attempt++
            val outcome = runNonStreamAttempt(request, adapter, encoder)
            if (outcome is NonStreamOutcome.Retryable && attempt < MAX_ATTEMPTS) {
                delay(1000L * attempt)
                continue
            }
            return when (outcome) {
                is NonStreamOutcome.Done -> outcome.response
                is NonStreamOutcome.Retryable -> ChatResponse(
                    content = "[错误] 网络请求故障: ${outcome.message}",
                    isError = true,
                    errorKind = outcome.kind
                )
            }
        }
    }

    // ---------- 流式单轮 ----------

    private sealed class StreamOutcome {
        /** 正常完成（含 200 整包成功），事件已发射。 */
        object Completed : StreamOutcome()
        /** 瞬时故障，可重试（仅发生在零语义输出阶段）。 */
        data class Retryable(val kind: LlmErrorKind, val message: String) : StreamOutcome()
        /** 明确 UnsupportedStreaming，允许 AUTO 降级。 */
        data class FallbackToNonStream(val reason: String) : StreamOutcome()
        /** 终态错误，事件已发射。 */
        data class Terminal(val kind: LlmErrorKind) : StreamOutcome()
    }

    private suspend fun runStreamAttempt(
        collector: FlowCollector<TransportEvent>,
        request: ChatExecutionRequest,
        adapter: ProviderAdapter,
        encoder: MessageEncoder,
        allowFallback: Boolean
    ): StreamOutcome {
        val payload = adapter.buildChatPayload(
            model = request.model,
            messages = request.messages,
            tools = request.tools,
            streamPayload = true,
            nonStreamEncoding = adapter.nonStreamEncoding,
            nativeSearch = request.nativeSearchRequested &&
                adapter.chatCapabilities(request.model).nativeSearch == CapabilityState.SUPPORTED,
            messageEncoder = encoder
        )
        logRequest(adapter, request, payload)
        val call = buildCall(adapter, request, payload, streamClient)

        var sseTransportObserved = false
        var semanticOutputObserved = false

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string().orEmpty()
                    val errMessage = extractErrorMessage(rawBody).ifBlank { rawBody.take(300) }
                    var kind = adapter.classifyError(response.code, errMessage)
                        ?: mergeKind(ErrorClassifier.fromHttpCode(response.code), errMessage)
                    if (kind == LlmErrorKind.Unknown || kind == LlmErrorKind.InvalidRequest || kind == LlmErrorKind.Server) {
                        ErrorClassifier.classifyStructuredError(errMessage).takeIf { it != LlmErrorKind.Unknown }?.let { kind = it }
                    }
                    // 明确不支持流式 → AUTO 降级（含 400/422/501 等此前漏掉的非 200 分支，Spec §8.5）
                    if (kind == LlmErrorKind.UnsupportedStreaming && allowFallback) {
                        return StreamOutcome.FallbackToNonStream(errMessage)
                    }
                    if (ErrorClassifier.isRetryableHttp(response.code)) {
                        return StreamOutcome.Retryable(kind, errMessage)
                    }
                    collector.emitError(kind, response.code, errMessage)
                    return StreamOutcome.Terminal(kind)
                }

                val body = response.body
                    ?: run {
                        collector.emit(TransportEvent.Error(LlmErrorKind.EmptyResponse, "[错误] 大模型接口返回了空响应"))
                        return StreamOutcome.Terminal(LlmErrorKind.EmptyResponse)
                    }

                // —— SSE / 非 SSE 增量解析状态 ——
                val fullContent = StringBuilder()
                var emittedThoughtsLength = 0
                var emittedContentLength = 0
                val nonSseRaw = StringBuilder()
                var truncatedBy: String? = null
                var streamErrorMessage: String? = null
                class ToolCallBuffer(var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())
                val toolCallBuffers = mutableMapOf<Int, ToolCallBuffer>()
                var usageSeen: Usage? = null
                var doneMarkerSeen = false
                var finishReasonSeen: String? = null

                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmedLine = line!!.trim()
                    val data = extractSseDataPayload(trimmedLine)
                    if (data == null) {
                        if (trimmedLine.isNotEmpty() && nonSseRaw.length < NON_SSE_CAPTURE_LIMIT) {
                            nonSseRaw.append(line).append('\n')
                        }
                        continue
                    }
                    sseTransportObserved = true
                    if (data == "[DONE]") {
                        doneMarkerSeen = true
                        break
                    }
                    try {
                        val chunkJson = gson.fromJson(data, JsonObject::class.java) ?: continue
                        // SSE error 事件：只有在产生语义输出前才可能据此降级（Spec §8.5）
                        if (chunkJson.has("error") && chunkJson.get("error").isJsonObject && !chunkJson.has("choices")) {
                            val errMsg = chunkJson.getAsJsonObject("error")?.get("message")?.takeIf { !it.isJsonNull }?.asString
                                ?: data.take(300)
                            streamErrorMessage = errMsg
                            break
                        }
                        // 终态 usage 块（choices 为空数组）
                        chunkJson.get("usage")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject?.let { usage ->
                            usageSeen = Usage(
                                promptTokens = usage.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                                completionTokens = usage.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                                totalTokens = usage.get("total_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                                promptCacheHitTokens = usage.get("prompt_cache_hit_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                                promptCacheMissTokens = usage.get("prompt_cache_miss_tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            )
                        }
                        val choices = chunkJson.getAsJsonArray("choices") ?: continue
                        if (choices.size() == 0) continue
                        val choiceObj = choices.get(0).asJsonObject
                        val finishReason = choiceObj.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
                        if (finishReason != null) finishReasonSeen = finishReason
                        if (finishReason == "length" || finishReason == "content_filter") {
                            truncatedBy = finishReason
                        }
                        val delta = choiceObj.getAsJsonObject("delta") ?: continue

                        val reasoningContent = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                        if (!reasoningContent.isNullOrEmpty()) {
                            semanticOutputObserved = true
                            collector.emit(TransportEvent.Thoughts(reasoningContent))
                        }

                        val content = delta.get("content")?.takeIf { !it.isJsonNull }?.asString
                        if (!content.isNullOrEmpty()) {
                            fullContent.append(content)
                            val parsedState = StreamContentParser.parseIncrementalStreamState(fullContent.toString())
                            if (parsedState.thoughts.length > emittedThoughtsLength) {
                                collector.emit(TransportEvent.Thoughts(parsedState.thoughts.substring(emittedThoughtsLength)))
                                emittedThoughtsLength = parsedState.thoughts.length
                            }
                            if (parsedState.visibleContent.length > emittedContentLength) {
                                val newContent = parsedState.visibleContent.substring(emittedContentLength)
                                if (newContent.isNotEmpty()) {
                                    semanticOutputObserved = true
                                    collector.emit(TransportEvent.Content(newContent))
                                }
                                emittedContentLength = parsedState.visibleContent.length
                            }
                            if (parsedState.completedXmlCalls.isNotEmpty()) break
                        }

                        val toolCallsJson = delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                        if (toolCallsJson != null && toolCallsJson.size() > 0) {
                            semanticOutputObserved = true
                            toolCallsJson.forEach { element ->
                                val tcObj = element.asJsonObject
                                val index = tcObj.get("index")?.asInt ?: 0
                                val tcId = tcObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                                val functionObj = tcObj.getAsJsonObject("function")
                                val funcName = functionObj?.get("name")?.takeIf { !it.isJsonNull }?.asString
                                val funcArgs = functionObj?.get("arguments")?.takeIf { !it.isJsonNull }?.asString
                                val buffer = toolCallBuffers.getOrPut(index) { ToolCallBuffer() }
                                if (tcId != null) buffer.id = tcId
                                if (funcName != null) buffer.name = funcName
                                if (funcArgs != null) buffer.arguments.append(funcArgs)
                            }
                        }
                    } catch (e: Exception) {
                        // 心跳/注释报文解析失败忽略；JSON 形态解析失败记录一次
                        if (line?.startsWith("{") == true) {
                            android.util.Log.w("LoyeaTransport", "SSE chunk parse failed: ${e.message}")
                        }
                    }
                }

                // —— SSE error 事件处理（Spec §8.5：有效降级来源之一） ——
                if (streamErrorMessage != null) {
                    val kind = adapter.classifyError(null, streamErrorMessage)
                        ?: ErrorClassifier.classifyStructuredError(streamErrorMessage)
                    if (kind == LlmErrorKind.UnsupportedStreaming && allowFallback && !semanticOutputObserved) {
                        return StreamOutcome.FallbackToNonStream(streamErrorMessage)
                    }
                    if (semanticOutputObserved) {
                        // 已有正文后收到 error：保留内容 + 结构化中断错误，不整轮重发
                        collector.emit(TransportEvent.Error(LlmErrorKind.Unknown, "[错误] 流式生成中断：${streamErrorMessage!!.take(200)}"))
                        return StreamOutcome.Terminal(kind)
                    }
                    collector.emitError(kind, null, streamErrorMessage)
                    return StreamOutcome.Terminal(kind)
                }

                // —— 整条流无任何 data 事件：HTTP 200 非 SSE 体判定 ——
                if (!sseTransportObserved) {
                    val rawBody = nonSseRaw.toString().trim()
                    return when (val outcome = parser.interpretNonSseBody(rawBody)) {
                        is NonSseBodyOutcome.Completion -> {
                            val resp = outcome.response
                            if (resp.isError) {
                                collector.emit(TransportEvent.Error(resp.errorKind ?: LlmErrorKind.Unknown, resp.content))
                                return StreamOutcome.Terminal(resp.errorKind ?: LlmErrorKind.Unknown)
                            }
                            // §8.6：标准整包（choices 形态）视为本轮成功 + 学习 prefer non-stream；
                            // 顶层裸字符串同样成功但不学习（非标准形态不足以为渠道定调）
                            if (rawBody.startsWith("{")) {
                                RuntimeCapabilityCache.markPreferNonStream(
                                    CapabilityCacheKey(request.configId, request.model, adapter.id))
                            }
                            collector.emitResponseEvents(resp)
                            collector.emit(TransportEvent.Done)
                            StreamOutcome.Completed
                        }
                        is NonSseBodyOutcome.ApiError -> {
                            val kind = adapter.classifyError(200, outcome.message)
                                ?: ErrorClassifier.classifyStructuredError(outcome.message)
                            if (kind == LlmErrorKind.UnsupportedStreaming && allowFallback) {
                                return StreamOutcome.FallbackToNonStream(outcome.message)
                            }
                            collector.emit(TransportEvent.Error(kind, "[错误] 服务商返回错误 (HTTP 200)：${outcome.message.take(300)}"))
                            StreamOutcome.Terminal(kind)
                        }
                        is NonSseBodyOutcome.Empty -> {
                            // HTML/Empty/Malformed → 报错，不降级、不学习（Spec §8.3）
                            collector.emit(TransportEvent.Error(LlmErrorKind.EmptyResponse,
                                "[错误] 服务商返回了空回复 (HTTP 200)：连接正常但未收到任何内容，请检查渠道是否故障或稍后重试"))
                            StreamOutcome.Terminal(LlmErrorKind.EmptyResponse)
                        }
                        is NonSseBodyOutcome.Unrecognized -> {
                            android.util.Log.w("LoyeaTransport",
                                "Non-SSE 200 body sample: ${outcome.sample.take(300)}")
                            collector.emit(TransportEvent.Error(LlmErrorKind.MalformedResponse,
                                "[错误] 响应不是 SSE 也不是有效 JSON (HTTP 200)：响应体开头：${outcome.sample.take(200)}"))
                            StreamOutcome.Terminal(LlmErrorKind.MalformedResponse)
                        }
                    }
                }

                // —— 正常流结束：补发滞留段 + 终态可见性 ——
                val finalState = StreamContentParser.parseIncrementalStreamState(fullContent.toString(), isDone = true)
                if (finalState.thoughts.length > emittedThoughtsLength) {
                    collector.emit(TransportEvent.Thoughts(finalState.thoughts.substring(emittedThoughtsLength)))
                    emittedThoughtsLength = finalState.thoughts.length
                }
                if (finalState.visibleContent.length > emittedContentLength) {
                    val finalContent = finalState.visibleContent.substring(emittedContentLength)
                    if (finalContent.isNotEmpty()) {
                        semanticOutputObserved = true
                        collector.emit(TransportEvent.Content(finalContent))
                    }
                    emittedContentLength = finalState.visibleContent.length
                }

                val finalToolCalls = toolCallBuffers.entries.sortedBy { it.key }.mapNotNull { (_, buffer) ->
                    val id = buffer.id ?: "call_${System.currentTimeMillis()}"
                    val name = buffer.name ?: return@mapNotNull null
                    ToolCall(id = id, name = name, argumentsJson = buffer.arguments.toString())
                }
                val combinedCalls = finalToolCalls + finalState.completedXmlCalls
                if (combinedCalls.isNotEmpty()) {
                    collector.emit(TransportEvent.ToolCalls(combinedCalls))
                }

                // 终态可见性：零内容（含只发 usage 的空跑）与输出上限截断都以 Error 收尾，
                // 上层「半截内容保留」逻辑会把原因拼在已生成内容之后
                if (emittedContentLength == 0 && emittedThoughtsLength == 0 && combinedCalls.isEmpty()) {
                    collector.emit(TransportEvent.Error(LlmErrorKind.EmptyResponse,
                        "[错误] 服务商返回了空回复 (HTTP 200)：连接正常但未收到任何文本内容，请检查渠道是否故障或稍后重试"))
                    return StreamOutcome.Terminal(LlmErrorKind.EmptyResponse)
                }
                if (truncatedBy != null) {
                    collector.emit(TransportEvent.Error(LlmErrorKind.Unknown,
                        "[错误] 回复因输出上限被截断 (finish_reason=$truncatedBy)：请在服务商侧调大 max_tokens 或更换输出上限更高的渠道"))
                    return StreamOutcome.Terminal(LlmErrorKind.Unknown)
                }
                // 流在无 [DONE] 且无任何 finish_reason 的情况下静默结束 = 连接被掐断（小马截断形态）：
                // 不得伪装成正常完成，以「生成中断」结构化错误收尾并保留已有内容（Spec §8.4）
                if (!doneMarkerSeen && finishReasonSeen == null) {
                    collector.emit(TransportEvent.Error(LlmErrorKind.Network,
                        "[错误] 生成中断：流式连接未正常结束（未收到终止标记），已生成的部分已保留，请重试或检查渠道稳定性"))
                    return StreamOutcome.Terminal(LlmErrorKind.Network)
                }

                usageSeen?.let { collector.emit(TransportEvent.UsageEvent(it)) }
                collector.emit(TransportEvent.Done)
                return StreamOutcome.Completed
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val kind = if (e is SocketTimeoutException) LlmErrorKind.Timeout else LlmErrorKind.Network
            // 已有语义输出后断流：保留内容、结构化中断错误、不整轮重发（Spec §8.4）
            if (semanticOutputObserved) {
                collector.emit(TransportEvent.Error(kind, "[错误] 生成中断：流式连接在输出过程中断开（${e.localizedMessage ?: e.message ?: "network"}）。已生成的部分已保留。"))
                return StreamOutcome.Terminal(kind)
            }
            return StreamOutcome.Retryable(kind, e.localizedMessage ?: e.message ?: "network error")
        }
    }

    // ---------- 非流式 ----------

    private sealed class NonStreamOutcome {
        data class Done(val response: ChatResponse) : NonStreamOutcome()
        data class Retryable(val kind: LlmErrorKind, val message: String) : NonStreamOutcome()
    }

    private suspend fun runNonStreamAttempt(
        request: ChatExecutionRequest,
        adapter: ProviderAdapter,
        encoder: MessageEncoder
    ): NonStreamOutcome {
        val payload = adapter.buildChatPayload(
            model = request.model,
            messages = request.messages,
            tools = request.tools,
            streamPayload = false,
            nonStreamEncoding = adapter.nonStreamEncoding,
            nativeSearch = request.nativeSearchRequested &&
                adapter.chatCapabilities(request.model).nativeSearch == CapabilityState.SUPPORTED,
            messageEncoder = encoder
        )
        logRequest(adapter, request, payload)
        val call = buildCall(adapter, request, payload, nonStreamClient)
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val rawBody = response.body?.string().orEmpty()
                    val errMessage = extractErrorMessage(rawBody).ifBlank { rawBody.take(300) }
                    var kind = adapter.classifyError(response.code, errMessage)
                        ?: mergeKind(ErrorClassifier.fromHttpCode(response.code), errMessage)
                    ErrorClassifier.classifyStructuredError(errMessage).takeIf { it != LlmErrorKind.Unknown }?.let { kind = it }
                    if (ErrorClassifier.isRetryableHttp(response.code)) {
                        return NonStreamOutcome.Retryable(kind, errMessage)
                    }
                    return NonStreamOutcome.Done(friendlyError(kind, response.code, errMessage))
                }
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return NonStreamOutcome.Done(
                        ChatResponse(
                            content = "[错误] 大模型接口返回了空响应",
                            isError = true,
                            errorKind = LlmErrorKind.EmptyResponse
                        )
                    )
                }
                NonStreamOutcome.Done(parser.parseChatCompletionResponse(responseBody))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val kind = if (e is SocketTimeoutException) LlmErrorKind.Timeout else LlmErrorKind.Network
            NonStreamOutcome.Retryable(kind, e.localizedMessage ?: e.message ?: "network error")
        }
    }

    /**
     * 非流式整包以流事件形态发射：AUTO 降级与缓存快速路径共用。
     * 仅在降级【成功】后学习 prefer non-stream（Spec §9.3：禁止 fallback 成功前写）。
     */
    private suspend fun emitNonStreamRound(
        collector: FlowCollector<TransportEvent>,
        request: ChatExecutionRequest,
        adapter: ProviderAdapter,
        encoder: MessageEncoder,
        notifyDowngrade: Boolean,
        fallbackReason: String? = null
    ) {
        val response = once(request, encoder)
        if (response.isError) {
            val detail = response.content.removePrefix("[错误] ")
            collector.emit(TransportEvent.Error(
                response.errorKind ?: LlmErrorKind.Unknown,
                if (fallbackReason != null) "[错误] 渠道不支持流式，且非流式请求也失败：$detail" else response.content
            ))
            return // 降级失败：绝不写能力缓存
        }
        if (response.content.isBlank() && response.thoughts.isNullOrBlank() && response.toolCalls.isEmpty()) {
            collector.emit(TransportEvent.Error(LlmErrorKind.EmptyResponse,
                "[错误] 服务商返回了空回复 (HTTP 200)：响应解析正常但未包含任何文本内容"))
            return
        }
        if (notifyDowngrade && fallbackReason != null) {
            RuntimeCapabilityCache.markPreferNonStream(
                CapabilityCacheKey(request.configId, request.model, adapter.id))
            collector.emit(TransportEvent.Notice(if (java.util.Locale.getDefault().language == "zh")
                "当前渠道不支持流式传输，已自动改用整包模式" else
                "This channel doesn't support streaming; switched to non-streaming mode"))
        }
        response.thoughts?.takeIf { it.isNotBlank() }?.let { collector.emit(TransportEvent.Thoughts(it)) }
        if (response.content.isNotBlank()) collector.emit(TransportEvent.Content(response.content))
        if (response.toolCalls.isNotEmpty()) collector.emit(TransportEvent.ToolCalls(response.toolCalls))
        response.usage?.let { collector.emit(TransportEvent.UsageEvent(it)) }
        collector.emit(TransportEvent.Done)
    }

    // ---------- 事件与工具 ----------

    private suspend fun FlowCollector<TransportEvent>.emitResponseEvents(response: ChatResponse) {
        response.thoughts?.takeIf { it.isNotBlank() }?.let { emit(TransportEvent.Thoughts(it)) }
        if (response.content.isNotBlank()) emit(TransportEvent.Content(response.content))
        if (response.toolCalls.isNotEmpty()) emit(TransportEvent.ToolCalls(response.toolCalls))
        response.usage?.let { emit(TransportEvent.UsageEvent(it)) }
    }

    private suspend fun FlowCollector<TransportEvent>.emitError(kind: LlmErrorKind, httpCode: Int?, detail: String) {
        emit(TransportEvent.Error(kind, friendlyText(kind, httpCode, detail)))
    }

    private fun friendlyError(kind: LlmErrorKind, httpCode: Int?, detail: String): ChatResponse =
        ChatResponse(content = friendlyText(kind, httpCode, detail), isError = true, errorKind = kind)

    private fun friendlyText(kind: LlmErrorKind, httpCode: Int?, detail: String): String {
        val safeDetail = detail.trim().take(300)
        return when (kind) {
            LlmErrorKind.Authentication ->
                "[错误] 鉴权失败 (HTTP $httpCode)：请检查 API Key 是否正确或账户额度是否有效。$safeDetail"
            LlmErrorKind.RateLimit ->
                "[错误] 请求过于频繁或额度已耗尽 (HTTP 429)：已自动重试，仍失败请稍后再试或检查账户额度。$safeDetail"
            LlmErrorKind.Quota ->
                "[错误] 账户额度不足 (HTTP $httpCode)：请检查账户余额或套餐。$safeDetail"
            LlmErrorKind.Server ->
                "[错误] 服务器繁忙 (HTTP $httpCode)：已自动重试，仍失败请稍后再试。$safeDetail"
            LlmErrorKind.InvalidRequest ->
                "[错误] 参数错误 (HTTP $httpCode)：$safeDetail"
            LlmErrorKind.UnsupportedTools ->
                "[错误] 当前渠道/模型不支持工具调用 (HTTP $httpCode)：$safeDetail"
            LlmErrorKind.UnsupportedSearch ->
                "[错误] 当前渠道/模型不支持联网搜索扩展 (HTTP $httpCode)：$safeDetail"
            LlmErrorKind.UnsupportedModality ->
                "[错误] 当前模型不支持该媒体类型 (HTTP $httpCode)：$safeDetail"
            LlmErrorKind.Timeout ->
                "[错误] 请求超时：服务长时间未响应，请稍后重试。$safeDetail"
            else ->
                "[错误] 服务器返回错误${httpCode?.let { " HTTP $it" } ?: ""}: $safeDetail"
        }
    }

    /** 通用 HTTP 码分类 + 结构化错误文案细分的合并。 */
    private fun mergeKind(base: LlmErrorKind, structuredMessage: String): LlmErrorKind =
        ErrorClassifier.classifyStructuredError(structuredMessage).takeIf { it != LlmErrorKind.Unknown } ?: base

    private fun extractErrorMessage(rawBody: String): String = try {
        val json = gson.fromJson(rawBody, JsonObject::class.java)
        json?.getAsJsonObject("error")?.get("message")?.takeIf { !it.isJsonNull }?.asString
            ?: rawBody
    } catch (e: Exception) {
        rawBody
    }

    private suspend fun buildCall(
        adapter: ProviderAdapter,
        request: ChatExecutionRequest,
        payload: JsonObject,
        client: OkHttpClient
    ): okhttp3.Call {
        val body = gson.toJson(payload).toRequestBody(jsonMediaType)
        val builder = Request.Builder()
            .url(adapter.resolveChatUrl(request.apiUrl))
            .addHeader("Content-Type", "application/json")
        adapter.buildAuthHeaders(request.apiKey).forEach { (k, v) -> builder.addHeader(k, v) }
        val call = client.newCall(builder.post(body).build())
        // 协程取消时及时断开底层连接（改善停止生成/切会话的取消延迟）
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        return call
    }

    private fun logRequest(adapter: ProviderAdapter, request: ChatExecutionRequest, payload: JsonObject) {
        // 诊断白名单（Spec §20）：host/path、模型、消息数、媒体数、工具数、payload 尺寸；禁止正文/Key
        val url = adapter.resolveChatUrl(request.apiUrl)
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "unknown-host"
        val mediaCount = request.messages.count { !it.imageUrl.isNullOrBlank() || !it.audioUrl.isNullOrBlank() }
        android.util.Log.d("LoyeaTransport",
            "POST https://$host/** model=${request.model} adapter=${adapter.id} " +
                "msgs=${request.messages.size} mediaMsgs=$mediaCount tools=${request.tools.size} " +
                "payloadBytes=${payload.toString().length}")
    }
}
