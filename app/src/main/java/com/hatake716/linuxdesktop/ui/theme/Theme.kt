package com.hatake716.linuxdesktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppleBlue = Color(0xFF007AFF)
val SuccessGreen = Color(0xFF34C759)
val WarningOrange = Color(0xFFFF9500)
val DangerRed = Color(0xFFFF3B30)
val LightGroupedBackground = Color(0xFFF2F2F7)
val DarkGroupedBackground = Color(0xFF000000)
val DarkCard = Color(0xFF1C1C1E)

private val LightColors = lightColorScheme(
    primary = AppleBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF5856D6),
    background = LightGroupedBackground,
    surface = Color.White,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF636366),
    error = DangerRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    secondary = Color(0xFF5E5CE6),
    background = DarkGroupedBackground,
    surface = DarkCard,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurface = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFFAEAEB2),
    error = Color(0xFFFF453A),
)

@Composable
fun LinuxDesktopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
