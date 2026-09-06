package com.loyea.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import com.loyea.mcp.McpServerConfig
import com.loyea.mcp.McpServerStatus
import com.loyea.mcp.McpTool
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.theme.LoyeaTheme
import com.loyea.bluetooth.WatchBluetoothClient
import com.loyea.health.HealthEcosystem
import com.loyea.health.HealthMetric
import com.loyea.health.HealthPairingStatus
import com.loyea.health.MetricAvailability

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

// 二级页面枚举
enum class SettingsSubPage {
    MAIN, API_CONFIG, THEME_SETTINGS, MCP_CONFIG, PHYSICAL_SENSOR, MEMORY_SETTINGS, TOOL_AUTHORIZATION, MULTIMODAL_SETTINGS, WORLD_INFO_SETTINGS
}

// API 配置数据模型
data class ApiConfig(
    val id: String = System.currentTimeMillis().toString(),
    val name: String = "Default",
    val provider: String = "Anthropic",
    val apiUrl: String = "https://api.anthropic.com",
    val apiKey: String = "",
    val modelName: String = "claude-3-5-sonnet",
    val isEnabled: Boolean = true,
    val enableSearch: Boolean = false,
    val enableReasoning: Boolean = true,
    val enableSmartRouting: Boolean = true,
    val useIndependentSearch: Boolean = false,
    val searchProvider: String = "Tavily",
    val searchApiUrl: String = "https://api.tavily.com",
    val searchApiKey: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    userName: String,
    onUserNameSave: (String) -> Unit,
    apiConfigList: List<ApiConfig>,
    activeConfigId: String,
    onApiConfigListSave: (List<ApiConfig>) -> Unit,
    onActiveConfigSelect: (String) -> Unit,
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
    userBubbleColor: String,
    onUserBubbleColorChange: (String) -> Unit,
    mcpConfigs: List<McpServerConfig>,
    mcpStates: Map<String, McpServerStatus>,
    onMcpConfigsSave: (List<McpServerConfig>) -> Unit,
    getMcpToolsForServer: (String) -> List<McpTool>,
    isWatchConnected: Boolean,
    onWatchConnectedChange: (Boolean) -> Unit,
    onWatchReconnect: () -> Unit,
    isWatchMoving: Boolean,
    onWatchMovingChange: (Boolean) -> Unit,
    useRealLocation: Boolean,
    onUseRealLocationChange: (Boolean) -> Unit,
    mockLocation: String,
    onMockLocationSave: (String) -> Unit,
    onHealthConnectClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: com.loyea.ui.chat.ChatViewModel? = null,
    modifier: Modifier = Modifier
) {
    var subPage by remember { mutableStateOf(SettingsSubPage.MAIN) }

    // 拦截系统物理/手势返回键：若处于二级页面则退回至设置主页；已处于主页则放行以退回会话页
    androidx.activity.compose.BackHandler(enabled = subPage != SettingsSubPage.MAIN) {
        subPage = SettingsSubPage.MAIN
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    // 使用 AnimatedContent 实现极具滑移动画质感的左右推拉过场
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedContent(
            targetState = subPage,
            transitionSpec = {
                if (targetState == SettingsSubPage.MAIN) {
                    // 返回一级页：左进右出
                    (slideInHorizontally { -it } + fadeIn()).togetherWith(slideOutHorizontally { it } + fadeOut())
                } else {
                    // 进入二级页：右进左出
                    (slideInHorizontally { it } + fadeIn()).togetherWith(slideOutHorizontally { -it } + fadeOut())
                }
            },
            label = "SubPageTransition",
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth(if (isWideScreen) 0.85f else 1f)
                .widthIn(max = 720.dp)
        ) { currentPage ->
            when (currentPage) {
                SettingsSubPage.MAIN -> {
                    SettingsMainLayout(
                        currentTheme = currentTheme,
                        userName = userName,
                        onUserNameSave = onUserNameSave,
                        apiConfigList = apiConfigList,
                        activeConfigId = activeConfigId,
                        appLanguage = appLanguage,
                        userBubbleColor = userBubbleColor,
                        mcpConfigs = mcpConfigs,
                        mcpStates = mcpStates,
                        onNavigateToApi = { subPage = SettingsSubPage.API_CONFIG },
                        onNavigateToTheme = { subPage = SettingsSubPage.THEME_SETTINGS },
                        onNavigateToMcp = { subPage = SettingsSubPage.MCP_CONFIG },
                        onNavigateToSensor = { subPage = SettingsSubPage.PHYSICAL_SENSOR },
                        onNavigateToMemory = { subPage = SettingsSubPage.MEMORY_SETTINGS },
                        onNavigateToToolAuth = { subPage = SettingsSubPage.TOOL_AUTHORIZATION },
                        onNavigateToMultimodal = { subPage = SettingsSubPage.MULTIMODAL_SETTINGS },
                        onNavigateToWorldInfo = { subPage = SettingsSubPage.WORLD_INFO_SETTINGS },
                        adultContentEnabled = viewModel?.enableAdultContent?.value ?: false,
                        onAdultContentToggle = { enabled -> viewModel?.updateAdultContentSetting(enabled) },
                        onBackClick = onBackClick
                    )
                }
                SettingsSubPage.MEMORY_SETTINGS -> {
                    MemorySettingsLayout(
                        apiConfigList = apiConfigList,
                        activeConfigId = activeConfigId,
                        appLanguage = appLanguage,
                        viewModel = viewModel,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.API_CONFIG -> {
                    ApiConfigLayout(
                        apiConfigList = apiConfigList,
                        activeConfigId = activeConfigId,
                        appLanguage = appLanguage,
                        onApiConfigListSave = onApiConfigListSave,
                        onActiveConfigSelect = onActiveConfigSelect,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.THEME_SETTINGS -> {
                    ThemeSettingsLayout(
                        currentTheme = currentTheme,
                        onThemeChange = onThemeChange,
                        appLanguage = appLanguage,
                        onAppLanguageChange = onAppLanguageChange,
                        userBubbleColor = userBubbleColor,
                        onUserBubbleColorChange = onUserBubbleColorChange,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.MCP_CONFIG -> {
                    McpConfigLayout(
                        mcpConfigs = mcpConfigs,
                        mcpStates = mcpStates,
                        onMcpConfigsSave = onMcpConfigsSave,
                        getMcpToolsForServer = getMcpToolsForServer,
                        appLanguage = appLanguage,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.PHYSICAL_SENSOR -> {
                    PhysicalSensorLayout(
                        isWatchConnected = isWatchConnected,
                        onWatchConnectedChange = onWatchConnectedChange,
                        onWatchReconnect = onWatchReconnect,
                        isWatchMoving = isWatchMoving,
                        onWatchMovingChange = onWatchMovingChange,
                        useRealLocation = useRealLocation,
                        onUseRealLocationChange = onUseRealLocationChange,
                        mockLocation = mockLocation,
                        onMockLocationSave = onMockLocationSave,
                        appLanguage = appLanguage,
                        onHealthConnectClick = onHealthConnectClick,
                        healthPairingStatus = viewModel?.healthPairingStatus?.value,
                        onRefreshHealthPairing = { viewModel?.refreshHealthPairingStatus() },
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.TOOL_AUTHORIZATION -> {
                    ToolAuthorizationLayout(
                        viewModel = viewModel,
                        appLanguage = appLanguage,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.MULTIMODAL_SETTINGS -> {
                    MultimodalSettingsLayout(
                        viewModel = viewModel,
                        appLanguage = appLanguage,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
                SettingsSubPage.WORLD_INFO_SETTINGS -> {
                    WorldInfoSettingsLayout(
                        viewModel = viewModel,
                        appLanguage = appLanguage,
                        onBackClick = { subPage = SettingsSubPage.MAIN }
                    )
                }
            }
        }
    }
}

// =================== 一级设置页布局 ===================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMainLayout(
    currentTheme: ThemeMode,
    userName: String,
    onUserNameSave: (String) -> Unit,
    apiConfigList: List<ApiConfig>,
    activeConfigId: String,
    appLanguage: String,
    userBubbleColor: String,
    mcpConfigs: List<McpServerConfig>,
    mcpStates: Map<String, McpServerStatus>,
    onNavigateToApi: () -> Unit,
    onNavigateToTheme: () -> Unit,
    onNavigateToMcp: () -> Unit,
    onNavigateToSensor: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToToolAuth: () -> Unit,
    onNavigateToMultimodal: () -> Unit,
    onNavigateToWorldInfo: () -> Unit,
    adultContentEnabled: Boolean,
    onAdultContentToggle: (Boolean) -> Unit,
    onBackClick: () -> Unit
) {
    val isEn = appLanguage == "en"
    val activeConfig = remember(apiConfigList, activeConfigId) {
        apiConfigList.find { it.id == activeConfigId }
    }
    val activeName = activeConfig?.name ?: (if (isEn) "None Selected" else "未选择")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Settings" else "设置", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 系统设置分组
            Text(
                text = if (isEn) "SYSTEM SETTINGS" else "系统设置",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // API 接口二级页面入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToApi() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "API & Model Connections" else "API 与模型连接管理",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Active: $activeName (${apiConfigList.size} configured)" else "正在使用：$activeName (已保存 ${apiConfigList.size} 个模型)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // 主题、配色与多语言选项
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToTheme() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ColorLens,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "Theme, Color & Language" else "主题、配色与语言",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            val bubbleColorName = when (userBubbleColor) {
                                "#EADFD3" -> if (isEn) "Warm Amber" else "琥珀沙黄"
                                "#F0F0F2" -> if (isEn) "Morandi Gray" else "莫兰迪灰"
                                "#E2F1E8" -> if (isEn) "Emerald Green" else "微光浅绿"
                                "#DCEAF5" -> if (isEn) "Loyea Blue" else "极简天蓝"
                                else -> if (isEn) "Default" else "默认气泡"
                            }
                            val themeModeName = when (currentTheme) {
                                ThemeMode.LIGHT -> if (isEn) "Light" else "亮色"
                                ThemeMode.DARK -> if (isEn) "Dark" else "暗色"
                                ThemeMode.SYSTEM -> if (isEn) "System" else "系统"
                            }
                            val langName = if (isEn) "English" else "简体中文"
                            Text(
                                text = "$themeModeName ($bubbleColorName, $langName)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // MCP 配置二级页面入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToMcp() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "MCP Cyber Plugins" else "MCP 赛博插件管理",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            val connectedCount = mcpConfigs.count { mcpStates[it.id] == McpServerStatus.CONNECTED }
                            Text(
                                text = if (isEn) "Active: $connectedCount / ${mcpConfigs.size}" else "已连接：$connectedCount / ${mcpConfigs.size}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // 物理感知二级页面入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSensor() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Watch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "Physical Sensor & Hardware" else "物理感知与外设集成",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Smartwatch mock, Heart Rate, Location" else "智能手表模拟，心率，GPS定位",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // 核心事实记忆机制二级页面入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToMemory() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "Core Fact Memory Settings" else "核心事实记忆设置",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Configure trigger counts & memory model" else "配置自动总结触发阈值、提取专用模型等",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // 全局世界观（World Info）二级页面入口：与人格设计/记忆管理平级
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToWorldInfo() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "World Info (Global Lore)" else "World Info 世界观记忆",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Keyword-triggered global lore, cross-session" else "关键词触发的全局世界观设定，跨会话生效（兼容 SillyTavern）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // 外部工具授权二级页面入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToToolAuth() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "Tool Authorization & Privacy" else "外部工具授权与隐私",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Control which tools the AI can access & trigger" else "自主控制 AI 能调用或触发哪些物理工具及震动",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // 多模态与媒体设置二级页面入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToMultimodal() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isEn) "Multimodal & Media Settings" else "多模态与媒体设置",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Configure Image Vision, Voice Input/Output & T2I" else "配置图片识图、语音输入/朗读及 AI 生图模型",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ============ 开发者彩蛋（不对普通用户可见，亦不出现在任何设置项或文档中） ============
            // 2 秒内连点版本号 5 次，切换「成人内容模式 Beta」：允许成人间自愿性互动描写，
            // 首次开启需年龄确认；开启时版本号行尾显示极淡星号 * 作为状态提示。
            var versionTapCount by remember { mutableStateOf(0) }
            var lastVersionTapTime by remember { mutableStateOf(0L) }
            var showAdultConfirm by remember { mutableStateOf(false) }
            val easterEggContext = LocalContext.current
            val displayVersion = "Loyea v${com.loyea.BuildConfig.VERSION_NAME}"
            Text(
                text = if (adultContentEnabled) "$displayVersion *" else displayVersion,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable {
                        val now = System.currentTimeMillis()
                        versionTapCount = if (now - lastVersionTapTime < 2000) versionTapCount + 1 else 1
                        lastVersionTapTime = now
                        if (versionTapCount >= 5) {
                            versionTapCount = 0
                            if (adultContentEnabled) {
                                onAdultContentToggle(false)
                                Toast.makeText(
                                    easterEggContext,
                                    if (isEn) "Adult Content Mode disabled" else "成人内容模式已关闭",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showAdultConfirm = true
                            }
                        }
                    },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (adultContentEnabled) 0.45f else 0.25f)
            )

            // 彩蛋首次开启的年龄确认弹窗（满 18 岁方可启用）
            if (showAdultConfirm) {
                AlertDialog(
                    onDismissRequest = { showAdultConfirm = false },
                    title = { Text(if (isEn) "Enable Adult Content Mode (Beta)?" else "启用成人内容模式（Beta）？") },
                    text = {
                        Text(
                            if (isEn) {
                                "This mode allows the AI to reply with mature, open content for adult roleplay.\n\nPlease confirm:\n· You are at least 18 years old;\n· You understand this is for adult fictional roleplay only;\n· Content involving minors, non-consent or illegal acts remains strictly forbidden."
                            } else {
                                "本模式允许 AI 回复更成熟、开放的内容，用于成年人角色扮演。\n\n请确认：\n· 你已年满 18 周岁；\n· 你了解该模式仅用于成年人的虚构角色扮演；\n· 涉及未成年人、非自愿或非法内容仍被严格禁止。"
                            },
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAdultConfirm = false
                            onAdultContentToggle(true)
                            Toast.makeText(
                                easterEggContext,
                                if (isEn) "Adult Content Mode enabled" else "成人内容模式已启用",
                                Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Text(if (isEn) "I'm 18+, Enable" else "我已满 18 岁，确认启用")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAdultConfirm = false }) {
                            Text(if (isEn) "Cancel" else "取消")
                        }
                    }
                )
            }
        }
    }
}

// =================== 原地行内无边框极简编辑框 ===================
@Composable
fun InlineEditNameField(
    initialName: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var nameText by remember(initialName) { mutableStateOf(initialName) }
    var isEditing by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { isEditing = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEditing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                BasicTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (nameText.isNotBlank()) {
                                onSave(nameText)
                            }
                            isEditing = false
                            focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .padding(vertical = 2.dp)
                )
                
                Spacer(modifier = Modifier.width(6.dp))
                
                IconButton(
                    onClick = {
                        if (nameText.isNotBlank()) {
                            onSave(nameText)
                        }
                        isEditing = false
                        focusManager.clearFocus()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Save",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            // 自动索要焦点弹出键盘
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        } else {
            Text(
                text = initialName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Name",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// =================== 二级 API 配置页布局 (多连接管理) ===================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiConfigLayout(
    apiConfigList: List<ApiConfig>,
    activeConfigId: String,
    appLanguage: String,
    onApiConfigListSave: (List<ApiConfig>) -> Unit,
    onActiveConfigSelect: (String) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"

    var showSheet by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Models & API Manager" else "模型与 API 连接管理", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingConfig = null
                        showSheet = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Connection",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 全局联网搜索 API（单拎出来，不再每个模型配置一次）
                item {
                    GlobalSearchConfigCard(appLanguage = appLanguage)
                }
                if (apiConfigList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isEn) "No API connections saved.\nClick '+' on top right to add." else "暂无 API 账号连接，\n请点击右上角 '+' 按钮添加。",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(apiConfigList) { config ->
                        val isActive = config.id == activeConfigId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    1.dp,
                                    if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                                    RoundedCornerShape(12.dp)
                                )
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { 
                                    onActiveConfigSelect(config.id)
                                    Toast.makeText(context, if (isEn) "Activated: ${config.name}" else "已激活连接：${config.name}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isActive) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = config.name,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            editingConfig = config
                                            showSheet = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val updated = apiConfigList.filter { it.id != config.id }
                                            onApiConfigListSave(updated)
                                            if (isActive && updated.isNotEmpty()) {
                                                onActiveConfigSelect(updated.first().id)
                                            }
                                            Toast.makeText(context, if (isEn) "Deleted" else "已删除连接", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BadgeLabel(text = config.provider)
                                BadgeLabel(text = config.modelName)
                                if (config.apiKey.isNotBlank()) {
                                    BadgeLabel(text = "Key: ****" + config.apiKey.takeLast(4))
                                }
                                if (config.enableSearch) {
                                    BadgeLabel(text = if (isEn) "Search" else "联网")
                                }
                                if (config.enableReasoning) {
                                    BadgeLabel(text = if (isEn) "Reasoning" else "深度思考")
                                }
                            }
                        }
                    }
                }
            }

            // =================== 自定义 Claude 风格 BottomSheet / 抽屉遮罩 ===================
            AnimatedVisibility(
                visible = showSheet,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { showSheet = false }
                )
            }

            AnimatedVisibility(
                visible = showSheet,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                AddOrEditSheet(
                    editingConfig = editingConfig,
                    appLanguage = appLanguage,
                    onSave = { newOrUpdated ->
                        val updatedList = if (editingConfig == null) {
                            apiConfigList + newOrUpdated
                        } else {
                            apiConfigList.map { if (it.id == newOrUpdated.id) newOrUpdated else it }
                        }
                        onApiConfigListSave(updatedList)
                        if (editingConfig == null) {
                            onActiveConfigSelect(newOrUpdated.id)
                        }
                        showSheet = false
                    },
                    onDismiss = { showSheet = false }
                )
            }
        }
    }
}

/**
 * 全局联网搜索 API 配置卡片：凭据只配一次，所有模型配置共用（用户要求单拎出来）。
 * 未填写时回落到旧版行为（服务商原生 web_search 或免 Key 检索）。
 */
@Composable
fun GlobalSearchConfigCard(appLanguage: String) {
    val isEn = appLanguage == "en"
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("loyea_prefs", android.content.Context.MODE_PRIVATE) }

    var provider by remember { mutableStateOf(prefs.getString("global_search_provider", "Tavily") ?: "Tavily") }
    var apiUrl by remember { mutableStateOf(prefs.getString("global_search_api_url", "https://api.tavily.com") ?: "https://api.tavily.com") }
    var apiKey by remember { mutableStateOf(prefs.getString("global_search_api_key", "") ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    fun persist() {
        prefs.edit()
            .putString("global_search_provider", provider)
            .putString("global_search_api_url", apiUrl)
            .putString("global_search_api_key", apiKey)
            .apply()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isEn) "Web Search API (Global)" else "联网搜索 API（全局）",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Text(
            text = if (isEn) "Configured once, shared by every model. Leave empty to use the provider's built-in web search or keyless fallback." else "只需配置一次，所有模型共用。留空则使用服务商自带联网搜索或免 Key 检索。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable { dropdownExpanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = provider, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier.fillMaxWidth(0.8f).background(MaterialTheme.colorScheme.surface)
            ) {
                listOf("Tavily", "Custom").forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = MaterialTheme.colorScheme.onBackground) },
                        onClick = {
                            provider = option
                            if (option == "Tavily") apiUrl = "https://api.tavily.com"
                            dropdownExpanded = false
                            persist()
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it; persist() },
            singleLine = true,
            label = { Text(if (isEn) "Search API Base URL" else "搜索 API 地址", fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; persist() },
            singleLine = true,
            label = { Text(if (isEn) "Search API Key" else "搜索 API Key", fontSize = 12.sp) },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun BadgeLabel(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditSheet(
    editingConfig: ApiConfig?,
    appLanguage: String,
    onSave: (ApiConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isEn = appLanguage == "en"

    var nameInput by remember { mutableStateOf(editingConfig?.name ?: "") }
    var selectedProvider by remember { mutableStateOf(editingConfig?.provider ?: "DeepSeek") }
    var apiUrlInput by remember { mutableStateOf(editingConfig?.apiUrl ?: "https://api.deepseek.com/v1") }
    var apiKeyInput by remember { mutableStateOf(editingConfig?.apiKey ?: "") }
    var modelInput by remember { mutableStateOf(editingConfig?.modelName ?: "deepseek-v4-pro") }
    
    var enableSearch by remember { mutableStateOf(editingConfig?.enableSearch ?: false) }
    var enableReasoning by remember { mutableStateOf(editingConfig?.enableReasoning ?: true) }
    var enableSmartRouting by remember { mutableStateOf(editingConfig?.enableSmartRouting ?: true) }


    var showApiKey by remember { mutableStateOf(false) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    // 模型列表云端同步（GET /models，OpenAI 兼容约定）
    val sheetScope = rememberCoroutineScope()
    var fetchingModels by remember { mutableStateOf(false) }
    var cloudModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelFetchMessage by remember { mutableStateOf<String?>(null) }

    val providersList = listOf(
        "DeepSeek", "OpenAI", 
        "MiMo", "Kimi (Moonshot)", "Qwen (千问)", "MiniMax", "Ollama (Local)", "Groq", "Custom"
    )

    val recommendedModels = remember(selectedProvider) {
        when (selectedProvider) {
            "OpenAI" -> listOf("gpt-4o", "gpt-4o-mini", "o1-mini", "o3-mini")
            "DeepSeek" -> listOf("deepseek-v4-pro", "deepseek-v4-flash")
            "MiMo" -> listOf("mimo-v2.5-pro", "mimo-v2.5-pro-ultraspeed")
            "Kimi (Moonshot)" -> listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
            "Qwen (千问)" -> listOf("qwen-plus", "qwen-turbo", "qwen-max")
            "MiniMax" -> listOf("abab6.5g-alias", "abab7-chat")
            "Ollama (Local)" -> listOf("qwen2.5", "llama3", "mistral", "gemma2")
            "Groq" -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
            else -> emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (editingConfig == null) {
                    if (isEn) "Add Model Connection" else "添加模型连接"
                } else {
                    if (isEn) "Edit Model Connection" else "编辑模型连接"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        Column {
            Text(
                text = if (isEn) "CONNECTION ALIAS" else "连接别名 (例如 Deepseek V4 Pro)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        Text(
            text = if (isEn) "CONNECTION" else "连接",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                placeholder = { Text("e.g. Deepseek Pro", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            Text(
                text = "API PROVIDER",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .clickable { providerDropdownExpanded = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = selectedProvider, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                }

                DropdownMenu(
                    expanded = providerDropdownExpanded,
                    onDismissRequest = { providerDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f).background(MaterialTheme.colorScheme.surface)
                ) {
                    providersList.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider, color = MaterialTheme.colorScheme.onBackground) },
                            onClick = {
                                selectedProvider = provider
                                providerDropdownExpanded = false
                                when (provider) {
                                    "OpenAI" -> {
                                        apiUrlInput = "https://api.openai.com/v1"
                                        modelInput = "gpt-4o-mini"
                                    }
                                    "DeepSeek" -> {
                                        apiUrlInput = "https://api.deepseek.com/v1"
                                        modelInput = "deepseek-v4-pro"
                                    }
                                    "MiMo" -> {
                                        apiUrlInput = "https://api.xiaomimimo.com/v1"
                                        modelInput = "mimo-v2.5-pro"
                                    }
                                    "Kimi (Moonshot)" -> {
                                        apiUrlInput = "https://api.moonshot.cn/v1"
                                        modelInput = "moonshot-v1-8k"
                                    }
                                    "Qwen (千问)" -> {
                                        apiUrlInput = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                        modelInput = "qwen-plus"
                                    }
                                    "MiniMax" -> {
                                        apiUrlInput = "https://api.minimax.chat/v1"
                                        modelInput = "abab7-chat"
                                    }
                                    "Ollama (Local)" -> {
                                        apiUrlInput = "http://10.0.2.2:11434/v1"
                                        modelInput = "qwen2.5"
                                    }
                                    "Groq" -> {
                                        apiUrlInput = "https://api.groq.com/openai/v1"
                                        modelInput = "llama-3.3-70b-versatile"
                                    }
                                    "Custom" -> {
                                        apiUrlInput = ""
                                        modelInput = ""
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        Text(
            text = if (isEn) "MODEL" else "模型",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
        Column {
            Text(
                text = "API BASE URL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = apiUrlInput,
                onValueChange = { apiUrlInput = it },
                singleLine = true,
                placeholder = { Text("e.g. https://api.deepseek.com/v1", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            Text(
                text = "API KEY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                singleLine = true,
                placeholder = { Text("sk-...", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            Text(
                text = "MODEL NAME",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        if (apiUrlInput.isBlank() || apiKeyInput.isBlank()) {
                            modelFetchMessage = if (isEn) "Fill API URL and Key first" else "请先填写 API 地址和 Key"
                            return@IconButton
                        }
                        fetchingModels = true
                        modelFetchMessage = null
                        sheetScope.launch {
                            ModelCatalogClient.fetchModels(apiUrlInput, apiKeyInput, selectedProvider)
                                .onSuccess { models ->
                                    fetchingModels = false
                                    cloudModels = models
                                    modelFetchMessage = if (models.isEmpty()) {
                                        if (isEn) "Provider returned no models" else "服务商未返回模型列表"
                                    } else null
                                }
                                .onFailure { e ->
                                    fetchingModels = false
                                    modelFetchMessage = (if (isEn) "Fetch failed: " else "获取失败：") + (e.message ?: "unknown")
                                }
                        }
                    }) {
                        if (fetchingModels) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = if (isEn) "Sync model list" else "从服务商同步模型列表",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth()
            )

            if (recommendedModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    recommendedModels.forEach { model ->
                        val isSelected = modelInput == model
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { modelInput = model }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = model,
                                fontSize = 11.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // 云端同步的模型列表（来自服务商 GET /models）
            modelFetchMessage?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }
            if (cloudModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isEn) "Cloud models (${cloudModels.size})" else "云端模型（${cloudModels.size} 个）",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    cloudModels.forEach { model ->
                        val isSelected = modelInput == model
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { modelInput = model }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = model,
                                fontSize = 11.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isEn) "CAPABILITIES" else "能力",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
        // 联网搜索开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEn) "Web Search" else "联网搜索",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isEn) "Enable real-time web search capability" else "启用大模型实时网页搜索能力",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = enableSearch,
                onCheckedChange = { enableSearch = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        // 深度思考开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEn) "Deep Thinking" else "深度思考",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isEn) "Reasoning logic and chain of thought (Default ON)" else "启用推理链与深度思考过程 (默认开启)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = enableReasoning,
                onCheckedChange = { enableReasoning = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        // 智能模型路由开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEn) "Smart Model Routing" else "智能模型路由",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isEn) "Auto route between Pro/Flash models based on Deep Thinking" else "根据深度思考开关自动在 Pro/Flash 模型之间切换",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            Switch(
                checked = enableSmartRouting,
                onCheckedChange = { enableSmartRouting = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        Button(
            onClick = {
                val finalName = if (nameInput.isBlank()) "${selectedProvider} Model" else nameInput
                val newConfig = ApiConfig(
                    id = editingConfig?.id ?: System.currentTimeMillis().toString(),
                    name = finalName,
                    provider = selectedProvider,
                    apiUrl = apiUrlInput,
                    apiKey = apiKeyInput,
                    modelName = modelInput,
                    isEnabled = true,
                    enableSearch = enableSearch,
                    enableReasoning = enableReasoning,
                    enableSmartRouting = enableSmartRouting,
                    // 搜索 API 凭据已全局化（设置页「联网搜索」卡片）；旧字段原样保留以兼容历史数据
                    useIndependentSearch = editingConfig?.useIndependentSearch ?: false,
                    searchProvider = editingConfig?.searchProvider ?: "Tavily",
                    searchApiUrl = editingConfig?.searchApiUrl ?: "https://api.tavily.com",
                    searchApiKey = editingConfig?.searchApiKey ?: ""
                )
                onSave(newConfig)
            },
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 10.dp)
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isEn) "Save Connection" else "保存连接设置", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// =================== 二级主题与语言设置页 ===================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsLayout(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
    userBubbleColor: String,
    onUserBubbleColorChange: (String) -> Unit,
    onBackClick: () -> Unit
) {
    val isEn = appLanguage == "en"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Theme & Language" else "外观与语言", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 主题模式
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "THEME MODE" else "主题模式",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val themeOptions = listOf(
                        Triple(ThemeMode.SYSTEM, if (isEn) "Follow System" else "跟随系统", Icons.Default.SettingsSystemDaydream),
                        Triple(ThemeMode.LIGHT, if (isEn) "Light Theme" else "亮色模式", Icons.Default.LightMode),
                        Triple(ThemeMode.DARK, if (isEn) "Dark Theme" else "暗色模式", Icons.Default.DarkMode)
                    )

                    themeOptions.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThemeChange(option.first) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = option.third,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(option.second, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                            }
                            if (currentTheme == option.first) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (index < themeOptions.size - 1) {
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // 2. 气泡颜色
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "USER BUBBLE COLOR" else "用户气泡颜色",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val colorOptions = listOf(
                        Triple("", if (isEn) "Default" else "系统默认气泡", Color.Transparent),
                        Triple("#EADFD3", if (isEn) "Warm Amber" else "琥珀沙黄", Color(0xFFEADFD3)),
                        Triple("#F0F0F2", if (isEn) "Morandi Gray" else "莫兰迪灰", Color(0xFFF0F0F2)),
                        Triple("#E2F1E8", if (isEn) "Emerald Green" else "微光浅绿", Color(0xFFE2F1E8)),
                        Triple("#DCEAF5", if (isEn) "Minimal Blue" else "极简天蓝", Color(0xFFDCEAF5))
                    )

                    colorOptions.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUserBubbleColorChange(option.first) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (option.first.isEmpty()) 
                                                MaterialTheme.colorScheme.secondaryContainer 
                                            else 
                                                option.third
                                        )
                                        .border(
                                            1.dp, 
                                            if (option.first.isEmpty()) MaterialTheme.colorScheme.outline else Color.Transparent, 
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(option.second, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                            }
                            if (userBubbleColor == option.first) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (index < colorOptions.size - 1) {
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // 3. 应用语言
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "APPLICATION LANGUAGE" else "应用显示语言",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val langOptions = listOf(
                        "zh" to if (isEn) "Simplified Chinese" else "简体中文 (Simplified Chinese)",
                        "en" to if (isEn) "English" else "英文 (English)"
                    )

                    langOptions.forEachIndexed { index, option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAppLanguageChange(option.first) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(option.second, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                            }
                            if (appLanguage == option.first) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (index < langOptions.size - 1) {
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    var theme by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var userName by remember { mutableStateOf("Loyea Developer") }
    var appLanguage by remember { mutableStateOf("zh") }
    var userBubbleColor by remember { mutableStateOf("") }
    
    LoyeaTheme {
        SettingsScreen(
            currentTheme = theme,
            onThemeChange = { theme = it },
            userName = userName,
            onUserNameSave = { userName = it },
            apiConfigList = listOf(ApiConfig(name = "Deepseek Pro", provider = "DeepSeek", modelName = "deepseek-v4-pro")),
            activeConfigId = "ds_pro",
            onApiConfigListSave = {},
            onActiveConfigSelect = {},
            appLanguage = appLanguage,
            onAppLanguageChange = { appLanguage = it },
            userBubbleColor = userBubbleColor,
            onUserBubbleColorChange = { userBubbleColor = it },
            mcpConfigs = emptyList(),
            mcpStates = emptyMap(),
            onMcpConfigsSave = {},
            getMcpToolsForServer = { emptyList() },
            isWatchConnected = false,
            onWatchConnectedChange = {},
            onWatchReconnect = {},
            isWatchMoving = false,
            onWatchMovingChange = {},
            useRealLocation = false,
            onUseRealLocationChange = {},
            mockLocation = "",
            onMockLocationSave = {},
            onHealthConnectClick = {},
            onBackClick = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpConfigLayout(
    mcpConfigs: List<McpServerConfig>,
    mcpStates: Map<String, McpServerStatus>,
    onMcpConfigsSave: (List<McpServerConfig>) -> Unit,
    getMcpToolsForServer: (String) -> List<McpTool>,
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"

    var showSheet by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<McpServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "MCP Cyber Plugins" else "MCP 赛博插件管理", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editingConfig = null
                        showSheet = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Server",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (mcpConfigs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isEn) "No MCP servers saved.\nClick '+' on top right to add." else "暂无 MCP 插件，\n请点击右上角 '+' 按钮添加。",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(mcpConfigs) { config ->
                        val status = mcpStates[config.id] ?: McpServerStatus.DISCONNECTED
                        McpServerCardItem(
                            config = config,
                            status = status,
                            tools = getMcpToolsForServer(config.id),
                            appLanguage = appLanguage,
                            onToggle = { isEnabled ->
                                val updated = mcpConfigs.map {
                                    if (it.id == config.id) it.copy(isEnabled = isEnabled) else it
                                }
                                onMcpConfigsSave(updated)
                            },
                            onEdit = {
                                editingConfig = config
                                showSheet = true
                            },
                            onDelete = {
                                val updated = mcpConfigs.filter { it.id != config.id }
                                onMcpConfigsSave(updated)
                                Toast.makeText(context, if (isEn) "Deleted" else "已删除服务器", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showSheet,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable { showSheet = false }
                )
            }

            AnimatedVisibility(
                visible = showSheet,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                AddOrEditMcpServerSheet(
                    editingConfig = editingConfig,
                    appLanguage = appLanguage,
                    onSave = { newOrUpdated ->
                        val updatedList = if (editingConfig == null) {
                            mcpConfigs + newOrUpdated
                        } else {
                            mcpConfigs.map { if (it.id == newOrUpdated.id) newOrUpdated else it }
                        }
                        onMcpConfigsSave(updatedList)
                        showSheet = false
                    },
                    onDismiss = { showSheet = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpServerCardItem(
    config: McpServerConfig,
    status: McpServerStatus,
    tools: List<McpTool>,
    appLanguage: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isEn = appLanguage == "en"
    var isExpanded by remember { mutableStateOf(false) }

    // Breathing effect for CONNECTING state
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )

    val statusColor = when (status) {
        McpServerStatus.CONNECTED -> Color(0xFF84A98C)  // Morandi Green
        McpServerStatus.CONNECTING -> Color(0xFFEADFD3) // Morandi Yellow / Amber
        McpServerStatus.DISCONNECTED -> Color(0xFF9E998F) // Morandi Gray
    }

    val statusText = when (status) {
        McpServerStatus.CONNECTED -> if (isEn) "Connected" else "已连接"
        McpServerStatus.CONNECTING -> if (isEn) "Connecting" else "连接中"
        McpServerStatus.DISCONNECTED -> if (isEn) "Disconnected" else "已断开"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (config.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp)
            )
            .background(MaterialTheme.colorScheme.surface)
            .clickable { isExpanded = !isExpanded }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status breathing light
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            statusColor.copy(
                                alpha = if (status == McpServerStatus.CONNECTING) breathingAlpha else 1f
                            )
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = config.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = config.sseUrl,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = config.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)) + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                // Tools Display
                if (status == McpServerStatus.CONNECTED) {
                    Text(
                        text = if (isEn) "Available Tools" else "可用工具列表",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    if (tools.isEmpty()) {
                        Text(
                            text = if (isEn) "No tools declared by server" else "服务未声明可用工具",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tools.forEach { tool ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = tool.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (!tool.description.isNullOrBlank()) {
                                            Text(
                                                text = tool.description,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (isEn) "Connect to see available tools" else "建立连接后即可查看可用工具列表",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (isEn) "Edit" else "编辑")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC97A7A)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (isEn) "Delete" else "删除", color = Color.White)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditMcpServerSheet(
    editingConfig: McpServerConfig?,
    appLanguage: String,
    onSave: (McpServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val isEn = appLanguage == "en"

    var nameInput by remember { mutableStateOf(editingConfig?.name ?: "") }
    var urlInput by remember { mutableStateOf(editingConfig?.sseUrl ?: "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(20.dp)
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (editingConfig == null) {
                    if (isEn) "Add MCP Server" else "添加 MCP 服务端"
                } else {
                    if (isEn) "Edit MCP Server" else "编辑 MCP 服务端"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        Column {
            Text(
                text = if (isEn) "SERVER ALIAS" else "服务端别名",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                placeholder = { Text("e.g. Local Workspace", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column {
            Text(
                text = if (isEn) "SSE CONNECTION URL" else "SSE 连接 URL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                singleLine = true,
                placeholder = { Text("e.g. http://10.0.2.2:3000/sse", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                if (urlInput.isNotBlank()) {
                    val finalName = if (nameInput.isBlank()) "MCP Server" else nameInput
                    val newConfig = McpServerConfig(
                        id = editingConfig?.id ?: System.currentTimeMillis().toString(),
                        name = finalName,
                        sseUrl = urlInput,
                        isEnabled = editingConfig?.isEnabled ?: true
                    )
                    onSave(newConfig)
                }
            },
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 10.dp)
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (isEn) "Save Server" else "保存服务端设置",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysicalSensorLayout(
    isWatchConnected: Boolean,
    onWatchConnectedChange: (Boolean) -> Unit,
    onWatchReconnect: () -> Unit,
    isWatchMoving: Boolean,
    onWatchMovingChange: (Boolean) -> Unit,
    useRealLocation: Boolean,
    onUseRealLocationChange: (Boolean) -> Unit,
    mockLocation: String,
    onMockLocationSave: (String) -> Unit,
    appLanguage: String,
    onHealthConnectClick: () -> Unit,
    healthPairingStatus: HealthPairingStatus?,
    onRefreshHealthPairing: () -> Unit,
    onBackClick: () -> Unit
) {
    val isEn = appLanguage == "en"

    // 进入面板即探测一次健康连接配对状态
    LaunchedEffect(Unit) {
        onRefreshHealthPairing()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Physical Perception" else "物理感知与外设集成", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Health Connect Integration
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "HEALTH DATA INTEGRATION" else "健康数据集成",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHealthConnectClick() }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = Color(0xFFC97A7A),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isEn) "Connect Health Hub" else "连接安卓“健康连接”",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (isEn) "Sync health data from other apps" else "同步来自其他健康应用的数据",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                        )
                    }
                }
                
                if (healthPairingStatus != null) {
                    PairingStatusCard(
                        status = healthPairingStatus,
                        isEn = isEn,
                        onRefresh = onRefreshHealthPairing,
                        onHealthConnectClick = onHealthConnectClick
                    )
                }

                Text(
                    text = if (isEn) "Tips: Ensure your health app has enabled 'Health Connect' write access." else "提示：请确保您的健康应用（如系统健康、运动应用等）已开启“健康连接”的写入权限与数据同步选项。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Watch sync & Bluetooth Integration (Claude Premium Aesthetics)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val btState by WatchBluetoothClient.connectionState.collectAsState()

                Text(
                    text = if (isEn) "SMARTWATCH BLUETOOTH SYNC" else "智能手表蓝牙同步",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                // Glassmorphism Card Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title and Switch row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Watch,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (isEn) "Enable Watch Sync" else "启用手表连接与同步",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (isEn) "Sync real heart rate and steps via classic Bluetooth" else "与真实 Loyea 手表同步健康数据",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                )
                            }
                        }
                        Switch(
                            checked = isWatchConnected,
                            onCheckedChange = onWatchConnectedChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    if (isWatchConnected) {
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        
                        // Connection Status Pill and Reconnect block
                        val (btStatusText, btStatusColor, btStatusBg) = when (btState) {
                            WatchBluetoothClient.ConnectionState.CONNECTED -> Triple(
                                if (isEn) "Connected" else "蓝牙已连接 🟢", 
                                Color(0xFF00FF66), 
                                Color(0xFF00FF66).copy(alpha = 0.08f)
                            )
                            WatchBluetoothClient.ConnectionState.CONNECTING -> Triple(
                                if (isEn) "Connecting..." else "正在连接 🔄", 
                                Color(0xFFFFD54F), 
                                Color(0xFFFFD54F).copy(alpha = 0.08f)
                            )
                            WatchBluetoothClient.ConnectionState.DISCONNECTED -> Triple(
                                if (isEn) "Disconnected" else "蓝牙未连接 ❌", 
                                Color(0xFFFF5252), 
                                Color(0xFFFF5252).copy(alpha = 0.08f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (isEn) "Bluetooth Link" else "外设链路状态",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                // Pill Badge
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100.dp))
                                        .background(btStatusBg)
                                        .border(1.dp, btStatusColor.copy(alpha = 0.25f), RoundedCornerShape(100.dp))
                                        .padding(horizontal = 10.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = btStatusText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = btStatusColor
                                    )
                                }
                            }

                            // Outlined reconnect button placed elegantly on the right
                            if (btState != WatchBluetoothClient.ConnectionState.CONNECTED) {
                                OutlinedButton(
                                    onClick = onWatchReconnect,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.03f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (isEn) "Reconnect" else "手动连接",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Guidance Info Box with clean background
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.02f))
                                .padding(12.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                                )
                                Text(
                                    text = if (isEn) "Note: Please ensure the watch app is open and bonded in your phone's system Bluetooth settings." 
                                           else "重要提示：请确保您的手表端已运行 Loyea Watch 且已在“系统蓝牙设置”中与该手机完成“配对”连接。若未连上，蓝牙模块将在后台自动尝试静默重连，您亦可点击手动连接强制唤醒。",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Mock Data configuration card (only shown when sync is enabled)
            if (isWatchConnected) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEn) "HARDWARE SIMULATION" else "模拟传感器调试 (无手表时使用)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isEn) "Simulate Moving State" else "模拟手表运动状态",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (isEn) "Heart rate will increase" else "心率会升高至 100-140 bpm 以进行逻辑测试",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                )
                            }
                            Switch(
                                checked = isWatchMoving,
                                onCheckedChange = onWatchMovingChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            // Location Settings
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "LOCATION SETTINGS" else "GPS 定位设置",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Use Real Location" else "获取真实物理定位",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Requires location permission" else "需要系统定位权限，否则回退到模拟位置",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                        Switch(
                            checked = useRealLocation,
                            onCheckedChange = onUseRealLocationChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    if (!useRealLocation) {
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isEn) "Mock Location" else "当前模拟位置 (经纬度)",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            InlineEditNameField(
                                initialName = mockLocation,
                                onSave = onMockLocationSave
                            )
                        }
                    }
                }
            }

            // Developer Tools
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "DEVELOPER TOOLS" else "开发者调试",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                val context = androidx.compose.ui.platform.LocalContext.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.loyea.worker.GreetingWorker>().build()
                                androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
                                android.widget.Toast.makeText(context, if (isEn) "Background task scheduled" else "后台问候任务已加入队列", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Test Background Greeting" else "测试后台主动问候 (WorkManager)",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Enqueues a one-time background greeting task" else "触发一次静默后台推送请求，完成后发送系统通知",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingStatusCard(
    status: HealthPairingStatus,
    isEn: Boolean,
    onRefresh: () -> Unit,
    onHealthConnectClick: () -> Unit
) {
    val sdkOk = status.hasSdkAvailable
    val ecoLabel = when (status.ecosystem) {
        HealthEcosystem.XIAOMI -> if (isEn) "Xiaomi" else "小米运动健康"
        HealthEcosystem.HUAWEI -> if (isEn) "Huawei" else "华为运动健康"
        HealthEcosystem.SAMSUNG -> if (isEn) "Samsung" else "三星健康"
        HealthEcosystem.OPPO -> "OPPO"
        HealthEcosystem.OTHER -> if (isEn) "Other" else "其他"
        HealthEcosystem.NONE -> if (isEn) "Not detected" else "未检测到"
    }
    val syncText = status.lastSyncTimeMillis?.let { ts ->
        val diff = System.currentTimeMillis() - ts
        when {
            diff < 60_000L -> if (isEn) "just now" else "刚刚"
            diff < 3_600_000L -> if (isEn) "${diff / 60_000L} min ago" else "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> if (isEn) "${diff / 3_600_000L} h ago" else "${diff / 3_600_000L} 小时前"
            else -> if (isEn) "${diff / 86_400_000L} d ago" else "${diff / 86_400_000L} 天前"
        }
    }

    val metricLabels = listOf(
        HealthMetric.HEART_RATE to (if (isEn) "Heart Rate" else "心率"),
        HealthMetric.STEPS to (if (isEn) "Steps" else "步数"),
        HealthMetric.SLEEP to (if (isEn) "Sleep" else "睡眠"),
        HealthMetric.BLOOD_PRESSURE to (if (isEn) "Blood Pressure" else "血压"),
        HealthMetric.RESTING_HEART_RATE to (if (isEn) "Resting HR" else "静息心率")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 头部：配对状态 + SDK 级别
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEn) "PAIRING STATUS" else "配对状态",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            status.apiLevelNote?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = if (sdkOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }

        // SDK 可用时展示数据来源 / 最近同步 / 各指标可用性
        if (sdkOk) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (isEn) "Data Source" else "数据来源",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Text(
                    text = ecoLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (status.dataOrigins.isNotEmpty()) {
                Text(
                    text = status.dataOrigins.joinToString(", "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (isEn) "Last Sync" else "最近同步",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Text(
                    text = syncText ?: (if (isEn) "Never" else "从未"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            metricLabels.forEach { (metric, label) ->
                val avail = status.metrics[metric]
                val (dotColor, availLabel) = when (avail) {
                    MetricAvailability.GRANTED_WITH_DATA -> Color(0xFF4CAF50) to (if (isEn) "Connected" else "已连接")
                    MetricAvailability.GRANTED_NO_DATA -> Color(0xFF9E9E9E) to (if (isEn) "No data" else "暂无数据")
                    MetricAvailability.NO_PERMISSION -> Color(0xFFFF9800) to (if (isEn) "Not granted" else "未授权")
                    else -> Color(0xFFBDBDBD) to (if (isEn) "Unavailable" else "不可用")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Text(text = availLabel, fontSize = 13.sp, color = dotColor)
                }
            }
        }

        // 引导文案（SDK 不可用时为安装/更新指引）
        if (status.guidanceText.isNotBlank()) {
            Text(
                text = status.guidanceText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }

        // 操作：连接/重新授权 + 刷新
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onHealthConnectClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isEn) "Connect / Re-grant" else "连接 / 重新授权", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isEn) "Refresh" else "刷新", fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySettingsLayout(
    apiConfigList: List<ApiConfig>,
    activeConfigId: String,
    appLanguage: String,
    viewModel: com.loyea.ui.chat.ChatViewModel?,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("loyea_prefs", android.content.Context.MODE_PRIVATE) }

    var enableMemory by remember { mutableStateOf(prefs.getBoolean("enable_memory_consolidation", true)) }
    var triggerCount by remember { mutableStateOf(prefs.getInt("memory_consolidation_trigger_count", 10)) }
    var memoryApiConfigId by remember { mutableStateOf(prefs.getString("memory_api_config_id", "") ?: "") }

    val isEn = appLanguage == "en"
    var expandedDropdown by remember { mutableStateOf(false) }

    // 控制关系图谱的查看与管理弹窗
    var showGraphDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Memory Settings" else "记忆机制设置", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Memory Consolidation Switch
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "AUTOMATIC CONSOLIDATION" else "自动记忆整理",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Enable Auto Memory" else "启用自动提取整理",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "LLM will automatically summarize key facts in background" else "大模型将在后台定期自动提炼并去重保存对话核心事实",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                        Switch(
                            checked = enableMemory,
                            onCheckedChange = {
                                enableMemory = it
                                prefs.edit().putBoolean("enable_memory_consolidation", it).apply()
                            }
                        )
                    }
                }
            }

            if (enableMemory) {
                // Trigger Message Threshold
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEn) "TRIGGER THRESHOLD" else "触发整理周期",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isEn) "Trigger count (messages)" else "触发阈值 (条消息)",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (isEn) "Trigger memory consolidation every $triggerCount messages" else "每隔 $triggerCount 条对话消息自动触发一次记忆整理",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                )
                            }
                            Text(
                                text = triggerCount.toString(),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = triggerCount.toFloat(),
                            onValueChange = {
                                triggerCount = (it + 0.5f).toInt()
                            },
                            onValueChangeFinished = {
                                prefs.edit().putInt("memory_consolidation_trigger_count", triggerCount).apply()
                            },
                            valueRange = 5f..30f,
                            steps = 4 // 5, 10, 15, 20, 25, 30
                        )
                    }
                }

                // Dedicated Model/API Configuration
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEn) "SUMMARY MODEL CONFIGURATION" else "总结记忆使用模型/配置",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = if (isEn) "API Configuration" else "API 配置选择",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (isEn) "Choose API client config used to synthesize memories" else "专门为记忆合并总结指定的 API 配置与大模型客户端",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val activeConfig = apiConfigList.find { it.id == memoryApiConfigId }
                        val displayValue = if (memoryApiConfigId.isEmpty()) {
                            if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置"
                        } else {
                            activeConfig?.let { "${it.name} (${it.modelName})" } ?: (if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置")
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable { expandedDropdown = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = displayValue, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = expandedDropdown,
                                onDismissRequest = { expandedDropdown = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置", fontSize = 13.sp) },
                                    onClick = {
                                        memoryApiConfigId = ""
                                        prefs.edit().putString("memory_api_config_id", "").apply()
                                        expandedDropdown = false
                                    }
                                )
                                apiConfigList.forEach { config ->
                                    DropdownMenuItem(
                                        text = { Text("${config.name} (${config.modelName})", fontSize = 13.sp) },
                                        onClick = {
                                            memoryApiConfigId = config.id
                                            prefs.edit().putString("memory_api_config_id", config.id).apply()
                                            expandedDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 脑内心智与共情系统 (Cyber Mind & Empathy System)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isEn) "CYBER MIND & EMPATHY SYSTEM" else "脑内心智与共情系统",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Graph Memory (Graph RAG) Switch
                    var enableGraphMemory by remember { mutableStateOf(prefs.getBoolean("enable_graph_memory", true)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Long-term Relation Graph (Graph RAG)" else "长程关系图谱 (Graph RAG)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Enable local triple relationship extraction and association recall" else "开启本地三元组关系网络提取与关联式记忆召回",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                        Switch(
                            checked = enableGraphMemory,
                            onCheckedChange = {
                                enableGraphMemory = it
                                prefs.edit().putBoolean("enable_graph_memory", it).apply()
                                viewModel?.updateGraphMemorySetting(it)
                            }
                        )
                    }

                    // 如果启用了关系图谱，展示管理记忆网络入口按钮
                    if (enableGraphMemory) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel?.loadGraphMemoriesForCurrentSession()
                                    showGraphDialog = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isEn) "Manage Relation Graph" else "管理记忆网络",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    // 2. Acoustic Emotion Perception Switch
                    var enableVoiceEmotion by remember { mutableStateOf(prefs.getBoolean("enable_voice_emotion_perception", true)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Acoustic Emotion Perception" else "声学情绪感知系统",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (isEn) "Allows AI to sense and align with your voice emotion during chats" else "允许 AI 在语音交互中敏锐感知并对齐主人的语气情绪",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                        Switch(
                            checked = enableVoiceEmotion,
                            onCheckedChange = {
                                enableVoiceEmotion = it
                                prefs.edit().putBoolean("enable_voice_emotion_perception", it).apply()
                                viewModel?.updateVoiceEmotionPerceptionSetting(it)
                            }
                        )
                    }
                }
            }
        }
    }

    // 关系图谱展现与删除管理弹窗
    if (showGraphDialog) {
        val graphMemories by remember { viewModel?.graphMemories ?: mutableStateOf(emptyList()) }

        AlertDialog(
            onDismissRequest = { showGraphDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(16.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEn) "Memory Relation Graph" else "会话关系图谱网络",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showGraphDialog = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (isEn)
                            "This list displays the relational triple network extracted from the current session. These facts are isolated from other sessions."
                            else "本列表展示从当前会话提取出的三元组记忆网络。不同会话及不同角色的记忆已做物理沙盒隔离，互不穿透。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )

                    if (graphMemories.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isEn) "No relational graph memories extracted yet." else "当前会话暂未提取出任何关系图谱记忆。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(graphMemories) { triple ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 杂志级排版展示三元组
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            // Subject
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = triple.subject,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Text(
                                                text = "── ${triple.predicate} ──>",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )

                                            // Object
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = triple.`object`,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }

                                        // 提及频次与遗忘权重
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(
                                                text = if (isEn) "Mentions: ${triple.mentionCount}" else "提及次数: ${triple.mentionCount}",
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                            val formattedWeight = String.format("%.2f", triple.getCalculatedWeight(System.currentTimeMillis()))
                                            Text(
                                                text = if (isEn) "Memory Weight: $formattedWeight" else "艾宾浩斯记忆权重: $formattedWeight",
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { viewModel?.deleteGraphMemoryTriple(triple.id) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (graphMemories.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel?.clearAllGraphMemoriesForCurrentSession() }
                        ) {
                            Text(
                                text = if (isEn) "Clear All" else "一键清空",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Button(
                        onClick = { showGraphDialog = false },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(text = if (isEn) "Close" else "关闭", fontSize = 14.sp)
                    }
                }
            }
        )
    }
}

// =================== 外部工具授权二级页面布局 ===================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolAuthorizationLayout(
    viewModel: com.loyea.ui.chat.ChatViewModel?,
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val isEn = appLanguage == "en"

    var authLocation by remember { mutableStateOf(viewModel?.toolAuthLocation?.value ?: true) }
    var authWeather by remember { mutableStateOf(viewModel?.toolAuthWeather?.value ?: true) }
    var authEnvironment by remember { mutableStateOf(viewModel?.toolAuthEnvironment?.value ?: true) }
    var authDevice by remember { mutableStateOf(viewModel?.toolAuthDevice?.value ?: true) }
    var authBluetoothActivity by remember { mutableStateOf(viewModel?.toolAuthBluetoothActivity?.value ?: true) }
    var authHealth by remember { mutableStateOf(viewModel?.toolAuthHealth?.value ?: true) }
    var authHaptic by remember { mutableStateOf(viewModel?.toolAuthHaptic?.value ?: true) }
    var enableBgGreeting by remember { mutableStateOf(viewModel?.enableBackgroundGreeting?.value ?: true) }

    // 外部 MCP 工具白名单状态（null = 从未管理过 → 全放行）
    var mcpTools by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var mcpWhitelist by remember { mutableStateOf(viewModel?.mcpToolWhitelist?.value) }
    LaunchedEffect(Unit) {
        mcpTools = viewModel?.getAllMcpToolsForAuth() ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Tool Authorization" else "外部工具授权", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = if (isEn) {
                        "Loyea integrates various physical perception modules. Below you can authorize or restrict AI access to specific sensors or physical effects for your privacy and preference."
                    } else {
                        "Loyea 深度整合了多项物理感知模块。您可以在下方自主授权或限制 AI 伴侣对特定传感器及物理马达的使用，以保护个人隐私并实现个性化的交互体验。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    lineHeight = 18.sp
                )
            }

            // AI 后台主动问候专属卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubble,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Proactive BG Greeting" else "允许 AI 后台主动联系我",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isEn) {
                                "AI will randomly contact you with custom messages (every 2-8 hrs) based on your live physical context."
                            } else {
                                "开启后，AI 伴侣会根据您的实时物理环境在后台不定时（2~8小时）主动联系您并推送问候语。"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            lineHeight = 16.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = enableBgGreeting,
                    onCheckedChange = {
                        enableBgGreeting = it
                        viewModel?.updateBackgroundGreeting(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            val items = listOf(
                ToolAuthItemData(
                    key = "tool_auth_location",
                    title = if (isEn) "GPS Location" else "物理定位服务",
                    desc = if (isEn) "Allows AI to query your current coordinates (latitude/longitude)" else "允许 AI 伴侣调取您当前的经纬度位置信息",
                    icon = Icons.Default.LocationOn,
                    isChecked = authLocation,
                    onCheckedChange = {
                        authLocation = it
                        viewModel?.updateToolAuth("tool_auth_location", it)
                    }
                ),
                ToolAuthItemData(
                    key = "tool_auth_weather",
                    title = if (isEn) "Weather & Forecast" else "实时气象与预报",
                    desc = if (isEn) "Allows AI to query current weather and 3-day forecast" else "允许 AI 伴侣调取当前天气状况与未来 3 天气温预报",
                    icon = Icons.Default.Cloud,
                    isChecked = authWeather,
                    onCheckedChange = {
                        authWeather = it
                        viewModel?.updateToolAuth("tool_auth_weather", it)
                    }
                ),
                ToolAuthItemData(
                    key = "tool_auth_environment",
                    title = if (isEn) "Ambient Light & Noise" else "环境照度与噪音",
                    desc = if (isEn) "Allows AI to measure ambient lux (light) and microphone decibel (dB) levels" else "允许 AI 伴侣读取环境亮度 (Lux) 与麦克风分贝噪音等级 (dB)",
                    icon = Icons.Default.Hearing,
                    isChecked = authEnvironment,
                    onCheckedChange = {
                        authEnvironment = it
                        viewModel?.updateToolAuth("tool_auth_environment", it)
                    }
                ),
                ToolAuthItemData(
                    key = "tool_auth_device",
                    title = if (isEn) "Device Power & Network" else "设备电量与网络",
                    desc = if (isEn) "Allows AI to read battery level, charging status, and Wi-Fi SSID" else "允许 AI 伴侣读取电池电量、充电状态与连接的 Wi-Fi 名称",
                    icon = Icons.Default.SettingsCell,
                    isChecked = authDevice,
                    onCheckedChange = {
                        authDevice = it
                        viewModel?.updateToolAuth("tool_auth_device", it)
                    }
                ),
                ToolAuthItemData(
                    key = "tool_auth_bluetooth_activity",
                    title = if (isEn) "Bluetooth & Movement State" else "外设蓝牙与运动状态",
                    desc = if (isEn) "Allows AI to scan nearby wearable battery levels and detect motion (walking/still)" else "允许 AI 伴侣扫描附近蓝牙耳机电量及检测运动状态 (如步行/静止)",
                    icon = Icons.Default.DirectionsRun,
                    isChecked = authBluetoothActivity,
                    onCheckedChange = {
                        authBluetoothActivity = it
                        viewModel?.updateToolAuth("tool_auth_bluetooth_activity", it)
                    }
                ),
                ToolAuthItemData(
                    key = "tool_auth_health",
                    title = if (isEn) "Health Connect Data" else "身体健康中心数据",
                    desc = if (isEn) "Allows AI to read smartwatch steps, real-time heart rate, sleep and BP" else "允许 AI 伴侣读取手环/手表上的步数、实时心率、血压与睡眠监测",
                    icon = Icons.Default.Favorite,
                    isChecked = authHealth,
                    onCheckedChange = {
                        authHealth = it
                        viewModel?.updateToolAuth("tool_auth_health", it)
                    }
                ),
                ToolAuthItemData(
                    key = "tool_auth_haptic",
                    title = if (isEn) "Physical Haptic Sync" else "物理震动反馈机制",
                    desc = if (isEn) "Allows AI to trigger phone vibrations synchronously during emotional action words (heartbeat, poke, whisper)" else "允许 AI 伴侣在表达情感动作（如心跳、轻戳、低语）时同步触发手机物理震动",
                    icon = Icons.Default.Vibration,
                    isChecked = authHaptic,
                    onCheckedChange = {
                        authHaptic = it
                        viewModel?.updateToolAuth("tool_auth_haptic", it)
                    }
                )
            )

            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = if (item.isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.desc,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = item.isChecked,
                        onCheckedChange = item.onCheckedChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }

            // ===== 外部 MCP 工具白名单（未管理时全放行兼容旧行为；管理后严格白名单） =====
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEn) "External MCP Tool Whitelist" else "外部 MCP 工具白名单",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                TextButton(onClick = { mcpTools = viewModel?.getAllMcpToolsForAuth() ?: emptyList() }) {
                    Text(text = if (isEn) "Refresh" else "刷新", fontSize = 12.sp)
                }
            }
            Text(
                text = if (isEn) {
                    "Third-party MCP server tools are hidden from the AI by default and only become callable after you authorize them individually."
                } else {
                    "第三方 MCP 服务器的工具默认对 AI 隐藏，仅在你逐个授权后才会开放给 AI 调用。"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (mcpTools.isEmpty()) {
                Text(
                    text = if (isEn) "No connected external MCP servers with discovered tools." else "暂无已连接并发现工具的外部 MCP 服务器。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                mcpTools.forEach { (serverName, fullName) ->
                    val whitelistLocal = mcpWhitelist
                    val isAuthorized = whitelistLocal == null || whitelistLocal.any { it.equals(fullName, ignoreCase = true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = if (isAuthorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = fullName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isEn) "Server: $serverName" else "服务器：$serverName",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isAuthorized,
                            onCheckedChange = { checked ->
                                val base = mcpWhitelist ?: mcpTools.map { it.second }.toSet()
                                mcpWhitelist = if (checked) base + fullName else base - fullName
                                viewModel?.updateMcpToolAuthorization(fullName, checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}

data class ToolAuthItemData(
    val key: String,
    val title: String,
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val isChecked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

// =================== 多模态与媒体扩展设置二级页面 ===================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultimodalSettingsLayout(
    viewModel: com.loyea.ui.chat.ChatViewModel?,
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val isEn = appLanguage == "en"

    // 读取 ViewModel 中响应式的 State
    val multimodalEnabled = viewModel?.enableMultimodal?.value ?: true
    val sttEnabled = viewModel?.enableStt?.value ?: true
    val audioUnderstandingEnabled = viewModel?.enableAudioUnderstanding?.value ?: false
    val ttsEnabled = viewModel?.enableTts?.value ?: true
    val selectedVoice = viewModel?.ttsVoice?.value ?: "茉莉"
    val autoTtsEnabled = viewModel?.enableAutoTts?.value ?: false
    val imageGenEnabled = viewModel?.enableImageGen?.value ?: true
    val imageModelName = viewModel?.imageGenModel?.value ?: "dall-e-3"

    val visionConfigId = viewModel?.visionConfigId?.value ?: ""
    val visionModelName = viewModel?.visionModelName?.value ?: "gpt-4o-mini"
    val sttConfigId = viewModel?.sttConfigId?.value ?: ""
    val sttModelName = viewModel?.sttModelName?.value ?: "whisper-1"
    val ttsConfigId = viewModel?.ttsConfigId?.value ?: ""
    val ttsModelName = viewModel?.ttsModelName?.value ?: "tts-1"
    val imageGenConfigId = viewModel?.imageGenConfigId?.value ?: ""

    // 模板及同步状态
    val isUpdatingTemplates = viewModel?.isUpdatingTemplates?.value ?: false
    val updateTemplatesStatus = viewModel?.updateTemplatesStatus?.value ?: ""
    val ttsProviderTemplate = viewModel?.ttsProviderTemplate?.value ?: "Auto"
    val sttProviderTemplate = viewModel?.sttProviderTemplate?.value ?: "Auto"
    val ttsTemplates = viewModel?.ttsTemplates?.value ?: emptyList()

    val apiConfigList = viewModel?.apiConfigList?.value ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Multimodal & Media" else "多模态与媒体设置", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 提示横幅
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isEn) {
                            "Configure API providers for speech synthesis (TTS), recording inputs (STT), visual understanding, and image generation."
                        } else {
                            "在此统一配置各个多模态模块（语音合成 TTS、语音录音输入 STT、视觉图片理解、AI 图画生成）的底层 API 客户端及模型名称。"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        lineHeight = 18.sp
                    )
                }
            }

            // --- 顶部的云端更新模块配置 ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
                    .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Sync Candidate Templates" else "云端接口配置模板",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (updateTemplatesStatus.isBlank()) {
                                if (isEn) "Dynamically load model/voice candidates from cloud" else "支持从云端拉取主流厂商最新的候选模型和音色列表"
                            } else {
                                updateTemplatesStatus
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            lineHeight = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { viewModel?.fetchTemplatesFromNetwork() },
                        enabled = !isUpdatingTemplates,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isUpdatingTemplates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = if (isEn) "Sync" else "同步", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 全局开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isEn) "Enable Multimodal Perception" else "开启多模态与感知能力",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isEn) "Allows AI to send/receive audio and image media." else "关闭后，AI 将无法接收图片，且无法启用语音录音或播报功能。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        lineHeight = 16.sp
                    )
                }
                Switch(
                    checked = multimodalEnabled,
                    onCheckedChange = { viewModel?.updateMultimodalSetting("enable_multimodal", it) }
                )
            }

            if (multimodalEnabled) {
                // ==================== 1. 语音合成卡片 (TTS) ====================
                MultimodalModuleCard(
                    icon = Icons.Default.VolumeUp,
                    title = if (isEn) "Read Aloud (TTS)" else "文本语音朗读 (TTS)",
                    isEn = isEn,
                    enabled = ttsEnabled,
                    onToggle = { viewModel?.updateMultimodalSetting("enable_tts", it) }
                ) {
                    // 自动朗读 AI 回复
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Auto Play Reply" else "自动朗读 AI 回复",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isEn) "Automatically read aloud new AI messages when generated." else "当 AI 消息生成完毕后，自动开始播报语音。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                        Switch(
                            checked = autoTtsEnabled,
                            onCheckedChange = { viewModel?.updateMultimodalSetting("enable_auto_tts", it) }
                        )
                    }

                    // API 客户端服务商（空 = 跟随当前会话配置）
                    ApiConfigDropdown(
                        configId = ttsConfigId,
                        apiConfigList = apiConfigList,
                        isEn = isEn,
                        onConfigIdChange = { viewModel?.updateMultimodalSetting("tts_config_id", it) }
                    )

                    // 智能匹配当前服务商的预置模板
                    val activeTtsConfig = apiConfigList.find { it.id == ttsConfigId }
                    val effectiveProvider = if (ttsProviderTemplate == "Auto") {
                        activeTtsConfig?.provider ?: "OpenAI"
                    } else {
                        ttsProviderTemplate
                    }
                    val standardProvider = when {
                        effectiveProvider.contains("mimo", ignoreCase = true) -> "MiMo"
                        effectiveProvider.contains("ali", ignoreCase = true) || effectiveProvider.contains("dashscope", ignoreCase = true) -> "Alibaba"
                        effectiveProvider.contains("volc", ignoreCase = true) || effectiveProvider.contains("doubao", ignoreCase = true) -> "Volcengine"
                        effectiveProvider.contains("custom", ignoreCase = true) -> "Custom"
                        else -> "OpenAI"
                    }
                    val currentTtsTemplate = ttsTemplates.find { it.provider.equals(standardProvider, ignoreCase = true) }
                    val voicePresets = currentTtsTemplate?.voices?.map { PresetOption(it.id, it.name) } ?: emptyList()
                    val modelPresets = currentTtsTemplate?.models?.map { PresetOption(it.id, it.name) } ?: emptyList()

                    // 合成音色：预置下拉 + 自定义输入弹窗（无需手写提示词，音色名即选择项）
                    PresetSelector(
                        label = if (isEn) "TTS Voice (${currentTtsTemplate?.displayName ?: standardProvider})" else "合成音色（${currentTtsTemplate?.displayName ?: standardProvider}）",
                        value = selectedVoice,
                        presets = voicePresets,
                        customDialogTitle = if (isEn) "Custom Voice" else "自定义音色",
                        customPlaceholder = "e.g. alloy, mimo_default, longanyang",
                        isEn = isEn,
                        onValueChange = { viewModel?.updateMultimodalSetting("tts_voice", it) }
                    )
                    if (currentTtsTemplate != null && voicePresets.none { it.value == selectedVoice }) {
                        Text(
                            text = if (isEn)
                                "Not in ${standardProvider} presets - will fallback to the provider default at runtime."
                            else
                                "该音色不在 ${standardProvider} 预置列表中，运行时将自动回退到该服务商的默认音色。",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                    }

                    // 合成模型：预置下拉 + 自定义输入弹窗
                    PresetSelector(
                        label = if (isEn) "TTS Model" else "合成模型",
                        value = ttsModelName,
                        presets = modelPresets,
                        customDialogTitle = if (isEn) "Custom Model" else "自定义模型",
                        customPlaceholder = "e.g. tts-1, cosyvoice-v3-flash, mimo-v2.5-tts",
                        isEn = isEn,
                        onValueChange = { viewModel?.updateMultimodalSetting("tts_model_name", it) }
                    )

                    // 高级：协议模板（默认自动判定，通常无需修改）
                    AdvancedSection(title = if (isEn) "Advanced: API Protocol Template" else "高级：API 对接协议模板", isEn = isEn) {
                        ProtocolTemplateDropdown(
                            current = ttsProviderTemplate,
                            options = listOf(
                                Pair("Auto", if (isEn) "Auto Detect" else "自动判定服务商协议"),
                                Pair("OpenAI", "OpenAI 官方规范"),
                                Pair("MiMo", "小米 MiMo 规范"),
                                Pair("Alibaba", "阿里百炼 (DashScope)"),
                                Pair("Volcengine", "火山引擎 (豆包)"),
                                Pair("Custom", if (isEn) "Custom Third-Party" else "完全自定义 / 其他中转")
                            ),
                            isEn = isEn,
                            onValueChange = { viewModel?.updateMultimodalSetting("tts_provider_template", it) }
                        )
                        if (ttsProviderTemplate == "Auto") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isEn) "Auto detected: $effectiveProvider -> $standardProvider" else "已自动匹配: $effectiveProvider → $standardProvider",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ==================== 2. 语音输入卡片 (STT) ====================
                MultimodalModuleCard(
                    icon = Icons.Default.Mic,
                    title = if (isEn) "Voice Input (STT)" else "语音录音输入 (STT)",
                    isEn = isEn,
                    enabled = sttEnabled,
                    onToggle = { viewModel?.updateMultimodalSetting("enable_stt", it) }
                ) {
                    // 输入模式单选：语音转文字 / 大模型直接音频理解（二者互斥）
                    Text(
                        text = if (isEn) "Input Mode" else "语音输入模式",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel?.updateMultimodalSetting("enable_audio_understanding", false) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !audioUnderstandingEnabled,
                            onClick = { viewModel?.updateMultimodalSetting("enable_audio_understanding", false) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Transcribe to Text (STT)" else "语音转文字 (STT)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isEn) "Recognize speech into text, compatible with all providers." else "识别为文字后发送，兼容所有服务商，小米 MiMo 转写效果最佳。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel?.updateMultimodalSetting("enable_audio_understanding", true) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = audioUnderstandingEnabled,
                            onClick = { viewModel?.updateMultimodalSetting("enable_audio_understanding", true) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Direct Audio Understanding" else "大模型直接音频理解",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isEn) "Send voice directly to the LLM to perceive tone and background sound (needs an audio multimodal model)." else "语音直接发给大模型，感知语气与背景声（需底层模型支持多模态音频）。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                        }
                    }

                    if (!audioUnderstandingEnabled) {
                        // API 客户端服务商（空 = 自动优先使用小米 MiMo 转写）
                        ApiConfigDropdown(
                            configId = sttConfigId,
                            apiConfigList = apiConfigList,
                            isEn = isEn,
                            onConfigIdChange = { viewModel?.updateMultimodalSetting("stt_config_id", it) }
                        )
                        val hasMimoConfig = apiConfigList.any { it.provider.equals("MiMo", ignoreCase = true) }
                        if (sttConfigId.isBlank() && hasMimoConfig) {
                            Text(
                                text = if (isEn) "Unspecified: will auto-prefer the MiMo config for transcription." else "未指定服务商时，将自动优先使用小米 MiMo 转写（转写效果最佳）。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // 转写模型：预置下拉 + 自定义输入弹窗
                        PresetSelector(
                            label = if (isEn) "Transcription Model" else "转写模型",
                            value = sttModelName,
                            presets = sttModelPresets,
                            customDialogTitle = if (isEn) "Custom Transcription Model" else "自定义转写模型",
                            customPlaceholder = "e.g. whisper-1, mimo-v2.5-asr",
                            isEn = isEn,
                            onValueChange = { viewModel?.updateMultimodalSetting("stt_model_name", it) }
                        )

                        // 高级：协议模板（默认自动判定）
                        AdvancedSection(title = if (isEn) "Advanced: STT Protocol Template" else "高级：语音输入协议模板", isEn = isEn) {
                            ProtocolTemplateDropdown(
                                current = sttProviderTemplate,
                                options = listOf(
                                    Pair("Auto", if (isEn) "Auto Detect" else "自动判定服务商协议"),
                                    Pair("OpenAI", "OpenAI / Whisper 标准 (Multipart)"),
                                    Pair("MiMo", "小米 MiMo / 多模态 ASR (ChatCompletions)"),
                                    Pair("Custom", if (isEn) "Custom / Others" else "完全自定义 / 其他中转")
                                ),
                                isEn = isEn,
                                onValueChange = { viewModel?.updateMultimodalSetting("stt_provider_template", it) }
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = if (isEn)
                                    "Audio understanding mode: voice is sent directly to the current conversation model. Make sure it supports audio input."
                                else
                                    "音频理解模式：语音将直接发送给当前会话的大模型，请确保该模型支持多模态音频输入（如 MiMo 多模态模型）。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                // ==================== 3. 视觉与图片理解卡片 (Vision) ====================
                MultimodalModuleCard(
                    icon = Icons.Default.Visibility,
                    title = if (isEn) "Visual Understanding" else "视觉图片理解 (Vision)",
                    isEn = isEn,
                    enabled = true,
                    onToggle = null
                ) {
                    ApiConfigDropdown(
                        configId = visionConfigId,
                        apiConfigList = apiConfigList,
                        isEn = isEn,
                        onConfigIdChange = { viewModel?.updateMultimodalSetting("vision_config_id", it) }
                    )
                    PresetSelector(
                        label = if (isEn) "Vision Model" else "视觉模型",
                        value = visionModelName,
                        presets = visionModelPresets,
                        customDialogTitle = if (isEn) "Custom Vision Model" else "自定义视觉模型",
                        customPlaceholder = "e.g. gpt-4o-mini, claude-3-5-sonnet, qwen-vl-max",
                        isEn = isEn,
                        onValueChange = { viewModel?.updateMultimodalSetting("vision_model_name", it) }
                    )
                }

                // ==================== 4. AI 图像生成卡片 (ImageGen) ====================
                MultimodalModuleCard(
                    icon = Icons.Default.Palette,
                    title = if (isEn) "Image Generation" else "AI 图像生成 (生图)",
                    isEn = isEn,
                    enabled = imageGenEnabled,
                    onToggle = { viewModel?.updateMultimodalSetting("enable_image_gen", it) }
                ) {
                    ApiConfigDropdown(
                        configId = imageGenConfigId,
                        apiConfigList = apiConfigList,
                        isEn = isEn,
                        onConfigIdChange = { viewModel?.updateMultimodalSetting("image_gen_config_id", it) }
                    )
                    PresetSelector(
                        label = if (isEn) "Image Model" else "生图模型",
                        value = imageModelName,
                        presets = imageGenModelPresets,
                        customDialogTitle = if (isEn) "Custom Image Model" else "自定义生图模型",
                        customPlaceholder = "e.g. dall-e-3, mimo-v2.5-images",
                        isEn = isEn,
                        onValueChange = { viewModel?.updateMultimodalSetting("image_gen_model", it) }
                    )
                }
            }
        }
    }
}

// ---------- 多模态通用组件 ----------

/** 预置选项：value 为实际值，name 为显示名 */
private data class PresetOption(val value: String, val name: String)

private val sttModelPresets = listOf(
    PresetOption("mimo-v2.5-asr", "MiMo 语音转写 (v2.5)"),
    PresetOption("whisper-1", "OpenAI Whisper-1"),
    PresetOption("gpt-4o-transcribe", "OpenAI GPT-4o Transcribe"),
    PresetOption("paraformer-realtime-v2", "阿里 Paraformer (实时)")
)

private val visionModelPresets = listOf(
    PresetOption("gpt-4o-mini", "GPT-4o mini"),
    PresetOption("gpt-4o", "GPT-4o"),
    PresetOption("qwen-vl-max", "通义千问 qwen-vl-max"),
    PresetOption("glm-4v-plus", "智谱 GLM-4V-Plus"),
    PresetOption("claude-3-5-sonnet", "Claude 3.5 Sonnet")
)

private val imageGenModelPresets = listOf(
    PresetOption("mimo-v2.5-images", "MiMo 图像生成 (v2.5)"),
    PresetOption("dall-e-3", "OpenAI DALL-E 3"),
    PresetOption("dall-e-2", "OpenAI DALL-E 2"),
    PresetOption("stable-diffusion-xl", "Stable Diffusion XL")
)

/**
 * 多模态模块卡片容器：图标 + 标题 + 可选开关
 */
@Composable
private fun MultimodalModuleCard(
    icon: ImageVector,
    title: String,
    isEn: Boolean,
    enabled: Boolean,
    onToggle: ((Boolean) -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                if (onToggle != null) {
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }
            if (onToggle == null || enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                content()
            }
        }
    }
}

/**
 * 两级选择器：预置下拉框 + 自定义输入弹窗
 * 点击后展开下拉菜单展示预置候选，选中"自定义输入…"则弹出输入框。
 */
@Composable
private fun PresetSelector(
    label: String,
    value: String,
    presets: List<PresetOption>,
    customDialogTitle: String,
    customPlaceholder: String,
    isEn: Boolean,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customInput by remember(value) { mutableStateOf(value) }

    val matched = presets.firstOrNull { it.value == value }
    val isCustom = matched == null

    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = matched?.name ?: value,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCustom) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEn) "Custom" else "自定义",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name, fontSize = 13.sp) },
                        onClick = {
                            onValueChange(preset.value)
                            expanded = false
                        }
                    )
                }
                if (presets.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isEn) "Custom input…" else "自定义输入…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = {
                        expanded = false
                        customInput = value
                        showCustomDialog = true
                    }
                )
            }
        }
        if (isCustom && value.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isEn) "Custom value (not in preset list)" else "当前为自定义值，不在预置列表中",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text(customDialogTitle, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp),
                    placeholder = {
                        Text(customPlaceholder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f))
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(customInput.trim())
                    showCustomDialog = false
                }) { Text(if (isEn) "OK" else "确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text(if (isEn) "Cancel" else "取消") }
            }
        )
    }
}

/**
 * API 客户端下拉选择（空值 = 跟随当前会话配置）
 */
@Composable
private fun ApiConfigDropdown(
    configId: String,
    apiConfigList: List<ApiConfig>,
    isEn: Boolean,
    onConfigIdChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentConfig = apiConfigList.find { it.id == configId }
    val configText = currentConfig?.let { "${it.name} (${it.provider})" }
        ?: (if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置")

    Column {
        Text(
            text = if (isEn) "API Client Provider" else "API 客户端服务商",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = configText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                DropdownMenuItem(
                    text = { Text(if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置", fontSize = 13.sp) },
                    onClick = {
                        onConfigIdChange("")
                        expanded = false
                    }
                )
                apiConfigList.forEach { config ->
                    DropdownMenuItem(
                        text = { Text("${config.name} (${config.provider})", fontSize = 13.sp) },
                        onClick = {
                            onConfigIdChange(config.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * 折叠式高级设置区域
 */
@Composable
private fun AdvancedSection(
    title: String,
    isEn: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            content()
        }
    }
}

/**
 * 协议模板下拉选择
 */
@Composable
private fun ProtocolTemplateDropdown(
    current: String,
    options: List<Pair<String, String>>,
    isEn: Boolean,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = options.firstOrNull { it.first == current }?.second
        ?: (if (isEn) "Custom / Others" else "完全自定义 / 其他中转")

    Column {
        Text(
            text = if (isEn) "Protocol Template" else "对接协议模板",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                options.forEach { (key, name) ->
                    DropdownMenuItem(
                        text = { Text(name, fontSize = 13.sp) },
                        onClick = {
                            onValueChange(key)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
