package com.loyea

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.loyea.ui.chat.ChatScreen
import com.loyea.ui.chat.ChatViewModel
import com.loyea.ui.chat.TavernScreen
import com.loyea.ui.chat.CharacterCard
import com.loyea.ui.main.MainScreen
import com.loyea.ui.settings.SettingsScreen
import com.loyea.ui.settings.ThemeMode
import com.loyea.ui.theme.LoyeaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var chatViewModel: ChatViewModel

    // Health Connect permissions request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty()) {
            Toast.makeText(this, "健康授权已更新", Toast.LENGTH_SHORT).show()
        }
    }

    private val HEALTH_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class)
    )

    override fun onStart() {
        super.onStart()
        if (::chatViewModel.isInitialized) {
            chatViewModel.startPerceptionSensors()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::chatViewModel.isInitialized) {
            if (!chatViewModel.isThinking.value && !chatViewModel.isMcpRunning.value) {
                val currId = chatViewModel.currentSessionId.value
                if (currId.isNotEmpty()) {
                    chatViewModel.selectSession(currId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 统一权限请求逻辑
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // 动态添加蓝牙运行时权限申请 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        // 动态添加录音权限申请 (环境噪音感应需要)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        val prefs = getSharedPreferences("loyea_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("use_real_location", false)) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                permissionsToRequest.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1000)
        }

        chatViewModel = androidx.lifecycle.ViewModelProvider(this)[ChatViewModel::class.java]

        // 启动自愈注册：检查是否开启了后台问候，如果开启则用 KEEP 策略启动初始延时的 GreetingWorker
        val enableBgGreeting = prefs.getBoolean("enable_background_greeting", true)
        if (enableBgGreeting) {
            val randomDelayMinutes = kotlin.random.Random.nextInt(60, 180).toLong()
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.loyea.worker.GreetingWorker>()
                .setInitialDelay(randomDelayMinutes, java.util.concurrent.TimeUnit.MINUTES)
                .addTag("loyea_bg_greeting")
                .build()
            androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "loyea_bg_greeting_work",
                androidx.work.ExistingWorkPolicy.KEEP, // KEEP 保证已存在的任务不会被重置，保留其原有的倒计时
                workRequest
            )
            Log.d("MainActivity", "Background greeting self-healing check: Active, scheduled initial delay $randomDelayMinutes mins with KEEP policy.")
        }

        setContent {
            val navController = rememberNavController()
            val currentTheme by chatViewModel.themeMode
            val darkTheme = when (currentTheme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            LoyeaTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(navController = navController, startDestination = "chat") {
                        composable("chat") {
                            val userName by chatViewModel.userName
                            val activeApiConfig by chatViewModel.activeApiConfig
                            val apiConfigList by chatViewModel.apiConfigList
                            val appLanguage by chatViewModel.appLanguage
                            val userBubbleColor by chatViewModel.userBubbleColor
                            val messages by chatViewModel.messages
                            val isThinking by chatViewModel.isThinking
                            val isMcpRunning by chatViewModel.isMcpRunning
                            val activeCharacterCard by chatViewModel.activeCharacterCard
                            val characterCardList by chatViewModel.characterCardList
                            val sessions by chatViewModel.sessions
                            val currentSessionId by chatViewModel.currentSessionId
                            val activeSession = chatViewModel.activeSession.value

                            MainScreen(
                                userName = userName,
                                apiConfig = activeApiConfig,
                                apiConfigList = apiConfigList,
                                onActiveConfigChange = { chatViewModel.selectActiveConfig(it) },
                                appLanguage = appLanguage,
                                userBubbleColor = userBubbleColor,
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                messages = messages,
                                isThinking = isThinking,
                                isMcpRunning = isMcpRunning,
                                onSendMessage = { text, img, audio, dur -> chatViewModel.sendMessage(text, img, audio, dur) },
                                onStopResponse = { chatViewModel.stopResponse() },
                                onToggleThoughts = { chatViewModel.toggleThoughtsExpanded(it) },
                                onSessionSelect = { chatViewModel.selectSession(it) },
                                onSessionDelete = { chatViewModel.deleteSession(it) },
                                onNewChatClick = { chatViewModel.createNewChat(it) },
                                activeCharacterCard = activeCharacterCard,
                                characterCardList = characterCardList,
                                onTavernClick = {
                                    chatViewModel.stopResponse()
                                    navController.navigate("tavern")
                                },
                                onNavigateToSettings = {
                                    chatViewModel.stopResponse()
                                    navController.navigate("settings")
                                },
                                onUserNameChange = { chatViewModel.saveUserName(it) },
                                useSystemTime = activeSession?.useSystemTime ?: false,
                                onToggleSystemTime = { chatViewModel.toggleCurrentSessionSystemTime() },
                                onUpdateCoreMemories = { sid, memories -> chatViewModel.updateCoreMemories(sid, memories) },
                                onTriggerManualMemorySummary = { chatViewModel.triggerManualMemorySummary() },
                                onEditMessage = { id, text -> chatViewModel.editMessage(id, text) },
                                getDraft = { chatViewModel.getDraft(it) },
                                saveDraft = { id, text -> chatViewModel.saveDraft(id, text) },
                                clearDraft = { chatViewModel.clearDraft(it) },
                                viewModel = chatViewModel
                            )
                        }
                        composable("settings") {
                            val apiConfigList by chatViewModel.apiConfigList
                            val activeConfigId by chatViewModel.activeConfigId
                            val userName by chatViewModel.userName
                            val appLanguage by chatViewModel.appLanguage
                            val userBubbleColor by chatViewModel.userBubbleColor
                            val mcpConfigs by chatViewModel.mcpConfigList
                            val mcpStates by chatViewModel.mcpStates.collectAsState()
                            val isWatchConnected by chatViewModel.isWatchConnected
                            val isWatchMoving by chatViewModel.isWatchMoving
                            val useRealLocation by chatViewModel.useRealLocation
                            val mockLocation by chatViewModel.mockLocation

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
                                mcpConfigs = mcpConfigs,
                                mcpStates = mcpStates,
                                onMcpConfigsSave = { chatViewModel.saveMcpConfigs(it) },
                                getMcpToolsForServer = { chatViewModel.getMcpToolsForServer(it) },
                                isWatchConnected = isWatchConnected,
                                onWatchConnectedChange = { chatViewModel.setWatchConnected(it) },
                                onWatchReconnect = { chatViewModel.reconnectWatch() },
                                isWatchMoving = isWatchMoving,
                                onWatchMovingChange = { chatViewModel.setWatchMoving(it) },
                                useRealLocation = useRealLocation,
                                onUseRealLocationChange = {
                                    chatViewModel.setUseRealLocation(it)
                                    if (it) {
                                        ActivityCompat.requestPermissions(
                                            this@MainActivity,
                                            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                                            1002
                                        )
                                    }
                                },
                                mockLocation = mockLocation,
                                onMockLocationSave = { chatViewModel.setMockLocation(it) },
                                onHealthConnectClick = {
                                    Log.d("MainActivity", "Health Connect Button Clicked")
                                    try {
                                        val sdkStatus = HealthConnectClient.getSdkStatus(this@MainActivity)
                                        Log.d("MainActivity", "Health Connect SDK Status: $sdkStatus")
                                        
                                        if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE) {
                                            Toast.makeText(this@MainActivity, "您的设备未安装或不支持健康连接", Toast.LENGTH_LONG).show()
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                startActivity(intent)
                                            } catch (e: Exception) {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
                                                startActivity(intent)
                                            }
                                        } else if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                                            Toast.makeText(this@MainActivity, "健康连接需要更新", Toast.LENGTH_LONG).show()
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                                                startActivity(intent)
                                            } catch (e: Exception) {}
                                        } else {
                                            // 直接在主线程调用 launch
                                            try {
                                                requestPermissionLauncher.launch(HEALTH_PERMISSIONS)
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "Launch permission failed", e)
                                                try {
                                                    startActivity(Intent("android.settings.HEALTH_CONNECT_SETTINGS"))
                                                } catch (e2: Exception) {
                                                    Toast.makeText(this@MainActivity, "无法打开授权界面，请手动设置", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "onHealthConnectClick Error", e)
                                        Toast.makeText(this@MainActivity, "异常: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                },
                                onBackClick = { navController.popBackStack() },
                                viewModel = chatViewModel
                            )
                        }
                        composable("tavern") {
                            val characterCardList by chatViewModel.characterCardList
                            val appLanguage by chatViewModel.appLanguage
                            TavernScreen(
                                characterCardList = characterCardList,
                                onCharacterCardListSave = { chatViewModel.saveCharacterCardList(it) },
                                appLanguage = appLanguage,
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::chatViewModel.isInitialized) {
            chatViewModel.stopResponse()
            chatViewModel.stopPerceptionSensors()
        }
    }
}
