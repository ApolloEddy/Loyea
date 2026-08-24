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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.ui.chat.ChatViewModel
import com.loyea.context.core.WorldInfoConfig
import com.loyea.context.core.WorldInfoEntry
import com.loyea.context.core.WorldInfoBook
import com.loyea.plugins.tavern.core.TavernWorldBookCodec
import com.loyea.context.core.WorldInfoInsertionOrder
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

    fun saveEntries(updated: List<WorldInfoEntry>) {
        val existingBook = if (scope == WorldInfoScope.SESSION) {
            viewModel?.sessionWorldInfo?.value
        } else {
            null
        } ?: WorldInfoBook(
            entries = entries,
            config = if (scope == WorldInfoScope.GLOBAL) {
                viewModel?.worldInfoConfig?.value ?: WorldInfoConfig()
            } else {
                viewModel?.sessionWorldInfo?.value?.config
                    ?: viewModel?.worldInfoConfig?.value
                    ?: WorldInfoConfig()
            }
        )
        viewModel?.saveWorldInfoBook(existingBook.copy(entries = updated), scope)
    }

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
            viewModel?.saveWorldInfoBook(imported, scope)
            Toast.makeText(context, if (isEn) "Imported ${imported.entries.size} entries" else "已导入 ${imported.entries.size} 条世界观条目", Toast.LENGTH_SHORT).show()
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
            val currentBook = if (scope == WorldInfoScope.SESSION) {
                viewModel?.sessionWorldInfo?.value
                    ?: WorldInfoBook(entries = entries, config = viewModel?.worldInfoConfig?.value ?: WorldInfoConfig())
            } else {
                WorldInfoBook(
                    entries = entries,
                    config = viewModel?.worldInfoConfig?.value ?: WorldInfoConfig()
                )
            }
            val json = buildSillyTavernWorldInfo(currentBook)
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
                                    saveEntries(updated)
                                },
                                onDelete = {
                                    val updated = entries.filter { it.id != entry.id }
                                    saveEntries(updated)
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
                saveEntries(updated)
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
 * 条目编辑对话框：长表单（verticalScroll + 高度上限），覆盖匹配、计时、注入位置、
 * 分组、全局扫描标记和 extensions 等字段。常用区 + AdvancedSection 折叠高级区。
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
    var useRegexInput by remember { mutableStateOf(editingEntry?.useRegex ?: false) }
    var caseSensitiveInput by remember { mutableStateOf(editingEntry?.caseSensitive ?: false) }
    var matchWholeWordsInput by remember { mutableStateOf(editingEntry?.matchWholeWords ?: false) }
    var positionTypeInput by remember { mutableStateOf(editingEntry?.positionType ?: "legacy") }
    var injectionDepthInput by remember { mutableStateOf(editingEntry?.injectionDepth?.toString() ?: "0") }
    var roleInput by remember { mutableStateOf(editingEntry?.role.orEmpty()) }
    var outletNameInput by remember { mutableStateOf(editingEntry?.outletName.orEmpty()) }
    var groupOverrideInput by remember { mutableStateOf(editingEntry?.groupOverride ?: false) }
    var groupWeightInput by remember { mutableStateOf(editingEntry?.groupWeight?.toString() ?: "100") }
    var useGroupScoringInput by remember { mutableStateOf(editingEntry?.useGroupScoring ?: false) }
    var priorityInput by remember { mutableStateOf(editingEntry?.priority?.toString().orEmpty()) }
    var scanDepthOverrideInput by remember { mutableStateOf(editingEntry?.scanDepthOverride?.toString().orEmpty()) }
    var stickyInput by remember { mutableStateOf(editingEntry?.sticky?.toString() ?: "0") }
    var cooldownInput by remember { mutableStateOf(editingEntry?.cooldown?.toString() ?: "0") }
    var delayInput by remember { mutableStateOf(editingEntry?.delay?.toString() ?: "0") }
    var triggersInput by remember { mutableStateOf(editingEntry?.triggers?.joinToString(", ").orEmpty()) }
    var automationIdInput by remember { mutableStateOf(editingEntry?.automationId.orEmpty()) }
    var vectorizedInput by remember { mutableStateOf(editingEntry?.vectorized ?: false) }
    var matchPersonaDescriptionInput by remember { mutableStateOf(editingEntry?.matchPersonaDescription ?: false) }
    var matchCharacterDescriptionInput by remember { mutableStateOf(editingEntry?.matchCharacterDescription ?: false) }
    var matchCharacterPersonalityInput by remember { mutableStateOf(editingEntry?.matchCharacterPersonality ?: false) }
    var matchCharacterDepthPromptInput by remember { mutableStateOf(editingEntry?.matchCharacterDepthPrompt ?: false) }
    var matchScenarioInput by remember { mutableStateOf(editingEntry?.matchScenario ?: false) }
    var matchCreatorNotesInput by remember { mutableStateOf(editingEntry?.matchCreatorNotes ?: false) }
    var ignoreBudgetInput by remember { mutableStateOf(editingEntry?.ignoreBudget ?: false) }
    var characterFilterNamesInput by remember { mutableStateOf(editingEntry?.characterFilterNames?.joinToString(", ").orEmpty()) }
    var characterFilterTagsInput by remember { mutableStateOf(editingEntry?.characterFilterTags?.joinToString(", ").orEmpty()) }
    var characterFilterExcludeInput by remember { mutableStateOf(editingEntry?.characterFilterExclude ?: false) }
    var addMemoInput by remember { mutableStateOf(editingEntry?.addMemo ?: true) }
    var displayIndexInput by remember { mutableStateOf(editingEntry?.displayIndex?.toString() ?: "0") }
    var extensionsJsonInput by remember { mutableStateOf(editingEntry?.extensionsJson.orEmpty()) }

    val extensionsJsonValid = extensionsJsonInput.isBlank() || runCatching {
        JsonParser.parseString(extensionsJsonInput).isJsonObject
    }.getOrDefault(false)

    val positionTypeOptions = listOf(
        "legacy" to (if (isEn) "Legacy / global position" else "兼容旧版 / 全局位置"),
        "before_char" to (if (isEn) "Before character definitions" else "角色定义之前"),
        "after_char" to (if (isEn) "After character definitions" else "角色定义之后"),
        "an_top" to (if (isEn) "Author note top" else "作者注释顶部"),
        "an_bottom" to (if (isEn) "Author note bottom" else "作者注释底部"),
        "at_depth" to (if (isEn) "At message depth" else "消息深度注入"),
        "em_top" to (if (isEn) "Example messages top" else "示例消息顶部"),
        "em_bottom" to (if (isEn) "Example messages bottom" else "示例消息底部"),
        "outlet" to (if (isEn) "Named outlet" else "命名出口")
    )

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
                    SwitchRow(
                        checked = useRegexInput,
                        onCheckedChange = { useRegexInput = it },
                        label = if (isEn) "Use regex for keywords" else "关键词使用正则表达式",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = caseSensitiveInput,
                        onCheckedChange = { caseSensitiveInput = it },
                        label = if (isEn) "Case sensitive" else "区分大小写",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = matchWholeWordsInput,
                        onCheckedChange = { matchWholeWordsInput = it },
                        label = if (isEn) "Match whole words" else "匹配完整单词",
                        isEn = isEn
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DropdownField(
                        label = if (isEn) "Insertion position" else "注入位置",
                        options = positionTypeOptions,
                        current = positionTypeInput,
                        isEn = isEn,
                        onValueChange = { positionTypeInput = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    NumberTextField(
                        value = injectionDepthInput,
                        onValueChange = { injectionDepthInput = it },
                        label = if (isEn) "Injection depth" else "注入消息深度",
                        modifier = Modifier.fillMaxWidth()
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = roleInput,
                            onValueChange = { roleInput = it },
                            label = { Text(if (isEn) "Role at depth" else "深度注入角色") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedTextField(
                            value = outletNameInput,
                            onValueChange = { outletNameInput = it },
                            label = { Text(if (isEn) "Outlet name" else "命名出口") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        NumberTextField(
                            value = groupWeightInput,
                            onValueChange = { groupWeightInput = it },
                            label = if (isEn) "Group weight" else "分组权重",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        NumberTextField(
                            value = priorityInput,
                            onValueChange = { priorityInput = it },
                            label = if (isEn) "Priority (optional)" else "优先级（可选）",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        NumberTextField(
                            value = scanDepthOverrideInput,
                            onValueChange = { scanDepthOverrideInput = it },
                            label = if (isEn) "Scan depth override" else "扫描深度覆盖",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        NumberTextField(
                            value = stickyInput,
                            onValueChange = { stickyInput = it },
                            label = if (isEn) "Sticky turns" else "粘滞回合",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        NumberTextField(
                            value = cooldownInput,
                            onValueChange = { cooldownInput = it },
                            label = if (isEn) "Cooldown turns" else "冷却回合",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        NumberTextField(
                            value = delayInput,
                            onValueChange = { delayInput = it },
                            label = if (isEn) "Message delay" else "消息延迟",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = triggersInput,
                        onValueChange = { triggersInput = it },
                        label = { Text(if (isEn) "Triggers (comma separated)" else "触发器 / triggers（逗号分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = characterFilterNamesInput,
                        onValueChange = { characterFilterNamesInput = it },
                        label = { Text(if (isEn) "Character filter names" else "角色过滤名称（逗号分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = characterFilterTagsInput,
                        onValueChange = { characterFilterTagsInput = it },
                        label = { Text(if (isEn) "Character filter tags" else "角色过滤标签（逗号分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(
                        checked = characterFilterExcludeInput,
                        onCheckedChange = { characterFilterExcludeInput = it },
                        label = if (isEn) "Exclude matching characters/tags" else "排除匹配的角色/标签",
                        isEn = isEn
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        NumberTextField(
                            value = displayIndexInput,
                            onValueChange = { displayIndexInput = it },
                            label = if (isEn) "Display index" else "显示序号",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        SwitchRow(
                            checked = addMemoInput,
                            onCheckedChange = { addMemoInput = it },
                            label = if (isEn) "Add memo" else "保留备注标记",
                            isEn = isEn,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = automationIdInput,
                        onValueChange = { automationIdInput = it },
                        label = { Text(if (isEn) "Automation ID (stored only)" else "自动化 ID（仅保存，不自动执行）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(
                        checked = groupOverrideInput,
                        onCheckedChange = { groupOverrideInput = it },
                        label = if (isEn) "Group override" else "覆盖同组竞争",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = useGroupScoringInput,
                        onCheckedChange = { useGroupScoringInput = it },
                        label = if (isEn) "Use group scoring" else "启用分组评分",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = vectorizedInput,
                        onCheckedChange = { vectorizedInput = it },
                        label = if (isEn) "Vectorized flag (lexical fallback)" else "向量化标记（当前使用词法回退）",
                        isEn = isEn
                    )
                    Text(
                        text = if (isEn) "Global scan fields" else "全局扫描字段",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    SwitchRow(
                        checked = matchPersonaDescriptionInput,
                        onCheckedChange = { matchPersonaDescriptionInput = it },
                        label = if (isEn) "Match persona description" else "扫描用户 Persona 描述",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = matchCharacterDescriptionInput,
                        onCheckedChange = { matchCharacterDescriptionInput = it },
                        label = if (isEn) "Match character description" else "扫描角色描述",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = matchCharacterPersonalityInput,
                        onCheckedChange = { matchCharacterPersonalityInput = it },
                        label = if (isEn) "Match character personality" else "扫描角色性格",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = matchCharacterDepthPromptInput,
                        onCheckedChange = { matchCharacterDepthPromptInput = it },
                        label = if (isEn) "Match character depth prompt" else "扫描角色深度提示词",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = matchScenarioInput,
                        onCheckedChange = { matchScenarioInput = it },
                        label = if (isEn) "Match scenario" else "扫描场景",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = matchCreatorNotesInput,
                        onCheckedChange = { matchCreatorNotesInput = it },
                        label = if (isEn) "Match creator notes" else "扫描创作者备注",
                        isEn = isEn
                    )
                    SwitchRow(
                        checked = ignoreBudgetInput,
                        onCheckedChange = { ignoreBudgetInput = it },
                        label = if (isEn) "Ignore token budget" else "忽略 token 预算",
                        isEn = isEn
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = commentInput,
                    onValueChange = { commentInput = it },
                    label = { Text(if (isEn) "Comment (optional)" else "备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = extensionsJsonInput,
                    onValueChange = { extensionsJsonInput = it },
                    label = { Text(if (isEn) "Extensions JSON (optional)" else "扩展字段 JSON（可选）") },
                    minLines = 3,
                    isError = !extensionsJsonValid,
                    supportingText = {
                        if (!extensionsJsonValid) {
                            Text(if (isEn) "Must be a valid JSON object" else "必须是有效的 JSON 对象")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val keywords = keywordsInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (contentInput.isBlank() || !extensionsJsonValid) {
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
                            weight = weightInput.toIntOrNull() ?: base?.weight ?: 0,
                            useRegex = useRegexInput,
                            caseSensitive = caseSensitiveInput,
                            matchWholeWords = matchWholeWordsInput,
                            positionType = positionTypeInput,
                            injectionDepth = injectionDepthInput.toIntOrNull() ?: base?.injectionDepth ?: 0,
                            role = roleInput.trim().ifBlank { null },
                            outletName = outletNameInput.trim().ifBlank { null },
                            groupOverride = groupOverrideInput,
                            groupWeight = groupWeightInput.toIntOrNull() ?: base?.groupWeight ?: 100,
                            useGroupScoring = useGroupScoringInput,
                            priority = priorityInput.toIntOrNull(),
                            scanDepthOverride = scanDepthOverrideInput.toIntOrNull()?.takeIf { it > 0 },
                            sticky = stickyInput.toIntOrNull() ?: base?.sticky ?: 0,
                            cooldown = cooldownInput.toIntOrNull() ?: base?.cooldown ?: 0,
                            delay = delayInput.toIntOrNull() ?: base?.delay ?: 0,
                            triggers = triggersInput.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                            extensionsJson = extensionsJsonInput.trim().ifBlank { "{}" },
                            automationId = automationIdInput.trim(),
                            vectorized = vectorizedInput,
                            matchPersonaDescription = matchPersonaDescriptionInput,
                            matchCharacterDescription = matchCharacterDescriptionInput,
                            matchCharacterPersonality = matchCharacterPersonalityInput,
                            matchCharacterDepthPrompt = matchCharacterDepthPromptInput,
                            matchScenario = matchScenarioInput,
                            matchCreatorNotes = matchCreatorNotesInput,
                            ignoreBudget = ignoreBudgetInput,
                            characterFilterNames = characterFilterNamesInput.split(",")
                                .map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                            characterFilterTags = characterFilterTagsInput.split(",")
                                .map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                            characterFilterExclude = characterFilterExcludeInput,
                            addMemo = addMemoInput,
                            displayIndex = displayIndexInput.toIntOrNull() ?: base?.displayIndex ?: 0,
                            rawJson = base?.rawJson
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
    var budgetCapInput by remember { mutableStateOf(config.budgetCap.toString()) }
    var recursionDepthInput by remember { mutableStateOf(config.recursionDepthCap.toString()) }
    var allowRecursionInput by remember { mutableStateOf(config.allowRecursion) }
    var emitHeadersInput by remember { mutableStateOf(config.emitGroupHeaders) }
    var caseSensitiveInput by remember { mutableStateOf(config.caseSensitive) }
    var matchWholeWordsInput by remember { mutableStateOf(config.matchWholeWords) }
    var useGroupScoringInput by remember { mutableStateOf(config.useGroupScoring) }
    var includeNamesInput by remember { mutableStateOf(config.includeNames) }

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
                Spacer(modifier = Modifier.height(10.dp))
                NumberTextField(
                    value = budgetCapInput,
                    onValueChange = { budgetCapInput = it },
                    label = if (isEn) "Budget cap (0 = no extra cap)" else "预算上限（0 = 不额外限制）",
                    modifier = Modifier.fillMaxWidth()
                )
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
                SwitchRow(
                    checked = caseSensitiveInput,
                    onCheckedChange = { caseSensitiveInput = it },
                    label = if (isEn) "Case-sensitive keywords" else "关键词区分大小写",
                    isEn = isEn
                )
                SwitchRow(
                    checked = matchWholeWordsInput,
                    onCheckedChange = { matchWholeWordsInput = it },
                    label = if (isEn) "Match whole words" else "匹配完整单词",
                    isEn = isEn
                )
                SwitchRow(
                    checked = useGroupScoringInput,
                    onCheckedChange = { useGroupScoringInput = it },
                    label = if (isEn) "Use inclusion-group scoring" else "启用分组关键词命中评分",
                    isEn = isEn
                )
                SwitchRow(
                    checked = includeNamesInput,
                    onCheckedChange = { includeNamesInput = it },
                    label = if (isEn) "Include participant names in World Info scan" else "世界书扫描包含参与者名称",
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
                            tokenBudget = tokenBudgetInput.toLongOrNull()?.coerceAtLeast(0) ?: config.tokenBudget,
                            recursionDepthCap = recursionDepthInput.toIntOrNull()?.coerceAtLeast(0) ?: config.recursionDepthCap,
                            allowRecursion = allowRecursionInput,
                            emitGroupHeaders = emitHeadersInput,
                            caseSensitive = caseSensitiveInput,
                            matchWholeWords = matchWholeWordsInput,
                            useGroupScoring = useGroupScoringInput,
                            budgetCap = budgetCapInput.toLongOrNull()?.coerceAtLeast(0) ?: config.budgetCap,
                            includeNames = includeNamesInput
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
    isEn: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
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
 * 同时兼容 ST native 的 entries object、部分导出器产生的 entries array，以及
 * camelCase/snake_case 两套字段名；未知字段不会被误解释，extensions 原样保留。
 */
private fun parseSillyTavernWorldInfo(json: String): WorldInfoBook? = TavernWorldBookCodec.parse(json)

/**
 * Kept as a compatibility reference for old serialized variants while the
 * active importer delegates to the single shared TavernWorldBookCodec.
 */
@Suppress("UNUSED_PARAMETER")
private fun parseSillyTavernWorldInfoLegacy(json: String): WorldInfoBook? {
    return try {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) return null
        val rootObj = root.asJsonObject
        val entriesElement = rootObj["entries"] ?: return null
        val items: List<Pair<String, JsonElement>> = when {
            entriesElement.isJsonObject -> entriesElement.asJsonObject.entrySet().map { it.key to it.value }
            entriesElement.isJsonArray -> entriesElement.asJsonArray.mapIndexed { index, value -> index.toString() to value }
            else -> emptyList()
        }
        if (items.isEmpty()) return WorldInfoBook(entries = emptyList())

        fun JsonObject.first(vararg names: String): JsonElement? = names.asSequence()
            .mapNotNull { get(it) }
            .firstOrNull { !it.isJsonNull }

        fun JsonObject.str(vararg names: String): String =
            first(*names)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

        fun JsonObject.bool(vararg names: String): Boolean? =
            first(*names)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        fun JsonObject.intOrNull(vararg names: String): Int? =
            first(*names)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

        fun JsonObject.int(def: Int, vararg names: String): Int = intOrNull(*names) ?: def

        fun JsonObject.strArr(vararg names: String): List<String> {
            val value = first(*names) ?: return emptyList()
            if (value.isJsonArray) {
                return value.asJsonArray.mapNotNull {
                    it.takeIf { element -> element.isJsonPrimitive && element.asJsonPrimitive.isString }?.asString
                }
            }
            return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.let(::listOf) ?: emptyList()
        }

        fun positionName(index: Int): String = when (index) {
            0 -> "before_char"
            1 -> "after_char"
            2 -> "an_top"
            3 -> "an_bottom"
            4 -> "at_depth"
            5 -> "em_top"
            6 -> "em_bottom"
            7 -> "outlet"
            else -> "after_char"
        }

        val result = mutableListOf<WorldInfoEntry>()
        var nextUid = 0
        items.forEach { (idStr, entryEl) ->
            if (!entryEl.isJsonObject) return@forEach
            val obj = entryEl.asJsonObject
            val extensionObj = obj.get("extensions")?.takeIf { it.isJsonObject }?.asJsonObject
            fun extensionFirst(vararg names: String): JsonElement? = extensionObj?.let { ext ->
                names.asSequence().mapNotNull { ext[it] }.firstOrNull { !it.isJsonNull }
            }
            fun extensionString(vararg names: String): String =
                extensionFirst(*names)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            fun extensionBoolean(vararg names: String): Boolean? =
                extensionFirst(*names)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            fun extensionInt(vararg names: String): Int? =
                extensionFirst(*names)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            fun extensionStrings(vararg names: String): List<String> =
                extensionFirst(*names)?.let { value ->
                    if (value.isJsonArray) {
                        value.asJsonArray.mapNotNull { item ->
                            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                        }
                    } else {
                        value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                            ?.let(::listOf).orEmpty()
                    }
                }.orEmpty()
            val keywords = obj.strArr("key", "keys").filter { it.isNotBlank() }
            val disable = obj.bool("disable") ?: false
            val explicitEnabled = obj.bool("enabled")
            val enabled = (explicitEnabled ?: true) && !disable
            val uid = obj.int(nextUid, "uid", "id")
            nextUid = maxOf(nextUid, uid + 1)
            val stableId = idStr.takeIf { it.isNotBlank() && it != "0" } ?: "uid_$uid"
            val positionElement = obj.first("position")
            val positionIndex = positionElement?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber
            }?.asInt ?: extensionInt("position")
            val explicitPositionType = obj.str("positionType", "position_type").ifBlank { null }
                ?: extensionString("positionType", "position_type").ifBlank { null }
            val positionType = explicitPositionType ?: if (positionElement == null && positionIndex == null) {
                "legacy"
            } else positionElement?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }?.asString?.ifBlank { null } ?: positionName(positionIndex ?: 0)
            val scanDepth = obj.intOrNull("scanDepth", "scan_depth") ?: extensionInt("scanDepth", "scan_depth")
            result.add(
                WorldInfoEntry(
                    id = stableId,
                    keywords = keywords,
                    content = obj.str("content"),
                    enabled = enabled,
                    uid = uid,
                    keysecondary = obj.strArr("keysecondary", "secondary_keys", "secondaryKeys"),
                    constant = obj.bool("constant") ?: false,
                    order = obj.int(100, "order", "insertion_order", "insertionOrder"),
                    depth = obj.intOrNull("depth") ?: extensionInt("depth") ?: 4,
                    comment = obj.str("comment", "name"),
                    selective = obj.bool("selective") ?: false,
                    disable = disable,
                    selectiveLogic = obj.intOrNull("selectiveLogic", "selective_logic")
                        ?: extensionInt("selectiveLogic", "selective_logic") ?: 0,
                    group = obj.str("group").ifBlank { extensionString("group") },
                    probability = obj.intOrNull("probability") ?: extensionInt("probability") ?: 100,
                    // ST's native default is enabled probability gating; with probability=100
                    // this remains byte/behavior compatible for ordinary entries.
                    useProbability = obj.bool("useProbability", "use_probability")
                        ?: extensionBoolean("useProbability", "use_probability") ?: true,
                    delayUntilRecursion = obj.intOrNull("delayUntilRecursion", "delay_until_recursion")
                        ?: extensionInt("delayUntilRecursion", "delay_until_recursion") ?: 0,
                    preventRecursion = obj.bool("preventRecursion", "prevent_recursion")
                        ?: extensionBoolean("preventRecursion", "prevent_recursion") ?: false,
                    allowRecursion = obj.bool("allowRecursion", "allow_recursion")
                        ?: extensionBoolean("allowRecursion", "allow_recursion") ?: true,
                    excludeRecursion = obj.bool("excludeRecursion", "exclude_recursion")
                        ?: extensionBoolean("excludeRecursion", "exclude_recursion") ?: false,
                    keysContainedIn = obj.str("keysContainedIn", "keys_contained_in")
                        .ifBlank { extensionString("keysContainedIn", "keys_contained_in") }
                        .ifBlank { "chat" },
                    position = positionIndex ?: 0,
                    weight = obj.int(0, "weight"),
                    useRegex = obj.bool("useRegex", "use_regex")
                        ?: extensionBoolean("useRegex", "use_regex") ?: false,
                    caseSensitive = obj.bool("caseSensitive", "case_sensitive")
                        ?: extensionBoolean("caseSensitive", "case_sensitive"),
                    matchWholeWords = obj.bool("matchWholeWords", "match_whole_words")
                        ?: extensionBoolean("matchWholeWords", "match_whole_words"),
                    positionType = positionType,
                    injectionDepth = obj.intOrNull("injectionDepth", "injection_depth")
                        ?: extensionInt("injectionDepth", "injection_depth")
                        ?: obj.intOrNull("depth")
                        ?: extensionInt("depth") ?: 0,
                    role = obj.str("role").ifBlank { extensionString("role") }.ifBlank { null },
                    outletName = obj.str("outletName", "outlet_name")
                        .ifBlank { extensionString("outletName", "outlet_name") }.ifBlank { null },
                    groupOverride = obj.bool("groupOverride", "group_override")
                        ?: extensionBoolean("groupOverride", "group_override") ?: false,
                    groupWeight = obj.intOrNull("groupWeight", "group_weight")
                        ?: extensionInt("groupWeight", "group_weight") ?: 100,
                    useGroupScoring = obj.bool("useGroupScoring", "use_group_scoring")
                        ?: extensionBoolean("useGroupScoring", "use_group_scoring") ?: false,
                    priority = obj.intOrNull("priority") ?: extensionInt("priority"),
                    scanDepthOverride = scanDepth,
                    sticky = obj.intOrNull("sticky") ?: extensionInt("sticky") ?: 0,
                    cooldown = obj.intOrNull("cooldown") ?: extensionInt("cooldown") ?: 0,
                    delay = obj.intOrNull("delay") ?: extensionInt("delay") ?: 0,
                    triggers = obj.strArr("triggers").ifEmpty { extensionStrings("triggers") },
                    extensionsJson = extensionObj?.toString() ?: "{}",
                    automationId = obj.str("automationId", "automation_id")
                        .ifBlank { extensionObj?.str("automationId", "automation_id").orEmpty() },
                    vectorized = obj.bool("vectorized")
                        ?: extensionObj?.bool("vectorized") ?: false,
                    matchPersonaDescription = obj.bool("matchPersonaDescription", "match_persona_description")
                        ?: extensionObj?.bool("matchPersonaDescription", "match_persona_description") ?: false,
                    matchCharacterDescription = obj.bool("matchCharacterDescription", "match_character_description")
                        ?: extensionObj?.bool("matchCharacterDescription", "match_character_description") ?: false,
                    matchCharacterPersonality = obj.bool("matchCharacterPersonality", "match_character_personality")
                        ?: extensionObj?.bool("matchCharacterPersonality", "match_character_personality") ?: false,
                    matchCharacterDepthPrompt = obj.bool("matchCharacterDepthPrompt", "match_character_depth_prompt")
                        ?: extensionObj?.bool("matchCharacterDepthPrompt", "match_character_depth_prompt") ?: false,
                    matchScenario = obj.bool("matchScenario", "match_scenario")
                        ?: extensionObj?.bool("matchScenario", "match_scenario") ?: false,
                    matchCreatorNotes = obj.bool("matchCreatorNotes", "match_creator_notes")
                        ?: extensionObj?.bool("matchCreatorNotes", "match_creator_notes") ?: false,
                    ignoreBudget = obj.bool("ignoreBudget", "ignore_budget")
                        ?: extensionObj?.bool("ignoreBudget", "ignore_budget") ?: false
                )
            )
        }
        val config = TavernWorldBookCodec.parse(json)?.config ?: WorldInfoConfig()
        WorldInfoBook(entries = result, config = config)
    } catch (_: Exception) {
        null
    }
}

/** 内部条目列表 → SillyTavern World Info JSON（kind:0 标准格式）。 */
private fun buildSillyTavernWorldInfo(
    book: WorldInfoBook
): String {
    val entries = book.entries
    val config = book.config
    val root = runCatching {
        JsonParser.parseString(book.rawJson.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull() ?: JsonObject()
    root.addProperty("kind", 0)
    if (book.name.isNotBlank()) root.addProperty("name", book.name)
    if (book.description.isNotBlank()) root.addProperty("description", book.description)
    root.addProperty("scan_depth", config.scanDepth)
    root.addProperty("token_budget", config.tokenBudget)
    root.addProperty("recursive_scanning", config.allowRecursion)
    root.addProperty("position", config.position)
    root.addProperty("recursion_depth_cap", config.recursionDepthCap)
    root.addProperty("case_sensitive", config.caseSensitive)
    root.addProperty("match_whole_words", config.matchWholeWords)
    root.addProperty("use_group_scoring", config.useGroupScoring)
    root.addProperty("budget_cap", config.budgetCap)
    root.addProperty("loyea_insertion_order_mode", config.insertionOrderMode.name)
    root.addProperty("loyea_emit_group_headers", config.emitGroupHeaders)
    root.add(
        "extensions",
        runCatching {
            JsonParser.parseString(book.extensionsJson).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: JsonObject()
    )
    val entriesObj = JsonObject()
    entries.forEachIndexed { index, e ->
        val obj = runCatching {
            JsonParser.parseString(e.rawJson.orEmpty()).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull() ?: JsonObject()
        obj.addProperty("uid", if (e.uid > 0) e.uid else index + 1)
        obj.add("key", JsonArray().apply { e.keywords.forEach(::add) })
        obj.add("keysecondary", JsonArray().apply { e.keysecondary.forEach(::add) })
        obj.addProperty("comment", e.comment)
        obj.addProperty("content", e.content)
        obj.addProperty("constant", e.constant)
        obj.addProperty("selective", e.selective)
        obj.addProperty("order", e.order)
        obj.addProperty("disable", !e.enabled || e.disable)
        obj.addProperty("depth", e.depth)
        obj.addProperty("enabled", e.enabled && !e.disable)
        obj.addProperty("selectiveLogic", e.selectiveLogic)
        obj.addProperty("group", e.group)
        obj.addProperty("probability", e.probability)
        obj.addProperty("useProbability", e.useProbability)
        obj.addProperty("delayUntilRecursion", e.delayUntilRecursion)
        obj.addProperty("preventRecursion", e.preventRecursion)
        obj.addProperty("allowRecursion", e.allowRecursion)
        obj.addProperty("excludeRecursion", e.excludeRecursion)
        obj.addProperty("keysContainedIn", e.keysContainedIn)
        // ST 原生 World Info 仍以数字 position 为主；positionType 作为无损扩展保留，
        // 这样 Tavern/新版 ST 能识别高级槽位，旧版 ST 也不会因为字符串 position 拒绝整本书。
        obj.addProperty("position", worldInfoPositionIndex(e.positionType, e.position))
        if (e.positionType != "legacy") obj.addProperty("positionType", e.positionType)
        obj.addProperty("weight", e.weight)
        obj.addProperty("useRegex", e.useRegex)
        e.caseSensitive?.let { obj.addProperty("caseSensitive", it) }
        e.matchWholeWords?.let { obj.addProperty("matchWholeWords", it) }
        obj.addProperty("injectionDepth", e.injectionDepth)
        e.role?.let { obj.addProperty("role", worldInfoRoleIndex(it)) }
        e.outletName?.let { obj.addProperty("outletName", it) }
        obj.addProperty("groupOverride", e.groupOverride)
        obj.addProperty("groupWeight", e.groupWeight)
        obj.addProperty("useGroupScoring", e.useGroupScoring)
        e.priority?.let { obj.addProperty("priority", it) }
        e.scanDepthOverride?.let { obj.addProperty("scanDepth", it) }
        obj.addProperty("sticky", e.sticky)
        obj.addProperty("cooldown", e.cooldown)
        obj.addProperty("delay", e.delay)
        obj.add("triggers", JsonArray().apply { e.triggers.forEach(::add) })
        obj.addProperty("automationId", e.automationId)
        obj.addProperty("vectorized", e.vectorized)
        obj.addProperty("matchPersonaDescription", e.matchPersonaDescription)
        obj.addProperty("matchCharacterDescription", e.matchCharacterDescription)
        obj.addProperty("matchCharacterPersonality", e.matchCharacterPersonality)
        obj.addProperty("matchCharacterDepthPrompt", e.matchCharacterDepthPrompt)
        obj.addProperty("matchScenario", e.matchScenario)
        obj.addProperty("matchCreatorNotes", e.matchCreatorNotes)
        obj.addProperty("ignoreBudget", e.ignoreBudget)
        if (e.characterFilterNames.isNotEmpty() || e.characterFilterTags.isNotEmpty() || e.characterFilterExclude) {
            obj.add("characterFilter", JsonObject().apply {
                add("names", JsonArray().also { values -> e.characterFilterNames.forEach(values::add) })
                add("tags", JsonArray().also { values -> e.characterFilterTags.forEach(values::add) })
                addProperty("isExclude", e.characterFilterExclude)
            })
        }
        obj.addProperty("addMemo", e.addMemo)
        obj.addProperty("displayIndex", e.displayIndex)
        runCatching {
            val extensionJson = JsonParser.parseString(e.extensionsJson).takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject()
            extensionJson.addProperty("automation_id", e.automationId)
            extensionJson.addProperty("vectorized", e.vectorized)
            extensionJson.addProperty("match_persona_description", e.matchPersonaDescription)
            extensionJson.addProperty("match_character_description", e.matchCharacterDescription)
            extensionJson.addProperty("match_character_personality", e.matchCharacterPersonality)
            extensionJson.addProperty("match_character_depth_prompt", e.matchCharacterDepthPrompt)
            extensionJson.addProperty("match_scenario", e.matchScenario)
            extensionJson.addProperty("match_creator_notes", e.matchCreatorNotes)
            extensionJson.addProperty("ignore_budget", e.ignoreBudget)
            obj.add("extensions", extensionJson)
        }
        entriesObj.add(e.id, obj)
    }
    root.add("entries", entriesObj)
    return root.toString()
}

private fun worldInfoPositionIndex(positionType: String, legacyPosition: Int): Int = when (
    positionType.lowercase().replace('-', '_')
) {
    "before_char", "before_character", "before_character_definitions" -> 0
    "after_char", "after_character", "after_character_definitions" -> 1
    "an_top", "antop", "author_note_top" -> 2
    "an_bottom", "anbottom", "author_note_bottom" -> 3
    "at_depth", "atdepth", "depth" -> 4
    "em_top", "emtop", "example_messages_top" -> 5
    "em_bottom", "embottom", "example_messages_bottom" -> 6
    "outlet" -> 7
    else -> legacyPosition
}

private fun worldInfoRoleIndex(role: String): Int = when (role.lowercase()) {
    "user", "1" -> 1
    "assistant", "2" -> 2
    else -> 0
}
