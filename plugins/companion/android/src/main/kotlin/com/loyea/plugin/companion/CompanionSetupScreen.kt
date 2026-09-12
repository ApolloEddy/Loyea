package com.loyea.plugin.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S-01 首次开启：单页可滚动简短设置，不做多页问卷。
 * 名称/头像可稍后改；物理感知默认开（FUN-05）；主动联系未设置过默认关（S-01 建议）。
 * UI-01：聊天服务未配置时提示先配置（保留已填内容）；UI-03：点击「开始陪伴」才提交。
 */
@Composable
fun CompanionSetupScreen(
    initial: CompanionConfig,
    chatConfigured: Boolean,
    onStart: (CompanionConfig) -> Unit,
    onCancel: () -> Unit,
    onConfigureChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayName by remember { mutableStateOf(initial.displayName) }
    var perception by remember { mutableStateOf(initial.perceptionEnabled) }
    var proactive by remember { mutableStateOf(initial.proactiveEnabled) }
    var submitting by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionPalette.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text("开启陪伴模式", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.Brand)
        Text(
            "用一个持续的聊天，记录你们的日常",
            fontSize = 14.sp,
            color = CompanionPalette.BrandMuted,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(28.dp))

        // 基础资料：显示名（D-01 可改显示名称；头像稍后支持）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(
                        Brush.verticalGradient(listOf(CompanionPalette.SendAmberTop, CompanionPalette.SendAmberBottom)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    displayName.trim().take(1).ifBlank { "L" }.uppercase(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = CompanionPalette.SendIcon
                )
            }
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text("显示名称", fontSize = 12.sp, color = CompanionPalette.Hint)
                BasicTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(20) },
                    textStyle = TextStyle(fontSize = 17.sp, color = CompanionPalette.TextPrimary),
                    cursorBrush = SolidColor(CompanionPalette.AmberDot),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
        Text(
            "默认 Loyea；名称与头像稍后可修改",
            fontSize = 12.sp,
            color = CompanionPalette.Hint,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        SetupSwitchRow(
            title = "物理感知",
            subtitle = "按需使用已允许的环境信息",
            checked = perception,
            onChange = { perception = it }
        )
        SetupSwitchRow(
            title = "主动联系",
            subtitle = "允许在合适的时候主动问候",
            checked = proactive,
            onChange = { proactive = it }
        )

        if (!chatConfigured) {
            // UI-01：未配置聊天服务时的明确入口；已填内容保留
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(16.dp),
                        fill = CompanionPalette.GlassFill,
                        border = CompanionPalette.BubbleUserBorder
                    )
                    .clickable(onClick = onConfigureChat)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = CompanionPalette.RoundBtnIcon)
                Spacer(Modifier.size(12.dp))
                Text("先配置聊天服务", fontSize = 14.sp, color = CompanionPalette.TextPrimary)
            }
        }

        Text(
            "普通模式的聊天会保留",
            fontSize = 12.sp,
            color = CompanionPalette.Hint,
            modifier = Modifier.padding(top = 20.dp)
        )

        Spacer(Modifier.height(32.dp))
        // 主按钮：开始陪伴（保存期间禁用防重入，UI-03）
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(
                    Brush.verticalGradient(listOf(CompanionPalette.SendAmberTop, CompanionPalette.SendAmberBottom)),
                    RoundedCornerShape(18.dp)
                )
                .clickable(enabled = !submitting) {
                    submitting = true
                    onStart(
                        initial.copy(
                            displayName = displayName.ifBlank { "Loyea" },
                            perceptionEnabled = perception,
                            proactiveEnabled = proactive
                        )
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text("开始陪伴", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.SendIcon)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .glassSurface(
                    shape = RoundedCornerShape(18.dp),
                    fill = CompanionPalette.GlassFill,
                    border = CompanionPalette.GlassBorder
                )
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Text("暂不开启", fontSize = 15.sp, color = CompanionPalette.ModeBtnText)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SetupSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = CompanionPalette.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CompanionPalette.SendAmberTop,
                checkedTrackColor = CompanionPalette.ModeActiveBorder,
                uncheckedThumbColor = CompanionPalette.ModeBtnText,
                uncheckedTrackColor = CompanionPalette.ModeBtnBg,
                uncheckedBorderColor = CompanionPalette.ModeBtnBorder
            )
        )
    }
}
