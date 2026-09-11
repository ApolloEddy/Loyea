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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.chat.ChatViewModel
import kotlinx.coroutines.delay

/**
 * S-05 记忆：固定记忆（会话核心记忆）的管理视图。
 * 添加→固定记忆；修改/删除即时生效并落盘，下一轮请求即用新状态（MEM-05/AC-14）。
 * 本期核心记忆即"固定"语义；自动整理产出的图谱记忆视图（MEM-01 适配层）在后续规格接入，
 * 页面如实说明，不伪造条目。
 */
@Composable
fun CompanionMemoryScreen(
    viewModel: ChatViewModel,
    sessionId: String,
    onBack: () -> Unit,
) {
    val memories = viewModel.activeSession.value?.coreMemories ?: emptyList()
    var adding by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var consolidateHint by remember { mutableStateOf<String?>(null) }

    fun commitEdit() {
        val text = draft.trim()
        val idx = editingIndex
        if (idx != null) {
            if (text.isNotEmpty()) {
                val next = memories.toMutableList().also { it[idx] = text }
                viewModel.updateCoreMemories(sessionId, next)
            }
        } else if (text.isNotEmpty()) {
            viewModel.updateCoreMemories(sessionId, memories + text)
        }
        editingIndex = null
        adding = false
        draft = ""
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
            Text("记忆", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.Brand, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .background(CompanionPalette.GlassFill, CircleShape)
                    .clickable {
                        val ok = viewModel.triggerManualMemorySummary(sessionId)
                        consolidateHint = if (ok) "正在整理，稍后自动完成" else "已有整理在进行中"
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("立即整理", fontSize = 12.sp, color = CompanionPalette.RoundBtnIcon)
            }
        }
        if (consolidateHint != null) {
            Text(
                consolidateHint!!,
                fontSize = 12.sp,
                color = CompanionPalette.Presence,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            LaunchedEffect(consolidateHint) {
                delay(3000)
                consolidateHint = null
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("固定记忆", fontSize = 13.sp, color = CompanionPalette.Presence)
                    Spacer(Modifier.width(8.dp))
                    Text("${memories.size} 条", fontSize = 12.sp, color = CompanionPalette.Hint)
                }
            }
            if (memories.isEmpty() && !adding) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text("还没有保存的记忆", fontSize = 14.sp, color = CompanionPalette.Hint)
                        Text(
                            "点击下方「添加」记下一件你们在意的事",
                            fontSize = 12.sp,
                            color = CompanionPalette.Hint,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            items(memories.size) { index ->
                val memory = memories[index]
                val editing = editingIndex == index
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(
                            shape = RoundedCornerShape(14.dp),
                            fill = CompanionPalette.GlassFill,
                            border = CompanionPalette.GlassBorder
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (editing) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = CompanionPalette.TextPrimary),
                            cursorBrush = SolidColor(CompanionPalette.AmberDot),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                        LaunchedEffect(editing) { focusRequester.requestFocus() }
                    } else {
                        Text(memory, fontSize = 15.sp, lineHeight = 22.sp, color = CompanionPalette.TextPrimary)
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "修改",
                            tint = CompanionPalette.Hint,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    editingIndex = index
                                    adding = false
                                    draft = memory
                                }
                        )
                        Spacer(Modifier.width(16.dp))
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除（聊天记录仍保留）",
                            tint = CompanionPalette.Hint,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    viewModel.updateCoreMemories(sessionId, memories.filterIndexed { i, _ -> i != index })
                                }
                        )
                    }
                }
            }
            if (adding) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .glassSurface(
                                shape = RoundedCornerShape(14.dp),
                                fill = CompanionPalette.BubbleUserFill,
                                border = CompanionPalette.BubbleUserBorder
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = CompanionPalette.TextPrimary),
                            cursorBrush = SolidColor(CompanionPalette.AmberDot),
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                        LaunchedEffect(adding) { focusRequester.requestFocus() }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                "取消",
                                fontSize = 13.sp,
                                color = CompanionPalette.Hint,
                                modifier = Modifier.clickable { adding = false; draft = "" }
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "保存",
                                fontSize = 13.sp,
                                color = CompanionPalette.ModeActiveText,
                                modifier = Modifier.clickable { commitEdit() }
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "自动整理产出的记忆会以可读短句出现在这里；删除记忆不会删除聊天记录",
                    fontSize = 12.sp,
                    color = CompanionPalette.Hint,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 添加按钮
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(48.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(CompanionPalette.SendAmberTop, CompanionPalette.SendAmberBottom)
                    ),
                    RoundedCornerShape(16.dp)
                )
                .clickable(enabled = !adding && editingIndex == null) {
                    adding = true
                    editingIndex = null
                    draft = ""
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = CompanionPalette.SendIcon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.SendIcon)
            }
        }
    }
}
