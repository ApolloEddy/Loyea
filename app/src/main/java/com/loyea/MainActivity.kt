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

            // 3. 全局 API 接口配置管理 (本地读取)
            val savedProvider = remember { prefs.getString("api_provider", "Anthropic") ?: "Anthropic" }
            val savedUrl = remember { prefs.getString("api_url", "https://api.anthropic.com") ?: "https://api.anthropic.com" }
            val savedKey = remember { prefs.getString("api_key", "") ?: "" }
            val savedModel = remember { prefs.getString("api_model", "claude-3-5-sonnet") ?: "claude-3-5-sonnet" }
            
            var apiConfig by remember { 
                mutableStateOf(
                    ApiConfig(
                        provider = savedProvider,
                        apiUrl = savedUrl,
                        apiKey = savedKey,
                        modelName = savedModel
                    )
                )
            }

            // 4. 全局语言配置管理 (本地读取)
            val savedLang = remember { prefs.getString("app_language", "zh") ?: "zh" }
            var appLanguage by remember { mutableStateOf(savedLang) }

            // 5. 全局气泡颜色配置管理 (本地读取)
            val savedBubbleColor = remember { prefs.getString("user_bubble_color", "") ?: "" }
            var userBubbleColor by remember { mutableStateOf(savedBubbleColor) }

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
                                appLanguage = appLanguage,
                                userBubbleColor = userBubbleColor,
                                onApiConfigChange = { newConfig ->
                                    apiConfig = newConfig
                                    prefs.edit()
                                        .putString("api_provider", newConfig.provider)
                                        .putString("api_url", newConfig.apiUrl)
                                        .putString("api_key", newConfig.apiKey)
                                        .putString("api_model", newConfig.modelName)
                                        .apply()
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }
                        
                        // 设置管理页面 (移除了 Logout 逻辑)
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
                                apiConfig = apiConfig,
                                onApiConfigSave = { newConfig ->
                                    apiConfig = newConfig
                                    prefs.edit()
                                        .putString("api_provider", newConfig.provider)
                                        .putString("api_url", newConfig.apiUrl)
                                        .putString("api_key", newConfig.apiKey)
                                        .putString("api_model", newConfig.modelName)
                                        .apply()
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
