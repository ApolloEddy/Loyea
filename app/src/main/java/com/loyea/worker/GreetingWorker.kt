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
import com.loyea.MainActivity
import com.loyea.R
import com.loyea.perception.PhysicalContextManager
import com.loyea.plugin.companion.CompanionConfigStore
import com.loyea.plugin.companion.CompanionContract
import com.loyea.plugin.companion.CompanionGreetingLedger
import com.loyea.plugin.companion.CompanionPersona
import com.loyea.plugin.companion.CompanionProactiveGate
import com.loyea.plugin.companion.CompanionRuntime
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台主动问候 Worker：同一调度链上的两条互斥路径（审计 R-06）。
 *
 * - 陪伴模式开启 → 陪伴主动联系路径：CompanionProactiveGate 完整门控在生成前与
 *   提交前各执行一次；目标显式绑定陪伴会话（不读普通 prefs 的 current_session_id）；
 *   通知未授权不生成、不落盘；台账持久化限频与稳定事件 ID；通知路由回陪伴会话。
 * - 普通模式 → 宿主原有语义全保留：enable_background_greeting 总开关、00:00–07:00
 *   静默、随机间隔；陪伴会话绝不成为普通问候目标。
 */
class GreetingWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val companionConfig = CompanionConfigStore(context).load()
            if (companionConfig.enabled) {
                companionGreetingFlow(companionConfig)
            } else {
                normalGreetingFlow()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    // ============================= 陪伴主动联系路径 =============================

    private suspend fun companionGreetingFlow(configAtStart: com.loyea.plugin.companion.CompanionConfig): Result {
        val storageManager = ChatStorageManager(context)
        val llmClient = LlmClient()
        val ledger = CompanionGreetingLedger(context)
        val now = System.currentTimeMillis()

        suspend fun gate(nowMillis: Long, config: com.loyea.plugin.companion.CompanionConfig, session: ChatSession?): CompanionProactiveGate.GreetDecision {            val messages = if (session != null) storageManager.loadSessionMessages(session.id) else emptyList()
            val lastMsg = messages.lastOrNull()
            val lastUserAt = messages.lastOrNull { it.sender == Sender.USER }?.timestamp
            return CompanionProactiveGate.evaluate(
                config = config,
                ledger = ledger.load(),
                nowMillis = nowMillis,
                notificationGranted = notificationsGranted(),
                sessionExists = session != null,
                uiForeground = CompanionRuntime.uiForeground,
                lastUserMessageAt = lastUserAt,
                lastMessageIsUnansweredGreeting = lastMsg?.id?.startsWith(GREETING_ID_PREFIX) == true
            )
        }

        // 0. 稳定事件去重（审计 R-06）：上次提交后崩溃 → 消息已在库但台账未更新，补记不重发；
        //    半途崩溃的悬空待办作废，重走完整门控
        val pending = ledger.load().pendingEventId
        if (pending.isNotBlank()) {
            val config = CompanionConfigStore(context).load()
            val session = findCompanionSession(storageManager, config)
            val committed = session?.let { s ->
                storageManager.loadSessionMessages(s.id).firstOrNull { it.id == greetMessageId(pending) }
            }
            if (committed != null) {
                ledger.commitEvent(pending, committed.timestamp)
                Log.d("GreetingWorker", "Companion greeting $pending already committed; ledger repaired.")
                scheduleNextGreeting(60)
                return Result.success()
            }
            ledger.clearPending()
        }

        // 1. 生成前门控（AC-17~19：每个条件独立判定）
        val session = findCompanionSession(storageManager, configAtStart)
        when (val decision = gate(now, configAtStart, session)) {
            is CompanionProactiveGate.GreetDecision.Postpone -> {
                Log.d("GreetingWorker", "Companion greeting postponed: ${decision.reason}, retry in ${decision.retryAfterMinutes} min.")
                scheduleNextGreeting(decision.retryAfterMinutes)
                return Result.success()
            }
            CompanionProactiveGate.GreetDecision.Proceed -> Unit
        }
        val currentSession = session!!

        // 2. 渠道解析：不伪报成功，不静默换配置（Spec §19）
        val repository = com.loyea.storage.ApiConfigRepository(context)
        val activeConfig = when (val chat = repository.resolve(com.loyea.storage.ChannelId.CHAT)) {
            is com.loyea.storage.ChannelResolution.Ready -> chat.resolved.config
            else -> {
                Log.w("GreetingWorker", "CHAT channel not ready: $chat")
                scheduleNextGreeting(60)
                return Result.success()
            }
        }

        val allCards = storageManager.loadCharacterCards()
        val activeCard = allCards.find { it.id == CompanionContract.COMPANION_CHARACTER_ID }
            ?: CompanionPersona.buildCard(configAtStart.displayName, configAtStart.avatarUri)
        // R-08：问候使用陪伴设置里的被称呼名字
        val userName = configAtStart.effectiveUserName

        // 3. 物理上下文：会话感知开关为唯一依据（R-01：陪伴感知关闭 → 后台问候同样不采集）
        val sessionUsesSystemTime = currentSession.useSystemTime ?: false
        val physicalContext = if (sessionUsesSystemTime) {
            PhysicalContextManager(context).buildPhysicalContextString()
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
        val history = storageManager.loadSessionMessages(currentSession.id).takeLast(10)
        val systemPrompt = BackgroundPromptTemplates.greetingSystem(promptParts.stableSystemPrompt, userName)
        val eventInput = BackgroundPromptTemplates.greetingEventInput(promptParts.turnContextSnapshot)
        val requestHistory = history.map { m ->
            if (!m.imageUrl.isNullOrBlank()) {
                val tag = if (m.imageDesc.isNullOrBlank()) "[图片]" else "[图片｜${m.imageDesc}]"
                m.copy(content = (m.content.ifBlank { "" } + (if (m.content.isBlank()) "" else "\n") + tag))
            } else m
        } + Message(
            id = "background-greeting-event",
            content = eventInput,
            sender = Sender.USER,
            timestamp = eventTime
        )

        // 4. 登记稳定事件 ID 后再生成：崩溃重投可据消息是否已落盘去重
        val eventId = java.util.UUID.randomUUID().toString()
        ledger.beginEvent(eventId, System.currentTimeMillis())

        var generatedText = ""
        var accumulatedPrompt = 0L
        var accumulatedCompletion = 0L
        var hasRealUsage = false
        try {
            llmClient.sendChatCompletionStream(
                config = activeConfig.copy(enableReasoning = false),
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
            ledger.clearPending()
            return Result.retry()
        }
        if (generatedText.isBlank()) {
            ledger.clearPending()
            return Result.retry()
        }

        // 5. 提交前复核（审计 R-06）：同一门控函数 + 开关/会话从磁盘重读；
        //    生成期间用户关闭开关/免打扰开始/恢复换绑/重开删除 → 丢弃，绝不落盘
        val configAtCommit = CompanionConfigStore(context).load()
        val sessionAtCommit = findCompanionSession(storageManager, configAtCommit)
        when (val recheck = gate(System.currentTimeMillis(), configAtCommit, sessionAtCommit)) {
            is CompanionProactiveGate.GreetDecision.Postpone -> {
                Log.d("GreetingWorker", "Companion greeting discarded at commit: ${recheck.reason}.")
                ledger.clearPending()
                scheduleNextGreeting(recheck.retryAfterMinutes)
                return Result.success()
            }
            CompanionProactiveGate.GreetDecision.Proceed -> Unit
        }
        val commitSession = sessionAtCommit!!

        // 6. 落盘（稳定 ID：greet_<eventId>，重复投递不会产生第二条）
        val newMsg = Message(
            id = greetMessageId(eventId),
            content = generatedText.trim(),
            sender = Sender.AI,
            characterId = activeCard.id
        )
        val writeOk = storageManager.updateSessionMessages(commitSession.id) { currentMsgs ->
            if (currentMsgs.any { it.id == newMsg.id }) currentMsgs else currentMsgs + newMsg
        }
        if (!writeOk) {
            // 会话在生成期间被删除/换绑：防复活护栏拒绝写入，事件作废
            Log.w("GreetingWorker", "Companion greeting commit rejected (session gone).")
            ledger.clearPending()
            scheduleNextGreeting(60)
            return Result.success()
        }
        storageManager.updateSessionList { currentSessions ->
            currentSessions.map {
                if (it.id == commitSession.id) it.copy(lastActiveTime = System.currentTimeMillis()) else it
            }.sortedByDescending { it.lastActiveTime }
        }
        val historyText = requestHistory.joinToString("\n") { it.content }
        storageManager.updateSessionTokens(
            commitSession.id,
            promptTokens = if (hasRealUsage) accumulatedPrompt else estimateTokens(systemPrompt) + estimateTokens(historyText),
            completionTokens = if (hasRealUsage) accumulatedCompletion else estimateTokens(generatedText),
            lastContextTokens = null
        )
        ledger.commitEvent(eventId, System.currentTimeMillis())

        // 7. 通知路由回陪伴会话（DATA-04/R-06：Intent 携带陪伴目标标记）
        sendCompanionNotification(activeCard.name, generatedText.trim(), eventId)

        // 8. 顺延下一次尝试；真实频率由门控（每日 2 条 / ≥4h）收敛
        val jitterMinutes = kotlin.random.Random.nextInt(60, 150).toLong()
        scheduleNextGreeting(jitterMinutes)
        Log.d("GreetingWorker", "Companion greeting sent. Next attempt in $jitterMinutes min.")
        return Result.success()
    }

    private suspend fun findCompanionSession(
        storageManager: ChatStorageManager,
        config: com.loyea.plugin.companion.CompanionConfig
    ): ChatSession? {
        val sessions = storageManager.loadSessionList()
        val byBinding = sessions.firstOrNull { it.id == config.sessionId }
        if (byBinding != null && CompanionContract.isCompanionCharacter(byBinding.characterId)) return byBinding
        // 绑定声明缺失/污染（历史版本）：按归属兜底回绑，不做静默重建
        return sessions.firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun greetMessageId(eventId: String) = "${GREETING_ID_PREFIX}$eventId"

    private fun sendCompanionNotification(title: String, content: String, eventId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "loyea_greetings"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "Loyea Proactive Greetings", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // 陪伴通知点击 → 直接回到陪伴模式与陪伴会话（通知路由合同，审计 R-06）
            putExtra(MainActivity.EXTRA_OPEN_COMPANION, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // 锁屏与通知历史不展示内容，防止健康/情感类问候语被他人窥见
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        notificationManager.notify(eventId.hashCode(), notification)
    }

    // ============================= 普通模式路径（宿主原语义） =============================

    private suspend fun normalGreetingFlow(): Result {
        val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)

        // 0. 前置判断：检查用户是否开启了后台主动问候
        val enableBgGreeting = prefs.getBoolean("enable_background_greeting", true)
        if (!enableBgGreeting) {
            Log.d("GreetingWorker", "Background greeting is disabled by user.")
            return Result.success()
        }

        // 0.1 陪伴会话绝不作为普通问候目标（归属过滤，审计 R-06）
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
            return Result.success()
        }

        val storageManager = ChatStorageManager(context)
        val llmClient = LlmClient()

        // 1. Get API config（经 ApiConfigRepository 统一解析 CHAT 通道——不直读加密库/散装键，Spec §19；
        //    不再 "?: firstOrNull()" 静默换配置——解析失败顺延重试，绝不把问候请求发给另一 Provider）
        val repository = com.loyea.storage.ApiConfigRepository(context)
        val activeConfig = when (val chat = repository.resolve(com.loyea.storage.ChannelId.CHAT)) {
            is com.loyea.storage.ChannelResolution.Ready -> chat.resolved.config
            else -> {
                Log.w("GreetingWorker", "CHAT channel not ready: $chat")
                scheduleNextGreeting(60)
                return Result.success()
            }
        }

        // 2. Get active session and character
        var sessionId = prefs.getString("current_session_id", "") ?: ""
        val sessions = storageManager.loadSessionList().filter { !CompanionContract.isCompanionCharacter(it.characterId) }

        if (sessionId.isBlank() || sessions.none { it.id == sessionId }) {
            sessionId = sessions.firstOrNull()?.id ?: run {
                scheduleNextGreeting(60)
                return Result.success()
            }
        }

        val currentSession = sessions.find { it.id == sessionId } ?: run {
            scheduleNextGreeting(60)
            return Result.success()
        }

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
            PhysicalContextManager(context).buildPhysicalContextString()
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
        // 图片消息以 [图片｜自动图注] 形式进入问候历史（无图注时退回占位）
        val appLangForHistory = prefs.getString("app_language", "zh") ?: "zh"
        val requestHistory = history.map { m ->
            if (!m.imageUrl.isNullOrBlank()) {
                val tag = if (m.imageDesc.isNullOrBlank()) (if (appLangForHistory == "en") "[Image]" else "[图片]")
                    else (if (appLangForHistory == "en") "[Image | ${m.imageDesc}]" else "[图片｜${m.imageDesc}]")
                m.copy(content = (m.content.ifBlank { "" } + (if (m.content.isBlank()) "" else "\n") + tag))
            } else m
        } + Message(
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
            return Result.retry()
        }

        if (generatedText.isBlank()) return Result.retry()

        // 后台主动问候计入解析出的那个会话用量（系统调用）；lastContext 仅主聊天流写，这里保持 null
        val historyText = requestHistory.joinToString("\n") { it.content }
        storageManager.updateSessionTokens(
            sessionId,
            promptTokens = if (hasRealUsage) accumulatedPrompt else estimateTokens(systemPrompt) + estimateTokens(historyText),
            completionTokens = if (hasRealUsage) accumulatedCompletion else estimateTokens(generatedText),
            lastContextTokens = null
        )

        // 4. Save to chat（防复活护栏：会话被删则写入被拒，不再重建旧会话消息文件）
        val newMsg = Message(
            id = "${System.currentTimeMillis()}_greeting",
            content = generatedText.trim(),
            sender = Sender.AI,
            characterId = activeCard.id
        )
        val writeOk = storageManager.updateSessionMessages(sessionId) { currentMsgs ->
            currentMsgs + newMsg
        }
        if (!writeOk) {
            Log.w("GreetingWorker", "Normal greeting commit rejected (session gone).")
            scheduleNextGreeting(60)
            return Result.success()
        }

        // Update session lastActiveTime atomically
        storageManager.updateSessionList { currentSessions ->
            currentSessions.map {
                if (it.id == sessionId) it.copy(lastActiveTime = System.currentTimeMillis()) else it
            }.sortedByDescending { it.lastActiveTime }
        }

        // 5. Send Notification
        sendNormalNotification(activeCard.name, generatedText.trim())

        // 6. 链式预定下一次随机延迟的主动问候（2 到 8 小时随机）
        val randomDelayMinutes = kotlin.random.Random.nextInt(120, 480).toLong()
        scheduleNextGreeting(randomDelayMinutes)
        Log.d("GreetingWorker", "Proactive greeting sent successfully. Next greeting scheduled in $randomDelayMinutes mins.")

        return Result.success()
    }

    private fun sendNormalNotification(title: String, content: String) {
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
            .setSmallIcon(R.drawable.ic_notification)
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

    companion object {
        /** 陪伴主动问候消息 ID 前缀：稳定事件 ID + 未回应判定 + 去重共用。 */
        const val GREETING_ID_PREFIX = "greet_"
    }
}
