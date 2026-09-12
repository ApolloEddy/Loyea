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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.chat.ChatViewModel

/**
 * S-04 陪伴设置：基础资料（显示层）、感知与联系、其他（关闭陪伴模式）。
 * UI-14：只提供显示层设置，没有人格滑杆/MBTI/情绪模型开关；
 * UI-16：任何层级不开放角色卡与世界书管理。
 * R-09：头像可在模式内更换（本地持久化副本）；聊天服务提供模式内快速切换 API 配置，
 * 模型管理等高级设置仍复用普通模式配置（UI-15）。
 */
@Composable
fun CompanionSettingsScreen(
    viewModel: ChatViewModel,
    config: CompanionConfig,
    onConfigChange: (CompanionConfig) -> Unit,
    onBack: () -> Unit,
    onOpenPerception: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onDisableCompanion: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var displayName by remember { mutableStateOf(config.displayName) }
    var calledName by remember { mutableStateOf(config.userCalledName) }
    var confirmDisable by remember { mutableStateOf(false) }
    var showApiPicker by remember { mutableStateOf(false) }

    // R-09 头像：拷贝为应用内持久化副本（cache 临时文件会被系统清理），再更新配置
    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val avatarFile = java.io.File(context.filesDir, "companion/avatar.jpg")
                avatarFile.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    avatarFile.outputStream().use { output -> input.copyTo(output) }
                }
                onConfigChange(config.copy(avatarUri = avatarFile.absolutePath))
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
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = CompanionPalette.Brand
                )
            }
            Text("陪伴设置", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.Brand)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            SectionLabel("基础资料")
            SettingsGroup {
                // R-09：头像更换
                Row(
                    Modifier.fillMaxWidth().clickable {
                        avatarPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("头像", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                        Text(
                            if (config.avatarUri.isBlank()) "设置陪伴对象的头像" else "点击更换",
                            fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    val avatarBitmap = if (config.avatarUri.isNotBlank()) {
                        com.loyea.ui.chat.rememberLocalImagePainter(config.avatarUri)
                    } else null
                    if (avatarBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = avatarBitmap,
                            contentDescription = "当前头像",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(50))
                        )
                    } else {
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(CompanionPalette.GlassFill, RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(config.displayName.take(1), fontSize = 18.sp, color = CompanionPalette.Brand)
                        }
                    }
                }
                GroupDivider()
                GroupTextField("陪伴对象显示名称", displayName) {
                    displayName = it.take(20)
                    onConfigChange(config.copy(displayName = displayName.ifBlank { "Loyea" }))
                }
                GroupDivider()
                GroupTextField("希望被称呼的名字", calledName) {
                    calledName = it.take(20)
                    onConfigChange(config.copy(userCalledName = calledName))
                }
            }
            Text(
                "没有人格参数可调；名字只是显示层设置",
                fontSize = 12.sp,
                color = CompanionPalette.Hint,
                modifier = Modifier.padding(top = 8.dp)
            )

            SectionLabel("感知与联系", topPadding = 24)
            SettingsGroup {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPerception)
                        .padding(16.dp)
                ) {
                    Column {
                        Text("感知与主动联系", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                        Text(
                            "总开关、来源授权与免打扰时段",
                            fontSize = 12.sp,
                            color = CompanionPalette.Hint,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }

            SectionLabel("记录", topPadding = 24)
            SettingsGroup {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenData)
                        .padding(16.dp)
                ) {
                    Text("数据与备份", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                }
            }

            SectionLabel("聊天服务", topPadding = 24)
            SettingsGroup {
                // R-09：模式内快速切换聊天服务（激活的 API 配置）；模型/多模态等
                // 高级设置仍复用普通模式配置（UI-15）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showApiPicker = true }
                        .padding(16.dp)
                ) {
                    Column {
                        Text("聊天服务", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                        val activeName = viewModel.apiConfigList.value
                            .firstOrNull { it.id == viewModel.activeConfigId.value }?.name
                        Text(
                            if (activeName.isNullOrBlank()) "未选择模型服务，点击选择"
                            else "当前：$activeName · 点击切换",
                            fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }

            SectionLabel("其他", topPadding = 24)
            SettingsGroup {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable { confirmDisable = true }
                        .padding(16.dp)
                ) {
                    Text("关闭陪伴模式", fontSize = 15.sp, color = Color(0xFFFF9C85))
                }
            }
            if (confirmDisable) {
                Text(
                    "关闭只是回到普通模式，聊天与记忆都会保留；再次开启时一切如旧。",
                    fontSize = 12.sp,
                    color = CompanionPalette.Hint,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 12.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(CompanionPalette.GlassFill, RoundedCornerShape(14.dp))
                            .clickable { confirmDisable = false }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("取消", fontSize = 14.sp, color = CompanionPalette.TextPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .background(Color(0x33BA761F), RoundedCornerShape(14.dp))
                            .clickable(onClick = onDisableCompanion)
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("确认关闭", fontSize = 14.sp, color = CompanionPalette.TextOnUser)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // R-09：聊天服务快速切换
    if (showApiPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showApiPicker = false },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showApiPicker = false }) {
                    Text("关闭", color = CompanionPalette.Hint)
                }
            },
            title = { Text("聊天服务", color = CompanionPalette.TextPrimary) },
            text = {
                Column {
                    val configs = viewModel.apiConfigList.value.filter { it.isEnabled }
                    if (configs.isEmpty()) {
                        Text("还没有可用的 API 配置；请关闭陪伴模式后在设置中添加。", fontSize = 13.sp, color = CompanionPalette.Hint)
                    }
                    configs.forEach { api ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectActiveConfig(api.id)
                                    showApiPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                api.name,
                                fontSize = 14.sp,
                                color = if (api.id == viewModel.activeConfigId.value)
                                    CompanionPalette.ModeActiveText else CompanionPalette.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (api.id == viewModel.activeConfigId.value) {
                                Text("使用中", fontSize = 12.sp, color = CompanionPalette.ModeActiveText)
                            }
                        }
                    }
                }
            },
            containerColor = Color(0xFF14110D)
        )
    }
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
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(16.dp),
                fill = CompanionPalette.GlassFill,
                border = CompanionPalette.GlassBorder,
                sheenAlpha = 0.04f
            )
    ) {
        content()
    }
}

@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(Color(0x14FFFFFF))
    )
}

@Composable
private fun GroupTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, fontSize = 12.sp, color = CompanionPalette.Hint)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = CompanionPalette.TextPrimary),
            cursorBrush = SolidColor(CompanionPalette.AmberDot),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}
