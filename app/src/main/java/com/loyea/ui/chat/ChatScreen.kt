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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.combinedClickable


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
    showMenuIcon: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val isEn = appLanguage == "en"
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var recordDragState by remember { mutableStateOf("IDLE") }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    if (showMenuIcon) {
                        IconButton(onClick = onMenuClick) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
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
                        currentlyPlayingAudioProgress = viewModel?.currentlyPlayingAudioProgress?.value ?: 0f,
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
                            if (!message.audioUrl.isNullOrBlank()) {
                                viewModel?.playAudioUrl(message.id, message.audioUrl)
                            } else {
                                viewModel?.playTts(message.id, message.content)
                            }
                        },
                        onMcpVoicePlay = { mcpCallId ->
                            viewModel?.playMcpVoice(mcpCallId)
                        },
                        onImageClick = { path ->
                            lightboxImagePath = path
                        },
                        onTranscribe = { msg ->
                            val audioPath = msg.audioUrl
                            if (!audioPath.isNullOrBlank() && viewModel != null) {
                                val audioFile = File(audioPath)
                                if (audioFile.exists()) {
                                    Toast.makeText(context, "正在重新转写语音...", Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        val transcribedText = viewModel.transcribeAudio(audioFile)
                                        if (transcribedText != null) {
                                            viewModel.updateMessageContent(msg.id, transcribedText)
                                            Toast.makeText(context, "转写成功", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val rawError = viewModel.lastAsrError ?: ""
                                            val formatted = when {
                                                rawError.contains("429") -> "服务商额度已耗尽或被限流 (HTTP 429)，请检查余额"
                                                rawError.contains("401") -> "API Key 无效或过期 (HTTP 401)"
                                                rawError.contains("400") -> "接口参数错误 (HTTP 400)，请确保模型名称可用"
                                                rawError.isNotBlank() -> rawError
                                                else -> "请稍后重试"
                                            }
                                            Toast.makeText(context, "转写失败：$formatted", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "语音文件已丢失，无法转写", Toast.LENGTH_SHORT).show()
                                }
                            }
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
                                            viewModel.transcribeAndSendAudio(file, duration) { errorMsg ->
                                                val formatted = when {
                                                    errorMsg.contains("429") -> "服务商额度已耗尽或被限流 (HTTP 429)，请检查余额"
                                                    errorMsg.contains("401") -> "API Key 无效或过期 (HTTP 401)"
                                                    errorMsg.contains("400") -> "接口参数错误 (HTTP 400)，请确保模型名称可用"
                                                    else -> errorMsg
                                                }
                                                Toast.makeText(context, if (isEn) "Speech recognition failed: $formatted" else "语音识别失败：$formatted", Toast.LENGTH_LONG).show()
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
                onRecordStart = {
                    val isSttEnabled = viewModel?.enableStt?.value ?: true
                    if (isSttEnabled && viewModel != null) {
                        recordDragState = "RECORDING"
                        viewModel.startRecording()
                    } else {
                        Toast.makeText(context, if (isEn) "STT is disabled in settings" else "语音输入功能在设置中已被关闭", Toast.LENGTH_SHORT).show()
                    }
                },
                onRecordDrag = { x, y ->
                    dragOffsetX = x
                    dragOffsetY = y
                    if (y < -150f) {
                        if (recordDragState != "CANCEL") {
                            recordDragState = "CANCEL"
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else if (x > 120f && y < -50f) {
                        if (recordDragState != "TRANSCRIBE") {
                            recordDragState = "TRANSCRIBE"
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        if (recordDragState != "RECORDING") {
                            recordDragState = "RECORDING"
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                onRecordEnd = { _, _ ->
                    if (viewModel != null) {
                        val finalState = recordDragState
                        recordDragState = "IDLE"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        
                        when (finalState) {
                            "CANCEL" -> {
                                viewModel.stopRecording { file, _ ->
                                    if (file != null) {
                                        try { file.delete() } catch (e: Exception) {}
                                    }
                                    Toast.makeText(context, "录音已取消", Toast.LENGTH_SHORT).show()
                                }
                            }
                            "TRANSCRIBE" -> {
                                Toast.makeText(context, "正在转写并发送...", Toast.LENGTH_SHORT).show()
                                viewModel.stopRecording { file, _ ->
                                    if (file != null) {
                                        coroutineScope.launch {
                                            val text = viewModel.transcribeAudio(file)
                                            if (!text.isNullOrBlank()) {
                                                onSendMessage(text, null, file.absolutePath, 0)
                                            } else {
                                                val errorReason = viewModel.lastAsrError ?: ""
                                                val formatted = when {
                                                    errorReason.contains("429") -> "服务商额度已耗尽或被限流 (HTTP 429)，请检查余额"
                                                    errorReason.contains("401") -> "API Key 无效或过期 (HTTP 401)"
                                                    errorReason.contains("400") -> "接口参数错误 (HTTP 400)，请确保模型名称可用"
                                                    errorReason.isNotBlank() -> errorReason
                                                    else -> "未提取到有效文字"
                                                }
                                                Toast.makeText(context, "转写失败：$formatted", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                viewModel.stopRecording { file, duration ->
                                    if (file != null) {
                                        viewModel.transcribeAndSendAudio(file, duration) { errorMsg ->
                                            Toast.makeText(context, if (isEn) "Speech recognition failed: $errorMsg" else "语音识别失败：$errorMsg", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                haptic = haptic,
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
    } // 闭合 Scaffold
    
    if (viewModel?.isRecording?.value == true) {
        RecordingOverlay(
            dragState = recordDragState,
            amplitude = viewModel.recordingAmplitude.value
        )
    }
} // 闭合 Box
} // 闭合 ChatScreen

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: Message,
    userBubbleColor: String,
    currentlyPlayingAudioId: String?,
    currentlyPlayingAudioProgress: Float = 0f,
    onCopy: () -> Unit,
    onToggleThoughts: () -> Unit,
    onEdit: (String) -> Unit,
    onSpeak: () -> Unit,
    onMcpVoicePlay: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onTranscribe: (Message) -> Unit
) {
    val isUser = message.sender == Sender.USER

    var showTranscribedText by remember { mutableStateOf(false) }
    var lastContent by remember { mutableStateOf(message.content) }
    if (message.content != lastContent) {
        if (lastContent.isBlank() && message.content.isNotBlank()) {
            showTranscribedText = true
        }
        lastContent = message.content
    }

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

            // 2. 用户语音条展示 (带播放中三柱实时波形动效，并支持点击播放原声，双击/长按/点击折叠展开转文字)
            // 2. 用户语音条展示 (带播放中音轨进度与平滑正弦波跳动动效，并支持点击播放/暂停，双击/长按/点击译字图标折叠展开转文字)
            if (!message.audioUrl.isNullOrBlank()) {
                val durationRatio = (message.audioDuration.toFloat() / 60f).coerceIn(0f, 1f)
                val bubbleWidth = 140.dp + 100.dp * durationRatio
                val baseModifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                    .background(bubbleBgColor ?: MaterialTheme.colorScheme.secondaryContainer)
                    .animateContentSize()
                    .combinedClickable(
                        onClick = { onSpeak() },
                        onDoubleClick = {
                            if (message.content.isBlank()) {
                                onTranscribe(message)
                            } else {
                                showTranscribedText = !showTranscribedText
                            }
                        },
                        onLongClick = {
                            if (message.content.isBlank()) {
                                onTranscribe(message)
                            } else {
                                showTranscribedText = !showTranscribedText
                            }
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                val bubbleModifier = if (!showTranscribedText || message.content.isBlank()) {
                    baseModifier.width(bubbleWidth)
                } else {
                    baseModifier.widthIn(min = bubbleWidth).fillMaxWidth(0.85f)
                }
                Column(
                    modifier = bubbleModifier
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 播放/暂停控制按钮
                        val isPlaying = message.isAudioPlaying && currentlyPlayingAudioId == message.id
                        val playIcon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(bubbleTextColor.copy(alpha = 0.15f))
                                .clickable { onSpeak() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = playIcon,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = bubbleTextColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 音轨进度与正弦波形
                        val progress = if (isPlaying) currentlyPlayingAudioProgress else 0f
                        VoicePlayTrack(
                            messageId = message.id,
                            isPlaying = isPlaying,
                            progress = progress,
                            tintColor = bubbleTextColor,
                            modifier = Modifier.weight(1f)
                        )

                        // 时长与极简转写按钮
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${message.audioDuration}\"",
                                color = bubbleTextColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )

                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(bubbleTextColor.copy(alpha = 0.08f))
                                    .clickable {
                                        if (message.content.isBlank()) {
                                            onTranscribe(message)
                                        } else {
                                            showTranscribedText = !showTranscribedText
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = "Transcribe",
                                    tint = bubbleTextColor.copy(alpha = 0.8f),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                    
                    if (showTranscribedText && message.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        androidx.compose.material3.HorizontalDivider(
                            color = bubbleTextColor.copy(alpha = 0.15f),
                            thickness = 0.5.dp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message.content,
                            color = bubbleTextColor.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. 用户文本展示 (仅在无语音时展示，防止双气泡)
            if (message.content.isNotBlank() && message.audioUrl.isNullOrBlank()) {
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
                            if (!isVoiceReply) {
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
                    val durationRatio = (message.audioDuration.toFloat() / 60f).coerceIn(0f, 1f)
                    val bubbleWidth = 140.dp + 100.dp * durationRatio
                    Row(
                        modifier = Modifier
                            .width(bubbleWidth)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            .clickable { onSpeak() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isPlaying = message.isAudioPlaying && currentlyPlayingAudioId == message.id
                        val playIcon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = playIcon,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        val progress = if (isPlaying) currentlyPlayingAudioProgress else 0f
                        VoicePlayTrack(
                            messageId = message.id,
                            isPlaying = isPlaying,
                            progress = progress,
                            tintColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = "${message.audioDuration}\"",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else if (message.isAudioSynthesizing) {
                    // 正在合成中的占位长条
                    val durationRatio = (message.audioDuration.toFloat() / 60f).coerceIn(0f, 1f)
                    val bubbleWidth = 80.dp + 160.dp * durationRatio
                    Row(
                        modifier = Modifier
                            .width(bubbleWidth)
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

                // AI 虚拟工具语音回复条展示 (McpVoiceReplyItem 统一收拢在底端渲染，防止位置乱跑)
                val voiceCalls = message.mcpCalls.filter { call ->
                    call.toolName.equals("send_voice_reply", ignoreCase = true) || 
                    call.toolName.endsWith("__send_voice_reply", ignoreCase = true) || 
                    call.toolName.endsWith(".send_voice_reply", ignoreCase = true)
                }
                if (voiceCalls.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        voiceCalls.forEach { call ->
                            McpVoiceReplyItem(
                                call = call,
                                currentlyPlayingAudioId = currentlyPlayingAudioId,
                                currentlyPlayingAudioProgress = currentlyPlayingAudioProgress,
                                onPlayClick = onMcpVoicePlay
                            )
                        }
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

// 1:1 复刻 Claude 输入框 (集成微信式语音录制交互及震动反馈)
@Composable
fun ChatInputBar(
    value: TextFieldValue,
    appLanguage: String,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRecordStart: () -> Unit,
    onRecordDrag: (Float, Float) -> Unit,
    onRecordEnd: (Boolean, Boolean) -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    isThinking: Boolean = false,
    isMcpRunning: Boolean = false,
    characterName: String = "Loyea"
) {
    val isTextEmpty = value.text.isBlank()
    val isEn = appLanguage == "en"
    val isActive = isThinking || isMcpRunning
    var isVoiceMode by remember { mutableStateOf(false) }

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

            // 键盘/语音切换按钮
            IconButton(
                onClick = { isVoiceMode = !isVoiceMode },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isVoiceMode) Icons.Default.Keyboard else Icons.Outlined.Mic,
                    contentDescription = "Switch Mode",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }

            if (isVoiceMode) {
                var isPressed by remember { mutableStateOf(false) }
                val buttonBg by animateColorAsState(
                    targetValue = if (isPressed) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                                  else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                    label = "VoiceButtonBg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(buttonBg)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    isPressed = true
                                    onRecordStart()
                                    
                                    var pointerId = down.id
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pointerChange = event.changes.find { it.id == pointerId }
                                        if (pointerChange == null || !pointerChange.pressed) {
                                            isPressed = false
                                            onRecordEnd(false, false)
                                            break
                                        }
                                        
                                        val position = pointerChange.position
                                        val downPosition = down.position
                                        val offsetX = position.x - downPosition.x
                                        val offsetY = position.y - downPosition.y
                                        
                                        onRecordDrag(offsetX, offsetY)
                                        pointerChange.consume()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPressed) "松开 发送" else "按住 说话",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            } else {
                // 自定义 BasicTextField
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
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
        }

        val showRightButton = isActive || (!isVoiceMode && !isTextEmpty)
        if (showRightButton) {
            Spacer(modifier = Modifier.width(8.dp))

            val buttonColor by animateColorAsState(
                targetValue = if (isActive) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.primary
                },
                animationSpec = tween(300),
                label = "ButtonColor"
            )

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .clickable { 
                        if (isActive) {
                            onStop()
                        } else {
                            onSend()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = if (isActive) "stop" else "send",
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
                            modifier = Modifier.size(22.dp)
                        )
                        else -> Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
    currentlyPlayingAudioProgress: Float = 0f,
    onPlayClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPlaying = currentlyPlayingAudioId == call.id
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    val cleanedText = remember(call.input) { cleanVoiceText(call.input) }
    var isTextExpanded by remember { mutableStateOf(false) }

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
                val isDark = androidx.compose.foundation.isSystemInDarkTheme()
                val claudeCardBg = if (isDark) Color(0xFF1E1E1C) else Color(0xFFFBFBFA)
                val claudeCardBorder = if (isDark) Color(0xFF323230) else Color(0xFFECEAE2)
                val claudeText = if (isDark) Color(0xFFE4E2DC) else Color(0xFF2A2926)
                val claudeSubText = if (isDark) Color(0xFF9E9C95) else Color(0xFF7D7C75)
                val claudePrimary = if (isDark) Color(0xFFD6CFC7) else Color(0xFF8C7A6B) // 沙色
                val claudePrimaryBg = if (isDark) Color(0xFF2B2925) else Color(0xFFF4EFEA) // 温暖沙色背景

                // 旋转动画控制展开箭头
                val arrowRotation by animateFloatAsState(
                    targetValue = if (isTextExpanded) 180f else 0f,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    label = "arrowRotation_${call.id}"
                )

                // 宽度渐变动画：未展开时占 AI 气泡的 65%，展开时平滑拉宽至 100% 占满
                val widthFraction by animateFloatAsState(
                    targetValue = if (isTextExpanded) 1.0f else 0.65f,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "widthFraction_${call.id}"
                )

                // 背景色平滑渐变：未展开时为浅沙色，展开后为极简卡片白色/深灰背景
                val backgroundColor by animateColorAsState(
                    targetValue = if (isTextExpanded) claudeCardBg else claudePrimaryBg,
                    animationSpec = tween(durationMillis = 300),
                    label = "bgColor_${call.id}"
                )

                Column(
                    modifier = modifier
                        .fillMaxWidth(widthFraction)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(backgroundColor)
                        .border(1.dp, claudeCardBorder, RoundedCornerShape(14.dp))
                        .animateContentSize(animationSpec = tween(300, easing = FastOutSlowInEasing))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayClick(call.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val playIcon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(claudePrimary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = playIcon,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = claudePrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        val progress = if (isPlaying) currentlyPlayingAudioProgress else 0f
                        VoicePlayTrack(
                            messageId = call.id,
                            isPlaying = isPlaying,
                            progress = progress,
                            tintColor = claudePrimary,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = "${duration}\"",
                            color = claudePrimary,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        )

                        if (cleanedText.isNotEmpty()) {
                            IconButton(
                                onClick = { isTextExpanded = !isTextExpanded },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isTextExpanded) "折叠文本" else "展开文本",
                                    tint = claudeSubText,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer(rotationZ = arrowRotation)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isTextExpanded && cleanedText.isNotEmpty(),
                        enter = expandVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(200))
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(claudeCardBorder.copy(alpha = 0.8f))
                                    .padding(horizontal = 16.dp)
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(claudePrimaryBg.copy(alpha = 0.2f))
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .fillMaxHeight()
                                            .padding(vertical = 2.dp)
                                            .clip(CircleShape)
                                            .background(claudePrimary.copy(alpha = 0.4f))
                                    )
                                    
                                    Spacer(modifier = Modifier.width(10.dp))
                                    
                                    Text(
                                        text = cleanedText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            lineHeight = 22.sp,
                                            letterSpacing = 0.3.sp
                                        ),
                                        color = claudeText,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .border(0.5.dp, claudePrimary.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                            .background(claudePrimaryBg.copy(alpha = 0.6f))
                                            .clickable {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(cleanedText))
                                                android.widget.Toast.makeText(context, "已复制语音文本", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            tint = claudePrimary,
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            text = "复制",
                                            color = claudePrimary,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 如果是 SUCCESS 状态但还没有获取到音频路径，
                // 渲染为优雅的“语音加载中...”占位态，避免退回到普通的调试用 McpCallItem 绿色卡片造成界面闪烁。
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
                        text = "语音加载中...",
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        McpStatus.FAILED -> {
            McpCallItem(mcpCall = call, modifier = modifier)
        }
    }
}

private fun cleanVoiceText(inputJson: String?): String {
    if (inputJson.isNullOrBlank()) return ""
    val text = try {
        val regex = Regex("""\"text\"\s*:\s*\"([\s\S]*?)\"""")
        val match = regex.find(inputJson)
        match?.groupValues?.get(1) ?: ""
    } catch (e: Exception) {
        ""
    }
    
    if (text.isBlank()) return ""
    
    // 净化Style语气标签和吸气等呼吸音标签 (支持小括号、中括号、大括号、尖括号)
    var result = text.replace(Regex("\\([\\s\\S]*?\\)"), "")
    result = result.replace(Regex("（[\\s\\S]*?）"), "")
    result = result.replace(Regex("\\[[\\s\\S]*?\\]"), "")
    result = result.replace(Regex("【[\\s\\S]*?】"), "")
    result = result.replace(Regex("\\{[\\s\\S]*?\\}"), "")
    result = result.replace(Regex("<[\\s\\S]*?>"), "")
    
    // 还原JSON转义
    result = result.replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "    ")
        .replace("\\\\", "\\")
        
    return result.trim()
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
fun RecordingOverlay(
    dragState: String,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center
    ) {
        val overlayBgColor = if (dragState == "CANCEL") {
            Color(0xFF8B2626).copy(alpha = 0.85f)
        } else {
            Color(0xFF2C2C2C).copy(alpha = 0.85f)
        }
        
        Column(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(overlayBgColor)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (dragState) {
                "CANCEL" -> {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Cancel",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "松开手指 取消发送",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
                "TRANSCRIBE" -> {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = "Transcribe",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "松开手指 转写发送",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
                else -> { // RECORDING
                    Row(
                        modifier = Modifier.height(48.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ampRatio = (amplitude / 32767f).coerceIn(0f, 1f)
                        for (i in 0 until 7) {
                            val infiniteTransition = rememberInfiniteTransition(label = "amplitudeBar_$i")
                            val randomFactor by infiniteTransition.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(250 + i * 50, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "RandomFactor_$i"
                            )
                            val heightRatio = (ampRatio * 0.7f + 0.3f) * randomFactor
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight(heightRatio.coerceIn(0.15f, 1.0f))
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "手指上滑取消，右滑转文字",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Default
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun VoicePlayTrack(
    messageId: String,
    isPlaying: Boolean,
    progress: Float,
    tintColor: Color,
    modifier: Modifier = Modifier
) {
    val count = 15
    val heights = remember(messageId) {
        val random = java.util.Random(messageId.hashCode().toLong())
        List(count) {
            0.15f + random.nextFloat() * 0.85f
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "voice_wave_$messageId")
    val waveAnimValue by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_anim"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Row(
        modifier = modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEachIndexed { index, baseHeight ->
            val fraction = index.toFloat() / count.toFloat()
            val isHighlighted = progress >= fraction

            val bounceFactor = if (isPlaying) {
                0.7f + 0.3f * kotlin.math.sin(waveAnimValue + index * 0.45f)
            } else {
                1.0f
            }

            val finalHeightPercent = (baseHeight * bounceFactor).coerceIn(0.1f, 1.0f)
            val barColor = if (isHighlighted) {
                tintColor
            } else {
                tintColor.copy(alpha = 0.25f)
            }

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(finalHeightPercent)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(barColor)
            )
        }
    }
}
