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
import com.loyea.R
import com.loyea.perception.memory.GraphMemoryManager
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.BackgroundPromptTemplates
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.PromptAssembler
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.estimateTokens
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记忆与长程知识图谱整理 Worker，以 Expedited (加急临时前台服务) 方式运行以防止切后台强杀
 */
class MemoryConsolidationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "loyea_consolidation"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isEn = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
            .getString("app_language", "zh") == "en"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Loyea Memory Consolidation",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Loyea")
            .setContentText(if (isEn) "Consolidating memories..." else "正在整理记忆思绪中...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return ForegroundInfo(1001, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString("session_id")
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

            // 整理窗口水位（审计 R-02/AC-25）：只整理上次之后的新消息；首次运行回溯最近 20 条。
            // 滑窗重叠会把同一来源反复喂给模型，图谱 upsert 会不断强化 mentionCount。
            val messages = storageManager.loadSessionMessages(sessionId)
            val watermark = session.consolidatedUpTo
            val start = when {
                watermark > 0 && watermark >= messages.size -> messages.size
                watermark in 1 until messages.size -> watermark
                else -> (messages.size - 20).coerceAtLeast(0)
            }
            if (start >= messages.size) return@withContext Result.success()
            val historyMsgs = messages.subList(start, messages.size)
            val processedCount = messages.size
            val revisionAtStart = session.memoryRevision

            val characterId = session.characterId
            val isCompanion = com.loyea.plugin.companion.CompanionContract.isCompanionCharacter(characterId)

            // MEMORY 通道经 ApiConfigRepository 统一解析（显式绑定优先，否则继承 CHAT；Spec §19/§5.4）
            // 不再直读加密库与散装键、不再构造幽灵默认配置；通道不可用 = 可重试失败，不伪报成功
            val repository = com.loyea.storage.ApiConfigRepository(context)
            val targetConfig = when (val memory = repository.resolve(com.loyea.storage.ChannelId.MEMORY)) {
                is com.loyea.storage.ChannelResolution.Ready -> memory.resolved.config
                else -> {
                    Log.w("MemoryConsolidationWorker", "MEMORY channel not ready: $memory")
                    return@withContext Result.retry()
                }
            }

            var revisionConflict = false
            // 核心整理步骤是否有效完成：模型失败（错误/空响应）时保持 false，
            // 水位不推进，让下一轮用同一窗口重试（审计 R-02：失败不得静默丢整理机会）
            var coreStepCompleted = isCompanion

            // 1. 整理核心事实记忆（Core Memories）
            // 审计 R-02：陪伴模式的「固定记忆」是用户独占所有权的资料——自动整理对其只读，
            // 连模型调用都不发起（UI 承诺「不会被自动整理改写」；模型产出永不做整体替换）。
            // 普通模式保留核心记忆整理，但提交走修订号条件合同。
            if (!isCompanion) {
                val coreFacts = session.coreMemories.filter { it.startsWith("★") }
                val normalFacts = session.coreMemories.filter { !it.startsWith("★") }

                val summaryInput = BackgroundPromptTemplates.memoryConsolidationInput(
                    coreFacts = coreFacts,
                    normalFacts = normalFacts,
                    history = historyMsgs
                )

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
                storageManager.updateSessionTokens(
                    sessionId,
                    promptTokens = llmResponse.promptTokens ?:
                        estimateTokens(BackgroundPromptTemplates.MEMORY_CONSOLIDATION_SYSTEM) + estimateTokens(summaryInput),
                    completionTokens = llmResponse.completionTokens ?: estimateTokens(llmResponse.content),
                    lastContextTokens = null
                )
                val responseText = llmResponse.content
                if (!llmResponse.isError && responseText.isNotBlank()) {
                    coreStepCompleted = true
                    val extractedRaw = mutableListOf<String>()
                    Regex("\\[([^\\]]+)\\]").findAll(responseText).forEach { matchResult ->
                        val fact = matchResult.groupValues[1].trim()
                        if (fact.isNotBlank()) {
                            extractedRaw.add(fact)
                        }
                    }

                    // 写入端隐私过滤：会话关闭物理感知时，敏感健康/位置/设备事实不允许进入长期记忆；
                    // ★ 用户锁定项跳过过滤（用户显式锁定 = 明确授权该记忆存在，严禁被静默删除）
                    val filteredExtracted = if (session.useSystemTime == true) {
                        extractedRaw
                    } else {
                        extractedRaw.filter { fact ->
                            fact.startsWith("★") || PromptAssembler.SENSITIVE_MEMORY_KEYWORDS.none { fact.contains(it, ignoreCase = true) }
                        }
                    }

                    // 条件提交（审计 R-02）：同一把锁内比对修订号——用户在模型运行期间
                    // 增/删/改/转固定过核心记忆则整轮作废；提取为空严格 no-op 不清空。
                    storageManager.updateSessionList { currentList ->
                        currentList.map { s ->
                            if (s.id != sessionId) s
                            else when (val d = MemoryConsolidationPolicy.decideCoreCommit(
                                revisionAtStart = revisionAtStart,
                                currentRevision = s.memoryRevision,
                                extracted = filteredExtracted,
                                lockedFacts = coreFacts,
                                processedCount = processedCount
                            )) {
                                is MemoryConsolidationPolicy.CoreDecision.Abort -> {
                                    revisionConflict = true
                                    s
                                }
                                is MemoryConsolidationPolicy.CoreDecision.AdvanceWatermark -> s
                                is MemoryConsolidationPolicy.CoreDecision.Commit ->
                                    s.copy(
                                        coreMemories = d.memories,
                                        memoryRevision = s.memoryRevision + 1
                                    )
                            }
                        }
                    }
                }
            }

            // 2. 提取长程图谱网络记忆 (且每个会话相互独立)
            // 核心步骤失败时整轮作废：否则图谱会先处理、下轮重试又再次 upsert 同一来源
            val enableGraphMemory = prefs.getBoolean("enable_graph_memory", true)
            if (enableGraphMemory && !revisionConflict && coreStepCompleted) {
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
                storageManager.updateSessionTokens(
                    sessionId,
                    promptTokens = graphLlmResponse.promptTokens ?:
                        estimateTokens(BackgroundPromptTemplates.GRAPH_EXTRACTION_SYSTEM) + estimateTokens(graphInput),
                    completionTokens = graphLlmResponse.completionTokens ?: estimateTokens(graphLlmResponse.content),
                    lastContextTokens = null
                )
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
                    for (item in triplesList) {
                        val s = item["s"]?.trim()
                        val p = item["p"]?.trim()
                        val o = item["o"]?.trim()
                        if (!s.isNullOrBlank() && !p.isNullOrBlank() && !o.isNullOrBlank()) {
                            if (memoryFiltered && PromptAssembler.SENSITIVE_MEMORY_KEYWORDS.any {
                                    s.contains(it, ignoreCase = true) || p.contains(it, ignoreCase = true) || o.contains(it, ignoreCase = true)
                                }) {
                                continue
                            }
                            graphMemoryManager.upsertTriple(
                                characterId = characterId,
                                sessionId = sessionId,
                                subject = s,
                                predicate = p,
                                `object` = o
                            )
                        }
                    }
                }
            }

            // 3. 推进整理水位（审计 R-02）：核心步骤有效完成且无修订冲突时推进；
            // 每条消息至多参与一次整理，图谱 mentionCount 不会因重复处理而虚高。
            // 图谱提取失败不回退水位（重试会造成 mentionCount 虚高，损失由后续消息上下文弥补）。
            if (!revisionConflict && coreStepCompleted) {
                storageManager.updateSessionConsolidationWatermark(sessionId, processedCount)
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
