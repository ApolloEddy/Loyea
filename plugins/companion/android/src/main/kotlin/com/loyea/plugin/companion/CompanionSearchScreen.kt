package com.loyea.plugin.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.chat.ChatViewModel
import com.loyea.ui.chat.Sender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private data class SearchHit(
    val messageId: String,
    val timestamp: Long,
    val isUser: Boolean,
    val snippetBefore: String,
    val snippetMatch: String,
    val snippetAfter: String,
)

/**
 * S-06 查找记录：当前陪伴会话的本地关键词搜索（UI-20）。
 * 空查询不搜索（UI-22）；快速输入防抖，仅最后一次查询上屏；匹配完整已落盘记录。
 * 全量遍历在 Dispatchers.Default 执行（NFR-03，审计 R-09：不得阻塞主线程）。
 * 点结果回到同一时间线并定位到原消息、短暂高亮（UI-21）。
 */
@Composable
fun CompanionSearchScreen(
    viewModel: ChatViewModel,
    displayName: String,
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }

    // 防抖：300ms 内的连续输入只有最后一次执行搜索（UI-22）
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            hits = null
            return@LaunchedEffect
        }
        delay(300)
        val all = viewModel.messages.value
        val lower = trimmed.lowercase()
        hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            all.mapNotNull { message ->
                val content = message.content
                if (content.isBlank()) return@mapNotNull null
                val idx = content.lowercase().indexOf(lower)
                if (idx < 0) return@mapNotNull null
                val start = (idx - 24).coerceAtLeast(0)
                val end = (idx + trimmed.length + 24).coerceAtMost(content.length)
                SearchHit(
                    messageId = message.id,
                    timestamp = message.timestamp,
                    isUser = message.sender == Sender.USER,
                    snippetBefore = (if (start > 0) "…" else "") + content.substring(start, idx),
                    snippetMatch = content.substring(idx, idx + trimmed.length),
                    snippetAfter = content.substring(idx + trimmed.length, end) + (if (end < content.length) "…" else "")
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = CompanionPalette.Brand)
            }
            Box(
                Modifier
                    .weight(1f)
                    .glassSurface(
                        shape = RoundedCornerShape(22.dp),
                        fill = CompanionPalette.GlassFill,
                        border = CompanionPalette.GlassBorder
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = CompanionPalette.TextPrimary),
                    cursorBrush = SolidColor(CompanionPalette.AmberDot),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text("搜索你们聊过的内容", fontSize = 15.sp, color = CompanionPalette.Placeholder)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        val result = hits
        if (result == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "搜索这个会话里已保存的文字记录",
                    fontSize = 13.sp,
                    color = CompanionPalette.Hint,
                    textAlign = TextAlign.Center
                )
            }
        } else if (result.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("没有找到匹配的内容", fontSize = 14.sp, color = CompanionPalette.Hint)
            }
        } else {
            val dateFormat = remember { SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                items(result, key = { it.messageId }) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glassSurface(
                                shape = RoundedCornerShape(14.dp),
                                fill = CompanionPalette.GlassFill,
                                border = CompanionPalette.GlassBorder
                            )
                            .clickable { onOpenMessage(hit.messageId) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (hit.isUser) "你" else displayName,
                                fontSize = 12.sp,
                                color = CompanionPalette.Presence
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                dateFormat.format(Date(hit.timestamp)),
                                fontSize = 12.sp,
                                color = CompanionPalette.Hint
                            )
                        }
                        Text(
                            annotatedSnippet(hit),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = CompanionPalette.TextPrimary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun annotatedSnippet(hit: SearchHit): AnnotatedString = buildAnnotatedString {
    append(hit.snippetBefore)
    pushStyle(SpanStyle(color = Color(0xFFE7C28A), fontWeight = FontWeight.Medium))
    append(hit.snippetMatch)
    pop()
    append(hit.snippetAfter)
}
