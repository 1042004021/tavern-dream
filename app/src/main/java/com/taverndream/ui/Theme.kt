package com.taverndream.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFB49CFF),
    onPrimary = Color(0xFF1B1035),
    primaryContainer = Color(0xFF3B2A66),
    onPrimaryContainer = Color(0xFFE9DEFF),
    secondary = Color(0xFFF0C4A0),
    onSecondary = Color(0xFF2A1B10),
    background = Color(0xFF12101A),
    onBackground = Color(0xFFEDE8F5),
    surface = Color(0xFF1A1724),
    onSurface = Color(0xFFEDE8F5),
    surfaceVariant = Color(0xFF262233),
    onSurfaceVariant = Color(0xFFBFB8CE),
    error = Color(0xFFFFB4AB),
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF6A4CB8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF7A5900),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDF9B),
    onSecondaryContainer = Color(0xFF261A00),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFF8F5FC),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE9E1F2),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A757F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

/**
 * 主题：跟随系统 / 浅色 / 深色。
 * @param mode "system" | "light" | "dark"
 */
@Composable
fun TavernTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    // 状态栏/导航栏图标跟着主题走，浅色主题下用深色图标
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        content = content,
    )
}