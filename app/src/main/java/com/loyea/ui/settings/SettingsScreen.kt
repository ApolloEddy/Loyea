package com.loyea.ui.settings

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.theme.LoyeaTheme
import com.loyea.bluetooth.WatchBluetoothClient

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

// 二级页面枚举
enum class SettingsSubPage {
    MAIN, API_CONFIG, THEME_SETTINGS, MCP_CONFIG, PHYSICAL_SENSOR, MEMORY_SETTINGS, TOOL_AUTHORIZATION, MULTIMODAL_SETTINGS
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

    // 使用 AnimatedContent 实现极具滑移动画质感的左右推拉过场
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
        modifier = modifier.fillMaxSize()
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
                    onBackClick = onBackClick
                )
            }
            SettingsSubPage.MEMORY_SETTINGS -> {
                MemorySettingsLayout(
                    apiConfigList = apiConfigList,
                    activeConfigId = activeConfigId,
                    appLanguage = appLanguage,
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

    var useIndependentSearch by remember { mutableStateOf(editingConfig?.useIndependentSearch ?: false) }
    var searchProvider by remember { mutableStateOf(editingConfig?.searchProvider ?: "Tavily") }
    var searchApiUrlInput by remember { mutableStateOf(editingConfig?.searchApiUrl ?: "https://api.tavily.com") }
    var searchApiKeyInput by remember { mutableStateOf(editingConfig?.searchApiKey ?: "") }
    var searchProviderDropdownExpanded by remember { mutableStateOf(false) }
    var showSearchApiKey by remember { mutableStateOf(false) }

    var showApiKey by remember { mutableStateOf(false) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

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
                placeholder = { Text("e.g. deepseek-v4-pro", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
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
        }

        Spacer(modifier = Modifier.height(4.dp))

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

        // 独立联网搜索配置模块
        androidx.compose.animation.AnimatedVisibility(
            visible = enableSearch,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 开关：使用独立搜索 API
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Use Independent Search API" else "使用独立搜索 API",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (isEn) "Fetch search results via custom search API before sending to LLM" else "在发送大模型前，先通过自定义搜索 API 检索信息",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                    Switch(
                        checked = useIndependentSearch,
                        onCheckedChange = { useIndependentSearch = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = useIndependentSearch,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // 搜索引擎 Provider 选择
                        Column {
                            Text(
                                text = "SEARCH API PROVIDER",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                        .clickable { searchProviderDropdownExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = searchProvider, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                                    Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                                }

                                DropdownMenu(
                                    expanded = searchProviderDropdownExpanded,
                                    onDismissRequest = { searchProviderDropdownExpanded = false },
                                    modifier = Modifier.fillMaxWidth(0.8f).background(MaterialTheme.colorScheme.surface)
                                ) {
                                    listOf("Tavily", "Custom").forEach { prov ->
                                        DropdownMenuItem(
                                            text = { Text(prov, color = MaterialTheme.colorScheme.onBackground) },
                                            onClick = {
                                                searchProvider = prov
                                                searchProviderDropdownExpanded = false
                                                if (prov == "Tavily") {
                                                    searchApiUrlInput = "https://api.tavily.com"
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Search API Base URL 输入框
                        Column {
                            Text(
                                text = "SEARCH API BASE URL",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            OutlinedTextField(
                                value = searchApiUrlInput,
                                onValueChange = { searchApiUrlInput = it },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Search API Key 输入框
                        Column {
                            Text(
                                text = "SEARCH API KEY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            OutlinedTextField(
                                value = searchApiKeyInput,
                                onValueChange = { searchApiKeyInput = it },
                                singleLine = true,
                                visualTransformation = if (showSearchApiKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (showSearchApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff
                                    IconButton(onClick = { showSearchApiKey = !showSearchApiKey }) {
                                        Icon(imageVector = image, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                    }
                                },
                                placeholder = { Text(text = "Enter Search API Key", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f), fontSize = 14.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
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
                    useIndependentSearch = useIndependentSearch,
                    searchProvider = searchProvider,
                    searchApiUrl = searchApiUrlInput,
                    searchApiKey = searchApiKeyInput
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
    onBackClick: () -> Unit
) {
    val isEn = appLanguage == "en"

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySettingsLayout(
    apiConfigList: List<ApiConfig>,
    activeConfigId: String,
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("loyea_prefs", android.content.Context.MODE_PRIVATE) }

    var enableMemory by remember { mutableStateOf(prefs.getBoolean("enable_memory_consolidation", true)) }
    var triggerCount by remember { mutableStateOf(prefs.getInt("memory_consolidation_trigger_count", 10)) }
    var memoryApiConfigId by remember { mutableStateOf(prefs.getString("memory_api_config_id", "") ?: "") }

    val isEn = appLanguage == "en"
    var expandedDropdown by remember { mutableStateOf(false) }

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
        }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // 头部开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isEn) "Read Aloud (TTS)" else "文本语音朗读 (TTS)",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Switch(
                                checked = ttsEnabled,
                                onCheckedChange = { viewModel?.updateMultimodalSetting("enable_tts", it) }
                            )
                        }

                        if (ttsEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                            
                            // 自动播报设置
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

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                            // 1. API 客户端配置
                            Column {
                                Text(
                                    text = if (isEn) "API Client Config" else "API 客户端连接",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                var ttsDropdownExpanded by remember { mutableStateOf(false) }
                                val currentConfig = apiConfigList.find { it.id == ttsConfigId }
                                val configText = currentConfig?.let { "${it.name} (${it.provider})" } ?: (if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置")
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { ttsDropdownExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = configText, fontSize = 13.sp)
                                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                    DropdownMenu(
                                        expanded = ttsDropdownExpanded,
                                        onDismissRequest = { ttsDropdownExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(if (isEn) "Follow Active Conversation Config" else "跟随当前会话配置", fontSize = 13.sp) },
                                            onClick = {
                                                viewModel?.updateMultimodalSetting("tts_config_id", "")
                                                ttsDropdownExpanded = false
                                            }
                                        )
                                        apiConfigList.forEach { config ->
                                            DropdownMenuItem(
                                                text = { Text("${config.name} (${config.provider})", fontSize = 13.sp) },
                                                onClick = {
                                                    viewModel?.updateMultimodalSetting("tts_config_id", config.id)
                                                    ttsDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. 预置服务商模板选择
                            Column {
                                Text(
                                    text = if (isEn) "API Template Protocol" else "API 对接模板协议",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                var protocolDropdownExpanded by remember { mutableStateOf(false) }
                                val protocolText = when (ttsProviderTemplate) {
                                    "Auto" -> if (isEn) "Auto Detect" else "自动判定服务商协议"
                                    "OpenAI" -> "OpenAI 官方规范"
                                    "MiMo" -> "小米 MiMo 规范"
                                    "Alibaba" -> "阿里百炼 (DashScope)"
                                    "Volcengine" -> "火山引擎 (豆包)"
                                    else -> if (isEn) "Custom Third-Party" else "完全自定义 / 其他中转"
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { protocolDropdownExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = protocolText, fontSize = 13.sp)
                                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                    DropdownMenu(
                                        expanded = protocolDropdownExpanded,
                                        onDismissRequest = { protocolDropdownExpanded = false }
                                    ) {
                                        val options = listOf(
                                            Pair("Auto", if (isEn) "Auto Detect" else "自动判定服务商协议"),
                                            Pair("OpenAI", "OpenAI 官方规范"),
                                            Pair("MiMo", "小米 MiMo 规范"),
                                            Pair("Alibaba", "阿里百炼 (DashScope)"),
                                            Pair("Volcengine", "火山引擎 (豆包)"),
                                            Pair("Custom", if (isEn) "Custom Third-Party" else "完全自定义 / 其他中转")
                                        )
                                        options.forEach { (key, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name, fontSize = 13.sp) },
                                                onClick = {
                                                    viewModel?.updateMultimodalSetting("tts_provider_template", key)
                                                    protocolDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // 智能识别当前运行的协议模板
                            val activeConfig = apiConfigList.find { it.id == ttsConfigId }
                            val effectiveProvider = if (ttsProviderTemplate == "Auto") {
                                activeConfig?.provider ?: "OpenAI"
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
                            
                            // 3. 模型选择与自定义
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isEn) "TTS Model Name" else "合成模型名称",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                    )
                                    if (ttsProviderTemplate == "Auto") {
                                        Text(
                                            text = "(${if (isEn) "Auto detected: " else "已自动匹配: "}$effectiveProvider)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                
                                // 模型快捷候选 Chip
                                currentTtsTemplate?.let { template ->
                                    if (template.models.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            template.models.forEach { modelCandidate ->
                                                val isSelected = ttsModelName == modelCandidate.id
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(
                                                            if (isSelected) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                        )
                                                        .border(
                                                            width = 1.dp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                                            shape = RoundedCornerShape(6.dp)
                                                        )
                                                        .clickable {
                                                            viewModel?.updateMultimodalSetting("tts_model_name", modelCandidate.id)
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                                ) {
                                                    Text(
                                                        text = modelCandidate.name,
                                                        fontSize = 11.sp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = ttsModelName,
                                    onValueChange = { viewModel?.updateMultimodalSetting("tts_model_name", it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 13.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    placeholder = { Text("e.g. tts-1, cosyvoice-v3-flash", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                                    singleLine = true
                                )
                            }

                            // 4. 音色选择
                            Column {
                                Text(
                                    text = if (isEn) "Select TTS Voice" else "选择合成音色",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                // 音色快捷候选 Grid
                                currentTtsTemplate?.let { template ->
                                    if (template.voices.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            val chunks = template.voices.chunked(2)
                                            chunks.forEach { rowVoices ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    rowVoices.forEach { voiceCandidate ->
                                                        val isSelected = selectedVoice == voiceCandidate.id
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(
                                                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                                                )
                                                                .border(
                                                                    width = 1.dp,
                                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                                    shape = RoundedCornerShape(8.dp)
                                                                )
                                                                .clickable {
                                                                    viewModel?.updateMultimodalSetting("tts_voice", voiceCandidate.id)
                                                                }
                                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                                    Icon(
                                                                        imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                                                        contentDescription = null,
                                                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                                                        modifier = Modifier.size(14.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(6.dp))
                                                                    Text(
                                                                        text = voiceCandidate.name,
                                                                        fontSize = 12.sp,
                                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                        maxLines = 1
                                                                    )
                                                                }
                                                                Text(
                                                                    text = voiceCandidate.id,
                                                                    fontSize = 10.sp,
                                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                                                    maxLines = 1
                                                                )
                                                            }
                                                        }
                                                    }
                                                    if (rowVoices.size == 1) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                }
                                
                                // 自定义输入
                                OutlinedTextField(
                                    value = selectedVoice,
                                    onValueChange = { viewModel?.updateMultimodalSetting("tts_voice", it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 13.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    placeholder = { Text("e.g. alloy, mimo_default, longanyang", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                // ==================== 2. 语音输入卡片 (STT) ====================
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
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isEn) "Voice Input (STT)" else "语音录音输入 (STT)",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Switch(
                                checked = sttEnabled,
                                onCheckedChange = { viewModel?.updateMultimodalSetting("enable_stt", it) }
                            )
                        }

                        if (sttEnabled) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                            MultimodalConfigForm(
                                configId = sttConfigId,
                                modelName = sttModelName,
                                apiConfigList = apiConfigList,
                                isEn = appLanguage,
                                onConfigIdChange = { viewModel?.updateMultimodalSetting("stt_config_id", it) },
                                onModelNameChange = { viewModel?.updateMultimodalSetting("stt_model_name", it) },
                                modelPlaceholder = "e.g. whisper-1"
                            )
                        }
                    }
                }
            }

            // ==================== 3. 视觉与图片理解卡片 (Vision) ====================
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
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isEn) "Visual Understanding" else "视觉图片理解 (Vision)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                    MultimodalConfigForm(
                        configId = visionConfigId,
                        modelName = visionModelName,
                        apiConfigList = apiConfigList,
                        isEn = appLanguage,
                        onConfigIdChange = { viewModel?.updateMultimodalSetting("vision_config_id", it) },
                        onModelNameChange = { viewModel?.updateMultimodalSetting("vision_model_name", it) },
                        modelPlaceholder = "e.g. gpt-4o-mini, claude-3-5-sonnet"
                    )
                }
            }

            // ==================== 4. AI 图像生成卡片 (ImageGen) ====================
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
                                imageVector = Icons.Default.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isEn) "Image Generation" else "AI 图像生成 (生图)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Switch(
                            checked = imageGenEnabled,
                            onCheckedChange = { viewModel?.updateMultimodalSetting("enable_image_gen", it) }
                        )
                    }

                    if (imageGenEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        MultimodalConfigForm(
                            configId = imageGenConfigId,
                            modelName = imageModelName,
                            apiConfigList = apiConfigList,
                            isEn = appLanguage,
                            onConfigIdChange = { viewModel?.updateMultimodalSetting("image_gen_config_id", it) },
                            onModelNameChange = { viewModel?.updateMultimodalSetting("image_gen_model", it) },
                            modelPlaceholder = "e.g. dall-e-3, mimo-v2.5-images"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MultimodalConfigForm(
    configId: String,
    modelName: String,
    apiConfigList: List<ApiConfig>,
    isEn: String,
    onConfigIdChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    modelPlaceholder: String = ""
) {
    val isEnglish = isEn == "en"
    var expandedDropdown by remember { mutableStateOf(false) }
    val currentConfig = apiConfigList.find { it.id == configId }
    val configText = currentConfig?.let { "${it.name} (${it.provider})" } ?: (if (isEnglish) "Follow Active Conversation Config" else "跟随当前会话配置")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 服务商选择
        Column {
            Text(
                text = if (isEnglish) "API Client Provider" else "API 客户端服务商",
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
                    .clickable { expandedDropdown = true }
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
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false },
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isEnglish) "Follow Active Conversation Config" else "跟随当前会话配置", fontSize = 13.sp) },
                        onClick = {
                            onConfigIdChange("")
                            expandedDropdown = false
                        }
                    )
                    apiConfigList.forEach { config ->
                        DropdownMenuItem(
                            text = { Text("${config.name} (${config.provider})", fontSize = 13.sp) },
                            onClick = {
                                onConfigIdChange(config.id)
                                expandedDropdown = false
                            }
                        )
                    }
                }
            }
        }

        // 模型名称自定义
        Column {
            Text(
                text = if (isEnglish) "Custom Model Name" else "自定义模型名称",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelNameChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                placeholder = { Text(modelPlaceholder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
                singleLine = true
            )
        }
    }
}

