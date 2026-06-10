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
                        onExport = { exportCharacterCard(context, card) },
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
    onExport: () -> Unit,
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

                IconButton(onClick = onExport) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "Export Card", modifier = Modifier.size(18.dp))
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
 * 弹窗表单：自定义创建角色卡
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePersonaDialog(
    appLanguage: String,
    onDismiss: () -> Unit,
    onSave: (CharacterCard) -> Unit
) {
    val isEn = appLanguage == "en"

    var name by remember { mutableStateOf("") }
    var intro by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var firstMessage by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf("") }
    var chatExamples by remember { mutableStateOf("") }

    // 头像背景色选择
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
                                            avatarUri = null,
                                            avatarColor = colors[selectedColorIndex],
                                            shortIntro = intro.ifBlank { if (isEn) "A unique custom AI companion." else "充满个性的自定义 AI 伙伴。" },
                                            systemPrompt = systemPrompt,
                                            personality = personality,
                                            scenario = scenario,
                                            firstMessage = firstMessage,
                                            chatExamples = chatExamples,
                                            isBuiltIn = false,
                                            creatorName = creator.ifBlank { if (isEn) "User Custom" else "用户自建" }
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
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 头像微光颜色选择
                    Text(
                        text = if (isEn) "Select avatar theme color" else "选择头像主题色",
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // 2. 表单字段
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
 * 导出角色卡：通过系统 Intent 分享 JSON 配置
 */
fun exportCharacterCard(context: Context, card: CharacterCard) {
    try {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val jsonStr = gson.toJson(card)

        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, jsonStr)
            type = "text/plain"
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "导出角色配置 [${card.name}]")
        context.startActivity(shareIntent)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "导出失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
