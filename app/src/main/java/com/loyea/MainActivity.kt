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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.loyea.ui.chat.PromptAssembler
import com.loyea.ui.chat.ChatViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val chatViewModel: ChatViewModel = viewModel()

            // 从 ViewModel 获取状态（可保证重建时能够读取到ViewModel的最新数据，不丢状态）
            val currentTheme = chatViewModel.themeMode.value
            val userName = chatViewModel.userName.value
            val apiConfigList = chatViewModel.apiConfigList.value
            val activeConfigId = chatViewModel.activeConfigId.value
            val apiConfig = chatViewModel.activeApiConfig.value
            val appLanguage = chatViewModel.appLanguage.value
            val userBubbleColor = chatViewModel.userBubbleColor.value
            val sessions = chatViewModel.sessions.value
            val currentSessionId = chatViewModel.currentSessionId.value
            val messages = chatViewModel.messages.value
            val characterCardList = chatViewModel.characterCardList.value
            val activeCharacterCard = chatViewModel.activeCharacterCard.value
            val isThinking = chatViewModel.isThinking.value

            val darkTheme = when (currentTheme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            LoyeaTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            MainScreen(
                                userName = userName,
                                apiConfig = apiConfig,
                                apiConfigList = apiConfigList,
                                onActiveConfigChange = { chatViewModel.selectActiveConfig(it) },
                                appLanguage = appLanguage,
                                userBubbleColor = userBubbleColor,
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                messages = messages,
                                isThinking = isThinking,
                                onSendMessage = { chatViewModel.sendMessage(it) },
                                onToggleThoughts = { chatViewModel.toggleThoughtsExpanded(it) },
                                onSessionSelect = { chatViewModel.selectSession(it) },
                                onSessionDelete = { chatViewModel.deleteSession(it) },
                                onNewChatClick = { chatViewModel.createNewChat(it) },
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

                        // 人格管理页面
                        composable("tavern") {
                            TavernScreen(
                                characterCardList = characterCardList,
                                onCharacterCardListSave = { chatViewModel.saveCharacterCardList(it) },
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
                                onThemeChange = { chatViewModel.changeTheme(it) },
                                userName = userName,
                                onUserNameSave = { chatViewModel.saveUserName(it) },
                                apiConfigList = apiConfigList,
                                activeConfigId = activeConfigId,
                                onApiConfigListSave = { chatViewModel.saveApiConfigList(it) },
                                onActiveConfigSelect = { chatViewModel.selectActiveConfig(it) },
                                appLanguage = appLanguage,
                                onAppLanguageChange = { chatViewModel.changeAppLanguage(it) },
                                userBubbleColor = userBubbleColor,
                                onUserBubbleColorChange = { chatViewModel.changeUserBubbleColor(it) },
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
