package com.loyea.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Locale

/**
 * 会话顶部 Token 用量小控件：仅展示 token 数量，不涉价格；每个会话独立计量。
 *
 * 形态：右上角小 pill（迷你 donut + 总量），点击弹出气泡（donut 环形图 + 本会话已用/Prompt/回复
 * + 上下文窗口占用条）。新会话（prompt+completion 均为 0）或预览（session 为 null）时不渲染。
 */
@Composable
fun TokenUsageWidget(
    session: ChatSession?,
    modelName: String,
    modifier: Modifier = Modifier
) {
    val promptTokens = session?.promptTokens ?: 0L
    val completionTokens = session?.completionTokens ?: 0L
    val total = promptTokens + completionTokens
    if (total <= 0L) return // 新会话 / 预览无数据时不显示

    var expanded by remember { mutableStateOf(false) }
    // Popup.offset 为像素 IntOffset，用 LocalDensity 换算 4dp 下移
    val popupOffset = with(LocalDensity.current) { IntOffset(0, 4.dp.roundToPx()) }

    Box(modifier = modifier, contentAlignment = Alignment.TopEnd) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TokenDonut(promptTokens = promptTokens, completionTokens = completionTokens, size = 10.dp, strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formatTokens(total),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = popupOffset,
                properties = PopupProperties(focusable = true)
            ) {
                TokenUsageBubble(
                    session = session,
                    modelName = modelName,
                    onDismiss = { expanded = false }
                )
            }
        }
    }
}

/**
 * 用量气泡：环形 donut + 三行数字 + 上下文窗口占用条
 */
@Composable
private fun TokenUsageBubble(
    session: ChatSession?,
    modelName: String,
    onDismiss: () -> Unit
) {
    val promptTokens = session?.promptTokens ?: 0L
    val completionTokens = session?.completionTokens ?: 0L
    val total = promptTokens + completionTokens
    val contextLimit = contextLimitFor(modelName)
    val contextUsed = (session?.lastContextTokens ?: 0L).coerceIn(0L, contextLimit)
    val contextProgress = if (contextLimit > 0) contextUsed.toFloat() / contextLimit else 0f

    Card(
        modifier = Modifier
            .width(220.dp)
            .clickable(onClick = onDismiss), // 点击气泡外部区收起
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TokenDonut(promptTokens = promptTokens, completionTokens = completionTokens, size = 56.dp, strokeWidth = 6.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "本会话已用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTokens(total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TokenUsageRow(label = "Prompt", tokens = promptTokens, color = MaterialTheme.colorScheme.primary)
            TokenUsageRow(label = "回复", tokens = completionTokens, color = MaterialTheme.colorScheme.tertiary)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "上下文窗口",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { contextProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatTokens(contextUsed)} / ${formatTokens(contextLimit)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TokenUsageRow(label: String, tokens: Long, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatTokens(tokens),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 迷你环形图：prompt 画满环，completion 按占比叠画同起点。
 * total <= 0 时不绘制，防除零。
 */
@Composable
private fun TokenDonut(
    promptTokens: Long,
    completionTokens: Long,
    size: Dp,
    strokeWidth: Dp
) {
    val total = promptTokens + completionTokens
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = Modifier.size(size)) {
        if (total <= 0L) return@Canvas
        val strokePx = strokeWidth.toPx()
        val arcSize = Size(size.toPx() - strokePx, size.toPx() - strokePx)
        val topLeft = Offset(strokePx / 2, strokePx / 2)
        drawArc(
            color = primary,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
        val completionSweep = completionTokens.toFloat() / total * 360f
        drawArc(
            color = tertiary,
            startAngle = -90f,
            sweepAngle = completionSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

/**
 * K/M 格式化：<1k 原样，<1M 一位小数 k，否则两位小数 M。
 */
private fun formatTokens(tokens: Long): String {
    return when {
        tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
        tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
        else -> tokens.toString()
    }
}

/**
 * 模型上下文窗口上限（展示用，不做配置）。
 */
private fun contextLimitFor(modelName: String): Long {
    val m = modelName.lowercase()
    return when {
        m.contains("deepseek-v4") -> 131_072L
        m.contains("deepseek") -> 131_072L
        m.contains("gpt-4o-mini") -> 128_000L
        m.contains("gpt-4") -> 128_000L
        m.contains("mimo") -> 32_000L
        else -> 128_000L
    }
}
