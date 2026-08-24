package com.loyea.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.LoyeaApplication
import com.loyea.R
import com.loyea.perception.memory.GraphMemoryManager
import com.loyea.perception.memory.MemoryTripleDraft
import com.loyea.plugin.host.PersonaRuntimeLease
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.BackgroundPromptTemplates
import com.loyea.ui.chat.CharacterPersonaOwnership
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.PromptAssembler
import com.loyea.ui.chat.PersonaBindingSnapshot
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.estimateTokens
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 记忆与长程知识图谱整理 Worker，以 Expedited (加急临时前台服务) 方式运行以防止切后台强杀
 */
class MemoryConsolidationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val INPUT_SESSION_ID = "session_id"
        const val INPUT_PERSONA_OWNER_ID = "persona_owner_id"
        const val INPUT_PERSONA_ID = "persona_id"
        const val INPUT_SESSION_INCARNATION_ID = "session_incarnation_id"
        const val INPUT_PERSONA_BINDING_REVISION = "persona_binding_revision"

        fun uniqueWorkName(binding: PersonaBindingSnapshot): String =
            "memory_consolidation_" + MessageDigest.getInstance("SHA-256")
                .digest(
                    listOf(
                        binding.sessionId,
                        binding.sessionIncarnationId,
                        binding.personaBindingRevision.toString(),
                        binding.ref.ownerId.value,
                        binding.ref.personaId
                    ).joinToString("\u0000").toByteArray(Charsets.UTF_8)
                )
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "loyea_consolidation"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Loyea Memory Consolidation",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Loyea")
            .setContentText("正在整理记忆思绪中...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return ForegroundInfo(1001, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString(INPUT_SESSION_ID)
        val expectedOwnerId = inputData.getString(INPUT_PERSONA_OWNER_ID)
        val expectedPersonaId = inputData.getString(INPUT_PERSONA_ID)
        val expectedIncarnationId = inputData.getString(INPUT_SESSION_INCARNATION_ID)
        val expectedBindingRevision = inputData.getLong(INPUT_PERSONA_BINDING_REVISION, Long.MIN_VALUE)
        if (sessionId.isNullOrBlank()) {
            return@withContext Result.failure()
        }

        try {
            val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
            val storageManager = ChatStorageManager(context)
            val llmClient = LlmClient()
            val graphMemoryManager = GraphMemoryManager(context)

            val sessions = storageManager.loadSessionList()
            val session = sessions.find { it.id == sessionId } ?: return@withContext Result.success()
            val binding = PersonaBindingSnapshot.capture(session) ?: return@withContext Result.success()
            if (!binding.matchesExpected(
                    expectedOwnerId,
                    expectedPersonaId,
                    expectedIncarnationId,
                    expectedBindingRevision
                )
            ) {
                return@withContext Result.success()
            }
            val loyeaApplication = context.applicationContext as? LoyeaApplication
            var personaLease: PersonaRuntimeLease? = null
            if (!binding.ref.isNative) {
                personaLease = loyeaApplication
                    ?.acquirePersonaRuntime(binding.ref.ownerId)
                    ?: return@withContext Result.success()
            }

            try {
            val cards = storageManager.loadCharacterCards()
            if (CharacterPersonaOwnership.resolveBoundPersona(session, cards)
                    ?.takeIf { it.ref == binding.ref } == null
            ) {
                return@withContext Result.success()
            }
            val oldMemories = session.coreMemories
            val messages = storageManager.loadSessionMessages(sessionId)
            val historyMsgs = messages.takeLast(20)

            // 1. 整理核心事实记忆 (Core Memories)
            val coreFacts = oldMemories.filter { it.startsWith("★") }
            val normalFacts = oldMemories.filter { !it.startsWith("★") }

            val summaryInput = BackgroundPromptTemplates.memoryConsolidationInput(
                coreFacts = coreFacts,
                normalFacts = normalFacts,
                history = historyMsgs
            )

            val memoryApiId = prefs.getString("memory_api_config_id", "") ?: ""
            val savedConfigsJson = prefs.getString("api_config_list", "") ?: ""
            val apiConfigList = if (savedConfigsJson.isNotBlank()) {
                val type = object : TypeToken<List<ApiConfig>>() {}.type
                Gson().fromJson<List<ApiConfig>>(savedConfigsJson, type) ?: emptyList()
            } else {
                emptyList()
            }
            
            // 获取当前激活的 API 配置
            val activeConfigId = prefs.getString("active_config_id", "") ?: ""
            val activeApiConfig = apiConfigList.find { it.id == activeConfigId } ?: ApiConfig(
                id = "default",
                name = "Default",
                provider = "DeepSeek",
                apiUrl = "https://api.deepseek.com/v1",
                apiKey = "",
                modelName = "deepseek-v4-pro"
            )

            val targetConfig = if (memoryApiId.isBlank()) {
                activeApiConfig
            } else {
                apiConfigList.find { it.id == memoryApiId } ?: activeApiConfig
            }

            val llmResponse = llmClient.sendChatCompletion(
                config = targetConfig,
                systemPrompt = BackgroundPromptTemplates.MEMORY_CONSOLIDATION_SYSTEM,
                history = listOf(
                    Message(
                        id = "memory-consolidation-input",
                        content = summaryInput,
                        sender = Sender.USER
                    )
                )
            )
            // 记忆提炼计入会话用量（系统调用）；服务端未返回 usage 时用字符估算兜底
            val corePromptTokens = llmResponse.promptTokens ?:
                estimateTokens(BackgroundPromptTemplates.MEMORY_CONSOLIDATION_SYSTEM) + estimateTokens(summaryInput)
            val coreCompletionTokens = llmResponse.completionTokens ?: estimateTokens(llmResponse.content)
            val responseText = llmResponse.content
            var consolidatedMemories: List<String>? = null
            if (!llmResponse.isError && responseText.isNotBlank()) {
                val newMemories = mutableListOf<String>()
                val regex = Regex("\\[([^\\]]+)\\]")
                regex.findAll(responseText).forEach { matchResult ->
                    val fact = matchResult.groupValues[1].trim()
                    if (fact.isNotBlank()) {
                        newMemories.add(fact)
                    }
                }

                // 确保所有的锁定事实依然完整保留（即使大模型漏掉了，也做兜底）
                coreFacts.forEach { coreFact ->
                    val coreFactContent = coreFact.removePrefix("★").trim()
                    if (newMemories.none { it.contains(coreFactContent) }) {
                        newMemories.add(0, coreFact)
                    }
                }

                // 写入端隐私过滤：会话关闭物理感知时，敏感健康/位置/设备事实不允许进入长期记忆；
                // ★ 用户锁定项跳过过滤（用户显式锁定 = 明确授权该记忆存在，优先于自动过滤，严禁被静默删除）
                val filteredMemories = if (session.useSystemTime == true) {
                    newMemories
                } else {
                    newMemories.filter { fact ->
                        fact.startsWith("★") || PromptAssembler.SENSITIVE_MEMORY_KEYWORDS.none { fact.contains(it, ignoreCase = true) }
                    }
                }

                if (filteredMemories.isNotEmpty() || responseText.contains("无旧核心记忆") || oldMemories.isNotEmpty()) {
                    consolidatedMemories = filteredMemories
                }
            }
            val coreCommitted = storageManager.updateSessionIfPersonaBinding(binding) { current ->
                current.copy(
                    coreMemories = consolidatedMemories ?: current.coreMemories,
                    promptTokens = current.promptTokens + corePromptTokens,
                    completionTokens = current.completionTokens + coreCompletionTokens
                )
            }
            if (!coreCommitted) return@withContext Result.success()

            // 2. 提取长程图谱网络记忆 (且每个会话相互独立)
            val enableGraphMemory = prefs.getBoolean("enable_graph_memory", true)
            if (enableGraphMemory) {
                if (!storageManager.isPersonaBindingCurrent(binding)) {
                    return@withContext Result.success()
                }
                val graphInput = BackgroundPromptTemplates.graphExtractionInput(historyMsgs)

                val graphLlmResponse = llmClient.sendChatCompletion(
                    config = targetConfig,
                    systemPrompt = BackgroundPromptTemplates.GRAPH_EXTRACTION_SYSTEM,
                    history = listOf(
                        Message(
                            id = "graph-extraction-input",
                            content = graphInput,
                            sender = Sender.USER
                        )
                    )
                )
                // 图谱提取计入会话用量（系统调用）；服务端未返回 usage 时用字符估算兜底
                val graphCommitted = storageManager.updateSessionIfPersonaBinding(binding) { current ->
                    current.copy(
                        promptTokens = current.promptTokens + (graphLlmResponse.promptTokens ?:
                            estimateTokens(BackgroundPromptTemplates.GRAPH_EXTRACTION_SYSTEM) + estimateTokens(graphInput)),
                        completionTokens = current.completionTokens +
                            (graphLlmResponse.completionTokens ?: estimateTokens(graphLlmResponse.content))
                    )
                }
                if (!graphCommitted) return@withContext Result.success()
                var graphResponseText = graphLlmResponse.content.trim()
                if (!graphLlmResponse.isError && graphResponseText.isNotBlank()) {
                    if (graphResponseText.startsWith("```")) {
                        graphResponseText = graphResponseText.removePrefix("```json").removePrefix("```")
                        if (graphResponseText.endsWith("```")) {
                            graphResponseText = graphResponseText.removeSuffix("```")
                        }
                        graphResponseText = graphResponseText.trim()
                    }

                    val typeMap = object : TypeToken<List<Map<String, String>>>() {}.type
                    val triplesList: List<Map<String, String>> = try {
                        Gson().fromJson(graphResponseText, typeMap)
                    } catch (jsonEx: Exception) {
                        Log.w("GraphMemory", "JSON syntax error when parsing extracted graph memories in Worker: ${jsonEx.message}")
                        emptyList()
                    }

                    // 写入端隐私过滤：会话关闭物理感知时，拒绝含敏感健康/位置/设备信息的三元组入库
                    val memoryFiltered = session.useSystemTime != true
                    val drafts = triplesList.mapNotNull { item ->
                        val s = item["s"]?.trim()
                        val p = item["p"]?.trim()
                        val o = item["o"]?.trim()
                        if (!s.isNullOrBlank() && !p.isNullOrBlank() && !o.isNullOrBlank()) {
                            if (memoryFiltered && PromptAssembler.SENSITIVE_MEMORY_KEYWORDS.any {
                                    s.contains(it, ignoreCase = true) || p.contains(it, ignoreCase = true) || o.contains(it, ignoreCase = true)
                                }) {
                                null
                            } else {
                                MemoryTripleDraft(s, p, o)
                            }
                        } else {
                            null
                        }
                    }
                    if (!storageManager.isPersonaBindingCurrent(binding)) {
                        return@withContext Result.success()
                    }
                    graphMemoryManager.upsertTriples(binding, drafts)
                    // Optimistic compensation closes the delete/rebind race. Old identities are
                    // never readable, and any write that lost the race is removed immediately.
                    if (!storageManager.isPersonaBindingCurrent(binding)) {
                        graphMemoryManager.clearMemoriesForBinding(binding)
                        return@withContext Result.success()
                    }
                }
            }

            Result.success()
            } finally {
                personaLease?.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
