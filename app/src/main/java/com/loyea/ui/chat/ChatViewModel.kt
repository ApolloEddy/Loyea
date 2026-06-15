package com.loyea.ui.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.ui.settings.ApiConfig
import com.loyea.ui.settings.ThemeMode
import com.loyea.mcp.McpServerConfig
import com.loyea.mcp.McpServerStatus
import com.loyea.mcp.McpConfigStorage
import com.loyea.mcp.McpManager
import com.loyea.mcp.McpTool
import com.loyea.perception.HapticManager
import com.loyea.perception.PhysicalContextManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import java.util.Timer
import java.util.TimerTask
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.workDataOf
import androidx.work.WorkInfo


class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
    private val storageManager = ChatStorageManager(context)
    private val llmClient = LlmClient()
    private val sessionDrafts = mutableMapOf<String, String>()

    private val mcpManager = McpManager(application)
    val mcpStates: StateFlow<Map<String, McpServerStatus>> = mcpManager.serverStates

    private val hapticManager = HapticManager(application)
    val perceptionManager = PhysicalContextManager(context)

    var mcpConfigList = mutableStateOf<List<McpServerConfig>>(emptyList())
        private set

    // 1. 全局亮暗色主题管理
    var themeMode = mutableStateOf(ThemeMode.SYSTEM)
        private set

    // 2. 全局自定义用户名管理
    var userName = mutableStateOf("Loyea Developer")
        private set

    // 3. 全局 API 连接配置管理
    var apiConfigList = mutableStateOf<List<ApiConfig>>(emptyList())
        private set
    var activeConfigId = mutableStateOf("")
        private set

    val activeApiConfig = derivedStateOf {
        apiConfigList.value.find { it.id == activeConfigId.value } ?: ApiConfig(
            id = "default",
            name = "Default",
            provider = "DeepSeek",
            apiUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            modelName = "deepseek-v4-pro"
        )
    }

    // 4. 全局语言配置管理
    var appLanguage = mutableStateOf("zh")
        private set

    // 5. 全局气泡颜色配置管理
    var userBubbleColor = mutableStateOf("")
        private set

    // 6. 会话列表管理
    var sessions = mutableStateOf<List<ChatSession>>(emptyList())
        private set
    var currentSessionId = mutableStateOf("")
        private set

    val activeSession = derivedStateOf {
        sessions.value.find { it.id == currentSessionId.value }
    }

    // 7. 消息列表状态管理
    var messages = mutableStateOf<List<Message>>(emptyList())
        private set

    // 8. 角色卡片列表与当前角色卡片
    var characterCardList = mutableStateOf<List<CharacterCard>>(emptyList())
        private set

    val activeCharacterCard = derivedStateOf {
        val currentSession = sessions.value.find { it.id == currentSessionId.value }
        val charId = currentSession?.characterId ?: "char_loyea_default"
        characterCardList.value.find { it.id == charId }
            ?: characterCardList.value.firstOrNull { it.id == "char_loyea_default" }
            ?: TavernCardParser.getBuiltInCards().first()
    }

    // 9. 思考/请求状态
    var isThinking = mutableStateOf(false)
        private set
    var isMcpRunning = mutableStateOf(false)
        private set

    private var responseJob: kotlinx.coroutines.Job? = null

    fun stopResponse() {
        responseJob?.cancel()
        isThinking.value = false
        isMcpRunning.value = false
        val lastMsg = messages.value.lastOrNull()
        if (lastMsg != null && lastMsg.sender == Sender.AI && (lastMsg.isStillThinking || isThinking.value)) {
            val updated = messages.value.map { msg ->
                if (msg.id == lastMsg.id) msg.copy(isStillThinking = false) else msg
            }
            messages.value = updated
        }
    }

    // 10. Physical Sensor states
    var isWatchConnected = mutableStateOf(false)
        private set
    var isWatchMoving = mutableStateOf(false)
        private set
    var useRealLocation = mutableStateOf(false)
        private set
    var mockLocation = mutableStateOf("")
        private set

    // 11. Tool Authorization States
    var toolAuthLocation = mutableStateOf(true)
        private set
    var toolAuthWeather = mutableStateOf(true)
        private set
    var toolAuthEnvironment = mutableStateOf(true)
        private set
    var toolAuthDevice = mutableStateOf(true)
        private set
    var toolAuthBluetoothActivity = mutableStateOf(true)
        private set
    var toolAuthHealth = mutableStateOf(true)
        private set
    var toolAuthHaptic = mutableStateOf(true)
        private set
    var enableBackgroundGreeting = mutableStateOf(true)
        private set

    // 12. 多模态配置状态
    var enableMultimodal = mutableStateOf(true)
        private set
    var enableStt = mutableStateOf(true)
        private set
    var enableTts = mutableStateOf(true)
        private set
    var ttsVoice = mutableStateOf("mimo-v2.5-tts-default")
        private set
    var enableAutoTts = mutableStateOf(false)
        private set
    var enableImageGen = mutableStateOf(true)
        private set
    var imageGenModel = mutableStateOf("dall-e-3")
        private set
        
    var visionConfigId = mutableStateOf("")
        private set
    var visionModelName = mutableStateOf("gpt-4o-mini")
        private set
    var sttConfigId = mutableStateOf("")
        private set
    var sttModelName = mutableStateOf("whisper-1")
        private set
    var ttsConfigId = mutableStateOf("")
        private set
    var ttsModelName = mutableStateOf("tts-1")
        private set
    var imageGenConfigId = mutableStateOf("")
        private set

    var ttsProviderTemplate = mutableStateOf("Auto")
        private set
    
    var ttsTemplates = mutableStateOf<List<TtsTemplate>>(emptyList())
        private set

    var isUpdatingTemplates = mutableStateOf(false)
        private set
    
    var updateTemplatesStatus = mutableStateOf("")
        private set

    // 13. 媒体录制与播放状态
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    var isRecording = mutableStateOf(false)
        private set
    var recordingDuration = mutableStateOf(0)
        private set
    var recordingAmplitude = mutableStateOf(0f)
        private set
    private var recordingTimer: Timer? = null

    private var mediaPlayer: MediaPlayer? = null
    private var currentFocusRequest: AudioFocusRequest? = null
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            viewModelScope.launch(Dispatchers.Main) {
                stopAudio()
            }
        }
    }
    
    var currentlyPlayingAudioId = mutableStateOf<String?>(null)
        private set

    // 图谱记忆管理器
    private val graphMemoryManager = com.loyea.perception.memory.GraphMemoryManager(context)

    // 长程图谱与声学情绪感知的开关
    var enableGraphMemory = mutableStateOf(true)
        private set
    var enableVoiceEmotionPerception = mutableStateOf(true)
        private set

    // 声学/语气临时情感缓存层，切换会话时会被自动清空
    var currentVoiceEmotion = mutableStateOf<String?>(null)
        private set

    // 长程图谱关系记忆列表数据（仅用于 UI 列表展现，每个会话物理隔离）
    var graphMemories = mutableStateOf<List<com.loyea.perception.memory.MemoryTriple>>(emptyList())
        private set

    private val ttsWriteMutex = kotlinx.coroutines.sync.Mutex()



    init {
        loadAllData()
        mcpManager.registerWebSearchProvider { query ->
            val activeConfig = activeApiConfig.value
            if (activeConfig.useIndependentSearch && activeConfig.searchApiKey.isNotBlank()) {
                llmClient.performIndependentWebSearch(activeConfig, query)
            } else {
                // 如果没有配置独立检索 Key，自动切换至备用免 Key 公共检索 (DuckDuckGo HTML 解析)
                llmClient.performFreeWebSearch(query)
            }
        }
        mcpManager.start()
        cleanOldTtsCacheAsync()
    }

    private fun loadAllData() {
        // 加载 MCP 配置
        mcpConfigList.value = McpConfigStorage(context).loadConfigs()

        // 加载主题
        val savedThemeName = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        themeMode.value = ThemeMode.valueOf(savedThemeName)

        // 加载用户名
        userName.value = prefs.getString("user_name", "Loyea Developer") ?: "Loyea Developer"

        // 加载 API 列表与激活 ID
        val savedConfigsJson = prefs.getString("api_config_list", "") ?: ""
        var list = if (savedConfigsJson.isNotBlank()) {
            try {
                val type = object : TypeToken<List<ApiConfig>>() {}.type
                val parsed = Gson().fromJson<List<ApiConfig>>(savedConfigsJson, type) ?: emptyList()
                var updated = false
                val upgraded = parsed.map { config ->
                    if (config.provider.equals("DeepSeek", ignoreCase = true)) {
                        if (config.modelName == "deepseek-chat") {
                            updated = true
                            config.copy(modelName = "deepseek-v4-flash")
                        } else if (config.modelName == "deepseek-reasoner") {
                            updated = true
                            config.copy(modelName = "deepseek-v4-pro")
                        } else {
                            config
                        }
                    } else {
                        config
                    }
                }
                if (updated) {
                    prefs.edit().putString("api_config_list", Gson().toJson(upgraded)).apply()
                }
                upgraded
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (list.isEmpty()) {
            // 默认连接列表 (不硬编码真实 API Key，避免安全审计泄漏)
            val deepseekPro = ApiConfig(
                id = "ds_v4_pro",
                name = "DeepSeek V4 Pro",
                provider = "DeepSeek",
                apiUrl = "https://api.deepseek.com/v1",
                apiKey = "",
                modelName = "deepseek-v4-pro",
                isEnabled = true,
                enableSearch = false,
                enableReasoning = true
            )
            val deepseekFlash = ApiConfig(
                id = "ds_v4_flash",
                name = "DeepSeek V4 Flash",
                provider = "DeepSeek",
                apiUrl = "https://api.deepseek.com/v1",
                apiKey = "",
                modelName = "deepseek-v4-flash",
                isEnabled = true,
                enableSearch = false,
                enableReasoning = false
            )
            val mimoPro = ApiConfig(
                id = "mimo_v25_pro",
                name = "MiMo 2.5 Pro",
                provider = "MiMo",
                apiUrl = "https://api.xiaomimimo.com/v1",
                apiKey = "",
                modelName = "mimo-v2.5-pro",
                isEnabled = true,
                enableSearch = true,
                enableReasoning = true
            )
            list = listOf(deepseekPro, deepseekFlash, mimoPro)
            prefs.edit().putString("api_config_list", Gson().toJson(list)).apply()
        }
        apiConfigList.value = list.filter { !it.provider.equals("Anthropic", ignoreCase = true) }

        val savedActiveId = prefs.getString("active_config_id", "") ?: ""
        activeConfigId.value = if (savedActiveId.isNotEmpty() && list.any { it.id == savedActiveId }) {
            savedActiveId
        } else {
            list.firstOrNull()?.id ?: ""
        }

        // 加载语言及气泡
        appLanguage.value = prefs.getString("app_language", "zh") ?: "zh"
        userBubbleColor.value = prefs.getString("user_bubble_color", "") ?: ""

        // 移入协程加载挂起 API
        viewModelScope.launch(Dispatchers.IO) {
            val cards = storageManager.loadCharacterCards()
            withContext(Dispatchers.Main) {
                characterCardList.value = cards
            }
            val watchConn = perceptionManager.watchProvider.isWatchConnected()
            val watchMov = perceptionManager.watchProvider.getMovementState() == "Moving"
            val useRealLoc = perceptionManager.locationProvider.isUsingRealLocation()
            val mockLoc = perceptionManager.locationProvider.getMockLocation()
            withContext(Dispatchers.Main) {
                isWatchConnected.value = watchConn
                isWatchMoving.value = watchMov
                useRealLocation.value = useRealLoc
                mockLocation.value = mockLoc
                // 异步加载会话列表
                viewModelScope.launch(Dispatchers.IO) {
                    loadSessions()
                }
            }
        }

        // 加载 SharedPreferences 中所有草稿
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("draft_") && value is String) {
                val sessionId = key.substringAfter("draft_")
                sessionDrafts[sessionId] = value
            }
        }

        // 加载多模态设置
        enableMultimodal.value = prefs.getBoolean("enable_multimodal", true)
        enableStt.value = prefs.getBoolean("enable_stt", true)
        enableTts.value = prefs.getBoolean("enable_tts", true)
        ttsVoice.value = prefs.getString("tts_voice", "mimo-v2.5-tts-default") ?: "mimo-v2.5-tts-default"
        enableAutoTts.value = prefs.getBoolean("enable_auto_tts", false)
        enableImageGen.value = prefs.getBoolean("enable_image_gen", true)
        imageGenModel.value = prefs.getString("image_gen_model", "dall-e-3") ?: "dall-e-3"
        
        visionConfigId.value = prefs.getString("vision_config_id", "") ?: ""
        visionModelName.value = prefs.getString("vision_model_name", "gpt-4o-mini") ?: "gpt-4o-mini"
        sttConfigId.value = prefs.getString("stt_config_id", "") ?: ""
        sttModelName.value = prefs.getString("stt_model_name", "whisper-1") ?: "whisper-1"
        ttsConfigId.value = prefs.getString("tts_config_id", "") ?: ""
        ttsModelName.value = prefs.getString("tts_model_name", "tts-1") ?: "tts-1"
        imageGenConfigId.value = prefs.getString("image_gen_config_id", "") ?: ""
        
        ttsProviderTemplate.value = prefs.getString("tts_provider_template", "Auto") ?: "Auto"
        
        val savedJson = prefs.getString("multimodal_templates_json", "") ?: ""
        if (savedJson.isNotBlank()) {
            loadTemplatesFromJson(savedJson)
        } else {
            loadDefaultTemplates()
        }

        // 加载工具授权状态
        toolAuthLocation.value = prefs.getBoolean("tool_auth_location", true)
        toolAuthWeather.value = prefs.getBoolean("tool_auth_weather", true)
        toolAuthEnvironment.value = prefs.getBoolean("tool_auth_environment", true)
        toolAuthDevice.value = prefs.getBoolean("tool_auth_device", true)
        toolAuthBluetoothActivity.value = prefs.getBoolean("tool_auth_bluetooth_activity", true)
        toolAuthHealth.value = prefs.getBoolean("tool_auth_health", true)
        toolAuthHaptic.value = prefs.getBoolean("tool_auth_haptic", true)
        enableBackgroundGreeting.value = prefs.getBoolean("enable_background_greeting", true)
        enableGraphMemory.value = prefs.getBoolean("enable_graph_memory", true)
        enableVoiceEmotionPerception.value = prefs.getBoolean("enable_voice_emotion_perception", true)
    }


    private suspend fun loadSessions() {
        var list = storageManager.loadSessionList()
        if (list.isEmpty()) {
            val defaultSession = ChatSession(
                id = System.currentTimeMillis().toString(),
                title = if (appLanguage.value == "en") "Welcome Chat" else "欢迎会话",
                lastActiveTime = System.currentTimeMillis(),
                characterId = "char_loyea_default"
            )
            list = listOf(defaultSession)
            storageManager.saveSessionList(list)
            
            val defaultMsgs = listOf(
                Message(
                    id = System.currentTimeMillis().toString(),
                    content = if (appLanguage.value == "en") "Hello! I'm Loyea. How can I help you today?" else "你好！我是 Loyea。今天我能帮您做点什么？",
                    sender = Sender.AI,
                    characterId = "char_loyea_default"
                )
            )
            storageManager.saveSessionMessages(defaultSession.id, defaultMsgs)
        }
        val sortedList = list.sortedByDescending { it.lastActiveTime }
        withContext(Dispatchers.Main) {
            sessions.value = sortedList
        }

        val savedSessionId = prefs.getString("current_session_id", "") ?: ""
        val initialSessionId = if (savedSessionId.isNotEmpty() && sortedList.any { it.id == savedSessionId }) {
            savedSessionId
        } else {
            sortedList.firstOrNull()?.id ?: ""
        }
        withContext(Dispatchers.Main) {
            selectSession(initialSessionId)
        }
    }

    fun selectSession(sessionId: String) {
        stopResponse()
        currentSessionId.value = sessionId
        currentVoiceEmotion.value = null // 清空临时情感缓存，防止信息混用污染
        prefs.edit().putString("current_session_id", sessionId).apply()
        viewModelScope.launch(Dispatchers.IO) {
            val msgs = storageManager.loadSessionMessages(sessionId)
            withContext(Dispatchers.Main) {
                messages.value = msgs
            }
        }
    }

    // --- 各种设置与配置修改方法 ---

    fun changeTheme(newTheme: ThemeMode) {
        themeMode.value = newTheme
        prefs.edit().putString("theme_mode", newTheme.name).apply()
    }

    fun saveUserName(newName: String) {
        userName.value = newName
        prefs.edit().putString("user_name", newName).apply()
    }

    fun saveApiConfigList(newList: List<ApiConfig>) {
        apiConfigList.value = newList
        prefs.edit().putString("api_config_list", Gson().toJson(newList)).apply()
    }

    fun selectActiveConfig(activeId: String) {
        activeConfigId.value = activeId
        prefs.edit().putString("active_config_id", activeId).apply()
        
        val activeConfig = apiConfigList.value.find { it.id == activeId }
        if (activeConfig != null) {
            prefs.edit()
                .putString("api_provider", activeConfig.provider)
                .putString("api_url", activeConfig.apiUrl)
                .putString("api_key", activeConfig.apiKey)
                .putString("api_model", activeConfig.modelName)
                .apply()
        }
    }

    fun changeAppLanguage(newLang: String) {
        appLanguage.value = newLang
        prefs.edit().putString("app_language", newLang).apply()
        // 重新加载会话以适配语言标题
        viewModelScope.launch(Dispatchers.IO) {
            loadSessions()
        }
    }

    fun changeUserBubbleColor(newColor: String) {
        userBubbleColor.value = newColor
        prefs.edit().putString("user_bubble_color", newColor).apply()
    }

    fun saveCharacterCardList(newList: List<CharacterCard>) {
        characterCardList.value = newList
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.saveCharacterCards(newList)
        }
    }

    fun toggleThoughtsExpanded(messageId: String) {
        val updated = messages.value.map { msg ->
            if (msg.id == messageId) msg.copy(
                isThoughtsExpanded = !msg.isThoughtsExpanded,
                hasUserToggledThoughts = true
            ) else msg
        }
        messages.value = updated
        val sessionId = currentSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            val finalMsgs = mergeAndSaveMessages(sessionId, updated)
            withContext(Dispatchers.Main) {
                messages.value = finalMsgs
            }
        }
    }

    // --- 会话与对话处理业务逻辑 ---

    fun deleteSession(deleteId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteSession(deleteId)
            val updatedSessions = storageManager.loadSessionList()
            withContext(Dispatchers.Main) {
                sessions.value = updatedSessions
                
                if (currentSessionId.value == deleteId) {
                    val nextSession = updatedSessions.firstOrNull()
                    if (nextSession != null) {
                        selectSession(nextSession.id)
                    } else {
                        // 如果全删了，强制建个新会话
                        val defaultSessionId = System.currentTimeMillis().toString()
                        val defaultSession = ChatSession(
                            id = defaultSessionId,
                            title = if (appLanguage.value == "en") "Welcome Chat" else "欢迎会话",
                            lastActiveTime = System.currentTimeMillis(),
                            characterId = "char_loyea_default"
                        )
                        val newList = listOf(defaultSession)
                        viewModelScope.launch(Dispatchers.IO) {
                            storageManager.saveSessionList(newList)
                            val defaultMsgs = listOf(
                                Message(
                                    id = System.currentTimeMillis().toString(),
                                    content = if (appLanguage.value == "en") "Hello! I'm Loyea. How can I help you today?" else "你好！我是 Loyea。今天我能帮您做点什么？",
                                    sender = Sender.AI,
                                    characterId = "char_loyea_default"
                                )
                            )
                            storageManager.saveSessionMessages(defaultSessionId, defaultMsgs)
                            withContext(Dispatchers.Main) {
                                sessions.value = newList
                                selectSession(defaultSessionId)
                            }
                        }
                    }
                }
            }
        }
    }

    fun createNewChat(selectedChar: CharacterCard) {
        val newSessionId = System.currentTimeMillis().toString()
        val newSession = ChatSession(
            id = newSessionId,
            title = if (appLanguage.value == "en") "New Chat" else "新会话",
            lastActiveTime = System.currentTimeMillis(),
            characterId = selectedChar.id
        )
        val updatedSessions = (listOf(newSession) + sessions.value).sortedByDescending { it.lastActiveTime }
        sessions.value = updatedSessions

        val welcomeText = selectedChar.firstMessage.ifBlank {
            if (appLanguage.value == "en") "Hello! I'm {{char}}. How can I help you today?" else "你好！我是 {{char}}。今天我能帮您做点什么？"
        }
        val formattedWelcome = PromptAssembler.formatMessageContent(welcomeText, selectedChar, userName.value)

        val initialMsgs = listOf(
            Message(
                id = System.currentTimeMillis().toString(),
                content = formattedWelcome,
                sender = Sender.AI,
                characterId = selectedChar.id
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.saveSessionList(updatedSessions)
            storageManager.saveSessionMessages(newSessionId, initialMsgs)
            withContext(Dispatchers.Main) {
                selectSession(newSessionId)
            }
        }
    }

    /**
     * 发送用户消息并触发 SSE 真实的流式输出，支持传入多模态图片/语音信息
     */
    fun sendMessage(inputText: String, imageUrl: String? = null, audioUrl: String? = null, audioDuration: Int = 0) {
        if (inputText.isBlank() && imageUrl.isNullOrBlank() && audioUrl.isNullOrBlank()) {
            return
        }

        // 拦截生图指令
        if (enableImageGen.value && inputText.trim().startsWith("/draw ")) {
            val prompt = inputText.trim().substringAfter("/draw ").trim()
            if (prompt.isNotEmpty()) {
                triggerImageGeneration(prompt)
                return
            }
        }

        isThinking.value = true
        val activeCard = activeCharacterCard.value
        
        // 发送新消息时，主动折叠之前的历史 Thinking 过程
        val collapsedHistory = messages.value.map { msg ->
            if (msg.sender == Sender.AI && msg.isThoughtsExpanded) {
                msg.copy(isThoughtsExpanded = false)
            } else {
                msg
            }
        }
        
        val userMsg = Message(
            id = System.currentTimeMillis().toString(),
            content = inputText,
            sender = Sender.USER,
            characterId = activeCard.id,
            imageUrl = imageUrl,
            audioUrl = audioUrl,
            audioDuration = audioDuration
        )
        val memoryMsgs = collapsedHistory + userMsg
        
        val sessionId = currentSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            val finalMsgs = mergeAndSaveMessages(sessionId, memoryMsgs)
            withContext(Dispatchers.Main) {
                messages.value = finalMsgs
                // 更新会话标题
                updateSessionTitleIfNeeded(sessionId, finalMsgs)
                // SSE 流式接收
                startAiResponseStream(sessionId, finalMsgs, activeCard)
            }
        }
    }


    private fun updateSessionTitleIfNeeded(sessionId: String, currentMessages: List<Message>) {
        val currentSession = sessions.value.find { it.id == sessionId }
        if (currentSession != null) {
            val isDefaultTitle = currentSession.title == "新会话" || 
                                currentSession.title == "New Chat" || 
                                currentSession.title.startsWith("欢迎会话") || 
                                currentSession.title.startsWith("Welcome Chat")
            
            val firstUserMsg = currentMessages.firstOrNull { it.sender == Sender.USER }
            if (isDefaultTitle && firstUserMsg != null) {
                val rawContent = firstUserMsg.content
                val cleanTitle = if (rawContent.length > 15) {
                    rawContent.take(15) + "..."
                } else {
                    rawContent
                }
                
                viewModelScope.launch(Dispatchers.IO) {
                    var updatedList: List<ChatSession> = emptyList()
                    storageManager.updateSessionList { diskSessions ->
                        val updated = diskSessions.map {
                            if (it.id == sessionId) {
                                it.copy(title = cleanTitle, lastActiveTime = System.currentTimeMillis())
                            } else {
                                it
                            }
                        }.sortedByDescending { it.lastActiveTime }
                        updatedList = updated
                        updated
                    }
                    withContext(Dispatchers.Main) {
                        sessions.value = updatedList
                    }
                }
            }
        }
    }

    private fun startAiResponseStream(
        sessionId: String,
        history: List<Message>,
        characterCard: CharacterCard
    ) {
        responseJob = viewModelScope.launch {
            isThinking.value = true
            isMcpRunning.value = false
            
            val aiMessageId = (System.currentTimeMillis() + 1).toString()
            val startTime = System.currentTimeMillis()

            // 1. 折叠历史中的思考
            val collapsedHistoryList = messages.value.map { msg ->
                if (msg.sender == Sender.AI && msg.isThoughtsExpanded) {
                    msg.copy(isThoughtsExpanded = false)
                } else {
                    msg
                }
            }

            // 2. 插入 AI 的占位气泡
            val placeholderAiMsg = Message(
                id = aiMessageId,
                content = "",
                sender = Sender.AI,
                thoughts = null,
                isThoughtsExpanded = true,
                thoughtDurationSeconds = 0,
                isStillThinking = true,
                characterId = characterCard.id
            )
            messages.value = collapsedHistoryList + placeholderAiMsg

            var currentList = messages.value
            var accumulatedContent = ""
            var accumulatedThoughts = ""
            var calculatedDuration: Int? = null

            // 获取 API 配置和 MCP 工具列表
            var apiConfig = activeApiConfig.value
            
            // 智能路由图片识图客户端与模型
            val hasImages = history.any { !it.imageUrl.isNullOrBlank() }
            if (hasImages && enableMultimodal.value) {
                val visionCfgId = visionConfigId.value
                val visionModel = visionModelName.value
                val targetVisionCfg = if (visionCfgId.isNotBlank()) {
                    apiConfigList.value.find { it.id == visionCfgId }
                } else {
                    null
                }
                if (targetVisionCfg != null) {
                    apiConfig = targetVisionCfg.copy(modelName = visionModel)
                } else {
                    apiConfig = apiConfig.copy(modelName = visionModel)
                }
            }

            // 判定当前角色是否是 Loyea 核心角色（仅 Loyea 支持物理感知和设备数据读取，隔离跨角色隐私泄露）
            // 策略调整：不再在每次发送消息时自动调出所有工具物理信息，而是将“系统当前时间”作为必要物理信息随消息附带。
            // 同时在上下文附上当前会话最近10分钟内成功调用的工具记录，过期数据将被自动丢弃。
            val now = System.currentTimeMillis()
            val tenMinutesAgo = now - 10 * 60 * 1000 // 10分钟前
            val recentToolCallsStr = history
                .filter { it.timestamp >= tenMinutesAgo }
                .flatMap { msg ->
                    val diffMs = now - msg.timestamp
                    val timeDesc = when {
                        diffMs < 30 * 1000 -> "刚刚"
                        diffMs < 60 * 1000 -> "1分钟内"
                        else -> "${diffMs / (60 * 1000)}分钟前"
                    }
                    msg.mcpCalls
                        .filter { it.status == McpStatus.SUCCESS && it.toolName != "send_voice_reply" }
                        .map { call ->
                            "- ${timeDesc}成功调用了 `${call.toolName}` 工具，返回结果为：${call.output.trim()}"
                        }
                }
                .joinToString("\n")

            val physicalContextData = buildString {
                if (enableVoiceEmotionPerception.value && !currentVoiceEmotion.value.isNullOrBlank()) {
                    append("[Acoustic Emotion]\n")
                    append("User's Voice Tone: ${currentVoiceEmotion.value}\n\n")
                }
                if (recentToolCallsStr.isNotBlank()) {
                    append("[RECENT PERCEPTION TOOL CALLS (10MIN CACHE)]\n")
                    append(recentToolCallsStr)
                }
            }.trim().takeIf { it.isNotEmpty() }

            val graphMemory = if (enableGraphMemory.value) {
                graphMemoryManager.retrieveRelationalContext(
                    characterId = characterCard.id,
                    sessionId = sessionId,
                    userInput = history.lastOrNull { it.sender == Sender.USER }?.content ?: ""
                )
            } else {
                null
            }

            val systemPrompt = PromptAssembler.assembleSystemPrompt(
                card = characterCard,
                userName = userName.value,
                useSystemTime = activeSession.value?.useSystemTime ?: false,
                physicalContext = physicalContextData,
                enableSearch = apiConfig.enableSearch,
                coreMemories = activeSession.value?.coreMemories ?: emptyList(),
                graphMemory = graphMemory
            )

            // 构建初始会话上下文
            var conversation = buildLlmConversation(systemPrompt, history)
            var round = 0
            val maxRounds = 5

            try {
                while (round < maxRounds) {
                    round++
                    var streamToolCalls = emptyList<LlmToolCall>()
                    var hasError = false
                    val availableMcpTools = mcpManager.getAggregateTools().filter { tool ->
                        val lowName = tool.name.lowercase()
                        when {
                            lowName.contains("web_search") -> apiConfig.enableSearch
                            lowName.contains("location") -> toolAuthLocation.value
                            lowName.contains("weather") || lowName.contains("forecast") -> toolAuthWeather.value
                            lowName.contains("light") || lowName.contains("noise") -> toolAuthEnvironment.value
                            lowName.contains("battery") || lowName.contains("wifi") -> toolAuthDevice.value
                            lowName.contains("bluetooth") || lowName.contains("activity") -> toolAuthBluetoothActivity.value
                            lowName.contains("health") -> toolAuthHealth.value
                            else -> true
                        }
                    }

                    // 执行流式调用
                    isThinking.value = true
                    val sendTools = if (hasImages && enableMultimodal.value) emptyList() else availableMcpTools
                    llmClient.sendChatCompletionStream(
                        config = apiConfig,
                        messages = conversation,
                        tools = sendTools
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.Thoughts -> {
                                accumulatedThoughts += event.text
                                currentList = messages.value.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            thoughts = accumulatedThoughts,
                                            isThoughtsExpanded = if (msg.hasUserToggledThoughts) msg.isThoughtsExpanded else true
                                        )
                                    } else {
                                        msg
                                    }
                                }
                                messages.value = currentList
                            }
                            is StreamEvent.Content -> {
                                try {
                                    accumulatedContent += event.text
                                    val hapticRegex = "\\[haptic:([a-zA-Z]+)\\]".toRegex()
                                    var hapticMatch = hapticRegex.find(accumulatedContent)
                                    while (hapticMatch != null) {
                                        val hapticType = hapticMatch.groupValues[1]
                                        if (toolAuthHaptic.value) {
                                            hapticManager.triggerHaptic(hapticType)
                                        }
                                        accumulatedContent = accumulatedContent.removeRange(hapticMatch.range)
                                        hapticMatch = hapticRegex.find(accumulatedContent)
                                    }
                                } catch (e: Exception) {
                                    Log.e("ChatViewModel", "Haptic parse error: ${e.message}", e)
                                }
                                
                                // 临时对准备渲染展示的内容进行半截过滤，不影响 accumulatedContent 的流拼接
                                var displayContent = accumulatedContent
                                try {
                                    val lastOpen = displayContent.lastIndexOf('[')
                                    if (lastOpen != -1 && lastOpen > displayContent.lastIndexOf(']')) {
                                        val tail = displayContent.substring(lastOpen)
                                        if ("[haptic:".startsWith(tail) || tail.startsWith("[haptic:")) {
                                            displayContent = displayContent.substring(0, lastOpen)
                                        }
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                }

                                if (calculatedDuration == null) {
                                    val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                    calculatedDuration = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                }
                                currentList = messages.value.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            content = displayContent,
                                            isStillThinking = false,
                                            thoughtDurationSeconds = calculatedDuration ?: 0
                                        )
                                    } else {
                                        msg
                                    }
                                }
                                messages.value = currentList
                            }
                            is StreamEvent.ToolCalls -> {
                                streamToolCalls = event.calls
                            }
                            is StreamEvent.Error -> {
                                currentList = messages.value.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            content = event.message,
                                            isStillThinking = false,
                                            isError = true
                                        )
                                    } else {
                                        msg
                                    }
                                }
                                messages.value = currentList
                                hasError = true
                            }
                            is StreamEvent.Done -> {
                                if (calculatedDuration == null) {
                                    val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                    calculatedDuration = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                }
                                // 完成后根据用户是否干预过，自动折叠 Thinking
                                currentList = messages.value.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            isStillThinking = streamToolCalls.isNotEmpty(),
                                            isThoughtsExpanded = if (msg.hasUserToggledThoughts) msg.isThoughtsExpanded else (streamToolCalls.isEmpty() && (calculatedDuration ?: 0) > 0),
                                            thoughtDurationSeconds = calculatedDuration ?: 0
                                        )
                                    } else {
                                        msg
                                    }
                                }
                                 messages.value = currentList
                                saveMessagesAsync(sessionId, currentList)

                                // AI 消息生成结束后，若开启了 TTS 且非工具流最终回合，则自动朗读
                                if (enableTts.value && enableAutoTts.value && streamToolCalls.isEmpty()) {
                                    playTts(aiMessageId, accumulatedContent)
                                }
                            }
                        }
                    }

                    if (hasError) {
                        isThinking.value = false
                        break
                    }

                    // 如果有工具调用，就处理工具调用，并在 nextConversation 里追加，然后继续下一轮
                    if (streamToolCalls.isNotEmpty()) {
                        isThinking.value = false
                        isMcpRunning.value = true
                        
                        val nextConversation = conversation.toMutableList()
                        nextConversation.add(
                            LlmChatMessage(
                                role = "assistant",
                                content = accumulatedContent.ifBlank { null },
                                toolCalls = streamToolCalls
                            )
                        )

                        // 逐个执行工具
                        for (toolCall in streamToolCalls) {
                            val displayCallId = "${toolCall.id}_${System.currentTimeMillis()}"
                            val parsedArgs = llmClient.parseArgumentsMap(toolCall.argumentsJson)
                            val customActionText = if (toolCall.name.lowercase().contains("web_search")) {
                                val query = parsedArgs.get("query")?.toString() ?: ""
                                if (query.isNotEmpty()) "搜索网页：$query" else "搜索网页"
                            } else {
                                translateToolName(toolCall.name)
                            }
                            val runningCall = McpCall(
                                id = displayCallId,
                                toolName = toolCall.name,
                                actionText = customActionText,
                                status = McpStatus.RUNNING,
                                input = toolCall.argumentsJson
                            )
                            
                            // 更新 UI 展示 RUNNING 状态
                            currentList = updateAiMessage(currentList, aiMessageId) {
                                it.copy(mcpCalls = it.mcpCalls + runningCall)
                            }
                            messages.value = currentList

                            val isVoiceReply = toolCall.name.equals("send_voice_reply", ignoreCase = true) || 
                                               toolCall.name.endsWith("__send_voice_reply", ignoreCase = true) || 
                                               toolCall.name.endsWith(".send_voice_reply", ignoreCase = true)

                            // 执行实际的工具请求
                            var toolOutput: String
                            var success: Boolean

                            val useSystemTime = activeSession.value?.useSystemTime ?: false
                            val isWebSearch = toolCall.name.equals("web_search", ignoreCase = true) || 
                                              toolCall.name.endsWith("__web_search", ignoreCase = true) || 
                                              toolCall.name.endsWith(".web_search", ignoreCase = true)

                            if (isVoiceReply) {
                                toolOutput = "语音回复已发送"
                                success = true
                            } else if (!useSystemTime && !isWebSearch) {
                                toolOutput = "Permission Denied: Physical perception is globally disabled by the user."
                                success = false
                            } else {
                                try {
                                    val result = mcpManager.callTool(
                                        prefixedToolName = toolCall.name,
                                        arguments = llmClient.parseArgumentsMap(toolCall.argumentsJson)
                                    )
                                    if (result.error != null) {
                                        toolOutput = "[MCP 错误] ${result.error.message}"
                                        success = false
                                    } else {
                                        toolOutput = result.result?.toString() ?: ""
                                        success = !toolOutput.startsWith("[MCP 错误]") && !toolOutput.startsWith("[MCP 异常]")
                                    }
                                } catch (e: Exception) {
                                    toolOutput = "[MCP 异常] ${e.localizedMessage ?: e.message ?: "未知错误"}"
                                    success = false
                                }
                            }
                            
                            // 更新 UI 展示 SUCCESS/FAILED 状态
                            currentList = updateMcpCall(currentList, aiMessageId, displayCallId) {
                                it.copy(
                                    status = if (success) McpStatus.SUCCESS else McpStatus.FAILED,
                                    output = toolOutput
                                )
                            }
                            messages.value = currentList

                            // 拦截 AI 主动发送语音消息工具，自动执行 TTS 合成、绑定与自动播放
                            if (success && isVoiceReply) {
                                val speechText = parsedArgs["text"]?.toString() ?: ""
                                val cleanedText = cleanTextForTts(speechText)
                                if (cleanedText.isNotBlank()) {
                                    viewModelScope.launch(Dispatchers.IO) {
                                        ttsWriteMutex.withLock {
                                            // 使用 displayCallId (工具调用唯一 ID) 作为文件名，防止多语音覆盖！
                                            val ttsFile = File(context.cacheDir, "tts_${displayCallId}.mp3")
                                            if (ttsFile.exists()) {
                                                try { ttsFile.delete() } catch (e: Exception) {}
                                            }

                                            val ttsCfgId = ttsConfigId.value
                                            val targetTtsConfig = if (ttsCfgId.isNotBlank()) {
                                                apiConfigList.value.find { it.id == ttsCfgId } ?: activeApiConfig.value
                                            } else {
                                                activeApiConfig.value
                                            }
                                            val voice = ttsVoice.value
                                            val ttsResult = llmClient.generateSpeech(targetTtsConfig, cleanedText, ttsModelName.value, voice, ttsFile)
                                            
                                            if (ttsResult.success && ttsFile.exists()) {
                                                val duration = getAudioDurationInSeconds(ttsFile)
                                                if (duration > 0) {
                                                    val voicePayload = "AUDIO_URL:${ttsFile.absolutePath}|DURATION:${duration}"
                                                    currentList = updateMcpCall(currentList, aiMessageId, displayCallId) {
                                                        it.copy(
                                                            status = McpStatus.SUCCESS,
                                                            output = voicePayload
                                                        )
                                                    }
                                                } else {
                                                    currentList = updateMcpCall(currentList, aiMessageId, displayCallId) {
                                                        it.copy(
                                                            status = McpStatus.FAILED,
                                                            output = "[错误] 语音解析失败"
                                                        )
                                                    }
                                                }
                                            } else {
                                                val err = ttsResult.errorMsg ?: "未知错误"
                                                currentList = updateMcpCall(currentList, aiMessageId, displayCallId) {
                                                    it.copy(
                                                        status = McpStatus.FAILED,
                                                        output = "[错误] 合成失败: $err"
                                                    )
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                messages.value = currentList
                                                saveMessagesAsync(currentSessionId.value, currentList)
                                            }
                                        }
                                    }
                                }
                            }

                            // 添加到会话上下文中
                            nextConversation.add(
                                LlmChatMessage(
                                    role = "tool",
                                    content = toolOutput,
                                    toolCallId = toolCall.id,
                                    name = toolCall.name
                                )
                            )
                        }

                        val executedToolsStr = streamToolCalls.joinToString("、") { 
                            val parsedArgs = llmClient.parseArgumentsMap(it.argumentsJson)
                            if (it.name.lowercase().contains("web_search")) {
                                val query = parsedArgs.get("query")?.toString() ?: ""
                                if (query.isNotEmpty()) "搜索网页：$query" else "搜索网页"
                            } else {
                                translateToolName(it.name)
                            }
                        }
                        accumulatedThoughts += "\n\n💡 *（已在此处调用接口感知状态：$executedToolsStr）*\n\n"
                        currentList = messages.value.map { msg ->
                            if (msg.id == aiMessageId) {
                                msg.copy(thoughts = accumulatedThoughts)
                            } else {
                                msg
                            }
                        }
                        messages.value = currentList

                        // 保存当前更新了 McpCalls 的消息到文件
                        saveMessagesAsync(sessionId, currentList)

                        // 更新 conversation 变量以进入下一次 while 循环
                        conversation = nextConversation
                        isMcpRunning.value = false
                    } else {
                        // 如果没有工具需要调用，完成最后一轮自动折叠逻辑 (针对内容结束)
                        currentList = currentList.map { msg ->
                            if (msg.id == aiMessageId) {
                                msg.copy(
                                    isThoughtsExpanded = if (msg.hasUserToggledThoughts) msg.isThoughtsExpanded else false
                                )
                            } else msg
                        }
                        messages.value = currentList
                        saveMessagesAsync(sessionId, currentList)
                        checkAndTriggerMemorySummaryAsync(sessionId)
                        break
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e("ChatViewModel", "FATAL in startAiResponseStream", t)
                val errMsg = when (t) {
                    is OutOfMemoryError -> "[崩溃防护] 内存不足，请重启应用"
                    is StackOverflowError -> "[崩溃防护] 调用栈溢出"
                    else -> "[错误] ${t.javaClass.simpleName}: ${t.message ?: "未知错误"}"
                }
                currentList = currentList.map { msg ->
                    if (msg.id == aiMessageId) {
                        msg.copy(
                            content = errMsg,
                            isStillThinking = false,
                            isError = true
                        )
                    } else msg
                }
                messages.value = currentList
            } finally {
                isThinking.value = false
                isMcpRunning.value = false
                currentVoiceEmotion.value = null // 情感分析重置
            }
        }
    }

    private fun translateToolName(name: String): String {
        val lowName = name.lowercase()
        return when {
            lowName.contains("get_location") || lowName.contains("current_location") -> "感知当前地理位置"
            lowName.contains("get_weather_forecast") || lowName.contains("forecast") -> "获取未来天气预报"
            lowName.contains("get_live_weather") || lowName.contains("weather") -> "获取当前气象状况"
            lowName.contains("get_environment_light") || lowName.contains("light") -> "检测环境光照强度"
            lowName.contains("get_battery_status") || lowName.contains("battery") -> "读取设备电池状态"
            lowName.contains("get_bluetooth_status") || lowName.contains("bluetooth") -> "检测蓝牙设备连接"
            lowName.contains("get_activity_state") || lowName.contains("activity") -> "识别系统运动状态"
            lowName.contains("get_health_data") || lowName.contains("health") -> "读取健康中心数据"
            lowName.contains("get_wifi_status") || lowName.contains("wifi") -> "检测 Wi-Fi 网络连接"
            lowName.contains("get_noise_level") || lowName.contains("noise") -> "测量环境噪音分贝"
            lowName.contains("send_voice_reply") -> "向你发送语音回复"
            lowName.contains("heart_rate") -> "调取实时心率"
            lowName.contains("steps") -> "查询今日步数"
            lowName.contains("sleep") -> "分析睡眠质量"
            lowName.contains("blood_pressure") -> "调取血压记录"
            lowName.contains("time") -> "同步系统时间"
            lowName.contains("physical_perception") -> "感知身体与环境状态"
            lowName.contains("web_search") || lowName.contains("google_search") -> "搜索实时互联网信息"
            else -> "执行操作: ${name.substringAfterLast(".")}"
        }
    }

    private fun buildLlmConversation(systemPrompt: String?, history: List<Message>): List<LlmChatMessage> {
        val list = mutableListOf<LlmChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            list.add(LlmChatMessage(role = "system", content = systemPrompt))
        }
        val filteredHistory = history.filter {
            (it.content.isNotBlank() || !it.imageUrl.isNullOrBlank()) && !it.content.startsWith("[错误]") && !it.content.startsWith("[Error]")
        }
        // 线性滑动窗口只取最新 20 条消息
        val recentHistory = filteredHistory.takeLast(20)
        recentHistory.forEach { msg ->
            val decoratedContent = msg.content
            list.add(
                LlmChatMessage(
                    role = if (msg.sender == Sender.USER) "user" else "assistant",
                    content = decoratedContent,
                    imageUrl = msg.imageUrl
                )
            )
        }
        return list
    }

    private fun updateAiMessage(
        currentList: List<Message>,
        aiMessageId: String,
        transform: (Message) -> Message
    ): List<Message> {
        return currentList.map { msg ->
            if (msg.id == aiMessageId) transform(msg) else msg
        }
    }

    private fun updateMcpCall(
        currentList: List<Message>,
        aiMessageId: String,
        callId: String,
        transform: (McpCall) -> McpCall
    ): List<Message> {
        return updateAiMessage(currentList, aiMessageId) { msg ->
            msg.copy(mcpCalls = msg.mcpCalls.map { call ->
                if (call.id == callId) transform(call) else call
            })
        }
    }

    private fun lockThoughtDuration(currentValue: Int?, startTime: Long, thoughts: String?): Int {
        return currentValue ?: if (!thoughts.isNullOrBlank()) {
            ((System.currentTimeMillis() - startTime) / 1000).toInt()
        } else {
            0
        }
    }

    private suspend fun mergeAndSaveMessages(sessionId: String, memoryMsgs: List<Message>): List<Message> {
        var finalMsgs = emptyList<Message>()
        storageManager.updateSessionMessages(sessionId) { diskMsgs ->
            val mergedMap = LinkedHashMap<String, Message>()
            for (msg in diskMsgs) {
                mergedMap[msg.id] = msg
            }
            for (msg in memoryMsgs) {
                mergedMap[msg.id] = msg
            }
            finalMsgs = mergedMap.values.toList()
            finalMsgs
        }
        return finalMsgs
    }

    private fun saveMessagesAsync(sessionId: String, currentList: List<Message>) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalMsgs = mergeAndSaveMessages(sessionId, currentList)
            withContext(Dispatchers.Main) {
                messages.value = finalMsgs
            }
        }
    }

    fun toggleCurrentSessionSystemTime() {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            var updatedList: List<ChatSession> = emptyList()
            storageManager.updateSessionList { diskSessions ->
                val updated = diskSessions.map { session ->
                    if (session.id == sessionId) {
                        session.copy(useSystemTime = session.useSystemTime != true)
                    } else {
                        session
                    }
                }
                updatedList = updated
                updated
            }
            withContext(Dispatchers.Main) {
                sessions.value = updatedList
            }
        }
    }

    fun saveMcpConfigs(newList: List<McpServerConfig>) {
        mcpConfigList.value = newList
        mcpManager.updateConfigs(newList)
    }

    fun getMcpToolsForServer(serverId: String): List<McpTool> {
        return mcpManager.getToolsForServer(serverId)
    }

    // --- 物理感知设置方法 ---
    fun setWatchConnected(connected: Boolean) {
        perceptionManager.watchProvider.setWatchConnected(connected)
        isWatchConnected.value = connected
    }

    fun setWatchMoving(moving: Boolean) {
        perceptionManager.watchProvider.setSimulationState(moving)
        isWatchMoving.value = moving
    }

    fun reconnectWatch() {
        // 先断开，再重新触发连接流程
        perceptionManager.watchProvider.setWatchConnected(false)
        perceptionManager.watchProvider.setWatchConnected(true)
        isWatchConnected.value = perceptionManager.watchProvider.isWatchConnected()
    }

    fun setUseRealLocation(use: Boolean) {
        perceptionManager.locationProvider.setUseRealLocation(use)
        useRealLocation.value = use
    }

    fun setMockLocation(location: String) {
        perceptionManager.locationProvider.setMockLocation(location)
        mockLocation.value = location
    }

    // --- 草稿箱记忆机制 ---
    fun getDraft(sessionId: String): String {
        return sessionDrafts[sessionId] ?: ""
    }

    fun saveDraft(sessionId: String, draft: String) {
        if (draft.isEmpty()) {
            clearDraft(sessionId)
        } else {
            sessionDrafts[sessionId] = draft
            prefs.edit().putString("draft_$sessionId", draft).apply()
        }
    }

    fun clearDraft(sessionId: String) {
        sessionDrafts.remove(sessionId)
        prefs.edit().remove("draft_$sessionId").apply()
    }

    private var messageCountSinceLastSummary = 0

    private fun checkAndTriggerMemorySummaryAsync(sessionId: String) {
        val enableMemory = prefs.getBoolean("enable_memory_consolidation", true)
        if (!enableMemory) return

        val triggerThreshold = prefs.getInt("memory_consolidation_trigger_count", 10)
        messageCountSinceLastSummary++
        if (messageCountSinceLastSummary >= triggerThreshold) {
            messageCountSinceLastSummary = 0
            
            val workData = workDataOf("session_id" to sessionId)
            val workRequest = OneTimeWorkRequestBuilder<com.loyea.worker.MemoryConsolidationWorker>()
                .setInputData(workData)
                .setExpedited(androidx.work.OutOfQuotaPolicy.valueOf("RUN_AS_FOREGROUND_SERVICE"))
                .build()
                
            WorkManager.getInstance(context).enqueueUniqueWork(
                "memory_consolidation_$sessionId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            // 监听后台提取状态，完成后重新加载本地数据更新 UI 状态
            viewModelScope.launch(Dispatchers.Main) {
                WorkManager.getInstance(context)
                    .getWorkInfoByIdFlow(workRequest.id)
                    .collect { workInfo ->
                        if (workInfo != null && (workInfo.state == WorkInfo.State.SUCCEEDED || workInfo.state == WorkInfo.State.FAILED)) {
                            val sessionsList = withContext(Dispatchers.IO) {
                                storageManager.loadSessionList()
                            }
                            sessions.value = sessionsList
                        }
                    }
            }
        }
    }

    fun updateCoreMemories(sessionId: String, memories: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.updateSessionCoreMemories(sessionId, memories)
            withContext(Dispatchers.Main) {
                sessions.value = sessions.value.map { session ->
                    if (session.id == sessionId) {
                        session.copy(coreMemories = memories)
                    } else {
                        session
                    }
                }
            }
        }
    }

    fun triggerManualMemorySummary() {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        
        val workData = workDataOf("session_id" to sessionId)
        val workRequest = OneTimeWorkRequestBuilder<com.loyea.worker.MemoryConsolidationWorker>()
            .setInputData(workData)
            .setExpedited(androidx.work.OutOfQuotaPolicy.valueOf("RUN_AS_FOREGROUND_SERVICE"))
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            "memory_consolidation_$sessionId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        viewModelScope.launch(Dispatchers.Main) {
            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    if (workInfo != null && (workInfo.state == WorkInfo.State.SUCCEEDED || workInfo.state == WorkInfo.State.FAILED)) {
                        val sessionsList = withContext(Dispatchers.IO) {
                            storageManager.loadSessionList()
                        }
                        sessions.value = sessionsList
                    }
                }
        }
    }

    fun updateBackgroundGreeting(enabled: Boolean) {
        enableBackgroundGreeting.value = enabled
        prefs.edit().putBoolean("enable_background_greeting", enabled).apply()
    }

    fun updateToolAuth(key: String, enabled: Boolean) {
        when (key) {
            "tool_auth_location" -> toolAuthLocation.value = enabled
            "tool_auth_weather" -> toolAuthWeather.value = enabled
            "tool_auth_environment" -> toolAuthEnvironment.value = enabled
            "tool_auth_device" -> toolAuthDevice.value = enabled
            "tool_auth_bluetooth_activity" -> toolAuthBluetoothActivity.value = enabled
            "tool_auth_health" -> toolAuthHealth.value = enabled
            "tool_auth_haptic" -> toolAuthHaptic.value = enabled
        }
        prefs.edit().putBoolean(key, enabled).apply()
    }

    fun updateMultimodalSetting(key: String, value: Any) {
        when (key) {
            "enable_multimodal" -> {
                val v = value as Boolean
                enableMultimodal.value = v
                prefs.edit().putBoolean("enable_multimodal", v).apply()
            }
            "enable_stt" -> {
                val v = value as Boolean
                enableStt.value = v
                prefs.edit().putBoolean("enable_stt", v).apply()
            }
            "enable_tts" -> {
                val v = value as Boolean
                enableTts.value = v
                prefs.edit().putBoolean("enable_tts", v).apply()
            }
            "tts_voice" -> {
                val v = value as String
                ttsVoice.value = v
                prefs.edit().putString("tts_voice", v).apply()
            }
            "enable_auto_tts" -> {
                val v = value as Boolean
                enableAutoTts.value = v
                prefs.edit().putBoolean("enable_auto_tts", v).apply()
            }
            "enable_image_gen" -> {
                val v = value as Boolean
                enableImageGen.value = v
                prefs.edit().putBoolean("enable_image_gen", v).apply()
            }
            "image_gen_model" -> {
                val v = value as String
                imageGenModel.value = v
                prefs.edit().putString("image_gen_model", v).apply()
            }
            "vision_config_id" -> {
                val v = value as String
                visionConfigId.value = v
                prefs.edit().putString("vision_config_id", v).apply()
            }
            "vision_model_name" -> {
                val v = value as String
                visionModelName.value = v
                prefs.edit().putString("vision_model_name", v).apply()
            }
            "stt_config_id" -> {
                val v = value as String
                sttConfigId.value = v
                prefs.edit().putString("stt_config_id", v).apply()
            }
            "stt_model_name" -> {
                val v = value as String
                sttModelName.value = v
                prefs.edit().putString("stt_model_name", v).apply()
            }
            "tts_config_id" -> {
                val v = value as String
                ttsConfigId.value = v
                prefs.edit().putString("tts_config_id", v).apply()
            }
            "tts_model_name" -> {
                val v = value as String
                ttsModelName.value = v
                prefs.edit().putString("tts_model_name", v).apply()
            }
            "image_gen_config_id" -> {
                val v = value as String
                imageGenConfigId.value = v
                prefs.edit().putString("image_gen_config_id", v).apply()
            }
            "tts_provider_template" -> {
                val v = value as String
                ttsProviderTemplate.value = v
                prefs.edit().putString("tts_provider_template", v).apply()
            }
        }
    }

    fun updateGraphMemorySetting(enabled: Boolean) {
        enableGraphMemory.value = enabled
        prefs.edit().putBoolean("enable_graph_memory", enabled).apply()
    }

    fun updateVoiceEmotionPerceptionSetting(enabled: Boolean) {
        enableVoiceEmotionPerception.value = enabled
        prefs.edit().putBoolean("enable_voice_emotion_perception", enabled).apply()
        if (!enabled) {
            currentVoiceEmotion.value = null
        }
    }

    /**
     * 加载当前会话隔离的长程三元组（UI 列表管理）
     */
    fun loadGraphMemoriesForCurrentSession() {
        val sessionId = currentSessionId.value
        val characterId = activeCharacterCard.value?.id ?: "char_loyea_default"
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val filtered = graphMemoryManager.getTriplesForSession(characterId, sessionId)
            withContext(Dispatchers.Main) {
                graphMemories.value = filtered
            }
        }
    }

    /**
     * 删除单条图谱三元组记录，确保当前会话的物理隔离
     */
    fun deleteGraphMemoryTriple(tripleId: Long) {
        val sessionId = currentSessionId.value
        val characterId = activeCharacterCard.value?.id ?: "char_loyea_default"
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            graphMemoryManager.deleteTriple(tripleId)
            val filtered = graphMemoryManager.getTriplesForSession(characterId, sessionId)
            withContext(Dispatchers.Main) {
                graphMemories.value = filtered
            }
        }
    }

    /**
     * 一键清空当前会话隔离的所有关系图谱三元组
     */
    fun clearAllGraphMemoriesForCurrentSession() {
        val sessionId = currentSessionId.value
        val characterId = activeCharacterCard.value?.id ?: "char_loyea_default"
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            graphMemoryManager.clearMemoriesForSession(characterId, sessionId)
            withContext(Dispatchers.Main) {
                graphMemories.value = emptyList()
            }
        }
    }


    fun editMessage(messageId: String, newContent: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return

        stopResponse()

        viewModelScope.launch(Dispatchers.IO) {
            val diskMsgs = storageManager.loadSessionMessages(sessionId)
            val index = diskMsgs.indexOfFirst { it.id == messageId }
            if (index == -1) return@launch

            val targetMsg = diskMsgs[index]
            if (targetMsg.content.trim() == newContent.trim()) return@launch

            // 截断 index 之后的消息，只保留当前被编辑消息及之前的消息，并更新当前消息内容
            val truncatedMsgs = diskMsgs.subList(0, index + 1).mapIndexed { idx, msg ->
                if (idx == index) {
                    msg.copy(
                        content = newContent,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    msg
                }
            }

            storageManager.updateSessionMessages(sessionId) {
                truncatedMsgs
            }

            withContext(Dispatchers.Main) {
                messages.value = truncatedMsgs
                startAiResponseStream(sessionId, truncatedMsgs, activeCharacterCard.value)
            }
        }
    }

    fun startRecording() {
        if (isRecording.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = context.cacheDir
                audioFile = File(cacheDir, "record_${System.currentTimeMillis()}.m4a")
                
                val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44100)
                recorder.setAudioEncodingBitRate(96000)
                recorder.setOutputFile(audioFile!!.absolutePath)
                
                recorder.prepare()
                recorder.start()
                
                mediaRecorder = recorder
                
                withContext(Dispatchers.Main) {
                    isRecording.value = true
                    recordingDuration.value = 0
                    recordingAmplitude.value = 0f
                    
                    recordingTimer = Timer()
                    recordingTimer?.scheduleAtFixedRate(object : TimerTask() {
                        override fun run() {
                            viewModelScope.launch(Dispatchers.Main) {
                                recordingDuration.value += 1
                                try {
                                    val amp = mediaRecorder?.maxAmplitude ?: 0
                                    recordingAmplitude.value = amp.toFloat()
                                } catch (e: Exception) {}
                            }
                        }
                    }, 0, 100)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to start recording", e)
            }
        }
    }

    fun stopRecording(onFinished: (File?, Int) -> Unit) {
        if (!isRecording.value) return
        recordingTimer?.cancel()
        recordingTimer = null
        
        viewModelScope.launch(Dispatchers.IO) {
            var durationSec = 0
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null
                
                durationSec = recordingDuration.value / 10
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to stop recording", e)
            }
            
            withContext(Dispatchers.Main) {
                isRecording.value = false
                val file = audioFile
                audioFile = null
                onFinished(file, durationSec)
            }
        }
    }

    private fun cleanTextForTts(rawText: String): String {
        if (rawText.isBlank()) return ""
        var text = rawText
        // 1. 剔除 <think> 和 </think> 标签及其内部的思考内容
        text = text.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        // 2. 剔除 XML 标签本身，如 <tool_call ...>...</tool_call>
        text = text.replace(Regex("<[^>]+>"), "")
        // 3. 剔除 Markdown 代码块 (``` ... ```)
        text = text.replace(Regex("```[\\s\\S]*?```"), "")
        // 4. 剔除行内代码 (`code`)
        text = text.replace(Regex("`([^`]+)`"), "$1")
        // 5. 替换 Markdown 链接 [链接文本](链接URL) -> 仅保留链接文本
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
        // 6. 替换 Markdown 图片 ![描述](链接URL) -> 移除
        text = text.replace(Regex("!\\[([^\\]]+)\\]\\([^\\)]+\\)"), "")
        // 7. 剔除 Markdown 格式符号：行首的 #, >, -, +, * 
        text = text.replace(Regex("(?m)^[#>\\-\\+\\*\\s]+"), "")
        // 行中的加粗、斜体、删除线修饰符
        text = text.replace(Regex("\\*\\*|\\*|__|_|~~|=="), "")
        // 8. 剔除 [haptic:类型] 物理震动等系统级占位符
        text = text.replace(Regex("\\[haptic:[^\\]]+\\]"), "")
        return text.trim()
    }

    private fun getAudioDurationInSeconds(file: File): Int {
        return try {
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.prepare()
            val durationMs = player.duration
            player.release()
            Math.max(1, (durationMs + 500) / 1000)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ChatViewModel", "音频解析失败，文件可能损坏，进行安全清理: ${file.absolutePath}")
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            0
        }
    }

    private fun cleanOldTtsCacheAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDirFile = context.cacheDir
                if (cacheDirFile.exists() && cacheDirFile.isDirectory) {
                    val ttsFiles = cacheDirFile.listFiles { file ->
                        file.isFile && file.name.startsWith("tts_") && file.name.endsWith(".mp3")
                    }
                    if (ttsFiles != null) {
                        val currentTime = System.currentTimeMillis()
                        val threeDaysInMillis = 3L * 24 * 60 * 60 * 1000
                        var deletedCount = 0
                        for (file in ttsFiles) {
                            val diff = currentTime - file.lastModified()
                            if (diff > threeDaysInMillis) {
                                try {
                                    if (file.delete()) {
                                        deletedCount++
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        if (deletedCount > 0) {
                            Log.d("ChatViewModel", "已自动清理 ${deletedCount} 个 3 天前的历史语音缓存 mp3 文件")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun playMcpVoice(mcpCallId: String) {
        if (currentlyPlayingAudioId.value == mcpCallId) {
            stopAudio()
            return
        }
        stopAudio()
        
        val ttsFile = File(context.cacheDir, "tts_${mcpCallId}.mp3")
        if (ttsFile.exists() && ttsFile.length() > 0) {
            playAudioFile(mcpCallId, ttsFile)
            return
        } else if (ttsFile.exists()) {
            Log.w("ChatViewModel", "语音文件损坏或大小为0，尝试删除并重新合成: ${ttsFile.absolutePath}")
            try { ttsFile.delete() } catch (e: Exception) {}
        }

        // 走到这里说明文件不存在或者已经损坏，需要自愈重新拉取合成
        var targetCall: McpCall? = null
        var parentMessageId: String? = null
        messages.value.forEach { msg ->
            val call = msg.mcpCalls.find { it.id == mcpCallId }
            if (call != null) {
                targetCall = call
                parentMessageId = msg.id
            }
        }

        if (targetCall != null && parentMessageId != null) {
            val inputJson = targetCall?.input ?: ""
            val parsedArgs = llmClient.parseArgumentsMap(inputJson)
            val speechText = parsedArgs["text"]?.toString() ?: ""
            val cleanedText = cleanTextForTts(speechText)
            
            if (cleanedText.isNotBlank()) {
                // 将 UI 状态更新为 RUNNING 占位态
                messages.value = messages.value.map { msg ->
                    if (msg.id == parentMessageId) {
                        msg.copy(mcpCalls = msg.mcpCalls.map { c ->
                            if (c.id == mcpCallId) c.copy(status = McpStatus.RUNNING, output = "重新合成中...") else c
                        })
                    } else {
                        msg
                    }
                }

                // 启动异步线程重新执行合成
                viewModelScope.launch(Dispatchers.IO) {
                    ttsWriteMutex.withLock {
                        val ttsCfgId = ttsConfigId.value
                        val targetTtsConfig = if (ttsCfgId.isNotBlank()) {
                            apiConfigList.value.find { it.id == ttsCfgId } ?: activeApiConfig.value
                        } else {
                            activeApiConfig.value
                        }
                        val voice = ttsVoice.value
                        val ttsResult = llmClient.generateSpeech(targetTtsConfig, cleanedText, ttsModelName.value, voice, ttsFile)
                        
                        withContext(Dispatchers.Main) {
                            if (ttsResult.success && ttsFile.exists()) {
                                val duration = getAudioDurationInSeconds(ttsFile)
                                if (duration > 0) {
                                    val voicePayload = "AUDIO_URL:${ttsFile.absolutePath}|DURATION:${duration}"
                                    messages.value = messages.value.map { msg ->
                                        if (msg.id == parentMessageId) {
                                            msg.copy(mcpCalls = msg.mcpCalls.map { c ->
                                                if (c.id == mcpCallId) c.copy(status = McpStatus.SUCCESS, output = voicePayload) else c
                                            })
                                        } else {
                                            msg
                                        }
                                    }
                                    saveMessagesAsync(currentSessionId.value, messages.value)
                                    // 重新播放它
                                    playAudioFile(mcpCallId, ttsFile)
                                } else {
                                    messages.value = messages.value.map { msg ->
                                        if (msg.id == parentMessageId) {
                                            msg.copy(mcpCalls = msg.mcpCalls.map { c ->
                                                if (c.id == mcpCallId) c.copy(status = McpStatus.FAILED, output = "[错误] 语音解析失败") else c
                                            })
                                        } else {
                                            msg
                                        }
                                    }
                                }
                            } else {
                                val err = ttsResult.errorMsg ?: "未知错误"
                                messages.value = messages.value.map { msg ->
                                    if (msg.id == parentMessageId) {
                                        msg.copy(mcpCalls = msg.mcpCalls.map { c ->
                                            if (c.id == mcpCallId) c.copy(status = McpStatus.FAILED, output = "[错误] 重新合成失败: $err") else c
                                        })
                                    } else {
                                        msg
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Log.e("ChatViewModel", "无法获取合成文本，文本内容为空")
                android.widget.Toast.makeText(context, "无法获取该历史语音对应的原始文本", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.e("ChatViewModel", "无法找到对应的历史语音工具调用: $mcpCallId")
            android.widget.Toast.makeText(context, "未找到该语音对应的历史记录", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun playTts(messageId: String, text: String) {
        if (currentlyPlayingAudioId.value == messageId) {
            stopAudio()
            return
        }
        
        // 停止之前的播放
        stopAudio()
        
        val ttsFile = File(context.cacheDir, "tts_${messageId}.mp3")
        val sessionId = currentSessionId.value
        
        if (ttsFile.exists()) {
            val duration = getAudioDurationInSeconds(ttsFile)
            if (duration > 0) {
                messages.value = messages.value.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(audioUrl = ttsFile.absolutePath, audioDuration = duration)
                    } else {
                        msg
                    }
                }
                saveMessagesAsync(sessionId, messages.value)
                playAudioFile(messageId, ttsFile)
                return
            } else {
                Log.w("ChatViewModel", "音频缓存损坏已删除，重新合成")
            }
        }
        
        val cleanedText = cleanTextForTts(text)
        if (cleanedText.isBlank()) {
            android.widget.Toast.makeText(context, "文字内容为空，无法进行语音合成", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 设置消息为正在合成状态
        messages.value = messages.value.map { msg ->
            if (msg.id == messageId) msg.copy(isAudioSynthesizing = true) else msg
        }
        
        // 后台进行 TTS 合成
        viewModelScope.launch(Dispatchers.IO) {
            val ttsCfgId = ttsConfigId.value
            val targetTtsConfig = if (ttsCfgId.isNotBlank()) {
                apiConfigList.value.find { it.id == ttsCfgId } ?: activeApiConfig.value
            } else {
                activeApiConfig.value
            }
            val voice = ttsVoice.value
            val ttsResult = llmClient.generateSpeech(targetTtsConfig, cleanedText, ttsModelName.value, voice, ttsFile)
            
            withContext(Dispatchers.Main) {
                // 重置消息正在合成状态
                messages.value = messages.value.map { msg ->
                    if (msg.id == messageId) msg.copy(isAudioSynthesizing = false) else msg
                }
                
                if (ttsResult.success && ttsFile.exists()) {
                    val duration = getAudioDurationInSeconds(ttsFile)
                    if (duration > 0) {
                        messages.value = messages.value.map { msg ->
                            if (msg.id == messageId) {
                                msg.copy(audioUrl = ttsFile.absolutePath, audioDuration = duration)
                            } else {
                                msg
                            }
                        }
                        saveMessagesAsync(sessionId, messages.value)
                        playAudioFile(messageId, ttsFile)
                    } else {
                        android.widget.Toast.makeText(context, "合成文件解析失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val err = ttsResult.errorMsg ?: "未知错误"
                    Log.e("ChatViewModel", "TTS generation failed: $err")
                    android.widget.Toast.makeText(context, "语音合成失败: $err", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            currentFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            currentFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun playAudioFile(messageId: String, file: File) {
        // 在播放新音频前，必须强制同步清理掉旧的播放器和状态，保障互斥防冲
        stopAudio()

        // 申请音频焦点
        if (!requestAudioFocus()) {
            Log.w("ChatViewModel", "无法获取音频焦点，播放取消")
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopAudio()
                }
                setOnErrorListener { _, _, _ ->
                    stopAudio()
                    true
                }
            }
            currentlyPlayingAudioId.value = messageId
            // 更新消息列表里的正在播放状态
            messages.value = messages.value.map { msg ->
                if (msg.id == messageId) msg.copy(isAudioPlaying = true) else msg
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "播放音频文件发生异常: ${e.message}", e)
            android.widget.Toast.makeText(context, "音频播放失败: ${e.localizedMessage ?: e.message}", android.widget.Toast.LENGTH_SHORT).show()
            stopAudio()
        }
    }

    fun stopAudio() {
        val playingId = currentlyPlayingAudioId.value
        
        // 释放播放器资源
        try {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.setOnErrorListener(null)
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "停止播放器异常: ${e.message}")
        } finally {
            mediaPlayer = null
        }
        
        currentlyPlayingAudioId.value = null

        // 释放音频焦点
        abandonAudioFocus()
        
        // 更新 UI 状态
        if (playingId != null) {
            messages.value = messages.value.map { msg ->
                if (msg.id == playingId) msg.copy(isAudioPlaying = false) else msg
            }
        }
    }

    fun transcribeAndSendAudio(file: File, duration: Int, onFailed: () -> Unit = {}) {
        isThinking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val sttCfgId = sttConfigId.value
            val targetSttConfig = if (sttCfgId.isNotBlank()) {
                apiConfigList.value.find { it.id == sttCfgId } ?: activeApiConfig.value
            } else {
                activeApiConfig.value
            }
            val text = llmClient.transcribeAudio(targetSttConfig, file, sttModelName.value)
            
            // 开启了声学情绪感知时，根据语音输入识别一个模拟的语气/情绪状态
            if (enableVoiceEmotionPerception.value && !text.isNullOrBlank()) {
                val lowerText = text.lowercase()
                val detectedEmotion = when {
                    lowerText.contains("难过") || lowerText.contains("伤心") || lowerText.contains("哭") || lowerText.contains("委屈") -> "伤心"
                    lowerText.contains("生气") || lowerText.contains("愤怒") || lowerText.contains("讨厌") || lowerText.contains("烦") -> "生气"
                    lowerText.contains("开心") || lowerText.contains("高兴") || lowerText.contains("哈哈") || lowerText.contains("乐") -> "开心"
                    lowerText.contains("谢谢") || lowerText.contains("温柔") || lowerText.contains("喜欢") || lowerText.contains("乖") -> "温柔"
                    lowerText.contains("累") || lowerText.contains("困") || lowerText.contains("睡") -> "慵懒"
                    else -> "中性"
                }
                withContext(Dispatchers.Main) {
                    currentVoiceEmotion.value = detectedEmotion
                }
            } else {
                withContext(Dispatchers.Main) {
                    currentVoiceEmotion.value = null
                }
            }

            withContext(Dispatchers.Main) {
                isThinking.value = false
                if (!text.isNullOrBlank()) {
                    sendMessage(text, null, file.absolutePath, duration)
                } else {
                    onFailed()
                }
            }
        }
    }

    fun triggerImageGeneration(prompt: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return

        isThinking.value = true
        val activeCard = activeCharacterCard.value

        // 1. 发送消息
        val userMsg = Message(
            id = System.currentTimeMillis().toString(),
            content = "/draw $prompt",
            sender = Sender.USER,
            characterId = activeCard.id
        )
        // 2. AI 占位消息
        val aiMessageId = (System.currentTimeMillis() + 1).toString()
        val aiMsg = Message(
            id = aiMessageId,
            content = "正在为您生成图像，请稍候...",
            sender = Sender.AI,
            isStillThinking = true,
            characterId = activeCard.id
        )

        viewModelScope.launch(Dispatchers.IO) {
            val collapsedHistory = messages.value.map { msg ->
                if (msg.sender == Sender.AI && msg.isThoughtsExpanded) msg.copy(isThoughtsExpanded = false) else msg
            }
            val finalMsgs = mergeAndSaveMessages(sessionId, collapsedHistory + userMsg + aiMsg)
            withContext(Dispatchers.Main) {
                messages.value = finalMsgs
            }

            // 3. 调用生图
            val genCfgId = imageGenConfigId.value
            val targetGenConfig = if (genCfgId.isNotBlank()) {
                apiConfigList.value.find { it.id == genCfgId } ?: activeApiConfig.value
            } else {
                activeApiConfig.value
            }
            val modelName = imageGenModel.value
            val imageUrl = llmClient.generateImage(targetGenConfig, prompt, modelName)

            withContext(Dispatchers.Main) {
                isThinking.value = false
                if (imageUrl != null) {
                    // 异步下载到本地以便离线查看
                    viewModelScope.launch(Dispatchers.IO) {
                        val localImageFile = File(context.filesDir, "images/img_${System.currentTimeMillis()}.png")
                        localImageFile.parentFile?.mkdirs()
                        try {
                            val request = okhttp3.Request.Builder().url(imageUrl).build()
                            okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    response.body?.byteStream()?.use { input ->
                                        localImageFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                        } catch (e: java.lang.Exception) {
                            e.printStackTrace()
                        }

                        // 将最终图片和消息更新
                        withContext(Dispatchers.Main) {
                            val updatedContent = "AI 已为您生成图像，提示词：\"$prompt\""
                            val currentList = messages.value.map { msg ->
                                if (msg.id == aiMessageId) {
                                    msg.copy(
                                        content = updatedContent,
                                        isStillThinking = false,
                                        imageUrl = if (localImageFile.exists()) localImageFile.absolutePath else imageUrl
                                    )
                                } else {
                                    msg
                                }
                            }
                            messages.value = currentList
                            saveMessagesAsync(sessionId, currentList)
                        }
                    }
                } else {
                    // 生图失败
                    val currentList = messages.value.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(
                                content = "图像生成失败，请检查您的生图 API 配置或网络连接。",
                                isStillThinking = false,
                                isError = true
                            )
                        } else {
                            msg
                        }
                    }
                    messages.value = currentList
                    saveMessagesAsync(sessionId, currentList)
                }
            }
        }
    }

    fun startPerceptionSensors() {
        try {
            perceptionManager.activityProvider.startLocalSensorListening()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to start perception sensors", e)
        }
    }

    fun stopPerceptionSensors() {
        try {
            perceptionManager.activityProvider.stopLocalSensorListening()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to stop perception sensors", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        mcpManager.stop()
        stopPerceptionSensors()
        stopAudio()
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {}
    }

    private fun loadTemplatesFromJson(json: String) {
        try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, List<TtsTemplate>>>() {}.type
            val map: Map<String, List<TtsTemplate>> = Gson().fromJson(json, type)
            map["tts"]?.let {
                ttsTemplates.value = it
            }
        } catch (e: Exception) {
            e.printStackTrace()
            loadDefaultTemplates()
        }
    }

    private fun loadDefaultTemplates() {
        try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, List<TtsTemplate>>>() {}.type
            val map: Map<String, List<TtsTemplate>> = Gson().fromJson(DEFAULT_TEMPLATES_JSON, type)
            map["tts"]?.let {
                ttsTemplates.value = it
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun fetchTemplatesFromNetwork() {
        viewModelScope.launch(Dispatchers.IO) {
            isUpdatingTemplates.value = true
            updateTemplatesStatus.value = "正在从云端拉取最新模板配置..."
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val urls = listOf(
                    "https://cdn.jsdelivr.net/gh/ApolloEddy/Loyea@main/assets/multimodal_templates.json",
                    "https://raw.githubusercontent.com/ApolloEddy/Loyea/main/assets/multimodal_templates.json"
                )
                
                var success = false
                var jsonResult = ""
                var lastError = ""
                
                for (url in urls) {
                    if (success) break
                    try {
                        val request = okhttp3.Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrBlank() && body.contains("\"tts\"")) {
                                    jsonResult = body
                                    success = true
                                } else {
                                    lastError = "响应数据为空或格式不匹配"
                                }
                            } else {
                                lastError = "HTTP 错误 ${response.code}"
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: e.message ?: "网络超时"
                    }
                }
                
                if (success) {
                    prefs.edit().putString("multimodal_templates_json", jsonResult).apply()
                    withContext(Dispatchers.Main) {
                        loadTemplatesFromJson(jsonResult)
                        updateTemplatesStatus.value = "更新成功！已同步最新配置"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateTemplatesStatus.value = "更新失败: $lastError，已为您保留本地内置模板"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateTemplatesStatus.value = "更新失败: ${e.localizedMessage}，已为您保留本地内置模板"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isUpdatingTemplates.value = false
                }
            }
        }
    }
}

data class ModelCandidate(val id: String, val name: String)
data class VoiceCandidate(val id: String, val name: String)

data class TtsTemplate(
    val provider: String,
    val displayName: String,
    val defaultModel: String,
    val defaultVoice: String,
    val models: List<ModelCandidate>,
    val voices: List<VoiceCandidate>
)

private val DEFAULT_TEMPLATES_JSON = """
{
  "tts": [
    {
      "provider": "MiMo",
      "displayName": "小米 MiMo",
      "defaultModel": "mimo-v2.5-tts",
      "defaultVoice": "茉莉",
      "models": [
        {"id": "mimo-v2.5-tts", "name": "MiMo 语音合成 (v2.5)"},
        {"id": "mimo-v2.5-audio", "name": "MiMo 语音大模型"}
      ],
      "voices": [
        {"id": "茉莉", "name": "官方标准原声 (女)"},
        {"id": "白桦", "name": "白桦 (男)"},
        {"id": "冰糖", "name": "冰糖 (温柔女声)"},
        {"id": "苏打", "name": "苏打 (温柔男声)"},
        {"id": "Mia", "name": "Mia (美式女声)"},
        {"id": "Chloe", "name": "Chloe (美式女声)"},
        {"id": "Milo", "name": "Milo (美式男声)"},
        {"id": "Dean", "name": "Dean (美式男声)"},
        {"id": "mimo_default", "name": "MiMo 默认音色"}
      ]
    },
    {
      "provider": "OpenAI",
      "displayName": "OpenAI",
      "defaultModel": "tts-1",
      "defaultVoice": "alloy",
      "models": [
        {"id": "tts-1", "name": "tts-1 (标准流式)"},
        {"id": "tts-1-hd", "name": "tts-1-hd (高保真)"}
      ],
      "voices": [
        {"id": "alloy", "name": "Alloy (中性)"},
        {"id": "echo", "name": "Echo (中性偏男)"},
        {"id": "fable", "name": "Fable (富有戏剧性)"},
        {"id": "onyx", "name": "Onyx (深沉男声)"},
        {"id": "nova", "name": "Nova (活泼女声)"},
        {"id": "shimmer", "name": "Shimmer (专业女声)"}
      ]
    },
    {
      "provider": "Alibaba",
      "displayName": "阿里百炼 (DashScope)",
      "defaultModel": "cosyvoice-v3-flash",
      "defaultVoice": "longanyang",
      "models": [
        {"id": "cosyvoice-v3-flash", "name": "cosyvoice-v3-flash"},
        {"id": "cosyvoice-v3.5-plus", "name": "cosyvoice-v3.5-plus"},
        {"id": "cosyvoice-tg-v1", "name": "cosyvoice-tg-v1 (声音复刻)"},
        {"id": "sambert-zhichuan-v1", "name": "sambert-zhichuan-v1 (基础)"}
      ],
      "voices": [
        {"id": "longanyang", "name": "龙安阳 (标准男声)"},
        {"id": "longying", "name": "龙莹 (标准女声)"},
        {"id": "longwan", "name": "龙婉 (温柔女声)"},
        {"id": "longxiaoxia", "name": "龙小小 (可爱女童)"},
        {"id": "longxiaochun", "name": "龙小春 (活泼男童)"},
        {"id": "longshu", "name": "龙书 (成熟男声)"},
        {"id": "longjielao", "name": "龙姐唠 (粤语女声)"}
      ]
    },
    {
      "provider": "Volcengine",
      "displayName": "火山引擎 (豆包)",
      "defaultModel": "volcengine-tts",
      "defaultVoice": "female_emotion_1",
      "models": [
        {"id": "volcengine-tts", "name": "火山引擎基础语音合成"},
        {"id": "doubao-tts", "name": "豆包语音合成大模型"}
      ],
      "voices": [
        {"id": "female_emotion_1", "name": "情感女声 (推荐)"},
        {"id": "male_emotion_1", "name": "情感男声 (推荐)"},
        {"id": "female_story", "name": "讲故事女声"},
        {"id": "male_story", "name": "讲故事男声"},
        {"id": "child_default", "name": "默认童声"}
      ]
    }
  ]
}
""".trimIndent()

