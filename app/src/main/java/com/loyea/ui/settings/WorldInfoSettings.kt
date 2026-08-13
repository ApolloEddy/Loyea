package com.loyea.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.loyea.ui.chat.ChatViewModel
import com.loyea.ui.chat.WorldInfoEntry

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
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"
    val entries = viewModel?.worldInfoEntries?.value ?: emptyList()

    var editingEntry by remember { mutableStateOf<WorldInfoEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }

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
            viewModel?.saveWorldInfo(imported)
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
                title = { Text(if (isEn) "World Info (Global Lore)" else "World Info 世界观记忆", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
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
            if (entries.isEmpty()) {
                EmptyHint(isEn = isEn)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                viewModel?.saveWorldInfo(updated)
                            },
                            onDelete = {
                                val updated = entries.filter { it.id != entry.id }
                                viewModel?.saveWorldInfo(updated)
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
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
                viewModel?.saveWorldInfo(updated)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun EmptyHint(isEn: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            // 元信息行：ST 兼容字段摘要
            val metaParts = buildList {
                if (entry.constant) add(if (isEn) "constant" else "常驻")
                if (entry.selective) add(if (isEn) "selective" else "选择性")
                if (entry.keysecondary.isNotEmpty()) add("secondary:${entry.keysecondary.size}")
                if (entry.comment.isNotBlank()) add(if (isEn) "comment: ${entry.comment}" else "备注: ${entry.comment}")
            }
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "· " + metaParts.joinToString(" · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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

@Composable
private fun WorldInfoEditDialog(
    editingEntry: WorldInfoEntry?,
    isEn: Boolean,
    onSave: (WorldInfoEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var keywordsInput by remember { mutableStateOf(editingEntry?.keywords?.joinToString(",") ?: "") }
    var contentInput by remember { mutableStateOf(editingEntry?.content ?: "") }
    var constantInput by remember { mutableStateOf(editingEntry?.constant ?: false) }
    var commentInput by remember { mutableStateOf(editingEntry?.comment ?: "") }

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
            Column {
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
                    value = contentInput,
                    onValueChange = { contentInput = it },
                    label = { Text(if (isEn) "Content (injected when triggered)" else "内容（命中后注入）") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isEn) "Always inject (constant)" else "常驻注入（无视关键词）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = constantInput,
                        onCheckedChange = { constantInput = it }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
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
                            keysecondary = base?.keysecondary ?: emptyList(),
                            constant = constantInput,
                            order = base?.order ?: 100,
                            depth = base?.depth ?: 4,
                            comment = commentInput.trim(),
                            selective = base?.selective ?: false,
                            disable = base?.disable ?: false
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
                    disable = disable
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
        entriesObj.add(e.id, obj)
    }
    root.add("entries", entriesObj)
    return root.toString()
}
