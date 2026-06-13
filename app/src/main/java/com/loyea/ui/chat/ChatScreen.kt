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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    apiConfig: com.loyea.ui.settings.ApiConfig,
    apiConfigList: List<com.loyea.ui.settings.ApiConfig>,
    onActiveConfigChange: (String) -> Unit,
    appLanguage: String,
    userBubbleColor: String,
    messages: List<Message>,
    isThinking: Boolean,
    isMcpRunning: Boolean,
    onSendMessage: (String, String?, String?, Int) -> Unit,
    onStopResponse: () -> Unit,
    onToggleThoughts: (String) -> Unit,
    onNewChatClick: (CharacterCard) -> Unit,
    onMenuClick: () -> Unit,
    activeCharacterCard: CharacterCard,
    characterCardList: List<CharacterCard>,
    currentSessionId: String,
    getDraft: (String) -> String,
    saveDraft: (String, String) -> Unit,
    clearDraft: (String) -> Unit,
    onEditMessage: (String, String) -> Unit,
    viewModel: ChatViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val isEn = appLanguage == "en"
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var showPersonaSelector by remember { mutableStateOf(false) }
    var lastSessionId by remember { mutableStateOf(currentSessionId) }

    val selectedImagePath = remember { mutableStateOf<String?>(null) }
    var lightboxImagePath by remember { mutableStateOf<String?>(null) }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val file = File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                selectedImagePath.value = file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 切换会话时，自动保存和载入草稿
    LaunchedEffect(currentSessionId) {
        if (lastSessionId.isNotEmpty() && lastSessionId != currentSessionId) {
            saveDraft(lastSessionId, inputText.text)
        }
        val loadedDraft = getDraft(currentSessionId)
        inputText = TextFieldValue(loadedDraft)
        lastSessionId = currentSessionId
        selectedImagePath.value = null // 切换会话时清空图片预览
    }

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
                        onActiveConfigChange = onActiveConfigChange
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
                .padding(paddingValues) // Scaffold 自动处理了状态栏和导航栏
                .consumeWindowInsets(paddingValues) // 消耗掉已经应用的 insets
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
                        currentlyPlayingAudioId = viewModel?.currentlyPlayingAudioId?.value,
                        onCopy = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, if (isEn) "Copied to clipboard" else "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        onToggleThoughts = {
                            onToggleThoughts(message.id)
                        },
                        onEdit = { newText ->
                            onEditMessage(message.id, newText)
                        },
                        onSpeak = {
                            viewModel?.playTts(message.id, message.content)
                        },
                        onMcpVoicePlay = { mcpCallId ->
                            viewModel?.playMcpVoice(mcpCallId)
                        },
                        onImageClick = { path ->
                            lightboxImagePath = path
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

            // 1. 已选图片预览卡片
            if (selectedImagePath.value != null) {
                val previewBitmap = rememberLocalImagePainter(selectedImagePath.value)
                if (previewBitmap != null) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = "Preview Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = { selectedImagePath.value = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // 2. 录音波形状态面板 (当正在录音时)
            val isRecording = viewModel?.isRecording?.value ?: false
            val recordingDuration = viewModel?.recordingDuration?.value ?: 0
            val recordingAmplitude = viewModel?.recordingAmplitude?.value ?: 0f

            AnimatedVisibility(
                visible = isRecording,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 红色录音呼吸点
                            val infiniteTransition = rememberInfiniteTransition(label = "BlinkingDot")
                            val alphaDot by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(600, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "BlinkingDotAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .graphicsLayer { alpha = alphaDot }
                                    .background(Color.Red, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val seconds = recordingDuration / 10
                            val tenths = recordingDuration % 10
                            Text(
                                text = String.format("%02d:%02d.%d", seconds / 60, seconds % 60, tenths),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // 绘制跳跃式波动音轨 (12柱 Q弹物理过渡)
                        Row(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val amplitudeFactor = (recordingAmplitude / 32767f).coerceIn(0f, 1f)
                            for (i in 0 until 12) {
                                val centerFactor = remember(i) {
                                    val dist = Math.abs(i - 5.5f)
                                    (1f - (dist / 6f)).coerceIn(0.2f, 1f)
                                }
                                val randomOffset = remember(i) { kotlin.random.Random.nextFloat() * 0.3f + 0.7f }
                                val targetHeight = 8.dp + (32.dp * amplitudeFactor * centerFactor * randomOffset)
                                val animatedHeight by animateDpAsState(
                                    targetValue = targetHeight,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessLow
                                    ),
                                    label = "WaveformHeight_$i"
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(3.5.dp)
                                        .height(animatedHeight)
                                        .clip(CircleShape)
                                        .background(
                                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                                )
                                            )
                                        )
                                )
                            }
                        }

                        // 动作按钮
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel?.stopRecording { _, _ -> } },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isEn) "Cancel" else "取消",
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                            Button(
                                onClick = {
                                    viewModel?.stopRecording { file, duration ->
                                        if (file != null && duration > 0) {
                                            viewModel.transcribeAndSendAudio(file, duration) {
                                                Toast.makeText(context, if (isEn) "Speech recognition failed" else "语音识别失败，未提取到有效文字", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Text(
                                    text = if (isEn) "Send" else "说完了",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // 底部输入区
            ChatInputBar(
                value = inputText,
                appLanguage = appLanguage,
                characterName = activeCharacterCard.name,
                onValueChange = { 
                    val filteredText = it.text.replace("\n", "").replace("\r", "")
                    inputText = it.copy(text = filteredText)
                    if (currentSessionId.isNotEmpty()) {
                        saveDraft(currentSessionId, filteredText)
                    }
                },
                onSend = {
                    val trimmed = inputText.text.trim()
                    if (trimmed.isNotBlank() || selectedImagePath.value != null) {
                        onSendMessage(trimmed, selectedImagePath.value, null, 0)
                        inputText = TextFieldValue("")
                        selectedImagePath.value = null
                        if (currentSessionId.isNotEmpty()) {
                            clearDraft(currentSessionId)
                        }
                        keyboardController?.hide()
                    }
                },
                onStop = onStopResponse,
                onAttach = {
                    pickMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onVoiceClick = {
                    val isSttEnabled = viewModel?.enableStt?.value ?: true
                    if (isSttEnabled) {
                        viewModel?.startRecording()
                    } else {
                        Toast.makeText(context, if (isEn) "STT is disabled in settings" else "语音输入功能在设置中已被关闭", Toast.LENGTH_SHORT).show()
                    }
                },
                isThinking = isThinking,
                isMcpRunning = isMcpRunning
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

        if (lightboxImagePath != null) {
            Dialog(
                onDismissRequest = { lightboxImagePath = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { lightboxImagePath = null }
                ) {
                    val lbBitmap = rememberLocalImagePainter(lightboxImagePath)
                    if (lbBitmap != null) {
                        Image(
                            bitmap = lbBitmap,
                            contentDescription = "Full Image",
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
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
    onActiveConfigChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
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
                .clickable { if (enabledConfigs.size > 1) expanded = true }
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

        if (enabledConfigs.size > 1) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                enabledConfigs.forEach { config ->
                    DropdownMenuItem(
                        text = { Text(config.name, color = MaterialTheme.colorScheme.onBackground) },
                        onClick = {
                            onActiveConfigChange(config.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun rememberLocalImagePainter(imagePath: String?): ImageBitmap? {
    if (imagePath.isNullOrBlank()) return null
    return remember(imagePath) {
        try {
            val file = File(imagePath)
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
    currentlyPlayingAudioId: String?,
    onCopy: () -> Unit,
    onToggleThoughts: () -> Unit,
    onEdit: (String) -> Unit,
    onSpeak: () -> Unit,
    onMcpVoicePlay: (String) -> Unit,
    onImageClick: (String) -> Unit
) {
    val isUser = message.sender == Sender.USER

    val animatableAlpha = remember { Animatable(0f) }
    val animatableOffsetY = remember { Animatable(30f) }

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

    // Shimmer 占位呼吸效果
    val shimmerTransition = rememberInfiniteTransition(label = "ShimmerTransition")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ShimmerAlpha"
    )

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
                val r = bubbleBgColor.red
                val g = bubbleBgColor.green
                val b = bubbleBgColor.blue
                val brightness = r * 0.299f + g * 0.587f + b * 0.114f
                if (brightness > 0.6f) {
                    Color(0xFF1A1A1A)
                } else {
                    Color(0xFFFAFAFA)
                }
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }

            var isEditing by remember { mutableStateOf(false) }
            var editInputText by remember(message.content) { mutableStateOf(TextFieldValue(message.content)) }
            var showUserCopyIcon by remember { mutableStateOf(false) }

            // 1. 用户图片展示 (带 Shimmer 骨架屏占位)
            if (!message.imageUrl.isNullOrBlank()) {
                val imageBitmap = rememberLocalImagePainter(message.imageUrl)
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "User Image",
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .heightIn(max = 200.dp)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .clickable { onImageClick(message.imageUrl) },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(200.dp)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f * shimmerAlpha))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // 2. 用户语音条展示 (带播放中三柱实时波形动效)
            if (!message.audioUrl.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(bubbleBgColor ?: MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { onSpeak() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "${message.audioDuration}\"",
                        color = bubbleTextColor,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    if (message.isAudioPlaying) {
                        Row(
                            modifier = Modifier.height(16.dp).padding(start = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (j in 0 until 3) {
                                val infiniteTransition = rememberInfiniteTransition(label = "audioPlayingUser_${message.id}_$j")
                                val heightPercent by infiniteTransition.animateFloat(
                                    initialValue = 0.2f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(400 + j * 120, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "AudioHeight_${message.id}_$j"
                                )
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .fillMaxHeight(heightPercent)
                                        .clip(CircleShape)
                                        .background(bubbleTextColor)
                                )
                            }
                        }
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Play Voice",
                            tint = bubbleTextColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 3. 用户文本展示
            if (message.content.isNotBlank()) {
                if (isEditing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                            .background(bubbleBgColor ?: MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp)
                    ) {
                        BasicTextField(
                            value = editInputText,
                            onValueChange = { editInputText = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = bubbleTextColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            cursorBrush = SolidColor(bubbleTextColor)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { 
                                    isEditing = false 
                                    editInputText = TextFieldValue(message.content)
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = bubbleTextColor.copy(alpha = 0.7f))
                            ) {
                                Text("取消", fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val trimmed = editInputText.text.trim()
                                    if (trimmed.isNotBlank()) {
                                        onEdit(trimmed)
                                        isEditing = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("保存并回溯", fontSize = 13.sp)
                            }
                        }
                    }
                } else {
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
                            .clickable { showUserCopyIcon = !showUserCopyIcon }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = bubbleTextColor
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showUserCopyIcon && !isEditing,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(top = 4.dp, end = 4.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(
                        onClick = { 
                            isEditing = true
                            showUserCopyIcon = false
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { 
                            onCopy()
                            showUserCopyIcon = false
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else if (message.isError) {
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
                    .background(Color(0xFFFDE8E8))
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
                        color = Color(0xFFE02424)
                    )
                }
            }
        } else {
            // AI 气泡
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.mcpCalls.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.mcpCalls.forEach { call ->
                            val isVoiceReply = call.toolName.equals("send_voice_reply", ignoreCase = true) || 
                                               call.toolName.endsWith("__send_voice_reply", ignoreCase = true) || 
                                               call.toolName.endsWith(".send_voice_reply", ignoreCase = true)
                            if (isVoiceReply) {
                                McpVoiceReplyItem(
                                    call = call,
                                    currentlyPlayingAudioId = currentlyPlayingAudioId,
                                    onPlayClick = onMcpVoicePlay
                                )
                            } else {
                                McpCallItem(mcpCall = call)
                            }
                        }
                    }
                }

                if (message.thoughts != null) {
                    ThinkingProcessLayout(
                        thoughts = message.thoughts,
                        isExpanded = message.isThoughtsExpanded,
                        onToggle = onToggleThoughts,
                        durationSeconds = message.thoughtDurationSeconds,
                        isStillThinking = message.isStillThinking
                    )
                }

                // AI 生图展示 (带 Shimmer 骨架屏占位)
                if (!message.imageUrl.isNullOrBlank()) {
                    val imageBitmap = rememberLocalImagePainter(message.imageUrl)
                    if (imageBitmap != null) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "AI Generated Image",
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .heightIn(max = 200.dp)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                .clickable { onImageClick(message.imageUrl) },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(200.dp)
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f * shimmerAlpha))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    strokeWidth = 2.5.dp
                                )
                                Text(
                                    text = "AI 正在绘制/加载图片...",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // AI 语音条展示
                if (!message.audioUrl.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .clickable { onSpeak() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isAudioPlaying) {
                            Row(
                                modifier = Modifier.height(16.dp).padding(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (j in 0 until 3) {
                                    val infiniteTransition = rememberInfiniteTransition(label = "audioPlayingAiBubble_${message.id}_$j")
                                    val heightPercent by infiniteTransition.animateFloat(
                                        initialValue = 0.2f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(400 + j * 120, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "AudioHeightAiBubble_${message.id}_$j"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .fillMaxHeight(heightPercent)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Play Voice",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp).padding(end = 8.dp)
                            )
                        }
                        Text(
                            text = "${message.audioDuration}\"",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (message.isAudioSynthesizing) {
                    // 正在合成中的占位长条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "语音回复合成中...",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (message.content.isNotBlank()) {
                    val processedContent = remember(message.content) {
                        message.content.replace(Regex("(?<!`)`(?!`)"), "\\\\`")
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        MarkdownText(
                            text = processedContent,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
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
                    
                    // Speak 朗读按钮，带正在播放时的实时三柱均衡器动画，以及正在合成中的 Loading 状态
                    val isSynthesizing = message.isAudioSynthesizing
                    IconButton(
                        onClick = { if (!isSynthesizing) onSpeak() }, 
                        modifier = Modifier.size(28.dp),
                        enabled = !isSynthesizing
                    ) {
                        if (isSynthesizing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (message.isAudioPlaying) {
                            Row(
                                modifier = Modifier.height(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (j in 0 until 3) {
                                    val infiniteTransition = rememberInfiniteTransition(label = "audioPlayingAi_${message.id}_$j")
                                    val heightPercent by infiniteTransition.animateFloat(
                                        initialValue = 0.2f,
                                        targetValue = 1.0f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(400 + j * 120, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "AudioHeightAi_${message.id}_$j"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(2.dp)
                                            .fillMaxHeight(heightPercent)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak",
                                tint = iconColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatTime(message.timestamp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                    )
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
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoiceClick: () -> Unit,
    isThinking: Boolean = false,
    isMcpRunning: Boolean = false,
    characterName: String = "Loyea"
) {
    val isTextEmpty = value.text.isBlank()
    val isEn = appLanguage == "en"
    val isActive = isThinking || isMcpRunning

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

            // 自定义 BasicTextField
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                if (isTextEmpty) {
                    Text(
                        text = if (isEn) "Talk to $characterName" else "与 $characterName 对话",
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter) {
                                if (isActive) {
                                    true
                                } else {
                                    false
                                }
                            } else {
                                    false
                            }
                        },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!isTextEmpty && !isActive) {
                                onSend()
                            }
                        }
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 发送、停止或语音按钮
        val buttonColor by animateColorAsState(
            targetValue = if (isActive) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
            } else if (isTextEmpty) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)
            } else {
                MaterialTheme.colorScheme.primary
            },
            animationSpec = tween(300),
            label = "ButtonColor"
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable { 
                    if (isActive) {
                        onStop()
                    } else if (!isTextEmpty) {
                        onSend()
                    } else {
                        onVoiceClick()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = if (isActive) "stop" else if (isTextEmpty) "mic" else "send",
                transitionSpec = {
                    (scaleIn(initialScale = 0.8f) + fadeIn(animationSpec = tween(220)))
                        .togetherWith(scaleOut(targetScale = 0.8f) + fadeOut(animationSpec = tween(90)))
                },
                label = "InputButtonTransition"
            ) { target ->
                when (target) {
                    "stop" -> Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    "mic" -> Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Record",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                    else -> Icon(
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
            apiConfig = ApiConfig(),
            apiConfigList = listOf(ApiConfig()),
            onActiveConfigChange = {},
            appLanguage = "zh",
            userBubbleColor = "",
            messages = emptyList(),
            isThinking = false,
            isMcpRunning = false,
            onSendMessage = { _, _, _, _ -> },
            onStopResponse = {},
            onToggleThoughts = {},
            onNewChatClick = {},
            onMenuClick = {},
            activeCharacterCard = defaultChar,
            characterCardList = listOf(defaultChar),
            currentSessionId = "session_id",
            getDraft = { "" },
            saveDraft = { _, _ -> },
            clearDraft = {},
            onEditMessage = { _, _ -> }
        )
    }
}

@Composable
fun McpVoiceReplyItem(
    call: McpCall,
    currentlyPlayingAudioId: String?,
    onPlayClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlaying = currentlyPlayingAudioId == call.id

    var duration = 0
    var hasVoiceUrl = false
    
    val output = call.output
    if (!output.isNullOrBlank() && output.startsWith("AUDIO_URL:")) {
        try {
            val parts = output.split("|")
            val urlPart = parts.getOrNull(0) ?: ""
            val durationPart = parts.getOrNull(1) ?: ""
            if (urlPart.startsWith("AUDIO_URL:") && durationPart.startsWith("DURATION:")) {
                duration = durationPart.removePrefix("DURATION:").toIntOrNull() ?: 0
                hasVoiceUrl = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    when (call.status) {
        McpStatus.RUNNING -> {
            Row(
                modifier = modifier
                    .fillMaxWidth(0.7f)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "语音合成中...",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        McpStatus.SUCCESS -> {
            if (hasVoiceUrl) {
                Row(
                    modifier = modifier
                        .fillMaxWidth(0.7f)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .clickable { 
                            onPlayClick(call.id)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPlaying) {
                        Row(
                            modifier = Modifier.height(16.dp).padding(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (j in 0 until 3) {
                                val infiniteTransition = rememberInfiniteTransition(label = "audioPlayingMcpCall_${call.id}_$j")
                                val heightPercent by infiniteTransition.animateFloat(
                                    initialValue = 0.2f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(400 + j * 120, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "AudioHeightMcpCall_${call.id}_$j"
                                )
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .fillMaxHeight(heightPercent)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Play Voice",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp).padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = "${duration}\"",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                McpCallItem(mcpCall = call, modifier = modifier)
            }
        }
        McpStatus.FAILED -> {
            McpCallItem(mcpCall = call, modifier = modifier)
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
