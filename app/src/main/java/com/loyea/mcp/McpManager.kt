package com.loyea.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.loyea.perception.PerceptionMcpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class McpManager(private val context: Context) {
    companion object {
        private const val TAG = "McpManager"
        private const val LOCAL_SERVER_NAME = "BuiltinPerception"
    }

    private val storage = McpConfigStorage(context)
    private val perceptionServer = PerceptionMcpServer(context)
    private val okhttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE connections need no read timeout
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val activeClients = ConcurrentHashMap<String, McpClient>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val statusJobs = ConcurrentHashMap<String, Job>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _serverStates = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val serverStates: StateFlow<Map<String, McpServerStatus>> = _serverStates

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isNetworkAvailableFlow = MutableStateFlow(checkInitialNetwork())
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailableFlow

    private var isNetworkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network connected")
            _isNetworkAvailableFlow.value = true
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network disconnected")
            _isNetworkAvailableFlow.value = false
        }
    }

    private fun checkInitialNetwork(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun start() {
        Log.d(TAG, "Starting McpManager")
        if (!isNetworkCallbackRegistered) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                connectivityManager.registerNetworkCallback(request, networkCallback)
                isNetworkCallbackRegistered = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val configs = storage.loadConfigs()
        updateConfigs(configs)
    }

    fun stop() {
        Log.d(TAG, "Stopping McpManager")
        for (job in reconnectJobs.values) {
            job.cancel()
        }
        reconnectJobs.clear()

        for (job in statusJobs.values) {
            job.cancel()
        }
        statusJobs.clear()

        for (client in activeClients.values) {
            client.disconnect()
        }
        activeClients.clear()

        if (isNetworkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isNetworkCallbackRegistered = false
        }

        updateServerStates()
    }

    fun updateConfigs(newConfigs: List<McpServerConfig>) {
        storage.saveConfigs(newConfigs)
        val newConfigMap = newConfigs.associateBy { it.id }

        // 1. Remove clients that are deleted or disabled
        for (id in activeClients.keys) {
            val newConf = newConfigMap[id]
            if (newConf == null || !newConf.isEnabled) {
                reconnectJobs[id]?.cancel()
                reconnectJobs.remove(id)
                statusJobs[id]?.cancel()
                statusJobs.remove(id)
                activeClients[id]?.disconnect()
                activeClients.remove(id)
            }
        }

        // 2. Start or update enabled clients
        for (config in newConfigs) {
            if (config.isEnabled) {
                val existingClient = activeClients[config.id]
                if (existingClient == null || existingClient.config.sseUrl != config.sseUrl || existingClient.config.name != config.name) {
                    reconnectJobs[config.id]?.cancel()
                    statusJobs[config.id]?.cancel()
                    statusJobs.remove(config.id)
                    existingClient?.disconnect()

                    val newClient = McpClient(config, okhttpClient)
                    activeClients[config.id] = newClient

                    // Monitor status changes
                    val statusJob = coroutineScope.launch {
                        newClient.status.collect {
                            updateServerStates()
                        }
                    }
                    statusJobs[config.id] = statusJob

                    startConnectionLoop(config)
                }
            }
        }

        updateServerStates()
    }

    private fun startConnectionLoop(config: McpServerConfig) {
        reconnectJobs[config.id]?.cancel()
        reconnectJobs[config.id] = coroutineScope.launch {
            var attempt = 0
            val initialDelayMs = 2000L
            val maxDelayMs = 60000L
            val multiplier = 2.0

            while (isActive) {
                val currentConfig = storage.loadConfigs().find { it.id == config.id }
                if (currentConfig == null || !currentConfig.isEnabled) {
                    break
                }

                // Wait for network connection
                _isNetworkAvailableFlow.first { it }

                val client = activeClients[config.id] ?: break
                
                Log.d(TAG, "Attempting connection for ${config.name}")
                val success = client.connect()
                if (success) {
                    attempt = 0
                    // Suspend until disconnected
                    client.status.first { it == McpServerStatus.DISCONNECTED }
                }

                // Check again if enabled before delaying
                val configAfterDisconnect = storage.loadConfigs().find { it.id == config.id }
                if (configAfterDisconnect == null || !configAfterDisconnect.isEnabled) {
                    break
                }

                attempt++
                val baseDelay = (initialDelayMs * Math.pow(multiplier, attempt.toDouble())).toLong().coerceAtMost(maxDelayMs)
                // Add Jitter (+/- 10%)
                val jitterRange = (baseDelay * 0.1).toLong()
                val jitter = if (jitterRange > 0) {
                    kotlin.random.Random.nextLong(-jitterRange, jitterRange)
                } else {
                    0L
                }
                val delayTime = (baseDelay + jitter).coerceAtLeast(1000L)

                Log.d(TAG, "Reconnecting to ${config.name} (attempt $attempt) in ${delayTime}ms")
                delay(delayTime)
            }
        }
    }

    private fun updateServerStates() {
        val states = mutableMapOf<String, McpServerStatus>()
        // Initialize loaded configs status
        for (config in storage.loadConfigs()) {
            states[config.id] = McpServerStatus.DISCONNECTED
        }
        // Apply active statuses
        for ((id, client) in activeClients) {
            states[id] = client.status.value
        }
        _serverStates.value = states
    }

    suspend fun getAggregateTools(): List<McpTool> {
        val list = mutableListOf<McpTool>()
        
        // 1. Add Local Builtin Tools
        val localTools = perceptionServer.getTools()
        for (tool in localTools) {
            list.add(tool.copy(name = "${LOCAL_SERVER_NAME}__${tool.name}"))
        }

        // 2. Add Remote Tools
        for (client in activeClients.values) {
            if (client.status.value == McpServerStatus.CONNECTED) {
                val tools = client.discoveredTools.value
                // Prefix tool names: Clean server name + "__" + tool name
                val prefix = client.config.name.replace(Regex("[^a-zA-Z0-9_]"), "_")
                for (tool in tools) {
                    list.add(tool.copy(name = "${prefix}__${tool.name}"))
                }
            }
        }
        return list
    }

    suspend fun callTool(prefixedToolName: String, arguments: Map<String, Any>?): JsonRpcResponse {
        // 1. Try to split by prefix
        if (prefixedToolName.contains("__")) {
            val parts = prefixedToolName.split("__", limit = 2)
            val serverPrefix = parts[0]
            val toolName = parts[1]

            if (serverPrefix == LOCAL_SERVER_NAME) {
                return perceptionServer.callTool(toolName, arguments)
            }

            val client = activeClients.values.find {
                it.config.name.replace(Regex("[^a-zA-Z0-9_]"), "_") == serverPrefix
            }
            if (client != null) {
                return client.callTool(toolName, arguments)
            }
        }

        // 2. Fallback: Search all active clients
        for (client in activeClients.values) {
            val hasTool = client.discoveredTools.value.any { it.name == prefixedToolName }
            if (hasTool) {
                return client.callTool(prefixedToolName, arguments)
            }
        }

        throw IOException("McpTool $prefixedToolName not found on any connected server.")
    }
    
    // Test helper to verify connections instantly or trigger connectivity manually
    fun getClients(): List<McpClient> = activeClients.values.toList()

    fun getToolsForServer(serverId: String): List<McpTool> {
        return activeClients[serverId]?.discoveredTools?.value ?: emptyList()
    }

    internal fun registerClientForTest(id: String, client: McpClient) {
        activeClients[id] = client
    }
}
