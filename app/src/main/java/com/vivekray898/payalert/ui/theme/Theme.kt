package com.vivekray898.payalert.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F5DE),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4C6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF08201A),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FDCB4),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFF8CF9D0),
    secondary = Color(0xFFB2CCBE),
    onSecondary = Color(0xFF1E352B),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E1),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

@Composable
fun PayAlertTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
