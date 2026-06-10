package com.loyea.ui.settings

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

// 二级页面枚举
enum class SettingsSubPage {
    MAIN, API_CONFIG, THEME_SETTINGS
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
    val enableReasoning: Boolean = true
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
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subPage by remember { mutableStateOf(SettingsSubPage.MAIN) }

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
                    onNavigateToApi = { subPage = SettingsSubPage.API_CONFIG },
                    onNavigateToTheme = { subPage = SettingsSubPage.THEME_SETTINGS },
                    onBackClick = onBackClick
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
    onNavigateToApi: () -> Unit,
    onNavigateToTheme: () -> Unit,
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
            // 1. 用户资料卡片 (精致的迷你极简编辑行)
            Text(
                text = if (isEn) "ACCOUNT PROFILE" else "个人资料",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 迷你精致头像
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (userName.isNotBlank()) userName.take(1).uppercase() else "L",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                InlineEditNameField(
                    initialName = userName,
                    onSave = onUserNameSave,
                    modifier = Modifier.weight(1f)
                )
            }

            // 2. 首选项与二级跳转
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
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && isEditing) {
                            if (nameText.isNotBlank()) {
                                onSave(nameText)
                            }
                            isEditing = false
                        }
                    }
                    .padding(vertical = 2.dp)
            )
            
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
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
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
    var selectedProvider by remember { mutableStateOf(editingConfig?.provider ?: "Anthropic") }
    var apiUrlInput by remember { mutableStateOf(editingConfig?.apiUrl ?: "https://api.anthropic.com") }
    var apiKeyInput by remember { mutableStateOf(editingConfig?.apiKey ?: "") }
    var modelInput by remember { mutableStateOf(editingConfig?.modelName ?: "claude-3-5-sonnet") }
    
    var enableSearch by remember { mutableStateOf(editingConfig?.enableSearch ?: false) }
    var enableReasoning by remember { mutableStateOf(editingConfig?.enableReasoning ?: true) }

    var showApiKey by remember { mutableStateOf(false) }
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    val providersList = listOf(
        "Anthropic", "OpenAI", "DeepSeek", 
        "Kimi (Moonshot)", "Qwen (千问)", "MiniMax", "MiMo", "Custom"
    )

    val recommendedModels = remember(selectedProvider) {
        when (selectedProvider) {
            "Anthropic" -> listOf("claude-3-5-sonnet", "claude-3-haiku")
            "OpenAI" -> listOf("gpt-4o", "gpt-4o-mini")
            "DeepSeek" -> listOf("deepseek-chat", "deepseek-coder")
            "Kimi (Moonshot)" -> listOf("moonshot-v1-8k")
            "Qwen (千问)" -> listOf("qwen-turbo", "qwen-max")
            "MiniMax" -> listOf("abab6.5-chat")
            "MiMo" -> listOf("mimo-v1")
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
                                    "Anthropic" -> {
                                        apiUrlInput = "https://api.anthropic.com"
                                        modelInput = "claude-3-5-sonnet"
                                    }
                                    "OpenAI" -> {
                                        apiUrlInput = "https://api.openai.com/v1"
                                        modelInput = "gpt-4o"
                                    }
                                    "DeepSeek" -> {
                                        apiUrlInput = "https://api.deepseek.com/v1"
                                        modelInput = "deepseek-chat"
                                    }
                                    "Kimi (Moonshot)" -> {
                                        apiUrlInput = "https://api.moonshot.cn/v1"
                                        modelInput = "moonshot-v1-8k"
                                    }
                                    "Qwen (千问)" -> {
                                        apiUrlInput = "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                        modelInput = "qwen-turbo"
                                    }
                                    "MiniMax" -> {
                                        apiUrlInput = "https://api.minimax.chat/v1"
                                        modelInput = "abab6.5-chat"
                                    }
                                    "MiMo" -> {
                                        apiUrlInput = "https://api.mimo.com/v1"
                                        modelInput = "mimo-v1"
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
                placeholder = { Text("e.g. deepseek-chat", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)) },
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
                    enableReasoning = enableReasoning
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
                        Triple("", if (isEn) "Default Container" else "系统默认气泡", Color.Transparent),
                        Triple("#EADFD3", if (isEn) "Loyea Warm Amber" else "琥珀沙黄 (Loyea 风格)", Color(0xFFEADFD3)),
                        Triple("#F0F0F2", if (isEn) "ChatGPT Gray" else "莫兰迪灰 (ChatGPT 风格)", Color(0xFFF0F0F2)),
                        Triple("#E2F1E8", if (isEn) "Emerald Green" else "微光浅绿", Color(0xFFE2F1E8)),
                        Triple("#DCEAF5", if (isEn) "Loyea Blue" else "极简天蓝 (Loyea 风格)", Color(0xFFDCEAF5))
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
            apiConfigList = listOf(ApiConfig(name = "Deepseek Pro", provider = "DeepSeek", modelName = "deepseek-chat")),
            activeConfigId = "ds_pro",
            onApiConfigListSave = {},
            onActiveConfigSelect = {},
            appLanguage = appLanguage,
            onAppLanguageChange = { appLanguage = it },
            userBubbleColor = userBubbleColor,
            onUserBubbleColorChange = { userBubbleColor = it },
            onBackClick = {}
        )
    }
}


