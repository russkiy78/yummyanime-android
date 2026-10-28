package com.yani.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF5F6D)
val AccentSoft = Color(0xFFFFB0B6)
val Gold = Color(0xFFFFC857)
val Background = Color(0xFF0E0E13)
val Surface = Color(0xFF16161D)
val SurfaceHigh = Color(0xFF20202A)
val SurfaceHighest = Color(0xFF2A2A36)
val OnSurfaceMuted = Color(0xFFA6A6B4)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1C24),
    onPrimaryContainer = AccentSoft,
    secondary = AccentSoft,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4A1E28),
    onSecondaryContainer = AccentSoft,
    background = Background,
    onBackground = Color(0xFFEDEDF2),
    surface = Surface,
    onSurface = Color(0xFFEDEDF2),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    outline = Color(0xFF4A4A58),
    outlineVariant = Color(0xFF32323E),
)

@Composable
fun YummyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
