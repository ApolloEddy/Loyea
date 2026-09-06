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
import com.loyea.health.HealthConnectCoordinator
import com.loyea.health.HealthPairingStatus
import com.loyea.perception.HapticManager
import com.loyea.perception.PhysicalContextManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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

    /** WorldInfo 2.0 统一书库门面（书库页 / 生效书面板直接使用，Spec §7）。 */
    val worldInfoLibrary: com.loyea.storage.worldinfo.WorldInfoLibrary
        get() = storageManager.worldInfoLibrary
    private val llmClient = LlmClient()
    private val sessionDrafts = mutableMapOf<String, String>()

    private val mcpManager = McpManager(application)
    val mcpStates: StateFlow<Map<String, McpServerStatus>> = mcpManager.serverStates

    private val hapticManager = HapticManager(application)
    val perceptionManager = PhysicalContextManager(context)

    private val healthCoordinator = HealthConnectCoordinator(context)

    /** 健康连接配对状态（设置页『健康数据集成』面板用）。 */
    var healthPairingStatus = mutableStateOf<HealthPairingStatus?>(null)
        private set

    fun refreshHealthPairingStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            healthPairingStatus.value = healthCoordinator.buildPairingStatus()
        }
    }

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

    // 8.2 全局世界观默认匹配配置（WorldInfo 2.0：书级未覆盖时的默认层，存 SharedPreferences）
    var worldInfoConfig = mutableStateOf(WorldInfoConfig())
        private set

    // P5 有限正则：当前角色的映射规则（显示阶段在渲染时按 DISPLAY_ASSISTANT 过滤应用）
    var displayRegexRules = mutableStateOf<List<com.loyea.character.core.regex.RegexRule>>(emptyList())
        private set

    /** 角色内嵌世界书的只读视图（key = 角色卡 id），供角色列表/编辑页展示（Spec §4.7） */
    data class WorldBookEntryView(
        val keys: List<String>,
        val secondaryKeys: List<String>,
        val content: String,
        val constant: Boolean,
        val enabled: Boolean,
        val position: String,
        val comment: String
    )

    data class WorldBookView(
        val name: String,
        val entries: List<WorldBookEntryView>
    )

    var characterBookViews = mutableStateOf<Map<String, WorldBookView>>(emptyMap())
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
    var enableAudioUnderstanding = mutableStateOf(false)
        private set
    var enableTts = mutableStateOf(true)
        private set
    var ttsVoice = mutableStateOf("茉莉")
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
    // LLM 智能标题生成防重入：同一会话只生成一次
    private val titleGenerationInFlight = mutableSetOf<String>()
    var imageGenConfigId = mutableStateOf("")
        private set

    var ttsProviderTemplate = mutableStateOf("Auto")
        private set
    var sttProviderTemplate = mutableStateOf("Auto")
        private set
    
    var ttsTemplates = mutableStateOf<List<TtsTemplate>>(emptyList())
        private set

    var isUpdatingTemplates = mutableStateOf(false)
        private set
    
    var updateTemplatesStatus = mutableStateOf("")
        private set

    // 13. 媒体录制与播放状态
    companion object {
        @Volatile
        var isRecordingActive = false
    }
    private var audioRecord: android.media.AudioRecord? = null
    private var isRecordingWav = false
    private var recordingThread: Thread? = null
    private var audioFile: File? = null
    var isRecording = mutableStateOf(false)
        private set
    var recordingDuration = mutableStateOf(0)
        private set
    var recordingAmplitude = mutableStateOf(0f)
        private set
    private var recordingTimer: Timer? = null
    private val amplitudeList = java.util.Collections.synchronizedList(mutableListOf<Int>())

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

    var currentlyPlayingAudioProgress = mutableStateOf(0f)
        private set

    private var audioProgressJob: kotlinx.coroutines.Job? = null

    // 图谱记忆管理器
    private val graphMemoryManager = com.loyea.perception.memory.GraphMemoryManager(context)

    // 长程图谱与声学情绪感知的开关
    var enableGraphMemory = mutableStateOf(true)
        private set
    var enableVoiceEmotionPerception = mutableStateOf(true)
        private set

    // 成人内容模式（Beta，默认关闭）：放宽 AI 回复内容开放度，仅注入 Prompt 引导，不改变红线
    var enableAdultContent = mutableStateOf(false)
        private set

    // 外部 MCP 工具白名单：null = 从未管理（兼容放行全部）；非 null = 严格白名单（仅授权工具可用）
    var mcpToolWhitelist = mutableStateOf<Set<String>?>(null)
        private set

    // 长会话压缩参数：消息数超过阈值时，滑窗外的旧消息异步压缩为早期摘要（增量断点 compressedAtCount）
    private val compressTriggerCount = 160
    private val compressTailCount = 20
    private var isCompressing = false // 防重入：压缩任务进行中禁止并发触发

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
            // 搜索凭据解析（全局优先）：设置页「联网搜索 API（全局）」配置一次全模型共用；
            // 未填时回落旧版行为（服务商原生 web_search 或免 Key 检索）
            val globalProvider = prefs.getString("global_search_provider", "") ?: ""
            val globalUrl = prefs.getString("global_search_api_url", "") ?: ""
            val globalKey = prefs.getString("global_search_api_key", "") ?: ""
            when {
                globalKey.isNotBlank() -> llmClient.performIndependentWebSearch(
                    globalProvider.ifBlank { "Tavily" },
                    globalUrl.ifBlank { "https://api.tavily.com" },
                    globalKey,
                    query
                )
                activeConfig.useIndependentSearch && activeConfig.searchApiKey.isNotBlank() -> llmClient.performIndependentWebSearch(
                    activeConfig.searchProvider, activeConfig.searchApiUrl, activeConfig.searchApiKey, query
                )
                else -> {
                    // 如果没有配置独立检索 Key，自动切换至备用免 Key 公共检索 (DuckDuckGo HTML 解析)
                    llmClient.performFreeWebSearch(query)
                }
            }
        }
        // read_url：抓取指定网页正文（Agent 化浏览，模型自主抉择「搜索」还是「直接读官网」）
        mcpManager.registerWebPageFetcher { url ->
            llmClient.fetchWebPage(url)
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
            val bookViews = HashMap<String, WorldBookView>()
            cards.forEach { card ->
                storageManager.loadCharacterDocument(card.id)?.embeddedBookJson?.let { bookJson ->
                    buildWorldBookView(bookJson)?.let { view -> bookViews[card.id] = view }
                }
            }
            withContext(Dispatchers.Main) {
                characterCardList.value = cards
                characterBookViews.value = bookViews
            }
            // 全局条目已并入统一书库（WorldInfo 2.0）；此处仅加载默认匹配配置
            val worldInfoCfg = WorldInfoConfigStorage.load(prefs)
            withContext(Dispatchers.Main) {
                worldInfoConfig.value = worldInfoCfg
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
        enableAudioUnderstanding.value = prefs.getBoolean("enable_audio_understanding", false)
        enableTts.value = prefs.getBoolean("enable_tts", true)
        val savedTtsVoice = prefs.getString("tts_voice", "") ?: ""
        // 旧版本默认值 "mimo-v2.5-tts-default" 迁移为真实音色 "茉莉"（运行时也会自愈回退）
        ttsVoice.value = when (savedTtsVoice) {
            "", "mimo-v2.5-tts-default" -> "茉莉"
            else -> savedTtsVoice
        }
        enableAutoTts.value = prefs.getBoolean("enable_auto_tts", false)
        enableImageGen.value = prefs.getBoolean("enable_image_gen", true)
        imageGenModel.value = prefs.getString("image_gen_model", "dall-e-3") ?: "dall-e-3"
        enableAdultContent.value = prefs.getBoolean("enable_adult_content", false)
        mcpToolWhitelist.value = prefs.getStringSet("mcp_tool_whitelist", null)
        
        visionConfigId.value = prefs.getString("vision_config_id", "") ?: ""
        visionModelName.value = prefs.getString("vision_model_name", "gpt-4o-mini") ?: "gpt-4o-mini"
        sttConfigId.value = prefs.getString("stt_config_id", "") ?: ""
        sttModelName.value = prefs.getString("stt_model_name", "whisper-1") ?: "whisper-1"
        ttsConfigId.value = prefs.getString("tts_config_id", "") ?: ""
        ttsModelName.value = prefs.getString("tts_model_name", "tts-1") ?: "tts-1"
        imageGenConfigId.value = prefs.getString("image_gen_config_id", "") ?: ""
        
        ttsProviderTemplate.value = prefs.getString("tts_provider_template", "Auto") ?: "Auto"
        sttProviderTemplate.value = prefs.getString("stt_provider_template", "Auto") ?: "Auto"
        
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
        stopAudio() // 切换会话时停止跨会话残留的音频播放
        currentSessionId.value = sessionId
        currentVoiceEmotion.value = null // 清空临时情感缓存，防止信息混用污染
        prefs.edit().putString("current_session_id", sessionId).apply()
        viewModelScope.launch(Dispatchers.IO) {
            val msgs = storageManager.loadSessionMessages(sessionId)
            // 世界书生效解析改为请求时经 WorldInfoLibrary（WorldInfo 2.0），切换会话无需预载
            // P5：随角色切换重载有限正则规则（来自卡 extensions.regex_scripts 白名单映射）
            val characterId = activeSession.value?.characterId
            val regexOutcome = characterId?.let { loadRegexRulesFor(it) }
            withContext(Dispatchers.Main) {
                messages.value = msgs
                displayRegexRules.value = regexOutcome?.rules ?: emptyList()
            }
        }
    }

    /** 解析内嵌世界书为只读视图；解析失败返回 null（UI 显示为无书）。 */
    private fun buildWorldBookView(bookJson: String): WorldBookView? = runCatching {
        val parsed = com.loyea.character.core.codec.CharacterCardCodec.parseCharacterBook(bookJson)
            ?: return null
        val adapted = com.loyea.character.core.codec.CardBookAdapter.toWorldInfoBook(parsed, "view")
        WorldBookView(
            name = parsed.name ?: "",
            entries = adapted.entries.map { e ->
                WorldBookEntryView(
                    keys = e.keywords,
                    secondaryKeys = e.keysecondary,
                    content = e.content,
                    constant = e.constant,
                    enabled = e.enabled && !e.disable,
                    position = e.positionType,
                    comment = e.comment
                )
            }
        )
    }.getOrNull()

    /** 加载角色的有限正则规则（映射失败的条目记日志，配置原文保留）。 */
    private suspend fun loadRegexRulesFor(characterId: String): com.loyea.character.core.regex.RegexScriptAdapter.ImportOutcome? {
        val doc = storageManager.loadCharacterDocument(characterId) ?: return null
        val outcome = com.loyea.character.core.regex.RegexScriptAdapter.fromExtensionsJson(doc.extensionsJson)
        if (outcome.rejections.isNotEmpty()) {
            android.util.Log.i("BoundedRegex", "规则映射拒绝: ${outcome.rejections.joinToString { it.ruleId + ":" + it.reason }}")
        }
        return outcome
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

    // --- 角色卡导入（character-core 保真 codec，Spec §4） ---

    /** 导入结果：message 已按语言组织好，UI 直接展示。 */
    data class CharacterImportOutcome(val success: Boolean, val message: String)

    fun importCharacterCard(bytes: ByteArray, onResult: (CharacterImportOutcome) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = com.loyea.character.core.api.CharacterCardImporter.import(bytes)
                var doc = result.document
                // 头像沿用既有 avatars 目录，以稳定 ID 命名（重复导入不产生新文件）
                val avatarsDir = File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }
                val avatarFile = File(avatarsDir, "${doc.profile.id}.png")
                runCatching { avatarFile.writeBytes(bytes) }
                doc = doc.withProfile(
                    doc.profile.copy(
                        display = doc.profile.display.copy(avatarUri = avatarFile.absolutePath)
                    )
                )
                // 同一稳定 ID 视为同一角色：更新现有并递增 revision；不同卡天然不同 ID（Spec §4.5）
                val isUpdate = storageManager.characterDocumentExists(doc.profile.id)
                if (isUpdate) {
                    storageManager.loadCharacterDocument(doc.profile.id)?.let { existing ->
                        doc = doc.copy(profile = doc.profile.copy(revision = existing.profile.revision + 1))
                    }
                }
                storageManager.saveCharacterDocument(doc)
                // WorldInfo 2.0：带内嵌书的新卡自动注册进书库（幂等；resolve 时也会兜底补建）
                if (doc.embeddedBookJson != null) {
                    runCatching { storageManager.worldInfoLibrary.ensureCardBookRegistered(doc.profile.id) }
                }
                val cards = storageManager.loadCharacterCards()
                // 同步刷新内嵌世界书视图（新导入/更新立即可见，无需重启）
                val bookViews = characterBookViews.value.toMutableMap()
                doc.embeddedBookJson?.let { bookJson ->
                    buildWorldBookView(bookJson)?.let { view -> bookViews[doc.profile.id] = view }
                } ?: run { bookViews.remove(doc.profile.id) }
                val newBooks = bookViews.toMap()
                withContext(Dispatchers.Main) {
                    characterCardList.value = cards
                    characterBookViews.value = newBooks
                    val base = if (isUpdate) "已更新角色卡 [${doc.profile.name}]" else "成功导入角色卡 [${doc.profile.name}]"
                    val hint = if (result.report.unsupported.isNotEmpty()) "（部分能力暂不支持，已保留原文）" else ""
                    onResult(CharacterImportOutcome(true, base + hint))
                }
            } catch (e: com.loyea.character.core.api.ImportFailure) {
                withContext(Dispatchers.Main) {
                    onResult(CharacterImportOutcome(false, e.message ?: "导入失败"))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(CharacterImportOutcome(false, "导入失败: ${e.localizedMessage}"))
                }
            }
        }
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
        // 删除当前会话时立即停止其正在进行的流式回复，防止流继续写回已删除会话文件
        stopResponse()
        clearDraft(deleteId)
        val targetCharId = sessions.value.find { it.id == deleteId }?.characterId
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteSession(deleteId)
            // 同步清理该会话的关系图谱记忆，防止残留数据被后续提炼流程"复活"
            if (targetCharId != null) {
                graphMemoryManager.clearMemoriesForSession(targetCharId, deleteId)
            }
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

        viewModelScope.launch(Dispatchers.IO) {
            // 备用开场白（Spec §1.2）：默认开场白为首条内容，备用开场白进多版本翻页器，
            // 沿用既有 swipe 交互切换，不新增主界面控件
            val doc = storageManager.loadCharacterDocument(selectedChar.id)
            val greetings = doc?.let {
                com.loyea.character.core.prompt.CharacterCompiler.greetings(it.profile, userName.value)
            }?.takeIf { it.isNotEmpty() } ?: listOf(formattedWelcome)
            val initialMsgs = listOf(
                Message(
                    id = System.currentTimeMillis().toString(),
                    content = greetings.first(),
                    sender = Sender.AI,
                    characterId = selectedChar.id,
                    versions = greetings.drop(1).map { alt -> MessageVersion(content = alt) }
                )
            )
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

        // 重入保护：AI 回复流式输出中禁止并发发起第二轮请求（文本/语音/音频理解路径统一拦截）
        if (responseJob?.isActive == true) {
            android.widget.Toast.makeText(context, "AI 正在回复中，请稍候或点击停止", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // 长会话惰性压缩检查（异步、不阻塞）：滑窗外的旧消息增量压成早期摘要
        maybeCompressSession(currentSessionId.value)

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
            id = newMessageId(),
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


    /**
     * 重新生成最后一条 AI 回复：旧回复快照作为历史版本保留（versions），重新发起一轮流式回复。
     * 失败/中断时由 applyRegenerateVersions 恢复旧回复，保证用户不丢失已有答案。
     */
    fun regenerateLastReply() {
        if (responseJob?.isActive == true) {
            android.widget.Toast.makeText(context, "AI 正在回复中，请稍候或点击停止", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val current = messages.value
        val lastAiIndex = current.indexOfLast { it.sender == Sender.AI }
        if (lastAiIndex < 0) return
        val lastAi = current[lastAiIndex]
        // 流式占位气泡（空内容 / 仍在思考）不允许重新生成
        if (lastAi.isStillThinking || (lastAi.content.isBlank() && lastAi.contentSegments.isEmpty())) return

        val history = current.subList(0, lastAiIndex)
        val sessionId = currentSessionId.value
        val activeCard = activeCharacterCard.value
        isThinking.value = true
        // 先移除旧回复气泡，流式会重建占位；旧消息交给 startAiResponseStream 的 regenerateOf 归并
        messages.value = history
        startAiResponseStream(sessionId, history, activeCard, regenerateOf = lastAi)
    }

    /**
     * 翻页切换 AI 回复版本（versions 列表 + activeVersionIndex）。
     * 切到目标版本后把该版本内容镜像回顶层字段，由旧渲染路径展示。
     */
    fun switchMessageVersion(messageId: String, delta: Int) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            var updatedMsgs = emptyList<Message>()
            storageManager.updateSessionMessages(sessionId) { diskMsgs ->
                val updated = diskMsgs.map { msg ->
                    if (msg.id == messageId && msg.versions.isNotEmpty()) {
                        val n = msg.versions.size
                        val newIdx = ((msg.activeVersionIndex + delta) % n + n) % n
                        val v = msg.versions[newIdx]
                        msg.copy(
                            activeVersionIndex = newIdx,
                            content = v.content,
                            thoughts = v.thoughts,
                            mcpCalls = v.mcpCalls,
                            audioUrl = v.audioUrl,
                            audioDuration = v.audioDuration,
                            contentSegments = emptyList(), // 版本内容走旧渲染路径
                            isError = false,
                            isStillThinking = false
                        )
                    } else msg
                }
                updatedMsgs = updated
                updated
            }
            // Spec 7.3 / M03：切换被摘要覆盖消息的版本 → 旧摘要失效，从原始消息重建
            val coveredIdx = updatedMsgs.indexOfFirst { it.id == messageId }
            val coveredCount = activeSession.value?.compressedAtCount ?: 0
            if (coveredIdx in 0 until coveredCount) {
                storageManager.updateSessionCompression(sessionId, "", 0)
            }
            withContext(Dispatchers.Main) {
                messages.value = updatedMsgs
                if (coveredIdx in 0 until coveredCount) {
                    sessions.value = sessions.value.map { s ->
                        if (s.id == sessionId) s.copy(compressedSummary = "", compressedAtCount = 0) else s
                    }
                }
            }
        }
    }

    /**
     * 重新生成完成后的版本归并：
     * - 新回复成功：旧回复 + 新回复作为两个历史版本并入 versions，activeVersionIndex 指向新回复
     * - 新回复失败 / 被中断 / 内容仍为空：恢复旧回复气泡
     */
    private fun applyRegenerateVersions(oldMessage: Message, newMessageId: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        val currentList = messages.value
        val newIdx = currentList.indexOfFirst { it.id == newMessageId }
        if (newIdx < 0) return
        val newMsg = currentList[newIdx]

        // 失败 / 中断 / 内容仍为空 → 恢复旧回复，避免用户丢失已有答案
        if (newMsg.isError || newMsg.isStillThinking || newMsg.content.isBlank()) {
            val restored = currentList.mapIndexed { i, m -> if (i == newIdx) oldMessage else m }
            if (currentSessionId.value == sessionId) {
                messages.value = restored
            }
            saveMessagesAsync(sessionId, restored)
            return
        }

        val oldVersion = MessageVersion(
            content = oldMessage.content,
            thoughts = oldMessage.thoughts,
            mcpCalls = oldMessage.mcpCalls,
            audioUrl = oldMessage.audioUrl,
            audioDuration = oldMessage.audioDuration
        )
        val newVersion = MessageVersion(
            content = newMsg.content,
            thoughts = newMsg.thoughts,
            mcpCalls = newMsg.mcpCalls,
            audioUrl = newMsg.audioUrl,
            audioDuration = newMsg.audioDuration
        )
        // 已有历史版本 + 本次被替换的旧回复 + 新回复
        val versions = oldMessage.versions + oldVersion + newVersion
        val updated = currentList.mapIndexed { i, m ->
            if (i == newIdx) m.copy(versions = versions, activeVersionIndex = versions.size - 1) else m
        }
        if (currentSessionId.value == sessionId) {
            messages.value = updated
        }
        saveMessagesAsync(sessionId, updated)
    }

    private fun updateSessionTitleIfNeeded(sessionId: String, currentMessages: List<Message>) {
        val currentSession = sessions.value.find { it.id == sessionId } ?: return
        // 已被 AI 总结过，不再自动覆盖
        if (currentSession.isTitleSummarized == true) return
        // 已有明确文字标题则跳过；语音/图片等占位标题可被后续文字消息升级
        if (!isDefaultSessionTitle(currentSession.title) && !isFallbackPlaceholderTitle(currentSession.title)) return
        val firstUserMsg = currentMessages.firstOrNull { it.sender == Sender.USER } ?: return
        val cleanTitle = buildFallbackTitle(firstUserMsg)
        if (cleanTitle.isBlank()) return
        // 兜底标题立即生效，但不置位 isTitleSummarized，保留 AI 精修资格
        applySessionTitle(sessionId, cleanTitle)
    }

    /** 语音/图片等无文字首条消息生成的占位标题（可被文字消息或 AI 总结升级） */
    private fun isFallbackPlaceholderTitle(title: String): Boolean =
        title == "语音消息" || title == "图片消息" || title == "Voice Message" || title == "Image Message"

    /** 首条用户消息 → 兜底标题（立即生效、零成本；语音/图片首条消息不再出现空标题） */
    private fun buildFallbackTitle(firstUserMsg: Message): String {
        var raw = firstUserMsg.content.orEmpty().trim()
        if (raw.startsWith("/draw ")) raw = raw.removePrefix("/draw ").trim()
        if (raw.isBlank()) {
            return when {
                !firstUserMsg.audioUrl.isNullOrBlank() ->
                    if (appLanguage.value == "en") "Voice Message" else "语音消息"
                !firstUserMsg.imageUrl.isNullOrBlank() ->
                    if (appLanguage.value == "en") "Image Message" else "图片消息"
                else -> ""
            }
        }
        val singleLine = raw.replace(Regex("\\s+"), " ")
        return if (singleLine.length > 15) singleLine.take(15) + "..." else singleLine
    }

    private fun isDefaultSessionTitle(title: String): Boolean =
        title == "新会话" || title == "New Chat" ||
            title.startsWith("欢迎会话") || title.startsWith("Welcome Chat")

    private fun applySessionTitle(sessionId: String, newTitle: String, isSummarized: Boolean? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            var updatedList: List<ChatSession> = emptyList()
            storageManager.updateSessionList { diskSessions ->
                val updated = diskSessions.map {
                    if (it.id == sessionId) {
                        it.copy(
                            title = newTitle,
                            lastActiveTime = System.currentTimeMillis(),
                            // isSummarized 为空时保留原值；AI 总结成功后置 true 防止再次覆盖
                            isTitleSummarized = isSummarized ?: it.isTitleSummarized
                        )
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

    /**
     * 第一条 AI 回复完成后，用 LLM 为会话精修一次标题。
     * 用户明确要求了标题（如「标题叫XX」「命名为XX」）则采纳；否则默认总结 4-12 字精炼标题。
     * 仅执行一次且静默失败：异常/超时直接放弃，兜底标题已由 updateSessionTitleIfNeeded 提供。
     */
    private fun maybeGenerateSmartTitle(sessionId: String, history: List<Message>, force: Boolean = false) {
        val currentSession = sessions.value.find { it.id == sessionId } ?: return
        if (!force && currentSession.isTitleSummarized == true) return // 已被 AI 总结过则跳过
        if (!titleGenerationInFlight.add(sessionId)) return      // 同一会话只允许一次
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val firstUserText = history.firstOrNull { it.sender == Sender.USER }?.content
                    ?.take(120)?.trim().orEmpty()
                val firstAiText = history.firstOrNull { it.sender == Sender.AI }?.content
                    ?.take(120)?.trim().orEmpty()
                // 纯语音/图片首条（无文字）时，退化为根据 AI 回复总结
                if (firstUserText.isBlank() && firstAiText.isBlank()) return@launch
                val prompt = buildSmartTitlePrompt(firstUserText, firstAiText)
                val titleBuilder = StringBuilder()
                var usagePrompt = 0L
                var usageCompletion = 0L
                var hasRealUsage = false
                kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    llmClient.sendChatCompletionStream(
                        activeApiConfig.value,
                        listOf(
                            LlmChatMessage(
                                role = "system",
                                content = BackgroundPromptTemplates.SMART_TITLE_SYSTEM
                            ),
                            LlmChatMessage(role = "user", content = prompt)
                        ),
                        emptyList()
                    ).collect { ev ->
                        when (ev) {
                            is StreamEvent.Content -> titleBuilder.append(ev.text)
                            is StreamEvent.Usage -> {
                                usagePrompt = ev.promptTokens
                                usageCompletion = ev.completionTokens
                                hasRealUsage = true
                            }
                            else -> {}
                        }
                    }
                }
                val title = titleBuilder.toString().trim()
                    .trim('"', '“', '”', '「', '」', '\'', '\n')
                    .replace(Regex("\\s+"), " ")
                    .take(16)
                if (title.length >= 2 && !title.contains("\n") && !title.contains("标题")) {
                    withContext(Dispatchers.Main) {
                        val fresh = sessions.value.find { it.id == sessionId }
                        if (fresh != null && (force || fresh.isTitleSummarized != true)) {
                            applySessionTitle(sessionId, title, isSummarized = true)
                        }
                    }
                }
                // 标题生成也计入会话用量（系统调用），但不更新上下文窗口展示值
                persistSessionTokens(
                    sessionId,
                    promptTokens = if (hasRealUsage) usagePrompt else {
                        estimateTokens(BackgroundPromptTemplates.SMART_TITLE_SYSTEM) + estimateTokens(prompt)
                    },
                    completionTokens = if (hasRealUsage) usageCompletion else estimateTokens(titleBuilder.toString()),
                    lastContext = null
                )
            } catch (e: Exception) {
                // 静默降级：标题保持兜底值
            } finally {
                titleGenerationInFlight.remove(sessionId)
            }
        }
    }

    /**
     * 手动重命名会话标题：置位 isTitleSummarized 防止后续 AI 自动总结覆盖手动命名
     */
    fun renameSession(sessionId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank() || trimmed.length > 50) return
        viewModelScope.launch(Dispatchers.IO) {
            var updatedList: List<ChatSession> = emptyList()
            storageManager.updateSessionList { diskSessions ->
                val updated = diskSessions.map {
                    if (it.id == sessionId) it.copy(title = trimmed, isTitleSummarized = true) else it
                }
                updatedList = updated
                updated
            }
            withContext(Dispatchers.Main) {
                sessions.value = updatedList
            }
        }
    }

    /**
     * AI 重新生成会话标题（用户手动触发，忽略一次性总结锁，可反复生成）。
     * 会话未打开时从磁盘加载首条用户/AI 消息作为总结依据。
     */
    fun regenerateSessionTitle(sessionId: String) {
        if (titleGenerationInFlight.contains(sessionId)) {
            android.widget.Toast.makeText(context, "标题生成中，请稍候", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val history = if (currentSessionId.value == sessionId) {
                messages.value
            } else {
                storageManager.loadSessionMessages(sessionId)
            }
            maybeGenerateSmartTitle(sessionId, history, force = true)
        }
    }

    /** 标题任务的易变对话数据只进入 user payload，稳定规则由模板统一提供。 */
    private fun buildSmartTitlePrompt(firstUserText: String, firstAiText: String): String {
        return BackgroundPromptTemplates.smartTitleInput(firstUserText, firstAiText)
    }

    private fun startAiResponseStream(
        sessionId: String,
        history: List<Message>,
        characterCard: CharacterCard,
        regenerateOf: Message? = null
    ) {
        responseJob = viewModelScope.launch {
            isThinking.value = true
            isMcpRunning.value = false
            
            val aiMessageId = newMessageId()
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
                thinkingStartedAt = startTime,
                characterId = characterCard.id
            )
            messages.value = collapsedHistoryList + placeholderAiMsg

            var currentList = messages.value
            var accumulatedContent = ""
            var accumulatedThoughts = ""
            var calculatedDuration: Int? = null

            // —— Token 用量累计（跨 Agent 多轮，直到整条回复结束才统一落库）——
            var accumulatedPromptTokens = 0L
            var accumulatedCompletionTokens = 0L
            var lastContextPromptTokens = 0L
            // —— DeepSeek 前缀缓存 token 累计（仅主聊天流，最终统一落库）——
            var accumulatedCacheHitTokens = 0L
            var accumulatedCacheMissTokens = 0L
            var hasRealUsage = false

            // —— Agent 式多轮分段构建（仅新消息）——
            // segments：已提交的文本段 + 工具段；segmentCut：accumulatedContent 中当前文本段的起点。
            // 工具段在工具执行期间显式写入（此时无 Content 事件），文本段在 ToolCalls/收尾时提交。
            val segments = mutableListOf<MessageContentSegment>()
            var segmentCut = 0

            fun tailText(): String {
                // 镜像 Content 事件的半截 [haptic: 过滤，再收敛换行、去段首残留（思考块剥离）空行
                // 防御：完整 [haptic:xxx] 跨提交边界被 removeRange 剥离后，accumulatedContent 会短于 segmentCut，
                // 越界 substring 会抛异常导致整条回复被外层 catch 打成错误气泡，这里 coerce 到当前长度
                val start = segmentCut.coerceAtMost(accumulatedContent.length)
                return cleanSegmentText(truncateIncompleteHaptic(accumulatedContent.substring(start)))
            }

            fun commitCurrentTextSegment() {
                val t = tailText()
                if (t.isNotBlank()) {
                    segments.add(MessageContentSegment("text", text = t))
                }
                segmentCut = accumulatedContent.length
            }

            fun currentSegments(): List<MessageContentSegment> {
                if (accumulatedContent.length <= segmentCut) return segments.toList()
                val t = tailText()
                return if (t.isNotBlank()) {
                    segments + MessageContentSegment("text", text = t)
                } else {
                    segments.toList()
                }
            }

            // 获取 API 配置和 MCP 工具列表
            var apiConfig = activeApiConfig.value
            
            // ===== 多模态智能路由与降级 =====
            // 仅当「当前正在发送的这条消息」携带图片时才考虑视觉路由。
            // 旧逻辑的致命缺陷：只要会话历史里出现过任意一张图片，后续所有请求（包括纯文本消息）
            // 都会被强制替换模型名并带图发送 —— DeepSeek 等无视觉模型直接 400，整个会话无法继续。
            val lastUserMsg = history.lastOrNull { it.sender == Sender.USER }
            val currentMsgHasImage = !lastUserMsg?.imageUrl.isNullOrBlank()
            val currentMsgHasAudio = enableAudioUnderstanding.value && !lastUserMsg?.audioUrl.isNullOrBlank()

            var useVisionRoute = false
            var includeVision = false
            if (currentMsgHasImage && enableMultimodal.value) {
                val visionCfgId = visionConfigId.value
                val visionModel = visionModelName.value
                val targetVisionCfg = if (visionCfgId.isNotBlank()) {
                    apiConfigList.value.find { it.id == visionCfgId }
                } else {
                    null
                }
                val visionCandidate = targetVisionCfg ?: apiConfig
                if (providerSupportsVision(visionCandidate.provider, visionModel)) {
                    // 视觉路由生效：切到视觉配置与模型
                    apiConfig = if (targetVisionCfg != null) {
                        targetVisionCfg.copy(modelName = visionModel)
                    } else {
                        apiConfig.copy(modelName = visionModel)
                    }
                    useVisionRoute = true
                    includeVision = true
                }
                // 视觉配置缺失或目标提供商不支持视觉 → includeVision 保持 false，
                // 图片将以 [图片] 文本占位随消息发送，会话继续正常进行，不报错。
            }
            // 音频输入是否可进 payload（取决于当前路由后的模型能力，与图片降级同理）
            val includeAudioInput = currentMsgHasAudio &&
                providerSupportsAudioInput(apiConfig.provider, apiConfig.modelName)

            // 每个用户回合的动态上下文只生成一次并固化到该 Message：
            // 后续重生成/多轮请求复用原快照，避免当前时间、图谱或世界书改写历史前缀。
            val requestUserMessage = history.lastOrNull { it.sender == Sender.USER }
            val snapshotTime = requestUserMessage?.timestamp?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val existingTurnSnapshot = requestUserMessage?.llmContextSnapshot?.takeIf { it.isNotBlank() }
            val needsTurnSnapshot = existingTurnSnapshot == null
            val sessionUsesSystemTime = activeSession.value?.useSystemTime == true
            val tenMinutesAgo = snapshotTime - 10 * 60 * 1000
            val recentToolCallsStr = if (needsTurnSnapshot) {
                history
                    .filter { it.timestamp in tenMinutesAgo..snapshotTime }
                    .flatMap { msg ->
                        val diffMs = snapshotTime - msg.timestamp
                        val timeDesc = when {
                            diffMs < 30 * 1000 -> "刚刚"
                            diffMs < 60 * 1000 -> "1分钟内"
                            else -> "${diffMs / (60 * 1000)}分钟前"
                        }
                        msg.mcpCalls
                            .filter { it.status == McpStatus.SUCCESS && it.toolName != "send_voice_reply" }
                            .map { call ->
                                "- ${timeDesc}成功调用了 `${call.toolName}` 工具，返回结果为：${call.output.trim().take(1500)}"
                            }
                    }
                    .joinToString("\n")
            } else {
                ""
            }

            val physicalContextData = if (needsTurnSnapshot) {
                buildString {
                    if (enableVoiceEmotionPerception.value && !currentVoiceEmotion.value.isNullOrBlank()) {
                        append("[Acoustic Emotion]\n")
                        append("User's Voice Tone: ${currentVoiceEmotion.value}\n\n")
                    }
                    if (recentToolCallsStr.isNotBlank()) {
                        append("[RECENT PERCEPTION TOOL CALLS (10MIN CACHE)]\n")
                        append(recentToolCallsStr)
                    }
                }.trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val graphMemory = if (needsTurnSnapshot && enableGraphMemory.value) {
                graphMemoryManager.retrieveRelationalContext(
                    characterId = characterCard.id,
                    sessionId = sessionId,
                    userInput = requestUserMessage?.content ?: ""
                )
            } else {
                null
            }

            // bottom 世界书固化进回合快照；显式 top 模式保留原有前置语义，因此每次仍需重建。
            val worldInfoPosition = if (worldInfoConfig.value.position == "top") "top" else "bottom"
            // 导入卡路径（Spec §5.1）：世界书由 CharacterCompiler 按位置注入 system，
            // 不再固化进回合快照——正确位置优先于缓存命中率（Spec §5.3）
            val characterDocument = storageManager.loadCharacterDocument(characterCard.id)
            // P5 有限正则（Spec §8 处理顺序）：原始消息副本 → prompt stage 转换 → 世界书扫描 → 编译
            val regexOutcome = characterDocument?.extensionsJson
                ?.let { com.loyea.character.core.regex.RegexScriptAdapter.fromExtensionsJson(it) }
            val promptRegexRules = regexOutcome?.rules ?: emptyList()
            val compiledPatterns = promptRegexRules.mapNotNull { rule ->
                when (val outcome = com.loyea.character.core.regex.BoundedRegexEngine.compile(rule)) {
                    is com.loyea.character.core.regex.RegexCompileOutcome.Ok -> rule.id to outcome.pattern
                    is com.loyea.character.core.regex.RegexCompileOutcome.Rejected -> null
                }
            }.toMap()
            val useCompiledPath = characterDocument != null && (
                characterDocument.embeddedBookJson != null ||
                    characterDocument.profile.origin == com.loyea.character.core.api.CharacterOrigin.IMPORTED
                )
            var compiledPrompt: com.loyea.character.core.prompt.CharacterCompiler.PreparedCharacterTurn? = null
            val worldInfo = if (useCompiledPath) {
                null
            } else if (needsTurnSnapshot || worldInfoPosition == "top") {
                buildWorldInfoBlock(sessionId, history)
            } else {
                null
            }

            // ===== 导入卡编译路径（Spec §5.1 固定顺序合同） =====
            // 原生人格走 0.5.5 稳定前缀路径；导入卡（或带内嵌世界书的文档）经 CharacterCompiler：
            // 世界书按 before_char/after_char 位置注入 system，正确位置优先于缓存命中率（Spec §5.3）。
            val promptParts = if (useCompiledPath) {
                val compiled = buildCompiledTurn(
                    document = characterDocument!!,
                    card = characterCard,
                    sessionId = sessionId,
                    history = history,
                    promptRegexRules = promptRegexRules,
                    compiledPatterns = compiledPatterns,
                    sessionUsesSystemTime = sessionUsesSystemTime,
                    physicalPerceptionEnabled = sessionUsesSystemTime,
                    enableSearch = apiConfig.enableSearch,
                    enableHaptic = toolAuthHaptic.value,
                    enableVoice = hasTtsCapability(),
                    enableAdultContent = enableAdultContent.value,
                    graphMemory = graphMemory,
                    snapshotTime = snapshotTime
                )
                // Spec §6.2.6：常驻条目放不进预算 → 可恢复错误，暂停发送，不静默丢核心规则
                if (compiled.constantOverflow) {
                    withContext(Dispatchers.Main) {
                        isThinking.value = false
                        android.widget.Toast.makeText(
                            context,
                            "世界书常驻内容超出预算：请在世界书设置中增大 Token 预算或缩减条目后重试",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                if (compiled.unsupportedPositions.isNotEmpty()) {
                    android.util.Log.w("CharacterCompiler",
                        "unsupported world info positions: ${compiled.unsupportedPositions}")
                }
                compiledPrompt = compiled
                PromptAssembler.PromptParts(
                    stableSystemPrompt = compiled.systemText(),
                    turnContextSnapshot = if (needsTurnSnapshot || sessionUsesSystemTime) {
                        PromptAssembler.assembleTurnSnapshotOnly(
                            physicalContext = physicalContextData,
                            graphMemory = if (enableGraphMemory.value) graphMemory else null,
                            useSystemTime = sessionUsesSystemTime,
                            includeSystemTimeInSnapshot = true,
                            snapshotTimeMillis = snapshotTime
                        )
                    } else ""
                )
            } else {
                null
            }

            val effectivePromptParts = promptParts ?: PromptAssembler.assemblePromptParts(
                card = characterCard,
                userName = userName.value,
                useSystemTime = sessionUsesSystemTime,
                includeSystemTimeInSnapshot = true,
                physicalContext = physicalContextData,
                enableSearch = apiConfig.enableSearch,
                coreMemories = activeSession.value?.coreMemories ?: emptyList(),
                graphMemory = graphMemory,
                worldInfo = worldInfo,
                worldInfoPosition = worldInfoPosition,
                enableHaptic = toolAuthHaptic.value,
                enableVoice = hasTtsCapability(),
                enableAdultContent = enableAdultContent.value,
                trustedCard = characterCard.isBuiltIn,
                snapshotTimeMillis = snapshotTime
            )
            val promptPartsFinal = effectivePromptParts

            val turnSnapshot = existingTurnSnapshot ?: promptPartsFinal.turnContextSnapshot
            var requestHistory = history
            if (requestUserMessage != null && existingTurnSnapshot == null && turnSnapshot.isNotBlank()) {
                requestHistory = history.map { message ->
                    if (message.id == requestUserMessage.id) {
                        message.copy(llmContextSnapshot = turnSnapshot)
                    } else {
                        message
                    }
                }
                currentList = messages.value.map { message ->
                    if (message.id == requestUserMessage.id) {
                        message.copy(llmContextSnapshot = turnSnapshot)
                    } else {
                        message
                    }
                }
                messages.value = currentList
                persistLlmContextSnapshot(
                    sessionId = sessionId,
                    messageId = requestUserMessage.id,
                    expectedTimestamp = requestUserMessage.timestamp,
                    snapshot = turnSnapshot
                )
            }

            // P5（Spec §8 处理顺序）：对消息副本按 prompt stage 应用有限正则，原文不动
            val promptHistory = if (promptRegexRules.isEmpty()) {
                requestHistory
            } else {
                requestHistory.map { msg ->
                    val stage = if (msg.sender == Sender.USER) {
                        com.loyea.character.core.regex.RegexStage.PROMPT_USER
                    } else {
                        com.loyea.character.core.regex.RegexStage.PROMPT_ASSISTANT
                    }
                    val (transformed, _) = com.loyea.character.core.regex.BoundedRegexEngine.applyForStage(
                        msg.content, promptRegexRules, stage, compiledPatterns
                    )
                    msg.copy(content = transformed)
                }
            }

            // ===== 历史覆盖（Spec §7.3）：预算内连续后缀，被移出的消息必须已被摘要覆盖 =====
            // 未提供模型容量配置时的保守回退：8192 − 输出预留(1024+256) − system/摘要/输入占用
            val systemEstimate = estimateTokens(promptPartsFinal.stableSystemPrompt) +
                estimateTokens(activeSession.value?.compressedSummary ?: "") +
                estimateTokens(compiledPrompt?.postHistoryBlock?.text ?: "") +
                estimateTokens(requestUserMessage?.content ?: "")
            val historyBudget = (8192L - 1024L - 256L - systemEstimate).coerceAtLeast(256L)
            var selectedHistory: List<Message> =
                LlmConversationBuilder.selectWithinBudget(promptHistory, historyBudget)
            val excludedCount = promptHistory.size - selectedHistory.size
            val coveredCount = activeSession.value?.compressedAtCount ?: 0
            if (excludedCount > coveredCount && sessionId.isNotBlank()) {
                // 将被移出的消息尚未被摘要覆盖：先同步完成摘要（成功才允许移出）
                val compressed = compressSessionPrefixNow(sessionId, excludedCount)
                if (!compressed) {
                    // 摘要失败：coverage 不推进、不静默丢历史——改为只排除已覆盖前缀并提示可重试
                    val keepFrom = minOf(coveredCount, promptHistory.size - 1)
                    selectedHistory = promptHistory.subList(keepFrom, promptHistory.size)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "上下文整理失败，本轮发送包含更多历史；可重试以重新整理",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            // 构建初始会话上下文（按目标模型能力决定图片/音频是否进入 payload）
            var conversation = buildLlmConversation(
                promptPartsFinal.stableSystemPrompt, selectedHistory,
                includeVision = includeVision,
                includeAudio = includeAudioInput,
                compressedSummary = activeSession.value?.compressedSummary ?: "",
                // 时间元数据只进回合快照（System Time），不再逐条加 [MESSAGE TIME] 前缀：
                // 逐条标签让模型在模仿自己的输出格式，是标签复述泄露的根因（2026-09-06）
                includeMessageTimestamps = false,
                allowPhysicalContext = sessionUsesSystemTime,
                allowGraphContext = enableGraphMemory.value,
                postHistoryInstructions = compiledPrompt?.postHistoryBlock?.text ?: "",
                historyBudgetTokens = historyBudget
            )
            var round = 0
            val maxRounds = 5
            var lastRoundHadTools = false // 标记最后一轮是否为工具轮，maxRounds 耗尽时需收尾占位气泡
            val executedToolsSignature = mutableSetOf<String>()
            // 多模态失败降级重试标记：带图/带音频请求报错后，仅允许自动去掉媒体重试一次
            var degradedRetried = false

            try {
                while (round < maxRounds) {
                    round++
                    var streamToolCalls = emptyList<LlmToolCall>()
                    var hasError = false
                    val availableMcpTools = mcpManager.getAggregateTools().filter { tool ->
                        val lowName = tool.name.lowercase()
                        when {
                            lowName.contains("web_search") || lowName.contains("read_url") -> apiConfig.enableSearch
                            lowName.contains("send_voice_reply") -> hasTtsCapability()
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
                    // 仅在真正走了视觉路由时才禁用工具（视觉模型工具兼容性保守处理），
                    // 历史有图但当前纯文本的请求不再被剥夺工具调用能力
                    val sendTools = if (useVisionRoute) emptyList() else availableMcpTools
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
                                val displayContent = truncateIncompleteHaptic(accumulatedContent)

                                if (calculatedDuration == null) {
                                    val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                    calculatedDuration = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                }
                                currentList = messages.value.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            content = cleanFinalContent(displayContent),
                                            contentSegments = currentSegments(),
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
                                // 本轮回文本段到此收拢（此后进入工具执行阶段，文本段在分段序列中定格）
                                commitCurrentTextSegment()
                                streamToolCalls = event.calls
                            }
                            is StreamEvent.Error -> {
                                // 多模态失败降级兜底：若本次请求携带图片/音频（可能模型不识别该媒体格式），
                                // 自动去掉媒体重建会话并重试一次，而不是让整个会话卡死在错误上
                                val carriesMedia = conversation.any {
                                    !it.imageUrl.isNullOrBlank() || !it.audioUrl.isNullOrBlank()
                                }
                                if (carriesMedia && !degradedRetried) {
                                    degradedRetried = true
                                    conversation = conversation.map { msg ->
                                        var c = msg.content ?: ""
                                        if (!msg.imageUrl.isNullOrBlank()) {
                                            c = (if (c.isBlank()) "" else "$c\n") + "[图片]"
                                        }
                                        if (!msg.audioUrl.isNullOrBlank() && c.isBlank()) c = "[语音消息]"
                                        msg.copy(content = c, imageUrl = null, audioUrl = null)
                                    }
                                    // 清除错误气泡状态并进入下一轮自动重试
                                    currentList = messages.value.map { msg ->
                                        if (msg.id == aiMessageId) {
                                            msg.copy(isError = false, isStillThinking = false)
                                        } else msg
                                    }
                                    messages.value = currentList
                                    accumulatedContent = ""
                                    accumulatedThoughts = ""
                                    segments.clear()
                                    segmentCut = 0
                                } else {
                                    // 流中断/出错时保留已生成的半截内容（附错误提示），不整体覆盖丢失，并落盘保存
                                    val partialContent = accumulatedContent.trim()
                                    val errorDisplay = if (partialContent.isNotEmpty()) {
                                        "$partialContent\n\n⚠️ ${event.message.removePrefix("[错误] ")}"
                                    } else {
                                        event.message
                                    }
                                    // 错误文本与分段序列不一致：清空分段，整条退回旧渲染路径
                                    segments.clear()
                                    segmentCut = accumulatedContent.length
                                    currentList = messages.value.map { msg ->
                                        if (msg.id == aiMessageId) {
                                            msg.copy(
                                                content = errorDisplay,
                                                contentSegments = emptyList(),
                                                isStillThinking = false,
                                                isError = true
                                            )
                                        } else msg
                                    }
                                    messages.value = currentList
                                    saveMessagesAsync(sessionId, currentList)
                                    hasError = true
                                }
                            }
                            is StreamEvent.Usage -> {
                                accumulatedPromptTokens += event.promptTokens
                                accumulatedCompletionTokens += event.completionTokens
                                lastContextPromptTokens = event.promptTokens
                                accumulatedCacheHitTokens += event.promptCacheHitTokens
                                accumulatedCacheMissTokens += event.promptCacheMissTokens
                                hasRealUsage = true
                            }
                            is StreamEvent.Done -> {
                                // 最终轮（无工具）时重算总耗时，让 "Thought for Xs" 覆盖整个多轮响应
                                if (calculatedDuration == null || streamToolCalls.isEmpty()) {
                                    val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                    calculatedDuration = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                }
                                // 完成后根据用户是否干预过，自动折叠 Thinking
                                currentList = messages.value.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            isStillThinking = streamToolCalls.isNotEmpty(),
                                            // 多轮 Agent 式：中间轮有工具继续 → 思考块保持展开；最终轮（无工具）→ 自动折叠
                                            isThoughtsExpanded = if (msg.hasUserToggledThoughts) msg.isThoughtsExpanded else streamToolCalls.isNotEmpty(),
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

                                // 首轮 AI 回复完成后，尝试用 LLM 精修会话标题（静默失败、仅一次）
                                if (streamToolCalls.isEmpty() && accumulatedContent.isNotBlank()) {
                                    maybeGenerateSmartTitle(sessionId, history)
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
                        lastRoundHadTools = true
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
                            val customActionText = when {
                                toolCall.name.lowercase().contains("web_search") -> {
                                    val query = parsedArgs.get("query")?.toString() ?: ""
                                    if (query.isNotEmpty()) "搜索网页：$query" else "搜索网页"
                                }
                                toolCall.name.lowercase().contains("read_url") -> {
                                    val url = parsedArgs.get("url")?.toString() ?: ""
                                    if (url.isNotEmpty()) "打开网页：$url" else "打开网页"
                                }
                                else -> translateToolName(toolCall.name)
                            }
                            val runningCall = McpCall(
                                id = displayCallId,
                                toolName = toolCall.name,
                                actionText = customActionText,
                                status = McpStatus.RUNNING,
                                input = toolCall.argumentsJson
                            )
                            
                            // 更新 UI 展示 RUNNING 状态（同时把工具卡追加进分段序列，让卡片出现在文本段之间）
                            segments.add(MessageContentSegment("tool", mcpCallId = displayCallId))
                            currentList = updateAiMessage(currentList, aiMessageId) {
                                it.copy(
                                    mcpCalls = it.mcpCalls + runningCall,
                                    contentSegments = segments.toList()
                                )
                            }
                            messages.value = currentList

                            val isVoiceReply = toolCall.name.equals("send_voice_reply", ignoreCase = true) || 
                                               toolCall.name.endsWith("__send_voice_reply", ignoreCase = true) || 
                                               toolCall.name.endsWith(".send_voice_reply", ignoreCase = true)

                            // 检查是否重复调用了相同参数的工具以防陷入死循环
                            val toolSignature = "${toolCall.name}::${toolCall.argumentsJson.trim()}"
                            val isDuplicate = executedToolsSignature.contains(toolSignature)

                            // 执行实际的工具请求
                            var toolOutput: String
                            var success: Boolean

                            val useSystemTime = activeSession.value?.useSystemTime ?: false
                            val isWebSearch = toolCall.name.equals("web_search", ignoreCase = true) ||
                                              toolCall.name.endsWith("__web_search", ignoreCase = true) ||
                                              toolCall.name.endsWith(".web_search", ignoreCase = true)
                            val isReadUrl = toolCall.name.equals("read_url", ignoreCase = true) ||
                                            toolCall.name.endsWith("__read_url", ignoreCase = true) ||
                                            toolCall.name.endsWith(".read_url", ignoreCase = true)

                            if (isDuplicate) {
                                toolOutput = "[系统拦截] 检测到重复的工具调用。您在本次回答中已调用过 ${toolCall.name} 且参数完全一致，请不要重复调用，直接根据已有信息组织最终语言回复用户。"
                                success = false
                            } else if (isVoiceReply) {
                                executedToolsSignature.add(toolSignature)
                                toolOutput = "语音回复已发送"
                                success = true
                            } else if (!useSystemTime && !isWebSearch && !isReadUrl) {
                                toolOutput = "Permission Denied: Physical perception is globally disabled by the user."
                                success = false
                            } else {
                                executedToolsSignature.add(toolSignature)
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
                                val cleanedText = cleanTextForTts(
                                    speechText,
                                    resolveTtsConfig().provider.contains("mimo", ignoreCase = true)
                                )
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
                                                // 会话守卫：TTS 合成期间用户可能已切换会话，
                                                // UI 更新仅在本会话仍处于前台时进行，磁盘始终写回本次流所属会话
                                                if (currentSessionId.value == sessionId) {
                                                    messages.value = currentList
                                                }
                                                saveMessagesAsync(sessionId, currentList)
                                            }
                                        }
                                    }
                                }
                            }

                            // 添加到会话上下文中
                            nextConversation.add(
                                LlmChatMessage(
                                    role = "tool",
                                    content = toolOutput.take(2000), // 截断超长工具输出，防止下一轮请求超上下文
                                    toolCallId = toolCall.id,
                                    name = toolCall.name
                                )
                            )
                        }

                        val executedToolsStr = streamToolCalls.joinToString("、") {
                            val parsedArgs = llmClient.parseArgumentsMap(it.argumentsJson)
                            when {
                                it.name.lowercase().contains("web_search") -> {
                                    val query = parsedArgs.get("query")?.toString() ?: ""
                                    if (query.isNotEmpty()) "搜索网页：$query" else "搜索网页"
                                }
                                it.name.lowercase().contains("read_url") -> {
                                    val url = parsedArgs.get("url")?.toString() ?: ""
                                    if (url.isNotEmpty()) "打开网页：$url" else "打开网页"
                                }
                                else -> translateToolName(it.name)
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
                        lastRoundHadTools = false
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

                // 工具轮耗尽 maxRounds 时的收尾：若最后一轮仍是工具轮，模型未产出最终回复，
                // 必须结束占位气泡的"思考中"状态（该状态会被落盘，不处理则重启后依然卡死）
                if (lastRoundHadTools) {
                    val finalContent = accumulatedContent.trim().ifBlank {
                        if (appLanguage.value == "en") "[System] Tool call limit reached. Please reply directly with the information you already have."
                        else "[系统] 工具调用次数已达上限，请直接根据已有信息组织回复。"
                    }
                    // 收拢最后一段文本；若模型未产出任何正文，把系统提示语作为末尾文本段，保证分段路径下可见
                    commitCurrentTextSegment()
                    if (accumulatedContent.isBlank()) {
                        segments.add(MessageContentSegment("text", text = finalContent))
                    }
                    currentList = currentList.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(
                                content = cleanFinalContent(finalContent),
                                contentSegments = segments.toList(),
                                isStillThinking = false
                            )
                        } else msg
                    }
                    messages.value = currentList
                    saveMessagesAsync(sessionId, currentList)
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
                // 重新生成路径：流结束后统一归并版本（成功合并进 versions / 失败恢复旧回复，避免丢失答案）
                if (regenerateOf != null) {
                    applyRegenerateVersions(regenerateOf, aiMessageId)
                }
                isThinking.value = false
                isMcpRunning.value = false
                currentVoiceEmotion.value = null // 情感分析重置

                // —— Token 用量落库（成功/出错/取消均计入；未返回 usage 的 provider 用字符估算兜底）——
                val promptTokens = if (hasRealUsage) {
                    accumulatedPromptTokens
                } else {
                    estimateTokens(conversation.joinToString("\n") { it.content ?: "" })
                }
                val completionTokens = if (hasRealUsage) accumulatedCompletionTokens else estimateTokens(accumulatedContent)
                persistSessionTokens(
                    sessionId,
                    promptTokens,
                    completionTokens,
                    lastContext = if (hasRealUsage) lastContextPromptTokens else promptTokens,
                    cacheHitTokens = accumulatedCacheHitTokens,
                    cacheMissTokens = accumulatedCacheMissTokens
                )
            }
        }
    }

    /**
     * 累计本会话 token 用量到存储与内存（加性，跨多次调用累加）。
     * lastContext 仅主聊天流传入（覆盖上下文窗口展示值），其它调用传 null 保留旧值。
     * cacheHitTokens/cacheMissTokens 仅主聊天流传入（DeepSeek 前缀缓存），其它调用传 null 不累计。
     */
    private fun persistSessionTokens(
        sessionId: String,
        promptTokens: Long,
        completionTokens: Long,
        lastContext: Long? = null,
        cacheHitTokens: Long? = null,
        cacheMissTokens: Long? = null
    ) {
        if (sessionId.isBlank() || (promptTokens <= 0L && completionTokens <= 0L)) return
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.updateSessionTokens(sessionId, promptTokens, completionTokens, lastContext, cacheHitTokens, cacheMissTokens)
            withContext(Dispatchers.Main) {
                sessions.value = sessions.value.map { s ->
                    if (s.id == sessionId) {
                        s.copy(
                            promptTokens = s.promptTokens + promptTokens,
                            completionTokens = s.completionTokens + completionTokens,
                            lastContextTokens = lastContext ?: s.lastContextTokens,
                            promptCacheHitTokens = s.promptCacheHitTokens + (cacheHitTokens ?: 0),
                            promptCacheMissTokens = s.promptCacheMissTokens + (cacheMissTokens ?: 0)
                        )
                    } else s
                }
            }
        }
    }

    /** 收敛连续换行：3+ 个 \n 折为 2 个（保留段落分隔），修复思考块剥离残留 + 模型输出导致的连续空行 */
    private fun collapseReplyNewlines(s: String): String = s.replace(Regex("\\n{2,}"), "\n\n")

    /** 段文本：折叠空行 + 去段首残留空行；保留段尾段落空行，让文本段与工具卡之间留空隙 */
    private fun cleanSegmentText(s: String): String = collapseReplyNewlines(s).trimStart('\n', '\r')

    /** 最终全文：折叠 + 去首尾（思考块剥离残留 + 结尾空行） */
    private fun cleanFinalContent(s: String): String = collapseReplyNewlines(s).trim('\n', '\r')

    /** 截掉未闭合的 [haptic: 尾部，避免半截标签进正文（抽取自流式 Content 事件处理） */
    private fun truncateIncompleteHaptic(s: String): String {
        return try {
            val lastOpen = s.lastIndexOf('[')
            if (lastOpen != -1 && lastOpen > s.lastIndexOf(']')) {
                val tail = s.substring(lastOpen)
                if ("[haptic:".startsWith(tail) || tail.startsWith("[haptic:")) {
                    s.substring(0, lastOpen)
                } else s
            } else s
        } catch (e: Exception) {
            s
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
            lowName.contains("read_url") || lowName.contains("fetch_url") || lowName.contains("open_url") -> "读取网页正文"
            else -> "执行操作: ${name.substringAfterLast(".")}"
        }
    }

    private fun buildLlmConversation(
        systemPrompt: String?,
        history: List<Message>,
        includeVision: Boolean = true,
        includeAudio: Boolean = true,
        compressedSummary: String = "",
        includeMessageTimestamps: Boolean = false,
        allowPhysicalContext: Boolean = true,
        allowGraphContext: Boolean = true,
        postHistoryInstructions: String = "",
        historyBudgetTokens: Long = 0L
    ): List<LlmChatMessage> = LlmConversationBuilder.build(
        systemPrompt = systemPrompt,
        history = history,
        includeVision = includeVision,
        includeAudio = includeAudio,
        compressedSummary = compressedSummary,
        includeMessageTimestamps = includeMessageTimestamps,
        allowPhysicalContext = allowPhysicalContext,
        allowGraphContext = allowGraphContext,
        postHistoryInstructions = postHistoryInstructions,
        historyBudgetTokens = historyBudgetTokens
    )

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
                // 会话守卫：磁盘始终写入参数指定的会话；仅当用户仍停留该会话时才回写 UI，
                // 防止流式保存与切会话的竞态把旧会话消息覆盖到新会话界面
                if (currentSessionId.value == sessionId) {
                    messages.value = finalMsgs
                }
            }
        }
    }

    /** 只补写 provider 快照，不把尚未完成的 AI 占位气泡提前持久化。 */
    private fun persistLlmContextSnapshot(
        sessionId: String,
        messageId: String,
        expectedTimestamp: Long,
        snapshot: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.updateSessionMessages(sessionId) { diskMessages ->
                diskMessages.map { message ->
                    if (
                        message.id == messageId &&
                        message.timestamp == expectedTimestamp &&
                        message.llmContextSnapshot.isNullOrBlank()
                    ) {
                        message.copy(llmContextSnapshot = snapshot)
                    } else {
                        message
                    }
                }
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

    /** 跨协程唯一消息 ID 生成器（时间戳 + 自增序号），杜绝同毫秒碰撞与并发重入混用 */
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
    private fun newMessageId(): String {
        val seq = idCounter.incrementAndGet()
        return if (seq == 1L) System.currentTimeMillis().toString() else "${System.currentTimeMillis()}_$seq"
    }

    /** 会话内消息计数（按会话隔离，避免全局计数器跨会话误触发记忆提炼） */
    private val messageCountBySession = mutableMapOf<String, Int>()

    private fun checkAndTriggerMemorySummaryAsync(sessionId: String) {
        val enableMemory = prefs.getBoolean("enable_memory_consolidation", true)
        if (!enableMemory) return

        val triggerThreshold = prefs.getInt("memory_consolidation_trigger_count", 10)
        val sessionCount = (messageCountBySession[sessionId] ?: 0) + 1
        messageCountBySession[sessionId] = sessionCount
        if (sessionCount >= triggerThreshold) {
            messageCountBySession[sessionId] = 0
            if (!enqueueMemoryConsolidation(sessionId)) {
                // 入队失败时保留“已达阈值”状态，让下一条消息可以重试，而不是静默再等一个完整周期。
                messageCountBySession[sessionId] = triggerThreshold
            }
        }
    }

    /**
     * 统一创建记忆整理任务，并把 WorkManager 的同步异常隔离在 UI 事件之外。
     * 返回 false 表示任务未成功入队；终态监听使用 first，避免每次点击遗留永久 collect 协程。
     */
    private fun enqueueMemoryConsolidation(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false

        return try {
            val workRequest = OneTimeWorkRequestBuilder<com.loyea.worker.MemoryConsolidationWorker>()
                .setInputData(workDataOf("session_id" to sessionId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                "memory_consolidation_$sessionId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            viewModelScope.launch(Dispatchers.Main) {
                try {
                    workManager.getWorkInfoByIdFlow(workRequest.id).first { workInfo ->
                        workInfo != null && workInfo.state in setOf(
                            WorkInfo.State.SUCCEEDED,
                            WorkInfo.State.FAILED,
                            WorkInfo.State.CANCELLED
                        )
                    }
                    sessions.value = withContext(Dispatchers.IO) {
                        storageManager.loadSessionList()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "监听记忆总结任务失败: $sessionId", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("ChatViewModel", "记忆总结任务入队失败: $sessionId", e)
            false
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

    /**
     * 保存全局默认匹配配置（WorldInfo 2.0：书级未覆盖时所有书继承的默认层）。
     * 书级配置覆盖在世界书详情页编辑（WorldInfoLibraryScreen）。
     */
    fun saveWorldInfoConfig(config: WorldInfoConfig) {
        WorldInfoConfigStorage.save(prefs, config)
        worldInfoConfig.value = config
    }

    /**
     * 当前会话生效书预览（W4 聊天页面板，Spec §6.4）。
     * 计数为书级全量（含被关条目），与书库页展示一致。
     */
    data class ActiveBookInfo(
        val source: com.loyea.storage.worldinfo.ActiveBookSource,
        val bookId: String?,
        val bookName: String,
        val isOwned: Boolean,
        val totalEntries: Int,
        val constantEntries: Int,
        val disabledEntries: Int,
        val sourceDeleted: Boolean = false
    )

    /** 解析某会话当前的生效书摘要（书库解析 + 书级计数）。 */
    suspend fun loadActiveBookInfo(sessionId: String): ActiveBookInfo = withContext(Dispatchers.IO) {
        val resolution = storageManager.worldInfoLibrary.resolveActiveBook(
            sessionId = sessionId,
            characterId = activeCharacterCard.value?.id,
            defaultConfig = worldInfoConfig.value
        )
        val book = resolution.book
        if (book == null) {
            return@withContext ActiveBookInfo(resolution.source, null, "", false, 0, 0, 0)
        }
        val overview = runCatching {
            storageManager.worldInfoLibrary.bookOverview(book.id)
        }.getOrNull()
        ActiveBookInfo(
            source = resolution.source,
            bookId = book.id,
            bookName = book.name,
            isOwned = book.isOwned,
            totalEntries = overview?.totalEntries ?: 0,
            constantEntries = overview?.constantEntries ?: 0,
            disabledEntries = overview?.disabledEntries ?: 0,
            sourceDeleted = overview?.sourceDeleted ?: false
        )
    }

    /**
     * 世界观关键词匹配并拼接注入块（WorldInfo 2.0：单一生效书，Spec §4.2）。
     * - 条目与配置来自 [WorldInfoLibrary.resolveActiveBook]（core 格式，已应用 override 过滤），
     *   匹配内核仍走 character-core WorldInfoMatcher（父 Spec §10 单一实现）
     * - "system" 关键词扫描源使用角色卡 systemPrompt（persona）近似系统设定
     * - 概率随机源用「会话 id + 最后一条用户消息」稳定种子：同轮重试可复现同一注入集合 → 保前缀缓存
     */
    private suspend fun buildWorldInfoBlock(sessionId: String, history: List<Message>): String? {
        val seedKey = sessionId + "|" +
            history.lastOrNull { it.sender == Sender.USER }?.content.orEmpty()
        val resolution = storageManager.worldInfoLibrary.resolveActiveBook(
            sessionId = sessionId,
            characterId = activeCharacterCard.value?.id,
            defaultConfig = worldInfoConfig.value
        )
        if (resolution.entries.isEmpty()) return null
        return com.loyea.character.core.worldinfo.WorldInfoMatcher.worldInfoBlockFor(
            entries = resolution.entries,
            historyContents = history.map { it.content },
            userName = userName.value,
            systemPrompt = activeCharacterCard.value?.systemPrompt.orEmpty(),
            config = resolution.config,
            random = kotlin.random.Random(seedKey.hashCode().toLong())
        )
    }

    /**
     * 导入卡编译路径（Spec §5.1）：单一生效书匹配后，
     * 由 CharacterCompiler 按固定顺序产出结构化提示词（WorldInfo 2.0 Spec §4.2：
     * 替代"卡书 + legacy 桶并行"合流，条目与配置来自 [WorldInfoLibrary.resolveActiveBook]）。
     * 配置优先级（Spec §6.1）：卡/书显式设置 > Loyea 默认；递归轮次上限沿用用户设置。
     */
    private suspend fun buildCompiledTurn(
        document: com.loyea.character.core.api.CharacterDocument,
        card: CharacterCard,
        sessionId: String,
        history: List<Message>,
        promptRegexRules: List<com.loyea.character.core.regex.RegexRule> = emptyList(),
        compiledPatterns: Map<String, com.google.re2j.Pattern> = emptyMap(),
        sessionUsesSystemTime: Boolean,
        physicalPerceptionEnabled: Boolean,
        enableSearch: Boolean,
        enableHaptic: Boolean,
        enableVoice: Boolean,
        enableAdultContent: Boolean,
        graphMemory: String?,
        snapshotTime: Long
    ): com.loyea.character.core.prompt.CharacterCompiler.PreparedCharacterTurn {
        val profile = document.profile
        val seedKey = sessionId + "|" +
            history.lastOrNull { it.sender == Sender.USER }?.content.orEmpty()

        // —— 单一生效书（WorldInfo 2.0）：条目已应用 override 过滤，配置已按书覆盖合并 ——
        val resolution = storageManager.worldInfoLibrary.resolveActiveBook(
            sessionId = sessionId,
            characterId = profile.id,
            defaultConfig = worldInfoConfig.value
        )
        val entries = resolution.entries
        val combinedConfig = resolution.config

        val hostBlocks = PromptAssembler.buildHostProtocolBlocks(
            userName = userName.value,
            useSystemTime = sessionUsesSystemTime,
            physicalPerceptionEnabled = physicalPerceptionEnabled,
            enableSearch = enableSearch,
            enableHaptic = enableHaptic,
            enableVoice = enableVoice,
            enableAdultContent = enableAdultContent,
            trustedCard = card.isBuiltIn
        ).mapIndexed { index, block ->
            com.loyea.character.core.api.PromptBlock(
                sourceId = "host.$index",
                category = com.loyea.character.core.api.PromptBlockCategory.HOST,
                text = block.text,
                slot = com.loyea.character.core.api.PromptBlock.SLOT_HOST
            )
        }
        // 缓存命中（Spec §5.3）：图谱记忆随会话内容逐轮变化，只进回合快照（冻结到用户消息），
        // system 消息仅保留稳定的角色/世界书/核心记忆 → 字节级稳定前缀得以保留
        val memoryBlocks = PromptAssembler.buildMemoryBlocks(
            coreMemories = activeSession.value?.coreMemories ?: emptyList(),
            graphMemory = null,
            useSystemTime = sessionUsesSystemTime
        ).mapIndexed { index, block ->
            com.loyea.character.core.api.PromptBlock(
                sourceId = "memory.$index",
                category = com.loyea.character.core.api.PromptBlockCategory.MEMORY,
                text = block.text,
                slot = com.loyea.character.core.api.PromptBlock.SLOT_MEMORY
            )
        }

        // Spec §8：世界书扫描在 prompt stage 正则转换之后进行
        val turnContents = if (promptRegexRules.isEmpty()) {
            history.map { it.content }
        } else {
            history.map { msg ->
                val stage = if (msg.sender == Sender.USER) {
                    com.loyea.character.core.regex.RegexStage.PROMPT_USER
                } else {
                    com.loyea.character.core.regex.RegexStage.PROMPT_ASSISTANT
                }
                com.loyea.character.core.regex.BoundedRegexEngine.applyForStage(
                    msg.content, promptRegexRules, stage, compiledPatterns
                ).first
            }
        }
        val turnInput = com.loyea.character.core.api.TurnInput(
            requestId = java.util.UUID.randomUUID().toString(),
            sessionId = activeSession.value?.id ?: "",
            bindingRevision = activeSession.value?.bindingRevision ?: 1L,
            characterRevision = profile.revision,
            generationKind = "normal",
            historyContents = turnContents,
            userName = userName.value,
            worldInfoEntries = entries,
            worldInfoConfig = combinedConfig,
            randomSeed = seedKey.hashCode().toLong()
        )
        return com.loyea.character.core.prompt.CharacterCompiler.prepare(
            profile = profile,
            input = turnInput,
            hostBlocks = hostBlocks,
            memoryBlocks = memoryBlocks
        )
    }

    /**
     * 长会话惰性压缩：消息数超阈值时，将滑窗（末尾 20 条）之外的旧消息增量压缩为早期摘要。
     * - 摘要持久化到 ChatSession.compressedSummary，断点记入 compressedAtCount（增量，避免重复压缩）
     * - 独立协程异步执行，不阻塞发送；防重入；失败静默（断点未推进，下次触发自然重试）
     */
    private fun maybeCompressSession(sessionId: String) {
        if (sessionId.isBlank()) return
        if (isCompressing) return
        val activeSession = activeSession.value ?: return
        val total = messages.value.size
        if (total < compressTriggerCount) return
        if (total - compressTailCount <= activeSession.compressedAtCount) return // 无新增可压缩段
        isCompressing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 以磁盘完整消息为准（内存可能只加载了尾部）
                val fullMessages = storageManager.loadSessionMessages(sessionId)
                if (fullMessages.size < compressTriggerCount) return@launch
                val bound = fullMessages.size - compressTailCount
                val compressStart = activeSession.compressedAtCount
                if (bound <= compressStart) return@launch

                val existingSummary = activeSession.compressedSummary
                val segment = fullMessages.subList(compressStart, bound)
                val segmentText = segment.joinToString("\n") {
                    "${if (it.sender == Sender.USER) "用户" else "AI"}: ${it.content.take(500)}"
                }

                // 摘要模型：优先提炼专用模型（memory_api_config_id），否则当前激活模型
                val memoryApiId = prefs.getString("memory_api_config_id", "") ?: ""
                val targetConfig = if (memoryApiId.isBlank()) {
                    activeApiConfig.value
                } else {
                    apiConfigList.value.find { it.id == memoryApiId } ?: activeApiConfig.value
                }

                val summaryInput = BackgroundPromptTemplates.compressionInput(existingSummary, segmentText)

                val response = llmClient.sendChatCompletion(
                    config = targetConfig,
                    systemPrompt = BackgroundPromptTemplates.CONVERSATION_COMPRESSION_SYSTEM,
                    history = listOf(
                        Message(
                            id = "conversation-compression-input",
                            content = summaryInput,
                            sender = Sender.USER
                        )
                    )
                )
                // 压缩也计入会话用量（系统调用），但不更新上下文窗口展示值
                persistSessionTokens(
                    sessionId,
                    promptTokens = response.promptTokens ?:
                        estimateTokens(BackgroundPromptTemplates.CONVERSATION_COMPRESSION_SYSTEM) + estimateTokens(summaryInput),
                    completionTokens = response.completionTokens ?: estimateTokens(response.content),
                    lastContext = null
                )
                if (!response.isError && response.content.isNotBlank()) {
                    val newSummary = response.content.trim()
                    storageManager.updateSessionCompression(sessionId, newSummary, bound)
                    withContext(Dispatchers.Main) {
                        sessions.value = sessions.value.map { s ->
                            if (s.id == sessionId) {
                                s.copy(compressedSummary = newSummary, compressedAtCount = bound)
                            } else {
                                s
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isCompressing = false
            }
        }
    }

    /**
     * 同步压缩会话前缀到 bound（Spec §7.3 / 验收 M01-M03）：
     * 把 [0, bound) 范围内未覆盖的消息增量摘要；成功返回 true 并推进 coverage，
     * 失败返回 false（coverage 不推进、原始消息保留、可重试）。
     * 防重入：与后台压缩共用 isCompressing。
     */
    private suspend fun compressSessionPrefixNow(sessionId: String, bound: Int): Boolean {
        if (sessionId.isBlank() || bound <= 0) return false
        if (isCompressing) return false
        isCompressing = true
        try {
            val fullMessages = storageManager.loadSessionMessages(sessionId)
            val compressStart = activeSession.value?.compressedAtCount ?: 0
            if (bound <= compressStart) return true
            if (bound > fullMessages.size) return false

            val existingSummary = activeSession.value?.compressedSummary ?: ""
            val segment = fullMessages.subList(compressStart, bound)
            val segmentText = segment.joinToString("\n") {
                "${if (it.sender == Sender.USER) "用户" else "AI"}: ${it.content.take(500)}"
            }

            val memoryApiId = prefs.getString("memory_api_config_id", "") ?: ""
            val targetConfig = if (memoryApiId.isBlank()) {
                activeApiConfig.value
            } else {
                apiConfigList.value.find { it.id == memoryApiId } ?: activeApiConfig.value
            }

            val summaryInput = BackgroundPromptTemplates.compressionInput(existingSummary, segmentText)
            val response = llmClient.sendChatCompletion(
                config = targetConfig,
                systemPrompt = BackgroundPromptTemplates.CONVERSATION_COMPRESSION_SYSTEM,
                history = listOf(
                    Message(
                        id = "conversation-compression-input",
                        content = summaryInput,
                        sender = Sender.USER
                    )
                )
            )
            persistSessionTokens(
                sessionId,
                promptTokens = response.promptTokens ?:
                    estimateTokens(BackgroundPromptTemplates.CONVERSATION_COMPRESSION_SYSTEM) + estimateTokens(summaryInput),
                completionTokens = response.completionTokens ?: estimateTokens(response.content),
                lastContext = null
            )
            if (!response.isError && response.content.isNotBlank()) {
                val newSummary = response.content.trim()
                storageManager.updateSessionCompression(sessionId, newSummary, bound)
                withContext(Dispatchers.Main) {
                    sessions.value = sessions.value.map { s ->
                        if (s.id == sessionId) {
                            s.copy(compressedSummary = newSummary, compressedAtCount = bound)
                        } else {
                            s
                        }
                    }
                }
                return true
            }
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            isCompressing = false
        }
    }

    fun triggerManualMemorySummary(sessionId: String): Boolean =
        enqueueMemoryConsolidation(sessionId)

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
            "enable_audio_understanding" -> {
                val v = value as Boolean
                enableAudioUnderstanding.value = v
                prefs.edit().putBoolean("enable_audio_understanding", v).apply()
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
            "stt_provider_template" -> {
                val v = value as String
                sttProviderTemplate.value = v
                prefs.edit().putString("stt_provider_template", v).apply()
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

    fun updateAdultContentSetting(enabled: Boolean) {
        enableAdultContent.value = enabled
        prefs.edit().putBoolean("enable_adult_content", enabled).apply()
    }

    /**
     * 外部 MCP 工具授权开关：
     * 首次管理（白名单尚为 null）时以当前已发现工具全集为基底构建白名单，避免误杀旧配置；
     * 之后为严格白名单模式，未授权工具不可见、不可调用。
     */
    fun updateMcpToolAuthorization(toolFullName: String, enabled: Boolean) {
        val base = mcpToolWhitelist.value ?: mcpManager.getAllRemoteToolsForAuth().map { it.second }.toSet()
        val newSet = base.toMutableSet().apply {
            if (enabled) add(toolFullName) else remove(toolFullName)
        }
        mcpToolWhitelist.value = newSet
        prefs.edit().putStringSet("mcp_tool_whitelist", newSet).apply()
    }

    /** 授权页专用：返回所有已连接外部服务器的全部工具（服务器名, 工具全名），不过滤 */
    fun getAllMcpToolsForAuth(): List<Pair<String, String>> = mcpManager.getAllRemoteToolsForAuth()

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
                        // 编辑后分段序列失效，退回旧路径整段渲染
                        contentSegments = emptyList(),
                        // 用户正文和发送时刻已改变，旧 provider 上下文快照必须失效并按新回合重建
                        llmContextSnapshot = null,
                        llmTimeZoneId = java.util.TimeZone.getDefault().id,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    msg
                }
            }

            storageManager.updateSessionMessages(sessionId) {
                truncatedMsgs
            }

            // Spec 7.3 / M03：被摘要覆盖的消息被编辑（且后续被截断）时，
            // 相应范围摘要失效——清空 coverage，随后从保留的原始消息重建
            if ((activeSession.value?.compressedAtCount ?: 0) > index) {
                storageManager.updateSessionCompression(sessionId, "", 0)
                withContext(Dispatchers.Main) {
                    sessions.value = sessions.value.map { s ->
                        if (s.id == sessionId) s.copy(compressedSummary = "", compressedAtCount = 0) else s
                    }
                }
            }

            withContext(Dispatchers.Main) {
                messages.value = truncatedMsgs
                startAiResponseStream(sessionId, truncatedMsgs, activeCharacterCard.value)
            }
        }
    }

    fun startRecording() {
        if (isRecording.value) return
        stopAudio() // 录音前停止正在播放的音频（含自动 TTS），防止回声循环
        isRecordingActive = true
        amplitudeList.clear()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 延迟 200ms 避让可能正在运行的 NoiseProvider 背景录音
                kotlinx.coroutines.delay(200)
                
                val cacheDir = context.cacheDir
                audioFile = File(cacheDir, "record_${System.currentTimeMillis()}.wav")
                
                val sampleRate = 16000
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                
                if (minBufferSize == android.media.AudioRecord.ERROR || minBufferSize == android.media.AudioRecord.ERROR_BAD_VALUE) {
                    throw IllegalStateException("AudioRecord min buffer size error")
                }
                
                val record = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize
                )
                
                if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed")
                }
                
                audioRecord = record
                record.startRecording()
                isRecordingWav = true
                
                // 开启后台录音写入线程
                val rawPcmFile = File(cacheDir, "temp_${System.currentTimeMillis()}.pcm")
                recordingThread = Thread {
                    val buffer = ShortArray(minBufferSize / 2)
                    try {
                        java.io.FileOutputStream(rawPcmFile).use { fos ->
                            while (isRecordingWav) {
                                val readSize = record.read(buffer, 0, buffer.size)
                                if (readSize > 0) {
                                    val byteBuffer = java.nio.ByteBuffer.allocate(readSize * 2)
                                    byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    for (i in 0 until readSize) {
                                        byteBuffer.putShort(buffer[i])
                                        val amp = kotlin.math.abs(buffer[i].toInt())
                                        if (amp > recordingAmplitude.value) {
                                            recordingAmplitude.value = amp.toFloat()
                                        }
                                    }
                                    fos.write(byteBuffer.array())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error in recording thread", e)
                    }
                }.apply { start() }
                
                withContext(Dispatchers.Main) {
                    isRecording.value = true
                    recordingDuration.value = 0
                    recordingAmplitude.value = 0f
                    
                    recordingTimer = Timer()
                    recordingTimer?.scheduleAtFixedRate(object : TimerTask() {
                        override fun run() {
                            viewModelScope.launch(Dispatchers.Main) {
                                recordingDuration.value += 1
                                amplitudeList.add(recordingAmplitude.value.toInt())
                                recordingAmplitude.value = 0f
                            }
                        }
                    }, 0, 100)
                }
            } catch (e: Exception) {
                isRecordingActive = false
                Log.e("ChatViewModel", "Failed to start recording with AudioRecord", e)
            }
        }
    }

    fun stopRecording(onFinished: (File?, Int) -> Unit) {
        isRecordingActive = false
        if (!isRecording.value) {
            recordingTimer?.cancel()
            recordingTimer = null
            isRecordingWav = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {}
            audioRecord = null
            recordingThread = null
            onFinished(null, 0)
            return
        }
        
        recordingTimer?.cancel()
        recordingTimer = null
        isRecordingWav = false
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error stopping AudioRecord", e)
            }
            audioRecord = null
            
            try {
                recordingThread?.join(1000)
            } catch (e: Exception) {}
            recordingThread = null
            
            var durationSec = recordingDuration.value / 10
            var isQuiet = false
            
            val pcmFiles = context.cacheDir.listFiles { _, name -> name.startsWith("temp_") && name.endsWith(".pcm") }
            val rawPcmFile = pcmFiles?.maxByOrNull { it.lastModified() }
            
            val finalWav = audioFile
            if (rawPcmFile != null && rawPcmFile.exists() && finalWav != null) {
                try {
                    val pcmLen = rawPcmFile.length()
                    java.io.FileOutputStream(finalWav).use { fos ->
                        writeWavHeader(
                            fos,
                            pcmLen,
                            pcmLen + 36,
                            16000L,
                            1,
                            16000L * 2
                        )
                        java.io.FileInputStream(rawPcmFile).use { fis ->
                            val buffer = ByteArray(4096)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    try { rawPcmFile.delete() } catch (e: Exception) {}
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error converting PCM to WAV", e)
                }
            }
            
            val maxAmp = amplitudeList.maxOrNull() ?: 0
            if (amplitudeList.isNotEmpty() && maxAmp < 150) {
                isQuiet = true
            }
            
            withContext(Dispatchers.Main) {
                isRecordingActive = false
                isRecording.value = false
                val file = audioFile
                audioFile = null
                if (isQuiet && file != null) {
                    try { file.delete() } catch (e: Exception) {}
                    android.widget.Toast.makeText(context, "未检测到说话声音，请检查麦克风权限或设备硬件", android.widget.Toast.LENGTH_LONG).show()
                    onFinished(null, 0)
                } else {
                    onFinished(file, durationSec)
                }
            }
        }
    }

    private fun writeWavHeader(
        out: java.io.FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte() // WAVE
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // fmt
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // Subchunk1Size
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat (1 for PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        header[36] = 'd'.toByte() // data
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun cleanTextForTts(rawText: String, isMiMo: Boolean = false): String {
        if (rawText.isBlank()) return ""
        // Spec 7.2：TTS 只朗读叙事/对白正文——先剔除 HTML 状态面板与标签，再走既有清洗链
        var text = HtmlDisplaySplitter.narrativeText(rawText)
        if (text.isBlank()) return ""
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
        // 9. 非 MiMo 服务商（阿里/火山/OpenAI 等）不支持 (风格)/[音频] 语气标签：
        //    统一剥离，避免 TTS 把括号内容当正文朗读（如"温柔你回来了"）。
        //    MiMo 官方原生支持：句首 (风格) 风格标签 + 句中 [音频标签] 细粒度语气控制，予以保留。
        if (!isMiMo) {
            text = text.replace(Regex("\\[[^\\]\\n]{1,20}\\]"), "")
            text = text.replace(Regex("[（(][^（）()\\n]{1,20}[）)]"), "")
        }
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
        val sessionId = currentSessionId.value // 捕获调用时所属会话，合成完成写回时做会话守卫

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
            val cleanedText = cleanTextForTts(
                speechText,
                resolveTtsConfig().provider.contains("mimo", ignoreCase = true)
            )
            
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
                                    val updatedMsgs = messages.value.map { msg ->
                                        if (msg.id == parentMessageId) {
                                            msg.copy(mcpCalls = msg.mcpCalls.map { c ->
                                                if (c.id == mcpCallId) c.copy(status = McpStatus.SUCCESS, output = voicePayload) else c
                                            })
                                        } else {
                                            msg
                                        }
                                    }
                                    // 会话守卫：重新合成期间用户可能已切换会话，UI 仅在本会话前台时更新
                                    if (currentSessionId.value == sessionId) {
                                        messages.value = updatedMsgs
                                    }
                                    saveMessagesAsync(sessionId, updatedMsgs)
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
        
        val cleanedText = cleanTextForTts(
            text,
            resolveTtsConfig().provider.contains("mimo", ignoreCase = true)
        )
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
            // 捕获所属会话的消息快照，避免合成期间用户切会话导致写错会话文件
            val originMsgs = messages.value
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
                        val updatedMsgs = originMsgs.map { msg ->
                            if (msg.id == messageId) {
                                msg.copy(audioUrl = ttsFile.absolutePath, audioDuration = duration)
                            } else {
                                msg
                            }
                        }
                        // 会话守卫：合成期间用户可能已切走，UI 仅在本会话前台时更新，磁盘写回本会话
                        if (currentSessionId.value == sessionId) {
                            messages.value = updatedMsgs
                        }
                        saveMessagesAsync(sessionId, updatedMsgs)
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

    fun playAudioUrl(messageId: String, audioUrl: String) {
        if (currentlyPlayingAudioId.value == messageId) {
            stopAudio()
            return
        }
        val file = File(audioUrl)
        if (file.exists()) {
            playAudioFile(messageId, file)
        } else {
            Log.e("ChatViewModel", "Audio file not found: $audioUrl")
            android.widget.Toast.makeText(context, "音频文件不存在或已损坏", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun playAudioFile(messageId: String, file: File) {
        // 录音进行中禁止播放任何音频（含自动 TTS），防止扬声器声音被麦克风录成回声再发给 AI
        if (isRecording.value) return
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
            currentlyPlayingAudioProgress.value = 0f
            audioProgressJob = viewModelScope.launch(Dispatchers.Main) {
                while (isActive) {
                    val player = mediaPlayer
                    if (player != null && player.isPlaying && player.duration > 0) {
                        currentlyPlayingAudioProgress.value = player.currentPosition.toFloat() / player.duration.toFloat()
                    }
                    kotlinx.coroutines.delay(50)
                }
            }
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
        
        audioProgressJob?.cancel()
        audioProgressJob = null
        currentlyPlayingAudioProgress.value = 0f
        
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

    val lastAsrError: String?
        get() = llmClient.lastAsrError

    private fun cleanVoiceText(inputJson: String?): String {
        if (inputJson.isNullOrBlank()) return ""
        val isJsonLike = inputJson.contains("\"text\"") && inputJson.contains(":")
        val text = if (isJsonLike) {
            try {
                val regex = Regex("""\"text\"\s*:\s*\"([\s\S]*?)\"""")
                val match = regex.find(inputJson)
                val extracted = match?.groupValues?.get(1)
                if (!extracted.isNullOrBlank()) extracted else inputJson
            } catch (e: Exception) {
                inputJson
            }
        } else {
            inputJson
        }
        
        if (text.isBlank()) return ""
        
        var result = text.replace(Regex("\\([\\s\\S]*?\\)"), "")
        result = result.replace(Regex("（[\\s\\S]*?）"), "")
        result = result.replace(Regex("\\[[\\s\\SLock]*?\\]"), "")
        result = result.replace(Regex("【[\\s\\S]*?】"), "")
        result = result.replace(Regex("\\{[\\s\\S]*?\\}"), "")
        result = result.replace(Regex("<[\\s\\S]*?>"), "")
        
        result = result.replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "    ")
            .replace("\\\\", "\\")
            
        return result.trim()
    }

    /**
     * 解析语音转写目标配置：
     * 显式指定了 stt_config_id 时优先使用它；未指定时自动优先使用已配置的小米 MiMo
     * （DeepSeek 等纯文本提供商没有 /audio/transcriptions 端点，转写必然失败）。
     */
    private fun resolveSttConfig(): ApiConfig {
        val sttCfgId = sttConfigId.value
        if (sttCfgId.isNotBlank()) {
            return apiConfigList.value.find { it.id == sttCfgId } ?: activeApiConfig.value
        }
        return apiConfigList.value.firstOrNull { it.provider.contains("mimo", ignoreCase = true) }
            ?: activeApiConfig.value
    }

    /** TTS 合成配置解析：优先用户指定的 TTS 配置，否则回退当前主模型配置 */
    private fun resolveTtsConfig(): ApiConfig {
        val ttsCfgId = ttsConfigId.value
        return if (ttsCfgId.isNotBlank()) {
            apiConfigList.value.find { it.id == ttsCfgId } ?: activeApiConfig.value
        } else {
            activeApiConfig.value
        }
    }

    // ===== 多模态能力检测：决定请求 payload 能否携带图片/音频，避免纯文本模型（如 DeepSeek）直接 400 =====

    /** 视觉能力判断：白名单服务商 + 模型名匹配视觉型号 */
    private fun providerSupportsVision(provider: String, model: String): Boolean {
        val p = provider.lowercase()
        val m = model.lowercase()
        return when {
            p.contains("anthropic") || p.contains("google") -> true // 全系原生支持视觉
            p.contains("openai") ->
                listOf("4o", "4.1", "4.5", "omni", "gpt-4-vision", "gpt-4-turbo").any { m.contains(it) }
            p.contains("alibaba") || p.contains("zhipu") || p.contains("moonshot") ->
                listOf("vl", "vision", "4v", "glm-4v", "kimi").any { m.contains(it) }
            p.contains("openrouter") ->
                listOf("vision", "vl", "4o", "4.5", "omni", "gemini", "claude").any { m.contains(it) }
            else -> false
        }
    }

    /** 音频输入（input_audio）能力判断：目前仅 OpenAI 音频模型与 Gemini 支持 */
    private fun providerSupportsAudioInput(provider: String, model: String): Boolean {
        val p = provider.lowercase()
        val m = model.lowercase()
        return (p.contains("openai") && (m.contains("omni") || m.contains("4o") || m.contains("audio"))) ||
            (p.contains("google") && m.contains("gemini"))
    }

    /** TTS 是否真正可用：显式配置了 TTS 服务商，或当前配置属于支持 TTS 的服务商（MiMo/OpenAI/阿里/火山） */
    private fun hasTtsCapability(): Boolean {
        if (ttsConfigId.value.isNotBlank()) return true
        val provider = activeApiConfig.value.provider.lowercase()
        if (provider.contains("mimo") || provider.contains("openai") ||
            provider.contains("alibaba") || provider.contains("volcengine")) return true
        return apiConfigList.value.any { it.provider.equals("MiMo", ignoreCase = true) }
    }

    suspend fun transcribeAudio(file: File): String? {
        val targetSttConfig = resolveSttConfig()
        val rawText = llmClient.transcribeAudio(targetSttConfig, file, sttModelName.value, sttProviderTemplate.value)
        return if (targetSttConfig.provider.contains("mimo", ignoreCase = true) || sttProviderTemplate.value.contains("mimo", ignoreCase = true)) {
            cleanVoiceText(rawText)
        } else {
            rawText
        }
    }

    fun transcribeAndSendAudio(file: File, duration: Int, onFailed: (String) -> Unit = {}) {
        // 重入拦截：AI 回复流式输出中禁止语音路径并发发起第二轮请求（转写耗时窗口内流可能仍在运行）
        if (responseJob?.isActive == true) {
            onFailed("AI 正在回复中，请稍候再说话")
            return
        }
        // 音频理解模式：仅当当前主模型支持音频输入（input_audio）时才直接发送语音，
        // 否则自动降级走 STT 转写文本（DeepSeek 等纯文本模型收到 input_audio 会直接 400）
        if (enableAudioUnderstanding.value &&
            providerSupportsAudioInput(activeApiConfig.value.provider, activeApiConfig.value.modelName)
        ) {
            sendMessage("", null, file.absolutePath, duration)
            return
        }

        isThinking.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val targetSttConfig = resolveSttConfig()
                val text = llmClient.transcribeAudio(targetSttConfig, file, sttModelName.value, sttProviderTemplate.value)

                val cleanedText = if (targetSttConfig.provider.contains("mimo", ignoreCase = true) || sttProviderTemplate.value.contains("mimo", ignoreCase = true)) {
                    cleanVoiceText(text)
                } else {
                    text
                }

                // 开启了声学情绪感知时，根据语音输入识别一个模拟的语气/情绪状态
                if (enableVoiceEmotionPerception.value && !cleanedText.isNullOrBlank()) {
                    val lowerText = cleanedText.lowercase()
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
                    if (!cleanedText.isNullOrBlank()) {
                        sendMessage(cleanedText, null, file.absolutePath, duration)
                    } else {
                        val errorReason = llmClient.lastAsrError ?: "未提取到有效文字"
                        onFailed(errorReason)
                    }
                }
            } catch (e: Exception) {
                // 兜底：任何转写异常都不能让会话卡死（isThinking 必须复位，错误需上报给用户）
                Log.e("ChatViewModel", "ASR transcribe error", e)
                withContext(Dispatchers.Main) {
                    isThinking.value = false
                    onFailed(e.localizedMessage ?: "语音转写异常，请重试")
                }
            }
        }
    }

    fun updateMessageContent(messageId: String, newContent: String) {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            var updatedMsgs = emptyList<Message>()
            storageManager.updateSessionMessages(sessionId) { diskMsgs ->
                val updated = diskMsgs.map { msg ->
                    if (msg.id == messageId) msg.copy(content = newContent) else msg
                }
                updatedMsgs = updated
                updated
            }
            withContext(Dispatchers.Main) {
                messages.value = updatedMsgs
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
            id = newMessageId(),
            content = "/draw $prompt",
            sender = Sender.USER,
            characterId = activeCard.id
        )
        // 2. AI 占位消息
        val aiMessageId = newMessageId()
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

                        // 将最终图片和消息更新（会话守卫：生图期间用户可能已切走，UI 仅在本会话前台时更新）
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
                            if (currentSessionId.value == sessionId) {
                                messages.value = currentList
                            }
                            saveMessagesAsync(sessionId, currentList)
                        }
                    }
                } else {
                    // 生图失败（会话守卫同成功分支）
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
                    if (currentSessionId.value == sessionId) {
                        messages.value = currentList
                    }
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
        isRecordingActive = false
        mcpManager.stop()
        stopPerceptionSensors()
        stopAudio()
        isRecordingWav = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {}
        try {
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
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
                val errors = mutableListOf<String>()

                for (url in urls) {
                    if (success) break
                    try {
                        val request = okhttp3.Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (!body.isNullOrBlank() && isValidTemplateJson(body)) {
                                    jsonResult = body
                                    success = true
                                } else {
                                    errors.add("响应数据为空或格式不匹配")
                                }
                            } else {
                                errors.add("HTTP 错误 ${response.code}")
                            }
                        }
                    } catch (e: Exception) {
                        errors.add(e.localizedMessage ?: e.message ?: "网络超时")
                    }
                }

                if (success) {
                    prefs.edit().putString("multimodal_templates_json", jsonResult).apply()
                    withContext(Dispatchers.Main) {
                        loadTemplatesFromJson(jsonResult)
                        updateTemplatesStatus.value = "更新成功！已同步云端最新模板配置"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        updateTemplatesStatus.value = "更新失败（${errors.joinToString("；")}），已保留本地内置模板。请确认仓库根目录 assets/multimodal_templates.json 已推送到 GitHub"
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

    /**
     * 校验云端拉取的模板 JSON 能否解析为合法模板结构，避免把损坏数据写入本地缓存
     */
    private fun isValidTemplateJson(json: String): Boolean {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, List<TtsTemplate>>>() {}.type
            val map: Map<String, List<TtsTemplate>> = Gson().fromJson(json, type)
            map["tts"]?.isNotEmpty() == true
        } catch (e: Exception) {
            false
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
