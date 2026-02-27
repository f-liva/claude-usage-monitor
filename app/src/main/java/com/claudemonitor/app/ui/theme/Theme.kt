package com.claudemonitor.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Claude brand-inspired color palette
val ClaudeOrange = Color(0xFFD97706)
val ClaudeTan = Color(0xFFF5E6D3)
val ClaudeCream = Color(0xFFFAF5EF)
val ClaudeDark = Color(0xFF1A1612)
val ClaudeBrown = Color(0xFF8B6914)

val Success = Color(0xFF16A34A)
val Warning = Color(0xFFEAB308)
val Danger = Color(0xFFDC2626)

private val DarkColorScheme = darkColorScheme(
    primary = ClaudeOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3D2800),
    onPrimaryContainer = ClaudeTan,
    secondary = Color(0xFFD4BFA0),
    onSecondary = Color(0xFF3A2F1F),
    secondaryContainer = Color(0xFF524434),
    onSecondaryContainer = Color(0xFFF0DEBB),
    tertiary = Color(0xFFAACDB0),
    onTertiary = Color(0xFF163722),
    tertiaryContainer = Color(0xFF2E4E37),
    onTertiaryContainer = Color(0xFFC6E9CB),
    background = Color(0xFF0F0D0B),
    onBackground = Color(0xFFEBE1D6),
    surface = Color(0xFF171411),
    onSurface = Color(0xFFEBE1D6),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),
    outline = Color(0xFF9C8E80),
    outlineVariant = Color(0xFF4F4539),
    surfaceContainerLowest = Color(0xFF0A0806),
    surfaceContainerLow = Color(0xFF1E1B17),
    surfaceContainer = Color(0xFF231F1B),
    surfaceContainerHigh = Color(0xFF2D2A25),
    surfaceContainerHighest = Color(0xFF383430),
)

private val LightColorScheme = lightColorScheme(
    primary = ClaudeOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB3),
    onPrimaryContainer = Color(0xFF2A1800),
    secondary = Color(0xFF6F5B3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF9DEB8),
    onSecondaryContainer = Color(0xFF271904),
    tertiary = Color(0xFF51634F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4E8CF),
    onTertiaryContainer = Color(0xFF0F2010),
    background = ClaudeCream,
    onBackground = Color(0xFF1E1B16),
    surface = Color.White,
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFF0E0CF),
    onSurfaceVariant = Color(0xFF4F4539),
    outline = Color(0xFF817567),
    outlineVariant = Color(0xFFD3C4B4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF5ED),
    surfaceContainer = Color(0xFFF6EFE7),
    surfaceContainerHigh = Color(0xFFF0E9E1),
    surfaceContainerHighest = Color(0xFFEBE4DC),
)

@Composable
fun ClaudeMonitorTheme(
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
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
