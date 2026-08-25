package com.loyea.ui.chat

import com.loyea.plugins.tavern.core.*
import com.loyea.plugins.tavern.ui.TavernUiEvent
import com.loyea.plugins.tavern.ui.TavernUiState
import com.loyea.plugins.tavern.ui.TavernUiText.AVATAR_PALETTE
import com.loyea.plugins.tavern.ui.TavernUiText.isOptionalJsonObjectValid
import com.loyea.plugins.tavern.ui.TavernUiText.parseGreetingInput
import com.loyea.plugins.tavern.ui.TavernUiText.parseTavernListInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
import com.google.gson.JsonObject
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

    var uiState by remember { mutableStateOf(TavernUiState()) }
    fun dispatch(event: TavernUiEvent) {
        uiState = uiState.reduce(event)
    }
    val cardToDelete = uiState.cardToDeleteId?.let { cardId ->
        characterCardList.firstOrNull { it.id == cardId }
    }
    val cardToEdit = uiState.cardToEditId?.let { cardId ->
        characterCardList.firstOrNull { it.id == cardId }
    }

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
                            parsedRegistry.regexCollections.isNotEmpty() ||
                            parsedRegistry.quickReplySets.isNotEmpty()
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
                    val quickReplySets = TavernQuickReplyCodec.parseSets(json)
                    when {
                        quickReplySets.isNotEmpty() -> TavernResourceRegistry(
                            quickReplySets = quickReplySets
                        )
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
                        .distinctBy { it.id },
                    quickReplySets = (tavernResourceRegistry.quickReplySets + importedRegistry.quickReplySets)
                        .distinctBy { it.name.lowercase() }
                )
                onTavernResourceRegistrySave(merged)
                val count = importedRegistry.worldBooks.size + importedRegistry.presets.size +
                    importedRegistry.regexCollections.size + importedRegistry.quickReplySets.size
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
                    IconButton(onClick = { dispatch(TavernUiEvent.UrlImportRequested) }) {
                        Icon(imageVector = Icons.Default.Link, contentDescription = "Import from URL", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { resourceImportLauncher.launch("application/json") }) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Import Tavern resources", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { dispatch(TavernUiEvent.ResourceRequested) }) {
                        Icon(imageVector = Icons.Default.List, contentDescription = "Manage Tavern resources", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dispatch(TavernUiEvent.CreateRequested) },
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
                        tavernResourceRegistry.regexCollections.isNotEmpty() ||
                        tavernResourceRegistry.quickReplySets.isNotEmpty()
                    ) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isEn) {
                                "External resources: ${tavernResourceRegistry.worldBooks.size} world book(s), " +
                                    "${tavernResourceRegistry.presets.size} preset(s), " +
                                    "${tavernResourceRegistry.regexCollections.size} regex collection(s), " +
                                    "${tavernResourceRegistry.quickReplySets.size} quick reply set(s)"
                            } else {
                                "已登记外部资源：世界书 ${tavernResourceRegistry.worldBooks.size} 个，" +
                                    "预设 ${tavernResourceRegistry.presets.size} 个，正则集合 ${tavernResourceRegistry.regexCollections.size} 个，" +
                                    "Quick Reply ${tavernResourceRegistry.quickReplySets.size} 组"
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
                            onEdit = { dispatch(TavernUiEvent.EditRequested(card.id)) },
                            onDelete = { dispatch(TavernUiEvent.DeleteRequested(card.id)) }
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
                            onEdit = { dispatch(TavernUiEvent.EditRequested(card.id)) },
                            onDelete = { dispatch(TavernUiEvent.DeleteRequested(card.id)) }
                        )
                    }
                }
            }
        }

        // 从链接导入角色卡对话框
        if (uiState.showUrlImportDialog) {
            UrlImportDialog(
                isEn = isEn,
                url = uiState.urlImportText,
                onUrlChange = { dispatch(TavernUiEvent.UrlImportTextChanged(it)) },
                onDismiss = { dispatch(TavernUiEvent.UrlImportDismissed) },
                onImport = { url ->
                    downloadAndImportCharacterCard(
                        url = url,
                        existing = characterCardList,
                        onSave = onCharacterCardListSave,
                        context = context,
                        isEn = isEn
                    )
                }
            )
        }

        // 3. 自定义创建弹窗
        if (uiState.showCreateDialog) {
            CreatePersonaDialog(
                appLanguage = appLanguage,
                onDismiss = { dispatch(TavernUiEvent.CreateDismissed) },
                onSave = { newCard ->
                    onCharacterCardListSave(characterCardList + newCard)
                    dispatch(TavernUiEvent.CreateCompleted)
                    Toast.makeText(context, if (isEn) "Created successfully" else "角色创建成功", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 4. 删除确认
        cardToDelete?.let { targetCard ->
            AlertDialog(
                onDismissRequest = { dispatch(TavernUiEvent.DeleteDismissed) },
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
                            dispatch(TavernUiEvent.DeleteCompleted)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (isEn) "Delete" else "删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dispatch(TavernUiEvent.DeleteDismissed) }) {
                        Text(if (isEn) "Cancel" else "取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            )
        }

        // 5. 编辑弹窗
        cardToEdit?.let { targetCard ->
            EditPersonaDialog(
                existingCard = targetCard,
                appLanguage = appLanguage,
                onDismiss = { dispatch(TavernUiEvent.EditDismissed) },
                onSave = { updatedCard ->
                    onCharacterCardListSave(
                        characterCardList.map { if (it.id == updatedCard.id) updatedCard else it }
                    )
                    dispatch(TavernUiEvent.EditCompleted)
                    Toast.makeText(context, if (isEn) "Updated successfully" else "角色更新成功", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (uiState.showResourceDialog) {
            TavernResourceRegistryDialog(
                registry = tavernResourceRegistry,
                isEn = isEn,
                onSave = onTavernResourceRegistrySave,
                onPresetCreate = { dispatch(TavernUiEvent.PresetEditorCreateRequested) },
                onPresetEdit = { id -> dispatch(TavernUiEvent.PresetEditorEditRequested(id)) },
                onDismiss = { dispatch(TavernUiEvent.ResourceDismissed) }
            )
        }

        // 6. C2 预设编辑器：新建（presetToEditId == ""）与编辑（非空 id）共用同一对话框。
        //    presetToEditId 由 TavernUiState 互斥管理，打开即关闭其它对话框。
        if (uiState.presetToEditId != null) {
            val editingId = uiState.presetToEditId
            val existingPreset = editingId?.takeIf { it.isNotBlank() }
                ?.let { id -> tavernResourceRegistry.presets.firstOrNull { it.id == id } }
                ?.let { TavernPresetCodec.parse(it.rawJson) }
            PresetEditorDialog(
                existing = existingPreset,
                existingId = editingId?.takeIf { it.isNotBlank() },
                isEn = isEn,
                onDismiss = { dispatch(TavernUiEvent.PresetEditorDismissed) },
                onSave = { preset ->
                    // 新建生成稳定 presetId；编辑按原 presetId 更新条目
                    val resourceId = editingId.takeIf { !it.isNullOrBlank() }
                        ?: TavernPresetEditor.newPresetId()
                    val resource = TavernPresetResource(
                        id = resourceId,
                        name = preset.name,
                        rawJson = TavernPresetEditor.buildPresetJson(preset),
                        enabled = true,
                        source = "user"
                    )
                    val next = if (editingId.isNullOrBlank()) {
                        tavernResourceRegistry.copy(presets = tavernResourceRegistry.presets + resource)
                    } else {
                        tavernResourceRegistry.copy(
                            presets = tavernResourceRegistry.presets.map {
                                if (it.id == editingId) resource else it
                            }
                        )
                    }
                    onTavernResourceRegistrySave(next)
                    dispatch(TavernUiEvent.PresetEditorSaved)
                    Toast.makeText(context, if (isEn) "Preset saved" else "预设保存成功", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

/**
 * 从链接导入角色卡：粘贴 chub.ai / aicharactercards 等链接，一键下载并走
 * 与本地文件导入一致的解析/保存管线。
 *
 * 流程：
 * 1. 在 IO 线程调用 [TavernCardDownloader.downloadFromUrl] 取字节（内部已做 1MB 上限与宽松实体提取）；
 * 2. 下载失败按 [com.loyea.ui.chat.TavernCardDownloader.TavernDownloadFailure] 映射成可读文案；
 * 3. 下载成功则交给 [parseImportedBytes] 原样复用现有 codec（JSON / PNG）解析；
 * 4. 解析成功即保存并 Toast，失败则返回"无法解析"提示。
 *
 * @return null 表示导入成功；否则返回需要展示给用户的错误文案。
 */
private suspend fun downloadAndImportCharacterCard(
    url: String,
    existing: List<CharacterCard>,
    onSave: (List<CharacterCard>) -> Unit,
    context: Context,
    isEn: Boolean
): String? {
    // 网络 I/O 放到 IO 线程，避免阻塞主线程
    val result = withContext(Dispatchers.IO) {
        TavernCardDownloader.downloadFromUrl(url, TavernCardDownloader.defaultFetchBytes)
    }
    return when (result) {
        is TavernCardDownloader.TavernDownloadResult.Failure -> when (result.reason) {
            // apiUrl 为空（例如 aicharactercards 链接未被解析到端点）时给出引导性提示
            TavernCardDownloader.TavernDownloadFailure.INVALID_URL ->
                "该站点暂不支持，可改从文件导入"
            TavernCardDownloader.TavernDownloadFailure.NETWORK ->
                if (isEn) "Network error, please check connection and retry" else "网络错误，请检查网络后重试"
            TavernCardDownloader.TavernDownloadFailure.TOO_LARGE ->
                if (isEn) "Card file too large (over 1MB)" else "角色卡文件过大（超过 1MB），无法导入"
            TavernCardDownloader.TavernDownloadFailure.PARSE ->
                if (isEn) "Cannot recognize character card" else "无法解析角色卡"
        }
        is TavernCardDownloader.TavernDownloadResult.Success -> {
            val card = parseImportedCard(result)
            if (card != null) {
                onSave(existing + card)
                Toast.makeText(
                    context,
                    if (isEn) "Imported [${card.name}] successfully" else "成功导入角色卡 [${card.name}]",
                    Toast.LENGTH_SHORT
                ).show()
                null
            } else {
                if (isEn) "Cannot parse character card" else "无法解析角色卡，请确认链接指向标准角色卡"
            }
        }
    }
}

/**
 * 复用现有 codec 解析下载结果：
 * 1. 优先用 [com.loyea.ui.chat.TavernCardDownloader.TavernDownloadResult.Success.cardJson]
 *    （chub 提取出的角色 JSON）走 JSON 解析；
 * 2. 兜底用原始字节：先按 UTF-8 文本解析 JSON，失败再尝试 PNG 隐写卡片。
 * 全部解析都失败则返回 null。
 */
private fun parseImportedCard(
    result: TavernCardDownloader.TavernDownloadResult.Success
): CharacterCard? {
    result.cardJson?.let { json ->
        runCatching { TavernCardParser.parseJsonCard(json) }.getOrNull()?.let { return it }
    }
    // 原样交给现有 codec，能解析则导入（覆盖 JSON 文本 / PNG 隐写两种形态）
    val rawText = String(result.rawBytes, Charsets.UTF_8)
    runCatching { TavernCardParser.parseJsonCard(rawText) }.getOrNull()?.let { return it }
    runCatching { TavernCardParser.parsePngCard(ByteArrayInputStream(result.rawBytes)) }
        .getOrNull()
        ?.let { return it }
    return null
}

/**
 * 从链接导入角色卡的输入对话框。
 * 支持粘贴 URL 后确认；点击确认后在协程里执行 [onImport]（挂起函数），
 * 返回 null 表示成功（自动关闭），否则把错误文案展示在输入框下方。
 */
@Composable
private fun UrlImportDialog(
    isEn: Boolean,
    url: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: suspend (url: String) -> String?
) {
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canConfirm = url.isNotBlank() && !loading

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(if (isEn) "Import from URL" else "从链接导入角色卡", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { onUrlChange(it); error = null },
                    label = { Text(if (isEn) "Paste chub.ai / character card link" else "粘贴角色卡链接（chub.ai / 其他）") },
                    placeholder = { Text("https://chub.ai/characters/...") },
                    singleLine = true,
                    enabled = !loading,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        val err = onImport(url.trim())
                        if (err == null) {
                            onDismiss()
                        } else {
                            loading = false
                            error = err
                        }
                    }
                }
            ) { Text(if (isEn) "Import" else "导入") }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = onDismiss) {
                Text(
                    if (isEn) "Cancel" else "取消",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    )
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
    onPresetCreate: () -> Unit,
    onPresetEdit: (String) -> Unit,
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
                if (registry.worldBooks.isEmpty() && registry.presets.isEmpty() && registry.regexCollections.isEmpty() && registry.quickReplySets.isEmpty()) {
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
                    onCreate = onPresetCreate,
                    onEdit = onPresetEdit,
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
                TavernQuickReplyResourceSection(
                    title = if (isEn) "Quick Reply sets" else "Quick Reply 组",
                    resources = registry.quickReplySets,
                    isEn = isEn,
                    onToggle = { name, enabled ->
                        onSave(registry.copy(quickReplySets = registry.quickReplySets.map {
                            if (it.name.equals(name, ignoreCase = true)) it.copy(enabled = enabled) else it
                        }))
                    },
                    onDelete = { name ->
                        onSave(registry.copy(quickReplySets = registry.quickReplySets.filterNot {
                            it.name.equals(name, ignoreCase = true)
                        }))
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
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    // 区块标题与"新建预设"入口始终展示，便于无预设时也能从零创建
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onCreate) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (isEn) "New preset" else "新建预设", fontSize = 12.sp)
        }
    }
    if (resources.isEmpty()) {
        Text(
            text = if (isEn) "No presets yet. Create one to customize prompt slots." else "暂无预设，可新建自定义提示词槽位。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    resources.forEach { resource ->
        TavernResourceRow(
            name = resource.name,
            id = resource.id,
            enabled = resource.enabled,
            source = resource.source,
            isEn = isEn,
            onEdit = { onEdit(resource.id) },
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
private fun TavernQuickReplyResourceSection(
    title: String,
    resources: List<TavernQuickReplySet>,
    isEn: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    if (resources.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    resources.forEach { resource ->
        TavernResourceRow(
            name = resource.name,
            id = "${resource.qrList.size} ${if (isEn) "reply(s)" else "条回复"}",
            enabled = resource.enabled,
            source = resource.qrList.take(3).joinToString(" · ") { it.label }.ifBlank { "Quick Reply" },
            isEn = isEn,
            onEnabledChange = { onToggle(resource.name, it) },
            onDelete = { onDelete(resource.name) }
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
    onEdit: (() -> Unit)? = null,
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
            onEdit?.let { edit ->
                IconButton(onClick = edit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = if (isEn) "Edit" else "编辑",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
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
    // 世界书/扩展 JSON 均为可选高级字段：格式错误仅红框提示（isError），绝不阻断保存（issue 8）。
    val canSave = name.isNotBlank() && systemPrompt.isNotBlank()

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
    val colors = AVATAR_PALETTE
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
    // 世界书/扩展 JSON 均为可选高级字段：格式错误仅红框提示（isError），绝不阻断保存（issue 8）。
    val canSave = name.isNotBlank() && systemPrompt.isNotBlank()

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
    val colors = AVATAR_PALETTE
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
 * 编辑器槽位的可变草稿。列表顺序即 promptOrder 顺序，保存时按序序列化。
 */
data class TavernPresetSlotDraft(
    val identifier: String,
    val label: String,
    val content: String,
    val enabled: Boolean = true
) {
    /** 转成可参与构建 / 序列化的 [TavernPresetPrompt]。 */
    fun toPrompt(order: Int): TavernPresetPrompt = TavernPresetPrompt(
        name = label,
        identifier = identifier,
        content = content,
        role = "system",
        systemPrompt = true,
        marker = false,
        enabled = enabled,
        injectionPosition = 0,
        injectionDepth = 0
    )
}

/**
 * C2 预设编辑器宿主侧的纯逻辑：Tavo 基础 9 槽位模板、默认预设、JSON 往返序列化、
 * 槽位可用性筛选与保存校验。均为顶层公开成员，便于在 app 测试中独立单测。
 */
object TavernPresetEditor {
    private data class SlotSeed(val identifier: String, val label: String, val content: String)

    /** 基础 9 槽位，顺序即默认 promptOrder（对齐 Tavo 预设页语义）。label 为英文，中文由 [slotLabel] 本地化。 */
    private val baseSlotSeeds: List<SlotSeed> = listOf(
        SlotSeed("user_identity", "User Identity",
            "User information: I am a human.\nMy identity details are as follows: ..."),
        SlotSeed("character_setting", "Character Setting",
            "{{char}}'s baseline traits, manner of speaking, and style are as follows: ..."),
        SlotSeed("personality", "Personality",
            "Personality summary for {{char}}: ..."),
        SlotSeed("scenario", "Scenario",
            "Background of the current scenario: ..."),
        SlotSeed("example_chat", "New Example Chat",
            "<START>\n{{user}}: ...\n{{char}}: ...\n<START>"),
        SlotSeed("new_chat", "New Chat",
            "<START>\n{{char}}: {{random}}"),
        SlotSeed("group_chat_progression", "Group Chat Progression",
            "{{group}}'s members: ...\nCurrent group chat history so far: ..."),
        SlotSeed("continue_progression", "Continue Progression",
            "[Summary of what happened so far]\n..."),
        SlotSeed("ai_assistance", "AI Assistance",
            "This is a {{llm}} roleplay session. Stay in character and write the next reply for {{char}}.")
    )

    private val zhLabels = mapOf(
        "user_identity" to "用户身份",
        "character_setting" to "角色设定",
        "personality" to "性格",
        "scenario" to "场景",
        "example_chat" to "新示例对话",
        "new_chat" to "新对话",
        "group_chat_progression" to "群聊推进",
        "continue_progression" to "继续对话推进",
        "ai_assistance" to "AI 辅助"
    )

    private val enLabels = baseSlotSeeds.associate { it.identifier to it.label }

    /** 槽位展示名（基础槽位双语，扩展槽位回退到标识符，主提示词 / 历史后指令友好化）。 */
    fun slotLabel(identifier: String, isEn: Boolean): String {
        (if (isEn) enLabels else zhLabels)[identifier]?.let { return it }
        return when (identifier.trim().lowercase()) {
            "main" -> if (isEn) "Main Prompt" else "主提示词"
            "post_history", "post_history_instructions", "post_history_prompt" ->
                if (isEn) "Post History" else "历史消息后"
            else -> identifier
        }
    }

    /** 新建预设 / 未找到时的默认 9 槽位草稿。 */
    fun defaultSlots(): List<TavernPresetSlotDraft> = baseSlotSeeds.map { seed ->
        TavernPresetSlotDraft(seed.identifier, enLabels.getValue(seed.identifier), seed.content, enabled = true)
    }

    /** 由基础槽位标识符补建一个启用的空内容草稿。 */
    fun newSlot(identifier: String, isEn: Boolean): TavernPresetSlotDraft {
        val seed = baseSlotSeeds.firstOrNull { it.identifier == identifier }
        return TavernPresetSlotDraft(
            identifier = identifier,
            label = slotLabel(identifier, isEn),
            content = seed?.content.orEmpty(),
            enabled = true
        )
    }

    /** 可"添加槽位"的基础标识符集合：promptOrder 尚未覆盖、也未出现在 prompts 里的基础槽位。 */
    fun canAddSlot(prompts: List<TavernPresetPrompt>): List<String> =
        baseSlotSeeds.map { it.identifier }.filter { id -> prompts.none { it.identifier == id } }

    /** 由基础槽位生成默认预设：temperature/maxTokens 预设非空，promptOrder 覆盖全部槽位。 */
    fun defaultPreset(name: String): TavernPromptPreset {
        val drafts = defaultSlots()
        return TavernPromptPreset(
            name = name,
            temperature = 0.7,
            maxTokens = 1024,
            prompts = drafts.mapIndexed { index, slot -> slot.toPrompt(index) },
            promptOrder = drafts.mapIndexed { index, slot -> TavernPresetPromptOrder(slot.identifier, slot.enabled, index) }
        )
    }

    /**
     * 将 [TavernPromptPreset] 序列化为与 [TavernPresetCodec.parse] 兼容的 JSON（往返安全），
     * 供 registry 的 preset 资源持久化；读取端仍走现有 TavernPresetCodec.parse。
     */
    fun buildPresetJson(preset: TavernPromptPreset): String {
        val root = JsonObject().apply {
            if (preset.name.isNotBlank()) addProperty("name", preset.name)
            preset.temperature?.let { addProperty("temperature", it) }
            preset.topP?.let { addProperty("top_p", it) }
            preset.topK?.let { addProperty("top_k", it) }
            preset.maxContext?.let { addProperty("max_context", it) }
            preset.maxTokens?.let { addProperty("max_tokens", it) }
        }
        root.add("prompts", JsonArray().also { array ->
            preset.prompts.forEach { p ->
                array.add(JsonObject().apply {
                    addProperty("name", p.name)
                    addProperty("identifier", p.identifier)
                    addProperty("content", p.content)
                    addProperty("role", p.role)
                    addProperty("system_prompt", p.systemPrompt)
                    addProperty("marker", p.marker)
                    addProperty("enabled", p.enabled)
                    addProperty("injection_position", p.injectionPosition)
                    addProperty("injection_depth", p.injectionDepth)
                    if (p.triggers.isNotEmpty()) {
                        add("triggers", JsonArray().also { p.triggers.forEach(it::add) })
                    }
                })
            }
        })
        if (preset.promptOrder.isNotEmpty()) {
            // 直序格式 [{identifier, enabled}, ...]，数组下标即 order，TavernPresetCodec 可直接读取
            root.add("prompt_order", JsonArray().also { array ->
                preset.promptOrder.forEach { order ->
                    array.add(JsonObject().apply {
                        addProperty("identifier", order.identifier)
                        addProperty("enabled", order.enabled)
                    })
                }
            })
        }
        return root.toString()
    }

    /** 保存校验：名称非空，且至少启用一个槽位。 */
    fun validatePreset(name: String, prompts: List<TavernPresetPrompt>): Boolean =
        name.isNotBlank() && prompts.any { it.enabled }

    /** 生成稳定的新预设 id（仅新建时调用一次，编辑时保留原 id）。 */
    fun newPresetId(): String = "preset_" + System.currentTimeMillis() + "_" + (100..999).random()
}

/**
 * C2 预设编辑器对话框：新建 / 编辑共用。默认列出基础 9 槽位（或已有预设的槽位），
 * 顶栏"添加槽位"可补充未覆盖的基础槽位；"完整模式"折叠区展示采样参数与 promptOrder 重排。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditorDialog(
    existing: TavernPromptPreset?,
    existingId: String?,
    isEn: Boolean,
    onDismiss: () -> Unit,
    onSave: (TavernPromptPreset) -> Unit
) {
    val initialSlots = if (existing != null) {
        existing.prompts
            .filter { it.identifier.isNotBlank() }
            .map { TavernPresetSlotDraft(it.identifier, it.name.ifBlank { it.identifier }, it.content, it.enabled) }
            .ifEmpty { TavernPresetEditor.defaultSlots() }
    } else {
        TavernPresetEditor.defaultSlots()
    }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var temperatureText by remember { mutableStateOf(existing?.temperature?.toString() ?: "") }
    var maxTokensText by remember { mutableStateOf(existing?.maxTokens?.toString() ?: "") }
    val slots = remember { mutableStateListOf<TavernPresetSlotDraft>().apply { addAll(initialSlots) } }
    var showFullMode by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var showRemoveHint by remember { mutableStateOf(false) }

    val availableIds = TavernPresetEditor.canAddSlot(slots.map { it.toPrompt(0) })
    val canSave = TavernPresetEditor.validatePreset(name, slots.map { it.toPrompt(0) })

    fun buildPreset(): TavernPromptPreset = TavernPromptPreset(
        name = name.trim(),
        temperature = temperatureText.trim().toDoubleOrNull(),
        maxTokens = maxTokensText.trim().toIntOrNull(),
        prompts = slots.mapIndexed { index, slot -> slot.toPrompt(index) },
        promptOrder = slots.mapIndexed { index, slot -> TavernPresetPromptOrder(slot.identifier, slot.enabled, index) }
    )

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
                        title = {
                            Text(
                                if (existingId != null) (if (isEn) "Edit Preset" else "编辑预设")
                                else (if (isEn) "New Preset" else "新建预设"),
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        },
                        actions = {
                            TextButton(
                                enabled = canSave,
                                onClick = { onSave(buildPreset()) }
                            ) {
                                Text(
                                    text = if (isEn) "Save" else "保存",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (canSave) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(if (isEn) "Preset Name（必填）" else "预设名称（必填）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 槽位列表 + 添加槽位
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEn) "Prompt Slots" else "提示词槽位",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Box {
                            TextButton(onClick = { addMenuExpanded = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isEn) "Add slot" else "添加槽位", fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = addMenuExpanded,
                                onDismissRequest = { addMenuExpanded = false }
                            ) {
                                if (availableIds.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(if (isEn) "All base slots added" else "基础槽位已全部添加") },
                                        enabled = false,
                                        onClick = {}
                                    )
                                }
                                availableIds.forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text(TavernPresetEditor.slotLabel(id, isEn)) },
                                        onClick = {
                                            addMenuExpanded = false
                                            slots.add(TavernPresetEditor.newSlot(id, isEn))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (slots.isEmpty()) {
                        Text(
                            text = if (isEn) "No slots. Add one to start building the preset." else "暂无槽位，请点击上方“添加槽位”。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    slots.forEachIndexed { index, slot ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(slot.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                slots.add(index - 1, slots.removeAt(index))
                                            }
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < slots.size - 1) {
                                                slots.add(index + 1, slots.removeAt(index))
                                            }
                                        },
                                        enabled = index < slots.size - 1
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down")
                                    }
                                    Switch(
                                        checked = slot.enabled,
                                        onCheckedChange = { slots[index] = slot.copy(enabled = it) },
                                        thumbContent = null
                                    )
                                }
                                OutlinedTextField(
                                    value = slot.content,
                                    onValueChange = { slots[index] = slot.copy(content = it) },
                                    label = { Text(if (isEn) "Slot content" else "槽位内容") },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // 完整模式折叠区：全部槽位已在上方展示，此处补充采样参数与 promptOrder 说明
                    TextButton(onClick = { showFullMode = !showFullMode }) {
                        Text(if (showFullMode) (if (isEn) "Hide full mode" else "收起完整模式")
                            else (if (isEn) "Full mode (sampling / order)" else "完整模式（采样参数 / prompt 顺序）"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (showFullMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = temperatureText,
                                onValueChange = { temperatureText = it },
                                label = { Text(if (isEn) "Temperature" else "温度") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxTokensText,
                                onValueChange = { maxTokensText = it },
                                label = { Text(if (isEn) "Max tokens" else "最大输出") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = (if (isEn) "Prompt order: " else "prompt 顺序：") + slots.joinToString(" → ") { it.identifier },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isEn)
                                "Use ↑/↓ on each slot to reorder, which updates promptOrder automatically."
                            else
                                "点击槽位上的 ↑/↓ 调整顺序，保存时按列表顺序生成 promptOrder。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { showRemoveHint = !showRemoveHint }) {
                            Text(if (showRemoveHint) (if (isEn) "Hide removal hint" else "收起删除提示")
                                else (if (isEn) "How to remove a slot?" else "如何删除槽位？"),
                                fontSize = 11.sp)
                        }
                        if (showRemoveHint) {
                            Text(
                                text = if (isEn)
                                    "Toggle a slot off to keep it but exclude it from the prompt; clearing its content is fine."
                                else
                                    "关闭槽位开关即可让其不参与提示词构建（仍保留内容与顺序）。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
