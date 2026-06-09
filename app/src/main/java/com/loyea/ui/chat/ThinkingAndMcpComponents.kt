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
    
    // 运行状态时的齿轮无限旋转动画
    val infiniteTransition = rememberInfiniteTransition(label = "GearRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .clickable { 
                hasUserInteracted = true
                isExpanded = !isExpanded 
            }
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // 根据状态展示不同的图标与动效
                when (mcpCall.status) {
                    McpStatus.RUNNING -> {
                        Icon(
                            imageVector = Icons.Default.Settings, // 用齿轮表示工具
                            contentDescription = "Running Tool",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotation) // 无限旋转
                        )
                    }
                    McpStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Tool Success",
                            tint = Color(0xFF4CAF50), // 绿色对勾
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    McpStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Tool Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                Text(
                    text = mcpCall.actionText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                )
            }
            
            // 展开小三角
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
        
        // 展开展示参数和输出结果
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.03f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (mcpCall.input.isNotBlank()) {
                    Column {
                        Text(
                            text = "PARAMETERS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Text(
                            text = mcpCall.input,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                if (mcpCall.output.isNotBlank()) {
                    Column {
                        Text(
                            text = "RESULT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Text(
                            text = mcpCall.output,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
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
