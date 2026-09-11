package com.loyea.plugin.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Stop
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

    var input by remember(sessionId) { mutableStateOf(TextFieldValue(viewModel.getDraft(sessionId))) }
    var inputFocused by remember { mutableStateOf(false) }
    var highlightedId by remember { mutableStateOf<String?>(null) }
    // 图片待发送预览（S-02 临时内容）：沿用宿主 vision 缓存拷贝语义
    var pendingImagePath by remember { mutableStateOf<String?>(null) }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        CompanionHeader(displayName = config.displayName, onOpenMore = onOpenMore)
        NeuralLivingStage(
            modifier = Modifier
                .fillMaxWidth()
                .height(292.dp),
            targetActivity = targetActivity
        )
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
                            highlight = highlightedId == message.id
                        )
                    }
                }
            }
        }

        CompanionInputBar(
            value = input,
            isThinking = isThinking,
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

    // 新消息追加且当前贴近底部时跟随；翻阅历史时不抢滚动（AC-12 的基础行为）
    LaunchedEffect(messages.size, isThinking) {
        if (messages.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= messages.size - 2) {
            listState.animateScrollToItem(messages.lastIndex)
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
@Composable
private fun CompanionMessageBubble(message: Message, appLanguage: String, highlight: Boolean = false) {
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
                SelectionContainer {
                    Column {
                        if (!message.imageUrl.isNullOrBlank()) {
                            val bitmap = rememberLocalImagePainter(message.imageUrl)
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "图片消息",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(12.dp))
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
}

/** 玻璃拟态输入条 + 图片附件 + 琥珀渐变发送/停止钮（发送中防重入，单轮串行）。 */
@Composable
private fun CompanionInputBar(
    value: TextFieldValue,
    isThinking: Boolean,
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
        Row(verticalAlignment = Alignment.Bottom) {
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
