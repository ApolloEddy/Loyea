package com.loyea.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 1. MCP 工具调用项布局
@Composable
fun McpCallItem(
    mcpCall: McpCall,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(mcpCall.status == McpStatus.RUNNING) }
    var hasUserInteracted by remember { mutableStateOf(false) }

    // 当工具执行状态改变时，若用户未曾手动干预，则根据状态自动切换折叠 (RUNNING 时展开，SUCCESS/FAILED 时收起)
    LaunchedEffect(mcpCall.status) {
        if (!hasUserInteracted) {
            isExpanded = mcpCall.status == McpStatus.RUNNING
        }
    }
    
    // 运行状态时的旋转动画
    val infiniteTransition = rememberInfiniteTransition(label = "GearRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    val iconColor = when (mcpCall.status) {
        McpStatus.RUNNING -> MaterialTheme.colorScheme.primary
        McpStatus.SUCCESS -> Color(0xFF4CAF50)
        McpStatus.FAILED -> Color(0xFFE53935)
    }

    val toolEmoji = when {
        mcpCall.toolName.contains("wifi") -> "📶"
        mcpCall.toolName.contains("noise") -> "🔊"
        mcpCall.toolName.contains("location") -> "📍"
        mcpCall.toolName.contains("forecast") -> "📅"
        mcpCall.toolName.contains("weather") -> "🌤️"
        mcpCall.toolName.contains("light") -> "💡"
        mcpCall.toolName.contains("battery") -> "🔋"
        mcpCall.toolName.contains("bluetooth") -> "📡"
        mcpCall.toolName.contains("activity") -> "🏃"
        mcpCall.toolName.contains("health") -> "🏥"
        mcpCall.toolName.contains("heart") -> "❤️"
        mcpCall.toolName.contains("step") -> "👣"
        mcpCall.toolName.contains("sleep") -> "🌙"
        mcpCall.toolName.contains("blood_pressure") -> "🩸"
        mcpCall.toolName.contains("time") -> "🕒"
        mcpCall.toolName.contains("web_search") || mcpCall.toolName.contains("google_search") -> "🔍"
        else -> "🛠️"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f))
            .clickable { 
                hasUserInteracted = true
                isExpanded = !isExpanded 
            }
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                when (mcpCall.status) {
                    McpStatus.RUNNING -> {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Running",
                            tint = iconColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp).rotate(rotation)
                        )
                    }
                    McpStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    McpStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Close, // 使用红叉
                            contentDescription = "Failed",
                            tint = iconColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = toolEmoji,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 4.dp)
            )

            Text(
                text = mcpCall.actionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
            
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp)
            )
        }
        
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mcpCall.input.isNotBlank() && mcpCall.input != "{}") {
                    Column {
                        Text(
                            text = "参数详情",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        ) {
                            Text(
                                text = mcpCall.input,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
                
                if (mcpCall.output.isNotBlank()) {
                    Column {
                        Text(
                            text = "执行结果",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        val beautifiedResult = remember(mcpCall.output) {
                            beautifyMcpResult(mcpCall.output)
                        }
                        Text(
                            text = beautifiedResult,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 美化处理 MCP 返回的 JSON 字符串，提取核心文本内容
 */
fun beautifyMcpResult(rawJson: String): String {
    if (rawJson.startsWith("[MCP")) return rawJson // 已经是错误提示
    try {
        val gson = com.google.gson.Gson()
        val map = gson.fromJson<Map<String, Any>>(rawJson, object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type)
        
        // 尝试解析标准的 MCP content 数组
        val content = map["content"] as? List<*>
        if (content != null) {
            val sb = StringBuilder()
            content.forEach { item ->
                if (item is Map<*, *>) {
                    val text = item["text"] as? String
                    if (text != null) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(text)
                    }
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        
        // 尝试解析 result 字段
        val result = map["result"] as? String
        if (result != null) return result
        
        // 如果都不是，尝试扁平化展示 Map
        return rawJson
    } catch (e: Exception) {
        return rawJson
    }
}

// 2. Thinking 深度推理折叠布局
@Composable
fun ThinkingProcessLayout(
    thoughts: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    durationSeconds: Int = 0,
    isStillThinking: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 小箭头随折叠状态平滑旋转 0 到 90 度
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(300),
        label = "ArrowRotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.02f))
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy))
    ) {
        // 顶部触发栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 8.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(arrowRotation)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = if (isStillThinking) {
                    "Thinking..."
                } else {
                    "Thought for ${durationSeconds}s"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
        
        // 展开推理链文本
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 10.dp, bottom = 10.dp)
            ) {
                // 左侧淡灰色竖边框
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        .align(Alignment.CenterVertically)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = thoughts,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
