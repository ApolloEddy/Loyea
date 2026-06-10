package com.loyea.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.loyea.ui.settings.ApiConfig
import com.loyea.ui.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
    private val storageManager = ChatStorageManager(context)
    private val llmClient = LlmClient()

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

    init {
        loadAllData()
    }

    private fun loadAllData() {
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
                    if (config.provider.equals("DeepSeek", ignoreCase = true) && config.modelName == "deepseek-chat") {
                        updated = true
                        config.copy(modelName = "deepseek-v4-pro")
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
                name = "Deepseek V4 Pro",
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
                name = "Deepseek V4 Flash",
                provider = "DeepSeek",
                apiUrl = "https://api.deepseek.com/v1",
                apiKey = "",
                modelName = "deepseek-v4-flash",
                isEnabled = true,
                enableSearch = false,
                enableReasoning = true
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

        // 加载角色卡
        characterCardList.value = storageManager.loadCharacterCards()

        // 加载会话列表
        loadSessions()
    }

    private fun loadSessions() {
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
        sessions.value = list.sortedByDescending { it.lastActiveTime }

        val savedSessionId = prefs.getString("current_session_id", "") ?: ""
        val initialSessionId = if (savedSessionId.isNotEmpty() && sessions.value.any { it.id == savedSessionId }) {
            savedSessionId
        } else {
            sessions.value.firstOrNull()?.id ?: ""
        }
        selectSession(initialSessionId)
    }

    fun selectSession(sessionId: String) {
        currentSessionId.value = sessionId
        prefs.edit().putString("current_session_id", sessionId).apply()
        messages.value = storageManager.loadSessionMessages(sessionId)
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
        loadSessions()
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
            storageManager.saveSessionMessages(sessionId, updated)
        }
    }

    // --- 会话与对话处理业务逻辑 ---

    fun deleteSession(deleteId: String) {
        storageManager.deleteSession(deleteId)
        val updatedSessions = storageManager.loadSessionList()
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
                sessions.value = newList
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
                selectSession(defaultSessionId)
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
        storageManager.saveSessionList(updatedSessions)

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
        storageManager.saveSessionMessages(newSessionId, initialMsgs)
        selectSession(newSessionId)
    }

    /**
     * 发送用户消息并触发 SSE 真实的流式输出
     */
    fun sendMessage(inputText: String) {
        if (inputText.isBlank()) return
        val activeCard = activeCharacterCard.value
        val userMsg = Message(
            id = System.currentTimeMillis().toString(),
            content = inputText,
            sender = Sender.USER,
            characterId = activeCard.id
        )
        val updatedMsgs = messages.value + userMsg
        messages.value = updatedMsgs
        
        val sessionId = currentSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.saveSessionMessages(sessionId, updatedMsgs)
        }

        // 更新会话标题
        updateSessionTitleIfNeeded(sessionId, updatedMsgs)

        // SSE 流式接收
        startAiResponseStream(sessionId, updatedMsgs, activeCard)
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
                
                val updatedSessions = sessions.value.map {
                    if (it.id == sessionId) {
                        it.copy(title = cleanTitle, lastActiveTime = System.currentTimeMillis())
                    } else {
                        it
                    }
                }.sortedByDescending { it.lastActiveTime }
                
                sessions.value = updatedSessions
                viewModelScope.launch(Dispatchers.IO) {
                    storageManager.saveSessionList(updatedSessions)
                }
            }
        }
    }

    private fun startAiResponseStream(
        sessionId: String,
        history: List<Message>,
        characterCard: CharacterCard
    ) {
        viewModelScope.launch {
            isThinking.value = true
            
            val aiMessageId = (System.currentTimeMillis() + 1).toString()
            val startTime = System.currentTimeMillis()

            // 首先创建一个占位的空 AI 回复消息
            val placeholderAiMsg = Message(
                id = aiMessageId,
                content = "",
                sender = Sender.AI,
                thoughts = null,
                isThoughtsExpanded = false,
                thoughtDurationSeconds = 0,
                isStillThinking = true, // 指示正在思考/输出中
                characterId = characterCard.id
            )
            
            var currentList = messages.value + placeholderAiMsg
            messages.value = currentList

            var accumulatedContent = ""
            var accumulatedThoughts = ""

            // 开始 SSE 流式接收
            try {
                llmClient.sendChatCompletionStream(
                    config = activeApiConfig.value,
                    systemPrompt = PromptAssembler.assembleSystemPrompt(
                        card = characterCard, 
                        userName = userName.value, 
                        useSystemTime = activeSession.value?.useSystemTime ?: false
                    ),
                    history = history
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Thoughts -> {
                            accumulatedThoughts += event.text
                            // 实时更新 AI 消息中的思考链内容
                            currentList = currentList.map { msg ->
                                if (msg.id == aiMessageId) {
                                    msg.copy(
                                        thoughts = accumulatedThoughts,
                                        isThoughtsExpanded = true
                                    )
                                } else {
                                    msg
                                }
                            }
                            messages.value = currentList
                        }
                        is StreamEvent.Content -> {
                            accumulatedContent += event.text
                            // 收到正文了，如果还在 isStillThinking，我们可以逐步将其移除思考状态
                            currentList = currentList.map { msg ->
                                if (msg.id == aiMessageId) {
                                    val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                    msg.copy(
                                        content = accumulatedContent,
                                        isStillThinking = false, // 正文开始，大模型不再是仅仅在思考
                                        thoughtDurationSeconds = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                    )
                                } else {
                                    msg
                                }
                            }
                            messages.value = currentList
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
                            val duration = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                            currentList = currentList.map { msg ->
                                if (msg.id == aiMessageId) {
                                    msg.copy(
                                        isStillThinking = false,
                                        thoughtDurationSeconds = if (accumulatedThoughts.isNotEmpty()) duration else 0
                                    )
                                } else {
                                    msg
                                }
                            }
                            messages.value = currentList
                            // 存盘持久化
                            launch(Dispatchers.IO) {
                                storageManager.saveSessionMessages(sessionId, currentList)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                currentList = currentList.map { msg ->
                    if (msg.id == aiMessageId) {
                        msg.copy(
                            content = "[错误] SSE 数据流接收异常: ${e.localizedMessage}",
                            isStillThinking = false,
                            isError = true
                        )
                    } else {
                        msg
                    }
                }
                messages.value = currentList
            } finally {
                isThinking.value = false
            }
        }
    }

    /**
     * 切换当前会话的“使用真实时间”状态并保存
     */
    fun toggleCurrentSessionSystemTime() {
        val sessionId = currentSessionId.value
        if (sessionId.isBlank()) return
        val currentList = sessions.value
        val updated = currentList.map { session ->
            if (session.id == sessionId) {
                session.copy(useSystemTime = session.useSystemTime != true)
            } else {
                session
            }
        }
        sessions.value = updated
        storageManager.saveSessionList(updated)
    }
}
