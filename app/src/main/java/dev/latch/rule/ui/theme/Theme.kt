package dev.latch.rule.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = LatchPalette.Primary,
    onPrimary = LatchPalette.OnPrimary,
    primaryContainer = LatchPalette.PrimaryContainer,
    onPrimaryContainer = LatchPalette.OnPrimaryContainer,
    background = LatchPalette.Background,
    onBackground = LatchPalette.OnBackground,
    surface = LatchPalette.Surface,
    onSurface = LatchPalette.OnSurface,
    surfaceVariant = LatchPalette.SurfaceVariant,
    onSurfaceVariant = LatchPalette.OnSurfaceVariant,
    outline = LatchPalette.Outline,
    outlineVariant = LatchPalette.OutlineVariant,
    error = LatchPalette.Danger,
)

private val DarkColors = darkColorScheme(
    primary = LatchPalette.DarkPrimary,
    onPrimary = LatchPalette.DarkBackground,
    primaryContainer = LatchPalette.DarkPrimaryContainer,
    onPrimaryContainer = LatchPalette.DarkOnPrimaryContainer,
    background = LatchPalette.DarkBackground,
    onBackground = LatchPalette.DarkOnBackground,
    surface = LatchPalette.DarkSurface,
    onSurface = LatchPalette.DarkOnSurface,
    surfaceVariant = LatchPalette.DarkSurfaceVariant,
    onSurfaceVariant = LatchPalette.DarkOnSurfaceVariant,
    outline = LatchPalette.DarkOutline,
    outlineVariant = LatchPalette.DarkOutlineVariant,
    error = LatchPalette.DarkDanger,
)

@Composable
fun LatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LatchTypography,
        shapes = LatchShapes,
        content = content,
    )
}
