package com.loyea.mcp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class McpClient(
    val config: McpServerConfig,
    private val okhttpClient: OkHttpClient
) {
    private val gson = Gson()
    private val _status = MutableStateFlow(McpServerStatus.DISCONNECTED)
    val status: StateFlow<McpServerStatus> = _status

    private val connectMutex = Mutex()
    private var eventSource: EventSource? = null
    @Volatile
    private var messageEndpoint: String? = null
    
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
    private val _discoveredTools = MutableStateFlow<List<McpTool>>(emptyList())
    val discoveredTools: StateFlow<List<McpTool>> = _discoveredTools

    @Volatile
    private var endpointDeferred: CompletableDeferred<String>? = null

    companion object {
        private const val TAG = "McpClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun connect(): Boolean = connectMutex.withLock {
        val shouldProceed = synchronized(this) {
            if (_status.value != McpServerStatus.DISCONNECTED) {
                return@synchronized false
            }
            _status.value = McpServerStatus.CONNECTING
            true
        }
        if (!shouldProceed) {
            return _status.value == McpServerStatus.CONNECTED
        }

        Log.d(TAG, "Connecting to ${config.name} at ${config.sseUrl}")
        
        try {
            synchronized(this) {
                // 显式清理旧 EventSource 及 Deferred，防 Socket 重复叠加与泄漏
                eventSource?.cancel()
                eventSource = null
                endpointDeferred?.completeExceptionally(IOException("Reconnecting"))
                endpointDeferred = CompletableDeferred()
            }

            val request = Request.Builder()
                .url(config.sseUrl)
                .header("Accept", "text/event-stream")
                .build()

            val factory = EventSources.createFactory(okhttpClient)
            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.d(TAG, "SSE Open for ${config.name}")
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.d(TAG, "SSE Event: type=$type, data=$data")
                    when (type) {
                        "endpoint" -> {
                            endpointDeferred?.complete(data)
                        }
                        "message" -> {
                            handleMessage(data)
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.d(TAG, "SSE Closed for ${config.name}")
                    handleDisconnect()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    Log.e(TAG, "SSE Failure for ${config.name}", t)
                    handleDisconnect()
                }
            }

            val newEventSource = factory.newEventSource(request, listener)
            synchronized(this) {
                if (_status.value == McpServerStatus.CONNECTING) {
                    eventSource = newEventSource
                } else {
                    newEventSource.cancel()
                    throw IOException("Connection was aborted before event source started")
                }
            }

            // 1. Wait for endpoint event (with 10s timeout)
            val endpoint = withTimeout(10000) {
                endpointDeferred!!.await()
            }
            
            // Resolve relative endpoint & SSRF Prevention
            val parsedSseUrl = config.sseUrl.toHttpUrlOrNull() ?: throw IOException("Invalid SSE URL: ${config.sseUrl}")
            val trimmedEndpoint = endpoint.trim()
            val finalHttpUrl = trimmedEndpoint.toHttpUrlOrNull() ?: parsedSseUrl.resolve(trimmedEndpoint) ?: throw IOException("Failed to parse or resolve endpoint: $endpoint")
            if (finalHttpUrl.host != parsedSseUrl.host || finalHttpUrl.port != parsedSseUrl.port) {
                throw SecurityException("SSRF Detected: Redirect host/port (${finalHttpUrl.host}:${finalHttpUrl.port}) does not match sseUrl host/port (${parsedSseUrl.host}:${parsedSseUrl.port})")
            }
            val resolvedEndpoint = finalHttpUrl.toString()

            synchronized(this) {
                if (_status.value == McpServerStatus.CONNECTING) {
                    messageEndpoint = resolvedEndpoint
                } else {
                    throw IOException("Connection was aborted before resolving endpoint")
                }
            }
            
            Log.d(TAG, "Message endpoint resolved to $messageEndpoint")

            // 2. Perform initialize handshake
            val initResponse = sendRequest("initialize", mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to mapOf<String, Any>(),
                "clientInfo" to mapOf(
                    "name" to "loyea-client",
                    "version" to "0.1"
                )
            ))

            if (initResponse.error != null) {
                throw IOException("Initialize failed: ${initResponse.error.message}")
            }

            // 3. Send initialized notification (no id, expect no response)
            sendNotification("notifications/initialized", null)

            // 4. Fetch tools list to populate cache
            fetchTools()

            val success = synchronized(this) {
                if (_status.value == McpServerStatus.CONNECTING) {
                    _status.value = McpServerStatus.CONNECTED
                    true
                } else {
                    false
                }
            }
            if (!success) {
                throw IOException("Connection was disconnected during initialization")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Connection failed for ${config.name}", t)
            handleDisconnect()
            if (t is CancellationException) throw t
            false
        }
    }

    fun disconnect() {
        handleDisconnect()
    }

    private fun handleDisconnect() = synchronized(this) {
        eventSource?.cancel()
        eventSource = null
        messageEndpoint = null
        
        endpointDeferred?.completeExceptionally(IOException("Disconnected"))
        
        val requestsToCancel = pendingRequests.values.toList()
        pendingRequests.clear()
        for (deferred in requestsToCancel) {
            deferred.completeExceptionally(IOException("Disconnected"))
        }

        _status.value = McpServerStatus.DISCONNECTED
    }

    private fun handleMessage(data: String) {
        if (data.length > 10 * 1024 * 1024) {
            Log.e(TAG, "Payload length exceeds limit: ${data.length}")
            return
        }
        try {
            val response = gson.fromJson(data, JsonRpcResponse::class.java)
            val idStr = response?.idAsString
            if (idStr != null) {
                val deferred = pendingRequests.remove(idStr)
                deferred?.complete(response)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse message: $data", t)
        }
    }

    suspend fun sendRequest(method: String, params: Any?): JsonRpcResponse = withContext(Dispatchers.IO) {
        val endpoint = messageEndpoint ?: throw IOException("Not connected")
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[requestId] = deferred

        val jsonRequest = gson.toJson(JsonRpcRequest(id = requestId, method = method, params = params))
        
        val body = jsonRequest.toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        val call = okhttpClient.newCall(httpRequest)
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (!response.isSuccessful) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(IOException("HTTP POST failed with code: ${response.code}"))
                                }
                            } else {
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                        }
                    }
                })
            }
            // Wait for response via SSE (with 30 second timeout)
            withTimeout(30000) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    private suspend fun sendNotification(method: String, params: Any?) = withContext(Dispatchers.IO) {
        val endpoint = messageEndpoint ?: throw IOException("Not connected")
        val jsonRequest = gson.toJson(JsonRpcRequest(id = null, method = method, params = params))
        val body = jsonRequest.toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()
        val call = okhttpClient.newCall(httpRequest)
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IOException("HTTP POST failed with code: ${response.code}"))
                            }
                        } else {
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                    }
                }
            })
        }
    }

    suspend fun fetchTools(): List<McpTool> {
        val response = sendRequest("tools/list", null)
        if (response.error != null) {
            throw IOException("Failed to fetch tools: ${response.error.message}")
        }
        val resultObj = response.result?.asJsonObject ?: return emptyList()
        val toolsArray = resultObj.getAsJsonArray("tools") ?: return emptyList()
        
        val toolsType = object : TypeToken<List<McpTool>>() {}.type
        val toolsList: List<McpTool> = gson.fromJson(toolsArray, toolsType) ?: emptyList()
        
        _discoveredTools.value = toolsList
        return toolsList
    }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>?): JsonRpcResponse {
        return sendRequest("tools/call", mapOf(
            "name" to toolName,
            "arguments" to (arguments ?: emptyMap<String, Any>())
        ))
    }
}
