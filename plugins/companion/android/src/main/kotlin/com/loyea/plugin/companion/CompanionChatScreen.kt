package com.loyea.plugin.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.chat.ChatViewModel
import com.loyea.ui.chat.Message
import com.loyea.ui.chat.MessageContentWithPanels
import com.loyea.ui.chat.Sender
import com.loyea.ui.chat.rememberLocalImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S-02 唯一聊天页（陪伴模式）。
 *
 * 布局自上而下：品牌头（琥珀呼吸点 + L O Y E A + ⋮ 更多）→ 神经元形态舞台
 * （docs/Loyea-Neural-Companion-v2.html 中间图形完整移植，倾听/思考节律随交互切换）→
 * 消息时间线（玻璃拟态气泡）→ 玻璃输入条。无抽屉/会话列表/角色卡/世界书入口（NAV-01/UI-04）。
 */
@Composable
fun CompanionChatScreen(
    viewModel: ChatViewModel,
    config: CompanionConfig,
    focusMessageId: String? = null,
    onFocusConsumed: () -> Unit = {},
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = viewModel.messages.value
    val isThinking = viewModel.isThinking.value
    val sessionId = config.sessionId
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var input by remember(sessionId) { mutableStateOf(TextFieldValue(viewModel.getDraft(sessionId))) }
    var inputFocused by remember { mutableStateOf(false) }
    var highlightedId by remember { mutableStateOf<String?>(null) }
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    // R-09 录音状态修复：以 ViewModel 的真实录音状态为唯一来源——权限被拒/初始化失败时
    // isRecording 不会变真，UI 不再出现"正在聆听"假象
    val recording = viewModel.isRecording.value
    // 图片待发送预览（S-02 临时内容）：沿用宿主 vision 缓存拷贝语义
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
    // R-09：图片全屏预览
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickMediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val file = java.io.File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                pendingImagePath = file.absolutePath
            }
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 形态节律（Web 版 targetActivity）：思考 1.65 / 倾听 1.05 / 静静陪伴 0.65
    val targetActivity = when {
        isThinking -> NeuralLivingScene.ACTIVITY_THINK
        inputFocused -> NeuralLivingScene.ACTIVITY_LISTEN
        else -> NeuralLivingScene.ACTIVITY_REST
    }

    // R-09 未读/回到底部：远离底部期间到达的新消息计数，点击一键回底
    var missedCount by remember { mutableStateOf(0) }
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) missedCount = 0 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        CompanionHeader(displayName = config.displayName, onOpenMore = onOpenMore)
        // R-09 高度适配：竖屏全高 292dp；小屏幕（横屏+键盘等）收缩，保住聊天与输入可达性
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stageHeight = if (maxHeight < 480.dp) 176.dp else 292.dp
            NeuralLivingStage(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stageHeight),
                targetActivity = targetActivity
            )
        }
        // 任务状态（S-02 顶栏下方：仅有任务执行时显示，结束消失）
        Text(
            text = if (isThinking) "正在回应…" else "",
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = CompanionPalette.Presence,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isThinking) 18.dp else 0.dp)
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                // UI-09 空会话占位：不是历史消息、不调用模型，第一条消息发出后消失
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "●  陪在你身边",
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        color = CompanionPalette.Presence
                    )
                    Text(
                        "我在，慢慢说。",
                        fontSize = 18.sp,
                        lineHeight = 29.sp,
                        color = CompanionPalette.Brand,
                        modifier = Modifier.padding(top = 16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        CompanionMessageBubble(
                            message = message,
                            appLanguage = viewModel.appLanguage.value,
                            highlight = highlightedId == message.id,
                            onLongPress = { selectedMessage = message },
                            onImageTap = { previewImagePath = it },
                            onAudioPlay = { m ->
                                m.audioUrl?.let { viewModel.playAudioUrl(m.id, it) }
                            }
                        )
                    }
                }
            }
            // R-09：回到底部/未读指示（AC-12）
            if (!atBottom && messages.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .glassSurface(
                            shape = CircleShape,
                            fill = CompanionPalette.RoundBtnBg,
                            border = CompanionPalette.RoundBtnBorder
                        )
                        .clickable {
                            missedCount = 0
                            scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "回到底部",
                        tint = CompanionPalette.RoundBtnIcon,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        if (missedCount > 0) "$missedCount 条新消息" else "回到底部",
                        fontSize = 12.sp,
                        color = CompanionPalette.RoundBtnIcon
                    )
                }
            }
        }

        CompanionInputBar(
            value = input,
            isThinking = isThinking,
            recording = recording,
            onMicPress = {
                if (isThinking || recording) return@CompanionInputBar
                if (!viewModel.enableStt.value) {
                    android.widget.Toast.makeText(
                        context, "语音输入功能在设置中已被关闭", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@CompanionInputBar
                }
                viewModel.startRecording()
            },
            onMicRelease = {
                if (!recording) return@CompanionInputBar
                viewModel.stopRecording { file, duration ->
                    if (file == null) return@stopRecording
                    if (duration < 1) {
                        file.delete()
                        android.widget.Toast.makeText(context, "说话时间太短", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.transcribeAndSendAudio(file, duration) { err ->
                            android.widget.Toast.makeText(context, "语音识别失败：$err", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onCancelRecording = {
                viewModel.stopRecording { file, _ ->
                    file?.delete()
                    android.widget.Toast.makeText(context, "录音已取消", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            pendingImagePath = pendingImagePath,
            onClearPendingImage = { pendingImagePath = null },
            onPickImage = {
                pickMediaLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onValueChange = {
                input = it
                viewModel.saveDraft(sessionId, it.text) // 键盘弹出/预览不丢草稿（UI-11）
            },
            onFocusedChange = { inputFocused = it },
            onSend = {
                val text = input.text.trim()
                if (text.isNotEmpty() || pendingImagePath != null) {
                    viewModel.sendMessage(text, imageUrl = pendingImagePath)
                    input = TextFieldValue("")
                    pendingImagePath = null
                    viewModel.clearDraft(sessionId)
                    // 发送后强制跟随到底部（用户主动行为必然想看回复；
                    // 键盘压缩视口时位置判断不可靠）
                    scope.launch { listState.animateScrollToItem(messages.size) }
                }
            },
            onStop = { viewModel.stopResponse() }
        )
        Spacer(Modifier.navigationBarsPadding())
    }

    // UI-07：长按消息操作区（复制/朗读/重新生成），遮罩点击关闭
    selectedMessage?.let { selected ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x88000000))
                .clickable { selectedMessage = null }
        ) {
            CompanionMessageActionsSheet(
                modifier = Modifier.align(Alignment.BottomCenter),
                message = selected,
                canRegenerate = !isThinking
                        && messages.indexOf(selected) == messages.indexOfLast { it.sender == Sender.AI }
                        && selected.content.isNotBlank(),
                onDismiss = { selectedMessage = null },
                onCopy = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(selected.content))
                    selectedMessage = null
                },
                onSpeak = {
                    viewModel.playTts(selected.id, selected.content)
                    selectedMessage = null
                },
                onRegenerate = {
                    selectedMessage = null
                    viewModel.regenerateLastReply()
                }
            )
        }
    }

    // R-09：图片全屏预览（点击关闭）
    previewImagePath?.let { path ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xEE000000))
                .clickable { previewImagePath = null },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = rememberLocalImagePainter(path)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "全屏图片，点击关闭",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("图片不存在或已被清理", fontSize = 14.sp, color = CompanionPalette.Hint)
            }
        }
    }

    // 新消息追加且当前贴近底部时跟随；翻阅历史时不抢滚动、只累计未读（AC-12）
    LaunchedEffect(messages.size, isThinking) {
        if (messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= messages.size - 2) {
            listState.animateScrollToItem(messages.lastIndex)
        } else {
            missedCount++
        }
    }

    // 查找记录跳转：定位到原消息并短暂高亮（UI-21）
    LaunchedEffect(focusMessageId) {
        val id = focusMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            highlightedId = id
            delay(1600)
            highlightedId = null
        }
        onFocusConsumed()
    }
}

@Composable
private fun CompanionHeader(displayName: String, onOpenMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 呼吸琥珀点（Web 版 lv-dot，含微光）
        Box(
            Modifier
                .size(12.dp)
                .background(CompanionPalette.AmberDot.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(6.dp).background(CompanionPalette.AmberDot, CircleShape))
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                "L O Y E A",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = CompanionPalette.Brand,
                letterSpacing = 2.sp
            )
            Text(
                displayName.ifBlank { "Loyea" } + " · 陪伴模式",
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = CompanionPalette.BrandMuted,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        // ⋮ 更多面板入口（S-03）；48dp 触控区域
        Box(
            Modifier
                .size(44.dp)
                .glassSurface(
                    shape = CircleShape,
                    fill = CompanionPalette.RoundBtnBg,
                    border = CompanionPalette.RoundBtnBorder,
                    sheenAlpha = 0.04f
                )
                .clickable(onClick = onOpenMore),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = "更多",
                tint = CompanionPalette.RoundBtnIcon
            )
        }
    }
}

/** 玻璃拟态气泡：AI 左侧冷玻璃、用户右侧暖琥珀玻璃；正文复用宿主 Markdown/面板渲染。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CompanionMessageBubble(
    message: Message,
    appLanguage: String,
    highlight: Boolean = false,
    onLongPress: () -> Unit = {},
    onImageTap: (String) -> Unit = {},
    onAudioPlay: (Message) -> Unit = {},
) {
    val isUser = message.sender == Sender.USER
    val isError = message.isError
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 330.dp)
        ) {
            Box(
                Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress // UI-07：长按呼出操作区
                    )
                    .glassSurface(
                        shape = glassBubbleShape(isUser),
                        fill = when {
                            highlight -> Color(0x3DE7C28A)
                            isUser -> CompanionPalette.BubbleUserFill
                            else -> CompanionPalette.BubbleAiFill
                        },
                        border = when {
                            highlight -> Color(0xFFE7C28A)
                            isUser -> CompanionPalette.BubbleUserBorder
                            else -> CompanionPalette.BubbleAiBorder
                        },
                        sheenAlpha = if (isUser) 0.08f else 0.06f
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (!message.imageUrl.isNullOrBlank()) {
                        val bitmap = rememberLocalImagePainter(message.imageUrl)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "图片消息，点击全屏查看",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageTap(message.imageUrl) } // R-09：全屏预览
                            )
                        }
                    } else if (!message.imageDesc.isNullOrBlank()) {
                        // R-05 恢复场景：图片文件不随备份迁移，仅存图注时以文字呈现
                        Text(
                            "[图片｜${message.imageDesc}]",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = CompanionPalette.Hint,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    // R-09：音频消息播放入口
                    if (!message.audioUrl.isNullOrBlank()) {
                        Row(
                            Modifier
                                .clickable { onAudioPlay(message) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.VolumeUp,
                                contentDescription = "播放语音",
                                tint = if (isUser) CompanionPalette.TextOnUser else CompanionPalette.RoundBtnIcon,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "语音消息 " + if (message.audioDuration > 0) "${message.audioDuration}″" else "",
                                fontSize = 13.sp,
                                color = if (isUser) CompanionPalette.TextOnUser else CompanionPalette.TextPrimary
                            )
                        }
                    }
                    if (message.content.isBlank() && message.isStillThinking) {
                        Text("……", fontSize = 16.sp, color = CompanionPalette.Presence)
                    } else if (message.content.isNotBlank()) {
                        MessageContentWithPanels(
                            raw = message.content,
                            collapseKeyPrefix = "companion_${message.id}",
                            color = when {
                                isError -> Color(0xFFFF9C85)
                                isUser -> CompanionPalette.TextOnUser
                                else -> CompanionPalette.TextPrimary
                            },
                            appLanguage = appLanguage
                        )
                    }
                }
            }
        }
    }
}

/** 玻璃拟态输入条 + 图片附件 + 语音录入 + 琥珀渐变发送/停止钮（发送中防重入，单轮串行）。 */
@Composable
private fun CompanionInputBar(
    value: TextFieldValue,
    isThinking: Boolean,
    recording: Boolean,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    onCancelRecording: () -> Unit,
    pendingImagePath: String?,
    onClearPendingImage: () -> Unit,
    onPickImage: () -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusedChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusedChange(focused) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // 图片待发送预览（就近出现，不挤占永久功能区）
        if (pendingImagePath != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val preview = rememberLocalImagePainter(pendingImagePath)
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "待发送图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text("图片已就绪", fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.weight(1f))
                Text(
                    "移除",
                    fontSize = 13.sp,
                    color = CompanionPalette.Presence,
                    modifier = Modifier.clickable(onClick = onClearPendingImage)
                )
            }
        }
        // 录音中：状态条 + 取消入口
        if (recording) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(14.dp),
                        fill = CompanionPalette.BubbleUserFill,
                        border = CompanionPalette.BubbleUserBorder
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("● 正在聆听…松开即转写发送", fontSize = 13.sp, color = CompanionPalette.TextOnUser, modifier = Modifier.weight(1f))
                Text(
                    "取消",
                    fontSize = 13.sp,
                    color = CompanionPalette.Presence,
                    modifier = Modifier.clickable(onClick = onCancelRecording)
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            // 语音录入：按住开始，松开转写并发送（AC-10）
            Box(
                Modifier
                    .size(44.dp)
                    .glassSurface(
                        shape = CircleShape,
                        fill = if (recording) CompanionPalette.BubbleUserFill else CompanionPalette.GlassFill,
                        border = if (recording) CompanionPalette.BubbleUserBorder else CompanionPalette.GlassBorder
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onMicPress()
                                tryAwaitRelease()
                                onMicRelease()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = "按住说话",
                    tint = if (recording) CompanionPalette.Presence else CompanionPalette.RoundBtnIcon,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.size(8.dp))
            // 附件按钮
            Box(
                Modifier
                    .size(44.dp)
                    .glassSurface(
                        shape = CircleShape,
                        fill = CompanionPalette.GlassFill,
                        border = CompanionPalette.GlassBorder,
                        sheenAlpha = 0.04f
                    )
                    .clickable(onClick = onPickImage),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Photo,
                    contentDescription = "发送图片",
                    tint = CompanionPalette.RoundBtnIcon,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.size(8.dp))
            val fieldShape = RoundedCornerShape(24.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .glassSurface(
                        shape = fieldShape,
                        fill = if (focused) CompanionPalette.GlassPressedFill else CompanionPalette.GlassFill,
                        border = if (focused) CompanionPalette.BubbleUserBorder else CompanionPalette.GlassBorder,
                        sheenAlpha = 0.05f
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        color = CompanionPalette.TextPrimary
                    ),
                    cursorBrush = SolidColor(CompanionPalette.AmberDot),
                    interactionSource = interaction,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { onSend() }
                    ),
                    maxLines = 5,
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty() && !focused) {
                                Text(
                                    "想聊什么都可以",
                                    fontSize = 16.sp,
                                    color = CompanionPalette.Placeholder
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(CompanionPalette.SendAmberTop, CompanionPalette.SendAmberBottom)
                        )
                    )
                    .clickable(enabled = value.text.isNotBlank() || pendingImagePath != null || isThinking) {
                        if (isThinking) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (isThinking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    contentDescription = if (isThinking) "停止" else "发送",
                    tint = CompanionPalette.SendIcon,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** UI-07：长按消息操作区——复制 / 朗读 / 重新生成（仅最后一条 AI 回复）。 */
@Composable
private fun CompanionMessageActionsSheet(
    message: Message,
    canRegenerate: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xF20E0C09))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Text(
                if (message.sender == Sender.USER) "你说的话" else "Loyea 说",
                fontSize = 12.sp,
                color = CompanionPalette.Hint
            )
            Spacer(Modifier.height(10.dp))
            ActionRowItem(Icons.Rounded.ContentCopy, "复制", onCopy)
            ActionRowItem(Icons.Rounded.VolumeUp, "朗读", onSpeak)
            if (canRegenerate) {
                ActionRowItem(Icons.Rounded.Refresh, "重新生成", onRegenerate)
            }
            ActionRowItem(Icons.Rounded.Close, "取消", onDismiss)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ActionRowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = CompanionPalette.RoundBtnIcon, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(14.dp))
        Text(title, fontSize = 15.sp, color = CompanionPalette.TextPrimary)
    }
}
