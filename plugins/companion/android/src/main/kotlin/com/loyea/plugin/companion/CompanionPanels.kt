package com.loyea.plugin.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S-03 更多面板：底部弹出的短面板，顺序固定为「记忆」「查找记录」「陪伴设置」，
 * 底部附「物理感知」快捷开关（与设置页同一状态源）。UI-13：不列角色卡、世界书、
 * 会话管理、提示词模板等普通模式入口。
 */
@Composable
fun CompanionMoreSheet(
    perceptionEnabled: Boolean,
    onPerceptionChange: (Boolean) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Color(0xF20E0C09))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .size(36.dp, 4.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(14.dp))
            MoreRow(Icons.Rounded.Favorite, "记忆", "保存你们在意的事", onOpenMemory)
            MoreRow(Icons.Rounded.ManageSearch, "查找记录", "找到聊过的某句话", onOpenSearch)
            MoreRow(Icons.Rounded.Settings, "陪伴设置", "资料、感知与关闭入口", onOpenSettings)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("物理感知", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                    Text(
                        "按需使用已允许的环境信息",
                        fontSize = 12.sp,
                        color = CompanionPalette.Hint,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = perceptionEnabled,
                    onCheckedChange = onPerceptionChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CompanionPalette.SendAmberTop,
                        checkedTrackColor = CompanionPalette.ModeActiveBorder,
                        uncheckedThumbColor = CompanionPalette.ModeBtnText,
                        uncheckedTrackColor = CompanionPalette.ModeBtnBg,
                        uncheckedBorderColor = CompanionPalette.ModeBtnBorder
                    )
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(CompanionPalette.GlassFill, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = CompanionPalette.RoundBtnIcon, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column {
            Text(title, fontSize = 15.sp, color = CompanionPalette.TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
