package com.patrick.copilotchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = GitHubBlueDark,
    onPrimary = GitHubSurface,
    primaryContainer = Color(0xFF1F3A5F),
    onPrimaryContainer = GitHubBlueDark,
    secondary = PurpleGrey80,
    onSecondary = PurpleGrey40,
    surface = GitHubSurface,
    surfaceVariant = GitHubSurfaceVariant,
    onSurfaceVariant = Color(0xFF8B949E),
    background = GitHubSurface,
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Color(0xFFF85149),
    errorContainer = Color(0xFF3D1F22),
    outline = GitHubBorder
)

private val LightColorScheme = lightColorScheme(
    primary = GitHubBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001D6C),
    secondary = PurpleGrey40,
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF6F8FA),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1F2328),
    onSurface = Color(0xFF1F2328),
    onSurfaceVariant = Color(0xFF656D76)
)

@Composable
fun CopilotChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
