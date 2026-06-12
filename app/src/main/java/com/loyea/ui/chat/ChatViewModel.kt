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
     * 发送用户消息并触发 SSE 真实的流式输出
     */
    fun sendMessage(inputText: String) {
        isThinking.value = true
        if (inputText.isBlank()) {
            isThinking.value = false
            return
        }
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
            characterId = activeCard.id
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
            val apiConfig = activeApiConfig.value

            // 策略调整：不再将物理信息直接塞入 System Prompt，而是作为辅助参考，
            // 且告诉 AI 如果需要最新数据必须调用工具。
            val physicalContextData = perceptionManager.buildPhysicalContextString()

            val systemPrompt = PromptAssembler.assembleSystemPrompt(
                card = characterCard,
                userName = userName.value,
                useSystemTime = activeSession.value?.useSystemTime ?: false,
                physicalContext = physicalContextData,
                enableSearch = apiConfig.enableSearch,
                coreMemories = activeSession.value?.coreMemories ?: emptyList()
            )

            // 构建初始会话上下文
            var conversation = buildLlmConversation(systemPrompt, history)
            var round = 0
            val maxRounds = 5

            try {
                while (round < maxRounds) {
                    round++
                    var streamToolCalls = emptyList<LlmToolCall>()
                    val availableMcpTools = mcpManager.getAggregateTools().filter { tool ->
                        val lowName = tool.name.lowercase()
                        when {
                            lowName.contains("web_search") -> activeApiConfig.value.enableSearch
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
                    llmClient.sendChatCompletionStream(
                        config = apiConfig,
                        messages = conversation,
                        tools = availableMcpTools
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
                            }
                        }
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
                                val query = parsedArgs?.get("query")?.toString() ?: ""
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

                            // 执行实际的工具请求
                            val toolOutput = try {
                                val result = mcpManager.callTool(
                                    prefixedToolName = toolCall.name,
                                    arguments = llmClient.parseArgumentsMap(toolCall.argumentsJson)
                                )
                                if (result.error != null) {
                                    "[MCP 错误] ${result.error.message}"
                                } else {
                                    result.result?.toString() ?: ""
                                }
                            } catch (e: Exception) {
                                "[MCP 异常] ${e.localizedMessage ?: e.message ?: "未知错误"}"
                            }

                            val success = !toolOutput.startsWith("[MCP 错误]") && !toolOutput.startsWith("[MCP 异常]")
                            
                            // 更新 UI 展示 SUCCESS/FAILED 状态
                            currentList = updateMcpCall(currentList, aiMessageId, displayCallId) {
                                it.copy(
                                    status = if (success) McpStatus.SUCCESS else McpStatus.FAILED,
                                    output = toolOutput
                                )
                            }
                            messages.value = currentList

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
                                val query = parsedArgs?.get("query")?.toString() ?: ""
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
            it.content.isNotBlank() && !it.content.startsWith("[错误]") && !it.content.startsWith("[Error]")
        }
        // 线性滑动窗口只取最新 20 条消息
        val recentHistory = filteredHistory.takeLast(20)
        recentHistory.forEach { msg ->
            list.add(
                LlmChatMessage(
                    role = if (msg.sender == Sender.USER) "user" else "assistant",
                    content = msg.content
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
            viewModelScope.launch(Dispatchers.IO) {
                triggerMemorySummaryInternal(sessionId)
            }
        }
    }

    private suspend fun triggerMemorySummaryInternal(sessionId: String) {
        if (sessionId.isBlank()) return
        val session = sessions.value.find { it.id == sessionId } ?: return
        val oldMemories = session.coreMemories
        val historyMsgs = messages.value.takeLast(20)

        // 区分“核心锁定记忆 (★ 开头)”和“普通事实记忆”
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

        try {
            val memoryApiId = prefs.getString("memory_api_config_id", "") ?: ""
            val targetConfig = if (memoryApiId.isBlank()) {
                activeApiConfig.value
            } else {
                apiConfigList.value.find { it.id == memoryApiId } ?: activeApiConfig.value
            }

            val llmResponse = llmClient.sendChatCompletion(
                config = targetConfig,
                systemPrompt = summaryPrompt,
                history = emptyList()
            )
            val responseText = llmResponse.content
            if (llmResponse.isError || responseText.isBlank()) return
            
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
                updateCoreMemories(sessionId, newMemories)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        viewModelScope.launch(Dispatchers.IO) {
            triggerMemorySummaryInternal(sessionId)
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
    }
}
