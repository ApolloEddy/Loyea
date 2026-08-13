package com.loyea.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 模型选择下拉列表顶部的会话 Token 用量头：仅展示 token 数量，不涉价格；每个会话独立计量。
 *
 * 形态：迷你 donut + 本会话已用总量 + Prompt/回复两行 + DeepSeek 前缀缓存命中率 + 上下文窗口占用条。
 * 新会话（prompt+completion 均为 0）或预览（session 为 null）时不渲染（total<=0 直接 return）。
 */
@Composable
fun TokenUsageMenuHeader(
    session: ChatSession?,
    modelName: String,
    modifier: Modifier = Modifier
) {
    val promptTokens = session?.promptTokens ?: 0L
    val completionTokens = session?.completionTokens ?: 0L
    val total = promptTokens + completionTokens
    if (total <= 0L) return // 新会话 / 预览无数据时不显示

    val contextLimit = contextLimitFor(modelName)
    val contextUsed = (session?.lastContextTokens ?: 0L).coerceIn(0L, contextLimit)
    val contextProgress = if (contextLimit > 0) contextUsed.toFloat() / contextLimit else 0f

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenDonut(promptTokens = promptTokens, completionTokens = completionTokens, size = 16.dp, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "本会话已用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatTokens(total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        TokenUsageRow(label = "Prompt", tokens = promptTokens, color = MaterialTheme.colorScheme.primary)
        TokenUsageRow(label = "回复", tokens = completionTokens, color = MaterialTheme.colorScheme.tertiary)
        // DeepSeek 前缀缓存命中率（累计 hit / (hit+miss)）；无缓存数据时不渲染
        val cacheHit = session?.promptCacheHitTokens ?: 0L
        val cacheMiss = session?.promptCacheMissTokens ?: 0L
        if (cacheHit + cacheMiss > 0L) {
            val rate = cacheHit.toFloat() / (cacheHit + cacheMiss) * 100f
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "缓存命中 ${String.format(Locale.US, "%.1f%%", rate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${formatTokens(cacheHit)} / ${formatTokens(cacheHit + cacheMiss)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { contextProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "上下文 ${formatTokens(contextUsed)} / ${formatTokens(contextLimit)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
