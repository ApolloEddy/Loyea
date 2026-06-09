package com.loyea.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = LoyeaDarkPrimary,
    background = LoyeaDarkBg,
    onBackground = LoyeaDarkText,
    surface = LoyeaDarkSurface,
    onSurface = LoyeaDarkText,
    secondaryContainer = LoyeaDarkBubbleUser,
    outline = LoyeaDarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = LoyeaLightPrimary,
    background = LoyeaLightBg,
    onBackground = LoyeaLightText,
    surface = LoyeaLightSurface,
    onSurface = LoyeaLightText,
    secondaryContainer = LoyeaLightBubbleUser,
    outline = LoyeaLightOutline
)

@Composable
fun LoyeaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LoyeaTypography,
        content = content
    )
}
