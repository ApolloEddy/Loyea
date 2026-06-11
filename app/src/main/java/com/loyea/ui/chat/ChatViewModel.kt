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

    private val mcpManager = McpManager(application)
    val mcpStates: StateFlow<Map<String, McpServerStatus>> = mcpManager.serverStates

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

    init {
        loadAllData()
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
            list = listOf(deepseekPro, deepseekFlash)
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
                physicalContext = physicalContextData
            )

            // 构建初始会话上下文
            var conversation = buildLlmConversation(systemPrompt, history)
            var round = 0
            val maxRounds = 5

            try {
                while (round < maxRounds) {
                    round++
                    var streamToolCalls = emptyList<LlmToolCall>()
                    val availableMcpTools = mcpManager.getAggregateTools()

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
                                currentList = currentList.map { msg ->
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
                                accumulatedContent += event.text
                                if (calculatedDuration == null) {
                                    val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                    calculatedDuration = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                }
                                currentList = currentList.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(
                                            content = accumulatedContent,
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
                                currentList = currentList.map { msg ->
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
                                currentList = currentList.map { msg ->
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
                            val runningCall = McpCall(
                                id = displayCallId,
                                toolName = toolCall.name,
                                actionText = translateToolName(toolCall.name),
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
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("ChatViewModel", "SSE Stream Error", e)
                    currentList = currentList.map { msg ->
                        if (msg.id == aiMessageId) {
                            msg.copy(
                                content = "[错误] 数据流接收异常: ${e.localizedMessage}",
                                isStillThinking = false,
                                isError = true
                            )
                        } else {
                            msg
                        }
                    }
                    messages.value = currentList
                }
            } finally {
                isThinking.value = false
                isMcpRunning.value = false
            }
        }
    }

    private fun translateToolName(name: String): String {
        val lowName = name.lowercase()
        return when {
            lowName.contains("get_location") || lowName.contains("current_location") -> "获取地理位置"
            lowName.contains("heart_rate") -> "调取实时心率"
            lowName.contains("steps") -> "查询今日步数"
            lowName.contains("sleep") -> "分析睡眠质量"
            lowName.contains("blood_pressure") -> "调取血压记录"
            lowName.contains("time") -> "同步系统时间"
            lowName.contains("physical_perception") -> "感知身体与环境状态"
            lowName.contains("weather") -> "获取天气预报"
            lowName.contains("web_search") || lowName.contains("google_search") -> "搜索实时信息"
            else -> "执行操作: ${name.substringAfterLast(".")}"
        }
    }

    private fun buildLlmConversation(systemPrompt: String?, history: List<Message>): List<LlmChatMessage> {
        val list = mutableListOf<LlmChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            list.add(LlmChatMessage(role = "system", content = systemPrompt))
        }
        history.filter {
            it.content.isNotBlank() && !it.content.startsWith("[错误]") && !it.content.startsWith("[Error]")
        }.forEach { msg ->
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

    fun setUseRealLocation(use: Boolean) {
        perceptionManager.locationProvider.setUseRealLocation(use)
        useRealLocation.value = use
    }

    fun setMockLocation(location: String) {
        perceptionManager.locationProvider.setMockLocation(location)
        mockLocation.value = location
    }

    override fun onCleared() {
        super.onCleared()
        mcpManager.stop()
    }
}
