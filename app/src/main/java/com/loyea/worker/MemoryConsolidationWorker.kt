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
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Sender
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
            val oldMemories = session.coreMemories
            val messages = storageManager.loadSessionMessages(sessionId)
            val historyMsgs = messages.takeLast(20)

            val characterId = session.characterId

            // 1. 整理核心事实记忆 (Core Memories)
            val coreFacts = oldMemories.filter { it.startsWith("★") }
            val normalFacts = oldMemories.filter { !it.startsWith("★") }

            val summaryPrompt = """
                你是一个AI事实记忆整合器。你的职责是根据最近的对话历史，提取并精简出长期事实记忆。
                
                【锁定核心事实】（以 ★ 开头，由用户锁定，必须完整且原样保留，严禁进行任何文字修改、合并或删除！）：
                ${if (coreFacts.isEmpty()) "(无)" else coreFacts.joinToString("\n") { "- $it" }}
                
                【已有普通事实】（未锁定，可以被修改、合并或删除）：
                ${if (normalFacts.isEmpty()) "(无)" else normalFacts.joinToString("\n") { "- $it" }}
                
                【最近20条对话历史】：
                ${historyMsgs.joinToString("\n") { "${if (it.sender == Sender.USER) "用户" else "AI"}: ${it.content}" }}
                
                任务目标：
                1. 仔细阅读最近的对话历史，从中提炼出有关于用户个人信息、喜好、重大事件、双方重要约定等需要被AI长期记住的事实（例如：“用户喜欢喝拿铁咖啡”、“用户的猫叫咪咪”）。
                2. 将提炼的最新事实与已有的记忆整合：
                   - 所有以 ★ 开头的事实必须在最终输出中完整且原样保留（包括 ★ 符号本身和前缀，例如：[★ 用户对花生过敏]），严禁对其做任何内容修改、合并或删除。
                   - 对于未锁定（不带 ★）的普通事实，请与新提取的事实整合。如果新旧事实冲突，请以新对话中的事实为准；如果意思重复或相近，请予以合并；如果某个旧条目已被对话明确推翻，可将其删除。
                3. 新提取出来的普通事实千万不要带 ★ 符号（★ 符号仅供用户锁定的核心事实使用）。记忆条目必须高度精炼，通常只需一句话陈述一个客观事实。
                4. 严格输出为以中括号包裹的格式，每一行一个条目，格式为：[★ 锁定事实内容] 或 [普通事实内容]。
                   例如：
                   [★ 用户对花生过敏]
                   [用户今天心情很好]
                5. 请直接输出整合后的最新事实列表，严禁包含任何前言、后记、分析过程或其他无关闲聊废话。如果最近的对话中没有提到任何有价值的、需要长期记住的核心事实，请完整原样输出所有旧核心记忆和普通事实。
            """.trimIndent()

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
                systemPrompt = summaryPrompt,
                history = emptyList()
            )
            val responseText = llmResponse.content
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

                if (newMemories.isNotEmpty() || responseText.contains("无旧核心记忆") || oldMemories.isNotEmpty()) {
                    // 更新 Session 的 Core Memories
                    storageManager.updateSessionCoreMemories(sessionId, newMemories)
                }
            }

            // 2. 提取长程图谱网络记忆 (且每个会话相互独立)
            val enableGraphMemory = prefs.getBoolean("enable_graph_memory", true)
            if (enableGraphMemory) {
                val extractPrompt = """
                    You are a highly structured information extractor. Your task is to extract core personal preferences, life events, habits, and relationships of the User ("主人") from the conversation history.

                    Rules:
                    1. Output ONLY a raw, minified JSON array of objects. Do NOT wrap in markdown code blocks, do NOT write prefix/suffix text.
                    2. Structure: [{"s":"Subject", "p":"Predicate", "o":"Object"}]
                    3. Avoid generic triples. Focus on concrete preferences, facts, and events.
                    4. Language of extraction MUST match the conversation (Chinese).

                    Example Input:
                    User: "我最近在做那个 loyea 安卓项目，加班好严重，牛奶过敏的我都只敢点抹茶燕麦拿铁提神。"
                    Extract Triples:
                    [{"s":"主人","p":"正在开发项目","o":"loyea 安卓项目"},{"s":"主人","p":"近期状态","o":"严重加班"},{"s":"主人","p":"过敏于","o":"纯牛奶"},{"s":"主人","p":"喜欢饮品","o":"抹茶燕麦拿铁"}]
                    
                    Here is the conversation history:
                    ${historyMsgs.joinToString("\n") { "${if (it.sender == Sender.USER) "User" else "AI"}: ${it.content}" }}
                """.trimIndent()

                val graphLlmResponse = llmClient.sendChatCompletion(
                    config = targetConfig,
                    systemPrompt = extractPrompt,
                    history = emptyList()
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

                    for (item in triplesList) {
                        val s = item["s"]?.trim()
                        val p = item["p"]?.trim()
                        val o = item["o"]?.trim()
                        if (!s.isNullOrBlank() && !p.isNullOrBlank() && !o.isNullOrBlank()) {
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

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
