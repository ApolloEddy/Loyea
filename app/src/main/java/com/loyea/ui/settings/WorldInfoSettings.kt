package com.loyea.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.ui.chat.ChatViewModel
import com.loyea.ui.chat.WorldInfoConfig
import com.loyea.ui.chat.WorldInfoEntry
import com.loyea.ui.chat.WorldInfoInsertionOrder
import com.loyea.ui.chat.WorldInfoScope

/**
 * 全局世界观（World Info）设置编辑器。
 *
 * 条目结构完全兼容 SillyTavern 世界书：支持导入/导出 SillyTavern 标准 World Info JSON
 * （{"kind":0,"entries":{id:{key:[...],keysecondary:[...],content,constant,disable,order,depth,comment,selective,...}}}），
 * 并保留 ST 字段保证往返不失真。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldInfoSettingsLayout(
    viewModel: ChatViewModel?,
    appLanguage: String,
    onBackClick: () -> Unit,
    scope: WorldInfoScope = WorldInfoScope.GLOBAL
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"
    // SESSION scope：未配置会话书时，显示全局条目作为"当前生效集"（编辑即生成本会话独立副本）
    val entries = if (scope == WorldInfoScope.SESSION) {
        viewModel?.sessionWorldInfo?.value?.entries ?: viewModel?.worldInfoEntries?.value.orEmpty()
    } else {
        viewModel?.worldInfoEntries?.value ?: emptyList()
    }
    val hasSessionBook = viewModel?.sessionWorldInfo?.value != null

    var editingEntry by remember { mutableStateOf<WorldInfoEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    // 导入 SillyTavern World Info JSON（SAF GetContent，application/json）
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (json.isNullOrBlank()) {
                Toast.makeText(context, if (isEn) "Empty file" else "导入文件为空", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val imported = parseSillyTavernWorldInfo(json)
            if (imported == null) {
                Toast.makeText(context, if (isEn) "Not a valid SillyTavern World Info file" else "不是有效的 SillyTavern 世界书文件", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            viewModel?.saveWorldInfo(imported, scope)
            Toast.makeText(context, if (isEn) "Imported ${imported.size} entries" else "已导入 ${imported.size} 条世界观条目", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, if (isEn) "Import failed" else "导入失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 导出为 SillyTavern World Info JSON（SAF CreateDocument）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val json = buildSillyTavernWorldInfo(entries)
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
            Toast.makeText(context, if (isEn) "Exported ${entries.size} entries" else "已导出 ${entries.size} 条世界观条目", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, if (isEn) "Export failed" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (scope == WorldInfoScope.SESSION) {
                            if (isEn) "Session World Info" else "会话世界书"
                        } else {
                            if (isEn) "World Info (Global Lore)" else "World Info 世界观记忆"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // 全局配置
                    IconButton(onClick = { showConfig = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = if (isEn) "World Info Global Settings" else "世界书全局配置",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // 导入
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = if (isEn) "Import SillyTavern World Info" else "导入 SillyTavern 世界书",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // 导出
                    IconButton(onClick = {
                        if (entries.isEmpty()) {
                            Toast.makeText(context, if (isEn) "Nothing to export" else "没有可导出的条目", Toast.LENGTH_SHORT).show()
                        } else {
                            exportLauncher.launch("loyea_world_info.json")
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = if (isEn) "Export SillyTavern World Info" else "导出 SillyTavern 世界书",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // 新建
                    IconButton(onClick = {
                        editingEntry = null
                        showEditor = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (isEn) "Add Entry" else "新建条目",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (scope == WorldInfoScope.SESSION) {
                    SessionWorldInfoBanner(
                        hasSessionBook = hasSessionBook,
                        isEn = isEn,
                        onCreateIndependent = { viewModel?.createSessionWorldInfo() },
                        onRestoreGlobal = { showRestoreConfirm = true }
                    )
                }
                if (entries.isEmpty()) {
                    EmptyHint(isEn = isEn, modifier = Modifier.fillMaxWidth().weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            WorldInfoEntryCard(
                                entry = entry,
                                isEn = isEn,
                                onEdit = {
                                    editingEntry = entry
                                    showEditor = true
                                },
                                onToggle = { enabled ->
                                    val updated = entries.map { if (it.id == entry.id) it.copy(enabled = enabled) else it }
                                    viewModel?.saveWorldInfo(updated, scope)
                                },
                                onDelete = {
                                    val updated = entries.filter { it.id != entry.id }
                                    viewModel?.saveWorldInfo(updated, scope)
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    if (showEditor) {
        WorldInfoEditDialog(
            editingEntry = editingEntry,
            isEn = isEn,
            onSave = { newOrUpdated ->
                val updated = if (editingEntry == null) {
                    entries + newOrUpdated
                } else {
                    entries.map { if (it.id == newOrUpdated.id) newOrUpdated else it }
                }
                viewModel?.saveWorldInfo(updated, scope)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }

    if (showConfig) {
        WorldInfoConfigDialog(
            config = if (scope == WorldInfoScope.SESSION) {
                viewModel?.sessionWorldInfo?.value?.config ?: viewModel?.worldInfoConfig?.value ?: WorldInfoConfig()
            } else {
                viewModel?.worldInfoConfig?.value ?: WorldInfoConfig()
            },
            isEn = isEn,
            onSave = { cfg ->
                viewModel?.saveWorldInfoConfig(cfg, scope)
                showConfig = false
            },
            onDismiss = { showConfig = false }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(if (isEn) "Restore global World Info?" else "恢复使用全局世界书？") },
            text = {
                Text(
                    if (isEn)
                        "This session will stop using its own World Info and fall back to the global book. This session's entries and settings will be discarded."
                    else
                        "本会话将不再使用独立世界书，回退到全局世界书。本会话专属的条目与配置将被丢弃。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel?.clearSessionWorldInfo()
                    showRestoreConfirm = false
                }) {
                    Text(if (isEn) "Restore" else "恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(if (isEn) "Cancel" else "取消")
                }
            }
        )
    }
}

@Composable
private fun EmptyHint(isEn: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (isEn)
                "No World Info entries yet.\nTap + to add, or import a SillyTavern world book."
            else
                "还没有世界观条目。\n点右上角 + 新建，或导入 SillyTavern 世界书。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/**
 * 会话世界书（SESSION scope）顶部状态横幅：
 * - 未配置：提示当前使用全局书；编辑下方条目或点「创建独立副本」即生成本会话专属书。
 * - 已配置：提示本会话使用独立书；点「恢复全局」删除会话书、回退全局。
 */
@Composable
private fun SessionWorldInfoBanner(
    hasSessionBook: Boolean,
    isEn: Boolean,
    onCreateIndependent: () -> Unit,
    onRestoreGlobal: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasSessionBook) {
                        if (isEn) "This session uses its own World Info (does not stack with global)" else "本会话使用独立世界书（不叠加全局）"
                    } else {
                        if (isEn) "This session is using the global World Info" else "此会话正在使用全局世界书"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium
                )
                if (!hasSessionBook) {
                    Text(
                        text = if (isEn) "Edits below will create a session-specific copy." else "编辑下方条目或配置将生成本会话的独立副本。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            if (hasSessionBook) {
                TextButton(onClick = onRestoreGlobal) {
                    Text(if (isEn) "Restore global" else "恢复全局")
                }
            } else {
                TextButton(onClick = onCreateIndependent) {
                    Text(if (isEn) "Create copy" else "创建独立副本")
                }
            }
        }
    }
}

@Composable
private fun WorldInfoEntryCard(
    entry: WorldInfoEntry,
    isEn: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val scrollState = rememberScrollState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.enabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 关键词 chips 行（横向滚动）
            if (entry.keywords.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entry.keywords.forEach { kw ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = kw,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                Text(
                    text = if (isEn) "(no keywords)" else "(无关键词)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 内容预览
            Text(
                text = entry.content,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // 元信息行：ST 兼容字段摘要（含 ST v2 高级字段）
            val metaParts = buildList {
                if (entry.constant) add(if (isEn) "constant" else "常驻")
                if (entry.selective) add(if (isEn) "selective" else "选择性")
                if (entry.keysecondary.isNotEmpty()) add("secondary:${entry.keysecondary.size}")
                if (entry.group.isNotBlank()) add(if (isEn) "group: ${entry.group}" else "分组: ${entry.group}")
                if (entry.useProbability) add("p:${entry.probability}%")
                if (entry.depth != 4) add("depth:${entry.depth}")
                if (entry.delayUntilRecursion > 0) add("delay:${entry.delayUntilRecursion}")
                if (entry.preventRecursion) add(if (isEn) "prevent" else "断链")
                if (entry.excludeRecursion) add(if (isEn) "no-recursion" else "禁递归")
                if (entry.keysContainedIn != "chat") add("src:${entry.keysContainedIn}")
                if (entry.comment.isNotBlank()) add(if (isEn) "comment: ${entry.comment}" else "备注: ${entry.comment}")
            }
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "· " + metaParts.joinToString(" · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isEn) "Enabled" else "启用",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = onToggle
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = if (isEn) "Edit" else "编辑",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = if (isEn) "Delete" else "删除",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 条目编辑对话框：长表单（verticalScroll + 高度上限），覆盖全部 23 个字段。
 * 常用区 + AdvancedSection 折叠高级区。
 */
@Composable
private fun WorldInfoEditDialog(
    editingEntry: WorldInfoEntry?,
    isEn: Boolean,
    onSave: (WorldInfoEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var keywordsInput by remember { mutableStateOf(editingEntry?.keywords?.joinToString(",") ?: "") }
    var keySecondaryInput by remember { mutableStateOf(editingEntry?.keysecondary?.joinToString(",") ?: "") }
    var contentInput by remember { mutableStateOf(editingEntry?.content ?: "") }
    var constantInput by remember { mutableStateOf(editingEntry?.constant ?: false) }
    var commentInput by remember { mutableStateOf(editingEntry?.comment ?: "") }
    var selectiveInput by remember { mutableStateOf(editingEntry?.selective ?: false) }
    var groupInput by remember { mutableStateOf(editingEntry?.group ?: "") }
    var probabilityInput by remember { mutableStateOf(editingEntry?.probability?.toString() ?: "100") }
    var useProbabilityInput by remember { mutableStateOf(editingEntry?.useProbability ?: false) }
    var depthInput by remember { mutableStateOf(editingEntry?.depth?.toString() ?: "4") }
    var orderInput by remember { mutableStateOf(editingEntry?.order?.toString() ?: "100") }
    var delayUntilRecursionInput by remember { mutableStateOf(editingEntry?.delayUntilRecursion?.toString() ?: "0") }
    var preventRecursionInput by remember { mutableStateOf(editingEntry?.preventRecursion ?: false) }
    var allowRecursionInput by remember { mutableStateOf(editingEntry?.allowRecursion ?: true) }
    var excludeRecursionInput by remember { mutableStateOf(editingEntry?.excludeRecursion ?: false) }
    var keysContainedInInput by remember { mutableStateOf(editingEntry?.keysContainedIn ?: "chat") }
    var weightInput by remember { mutableStateOf(editingEntry?.weight?.toString() ?: "0") }
    var positionInput by remember { mutableStateOf(editingEntry?.position?.toString() ?: "0") }
    var selectiveLogicInput by remember { mutableStateOf(editingEntry?.selectiveLogic?.toString() ?: "0") }

    val selectiveLogicOptions = listOf(
        "0" to (if (isEn) "AND_ANY: key OR secondary matches" else "AND_ANY：主词或次词任一命中"),
        "1" to (if (isEn) "NOT_ALL: key + NOT all secondary" else "NOT_ALL：主词命中且非全部次词存在"),
        "2" to (if (isEn) "NOT_ANY: key + NO secondary" else "NOT_ANY：主词命中且无任一次词存在"),
        "3" to (if (isEn) "AND_ALL: key + ALL secondary" else "AND_ALL：主词命中且全部次词存在")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (editingEntry == null) {
                    if (isEn) "New World Info Entry" else "新建世界观条目"
                } else {
                    if (isEn) "Edit World Info Entry" else "编辑世界观条目"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 560.dp)
            ) {
                OutlinedTextField(
                    value = keywordsInput,
                    onValueChange = { keywordsInput = it },
                    label = { Text(if (isEn) "Trigger keywords (comma separated)" else "触发关键词（逗号分隔）") },
                    placeholder = { Text(if (isEn) "e.g. 学院, 魔法, school" else "例如：学院, 魔法, 咖啡") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = keySecondaryInput,
                    onValueChange = { keySecondaryInput = it },
                    label = { Text(if (isEn) "Secondary keywords (optional)" else "次关键词（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = contentInput,
                    onValueChange = { contentInput = it },
                    label = { Text(if (isEn) "Content (injected when triggered)" else "内容（命中后注入）") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                SwitchRow(
                    checked = constantInput,
                    onCheckedChange = { constantInput = it },
                    label = if (isEn) "Always inject (constant)" else "常驻注入（无视关键词）",
                    isEn = isEn
                )
                SwitchRow(
                    checked = selectiveInput,
                    onCheckedChange = { selectiveInput = it },
                    label = if (isEn) "Selective (use secondary logic)" else "选择性（启用次词逻辑）",
                    isEn = isEn
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = groupInput,
                    onValueChange = { groupInput = it },
                    label = { Text(if (isEn) "Group (adjacent injection)" else "分组（同组连续注入）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    NumberTextField(
                        value = probabilityInput,
                        onValueChange = { probabilityInput = it },
                        label = if (isEn) "Trigger probability %" else "触发概率 %",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    NumberTextField(
                        value = depthInput,
                        onValueChange = { depthInput = it },
                        label = if (isEn) "Depth (0=global)" else "深度 (0=全局)",
                        modifier = Modifier.weight(1f)
                    )
                }
                SwitchRow(
                    checked = useProbabilityInput,
                    onCheckedChange = { useProbabilityInput = it },
                    label = if (isEn) "Enable probability gating" else "启用概率触发",
                    isEn = isEn
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    NumberTextField(
                        value = orderInput,
                        onValueChange = { orderInput = it },
                        label = if (isEn) "Order" else "排序权重 order",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    NumberTextField(
                        value = delayUntilRecursionInput,
                        onValueChange = { delayUntilRecursionInput = it },
                        label = if (isEn) "Delay to recursion" else "延迟到递归轮",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                AdvancedSectionLocal(
                    title = if (isEn) "Advanced" else "高级设置",
                    isEn = isEn
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DropdownField(
                        label = if (isEn) "Selective logic" else "选择性逻辑",
                        options = selectiveLogicOptions,
                        current = selectiveLogicInput,
                        isEn = isEn,
                        onValueChange = { selectiveLogicInput = it }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // keysContainedIn 多选 toggle chips
                    Text(
                        text = if (isEn) "Scan sources" else "关键词扫描源",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val currentSources = keysContainedInInput.split(",")
                        .map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("chat", "user", "system", "world").forEach { src ->
                            SourceToggleChip(
                                label = src,
                                selected = src in currentSources,
                                onToggle = {
                                    if (src in currentSources) currentSources.remove(src) else currentSources.add(src)
                                    keysContainedInInput = if (currentSources.isEmpty()) "chat" else currentSources.joinToString(",")
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    SwitchRow(
                        checked = allowRecursionInput,
                        onCheckedChange = { allowRecursionInput = it },
                        label = if (isEn) "Allow recursion (as scan source)" else "允许递归（作为扫描来源）",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = excludeRecursionInput,
                        onCheckedChange = { excludeRecursionInput = it },
                        label = if (isEn) "Exclude recursion (direct only)" else "仅直接扫描（禁递归命中）",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = preventRecursionInput,
                        onCheckedChange = { preventRecursionInput = it },
                        label = if (isEn) "Prevent recursion (break chain)" else "命中后阻断递归链",
                        isEn = isEn
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        NumberTextField(
                            value = weightInput,
                            onValueChange = { weightInput = it },
                            label = if (isEn) "Weight" else "权重",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        NumberTextField(
                            value = positionInput,
                            onValueChange = { positionInput = it },
                            label = if (isEn) "Position" else "位置",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = commentInput,
                    onValueChange = { commentInput = it },
                    label = { Text(if (isEn) "Comment (optional)" else "备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val keywords = keywordsInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (contentInput.isBlank()) {
                        return@TextButton // 内容必填
                    }
                    val base = editingEntry
                    onSave(
                        WorldInfoEntry(
                            id = base?.id ?: System.currentTimeMillis().toString(),
                            keywords = keywords,
                            content = contentInput.trim(),
                            enabled = base?.enabled ?: true,
                            uid = base?.uid ?: 0,
                            keysecondary = keySecondaryInput.split(",").map { it.trim() }.filter { it.isNotBlank() },
                            constant = constantInput,
                            order = orderInput.toIntOrNull() ?: base?.order ?: 100,
                            depth = depthInput.toIntOrNull() ?: base?.depth ?: 4,
                            comment = commentInput.trim(),
                            selective = selectiveInput,
                            disable = base?.disable ?: false,
                            selectiveLogic = selectiveLogicInput.toIntOrNull() ?: base?.selectiveLogic ?: 0,
                            group = groupInput.trim(),
                            probability = probabilityInput.toIntOrNull() ?: base?.probability ?: 100,
                            useProbability = useProbabilityInput,
                            delayUntilRecursion = delayUntilRecursionInput.toIntOrNull() ?: base?.delayUntilRecursion ?: 0,
                            preventRecursion = preventRecursionInput,
                            allowRecursion = allowRecursionInput,
                            excludeRecursion = excludeRecursionInput,
                            keysContainedIn = keysContainedInInput,
                            position = positionInput.toIntOrNull() ?: base?.position ?: 0,
                            weight = weightInput.toIntOrNull() ?: base?.weight ?: 0
                        )
                    )
                }
            ) {
                Text(if (isEn) "Save" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isEn) "Cancel" else "取消")
            }
        }
    )
}

/**
 * 全局配置对话框：扫描深度 / 注入位置 / 排序模式 / token 预算 / 递归上限 / 开关
 */
@Composable
private fun WorldInfoConfigDialog(
    config: WorldInfoConfig,
    isEn: Boolean,
    onSave: (WorldInfoConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var scanDepthInput by remember { mutableStateOf(config.scanDepth.toString()) }
    var positionInput by remember { mutableStateOf(config.position) }
    var orderModeInput by remember { mutableStateOf(config.insertionOrderMode.name) }
    var tokenBudgetInput by remember { mutableStateOf(config.tokenBudget.toString()) }
    var recursionDepthInput by remember { mutableStateOf(config.recursionDepthCap.toString()) }
    var allowRecursionInput by remember { mutableStateOf(config.allowRecursion) }
    var emitHeadersInput by remember { mutableStateOf(config.emitGroupHeaders) }

    val positionOptions = listOf(
        "bottom" to (if (isEn) "Bottom (end of prompt, keeps prefix cache)" else "底部（Prompt 最尾，保持前缀缓存）"),
        "top" to (if (isEn) "Top (after web search, breaks prefix cache)" else "顶部（联网块之后，前缀缓存失效）")
    )
    val orderModeOptions = listOf(
        WorldInfoInsertionOrder.ORDER.name to (if (isEn) "Order (asc)" else "按 order 升序（默认）"),
        WorldInfoInsertionOrder.KEY_LENGTH.name to (if (isEn) "Key length (desc)" else "按首主词长度降序"),
        WorldInfoInsertionOrder.ALPHABETICAL.name to (if (isEn) "Alphabetical" else "按内容字典序"),
        WorldInfoInsertionOrder.INSERT_AT_TOP.name to (if (isEn) "Insert at top" else "按 order 升序（置顶语义）"),
        WorldInfoInsertionOrder.INSERT_AT_BOTTOM.name to (if (isEn) "Insert at bottom" else "按 order 降序（置底语义）")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEn) "World Info Global Settings" else "世界书全局配置",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 480.dp)
            ) {
                NumberTextField(
                    value = scanDepthInput,
                    onValueChange = { scanDepthInput = it },
                    label = if (isEn) "Scan depth (chat window)" else "扫描深度（聊天窗口条数）",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                DropdownField(
                    label = if (isEn) "Injection position" else "注入位置",
                    options = positionOptions,
                    current = positionInput,
                    isEn = isEn,
                    onValueChange = { positionInput = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
                DropdownField(
                    label = if (isEn) "Insertion order" else "排序模式",
                    options = orderModeOptions,
                    current = orderModeInput,
                    isEn = isEn,
                    onValueChange = { orderModeInput = it }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    NumberTextField(
                        value = tokenBudgetInput,
                        onValueChange = { tokenBudgetInput = it },
                        label = if (isEn) "Token budget" else "Token 预算",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    NumberTextField(
                        value = recursionDepthInput,
                        onValueChange = { recursionDepthInput = it },
                        label = if (isEn) "Recursion depth" else "递归轮次上限",
                        modifier = Modifier.weight(1f)
                    )
                }
                SwitchRow(
                    checked = allowRecursionInput,
                    onCheckedChange = { allowRecursionInput = it },
                    label = if (isEn) "Enable recursion globally" else "启用递归链",
                    isEn = isEn
                )
                SwitchRow(
                    checked = emitHeadersInput,
                    onCheckedChange = { emitHeadersInput = it },
                    label = if (isEn) "Emit group headers (# group)" else "分组前输出 # 注释行",
                    isEn = isEn
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        WorldInfoConfig(
                            scanDepth = scanDepthInput.toIntOrNull()?.coerceAtLeast(1) ?: config.scanDepth,
                            position = if (positionInput == "top") "top" else "bottom",
                            insertionOrderMode = runCatching {
                                WorldInfoInsertionOrder.valueOf(orderModeInput)
                            }.getOrDefault(config.insertionOrderMode),
                            tokenBudget = tokenBudgetInput.toLongOrNull()?.coerceAtLeast(1) ?: config.tokenBudget,
                            recursionDepthCap = recursionDepthInput.toIntOrNull()?.coerceAtLeast(0) ?: config.recursionDepthCap,
                            allowRecursion = allowRecursionInput,
                            emitGroupHeaders = emitHeadersInput
                        )
                    )
                }
            ) {
                Text(if (isEn) "Save" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isEn) "Cancel" else "取消")
            }
        }
    )
}

// ===== 本地可复用 helper =====

@Composable
private fun AdvancedSectionLocal(
    title: String,
    isEn: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    isEn: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun NumberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.filter { it.isDigit() || it == '-' })
        },
        label = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    current: String,
    isEn: Boolean,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = options.firstOrNull { it.first == current }?.second
        ?: (if (isEn) "Custom" else "自定义")

    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, optionText) ->
                    DropdownMenuItem(
                        text = { Text(optionText, fontSize = 13.sp) },
                        onClick = {
                            onValueChange(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceToggleChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                }
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            }
        )
    }
}

/**
 * 解析 SillyTavern World Info JSON → 内部条目列表。解析失败返回 null。
 * 支持 {"kind":0,"entries":{"<id>":{key,keysecondary,content,constant,disable,order,depth,comment,selective,...}}}
 */
private fun parseSillyTavernWorldInfo(json: String): List<WorldInfoEntry>? {
    return try {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) return null
        val rootObj = root.asJsonObject
        val entriesObj = rootObj.getAsJsonObject("entries") ?: return null
        val result = mutableListOf<WorldInfoEntry>()
        var nextUid = 0
        entriesObj.entrySet().forEach { (idStr, entryEl) ->
            if (!entryEl.isJsonObject) return@forEach
            val obj = entryEl.asJsonObject
            fun str(name: String): String = obj.get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""
            fun bool(name: String): Boolean = obj.get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            fun int(name: String, def: Int): Int = obj.get(name)?.takeIf { !it.isJsonNull }?.asInt ?: def
            fun strArr(name: String): List<String> =
                obj.get(name)?.takeIf { !it.isJsonNull && it.isJsonArray }
                    ?.asJsonArray?.mapNotNull { if (it.isJsonNull) null else it.asString } ?: emptyList()

            val keywords = strArr("key").filter { it.isNotBlank() }
            val disable = bool("disable")
            val uid = int("uid", nextUid)
            nextUid = maxOf(nextUid, uid + 1)
            val id = if (idStr.isBlank() || idStr == "0") System.currentTimeMillis().toString() else idStr
            result.add(
                WorldInfoEntry(
                    id = id,
                    keywords = keywords,
                    content = str("content"),
                    enabled = !disable,
                    uid = uid,
                    keysecondary = strArr("keysecondary"),
                    constant = bool("constant"),
                    order = int("order", 100),
                    depth = int("depth", 4),
                    comment = str("comment"),
                    selective = bool("selective"),
                    disable = disable,
                    // ---- ST v2 高级字段（camelCase）----
                    selectiveLogic = int("selectiveLogic", 0),
                    group = str("group"),
                    probability = int("probability", 100),
                    useProbability = bool("useProbability"),
                    delayUntilRecursion = int("delayUntilRecursion", 0),
                    preventRecursion = bool("preventRecursion"),
                    allowRecursion = if (obj.has("allowRecursion")) bool("allowRecursion") else true,
                    excludeRecursion = bool("excludeRecursion"),
                    keysContainedIn = str("keysContainedIn").ifBlank { "chat" },
                    position = int("position", 0),
                    weight = int("weight", 0)
                )
            )
        }
        result
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * 内部条目列表 → SillyTavern World Info JSON（kind:0 标准格式）。
 * disable 由本地 enabled 反推：!enabled → disable=true，保证导出给 SillyTavern 语义一致。
 */
private fun buildSillyTavernWorldInfo(entries: List<WorldInfoEntry>): String {
    val root = JsonObject()
    root.addProperty("kind", 0)
    val entriesObj = JsonObject()
    entries.forEachIndexed { index, e ->
        val obj = JsonObject()
        obj.addProperty("uid", if (e.uid > 0) e.uid else index + 1)
        val keyArr = JsonArray().apply { e.keywords.forEach { add(it) } }
        val keySecArr = JsonArray().apply { e.keysecondary.forEach { add(it) } }
        obj.add("key", keyArr)
        obj.add("keysecondary", keySecArr)
        obj.addProperty("comment", e.comment)
        obj.addProperty("content", e.content)
        obj.addProperty("constant", e.constant)
        obj.addProperty("selective", e.selective)
        obj.addProperty("order", e.order)
        obj.addProperty("disable", !e.enabled)
        obj.addProperty("depth", e.depth)
        obj.addProperty("enabled", e.enabled)
        // ---- ST v2 高级字段（camelCase）----
        obj.addProperty("selectiveLogic", e.selectiveLogic)
        obj.addProperty("group", e.group)
        obj.addProperty("probability", e.probability)
        obj.addProperty("useProbability", e.useProbability)
        obj.addProperty("delayUntilRecursion", e.delayUntilRecursion)
        obj.addProperty("preventRecursion", e.preventRecursion)
        obj.addProperty("allowRecursion", e.allowRecursion)
        obj.addProperty("excludeRecursion", e.excludeRecursion)
        obj.addProperty("keysContainedIn", e.keysContainedIn)
        obj.addProperty("position", e.position)
        obj.addProperty("weight", e.weight)
        entriesObj.add(e.id, obj)
    }
    root.add("entries", entriesObj)
    return root.toString()
}
