package com.loyea.ui.chat

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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TavernScreen(
    characterCardList: List<CharacterCard>,
    onCharacterCardListSave: (List<CharacterCard>) -> Unit,
    appLanguage: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val isEn = appLanguage == "en"

    var showCreateDialog by remember { mutableStateOf(false) }
    var cardToDelete by remember { mutableStateOf<CharacterCard?>(null) }

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

    // 2. JSON 导入启动器
    val jsonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonStr = inputStream.bufferedReader().use { it.readText() }
                    val parsedCard = TavernCardParser.parseJsonCard(jsonStr)
                    if (parsedCard != null) {
                        onCharacterCardListSave(characterCardList + parsedCard)
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
                    IconButton(onClick = { jsonImportLauncher.launch("application/json") }) {
                        Icon(imageVector = Icons.Default.Code, contentDescription = "Import JSON", tint = MaterialTheme.colorScheme.primary)
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
                }
            }

            // 角色卡瀑布流/列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(characterCardList) { card ->
                    TavernCardItem(
                        card = card,
                        appLanguage = appLanguage,
                        onExportPng = { shareCharacterCardPng(context, card) },
                        onExportJson = { shareCharacterCardJson(context, card) },
                        onDelete = { cardToDelete = card }
                    )
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
                                    if (name.isNotBlank() && systemPrompt.isNotBlank()) {
                                        val newCard = CharacterCard(
                                            id = "char_" + System.currentTimeMillis() + "_" + (100..999).random(),
                                            name = name,
                                            avatarUri = localAvatarUri,
                                            avatarColor = colors[selectedColorIndex],
                                            shortIntro = intro.ifBlank { if (isEn) "A unique custom AI companion." else "充满个性的自定义 AI 伙伴。" },
                                            systemPrompt = systemPrompt,
                                            personality = personality,
                                            scenario = scenario,
                                            firstMessage = firstMessage,
                                            chatExamples = chatExamples,
                                            isBuiltIn = false,
                                            creatorName = creator.ifBlank { if (isEn) "User Custom" else "用户自建" },
                                            backgroundUri = localBackgroundUri
                                        )
                                        onSave(newCard)
                                    }
                                },
                                enabled = name.isNotBlank() && systemPrompt.isNotBlank()
                            ) {
                                Text(
                                    text = if (isEn) "Save" else "保存",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (name.isNotBlank() && systemPrompt.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
    
    val data = mutableMapOf<String, Any>()
    data["name"] = card.name
    data["description"] = card.shortIntro
    data["short_description"] = card.shortIntro
    data["personality"] = card.personality
    data["scenario"] = card.scenario
    data["first_mes"] = card.firstMessage
    data["mes_example"] = card.chatExamples
    data["creator_notes"] = ""
    data["system_prompt"] = card.systemPrompt
    data["post_history_instructions"] = ""
    data["alternate_greetings"] = emptyList<String>()
    data["creator"] = card.creatorName ?: "Loyea"
    data["character_version"] = "1.0"
    data["extensions"] = emptyMap<String, Any>()

    val root = mutableMapOf<String, Any>()
    root["spec"] = "chara_card_v2"
    root["spec_version"] = "2.0"
    root["data"] = data

    return gson.toJson(root)
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
