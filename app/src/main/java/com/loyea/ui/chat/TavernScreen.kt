package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.core.content.FileProvider

/**
 * 拷贝 Uri 内容到本地应用私有目录下指定子目录中
 */
fun copyUriToLocal(context: Context, sourceUri: Uri, subDirName: String, fileName: String): String? {
    return try {
        val dir = File(context.filesDir, subDirName).apply { if (!exists()) mkdirs() }
        val targetFile = File(dir, fileName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        targetFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/** 将 CHARX manifest 声明的资源安全地映射到应用私有目录，并回写本地 URI。 */
private fun localizeCharxAssets(
    context: Context,
    card: CharacterCard,
    archive: TavernCharxArchive
): CharacterCard {
    val assets = runCatching {
        JsonParser.parseString(card.assetsJson).takeIf { it.isJsonArray }?.asJsonArray
    }.getOrNull() ?: return card
    val targetDir = File(context.filesDir, "tavern_assets/${card.id}").apply { mkdirs() }
    var avatarPath: String? = null
    var backgroundPath: String? = null
    val localized = JsonArray()
    assets.forEach { element ->
        val asset = element.takeIf { it.isJsonObject }?.asJsonObject
        if (asset == null) {
            localized.add(element)
            return@forEach
        }
        val uri = asset["uri"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val name = asset["name"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val candidates = listOf(uri, name).flatMap { value ->
            if (value.isBlank()) emptyList() else {
                val normalized = value.substringAfter("://", value)
                    .substringBefore('?')
                    .trimStart('/')
                listOf(normalized, normalized.substringAfterLast('/'))
            }
        }.filter { it.isNotBlank() }.distinct()
        val source = archive.assets.entries.firstOrNull { (path, _) ->
            candidates.any { candidate -> path == candidate || path.endsWith("/$candidate") }
        }
        if (source == null) {
            localized.add(asset)
            return@forEach
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.value)
            .joinToString("") { "%02x".format(it) }
            .take(20)
        val extension = name.substringAfterLast('.', "bin")
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "bin" }
        val target = File(targetDir, "$digest.$extension")
        if (!target.exists()) target.writeBytes(source.value)
        asset.addProperty("uri", target.absolutePath)
        localized.add(asset)
        val type = asset["type"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
        when {
            type.contains("icon") || type.contains("avatar") -> avatarPath = target.absolutePath
            type.contains("background") -> backgroundPath = target.absolutePath
        }
    }
    return card.copy(
        avatarUri = avatarPath ?: card.avatarUri,
        backgroundUri = backgroundPath ?: card.backgroundUri,
        assetsJson = localized.toString()
    )
}

/** 将编辑器中的逗号/换行列表稳定转换为 ST 数组，忽略空项并去重。 */
private fun parseTavernListInput(value: String): List<String> = value
    .split(',', '\n')
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()

/** 可选的扩展 JSON 只接受完整 JSON 对象，避免导出后破坏角色卡结构。 */
private fun isOptionalJsonObjectValid(value: String): Boolean = value.isBlank() || runCatching {
    JsonParser.parseString(value).isJsonObject
}.getOrDefault(false)

/** 角色卡编辑器中的多条问候语以独立行保存；保留空行外的可见内容。 */
private fun parseGreetingInput(value: String): List<String> = value
    .split("\n---\n", "\n<GREETING>\n")
    .map(String::trim)
    .filter(String::isNotBlank)
    .toList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavernScreen(
    characterCardList: List<CharacterCard>,
    onCharacterCardListSave: (List<CharacterCard>) -> Unit,
    tavernResourceRegistry: TavernResourceRegistry = TavernResourceRegistry(),
    onTavernResourceRegistrySave: (TavernResourceRegistry) -> Unit = {},
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val isEn = appLanguage == "en"

    var showCreateDialog by remember { mutableStateOf(false) }
    var showResourceDialog by remember { mutableStateOf(false) }
    var cardToDelete by remember { mutableStateOf<CharacterCard?>(null) }
    var cardToEdit by remember { mutableStateOf<CharacterCard?>(null) }

    // 1. PNG 导入启动器
    val pngImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val parsedCard = TavernCardParser.parsePngCard(ByteArrayInputStream(bytes))
                    if (parsedCard != null) {
                        // 自动拷贝图片至本地 avatars 目录
                        val avatarsDir = File(context.filesDir, "avatars").apply {
                            if (!exists()) mkdirs()
                        }
                        val targetFile = File(avatarsDir, "${parsedCard.id}.png")
                        targetFile.writeBytes(bytes)

                        // 注入带有绝对路径的卡片
                        val finalCard = parsedCard.copy(avatarUri = targetFile.absolutePath)
                        onCharacterCardListSave(characterCardList + finalCard)
                        Toast.makeText(context, if (isEn) "Imported [${parsedCard.name}] successfully" else "成功导入角色卡 [${parsedCard.name}]", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, if (isEn) "No valid character card metadata found in PNG" else "未能在此 PNG 中找到有效的人格卡设定，请确认其为标准角色卡", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "${if (isEn) "Import failed" else "导入失败"}: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 2. JSON / CHARX 导入启动器：先按 JSON 读取，失败后尝试 V3 CHARX 容器
    val jsonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val charxArchive = TavernCardCodec.parseCharxWithAssets(ByteArrayInputStream(bytes))
                    val parsedCard = TavernCardParser.parseJsonCard(String(bytes, Charsets.UTF_8))
                        ?: charxArchive?.document?.let(TavernCardParser::fromDocument)
                    if (parsedCard != null) {
                        val localizedCard = charxArchive?.let { localizeCharxAssets(context, parsedCard, it) } ?: parsedCard
                        onCharacterCardListSave(characterCardList + localizedCard)
                        Toast.makeText(context, if (isEn) "Imported [${parsedCard.name}] successfully" else "成功导入角色卡 [${parsedCard.name}]", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, if (isEn) "Invalid JSON character card format" else "角色卡 JSON 格式不规范，解析失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "${if (isEn) "Import failed" else "导入失败"}: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. 外部酒馆资源导入：世界书 / preset / regex collection 统一登记，供卡片绑定引用。
    val resourceImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> reader.readText() }
                    .orEmpty()
                if (json.isBlank()) throw IllegalArgumentException("empty resource")
                val source = uri.toString()
                val parsedRegistry = TavernResourceRegistryCodec.parse(json)
                val importedRegistry = if (parsedRegistry != null && (
                        parsedRegistry.worldBooks.isNotEmpty() ||
                            parsedRegistry.presets.isNotEmpty() ||
                            parsedRegistry.regexCollections.isNotEmpty()
                        )) {
                    parsedRegistry
                } else {
                    val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                    val name = root?.let { obj ->
                        listOf("name", "title", "preset_name").asSequence()
                            .mapNotNull { key -> obj[key] }
                            .firstOrNull { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.asString
                    }.orEmpty()
                    val regexScripts = runCatching {
                        root?.let(TavernRegexEngine::parseScriptElement).orEmpty()
                    }.getOrDefault(emptyList())
                    when {
                        regexScripts.isNotEmpty() -> TavernResourceRegistry(
                            regexCollections = listOf(
                                TavernResourceRegistryCodec.regexResource("", name, json, source)
                            )
                        )
                        TavernWorldBookCodec.parse(json)?.entries?.isNotEmpty() == true -> TavernResourceRegistry(
                            worldBooks = listOf(
                                TavernResourceRegistryCodec.worldBookResource("", name, json, source)
                            )
                        )
                        else -> TavernPresetCodec.parse(json)?.takeIf { preset ->
                            preset.prompts.isNotEmpty() || preset.name.isNotBlank() ||
                                preset.temperature != null || preset.maxTokens != null ||
                                preset.promptOrder.isNotEmpty()
                        }?.let { TavernResourceRegistry(
                            presets = listOf(
                                TavernResourceRegistryCodec.presetResource("", name.ifBlank { it.name }, json, source)
                            )
                        ) }
                    }
                }
                if (importedRegistry == null) throw IllegalArgumentException("unsupported resource")
                val merged = tavernResourceRegistry.copy(
                    worldBooks = (tavernResourceRegistry.worldBooks + importedRegistry.worldBooks)
                        .distinctBy { it.id },
                    presets = (tavernResourceRegistry.presets + importedRegistry.presets)
                        .distinctBy { it.id },
                    regexCollections = (tavernResourceRegistry.regexCollections + importedRegistry.regexCollections)
                        .distinctBy { it.id }
                )
                onTavernResourceRegistrySave(merged)
                val count = importedRegistry.worldBooks.size + importedRegistry.presets.size +
                    importedRegistry.regexCollections.size
                Toast.makeText(context, if (isEn) "Imported $count Tavern resource(s)" else "已登记 $count 个酒馆外部资源", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, if (isEn) "Unsupported Tavern resource" else "无法识别该酒馆资源文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEn) "Personas" else "人格", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // 顶部快捷导入/创建菜单
                    IconButton(onClick = { pngImportLauncher.launch("image/png") }) {
                        Icon(imageVector = Icons.Default.Image, contentDescription = "Import PNG", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { jsonImportLauncher.launch("*/*") }) {
                        Icon(imageVector = Icons.Default.Code, contentDescription = "Import JSON / CHARX", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { resourceImportLauncher.launch("application/json") }) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Import Tavern resources", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showResourceDialog = true }) {
                        Icon(imageVector = Icons.Default.List, contentDescription = "Manage Tavern resources", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (isEn) "Create Persona" else "自定义角色") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 页顶横幅提示
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (isEn) "Supported Formats:" else "支持的导入格式：",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEn) 
                            "• Standard PNG Character Card (V1 / V2 Metadata)\n• Character Card exported JSON config"
                            else 
                            "• 各种标准角色扮演 APP 生成的 PNG 角色设定卡 (隐写 V1/V2 数据)\n• 各种平台导出的 JSON 纯文本人物配置",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 18.sp
                    )
                    if (tavernResourceRegistry.worldBooks.isNotEmpty() ||
                        tavernResourceRegistry.presets.isNotEmpty() ||
                        tavernResourceRegistry.regexCollections.isNotEmpty()
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isEn) {
                                "External resources: ${tavernResourceRegistry.worldBooks.size} world book(s), " +
                                    "${tavernResourceRegistry.presets.size} preset(s), " +
                                    "${tavernResourceRegistry.regexCollections.size} regex collection(s)"
                            } else {
                                "已登记外部资源：世界书 ${tavernResourceRegistry.worldBooks.size} 个，" +
                                    "预设 ${tavernResourceRegistry.presets.size} 个，正则集合 ${tavernResourceRegistry.regexCollections.size} 个"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val screenWidthDp = configuration.screenWidthDp
            val columns = when {
                screenWidthDp >= 900 -> 3
                screenWidthDp >= 600 -> 2
                else -> 1
            }

            if (columns > 1) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(characterCardList) { card ->
                        TavernCardItem(
                            card = card,
                            appLanguage = appLanguage,
                            onExportPng = { shareCharacterCardPng(context, card) },
                            onExportJson = { shareCharacterCardJson(context, card) },
                            onExportJsonV3 = { shareCharacterCardJsonV3(context, card) },
                            onExportCharx = { shareCharacterCardCharx(context, card) },
                            onEdit = { cardToEdit = card },
                            onDelete = { cardToDelete = card }
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(characterCardList) { card ->
                        TavernCardItem(
                            card = card,
                            appLanguage = appLanguage,
                            onExportPng = { shareCharacterCardPng(context, card) },
                            onExportJson = { shareCharacterCardJson(context, card) },
                            onExportJsonV3 = { shareCharacterCardJsonV3(context, card) },
                            onExportCharx = { shareCharacterCardCharx(context, card) },
                            onEdit = { cardToEdit = card },
                            onDelete = { cardToDelete = card }
                        )
                    }
                }
            }
        }

        // 3. 自定义创建弹窗
        if (showCreateDialog) {
            CreatePersonaDialog(
                appLanguage = appLanguage,
                onDismiss = { showCreateDialog = false },
                onSave = { newCard ->
                    onCharacterCardListSave(characterCardList + newCard)
                    showCreateDialog = false
                    Toast.makeText(context, if (isEn) "Created successfully" else "角色创建成功", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 4. 删除确认
        if (cardToDelete != null) {
            val targetCard = cardToDelete!!
            AlertDialog(
                onDismissRequest = { cardToDelete = null },
                title = { Text(if (isEn) "Delete Persona?" else "确认删除角色？", fontWeight = FontWeight.Bold) },
                text = { Text(if (isEn) "Are you sure you want to delete [${targetCard.name}]? Built-in presets cannot be deleted." else "确认要删除角色 [${targetCard.name}] 吗？系统内置的预置人格无法删除。") },
                confirmButton = {
                    Button(
                        onClick = {
                            if (targetCard.isBuiltIn) {
                                Toast.makeText(context, if (isEn) "Preset cannot be deleted" else "内置人设无法被删除", Toast.LENGTH_SHORT).show()
                            } else {
                                onCharacterCardListSave(characterCardList.filter { it.id != targetCard.id })
                                // 尝试清理本地头像
                                targetCard.avatarUri?.let { File(it).apply { if (exists()) delete() } }
                                Toast.makeText(context, if (isEn) "Deleted" else "删除成功", Toast.LENGTH_SHORT).show()
                            }
                            cardToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isEn) "Delete" else "删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cardToDelete = null }) {
                        Text(if (isEn) "Cancel" else "取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            )
        }

        // 5. 编辑弹窗
        if (cardToEdit != null) {
            val targetCard = cardToEdit!!
            EditPersonaDialog(
                existingCard = targetCard,
                appLanguage = appLanguage,
                onDismiss = { cardToEdit = null },
                onSave = { updatedCard ->
                    onCharacterCardListSave(
                        characterCardList.map { if (it.id == updatedCard.id) updatedCard else it }
                    )
                    cardToEdit = null
                    Toast.makeText(context, if (isEn) "Updated successfully" else "角色更新成功", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showResourceDialog) {
            TavernResourceRegistryDialog(
                registry = tavernResourceRegistry,
                isEn = isEn,
                onSave = onTavernResourceRegistrySave,
                onDismiss = { showResourceDialog = false }
            )
        }
    }
}

/**
 * 外部酒馆资源管理器：导入后的世界书、预设和正则集合都可以独立停用或删除。
 * 原始 JSON 保留在 registry 中，删除只影响本地登记，不会修改角色卡文件。
 */
@Composable
private fun TavernResourceRegistryDialog(
    registry: TavernResourceRegistry,
    isEn: Boolean,
    onSave: (TavernResourceRegistry) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEn) "Tavern resources" else "酒馆外部资源",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (registry.worldBooks.isEmpty() && registry.presets.isEmpty() && registry.regexCollections.isEmpty()) {
                    Text(
                        text = if (isEn) "No external resources registered. Use the folder button to import JSON." else "暂无外部资源，请使用顶部文件夹按钮导入 JSON。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TavernWorldBookResourceSection(
                    title = if (isEn) "World books" else "世界书",
                    resources = registry.worldBooks,
                    isEn = isEn,
                    onToggle = { id, enabled ->
                        onSave(registry.copy(worldBooks = registry.worldBooks.map { if (it.id == id) it.copy(enabled = enabled) else it }))
                    },
                    onDelete = { id ->
                        onSave(registry.copy(worldBooks = registry.worldBooks.filterNot { it.id == id }))
                    }
                )
                TavernPresetResourceSection(
                    title = if (isEn) "Presets" else "预设",
                    resources = registry.presets,
                    isEn = isEn,
                    onToggle = { id, enabled ->
                        onSave(registry.copy(presets = registry.presets.map { if (it.id == id) it.copy(enabled = enabled) else it }))
                    },
                    onDelete = { id ->
                        onSave(registry.copy(presets = registry.presets.filterNot { it.id == id }))
                    }
                )
                TavernRegexResourceSection(
                    title = if (isEn) "Regex collections" else "正则集合",
                    resources = registry.regexCollections,
                    isEn = isEn,
                    onToggle = { id, enabled ->
                        onSave(registry.copy(regexCollections = registry.regexCollections.map { if (it.id == id) it.copy(enabled = enabled) else it }))
                    },
                    onDelete = { id ->
                        onSave(registry.copy(regexCollections = registry.regexCollections.filterNot { it.id == id }))
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isEn) "Done" else "完成")
            }
        }
    )
}

@Composable
private fun TavernWorldBookResourceSection(
    title: String,
    resources: List<TavernWorldBookResource>,
    isEn: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    if (resources.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    resources.forEach { resource ->
        TavernResourceRow(
            name = resource.name,
            id = resource.id,
            enabled = resource.enabled,
            source = resource.source,
            isEn = isEn,
            onEnabledChange = { onToggle(resource.id, it) },
            onDelete = { onDelete(resource.id) }
        )
    }
}

@Composable
private fun TavernPresetResourceSection(
    title: String,
    resources: List<TavernPresetResource>,
    isEn: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    if (resources.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    resources.forEach { resource ->
        TavernResourceRow(
            name = resource.name,
            id = resource.id,
            enabled = resource.enabled,
            source = resource.source,
            isEn = isEn,
            onEnabledChange = { onToggle(resource.id, it) },
            onDelete = { onDelete(resource.id) }
        )
    }
}

@Composable
private fun TavernRegexResourceSection(
    title: String,
    resources: List<TavernRegexResource>,
    isEn: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    if (resources.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    resources.forEach { resource ->
        TavernResourceRow(
            name = resource.name,
            id = resource.id,
            enabled = resource.enabled,
            source = resource.source,
            isEn = isEn,
            onEnabledChange = { onToggle(resource.id, it) },
            onDelete = { onDelete(resource.id) }
        )
    }
}

@Composable
private fun TavernResourceRow(
    name: String,
    id: String,
    enabled: Boolean,
    source: String,
    isEn: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name.ifBlank { id }, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    text = if (source.isBlank()) id else source,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                thumbContent = null
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = if (isEn) "Delete" else "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 每一个卡片项组件
 */
@Composable
fun TavernCardItem(
    card: CharacterCard,
    appLanguage: String,
    onExportPng: () -> Unit,
    onExportJson: () -> Unit,
    onExportJsonV3: () -> Unit = {},
    onExportCharx: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isEn = appLanguage == "en"
    val avatarBitmap = rememberAvatarPainter(card.avatarUri)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 圆形头像区
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap,
                        contentDescription = card.name,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    val bgColor = remember(card.avatarColor) {
                        try {
                            Color(android.graphics.Color.parseColor(card.avatarColor))
                        } catch (e: Exception) {
                            Color(0xFFE5D3B3)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(bgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = card.name.take(1).uppercase(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 信息区
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = card.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (card.isBuiltIn) {
                            Spacer(modifier = Modifier.width(8.dp))
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Preset", fontSize = 10.sp) },
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (card.creatorName.isNullOrBlank()) "Creator: Unknown" else "${if (isEn) "Creator" else "创作者"}: ${card.creatorName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 一句话简介
            Text(
                text = card.shortIntro,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 人设 Prompts 折叠预览
            var showSettingsPreview by remember { mutableStateOf(false) }
            if (showSettingsPreview) {
                Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "System Prompt (核心人设):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = card.systemPrompt,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                            .padding(8.dp)
                    )

                    if (card.personality.isNotBlank()) {
                        Text(
                            text = "Personality (性格特征):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = card.personality,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                .padding(8.dp)
                        )
                    }

                    if (card.scenario.isNotBlank()) {
                        Text(
                            text = "Scenario (对话场景):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = card.scenario,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                .padding(8.dp)
                        )
                    }

                    if (card.firstMessage.isNotBlank()) {
                        Text(
                            text = "First Message (首句打招呼):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = card.firstMessage,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                .padding(8.dp)
                        )
                    }

                    if (card.chatExamples.isNotBlank()) {
                        Text(
                            text = "Examples (少样本范例):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = card.chatExamples,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                .padding(8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 卡片底部控制按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showSettingsPreview = !showSettingsPreview }) {
                    Text(if (showSettingsPreview) (if (isEn) "Hide Prompts" else "隐藏人设") else (if (isEn) "View Prompts" else "展开人设"), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

                // 编辑按钮
                IconButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Card", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }

                // 原地 DropdownMenu 导出双格式选择
                var exportMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { exportMenuExpanded = true }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Export Card", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Export as PNG Card (Standard)" else "导出为酒馆 PNG 角色卡 (推荐)") },
                            onClick = {
                                exportMenuExpanded = false
                                onExportPng()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Export as JSON Config (Standard)" else "导出为 V2 JSON 配置文件") },
                            onClick = {
                                exportMenuExpanded = false
                                onExportJson()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Export as V3 JSON (full fields)" else "导出为 V3 JSON（完整字段）") },
                            onClick = {
                                exportMenuExpanded = false
                                onExportJsonV3()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isEn) "Export as CHARX (V3 + assets)" else "导出为 CHARX V3（含资源）") },
                            onClick = {
                                exportMenuExpanded = false
                                onExportCharx()
                            }
                        )
                    }
                }

                if (!card.isBuiltIn) {
                    IconButton(onClick = onDelete) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Card", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * 弹窗表单：自定义创建角色卡 (升级支持本地头像和聊天背景图选择)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePersonaDialog(
    appLanguage: String,
    onDismiss: () -> Unit,
    onSave: (CharacterCard) -> Unit
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"

    var name by remember { mutableStateOf("") }
    var intro by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf("") }
    var chatExamples by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var creatorNotes by remember { mutableStateOf("") }
    var postHistoryInstructions by remember { mutableStateOf("") }
    var alternateGreetings by remember { mutableStateOf("") }
    var groupOnlyGreetings by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var characterVersion by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var extensionsJson by remember { mutableStateOf("") }
    var characterBookJson by remember { mutableStateOf("") }

    val extensionsJsonValid = isOptionalJsonObjectValid(extensionsJson)
    val characterBookJsonValid = isOptionalJsonObjectValid(characterBookJson)
    val canSave = name.isNotBlank() && systemPrompt.isNotBlank() &&
        extensionsJsonValid && characterBookJsonValid

    // 本地头像及背景 URI 绝对路径
    var localAvatarUri by remember { mutableStateOf<String?>(null) }
    var localBackgroundUri by remember { mutableStateOf<String?>(null) }

    // 头像及背景选择 Launcher
    val avatarPickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "avatars", "avatar_${System.currentTimeMillis()}.png")
            if (path != null) {
                localAvatarUri = path
                Toast.makeText(context, if (isEn) "Avatar selected" else "头像选择成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val backgroundPickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "backgrounds", "bg_${System.currentTimeMillis()}.png")
            if (path != null) {
                localBackgroundUri = path
                Toast.makeText(context, if (isEn) "Chat background selected" else "背景图选择成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 头像背景色选择 (兜底色)
    val colors = listOf("#E5D3B3", "#D3E2CD", "#CBE3F5", "#E2D3F5", "#F2D4D7")
    var selectedColorIndex by remember { mutableStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (isEn) "New Custom Persona" else "创建自定义人格", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    if (canSave) {
                                        val newCard = CharacterCard(
                                            id = "char_" + System.currentTimeMillis() + "_" + (100..999).random(),
                                            name = name.trim(),
                                            avatarUri = localAvatarUri,
                                            avatarColor = colors[selectedColorIndex],
                                            shortIntro = intro.ifBlank { if (isEn) "A unique custom companion." else "充满个性的自定义伙伴。" },
                                            systemPrompt = systemPrompt.trim(),
                                            personality = personality,
                                            scenario = scenario,
                                            firstMessage = firstMessage,
                                            chatExamples = chatExamples,
                                            isBuiltIn = false,
                                            creatorName = creator.ifBlank { if (isEn) "User Custom" else "用户自建" },
                                            backgroundUri = localBackgroundUri,
                                            description = description.ifBlank { intro },
                                            creatorNotes = creatorNotes,
                                            postHistoryInstructions = postHistoryInstructions,
                                            alternateGreetings = parseGreetingInput(alternateGreetings),
                                            groupOnlyGreetings = parseGreetingInput(groupOnlyGreetings),
                                            tags = parseTavernListInput(tags),
                                            characterVersion = characterVersion.trim(),
                                            nickname = nickname.trim().ifBlank { null },
                                            source = parseTavernListInput(source),
                                            extensionsJson = extensionsJson.trim().ifBlank { "{}" },
                                            characterBookJson = characterBookJson.trim().ifBlank { null }
                                        )
                                        onSave(newCard)
                                    }
                                },
                                enabled = canSave
                            ) {
                                Text(
                                    text = if (isEn) "Save" else "保存",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 头像与壁纸图片选择区
                    Text(
                        text = if (isEn) "Custom Artworks" else "自定义形象与聊天室背景",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 圆形头像预览/点击
                        val avatarPainter = rememberAvatarPainter(localAvatarUri)
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (localAvatarUri == null) {
                                        Color(android.graphics.Color.parseColor(colors[selectedColorIndex])).copy(alpha = 0.3f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable { avatarPickLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarPainter != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = avatarPainter,
                                    contentDescription = "Avatar Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(if (isEn) "Avatar" else "设头像", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        // 背景图卡片选择器
                        val bgPainter = rememberAvatarPainter(localBackgroundUri)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(72.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { backgroundPickLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (bgPainter != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bgPainter,
                                        contentDescription = "Background Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(if (isEn) "Change Wallpaper" else "已设背景 (点击更换)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (isEn) "Add Chat Wallpaper" else "添加聊天背景壁纸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // 2. 头像微光颜色选择 (仅当未选择本地头像时起兜底渲染作用)
                    if (localAvatarUri == null) {
                        Text(
                            text = if (isEn) "Select avatar fallback color" else "选择头像兜底背景色",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            colors.forEachIndexed { index, hex ->
                                val color = Color(android.graphics.Color.parseColor(hex))
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selectedColorIndex == index) 3.dp else 1.dp,
                                            color = if (selectedColorIndex == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorIndex = index }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. 表单输入字段
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (isEn) "Character Name" else "人物姓名（必填）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = creator,
                        onValueChange = { creator = it },
                        label = { Text(if (isEn) "Creator Name" else "创作者署名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = intro,
                        onValueChange = { intro = it },
                        label = { Text(if (isEn) "One-line Intro" else "一句话简介") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it },
                        label = { Text(if (isEn) "Personality Description" else "性格词汇描述（如：傲娇粘人、冷静高智商）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = scenario,
                        onValueChange = { scenario = it },
                        label = { Text(if (isEn) "Scenario Background" else "对话场景设定（如：在灯光昏暗的废土酒吧里）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = firstMessage,
                        onValueChange = { firstMessage = it },
                        label = { Text(if (isEn) "First greeting message" else "首句欢迎词 / 打招呼语") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(if (isEn) "System Prompt (Character Settings)" else "系统核心设定 / 人格 Prompt（必填）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = chatExamples,
                        onValueChange = { chatExamples = it },
                        label = { Text(if (isEn) "Example Dialogs (use <START> to split)" else "少样本对话范例（多行，使用 <START> 划分对话片段）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = if (isEn) "SillyTavern / Tavern fields" else "SillyTavern / Tavern 兼容字段",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(if (isEn) "Full description" else "完整角色描述（description）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = creatorNotes,
                        onValueChange = { creatorNotes = it },
                        label = { Text(if (isEn) "Creator notes" else "创作者备注（creator_notes）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = postHistoryInstructions,
                        onValueChange = { postHistoryInstructions = it },
                        label = { Text(if (isEn) "Post-history instructions" else "历史消息后指令（post_history_instructions）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = alternateGreetings,
                        onValueChange = { alternateGreetings = it },
                        label = { Text(if (isEn) "Alternate greetings (split with ---)" else "备用开场白（用单独一行 --- 分隔）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groupOnlyGreetings,
                        onValueChange = { groupOnlyGreetings = it },
                        label = { Text(if (isEn) "Group-only greetings (split with ---)" else "仅群聊开场白（用单独一行 --- 分隔）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text(if (isEn) "Nickname" else "昵称 / 宏名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = characterVersion,
                            onValueChange = { characterVersion = it },
                            label = { Text(if (isEn) "Version" else "角色版本") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text(if (isEn) "Tags (comma or newline separated)" else "标签（逗号或换行分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        label = { Text(if (isEn) "Sources (comma or newline separated)" else "来源（逗号或换行分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = characterBookJson,
                        onValueChange = { characterBookJson = it },
                        label = { Text(if (isEn) "Embedded Character Book JSON (optional)" else "内嵌角色世界书 JSON（可选）") },
                        minLines = 4,
                        isError = !characterBookJsonValid,
                        supportingText = {
                            if (!characterBookJsonValid) {
                                Text(if (isEn) "Must be a valid JSON object" else "必须是有效的 JSON 对象")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = extensionsJson,
                        onValueChange = { extensionsJson = it },
                        label = { Text(if (isEn) "Card extensions JSON (optional)" else "角色卡扩展字段 JSON（可选）") },
                        minLines = 3,
                        isError = !extensionsJsonValid,
                        supportingText = {
                            if (!extensionsJsonValid) {
                                Text(if (isEn) "Must be a valid JSON object" else "必须是有效的 JSON 对象")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

/**
 * 弹窗表单：编辑已有角色卡 (复用 CreatePersonaDialog 的结构，但预填充已有数据)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPersonaDialog(
    existingCard: CharacterCard,
    appLanguage: String,
    onDismiss: () -> Unit,
    onSave: (CharacterCard) -> Unit
) {
    val context = LocalContext.current
    val isEn = appLanguage == "en"

    var name by remember { mutableStateOf(existingCard.name) }
    var intro by remember { mutableStateOf(existingCard.shortIntro) }
    var systemPrompt by remember { mutableStateOf(existingCard.systemPrompt) }
    var firstMessage by remember { mutableStateOf(existingCard.firstMessage) }
    var creator by remember { mutableStateOf(existingCard.creatorName ?: "") }
    var personality by remember { mutableStateOf(existingCard.personality) }
    var scenario by remember { mutableStateOf(existingCard.scenario) }
    var chatExamples by remember { mutableStateOf(existingCard.chatExamples) }
    var description by remember { mutableStateOf(existingCard.description) }
    var creatorNotes by remember { mutableStateOf(existingCard.creatorNotes) }
    var postHistoryInstructions by remember { mutableStateOf(existingCard.postHistoryInstructions) }
    var alternateGreetings by remember { mutableStateOf(existingCard.alternateGreetings.joinToString("\n---\n")) }
    var groupOnlyGreetings by remember { mutableStateOf(existingCard.groupOnlyGreetings.joinToString("\n---\n")) }
    var tags by remember { mutableStateOf(existingCard.tags.joinToString(", ")) }
    var nickname by remember { mutableStateOf(existingCard.nickname.orEmpty()) }
    var characterVersion by remember { mutableStateOf(existingCard.characterVersion) }
    var source by remember { mutableStateOf(existingCard.source.joinToString(", ")) }
    var characterBookJson by remember { mutableStateOf(existingCard.characterBookJson.orEmpty()) }
    var extensionsJson by remember { mutableStateOf(existingCard.extensionsJson.takeIf { it != "{}" }.orEmpty()) }

    val extensionsJsonValid = isOptionalJsonObjectValid(extensionsJson)
    val characterBookJsonValid = isOptionalJsonObjectValid(characterBookJson)
    val canSave = name.isNotBlank() && systemPrompt.isNotBlank() &&
        extensionsJsonValid && characterBookJsonValid

    // 本地头像及背景 URI 绝对路径
    var localAvatarUri by remember { mutableStateOf(existingCard.avatarUri) }
    var localBackgroundUri by remember { mutableStateOf(existingCard.backgroundUri) }

    // 头像及背景选择 Launcher
    val avatarPickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "avatars", "avatar_${System.currentTimeMillis()}.png")
            if (path != null) {
                localAvatarUri = path
                Toast.makeText(context, if (isEn) "Avatar selected" else "头像选择成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val backgroundPickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "backgrounds", "bg_${System.currentTimeMillis()}.png")
            if (path != null) {
                localBackgroundUri = path
                Toast.makeText(context, if (isEn) "Chat background selected" else "背景图选择成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 头像背景色选择 (兜底色)
    val colors = listOf("#E5D3B3", "#D3E2CD", "#CBE3F5", "#E2D3F5", "#F2D4D7")
    var selectedColorIndex by remember {
        mutableStateOf(colors.indexOf(existingCard.avatarColor).takeIf { it >= 0 } ?: 0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (isEn) "Edit Persona" else "编辑角色", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    if (canSave) {
                                        val updatedCard = existingCard.copy(
                                            name = name.trim(),
                                            avatarUri = localAvatarUri,
                                            avatarColor = colors[selectedColorIndex],
                                            shortIntro = intro.ifBlank { if (isEn) "A unique custom companion." else "充满个性的自定义伙伴。" },
                                            systemPrompt = systemPrompt.trim(),
                                            personality = personality,
                                            scenario = scenario,
                                            firstMessage = firstMessage,
                                            chatExamples = chatExamples,
                                            creatorName = creator.ifBlank { if (isEn) "User Custom" else "用户自建" },
                                            backgroundUri = localBackgroundUri,
                                            description = description.ifBlank { intro },
                                            creatorNotes = creatorNotes,
                                            postHistoryInstructions = postHistoryInstructions,
                                            alternateGreetings = parseGreetingInput(alternateGreetings),
                                            groupOnlyGreetings = parseGreetingInput(groupOnlyGreetings),
                                            tags = parseTavernListInput(tags),
                                            characterVersion = characterVersion.trim(),
                                            nickname = nickname.trim().ifBlank { null },
                                            source = parseTavernListInput(source),
                                            characterBookJson = characterBookJson.trim().ifBlank { null },
                                            extensionsJson = extensionsJson.trim().ifBlank { "{}" }
                                        )
                                        onSave(updatedCard)
                                    }
                                },
                                enabled = canSave
                            ) {
                                Text(
                                    text = if (isEn) "Save" else "保存",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 头像与壁纸图片选择区
                    Text(
                        text = if (isEn) "Custom Artworks" else "自定义形象与聊天室背景",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 圆形头像预览/点击
                        val avatarPainter = rememberAvatarPainter(localAvatarUri)
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (localAvatarUri == null) {
                                        Color(android.graphics.Color.parseColor(colors[selectedColorIndex])).copy(alpha = 0.3f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable { avatarPickLauncher.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarPainter != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = avatarPainter,
                                    contentDescription = "Avatar Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(if (isEn) "Avatar" else "设头像", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        // 背景图卡片选择器
                        val bgPainter = rememberAvatarPainter(localBackgroundUri)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(72.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { backgroundPickLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (bgPainter != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bgPainter,
                                        contentDescription = "Background Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(if (isEn) "Change Wallpaper" else "已设背景 (点击更换)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(if (isEn) "Add Chat Wallpaper" else "添加聊天背景壁纸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    // 2. 头像微光颜色选择 (仅当未选择本地头像时起兜底渲染作用)
                    if (localAvatarUri == null) {
                        Text(
                            text = if (isEn) "Select avatar fallback color" else "选择头像兜底背景色",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            colors.forEachIndexed { index, hex ->
                                val color = Color(android.graphics.Color.parseColor(hex))
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (selectedColorIndex == index) 3.dp else 1.dp,
                                            color = if (selectedColorIndex == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorIndex = index }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. 表单输入字段
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (isEn) "Character Name" else "人物姓名（必填）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = creator,
                        onValueChange = { creator = it },
                        label = { Text(if (isEn) "Creator Name" else "创作者署名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = intro,
                        onValueChange = { intro = it },
                        label = { Text(if (isEn) "One-line Intro" else "一句话简介") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = personality,
                        onValueChange = { personality = it },
                        label = { Text(if (isEn) "Personality Description" else "性格词汇描述（如：傲娇粘人、冷静高智商）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = scenario,
                        onValueChange = { scenario = it },
                        label = { Text(if (isEn) "Scenario Background" else "对话场景设定（如：在灯光昏暗的废土酒吧里）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = firstMessage,
                        onValueChange = { firstMessage = it },
                        label = { Text(if (isEn) "First greeting message" else "首句欢迎词 / 打招呼语") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text(if (isEn) "System Prompt (Character Settings)" else "系统核心设定 / 人格 Prompt（必填）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = chatExamples,
                        onValueChange = { chatExamples = it },
                        label = { Text(if (isEn) "Example Dialogs (use <START> to split)" else "少样本对话范例（多行，使用 <START> 划分对话片段）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = if (isEn) "SillyTavern / Tavern fields" else "SillyTavern / Tavern 兼容字段",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(if (isEn) "Full description" else "完整角色描述（description）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = creatorNotes,
                        onValueChange = { creatorNotes = it },
                        label = { Text(if (isEn) "Creator notes" else "创作者备注（creator_notes）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = postHistoryInstructions,
                        onValueChange = { postHistoryInstructions = it },
                        label = { Text(if (isEn) "Post-history instructions" else "历史消息后指令（post_history_instructions）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = alternateGreetings,
                        onValueChange = { alternateGreetings = it },
                        label = { Text(if (isEn) "Alternate greetings (split with ---)" else "备用开场白（用单独一行 --- 分隔）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groupOnlyGreetings,
                        onValueChange = { groupOnlyGreetings = it },
                        label = { Text(if (isEn) "Group-only greetings (split with ---)" else "仅群聊开场白（用单独一行 --- 分隔）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text(if (isEn) "Nickname" else "昵称 / 宏名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = characterVersion,
                            onValueChange = { characterVersion = it },
                            label = { Text(if (isEn) "Version" else "角色版本") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text(if (isEn) "Tags (comma or newline separated)" else "标签（逗号或换行分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        label = { Text(if (isEn) "Sources (comma or newline separated)" else "来源（逗号或换行分隔）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = characterBookJson,
                        onValueChange = { characterBookJson = it },
                        label = { Text(if (isEn) "Embedded Character Book JSON (optional)" else "内嵌角色世界书 JSON（可选）") },
                        minLines = 4,
                        isError = !characterBookJsonValid,
                        supportingText = {
                            if (!characterBookJsonValid) {
                                Text(if (isEn) "Must be a valid JSON object" else "必须是有效的 JSON 对象")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = extensionsJson,
                        onValueChange = { extensionsJson = it },
                        label = { Text(if (isEn) "Card extensions JSON (optional)" else "角色卡扩展字段 JSON（可选）") },
                        minLines = 3,
                        isError = !extensionsJsonValid,
                        supportingText = {
                            if (!extensionsJsonValid) {
                                Text(if (isEn) "Must be a valid JSON object" else "必须是有效的 JSON 对象")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

/**
 * 构造符合酒馆 V2 标准的角色卡 JSON
 */
fun buildTavernValueV2Json(card: CharacterCard): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val document = TavernCharacterCardAdapter.toDocument(card)
    return gson.toJson(JsonParser.parseString(TavernCardCodec.toJson(document, "chara_card_v2")))
}

/** V3 JSON 导出：保留 nickname、群聊开场白、source、assets 等 V3 字段。 */
fun buildTavernValueV3Json(card: CharacterCard): String {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val document = TavernCharacterCardAdapter.toDocument(card)
    return gson.toJson(JsonParser.parseString(TavernCardCodec.toJson(document, "chara_card_v3")))
}

/**
 * 动态 Canvas 绘制莫兰迪色沙黄渐变的大卡片图作为 PNG 卡基
 */
fun drawDefaultCardBitmap(card: CharacterCard): Bitmap {
    val width = 512
    val height = 512
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 莫兰迪色沙黄渐变背景
    val paint = Paint()
    val startColor = android.graphics.Color.parseColor("#F5EAD4")
    val endColor = android.graphics.Color.parseColor("#D5C6A9")
    val shader = LinearGradient(
        0f, 0f, 0f, height.toFloat(),
        startColor, endColor,
        Shader.TileMode.CLAMP
    )
    paint.shader = shader
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    // 绘制微光几何框 (浅白色线条，增加质感)
    val strokePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 100 // 半透明
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    canvas.drawRect(24f, 24f, (width - 24).toFloat(), (height - 24).toFloat(), strokePaint)
    canvas.drawRect(32f, 32f, (width - 32).toFloat(), (height - 32).toFloat(), strokePaint)

    // 绘制名称 (大字)
    val textPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#4A3F2C")
        textSize = 48f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    // 居中绘制
    val xPos = width / 2f
    val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(card.name, xPos, yPos, textPaint)

    // 绘制简介 (较小字体)
    val introPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#6E5D47")
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    
    val cleanIntro = if (card.shortIntro.length > 20) card.shortIntro.take(18) + "..." else card.shortIntro
    canvas.drawText(cleanIntro, xPos, yPos + 60f, introPaint)

    // 绘制底部的 "Loyea Persona Card" 微光小标
    val footerPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 180
        textSize = 14f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("Loyea Persona Card", xPos, (height - 60).toFloat(), footerPaint)

    return bitmap
}

/**
 * 将 Base64 的 JSON 隐写写入 PNG 字节流中 (IHDR 块后安全插入自定义的 tEXt chunk)
 */
fun injectTavernMetadata(pngBytes: ByteArray, jsonBase64: String): ByteArray {
    val inputStream = ByteArrayInputStream(pngBytes)
    val outputStream = java.io.ByteArrayOutputStream()

    // 读取并写入 8 字节 PNG 头部签名
    val signature = ByteArray(8)
    if (inputStream.read(signature) != 8) throw IllegalArgumentException("Invalid PNG signature")
    outputStream.write(signature)

    val buffer = ByteArray(4)
    while (true) {
        // 读取长度
        if (inputStream.read(buffer) != 4) break
        val length = ((buffer[0].toInt() and 0xFF) shl 24) or
                     ((buffer[1].toInt() and 0xFF) shl 16) or
                     ((buffer[2].toInt() and 0xFF) shl 8) or
                     (buffer[3].toInt() and 0xFF)

        // 读取类型
        val typeBytes = ByteArray(4)
        if (inputStream.read(typeBytes) != 4) break
        val type = String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII)

        // 读取数据
        val data = ByteArray(length)
        var readBytes = 0
        while (readBytes < length) {
            val read = inputStream.read(data, readBytes, length - readBytes)
            if (read == -1) break
            readBytes += read
        }
        
        // 读取 CRC
        val crcBytes = ByteArray(4)
        if (inputStream.read(crcBytes) != 4) break

        // 写入当前 chunk
        outputStream.write(buffer)
        outputStream.write(typeBytes)
        outputStream.write(data)
        outputStream.write(crcBytes)

        // 如果是 IHDR chunk，在此之后立即插入 tEXt chunk
        if (type == "IHDR") {
            val keyword = "chara".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
            val text = jsonBase64.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val chunkData = ByteArray(keyword.size + 1 + text.size)
            System.arraycopy(keyword, 0, chunkData, 0, keyword.size)
            chunkData[keyword.size] = 0.toByte()
            System.arraycopy(text, 0, chunkData, keyword.size + 1, text.size)

            val textLength = chunkData.size
            val lenBytes = ByteArray(4)
            lenBytes[0] = ((textLength ushr 24) and 0xFF).toByte()
            lenBytes[1] = ((textLength ushr 16) and 0xFF).toByte()
            lenBytes[2] = ((textLength ushr 8) and 0xFF).toByte()
            lenBytes[3] = (textLength and 0xFF).toByte()
            outputStream.write(lenBytes)

            val tEXtType = "tEXt".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
            outputStream.write(tEXtType)
            outputStream.write(chunkData)

            val crc32 = java.util.zip.CRC32()
            crc32.update(tEXtType)
            crc32.update(chunkData)
            val crcVal = crc32.value

            val outCrc = ByteArray(4)
            outCrc[0] = ((crcVal ushr 24) and 0xFF).toByte()
            outCrc[1] = ((crcVal ushr 16) and 0xFF).toByte()
            outCrc[2] = ((crcVal ushr 8) and 0xFF).toByte()
            outCrc[3] = (crcVal and 0xFF).toByte()
            outputStream.write(outCrc)
        }

        if (type == "IEND") break
    }

    return outputStream.toByteArray()
}

/**
 * 分享 PNG 隐写角色卡
 */
fun shareCharacterCardPng(context: Context, card: CharacterCard) {
    try {
        val jsonV2 = buildTavernValueV2Json(card)
        val base64Json = android.util.Base64.encodeToString(jsonV2.toByteArray(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
        
        // 1. 获取基底图
        val basePngBytes = if (card.avatarUri != null) {
            val avatarFile = File(card.avatarUri)
            if (avatarFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(card.avatarUri)
                if (bitmap != null) {
                    val baos = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    baos.toByteArray()
                } else {
                    val defaultBitmap = drawDefaultCardBitmap(card)
                    val baos = java.io.ByteArrayOutputStream()
                    defaultBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    baos.toByteArray()
                }
            } else {
                val defaultBitmap = drawDefaultCardBitmap(card)
                val baos = java.io.ByteArrayOutputStream()
                defaultBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            }
        } else {
            val defaultBitmap = drawDefaultCardBitmap(card)
            val baos = java.io.ByteArrayOutputStream()
            defaultBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            baos.toByteArray()
        }

        // 2. 注入隐写信息
        val finalPngBytes = injectTavernMetadata(basePngBytes, base64Json)

        // 3. 写入缓存文件 exports 目录下
        val exportsDir = File(context.cacheDir, "exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()
        
        val fileName = "${card.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.png"
        val outFile = File(exportsDir, fileName)
        FileOutputStream(outFile).use { fos ->
            fos.write(finalPngBytes)
        }

        // 4. 分享
        val fileUri = FileProvider.getUriForFile(context, "com.loyea.fileprovider", outFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享 PNG 角色卡"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "分享失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 分享 JSON 配置文件
 */
fun shareCharacterCardJson(context: Context, card: CharacterCard) {
    try {
        val jsonV2 = buildTavernValueV2Json(card)
        val exportsDir = File(context.cacheDir, "exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()

        val fileName = "${card.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.json"
        val outFile = File(exportsDir, fileName)
        FileOutputStream(outFile).use { fos ->
            fos.write(jsonV2.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }

        val fileUri = FileProvider.getUriForFile(context, "com.loyea.fileprovider", outFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享 JSON 配置文件"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "分享失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

/** 分享完整 V3 JSON；与 V2 导出并列，避免用户必须选择 CHARX 才能保留高级字段。 */
fun shareCharacterCardJsonV3(context: Context, card: CharacterCard) {
    try {
        val jsonV3 = buildTavernValueV3Json(card)
        val exportsDir = File(context.cacheDir, "exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()

        val fileName = "${card.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.v3.json"
        val outFile = File(exportsDir, fileName)
        FileOutputStream(outFile).use { fos ->
            fos.write(jsonV3.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }

        val fileUri = FileProvider.getUriForFile(context, "com.loyea.fileprovider", outFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享 V3 JSON 角色卡"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "V3 JSON 分享失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

/** 构造 V3 CHARX：card.json 使用 embeded URI，资源只打包应用私有目录中明确存在的文件。 */
private fun buildTavernCharxBytes(card: CharacterCard): ByteArray {
    val document = TavernCharacterCardAdapter.toDocument(card)
    val assets = runCatching {
        JsonParser.parseString(card.assetsJson).takeIf { it.isJsonArray }?.asJsonArray
    }.getOrNull() ?: JsonArray()
    val usedNames = mutableSetOf<String>()
    val files = mutableListOf<Pair<String, ByteArray>>()
    val localizedAssets = JsonArray()
    assets.forEachIndexed { index, element ->
        val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: run {
            localizedAssets.add(element)
            return@forEachIndexed
        }
        val uri = asset["uri"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val path = when {
            uri.startsWith("file://") -> Uri.parse(uri).path
            uri.startsWith("/") -> uri
            else -> null
        }
        val file = path?.let(::File)?.takeIf { it.isFile }
        if (file == null) {
            localizedAssets.add(asset)
            return@forEachIndexed
        }
        val rawName = asset["name"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val baseName = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "asset_$index.bin" }
        var name = baseName
        var suffix = 1
        while (!usedNames.add(name)) {
            name = "${baseName.substringBeforeLast('.', baseName)}_$suffix" +
                baseName.substringAfterLast('.', "").let { ext -> if (ext.isBlank()) "" else ".$ext" }
            suffix++
        }
        asset.addProperty("uri", "embeded://$name")
        localizedAssets.add(asset)
        files += name to file.readBytes()
    }
    val v3Document = document.copy(
        spec = "chara_card_v3",
        specVersion = "3.0",
        data = document.data.copy(assetsJson = localizedAssets.toString())
    )
    val cardJson = TavernCardCodec.toJson(v3Document, "chara_card_v3")
    return java.io.ByteArrayOutputStream().also { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("card.json"))
            zip.write(cardJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}

fun shareCharacterCardCharx(context: Context, card: CharacterCard) {
    try {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "${card.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.charx"
        val outFile = File(exportsDir, fileName)
        FileOutputStream(outFile).use { it.write(buildTavernCharxBytes(card)) }
        val fileUri = FileProvider.getUriForFile(context, "com.loyea.fileprovider", outFile)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享 CHARX 角色卡"))
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "CHARX 分享失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
