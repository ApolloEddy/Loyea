package com.loyea.plugin.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** 感知来源四态（PER-01）：已关闭 / 待授权 / 可用 / 无数据或设备不支持。 */
private enum class SourceState { AVAILABLE, NEED_AUTH, NO_DATA }

/**
 * S-07 感知与主动联系（陪伴设置二级页）。
 * PER-01：逐项区分「已关闭/待授权/可用/无数据」，授权经系统流程，拒绝不循环弹窗；
 * PER-05：明示环境信息可能随对话发送给所配模型服务；
 * ACT-04：通知未授权时提示「开启通知后可主动联系」；
 * 免打扰支持跨午夜区间（默认 23:00–08:00，可修改）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionPerceptionScreen(
    config: CompanionConfig,
    onConfigChange: (CompanionConfig) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val masterOn = config.perceptionEnabled

    // 免打扰时间选择
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }

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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = CompanionPalette.Brand)
            }
            Text("感知与主动联系", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = CompanionPalette.Brand)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            SectionLabel("总开关")
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("物理感知", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                        Text(
                            "允许按需使用感知能力；关闭即全部来源停用",
                            fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    PerceptionSwitch(masterOn) { onConfigChange(config.copy(perceptionEnabled = it)) }
                }
            }

            SectionLabel("来源授权", topPadding = 24)
            SettingsGroup {
                SourceRow("时间与电量", masterOn = masterOn, state = if (masterOn) SourceState.AVAILABLE else SourceState.NO_DATA, note = "在需要的对话回合读取")
                GroupDivider()
                SourceRow(
                    "位置",
                    masterOn = masterOn,
                    state = if (!masterOn) SourceState.NO_DATA else stateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED
                    ),
                    note = "沿用已授权的定位来源",
                    needGate = true
                ) {
                    openAppSettings(context)
                }
                GroupDivider()
                SourceRow(
                    "麦克风（环境噪声）",
                    masterOn = masterOn,
                    state = if (!masterOn) SourceState.NO_DATA else stateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    ),
                    note = "仅按需短时采样，不常驻录音",
                    needGate = true
                ) {
                    openAppSettings(context)
                }
                GroupDivider()
                SourceRow(
                    "运动与环境光传感器",
                    masterOn = masterOn,
                    state = if (!masterOn) SourceState.NO_DATA else sensorAvailable(context),
                    note = "取决于设备传感器"
                )
                GroupDivider()
                SourceRow(
                    "健康连接",
                    masterOn = masterOn,
                    state = SourceState.NO_DATA,
                    note = "需安装健康连接并完成授权"
                )
            }
            Text(
                "使用到的环境信息可能随对话发送给你配置的模型服务；感知来源不会在后台持续采集。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = CompanionPalette.Hint,
                modifier = Modifier.padding(top = 10.dp)
            )

            SectionLabel("主动联系", topPadding = 24)
            SettingsGroup {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许主动问候", fontSize = 15.sp, color = CompanionPalette.TextPrimary)
                        Text(
                            notificationHint(context),
                            fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    PerceptionSwitch(config.proactiveEnabled) { onConfigChange(config.copy(proactiveEnabled = it)) }
                }
                GroupDivider()
                DndRow(
                    label = "免打扰开始",
                    minutes = config.dndStartMinute
                ) { editingStart = true }
                GroupDivider()
                DndRow(
                    label = "免打扰结束",
                    minutes = config.dndEndMinute
                ) { editingEnd = true }
            }
            Text(
                "免打扰时段内不会主动打扰；区间支持跨午夜（如 23:00 → 08:00）。",
                fontSize = 12.sp,
                color = CompanionPalette.Hint,
                modifier = Modifier.padding(top = 10.dp)
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (editingStart || editingEnd) {
        val isStart = editingStart
        val initialMinutes = if (isStart) config.dndStartMinute else config.dndEndMinute
        val timeState = rememberTimePickerState(
            initialHour = initialMinutes / 60,
            initialMinute = initialMinutes % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { editingStart = false; editingEnd = false },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = timeState.hour * 60 + timeState.minute
                    onConfigChange(
                        if (isStart) config.copy(dndStartMinute = minutes)
                        else config.copy(dndEndMinute = minutes)
                    )
                    editingStart = false
                    editingEnd = false
                }) { Text("确定", color = CompanionPalette.ModeActiveText) }
            },
            dismissButton = {
                TextButton(onClick = { editingStart = false; editingEnd = false }) {
                    Text("取消", color = CompanionPalette.Hint)
                }
            },
            title = { Text(if (isStart) "免打扰开始时间" else "免打扰结束时间", color = CompanionPalette.TextPrimary) },
            text = { TimePicker(state = timeState) },
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
    ) { content() }
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
private fun PerceptionSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
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

private fun stateOf(granted: Boolean) = if (granted) SourceState.AVAILABLE else SourceState.NEED_AUTH

private fun sensorAvailable(context: Context): SourceState {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return SourceState.NO_DATA
    val hasLight = sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    val hasMotion = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    return if (hasLight || hasMotion) SourceState.AVAILABLE else SourceState.NO_DATA
}

private fun notificationHint(context: Context): String {
    val granted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
    return if (granted) "在合适的时机于后台问候" else "开启系统通知后才能主动联系"
}

private fun openAppSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.fromParts("package", context.packageName, null)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun SourceRow(
    title: String,
    masterOn: Boolean,
    state: SourceState,
    note: String,
    needGate: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val (label, color) = when {
        !masterOn -> "已关闭" to CompanionPalette.Hint
        state == SourceState.NEED_AUTH -> "待授权" to Color(0xFFE7C28A)
        state == SourceState.NO_DATA -> "无数据／设备不支持" to CompanionPalette.Hint
        else -> "可用" to CompanionPalette.Presence
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = CompanionPalette.TextPrimary)
            Text(note, fontSize = 12.sp, color = CompanionPalette.Hint, modifier = Modifier.padding(top = 2.dp))
        }
        if (needGate && onClick != null && state == SourceState.NEED_AUTH) {
            Text("去授权", fontSize = 13.sp, color = CompanionPalette.ModeActiveText)
            Spacer(Modifier.size(8.dp))
        }
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 13.sp, color = CompanionPalette.TextPrimary)
    }
}

// 总开关状态注入：SourceRow 内「已关闭/无数据」文案依赖总开关；以顶层可变快照传递，
// 避免为每行扩展签名（页面组合期间为常量）。

@Composable
private fun DndRow(label: String, minutes: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = CompanionPalette.TextPrimary, modifier = Modifier.weight(1f))
        Text(
            "%02d:%02d".format(minutes / 60, minutes % 60),
            fontSize = 15.sp,
            color = CompanionPalette.ModeActiveText
        )
    }
}
