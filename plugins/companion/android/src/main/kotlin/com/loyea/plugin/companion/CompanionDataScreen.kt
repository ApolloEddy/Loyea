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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.ChatStorageManager
import com.loyea.ui.chat.ChatViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S-08 数据与备份。
 *
 * - 导出聊天：Markdown 可读文本（不称为完整备份）。
 * - 导出陪伴备份：JSON 快照（设置+记忆+全量消息）；不含 API Key 与媒体文件（DATA-01/02）。
 * - 恢复陪伴备份：先校验格式与版本 → 展示名称/消息数/时间范围 → 用户确认替换；
 *   先写新会话文件再原子更新会话列表，再清理旧文件，任何中断不留半新半旧（DATA-03/04）。
 * - 重新开始：明确范围（清聊天/记忆/摘要/草稿，保留普通模式与公共服务配置），
 *   提供导出入口后再执行；旧任务经 stopResponse 与会话重绑失效（DATA-05/06）。
 */
@Composable
fun CompanionDataScreen(
    viewModel: ChatViewModel,
    config: CompanionConfig,
    onBack: () -> Unit,
    onRestored: (String) -> Unit,
    onRestarted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<CompanionBackupCodec.BackupPreview?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var restoreDone by remember { mutableStateOf(false) }
    var restartConfirm by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf<String?>(null) }

    val jsonSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val session = withContext(Dispatchers.IO) {
                        ChatStorageManager(context).loadSessionList()
                            .firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
                    }
                    val messages = if (session != null) {
                        withContext(Dispatchers.IO) {
                            ChatStorageManager(context).loadSessionMessages(session.id)
                        }
                    } else emptyList()
                    val json = CompanionBackupCodec.exportJson(
                        displayName = config.displayName,
                        perceptionEnabled = config.perceptionEnabled,
                        proactiveEnabled = config.proactiveEnabled,
                        configVersion = config.configVersion,
                        createdAt = config.createdAt,
                        exportedAt = System.currentTimeMillis(),
                        session = session ?: com.loyea.ui.chat.ChatSession(
                            id = "", title = "陪伴", characterId = CompanionContract.COMPANION_CHARACTER_ID
                        ),
                        messages = messages
                    )
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                    }
                    exportDone = "备份已导出"
                } catch (e: Exception) {
                    exportDone = null
                    restoreError = "导出失败：${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val mdSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val session = withContext(Dispatchers.IO) {
                        ChatStorageManager(context).loadSessionList()
                            .firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
                    }
                    val messages = if (session != null) {
                        withContext(Dispatchers.IO) {
                            ChatStorageManager(context).loadSessionMessages(session.id)
                        }
                    } else emptyList()
                    val md = CompanionBackupCodec.exportMarkdown(
                        config.displayName, messages, System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(md.toByteArray(Charsets.UTF_8))
                        }
                    }
                    exportDone = "聊天记录已导出"
                } catch (e: Exception) {
                    restoreError = "导出失败：${e.message}"
                } finally {
                    busy = false
                }
            }
        }
    }

    val restorePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            busy = true
            restoreError = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().toString(Charsets.UTF_8)
                        } ?: throw IllegalStateException("无法读取文件")
                    }.map { CompanionBackupCodec.parse(it) }
                }
                busy = false
                result.fold(
                    onSuccess = { parsed ->
                        when (parsed) {
                            is CompanionBackupCodec.ParseResult.Ok -> restorePreview = parsed.preview
                            is CompanionBackupCodec.ParseResult.Rejected -> restoreError = parsed.reason
                        }
                    },
                    onFailure = { restoreError = "读取失败：${it.message}" }
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
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = CompanionPalette.Brand)
            }
            Text("数据与备份", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.Brand)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            SectionLabel("导出")
            ActionRow(
                icon = Icons.Rounded.SaveAlt,
                title = "导出聊天（Markdown）",
                subtitle = "可读文本；不包含图片/音频，不能用于恢复",
                enabled = !busy
            ) { mdSaver.launch("loyea_companion_chat_${exportStamp()}.md") }
            GroupDivider()
            ActionRow(
                icon = Icons.Rounded.Archive,
                title = "导出陪伴备份（JSON）",
                subtitle = "包含设置、记忆与全部消息；图片和音频文件不随备份迁移；不含任何 API Key",
                enabled = !busy
            ) { jsonSaver.launch("loyea_companion_backup_${exportStamp()}.json") }

            SectionLabel("恢复", topPadding = 24)
            ActionRow(
                icon = Icons.Rounded.SettingsBackupRestore,
                title = "恢复陪伴备份",
                subtitle = "先校验再确认，替换当前陪伴数据（不会拼接两段记录）",
                enabled = !busy
            ) { restoreError = null; restorePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

            restorePreview?.let { preview ->
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(
                            shape = RoundedCornerShape(16.dp),
                            fill = CompanionPalette.BubbleUserFill,
                            border = CompanionPalette.BubbleUserBorder
                        )
                        .padding(16.dp)
                ) {
                    Text("已校验，替换前请确认", fontSize = 13.sp, color = CompanionPalette.ModeActiveText)
                    Text("陪伴对象：${preview.displayName}", fontSize = 15.sp, color = CompanionPalette.TextPrimary, modifier = Modifier.padding(top = 8.dp))
                    Text("消息 ${preview.messageCount} 条 · 记忆 ${preview.memoryCount} 条", fontSize = 13.sp, color = CompanionPalette.TextPrimary, modifier = Modifier.padding(top = 4.dp))
                    val range = formatRange(preview.firstMessageAt, preview.lastMessageAt)
                    if (range != null) {
                        Text("时间范围：$range", fontSize = 13.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.padding(top = 12.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .background(CompanionPalette.GlassFill, RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy) { restorePreview = null }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", fontSize = 14.sp, color = CompanionPalette.TextPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .background(Color(0x40BA761F), RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy) {
                                    busy = true
                                    scope.launch {
                                        val newId = withContext(Dispatchers.IO) {
                                            applyRestore(context, viewModel, preview)
                                        }
                                        busy = false
                                        restorePreview = null
                                        restoreDone = true
                                        onRestored(newId)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("确认替换当前陪伴数据", fontSize = 14.sp, color = CompanionPalette.TextOnUser)
                        }
                    }
                }
            }

            if (restoreDone) {
                InfoNote("恢复完成，已回到刚恢复的记录。")
            }
            exportDone?.let { InfoNote(it) }
            restoreError?.let {
                InfoNote(it, tone = Color(0xFFFF9C85))
                Text(
                    "当前数据未被改动。",
                    fontSize = 12.sp,
                    color = CompanionPalette.Hint,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            SectionLabel("重新开始", topPadding = 24)
            ActionRow(
                icon = Icons.Rounded.RestartAlt,
                title = "重新开始陪伴",
                subtitle = "清除当前陪伴的聊天、记忆、摘要与草稿；普通模式与公共服务配置不受影响",
                enabled = !busy
            ) { restartConfirm = true }

            if (restartConfirm) {
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(
                            shape = RoundedCornerShape(16.dp),
                            fill = CompanionPalette.GlassFill,
                            border = CompanionPalette.BubbleUserBorder
                        )
                        .padding(16.dp)
                ) {
                    Text("这将清除：", fontSize = 13.sp, color = CompanionPalette.TextPrimary)
                    Text(
                        "当前陪伴的聊天记录、固定记忆、摘要与草稿。\n不会影响：普通模式的会话、模型配置与其他设置。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = CompanionPalette.Hint,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "想留一份记录的话，先使用上方的导出。",
                        fontSize = 12.sp,
                        color = CompanionPalette.Hint,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(Modifier.padding(top = 12.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .background(CompanionPalette.GlassFill, RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy) { restartConfirm = false }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", fontSize = 14.sp, color = CompanionPalette.TextPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .background(Color(0x40BA761F), RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy) {
                                    busy = true
                                    scope.launch {
                                        withContext(Dispatchers.IO) { applyRestart(context, viewModel) }
                                        busy = false
                                        restartConfirm = false
                                        onRestarted()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("确认重新开始", fontSize = 14.sp, color = CompanionPalette.TextOnUser)
                        }
                    }
                }
            }

            if (busy) {
                Text(
                    "正在处理…",
                    fontSize = 12.sp,
                    color = CompanionPalette.Presence,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- 恢复/重开的实际数据操作（DATA-03 原子顺序：先写新文件 → 原子换列表 → 清旧文件） ----

/** 恢复：返回重新映射后的新会话 ID（DATA-04）。 */
private suspend fun applyRestore(
    context: android.content.Context,
    viewModel: ChatViewModel,
    preview: CompanionBackupCodec.BackupPreview,
): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
    val storage = ChatStorageManager(context)
    val old = viewModel.sessions.value.firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
        ?: storage.loadSessionList().firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
    val newId = System.currentTimeMillis().toString()
    val restored = preview.session.copy(
        id = newId,
        characterId = CompanionContract.COMPANION_CHARACTER_ID,
        lastActiveTime = System.currentTimeMillis(),
        bindingRevision = preview.session.bindingRevision + 1 // DATA-04：代际递增，旧写回失效
    )
    storage.saveSessionMessages(newId, preview.messages) // 1. 新数据先落盘（原子写）
    storage.updateSessionList { list -> // 2. 原子换列表：移除全部旧陪伴归属，挂入新会话
        (list.filter { !CompanionContract.isCompanionCharacter(it.characterId) } + restored)
            .sortedByDescending { it.lastActiveTime }
    }
    old?.let {
        viewModel.clearDraft(it.id) // 草稿随旧绑定清除
        if (it.id != newId) storage.deleteSession(it.id) // 3. 清理旧文件与其书绑定
    }
    newId
}

/** 重新开始：清陪伴会话/记忆/摘要与草稿，保留普通模式与公共服务配置（DATA-05）。 */
private suspend fun applyRestart(
    context: android.content.Context,
    viewModel: ChatViewModel,
): Unit = kotlinx.coroutines.withContext(Dispatchers.IO) {
    viewModel.stopResponse() // DATA-05：使进行中的写回失效
    val storage = ChatStorageManager(context)
    val old = viewModel.sessions.value.firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
        ?: storage.loadSessionList().firstOrNull { CompanionContract.isCompanionCharacter(it.characterId) }
    old?.let {
        viewModel.clearDraft(it.id)
        storage.deleteSession(it.id)
    }
    Unit
}

private fun exportStamp(): String =
    SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(System.currentTimeMillis()))

private fun formatRange(first: Long?, last: Long?): String? {
    if (first == null || last == null) return null
    val fmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault())
    return "${fmt.format(Date(first))} ~ ${fmt.format(Date(last))}"
}

@Composable
private fun SectionLabel(text: String, topPadding: Int = 0) {
    Text(
        text,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        color = CompanionPalette.Hint,
        modifier = Modifier.padding(top = topPadding.dp, bottom = 8.dp)
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(16.dp),
                fill = CompanionPalette.GlassFill,
                border = CompanionPalette.GlassBorder,
                sheenAlpha = 0.04f
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = CompanionPalette.RoundBtnIcon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = CompanionPalette.TextPrimary)
            Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(Color(0x14FFFFFF))
    )
}

@Composable
private fun InfoNote(text: String, tone: Color = CompanionPalette.Presence) {
    Text(
        text,
        fontSize = 13.sp,
        color = tone,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    )
}
