package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ObsidianDarkColorScheme = darkColorScheme(
    primary = ShieldCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1814),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = CyberCyan,
    onSecondary = Color(0xFF002024),
    secondaryContainer = Color(0xFF003B42),
    onSecondaryContainer = Color(0xFFA6F7FF),
    tertiary = EmeraldSafe,
    onTertiary = Color(0xFF002114),
    tertiaryContainer = Color(0xFF083D29),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = ObsidianBg,
    onBackground = ObsidianTextPrimary,
    surface = ObsidianSurface,
    onSurface = ObsidianTextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = ObsidianTextSecondary,
    surfaceContainerHigh = ObsidianSurfaceBright,
    outline = ObsidianOutline,
    error = CrimsonError,
    onError = Color.White
)

private val AmoledDarkColorScheme = ObsidianDarkColorScheme.copy(
    background = AmoledBg,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceElevated
)

private val DaylightLightColorScheme = lightColorScheme(
    primary = ShieldCoralDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD4),
    onPrimaryContainer = Color(0xFF3E0500),
    secondary = CyberCyanDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F9FF),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = EmeraldSafe,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = DaylightBg,
    onBackground = DaylightTextPrimary,
    surface = DaylightSurface,
    onSurface = DaylightTextPrimary,
    surfaceVariant = DaylightSurfaceElevated,
    onSurfaceVariant = DaylightTextSecondary,
    outline = DaylightOutline,
    error = CrimsonError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme && amoledBlack -> AmoledDarkColorScheme
        darkTheme -> ObsidianDarkColorScheme
        else -> DaylightLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
