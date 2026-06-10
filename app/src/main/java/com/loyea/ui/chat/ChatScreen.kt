package com.loyea.ui.chat

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.theme.LoyeaTheme
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import com.loyea.ui.chat.PromptAssembler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    userName: String,
    apiConfig: com.loyea.ui.settings.ApiConfig,
    apiConfigList: List<com.loyea.ui.settings.ApiConfig>,
    onActiveConfigChange: (String) -> Unit,
    appLanguage: String,
    userBubbleColor: String,
    messages: List<Message>,
    isThinking: Boolean,
    onSendMessage: (String) -> Unit,
    onToggleThoughts: (String) -> Unit,
    onNewChatClick: (CharacterCard) -> Unit,
    onMenuClick: () -> Unit,
    activeCharacterCard: CharacterCard,
    characterCardList: List<CharacterCard>,
    useSystemTime: Boolean = false,
    onToggleSystemTime: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val isEn = appLanguage == "en"

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var showPersonaSelector by remember { mutableStateOf(false) }

    // 自动滚动到底部
    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1 + if (isThinking) 1 else 0)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    ModelSelector(
                        activeCharacterCard = activeCharacterCard,
                        selectedModelName = apiConfig.name,
                        apiConfigList = apiConfigList,
                        onActiveConfigChange = onActiveConfigChange,
                        useSystemTime = useSystemTime,
                        onToggleSystemTime = onToggleSystemTime,
                        appLanguage = appLanguage
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    val hasUserSpoken = remember(messages) {
                        messages.any { it.sender == Sender.USER }
                    }
                    if (hasUserSpoken) {
                        IconButton(onClick = { showPersonaSelector = true }) {
                            Icon(
                                imageVector = Icons.Default.EditNote,
                                contentDescription = "New Chat",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        val backgroundBitmap = rememberBackgroundPainter(activeCharacterCard.backgroundUri)
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .then(
                    if (backgroundBitmap != null) {
                        Modifier.paint(
                            painter = BitmapPainter(backgroundBitmap),
                            contentScale = ContentScale.Crop,
                            alpha = 0.12f
                        )
                    } else {
                        Modifier
                    }
                )
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 消息流
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 占位，防止贴顶
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(messages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        userBubbleColor = userBubbleColor,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, if (isEn) "Copied to clipboard" else "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        onToggleThoughts = {
                            onToggleThoughts(message.id)
                        }
                    )
                }

                if (isThinking) {
                    item {
                        ThinkingIndicator()
                    }
                }

                // 占位，防止贴底
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // 底部输入区
            ChatInputBar(
                value = inputText,
                appLanguage = appLanguage,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.text.isNotBlank()) {
                        onSendMessage(inputText.text)
                        inputText = TextFieldValue("")
                    }
                },
                onAttach = {
                    Toast.makeText(context, "Attachment clicked", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showPersonaSelector) {
            ModalBottomSheet(
                onDismissRequest = { showPersonaSelector = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)) }
            ) {
                SelectPersonaContent(
                    characterCardList = characterCardList,
                    appLanguage = appLanguage,
                    onSelect = { selectedChar ->
                        showPersonaSelector = false
                        onNewChatClick(selectedChar)
                    }
                )
            }
        }
    }
}

// 1:1 复刻 Claude 顶部模型选择胶囊 (增强角色卡头像及双行文本解耦设计)
@Composable
fun ModelSelector(
    activeCharacterCard: CharacterCard,
    selectedModelName: String,
    apiConfigList: List<com.loyea.ui.settings.ApiConfig>,
    onActiveConfigChange: (String) -> Unit,
    useSystemTime: Boolean,
    onToggleSystemTime: () -> Unit,
    appLanguage: String
) {
    var expanded by remember { mutableStateOf(false) }
    val isEn = appLanguage == "en"
    val enabledConfigs = remember(apiConfigList) {
        apiConfigList.filter { it.isEnabled }
    }

    val avatarBitmap = rememberAvatarPainter(activeCharacterCard.avatarUri)

    Box(
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 头像部分
            if (avatarBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = avatarBitmap,
                    contentDescription = activeCharacterCard.name,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                val bgColor = remember(activeCharacterCard.avatarColor) {
                    try {
                        Color(android.graphics.Color.parseColor(activeCharacterCard.avatarColor))
                    } catch (e: Exception) {
                        Color(0xFFE5D3B3)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(bgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = activeCharacterCard.name.take(1).uppercase(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            // 名字和模型配置
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = activeCharacterCard.name,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = selectedModelName.ifBlank { "无可用模型" },
                    style = TextStyle(
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            
            if (enabledConfigs.size > 1) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select Model",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            if (enabledConfigs.isNotEmpty()) {
                enabledConfigs.forEach { config ->
                    DropdownMenuItem(
                        text = { Text(config.name, color = MaterialTheme.colorScheme.onBackground) },
                        onClick = {
                            onActiveConfigChange(config.id)
                            expanded = false
                        }
                    )
                }
                
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }

            DropdownMenuItem(
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEn) "Physical Perception" else "物理感知 (时间)",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(24.dp))
                        Switch(
                            checked = useSystemTime,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                },
                onClick = {
                    onToggleSystemTime()
                }
            )
        }
    }
}

@Composable
fun rememberAvatarPainter(avatarUri: String?): ImageBitmap? {
    if (avatarUri.isNullOrBlank()) return null
    return remember(avatarUri) {
        try {
            val file = File(avatarUri)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun rememberBackgroundPainter(backgroundUri: String?): ImageBitmap? {
    if (backgroundUri.isNullOrBlank()) return null
    return remember(backgroundUri) {
        try {
            val file = File(backgroundUri)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

@Composable
fun SelectPersonaContent(
    characterCardList: List<CharacterCard>,
    appLanguage: String,
    onSelect: (CharacterCard) -> Unit
) {
    val isEn = appLanguage == "en"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = if (isEn) "Choose a Persona" else "选择对话人格",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
        ) {
            items(characterCardList) { card ->
                val avatarBitmap = rememberAvatarPainter(card.avatarUri)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        .clickable { onSelect(card) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (avatarBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = avatarBitmap,
                            contentDescription = card.name,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        val bgColor = remember(card.avatarColor) {
                            try {
                                Color(android.graphics.Color.parseColor(card.avatarColor))
                            } catch (e: Exception) {
                                Color(0xFFE5D3B3)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(bgColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = card.name.take(1).uppercase(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = card.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = card.shortIntro,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            maxLines = 2
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// 消息卡片项 (带上浮淡入级联微动效)
@Composable
fun MessageItem(
    message: Message,
    userBubbleColor: String,
    onCopy: () -> Unit,
    onToggleThoughts: () -> Unit
) {
    val isUser = message.sender == Sender.USER

    // 每一个气泡被创建时，都会伴随一个平滑的自下而上淡入效果
    val animatableAlpha = remember { Animatable(0f) }
    val animatableOffsetY = remember { Animatable(30f) } // 自下而上漂移 30 像素

    LaunchedEffect(key1 = message.id) {
        launch {
            animatableAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
            )
        }
        launch {
            animatableOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animatableAlpha.value
                translationY = animatableOffsetY.value
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // 用户气泡：有圆角和自适应的背景颜色
            val bubbleBgColor = remember(userBubbleColor) {
                if (userBubbleColor.isNotBlank()) {
                    try {
                        Color(android.graphics.Color.parseColor(userBubbleColor))
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            val bubbleTextColor = if (bubbleBgColor != null) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd = 4.dp
                        )
                    )
                    .background(bubbleBgColor ?: MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = bubbleTextColor
                )
            }
        } else if (message.isError) {
            // 错误气泡：柔和淡红背景，警告深红文字，类似警告通知卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = 4.dp,
                            bottomEnd = 16.dp
                        )
                    )
                    .background(Color(0xFFFDE8E8)) // 柔和浅红背景
                    .border(1.dp, Color(0xFFF8B4B4), RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        tint = Color(0xFFE02424),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = message.content.replace("[错误]", "").trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFE02424) // 警告深红文字
                    )
                }
            }
        } else {
            // AI 气泡：无背景，纯文本，左侧缩进排版，支持 MCP & Thinking 展开
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. 渲染所有的 MCP 调用项
                if (message.mcpCalls.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.mcpCalls.forEach { call ->
                            McpCallItem(mcpCall = call)
                        }
                    }
                }

                // 2. 渲染 Thinking 推理链
                if (message.thoughts != null) {
                    ThinkingProcessLayout(
                        thoughts = message.thoughts,
                        isExpanded = message.isThoughtsExpanded,
                        onToggle = onToggleThoughts,
                        durationSeconds = message.thoughtDurationSeconds,
                        isStillThinking = message.isStillThinking
                    )
                }

                // 3. 渲染主回答正文 (当正文不为空时)
                if (message.content.isNotBlank()) {
                    MarkdownText(
                        text = message.content,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // AI 动作条
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    val iconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = { /* TODO: Speak */ }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Speak",
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = { /* TODO: Regenerate */ }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = "Regenerate",
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = { /* TODO: Good */ }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.ThumbUp,
                            contentDescription = "Up",
                            tint = iconColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

// 1:1 复刻 Claude 输入框
@Composable
fun ChatInputBar(
    value: TextFieldValue,
    appLanguage: String,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit
) {
    val isTextEmpty = value.text.isBlank()
    val isEn = appLanguage == "en"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 加号附件按钮
            IconButton(
                onClick = onAttach,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // 自定义 BasicTextField，实现无边框和完美居中对齐
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                if (isTextEmpty) {
                    Text(
                        text = if (isEn) "Talk to Loyea" else "与 Loyea 对话",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 发送或语音录音按钮 (高级 Scale Fade 动画切换)
        val buttonColor by animateColorAsState(
            targetValue = if (isTextEmpty) Color.Transparent else MaterialTheme.colorScheme.primary,
            animationSpec = tween(300),
            label = "ButtonColor"
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(enabled = !isTextEmpty) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = isTextEmpty,
                transitionSpec = {
                    (scaleIn(initialScale = 0.8f) + fadeIn(animationSpec = tween(220, delayMillis = 90)))
                        .togetherWith(scaleOut(targetScale = 0.8f) + fadeOut(animationSpec = tween(90)))
                },
                label = "SendButtonTransition"
            ) { isEmpty ->
                if (isEmpty) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Record",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// 思考中闪烁点动画
@Composable
fun ThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "Thinking")
    
    @Composable
    fun animateDotAlpha(delayMillis: Int): State<Float> {
        return infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(delayMillis)
            ),
            label = "DotAlpha"
        )
    }

    val alpha1 by animateDotAlpha(0)
    val alpha2 by animateDotAlpha(200)
    val alpha3 by animateDotAlpha(400)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = MaterialTheme.colorScheme.onBackground
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha1)))
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha2)))
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha3)))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ChatScreenPreview() {
    LoyeaTheme {
        val defaultChar = TavernCardParser.getBuiltInCards().first()
        ChatScreen(
            userName = "Loyea Developer",
            apiConfig = ApiConfig(),
            apiConfigList = listOf(ApiConfig()),
            onActiveConfigChange = {},
            appLanguage = "zh",
            userBubbleColor = "",
            messages = emptyList(),
            isThinking = false,
            onSendMessage = {},
            onToggleThoughts = {},
            onNewChatClick = {},
            onMenuClick = {},
            activeCharacterCard = defaultChar,
            characterCardList = listOf(defaultChar)
        )
    }
}
