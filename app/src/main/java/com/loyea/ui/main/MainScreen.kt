package com.loyea.ui.main

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.loyea.ui.chat.ChatScreen
import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.Message
import com.loyea.ui.settings.ApiConfig
import com.loyea.ui.theme.LoyeaTheme
import kotlinx.coroutines.launch
import java.util.Calendar
import android.text.format.DateUtils

@Composable
fun MainScreen(
    userName: String,
    apiConfig: ApiConfig,
    apiConfigList: List<ApiConfig>,
    onActiveConfigChange: (String) -> Unit,
    appLanguage: String,
    userBubbleColor: String,
    sessions: List<ChatSession>,
    currentSessionId: String,
    messages: List<Message>,
    onMessagesChange: (List<Message>) -> Unit,
    onSessionSelect: (String) -> Unit,
    onSessionDelete: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var lastMenuClickTime by remember { mutableStateOf(0L) }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.width(300.dp)
                ) {
                    SidebarContent(
                        userName = userName,
                        appLanguage = appLanguage,
                        sessions = sessions,
                        currentSessionId = currentSessionId,
                        onHistoryItemClick = { sessionId ->
                            scope.launch { drawerState.close() }
                            onSessionSelect(sessionId)
                        },
                        onSessionDelete = onSessionDelete,
                        onSettingsClick = {
                            scope.launch {
                                drawerState.close()
                                onNavigateToSettings()
                            }
                        }
                    )
                }
            }
        ) {
            ChatScreen(
                apiConfig = apiConfig,
                apiConfigList = apiConfigList,
                onActiveConfigChange = onActiveConfigChange,
                appLanguage = appLanguage,
                userBubbleColor = userBubbleColor,
                messages = messages,
                onMessagesChange = onMessagesChange,
                onNewChatClick = onNewChatClick,
                onMenuClick = {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastMenuClickTime > 800L) {
                        lastMenuClickTime = currentTime
                        scope.launch {
                            try {
                                drawerState.open()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                },
                modifier = modifier
            )
        }

        // 核心交互优化：在侧栏滑出（打开）或缩回（关闭）动画运行期间，在最上层覆盖透明拦截层，
        // 拦截并消费一切快速点击，彻底杜绝连击事件落入 Scrim 导致抽屉半路缩回或反复震荡的缺陷。
        if (drawerState.isAnimationRunning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { /* 消费事件，不做任何处理 */ }
                    }
            )
        }
    }
}

// 侧边栏整体内容
@Composable
fun SidebarContent(
    userName: String,
    appLanguage: String,
    sessions: List<ChatSession>,
    currentSessionId: String,
    onHistoryItemClick: (String) -> Unit,
    onSessionDelete: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    val isEn = appLanguage == "en"
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    val historyGroups = remember(sessions, appLanguage) {
        val todayList = mutableListOf<ChatSession>()
        val yesterdayList = mutableListOf<ChatSession>()
        val last7DaysList = mutableListOf<ChatSession>()
        val olderList = mutableListOf<ChatSession>()

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val yesterdayStart = todayStart - DateUtils.DAY_IN_MILLIS
        val sevenDaysAgoStart = todayStart - 7 * DateUtils.DAY_IN_MILLIS

        for (session in sessions) {
            val time = session.lastActiveTime
            when {
                time >= todayStart -> todayList.add(session)
                time >= yesterdayStart -> yesterdayList.add(session)
                time >= sevenDaysAgoStart -> last7DaysList.add(session)
                else -> olderList.add(session)
            }
        }

        val groups = mutableListOf<HistoryGroup>()
        if (todayList.isNotEmpty()) {
            groups.add(HistoryGroup(if (isEn) "Today" else "今天", todayList))
        }
        if (yesterdayList.isNotEmpty()) {
            groups.add(HistoryGroup(if (isEn) "Yesterday" else "昨天", yesterdayList))
        }
        if (last7DaysList.isNotEmpty()) {
            groups.add(HistoryGroup(if (isEn) "Previous 7 Days" else "前 7 天", last7DaysList))
        }
        if (olderList.isNotEmpty()) {
            groups.add(HistoryGroup(if (isEn) "Older" else "更早", olderList))
        }
        groups
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 1. 顶部用户信息栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (userName.isNotBlank()) userName.take(1).uppercase() else "L",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = userName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "loyea@example.com",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(16.dp))

        // 2. 历史会话列表 (带滑动)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(historyGroups) { group ->
                Column {
                    // 分组标题
                    Text(
                        text = group.timeLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    // 会话项
                    group.items.forEach { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (session.id == currentSessionId) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable { onHistoryItemClick(session.id) }
                                .padding(vertical = 6.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = session.title,
                                fontSize = 14.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { sessionToDelete = session.id },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 底部区域 (设置)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 设置按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSettingsClick() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isEn) "Settings" else "设置",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // 删除确认弹窗 (多语言兼容 & Loyea 燕麦沙黄主题风格)
        if (sessionToDelete != null) {
            val targetSessionId = sessionToDelete!!
            AlertDialog(
                onDismissRequest = { sessionToDelete = null },
                title = {
                    Text(
                        text = if (isEn) "Delete Chat?" else "确认删除会话？",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = if (isEn) "This will permanently delete this conversation." else "此操作将永久删除此会话历史记录。",
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onSessionDelete(targetSessionId)
                            sessionToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(text = if (isEn) "Delete" else "删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToDelete = null }) {
                        Text(
                            text = if (isEn) "Cancel" else "取消",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

data class HistoryGroup(
    val timeLabel: String,
    val items: List<ChatSession>
)

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    LoyeaTheme {
        MainScreen(
            userName = "Loyea Developer",
            apiConfig = ApiConfig(),
            apiConfigList = listOf(ApiConfig()),
            onActiveConfigChange = {},
            appLanguage = "zh",
            userBubbleColor = "",
            sessions = listOf(ChatSession("1", "Jetpack Compose Button Design")),
            currentSessionId = "1",
            messages = emptyList(),
            onMessagesChange = {},
            onSessionSelect = {},
            onSessionDelete = {},
            onNewChatClick = {},
            onNavigateToSettings = {}
        )
    }
}
