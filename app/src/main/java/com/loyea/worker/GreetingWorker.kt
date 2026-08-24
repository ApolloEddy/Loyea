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
import com.loyea.LoyeaApplication
import com.loyea.MainActivity
import com.loyea.R
import com.loyea.perception.PhysicalContextManager
import com.loyea.plugin.api.ChatRole
import com.loyea.plugin.api.ConversationText
import com.loyea.plugin.api.PluginTurnInput
import com.loyea.plugin.api.PromptPatch
import com.loyea.plugin.host.PersonaRuntimeLease
import com.loyea.ui.chat.PromptAssembler
import com.loyea.ui.chat.BackgroundPromptTemplates
import com.loyea.ui.chat.BackgroundGreetingCommitStatus
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.CharacterPersonaOwnership
import com.loyea.ui.chat.LegacyTavernTurnAdapter
import com.loyea.ui.chat.LlmClient
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.PersonaBindingSnapshot
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.StreamEvent
import com.loyea.plugins.tavern.core.TavernPreparedTurnFactory
import com.loyea.ui.chat.estimateTokens
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GreetingWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "loyea_bg_greeting_work"
        private const val WORK_TAG = "loyea_bg_greeting"

        fun ensureScheduled(context: Context, delayMinutes: Long) {
            enqueueScheduled(context, delayMinutes, androidx.work.ExistingWorkPolicy.KEEP)
        }

        fun rescheduleAfterPluginEnabled(context: Context, delayMinutes: Long) {
            enqueueScheduled(context, delayMinutes, androidx.work.ExistingWorkPolicy.REPLACE)
        }

        private fun enqueueScheduled(
            context: Context,
            delayMinutes: Long,
            policy: androidx.work.ExistingWorkPolicy
        ) {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<GreetingWorker>()
                .setInitialDelay(delayMinutes.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = id.toString()
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
            val binding = PersonaBindingSnapshot.capture(currentSession)
                ?: run {
                    scheduleAfterSkippedGreeting("The persisted persona identity is unresolved.")
                    return@withContext Result.success()
                }
            val loyeaApplication = context.applicationContext as? LoyeaApplication
            var personaLease: PersonaRuntimeLease? = null
            if (!binding.ref.isNative) {
                personaLease = loyeaApplication
                    ?.acquirePersonaRuntime(binding.ref.ownerId)
                    ?: run {
                        storageManager.discardPendingBackgroundGreeting(operationId)
                        scheduleAfterSkippedGreeting("The persona plugin is disabled or unavailable.")
                        return@withContext Result.success()
                    }
            }

            try {
                val allCards = storageManager.loadCharacterCards()
                val boundPersona = CharacterPersonaOwnership.resolveBoundPersona(currentSession, allCards)
                    ?.takeIf { it.ref == binding.ref }
                    ?: run {
                        storageManager.discardPendingBackgroundGreeting(operationId)
                        scheduleAfterSkippedGreeting("The bound persona card is unavailable.")
                        return@withContext Result.success()
                    }
                val activeCard = boundPersona.card
                val userName = prefs.getString("user_name", "Loyea Developer") ?: "Loyea Developer"

                // A process may have stopped after journaling or after only one target file write.
                // Finish that exact Work operation locally before considering another model request.
                storageManager.resumeBackgroundGreeting(operationId, binding)?.let { recovered ->
                    if (recovered.status != BackgroundGreetingCommitStatus.STALE) {
                        recovered.message?.let { message ->
                            sendNotification(operationId, activeCard.name, message.content)
                        }
                    }
                    scheduleAfterSkippedGreeting("Recovered a previous background greeting transaction.")
                    return@withContext Result.success()
                }

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
                val history = storageManager.loadSessionMessages(sessionId).takeLast(10)
                val promptParts = PromptAssembler.assemblePromptParts(
                    card = activeCard,
                    userName = userName,
                    useSystemTime = sessionUsesSystemTime,
                    physicalContext = physicalContext,
                    trustedCard = binding.ref.isNative,
                    snapshotTimeMillis = eventTime
                )
                val turnSpec = LegacyTavernTurnAdapter.spec(
                    card = activeCard,
                    userName = userName,
                    regexScripts = emptyList(),
                    presetMessages = emptyList(),
                    worldInfoAtDepth = emptyMap(),
                    generationType = "quiet",
                    prompt = PromptPatch(
                        stablePersonaText = promptParts.stableSystemPrompt,
                        turnContextText = promptParts.turnContextSnapshot,
                        postHistoryText = promptParts.postHistoryInstructions
                    )
                )
                val preparedTurn = if (binding.ref.isNative) {
                    TavernPreparedTurnFactory.prepare(turnSpec)
                } else {
                    checkNotNull(loyeaApplication).prepareTavernPersonaTurn(
                        lease = checkNotNull(personaLease),
                        ref = binding.ref,
                        input = PluginTurnInput(
                            sessionId = sessionId,
                            turnId = "background-greeting-$id",
                            turnIndex = history.count { it.sender == Sender.USER }.toLong(),
                            userName = userName,
                            history = history.mapIndexed { index, message ->
                                ConversationText(
                                    id = message.id.ifBlank { "history-$index" },
                                    role = if (message.sender == Sender.USER) ChatRole.USER else ChatRole.ASSISTANT,
                                    content = message.content
                                )
                            },
                            generationType = "quiet"
                        ),
                        spec = turnSpec
                    )
                }

                // 4. Prepare Prompt
                val systemPrompt = BackgroundPromptTemplates.greetingSystem(
                    preparedTurn.plan.prompt.stablePersonaText,
                    userName
                )
                val eventInput = BackgroundPromptTemplates.greetingEventInput(
                    preparedTurn.plan.prompt.turnContextText
                )
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
                        history = requestHistory,
                        generation = preparedTurn.plan.generation
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext Result.retry()
                }

                if (generatedText.isBlank()) return@withContext Result.retry()
                val finalText = generatedText.trim()
                val historyText = requestHistory.joinToString("\n") { it.content }
                val newMsg = Message(
                    id = ChatStorageManager.backgroundGreetingMessageId(operationId),
                    content = finalText,
                    sender = Sender.AI,
                    characterId = activeCard.id
                )
                val committed = try {
                    storageManager.commitBackgroundGreeting(
                        operationId = operationId,
                        binding = binding,
                        message = newMsg,
                        promptTokens = if (hasRealUsage) accumulatedPrompt
                        else estimateTokens(systemPrompt) + estimateTokens(historyText),
                        completionTokens = if (hasRealUsage) accumulatedCompletion else estimateTokens(generatedText),
                        lastActiveTime = System.currentTimeMillis()
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("GreetingWorker", "Greeting commit interrupted; the journal will be resumed.", e)
                    return@withContext Result.retry()
                }
                if (committed.status == BackgroundGreetingCommitStatus.STALE) {
                    Log.d("GreetingWorker", "Discarded greeting because the session persona changed.")
                    scheduleAfterSkippedGreeting("The session persona changed during generation.")
                    return@withContext Result.success()
                }

                // 5. Send Notification
                sendNotification(operationId, activeCard.name, committed.message?.content ?: finalText)

                // 6. 链式预定下一次随机延迟的主动问候（2 到 8 小时随机）
                val randomDelayMinutes = kotlin.random.Random.nextInt(120, 480).toLong()
                scheduleNextGreeting(randomDelayMinutes)
                Log.d("GreetingWorker", "Proactive greeting sent successfully. Next greeting scheduled in $randomDelayMinutes mins.")

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

    private fun scheduleNextGreeting(delayMinutes: Long) {
        enqueueScheduled(context, delayMinutes, androidx.work.ExistingWorkPolicy.REPLACE)
    }

    private fun scheduleAfterSkippedGreeting(reason: String) {
        val delayMinutes = kotlin.random.Random.nextInt(120, 480).toLong()
        scheduleNextGreeting(delayMinutes)
        Log.d("GreetingWorker", "$reason Next eligibility check is scheduled in $delayMinutes mins.")
    }

    private fun sendNotification(operationId: String, title: String, content: String) {
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

        notificationManager.notify(operationId.hashCode(), notification)
    }
}
