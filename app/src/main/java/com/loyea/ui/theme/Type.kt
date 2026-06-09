package com.loyea.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.loyea.R

// 声明 Anthropic 官方字体
val AnthropicSans = FontFamily(
    Font(R.font.anthropic_sans_romans, FontWeight.Normal),
    Font(R.font.anthropic_sans_italics, FontWeight.Normal, style = FontStyle.Italic)
)

val AnthropicSerif = FontFamily(
    Font(R.font.anthropic_serif_romans, FontWeight.Normal),
    Font(R.font.anthropic_serif_italics, FontWeight.Normal, style = FontStyle.Italic)
)

// 定义基础排版样式，应用 Anthropic 官方字体
val LoyeaTypography = Typography(
    // 标题，如 TopAppBar 上的标题
    titleMedium = TextStyle(
        fontFamily = AnthropicSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    // 聊天消息正文 - Claude 衬线体，带来极佳的阅读质感
    bodyLarge = TextStyle(
        fontFamily = AnthropicSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    // 辅助文本，如按钮、列表子项、提示文本等
    labelMedium = TextStyle(
        fontFamily = AnthropicSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    // 极小文本，如时间戳等
    labelSmall = TextStyle(
        fontFamily = AnthropicSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
