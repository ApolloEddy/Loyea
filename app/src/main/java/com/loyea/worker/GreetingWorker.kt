package com.loyea.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.MainActivity
import com.loyea.R
import com.loyea.perception.PhysicalContextManager
import com.loyea.ui.chat.PromptAssembler
import com.loyea.ui.chat.BackgroundPromptTemplates
import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.StreamEvent
import com.loyea.ui.chat.TavernCardParser
import com.loyea.ui.chat.estimateTokens
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GreetingWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
            
            // 0. 前置判断：检查用户是否开启了后台主动问候
            val enableBgGreeting = prefs.getBoolean("enable_background_greeting", true)
            if (!enableBgGreeting) {
                Log.d("GreetingWorker", "Background greeting is disabled by user.")
                return@withContext Result.success()
            }

            // 0.1 深夜免打扰判断：凌晨 0 点到 7 点之间不进行推送，顺延到早晨 8 点以后
            val calendar = java.util.Calendar.getInstance()
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            if (hour >= 0 && hour < 7) {
                val targetCalendar = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 8)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                }
                val delayMinutes = ((targetCalendar.timeInMillis - calendar.timeInMillis) / (1000 * 60)).coerceAtLeast(60)
                scheduleNextGreeting(delayMinutes)
                Log.d("GreetingWorker", "Quiet hours active (00:00-07:00). Postponing next greeting to 08:00 ($delayMinutes mins delay).")
                return@withContext Result.success()
            }

            val storageManager = ChatStorageManager(context)
            val llmClient = LlmClient()

            // 1. Get API config
            val activeConfigId = prefs.getString("active_config_id", "") ?: ""
            val savedConfigsJson = prefs.getString("api_config_list", "") ?: ""
            if (savedConfigsJson.isBlank()) return@withContext Result.failure()
            val type = object : TypeToken<List<ApiConfig>>() {}.type
            val apiConfigList = Gson().fromJson<List<ApiConfig>>(savedConfigsJson, type) ?: emptyList()
            val activeConfig = apiConfigList.find { it.id == activeConfigId } ?: apiConfigList.firstOrNull()
            if (activeConfig == null) return@withContext Result.failure()

            // 2. Get active session and character
            var sessionId = prefs.getString("current_session_id", "") ?: ""
            val sessions = storageManager.loadSessionList()
            
            if (sessionId.isBlank() || sessions.none { it.id == sessionId }) {
                sessionId = sessions.firstOrNull()?.id ?: return@withContext Result.failure()
            }
            
            val currentSession = sessions.find { it.id == sessionId } ?: return@withContext Result.failure()
            
            val charId = currentSession.characterId
            val allCards = storageManager.loadCharacterCards()
            val activeCard = allCards.find { it.id == charId } 
                ?: allCards.firstOrNull { it.id == "char_loyea_default" } 
                ?: TavernCardParser.getBuiltInCards().first()

            val userName = prefs.getString("user_name", "Loyea Developer") ?: "Loyea Developer"

            // 3. Prepare Physical Context
            // 尊重会话级物理感知开关：关闭时不构建/不发送任何物理上下文（隐私优先），
            // 避免后台问候绕过用户开关把 GPS/健康/蓝牙等敏感数据外发
            val sessionUsesSystemTime = currentSession.useSystemTime ?: false
            val physicalContext = if (sessionUsesSystemTime) {
                val perceptionManager = PhysicalContextManager(context)
                perceptionManager.buildPhysicalContextString()
            } else {
                null
            }

            val eventTime = System.currentTimeMillis()
            val promptParts = PromptAssembler.assemblePromptParts(
                card = activeCard,
                userName = userName,
                useSystemTime = sessionUsesSystemTime,
                physicalContext = physicalContext,
                trustedCard = activeCard.isBuiltIn,
                snapshotTimeMillis = eventTime
            )

            // 4. Prepare Prompt
            val history = storageManager.loadSessionMessages(sessionId).takeLast(10)
            val systemPrompt = BackgroundPromptTemplates.greetingSystem(
                promptParts.stableSystemPrompt,
                userName
            )
            val eventInput = BackgroundPromptTemplates.greetingEventInput(promptParts.turnContextSnapshot)
            val requestHistory = history + Message(
                id = "background-greeting-event",
                content = eventInput,
                sender = Sender.USER,
                timestamp = eventTime
            )

            var generatedText = ""
            // 问候计费累计器：服务端返回 usage 时用真实值，否则估算兜底
            var accumulatedPrompt = 0L
            var accumulatedCompletion = 0L
            var hasRealUsage = false
            try {
                llmClient.sendChatCompletionStream(
                    config = activeConfig.copy(enableReasoning = false), // Disable reasoning for quick greeting
                    systemPrompt = systemPrompt,
                    history = requestHistory
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> generatedText += event.text
                        is StreamEvent.Usage -> {
                            accumulatedPrompt += event.promptTokens
                            accumulatedCompletion += event.completionTokens
                            hasRealUsage = true
                        }
                        is StreamEvent.Error -> throw Exception(event.message)
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext Result.retry()
            }

            if (generatedText.isBlank()) return@withContext Result.retry()

            // 后台主动问候计入解析出的那个会话用量（系统调用）；lastContext 仅主聊天流写，这里保持 null
            val historyText = requestHistory.joinToString("\n") { it.content }
            storageManager.updateSessionTokens(
                sessionId,
                promptTokens = if (hasRealUsage) accumulatedPrompt else estimateTokens(systemPrompt) + estimateTokens(historyText),
                completionTokens = if (hasRealUsage) accumulatedCompletion else estimateTokens(generatedText),
                lastContextTokens = null
            )

            // 4. Save to chat
            val newMsg = Message(
                id = "${System.currentTimeMillis()}_greeting",
                content = generatedText.trim(),
                sender = Sender.AI,
                characterId = activeCard.id
            )
            // 4. Save to chat atomically
            storageManager.updateSessionMessages(sessionId) { currentMsgs ->
                currentMsgs + newMsg
            }

            // Update session lastActiveTime atomically
            storageManager.updateSessionList { currentSessions ->
                currentSessions.map {
                    if (it.id == sessionId) it.copy(lastActiveTime = System.currentTimeMillis()) else it
                }.sortedByDescending { it.lastActiveTime }
            }

            // 5. Send Notification
            sendNotification(activeCard.name, generatedText.trim())

            // 6. 链式预定下一次随机延迟的主动问候（2 到 8 小时随机）
            val randomDelayMinutes = kotlin.random.Random.nextInt(120, 480).toLong()
            scheduleNextGreeting(randomDelayMinutes)
            Log.d("GreetingWorker", "Proactive greeting sent successfully. Next greeting scheduled in $randomDelayMinutes mins.")

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun scheduleNextGreeting(delayMinutes: Long) {
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<GreetingWorker>()
            .setInitialDelay(delayMinutes, java.util.concurrent.TimeUnit.MINUTES)
            .addTag("loyea_bg_greeting")
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "loyea_bg_greeting_work",
            androidx.work.ExistingWorkPolicy.REPLACE, // REPLACE 替换原有，保证队列唯一性
            workRequest
        )
    }

    private fun sendNotification(title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "loyea_greetings"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Loyea Proactive Greetings",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // 锁屏与通知历史不展示内容，防止健康/情感类问候语被他人窥见
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
