package com.loyea.plugin.companion

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 陪伴模式视觉语言：Web 设计稿「Loyea Neural Living」的暗色琥珀体系 + 玻璃拟态质感。
 * 深色近黑底上的玻璃面 = 半透明填充 + 顶部高光渐变 + 细亮边；文字全部用高暖白保证辨识度。
 */
object CompanionPalette {
    val Bg = Color(0xFF050608)
    val Brand = Color(0xFFEEE3D1)
    val BrandMuted = Color(0xFFA29785)
    val AmberDot = Color(0xFFD9A357)
    val Presence = Color(0xFFBB9866)
    val Hint = Color(0xFF998771)
    val TextPrimary = Color(0xFFF2E9DA)
    val TextOnUser = Color(0xFFFCF4E8)
    val Placeholder = Color(0xFF8A7E6C)

    // 玻璃气泡
    val BubbleAiFill = Color(0x14FFFFFF)
    val BubbleAiBorder = Color(0x24FFFFFF)
    val BubbleUserFill = Color(0x30D9A357)
    val BubbleUserBorder = Color(0x55BA761F)

    // 玻璃输入条 / 按钮
    val GlassFill = Color(0x0FFFFFFF)
    val GlassBorder = Color(0x26FFFFFF)
    val GlassPressedFill = Color(0x1AFFFFFF)
    val RoundBtnBg = Color(0xFF11100E)
    val RoundBtnBorder = Color(0xFF342B1F)
    val RoundBtnIcon = Color(0xFFC0A477)

    // 状态按钮（静静陪伴 / 倾听 / 思考）
    val ModeBtnBg = Color(0xFF0C0C0D)
    val ModeBtnBorder = Color(0xFF28251F)
    val ModeBtnText = Color(0xFFB2A795)
    val ModeActiveBg = Color(0xFF211A10)
    val ModeActiveBorder = Color(0xFF70522B)
    val ModeActiveText = Color(0xFFE7C28A)

    // 发送按钮（琥珀渐变）
    val SendAmberTop = Color(0xFFE7BC6B)
    val SendAmberBottom = Color(0xFFB4762A)
    val SendIcon = Color(0xFF241708)
}

/** 气泡形状：发言方一侧上角收紧（5dp），其余 18dp。 */
fun glassBubbleShape(isUser: Boolean): RoundedCornerShape = RoundedCornerShape(
    topStart = if (isUser) 18.dp else 5.dp,
    topEnd = if (isUser) 5.dp else 18.dp,
    bottomStart = 18.dp,
    bottomEnd = 18.dp
)

/**
 * 玻璃拟态表面：半透明填充 → 顶部高光渐变 → 细亮边。
 * 深色背景上以分层透光模拟磨砂玻璃，保证气泡文字对比度（辨识度要求）。
 */
fun Modifier.glassSurface(
    shape: Shape,
    fill: Color,
    border: Color,
    borderWidth: Dp = 1.dp,
    sheenAlpha: Float = 0.06f,
): Modifier = this
    .background(fill, shape)
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = sheenAlpha),
            0.5f to Color.White.copy(alpha = sheenAlpha * 0.25f),
            1f to Color.White.copy(alpha = 0f)
        ),
        shape
    )
    .border(borderWidth, border, shape)

/**
 * 陪伴模式系统栏外观：状态栏/导航栏并入暗色画布，图标改浅色。
 * API 35+ 强制 edge-to-edge 且忽略 window 颜色，故同时声明 decor 不裁切，
 * 让插件的暗色背景自然延伸到系统栏后面；离开组合时恢复宿主原状。
 */
@Composable
fun CompanionDarkWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val insets = WindowCompat.getInsetsController(window, view)
        val prevStatus = window.statusBarColor
        val prevNav = window.navigationBarColor
        val prevLightStatus = insets.isAppearanceLightStatusBars
        val prevLightNav = insets.isAppearanceLightNavigationBars
        val dark = CompanionPalette.Bg.toArgb()
        window.statusBarColor = dark
        window.navigationBarColor = dark
        // 宿主从不调用 setDecorFitsSystemWindows（默认 true），恢复时回到 true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
        onDispose {
            window.statusBarColor = prevStatus
            window.navigationBarColor = prevNav
            WindowCompat.setDecorFitsSystemWindows(window, true)
            insets.isAppearanceLightStatusBars = prevLightStatus
            insets.isAppearanceLightNavigationBars = prevLightNav
        }
    }
}
