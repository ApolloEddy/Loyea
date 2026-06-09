package com.loyea.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loyea.ui.theme.LoyeaTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen(
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 声明级联动画状态
    val titleAlpha = remember { Animatable(0f) }
    val titleOffsetY = remember { Animatable(40f) }

    val buttonsAlpha = remember { Animatable(0f) }
    val buttonsOffsetY = remember { Animatable(30f) }

    // 触发级联动效
    LaunchedEffect(Unit) {
        // 1. Loyea 大标题淡入并向上漂浮
        launch {
            titleAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(600, easing = FastOutSlowInEasing)
            )
        }
        launch {
            titleOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(600, easing = FastOutSlowInEasing)
            )
        }

        // 2. 延迟 200ms 后，底部登录按钮组接续淡入上滑
        delay(200)
        launch {
            buttonsAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(600, easing = FastOutSlowInEasing)
            )
        }
        launch {
            buttonsOffsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(600, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // 中间 Logo & 标语
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = titleAlpha.value
                    translationY = titleOffsetY.value
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 优雅的 Serif 字体呈现 Loyea
            Text(
                text = "Loyea",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp,
                    lineHeight = 56.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Talk to Loyea. An AI assistant.",
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        // 底部登录按钮区
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = buttonsAlpha.value
                    translationY = buttonsOffsetY.value
                }
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Google 登录按钮 (假定以高对比度胶囊状圆角展示)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(25.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onContinueClick() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 模拟 Google G 图标
                Text(
                    text = "G",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Continue with Google",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Email 登录按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(MaterialTheme.colorScheme.onBackground) // 与背景相反色，形成主要视觉焦点
                    .clickable { onContinueClick() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Continue with Email",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.background
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 底部协议文字说明
            Text(
                text = "By continuing, you agree to Loyea's Terms of Service and Privacy Policy.",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WelcomeScreenPreview() {
    LoyeaTheme {
        WelcomeScreen(onContinueClick = {})
    }
}
