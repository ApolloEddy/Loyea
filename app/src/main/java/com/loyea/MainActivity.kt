package com.loyea

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.loyea.ui.main.MainScreen
import com.loyea.ui.settings.ApiConfig
import com.loyea.ui.settings.SettingsScreen
import com.loyea.ui.settings.ThemeMode
import com.loyea.ui.theme.LoyeaTheme
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.CharacterCard
import com.loyea.ui.chat.TavernCardParser
import com.loyea.ui.chat.TavernScreen
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 SharedPreferences 用于数据本地持久化
        val prefs = getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)

        setContent {
            // 1. 全局亮暗色主题管理 (本地读取)
            val savedThemeName = remember { prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name }
            val currentThemeState = remember { mutableStateOf(ThemeMode.valueOf(savedThemeName)) }
            val currentTheme = currentThemeState.value
            val darkTheme = when (currentTheme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // 2. 全局自定义用户名管理 (本地读取)
            val savedName = remember { prefs.getString("user_name", "Loyea Developer") ?: "Loyea Developer" }
            var userName by remember { mutableStateOf(savedName) }

            // 3. 全局 API 接口配置管理 (本地读取列表)
            val savedConfigsJson = remember { prefs.getString("api_config_list", "") ?: "" }
            val initialConfigs = remember<List<ApiConfig>> {
                if (savedConfigsJson.isNotBlank()) {
                    try {
                        val type = object : TypeToken<List<ApiConfig>>() {}.type
                        Gson().fromJson<List<ApiConfig>>(savedConfigsJson, type) ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            var apiConfigList by remember {
                val list = if (initialConfigs.isEmpty()) {
                    // 默认注册用户指定的 Deepseek V4 Pro 和默认 Anthropic
                    val deepseek = ApiConfig(
                        id = "ds_v4_pro",
                        name = "Deepseek V4 Pro",
                        provider = "DeepSeek",
                        apiUrl = "https://api.deepseek.com/v1",
                        apiKey = "",
                        modelName = "deepseek-chat",
                        isEnabled = true
                    )
                    val claude = ApiConfig(
                        id = "claude_3_5",
                        name = "Claude 3.5 Sonnet",
                        provider = "Anthropic",
                        apiUrl = "https://api.anthropic.com",
                        apiKey = "",
                        modelName = "claude-3-5-sonnet",
                        isEnabled = true
                    )
                    val defaultList = listOf(deepseek, claude)
                    prefs.edit().putString("api_config_list", Gson().toJson(defaultList)).apply()
                    defaultList
                } else {
                    initialConfigs
                }
                mutableStateOf(list)
            }

            var activeConfigId by remember {
                val savedActiveId = prefs.getString("active_config_id", "") ?: ""
                val initialId = if (savedActiveId.isNotEmpty() && apiConfigList.any { it.id == savedActiveId }) {
                    savedActiveId
                } else {
                    apiConfigList.firstOrNull()?.id ?: ""
                }
                mutableStateOf(initialId)
            }

            val apiConfig = remember(apiConfigList, activeConfigId) {
                apiConfigList.find { it.id == activeConfigId } ?: ApiConfig()
            }

            // 4. 全局语言配置管理 (本地读取)
            val savedLang = remember { prefs.getString("app_language", "zh") ?: "zh" }
            var appLanguage by remember { mutableStateOf(savedLang) }

            // 5. 全局气泡颜色配置管理 (本地读取)
            val savedBubbleColor = remember { prefs.getString("user_bubble_color", "") ?: "" }
            var userBubbleColor by remember { mutableStateOf(savedBubbleColor) }

            // 6. 聊天会话本地持久化管理器与状态
            val storageManager = remember { ChatStorageManager(this@MainActivity) }
            val scope = rememberCoroutineScope()

            val initializedSessions = remember(appLanguage) {
                var list = storageManager.loadSessionList()
                if (list.isEmpty()) {
                    val defaultSession = ChatSession(
                        id = System.currentTimeMillis().toString(),
                        title = if (appLanguage == "en") "Welcome Chat" else "欢迎会话",
                        lastActiveTime = System.currentTimeMillis(),
                        characterId = "char_loyea_default"
                    )
                    list = listOf(defaultSession)
                    storageManager.saveSessionList(list)
                    
                    val defaultMsgs = listOf(
                        Message(
                            id = System.currentTimeMillis().toString(),
                            content = if (appLanguage == "en") "Hello! I'm Loyea. How can I help you today?" else "你好！我是 Loyea。今天我能帮您做点什么？",
                            sender = Sender.AI,
                            characterId = "char_loyea_default"
                        )
                    )
                    storageManager.saveSessionMessages(defaultSession.id, defaultMsgs)
                }
                list.sortedByDescending { it.lastActiveTime }
            }
            var sessions by remember { mutableStateOf(initializedSessions) }

            var currentSessionId by remember {
                val savedId = prefs.getString("current_session_id", "") ?: ""
                val initialId = if (savedId.isNotEmpty() && sessions.any { it.id == savedId }) {
                    savedId
                } else {
                    sessions.firstOrNull()?.id ?: ""
                }
                mutableStateOf(initialId)
            }

            var messages by remember(currentSessionId) {
                mutableStateOf(storageManager.loadSessionMessages(currentSessionId))
            }

            var characterCardList by remember { mutableStateOf(storageManager.loadCharacterCards()) }

            val activeCharacterCard = remember(currentSessionId, characterCardList, sessions) {
                val currentSession = sessions.find { it.id == currentSessionId }
                val charId = currentSession?.characterId ?: "char_loyea_default"
                characterCardList.find { it.id == charId }
                    ?: characterCardList.firstOrNull { it.id == "char_loyea_default" }
                    ?: TavernCardParser.getBuiltInCards().first()
            }

            LoyeaTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "main") {
                        // 启动直接导航至主界面 (取消登录欢迎页)
                        composable("main") {
                            MainScreen(
                                userName = userName,
                                apiConfig = apiConfig,
                                apiConfigList = apiConfigList,
                                onActiveConfigChange = { configId ->
                                    activeConfigId = configId
                                    prefs.edit().putString("active_config_id", configId).apply()
                                },
                                appLanguage = appLanguage,
                                userBubbleColor = userBubbleColor,
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                messages = messages,
                                onMessagesChange = { newMessages ->
                                    messages = newMessages
                                    val sessionId = currentSessionId
                                    scope.launch(Dispatchers.IO) {
                                        storageManager.saveSessionMessages(sessionId, newMessages)
                                    }

                                    // 检测并自动更新会话标题 (如果是新会话默认标题，并且有了用户发送的第一条消息)
                                    val currentSession = sessions.find { it.id == sessionId }
                                    if (currentSession != null) {
                                        val isDefaultTitle = currentSession.title == "新会话" || 
                                                            currentSession.title == "New Chat" || 
                                                            currentSession.title.startsWith("欢迎会话") || 
                                                            currentSession.title.startsWith("Welcome Chat")
                                        
                                        val firstUserMsg = newMessages.firstOrNull { it.sender == Sender.USER }
                                        if (isDefaultTitle && firstUserMsg != null) {
                                            val rawContent = firstUserMsg.content
                                            val cleanTitle = if (rawContent.length > 15) {
                                                rawContent.take(15) + "..."
                                            } else {
                                                rawContent
                                            }
                                            
                                            val updatedSessions = sessions.map {
                                                if (it.id == sessionId) {
                                                    it.copy(title = cleanTitle, lastActiveTime = System.currentTimeMillis())
                                                } else {
                                                    it
                                                }
                                            }.sortedByDescending { it.lastActiveTime }
                                            
                                            sessions = updatedSessions
                                            scope.launch(Dispatchers.IO) {
                                                storageManager.saveSessionList(updatedSessions)
                                            }
                                        }
                                    }
                                },
                                onSessionSelect = { sessionId ->
                                    currentSessionId = sessionId
                                    prefs.edit().putString("current_session_id", sessionId).apply()
                                },
                                onSessionDelete = { deleteId ->
                                    storageManager.deleteSession(deleteId)
                                    val updatedSessions = storageManager.loadSessionList()
                                    sessions = updatedSessions
                                    
                                    if (currentSessionId == deleteId) {
                                        val nextSession = updatedSessions.firstOrNull()
                                        if (nextSession != null) {
                                            currentSessionId = nextSession.id
                                            prefs.edit().putString("current_session_id", nextSession.id).apply()
                                        } else {
                                            // 无会话时，自动创建一个
                                            val defaultSessionId = System.currentTimeMillis().toString()
                                            val defaultSession = ChatSession(
                                                id = defaultSessionId,
                                                title = if (appLanguage == "en") "Welcome Chat" else "欢迎会话",
                                                lastActiveTime = System.currentTimeMillis(),
                                                characterId = "char_loyea_default"
                                            )
                                            val newList = listOf(defaultSession)
                                            sessions = newList
                                            storageManager.saveSessionList(newList)
                                            
                                            val defaultMsgs = listOf(
                                                Message(
                                                    id = System.currentTimeMillis().toString(),
                                                    content = if (appLanguage == "en") "Hello! I'm Loyea. How can I help you today?" else "你好！我是 Loyea。今天我能帮您做点什么？",
                                                    sender = Sender.AI,
                                                    characterId = "char_loyea_default"
                                                )
                                            )
                                            storageManager.saveSessionMessages(defaultSessionId, defaultMsgs)
                                            
                                            currentSessionId = defaultSessionId
                                            prefs.edit().putString("current_session_id", defaultSessionId).apply()
                                        }
                                    }
                                },
                                onNewChatClick = { selectedChar ->
                                    val newSessionId = System.currentTimeMillis().toString()
                                    val newSession = ChatSession(
                                        id = newSessionId,
                                        title = if (appLanguage == "en") "New Chat" else "新会话",
                                        lastActiveTime = System.currentTimeMillis(),
                                        characterId = selectedChar.id
                                    )
                                    val updatedSessions = (listOf(newSession) + sessions).sortedByDescending { it.lastActiveTime }
                                    sessions = updatedSessions
                                    storageManager.saveSessionList(updatedSessions)

                                    val initialMsgs = listOf(
                                        Message(
                                            id = System.currentTimeMillis().toString(),
                                            content = selectedChar.firstMessage.ifBlank {
                                                if (appLanguage == "en") "Hello! I'm ${selectedChar.name}. How can I help you today?" else "你好！我是 ${selectedChar.name}。今天我能帮您做点什么？"
                                            },
                                            sender = Sender.AI,
                                            characterId = selectedChar.id
                                        )
                                    )
                                    storageManager.saveSessionMessages(newSessionId, initialMsgs)

                                    currentSessionId = newSessionId
                                    prefs.edit().putString("current_session_id", newSessionId).apply()
                                },
                                activeCharacterCard = activeCharacterCard,
                                characterCardList = characterCardList,
                                onTavernClick = {
                                    navController.navigate("tavern")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        // 角色卡酒馆管理页面
                        composable("tavern") {
                            TavernScreen(
                                characterCardList = characterCardList,
                                onCharacterCardListSave = { newList ->
                                    characterCardList = newList
                                    scope.launch(Dispatchers.IO) {
                                        storageManager.saveCharacterCards(newList)
                                    }
                                },
                                appLanguage = appLanguage,
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        
                        // 设置管理页面
                        composable("settings") {
                            SettingsScreen(
                                currentTheme = currentTheme,
                                onThemeChange = { newTheme ->
                                    currentThemeState.value = newTheme
                                    prefs.edit().putString("theme_mode", newTheme.name).apply()
                                },
                                userName = userName,
                                onUserNameSave = { newName ->
                                    userName = newName
                                    prefs.edit().putString("user_name", newName).apply()
                                },
                                apiConfigList = apiConfigList,
                                activeConfigId = activeConfigId,
                                onApiConfigListSave = { newList ->
                                    apiConfigList = newList
                                    prefs.edit().putString("api_config_list", Gson().toJson(newList)).apply()
                                },
                                onActiveConfigSelect = { activeId ->
                                    activeConfigId = activeId
                                    prefs.edit().putString("active_config_id", activeId).apply()
                                    // 自动把 SharedPreferences 里的 api_model 同步更新，保证其他依赖它的模块正常
                                    val activeConfig = apiConfigList.find { it.id == activeId }
                                    if (activeConfig != null) {
                                        prefs.edit()
                                            .putString("api_provider", activeConfig.provider)
                                            .putString("api_url", activeConfig.apiUrl)
                                            .putString("api_key", activeConfig.apiKey)
                                            .putString("api_model", activeConfig.modelName)
                                            .apply()
                                    }
                                },
                                appLanguage = appLanguage,
                                onAppLanguageChange = { newLang ->
                                    appLanguage = newLang
                                    prefs.edit().putString("app_language", newLang).apply()
                                },
                                userBubbleColor = userBubbleColor,
                                onUserBubbleColorChange = { newColor ->
                                    userBubbleColor = newColor
                                    prefs.edit().putString("user_bubble_color", newColor).apply()
                                },
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
