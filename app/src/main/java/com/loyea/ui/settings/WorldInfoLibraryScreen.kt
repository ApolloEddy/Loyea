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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.storage.worldinfo.WorldInfoBookDocument
import com.loyea.storage.worldinfo.WorldInfoBookOrigin
import com.loyea.storage.worldinfo.WorldInfoBookSummary
import com.loyea.ui.chat.ChatSession
import com.loyea.ui.chat.ChatViewModel
import com.loyea.ui.chat.WorldInfoConfig
import com.loyea.ui.chat.WorldInfoEntry
import com.loyea.ui.chat.WorldInfoInsertionOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 世界书统一书库（WorldInfo 2.0 Spec §6）。
 *
 * 列表页：全部书（自建/导入/角色卡）集中管理；全局生效书置顶；
 * 详情页：条目逐条开关（card 书写 override，owned 书直改条目）、ST 导入导出、
 * 生效域（全局生效 / 绑定会话）、匹配配置覆盖。
 * 条目编辑/配置弹窗与 ST JSON 解析导出沿用 0.7.1 WorldInfoSettings 的实现（W5 迁移后唯一真源）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldInfoLibraryScreen(
    viewModel: ChatViewModel?,
    appLanguage: String,
    onBackClick: () -> Unit,
    initialFocus: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isEn = appLanguage == "en"
    val scope = rememberCoroutineScope()

    var summaries by remember { mutableStateOf<List<WorldInfoBookSummary>?>(null) }
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    var focusConsumed by remember { mutableStateOf(initialFocus == null) }

    var showGlobalConfig by remember { mutableStateOf(false) }
    var renamingBook by remember { mutableStateOf<WorldInfoBookDocument?>(null) }
    var deletingSummary by remember { mutableStateOf<WorldInfoBookSummary?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    fun reload(focus: String? = null) {
        val vm = viewModel ?: return
        scope.launch(Dispatchers.IO) {
            val list = runCatching { vm.worldInfoLibrary.bookSummaries() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                summaries = list
                if (focus != null) {
                    val target = when {
                        focus.startsWith("char:") -> list.firstOrNull {
                            it.book.origin == WorldInfoBookOrigin.CARD &&
                                it.book.originCharacterId == focus.removePrefix("char:")
                        }
                        else -> list.firstOrNull { it.book.id == focus }
                    }
                    if (target != null) selectedBookId = target.book.id
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!focusConsumed) {
            focusConsumed = true
            reload(initialFocus)
        } else {
            reload()
        }
    }

    // 导入 SillyTavern World Info JSON → 新建 owned 书
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val vm = viewModel ?: return@rememberLauncherForActivityResult
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
            scope.launch(Dispatchers.IO) {
                runCatching { vm.worldInfoLibrary.createOwnedBook(name = "导入的世界书", entries = imported, imported = true) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (isEn) "Imported ${imported.size} entries" else "已导入 ${imported.size} 条条目", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, if (isEn) "Import failed" else "导入失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 导出（内容在点击导出时预取；owned 书直接构建，card 书实时快照）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json ?: "") }
            Toast.makeText(context, if (isEn) "Exported" else "已导出", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, if (isEn) "Export failed" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportBook(summary: WorldInfoBookSummary) {
        val vm = viewModel ?: return
        scope.launch(Dispatchers.IO) {
            val json = if (summary.book.isOwned) {
                buildSillyTavernWorldInfo(summary.book.entries)
            } else {
                runCatching { vm.worldInfoLibrary.loadCardBookEntries(summary.book.id) }.getOrNull()
                    ?.let { buildSillyTavernWorldInfo(it) }
            }
            withContext(Dispatchers.Main) {
                if (json == null) {
                    Toast.makeText(context, if (isEn) "Source card missing or unparsable" else "来源卡已删除或解析失败", Toast.LENGTH_SHORT).show()
                } else {
                    pendingExportJson = json
                    exportLauncher.launch("loyea_world_info.json")
                }
            }
        }
    }

    fun setGlobalActive(bookId: String?) {
        val vm = viewModel ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { vm.worldInfoLibrary.setGlobalActive(bookId) }
            withContext(Dispatchers.Main) { reload() }
        }
    }

    fun deleteBook(summary: WorldInfoBookSummary) {
        val vm = viewModel ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { vm.worldInfoLibrary.deleteBook(summary.book.id) }
            withContext(Dispatchers.Main) { reload() }
        }
    }

    val currentSummary = summaries?.firstOrNull { it.book.id == selectedBookId }

    if (currentSummary != null) {
        // ===== 详情页 =====
        WorldInfoBookDetailContent(
            viewModel = viewModel,
            bookId = currentSummary.book.id,
            isEn = isEn,
            onBack = { selectedBookId = null; reload() },
            onBookMutated = { reload() },
            onRename = { renamingBook = currentSummary.book },
            onExport = { exportBook(currentSummary) }
        )
        return
    }

    // ===== 列表页 =====
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "World Books" else "世界书", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = if (isEn) "Back" else "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // 全局默认匹配配置（未做书级覆盖的书继承它）
                    IconButton(onClick = { showGlobalConfig = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = if (isEn) "Default matching config" else "默认匹配配置",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    // 导入 ST JSON
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = if (isEn) "Import SillyTavern World Info" else "导入 SillyTavern 世界书",
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
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
            val list = summaries
            when {
                list == null -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                    strokeWidth = 3.dp
                )
                list.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isEn) "No world books yet.\nTap + to create, or import a SillyTavern world book."
                               else "还没有世界书。\n点右上角 + 新建，或导入 SillyTavern 世界书。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        lineHeight = 20.sp
                    )
                }
                else -> {
                    val sorted = list.sortedWith(
                        compareByDescending<WorldInfoBookSummary> { it.book.isGlobalActive }
                            .thenBy { it.book.createdAt }
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                        )
                    ) {
                        items(sorted, key = { it.book.id }) { summary ->
                            BookRow(
                                summary = summary,
                                isEn = isEn,
                                onClick = { selectedBookId = summary.book.id },
                                onSetGlobalActive = { setGlobalActive(summary.book.id) },
                                onUnsetGlobalActive = { setGlobalActive(null) },
                                onExport = { exportBook(summary) },
                                onRename = { renamingBook = summary.book },
                                onDelete = { deletingSummary = summary }
                            )
                        }
                    }
                }
            }

            // 新建书（浮动按钮区：右下角小圆钮，保持简约）
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = {
                    val vm = viewModel ?: return@SmallFloatingActionButton
                    scope.launch(Dispatchers.IO) {
                        runCatching { vm.worldInfoLibrary.createOwnedBook(name = if (isEn) "New book" else "新建世界书", entries = emptyList()) }
                        withContext(Dispatchers.Main) { reload() }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = if (isEn) "New book" else "新建世界书")
            }
        }
    }

    // ===== 列表页弹窗 =====
    if (showGlobalConfig && viewModel != null) {
        WorldInfoConfigDialog(
            config = viewModel.worldInfoConfig.value,
            isEn = isEn,
            onSave = { cfg ->
                viewModel.saveWorldInfoConfig(cfg)
                showGlobalConfig = false
            },
            onDismiss = { showGlobalConfig = false }
        )
    }

    renamingBook?.let { book ->
        RenameBookDialog(
            initialName = book.name,
            isEn = isEn,
            onSave = { newName ->
                val vm = viewModel
                val target = book
                renamingBook = null
                if (vm != null && newName.isNotBlank() && newName != target.name) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            vm.worldInfoLibrary.saveBook(
                                target.copy(name = newName.trim(), updatedAt = System.currentTimeMillis())
                            )
                        }
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
            },
            onDismiss = { renamingBook = null }
        )
    }

    deletingSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { deletingSummary = null },
            title = { Text(if (isEn) "Delete book?" else "删除世界书？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                val msg = if (summary.book.isOwned) {
                    val n = summary.book.sessionIds.size
                    if (n > 0) {
                        if (isEn) "Delete \"${summary.book.name}\"? It is bound to $n session(s); those sessions will fall back to default resolution (character card / global)."
                        else "删除《${summary.book.name}》？该书正绑定 $n 个会话，删除后这些会话回退默认解析（随角色 / 全局生效书）。"
                    } else {
                        if (isEn) "Delete \"${summary.book.name}\"? This cannot be undone."
                        else "删除《${summary.book.name}》？删除后不可恢复。"
                    }
                } else {
                    if (isEn) "Only removes \"${summary.book.name}\" from the library. The character card is untouched; the book re-appears next time the character chats (entry toggles will be reset)."
                    else "仅从书库移除《${summary.book.name}》，不影响角色卡本身；该角色的书会在下次对话时自动重新入库（条目开关将重置）。"
                }
                Text(msg, fontSize = 13.sp, lineHeight = 19.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = summary
                    deletingSummary = null
                    deleteBook(target)
                }) { Text(if (isEn) "Delete" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingSummary = null }) { Text(if (isEn) "Cancel" else "取消") }
            }
        )
    }
}

// =================== 列表行 ===================

@Composable
private fun BookRow(
    summary: WorldInfoBookSummary,
    isEn: Boolean,
    onClick: () -> Unit,
    onSetGlobalActive: () -> Unit,
    onUnsetGlobalActive: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val book = summary.book
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .let { m ->
                if (book.isGlobalActive) {
                    m.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                } else m
            },
        colors = CardDefaults.cardColors(
            containerColor = if (book.isGlobalActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (summary.sourceDeleted) {
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        } else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    BadgeChip(text = originBadgeText(book, isEn), isEn = isEn)
                    Spacer(modifier = Modifier.width(6.dp))
                    BadgeChip(
                        text = scopeBadgeText(summary, isEn),
                        isEn = isEn,
                        emphasized = book.isGlobalActive
                    )
                    if (summary.conflictingSessions.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        BadgeChip(
                            text = if (isEn) "Conflict" else "冲突",
                            isEn = isEn,
                            emphasized = true,
                            warning = true
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entrySummaryText(summary, isEn),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = if (isEn) "Actions" else "操作",
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (book.isGlobalActive) {
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Unset global active" else "取消全局生效", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onUnsetGlobalActive() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Set as global active" else "设为全局生效", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onSetGlobalActive() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isEn) "Export" else "导出", fontSize = 13.sp) },
                        onClick = { menuOpen = false; onExport() }
                    )
                    if (book.isOwned) {
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Rename" else "重命名", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onRename() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (isEn) "Delete" else "删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(
    text: String,
    isEn: Boolean,
    emphasized: Boolean = false,
    warning: Boolean = false
) {
    val bg = when {
        warning -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        emphasized -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
    }
    val fg = when {
        warning -> MaterialTheme.colorScheme.error
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text = text, fontSize = 10.sp, color = fg, maxLines = 1)
    }
}

private fun originBadgeText(book: WorldInfoBookDocument, isEn: Boolean): String = when (book.origin) {
    WorldInfoBookOrigin.CREATED -> if (isEn) "Created" else "自建"
    WorldInfoBookOrigin.IMPORTED -> if (isEn) "Imported" else "导入"
    WorldInfoBookOrigin.CARD -> if (isEn) "Card" else "角色卡"
}

private fun scopeBadgeText(summary: WorldInfoBookSummary, isEn: Boolean): String {
    val book = summary.book
    return when {
        summary.sourceDeleted -> if (isEn) "Source deleted" else "来源已删除"
        book.isGlobalActive && book.sessionIds.isNotEmpty() ->
            if (isEn) "Global · ${book.sessionIds.size} sess." else "全局生效 · 绑${book.sessionIds.size}会话"
        book.isGlobalActive -> if (isEn) "Global" else "全局生效"
        book.origin == WorldInfoBookOrigin.CARD -> if (isEn) "Follows character" else "随角色"
        book.sessionIds.isNotEmpty() ->
            if (isEn) "${book.sessionIds.size} session(s)" else "绑定 ${book.sessionIds.size} 会话"
        else -> if (isEn) "Inactive" else "未生效"
    }
}

private fun entrySummaryText(summary: WorldInfoBookSummary, isEn: Boolean): String {
    val parts = buildList {
        add(if (isEn) "${summary.totalEntries} entries" else "${summary.totalEntries} 条")
        if (summary.constantEntries > 0) add(if (isEn) "${summary.constantEntries} constant" else "${summary.constantEntries} 常驻")
        if (summary.disabledEntries > 0) add(if (isEn) "${summary.disabledEntries} off" else "${summary.disabledEntries} 已关")
    }
    return parts.joinToString(" · ")
}

// =================== 详情页 ===================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldInfoBookDetailContent(
    viewModel: ChatViewModel?,
    bookId: String,
    isEn: Boolean,
    onBack: () -> Unit,
    onBookMutated: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var book by remember(bookId) { mutableStateOf<WorldInfoBookDocument?>(null) }
    // card 书实时条目（null = 加载中或不可解析）
    var cardEntries by remember(bookId) { mutableStateOf<List<WorldInfoEntry>?>(null) }
    var loading by remember(bookId) { mutableStateOf(true) }

    var editingEntry by remember { mutableStateOf<WorldInfoEntry?>(null) }
    var showEntryEditor by remember { mutableStateOf(false) }
    var showConfigOverride by remember { mutableStateOf(false) }
    var showBindDialog by remember { mutableStateOf(false) }
    var deleteEntryTarget by remember { mutableStateOf<WorldInfoEntry?>(null) }

    fun reloadDetail() {
        val vm = viewModel ?: return
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { vm.worldInfoLibrary.loadBook(bookId) }.getOrNull()
            val entries = if (loaded != null && !loaded.isOwned) {
                runCatching { vm.worldInfoLibrary.loadCardBookEntries(bookId) }.getOrNull()
            } else null
            withContext(Dispatchers.Main) {
                book = loaded
                cardEntries = entries
                loading = false
            }
        }
    }

    LaunchedEffect(bookId) { reloadDetail() }

    fun mutateBook(transform: (WorldInfoBookDocument) -> WorldInfoBookDocument) {
        val vm = viewModel
        val current = book
        if (vm == null || current == null) return
        scope.launch(Dispatchers.IO) {
            val updated = transform(current)
            runCatching { vm.worldInfoLibrary.saveBook(updated) }
            withContext(Dispatchers.Main) {
                book = updated
                onBookMutated()
            }
        }
    }

    val currentBook = book

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentBook?.name ?: (if (isEn) "World Book" else "世界书"),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = if (isEn) "Back" else "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (currentBook?.isOwned == true) {
                        IconButton(onClick = { editingEntry = null; showEntryEditor = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = if (isEn) "Add entry" else "新建条目",
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when {
            loading || currentBook == null -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp
                )
            ) {
                // —— 头部信息 ——
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BadgeChip(text = originBadgeText(currentBook, isEn), isEn = isEn)
                            Spacer(modifier = Modifier.width(6.dp))
                            BadgeChip(text = scopeBadgeText(
                                WorldInfoBookSummary(currentBook, 0, 0, 0), isEn
                            ), isEn = isEn, emphasized = currentBook.isGlobalActive)
                            if (currentBook.isOwned) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = onRename, modifier = Modifier.size(26.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = if (isEn) "Rename" else "重命名",
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (isEn) "Updated ${formatTime(currentBook.updatedAt)}" else "更新于 ${formatTime(currentBook.updatedAt)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                        )
                        if (!currentBook.isOwned) {
                            val deleted = cardEntries == null
                            Text(
                                text = if (deleted) {
                                    if (isEn) "Source card deleted — content unavailable; export or delete this book."
                                    else "来源卡已删除——内容不可用；可导出留档或删除此书。"
                                } else {
                                    if (isEn) "Content is read live from the character card; toggles are stored as overrides."
                                    else "内容实时读取角色卡（重导自动更新）；开关以覆盖层存储，不改动卡原文。"
                                },
                                fontSize = 11.sp,
                                color = if (deleted) MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // —— 生效域 ——
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isEn) "Global active" else "全局生效",
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (isEn) "Used by sessions without a bound book or card" else "未被绑定书/角色卡覆盖的会话使用",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                    )
                                }
                                Switch(
                                    checked = currentBook.isGlobalActive,
                                    onCheckedChange = { checked ->
                                        val vm = viewModel ?: return@Switch
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { vm.worldInfoLibrary.setGlobalActive(if (checked) currentBook.id else null) }
                                            withContext(Dispatchers.Main) {
                                                reloadDetail()
                                                onBookMutated()
                                            }
                                        }
                                    }
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showBindDialog = true }
                                    .padding(vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isEn) "Bound sessions" else "绑定会话",
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (currentBook.sessionIds.isEmpty()) {
                                            if (isEn) "Not bound" else "未绑定"
                                        } else {
                                            if (isEn) "${currentBook.sessionIds.size} session(s) — highest priority in those sessions"
                                            else "${currentBook.sessionIds.size} 个会话——在这些会话中最高优先"
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // —— 匹配配置覆盖 ——
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = if (isEn) "Matching config" else "匹配配置",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (currentBook.config == null) {
                                    if (isEn) "Inheriting global defaults (gear icon on library page)" else "继承全局默认配置（书库页右上角齿轮）"
                                } else {
                                    if (isEn) "Custom override in effect" else "已自定义覆盖"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                            )
                            Row {
                                TextButton(onClick = { showConfigOverride = true }) {
                                    Text(if (currentBook.config == null) {
                                        if (isEn) "Customize" else "自定义"
                                    } else {
                                        if (isEn) "Edit" else "编辑"
                                    })
                                }
                                if (currentBook.config != null) {
                                    TextButton(onClick = {
                                        mutateBook { it.copy(config = null, updatedAt = System.currentTimeMillis()) }
                                    }) {
                                        Text(if (isEn) "Clear (inherit global)" else "清除覆盖（继承全局）")
                                    }
                                }
                            }
                        }
                    }
                }

                // —— 导出 ——
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onExport),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isEn) "Export as SillyTavern World Info JSON" else "导出为 SillyTavern 世界书 JSON",
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // —— 条目列表 ——
                item {
                    Text(
                        text = if (isEn) "Entries — changes take effect on the next turn" else "条目——改动在下一轮对话生效",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (currentBook.isOwned) {
                    if (currentBook.entries.isEmpty()) {
                        item {
                            Text(
                                text = if (isEn) "No entries yet. Tap + to add." else "暂无条目，点右上角 + 新建。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else {
                        items(currentBook.entries, key = { it.id }) { entry ->
                            WorldInfoEntryCard(
                                entry = entry,
                                isEn = isEn,
                                onEdit = { editingEntry = entry; showEntryEditor = true },
                                onToggle = { enabled ->
                                    val vm = viewModel ?: return@WorldInfoEntryCard
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { vm.worldInfoLibrary.setOwnedEntryEnabled(currentBook.id, entry.id, enabled) }
                                        withContext(Dispatchers.Main) { reloadDetail(); onBookMutated() }
                                    }
                                },
                                onDelete = { deleteEntryTarget = entry }
                            )
                        }
                    }
                } else {
                    val entries = cardEntries
                    if (entries == null) {
                        item {
                            Text(
                                text = if (isEn) "Content unavailable (source card deleted)." else "内容不可用（来源卡已删除）。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else if (entries.isEmpty()) {
                        item {
                            Text(
                                text = if (isEn) "The card's embedded book is empty." else "该卡的内嵌世界书为空。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else {
                        items(entries, key = { it.id }) { entry ->
                            WorldInfoEntryCard(
                                entry = entry,
                                isEn = isEn,
                                showEdit = true,
                                showDelete = false,
                                toggleEnabled = !entry.disable,
                                disabledHint = if (entry.disable) {
                                    if (isEn) "(disabled in card)" else "（卡内已禁用）"
                                } else null,
                                editedInPlace = currentBook?.entryOverrides?.containsKey(entry.uid) == true,
                                onEdit = { editingEntry = entry; showEntryEditor = true },
                                onToggle = { enabled ->
                                    val vm = viewModel ?: return@WorldInfoEntryCard
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { vm.worldInfoLibrary.setCardEntryOverride(currentBook.id, entry.uid, enabled) }
                                        withContext(Dispatchers.Main) { reloadDetail(); onBookMutated() }
                                    }
                                },
                                onDelete = {}
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== 详情页弹窗 =====
    if (showEntryEditor && currentBook != null) {
        val targetBook = currentBook
        WorldInfoEditDialog(
            editingEntry = editingEntry,
            isEn = isEn,
            entryOverridden = editingEntry?.let { targetBook.entryOverrides.containsKey(it.uid) } == true,
            onResetOverride = {
                val uid = editingEntry?.uid
                if (uid != null) {
                    showEntryEditor = false
                    scope.launch(Dispatchers.IO) {
                        runCatching { viewModel?.worldInfoLibrary?.resetCardEntryOverride(targetBook.id, uid) }
                        withContext(Dispatchers.Main) { reloadDetail(); onBookMutated() }
                    }
                }
            },
            onSave = { saved ->
                showEntryEditor = false
                if (targetBook.isOwned) {
                    mutateBook { b ->
                        val newEntries = if (b.entries.any { it.id == saved.id }) {
                            b.entries.map { if (it.id == saved.id) saved else it }
                        } else {
                            b.entries + saved
                        }
                        b.copy(entries = newEntries, updatedAt = System.currentTimeMillis())
                    }
                } else {
                    // 卡书：内容改动存 override 层，原卡文件不动
                    val uid = editingEntry?.uid
                    if (uid != null) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { viewModel?.worldInfoLibrary?.saveCardEntryOverride(targetBook.id, uid, saved) }
                            withContext(Dispatchers.Main) { reloadDetail(); onBookMutated() }
                        }
                    }
                }
            },
            onDismiss = { showEntryEditor = false }
        )
    }

    if (showConfigOverride && viewModel != null) {
        WorldInfoConfigDialog(
            config = currentBook?.config ?: viewModel.worldInfoConfig.value,
            isEn = isEn,
            onSave = { cfg ->
                showConfigOverride = false
                mutateBook { it.copy(config = cfg, updatedAt = System.currentTimeMillis()) }
            },
            onDismiss = { showConfigOverride = false }
        )
    }

    if (showBindDialog && viewModel != null && currentBook != null) {
        val bound = currentBook
        SessionBindDialog(
            sessions = viewModel.sessions.value,
            currentBindings = bound.sessionIds,
            isEn = isEn,
            onSave = { sessionIds ->
                showBindDialog = false
                val vm = viewModel
                scope.launch(Dispatchers.IO) {
                    runCatching { vm.worldInfoLibrary.setBookSessionBindings(bound.id, sessionIds) }
                    withContext(Dispatchers.Main) {
                        reloadDetail()
                        onBookMutated()
                    }
                }
            },
            onDismiss = { showBindDialog = false }
        )
    }

    deleteEntryTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteEntryTarget = null },
            title = { Text(if (isEn) "Delete entry?" else "删除条目？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text(if (isEn) "This entry will be removed from the book." else "该条目将从书中移除。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    deleteEntryTarget = null
                    mutateBook { b ->
                        b.copy(entries = b.entries.filter { it.id != t.id }, updatedAt = System.currentTimeMillis())
                    }
                }) { Text(if (isEn) "Delete" else "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntryTarget = null }) { Text(if (isEn) "Cancel" else "取消") }
            }
        )
    }
}

// =================== 会话绑定弹窗 ===================

@Composable
private fun SessionBindDialog(
    sessions: List<ChatSession>,
    currentBindings: List<String>,
    isEn: Boolean,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember { mutableStateOf(currentBindings.toMutableSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEn) "Bound sessions" else "绑定会话", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 420.dp)
            ) {
                Text(
                    text = if (isEn) "Bound sessions use this book with highest priority." else "被绑定的会话以最高优先使用本书。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (sessions.isEmpty()) {
                    Text(
                        text = if (isEn) "No sessions" else "暂无会话",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
                sessions.forEach { session ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (session.id in selected.value) selected.value.remove(session.id)
                                else selected.value.add(session.id)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = session.id in selected.value,
                            onCheckedChange = { checked ->
                                if (checked) selected.value.add(session.id)
                                else selected.value.remove(session.id)
                            }
                        )
                        Text(
                            text = session.title.ifBlank { session.id },
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected.value.toList()) }) { Text(if (isEn) "Save" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isEn) "Cancel" else "取消") }
        }
    )
}

// =================== 重命名弹窗 ===================

@Composable
private fun RenameBookDialog(
    initialName: String,
    isEn: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameInput by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEn) "Rename book" else "重命名世界书", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(nameInput) }) { Text(if (isEn) "Save" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isEn) "Cancel" else "取消") }
        }
    )
}

private fun formatTime(millis: Long): String = if (millis <= 0) "-" else {
    runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }.getOrDefault("-")
}

// =================== 复用组件（沿用 0.7.1 WorldInfoSettings 实现，W5 后此处为唯一真源） ===================

@Composable
private fun WorldInfoEntryCard(
    entry: WorldInfoEntry,
    isEn: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    showEdit: Boolean = true,
    showDelete: Boolean = true,
    toggleEnabled: Boolean = true,
    disabledHint: String? = null,
    editedInPlace: Boolean = false
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

            // 内容（点击展开/收起全文；自建书与卡书一致）
            var expanded by remember { mutableStateOf(false) }
            Text(
                text = entry.content,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded }
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
                if (editedInPlace) add(if (isEn) "edited" else "已改")
                disabledHint?.let { add(it) }
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
                        onCheckedChange = onToggle,
                        enabled = toggleEnabled
                    )
                }
                if (showEdit) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = if (isEn) "Edit" else "编辑",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (showDelete) {
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
}

/**
 * 条目编辑对话框：长表单（verticalScroll + 高度上限），覆盖全部字段。
 * 常用区 + AdvancedSection 折叠高级区。
 */
@Composable
private fun WorldInfoEditDialog(
    editingEntry: WorldInfoEntry?,
    isEn: Boolean,
    entryOverridden: Boolean = false,
    onResetOverride: (() -> Unit)? = null,
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
            if (entryOverridden) {
                TextButton(onClick = { onResetOverride?.invoke() }) {
                    Text(if (isEn) "Reset" else "恢复原文")
                }
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
 * 匹配配置对话框（全局默认配置与书级覆盖共用）。
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
                text = if (isEn) "Matching Config" else "匹配配置",
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
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) content()
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
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
