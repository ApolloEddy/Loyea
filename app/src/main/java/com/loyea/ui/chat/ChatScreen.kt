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
import com.loyea.ui.theme.ClaudeTheme
import com.loyea.ui.settings.ApiConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    apiConfig: ApiConfig,
    appLanguage: String,
    userBubbleColor: String,
    onApiConfigChange: (ApiConfig) -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val isEn = appLanguage == "en"

    // 默认对话历史 (根据语言环境自适应)
    var messages by remember(appLanguage) {
        mutableStateOf(
            listOf(
                Message(
                    "1", 
                    if (isEn) "Hello! I'm Claude. How can I help you today?" else "你好！我是 Claude。今天我能帮您做点什么？", 
                    Sender.AI
                ),
                Message(
                    "2", 
                    if (isEn) "Can you show me how to write a simple Jetpack Compose layout with `Button`?" else "你能向我展示如何用 `Button` 编写一个简单的 Jetpack Compose 布局吗？", 
                    Sender.USER
                ),
                Message(
                    "3", 
                    if (isEn) {
                        "Sure! Here is a simple layout using a `Button` and `Text`:\n\n```kotlin\n@Composable\nfun MyButtonLayout() {\n    Column(\n        modifier = Modifier.padding(16.dp),\n        horizontalAlignment = Alignment.CenterVertically\n    ) {\n        Text(text = \"Click the button below!\")\n        Spacer(modifier = Modifier.height(8.dp))\n        Button(onClick = { /* Handle Click */ }) {\n            Text(text = \"Click Me\")\n        }\n    }\n}\n```\nLet me know if you need any other modifications!"
                    } else {
                        "当然！下面是一个使用 `Button` 和 `Text` 的简单布局：\n\n```kotlin\n@Composable\nfun MyButtonLayout() {\n    Column(\n        modifier = Modifier.padding(16.dp),\n        horizontalAlignment = Alignment.CenterVertically\n    ) {\n        Text(text = \"点击下方的按钮！\")\n        Spacer(modifier = Modifier.height(8.dp))\n        Button(onClick = { /* 处理点击事件 */ }) {\n            Text(text = \"点击我\")\n        }\n    }\n}\n```\n如果您需要任何其他修改，请告诉我！"
                    }, 
                    Sender.AI
                )
            )
        )
    }

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var isThinking by remember { mutableStateOf(false) }

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
                        selectedModel = apiConfig.modelName,
                        onModelChange = { newModelName ->
                            onApiConfigChange(apiConfig.copy(modelName = newModelName))
                        }
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
                    IconButton(onClick = {
                        messages = listOf(
                            Message(
                                System.currentTimeMillis().toString(), 
                                if (isEn) "Hello! I'm Claude. How can I help you today?" else "你好！我是 Claude。今天我能帮您做点什么？", 
                                Sender.AI
                            )
                        )
                    }) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = "New Chat",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                            messages = messages.map { msg ->
                                if (msg.id == message.id) msg.copy(
                                    isThoughtsExpanded = !msg.isThoughtsExpanded,
                                    hasUserToggledThoughts = true
                                ) else msg
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

            // 底部输入区
            ChatInputBar(
                value = inputText,
                appLanguage = appLanguage,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.text.isNotBlank()) {
                        val userText = inputText.text
                        messages = messages + Message(
                            id = System.currentTimeMillis().toString(),
                            content = userText,
                            sender = Sender.USER
                        )
                        inputText = TextFieldValue("")
                        
                        // 开启多阶段高仿真 AI 推理 + 工具调用流
                        scope.launch {
                            // 阶段 1：显示全局闪烁点，代表大模型正在调度资源
                            isThinking = true
                            delay(800)
                            isThinking = false
                            
                            val aiMessageId = (System.currentTimeMillis() + 1).toString()
                            
                            // 阶段 2：插入一个工具调用 read_file (正在运行状态)
                            val call1 = McpCall(
                                id = "c1", 
                                toolName = "read_file", 
                                actionText = "mcp-server: read_file", 
                                status = McpStatus.RUNNING,
                                input = "file: app/build.gradle.kts"
                            )
                            val initialAiMsg = Message(
                                id = aiMessageId,
                                content = "",
                                sender = Sender.AI,
                                mcpCalls = listOf(call1)
                            )
                            messages = messages + initialAiMsg
                            delay(1800) // 齿轮无限旋转动效维持 1.8 秒

                            // 阶段 3：工具调用成功，并开启思维链进行深度思考 (Thinking)
                            val call1Success = call1.copy(
                                status = McpStatus.SUCCESS,
                                output = "dependencies {\n    implementation(\"androidx.compose.material:material-icons-extended\")\n}"
                            )
                            val thoughtContent = "1. Found dependencies for icons-extended in build.gradle.kts.\n2. The compose framework configuration looks fully stable.\n3. The user wants to integrate MCP and Thinking visual elements.\n4. I should write a detailed explanation and a code snippet to demonstrate."
                            
                            messages = messages.map { msg ->
                                if (msg.id == aiMessageId) msg.copy(
                                    mcpCalls = listOf(call1Success),
                                    thoughts = thoughtContent,
                                    isStillThinking = true,
                                    isThoughtsExpanded = true, // 默认展开思考链，让动画更动感
                                    thoughtDurationSeconds = 1
                                ) else msg
                            }
                            delay(1200) // 思考中维持 1.2 秒

                            // 阶段 4：思考结束，耗时计数增加为 2s，并开启第二个工具 Web Search
                            val call2 = McpCall(
                                id = "c2",
                                toolName = "web_search",
                                actionText = "mcp-server: web_search",
                                status = McpStatus.RUNNING,
                                input = "query: Jetpack Compose AnimatedContent"
                            )
                            messages = messages.map { msg ->
                                if (msg.id == aiMessageId) {
                                    val shouldCollapse = !msg.hasUserToggledThoughts
                                    msg.copy(
                                        isStillThinking = false,
                                        thoughtDurationSeconds = 2,
                                        isThoughtsExpanded = if (shouldCollapse) false else msg.isThoughtsExpanded,
                                        mcpCalls = listOf(call1Success, call2)
                                    )
                                } else msg
                            }
                            delay(1500) // 网页搜索等待 1.5 秒

                            // 阶段 5：第二个工具执行成功，并开始流式打字输出正文
                            val call2Success = call2.copy(
                                status = McpStatus.SUCCESS,
                                output = "Found 3 articles about Compose animations."
                            )
                            messages = messages.map { msg ->
                                if (msg.id == aiMessageId) msg.copy(
                                    mcpCalls = listOf(call1Success, call2Success)
                                ) else msg
                            }

                            val fullResponse = if (isEn) {
                                "Here is how you can use the MCP and Thinking UI components. The tools have executed successfully!\n\nCheck the thoughts box above to see my exact reasoning process. I used the `read_file` tool to inspect dependencies and `web_search` to verify documentation.\n\n```kotlin\n// The tools run smoothly!\nMcpStatus.SUCCESS -> \"Gear stops and green check displays\"\n```\nAll animations run dynamically!"
                            } else {
                                "这就是您可以使用 MCP 和 Thinking UI 组件的方法。工具已成功执行！\n\n检查上方的思考框以查看我确切的推理过程。我使用了 `read_file` 工具检查依赖，使用 `web_search` 验证了文档。\n\n```kotlin\n// 工具运行顺畅！\nMcpStatus.SUCCESS -> \"齿轮停止并显示绿色对勾\"\n```\n所有动画都在动态运行！"
                            }
                            
                            var currentContent = ""
                            fullResponse.forEach { char ->
                                currentContent += char
                                messages = messages.map { msg ->
                                    if (msg.id == aiMessageId) msg.copy(content = currentContent) else msg
                                }
                                delay(12) // 逐字输出打字速度优化
                            }
                        }
                    }
                },
                onAttach = {
                    Toast.makeText(context, "Attachment clicked", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

// 1:1 复刻 Claude 顶部模型选择胶囊
@Composable
fun ModelSelector(
    selectedModel: String,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val models = remember(selectedModel) {
        val defaults = listOf(
            "claude-3-5-sonnet", 
            "gpt-4o", 
            "deepseek-chat", 
            "moonshot-v1-8k", 
            "qwen-turbo", 
            "abab6.5-chat", 
            "mimo-v1"
        )
        if (selectedModel in defaults) {
            defaults
        } else {
            listOf(selectedModel) + defaults.filter { it != selectedModel }
        }
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = selectedModel,
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select Model",
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, color = MaterialTheme.colorScheme.onBackground) },
                    onClick = {
                        onModelChange(model)
                        expanded = false
                    }
                )
            }
        }
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
                        text = if (isEn) "Talk to Claude" else "与 Claude 对话",
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
    ClaudeTheme {
        ChatScreen(
            apiConfig = ApiConfig(),
            appLanguage = "zh",
            userBubbleColor = "",
            onApiConfigChange = {},
            onMenuClick = {}
        )
    }
}
