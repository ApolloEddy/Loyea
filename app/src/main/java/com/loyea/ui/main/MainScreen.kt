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
import androidx.compose.material.icons.outlined.Star
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
import com.loyea.ui.chat.ChatScreen
import com.loyea.ui.settings.ApiConfig
import com.loyea.ui.theme.ClaudeTheme
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    userName: String,
    apiConfig: ApiConfig,
    appLanguage: String,
    userBubbleColor: String,
    onApiConfigChange: (ApiConfig) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                    onHistoryItemClick = { title ->
                        scope.launch { drawerState.close() }
                        Toast.makeText(context, if (appLanguage == "en") "Opened: $title" else "已打开：$title", Toast.LENGTH_SHORT).show()
                    },
                    onSettingsClick = {
                        scope.launch { drawerState.close() }
                        onNavigateToSettings()
                    },
                    onUpgradeClick = {
                        scope.launch { drawerState.close() }
                        Toast.makeText(context, if (appLanguage == "en") "Upgrade Clicked" else "点击了升级", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    ) {
        ChatScreen(
            apiConfig = apiConfig,
            appLanguage = appLanguage,
            userBubbleColor = userBubbleColor,
            onApiConfigChange = onApiConfigChange,
            onMenuClick = {
                scope.launch { drawerState.open() }
            },
            modifier = modifier
        )
    }
}

// 侧边栏整体内容
@Composable
fun SidebarContent(
    userName: String,
    appLanguage: String,
    onHistoryItemClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onUpgradeClick: () -> Unit
) {
    val isEn = appLanguage == "en"
    val historyGroups = remember(appLanguage) {
        listOf(
            HistoryGroup(
                if (isEn) "Today" else "今天", listOf(
                    "Jetpack Compose Button Design",
                    "RAG Optimization Logic",
                    "Android Gradle Setup"
                )
            ),
            HistoryGroup(
                if (isEn) "Yesterday" else "昨天", listOf(
                    "Claude App UI Clone Spec",
                    "Consolas Font Configuration"
                )
            ),
            HistoryGroup(
                if (isEn) "Previous 7 Days" else "前 7 天", listOf(
                    "Kotlin Flow vs LiveData",
                    "Retrofit Network Architecture",
                    "Room Database Auto-migration"
                )
            )
        )
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
                    group.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onHistoryItemClick(item) }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
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
                                text = item,
                                fontSize = 14.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }

        // 3. 底部区域 (Upgrade 卡片 + 设置)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Claude 风格的黄金/橙色升级 Pro 会员卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFBEEDB)) // 特殊卡片色，类似 Claude Pro 的浅橙黄底色
                    .clickable { onUpgradeClick() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = Color(0xFFC27D38),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isEn) "Upgrade to Claude Pro" else "升级至 Claude Pro",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF5C3E21)
                    )
                    Text(
                        text = if (isEn) "5x more messages on Sonnet" else "Sonnet 额度提升 5 倍",
                        fontSize = 11.sp,
                        color = Color(0xFF5C3E21).copy(alpha = 0.7f)
                    )
                }
            }

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
    }
}

data class HistoryGroup(
    val timeLabel: String,
    val items: List<String>
)

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ClaudeTheme {
        MainScreen(
            userName = "Loyea Developer",
            apiConfig = ApiConfig(),
            appLanguage = "zh",
            userBubbleColor = "",
            onApiConfigChange = {},
            onNavigateToSettings = {}
        )
    }
}
