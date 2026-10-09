package com.smartplug.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.smartplug.app.data.local.AppThemeMode

private val Color0F = androidx.compose.ui.graphics.Color(0xFF060E1C)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color0F,
    secondary = AccentGreen,
    tertiary = AccentAmber,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceMuted,
    error = AccentRed,
)

private val LightColors = lightColorScheme(
    primary = AccentBlueDim,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = AccentGreenDim,
    tertiary = AccentAmber,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceMuted,
    error = AccentRed,
)

@Composable
fun SmartPlugTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val colorScheme = if (useDarkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SmartPlugTypography,
        content = content,
    )
}
