package com.phototok.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal val DarkColorScheme = darkColorScheme(
    primary = PtPrimary,
    onPrimary = PtOnPrimary,
    primaryContainer = PtPrimaryContainer,
    onPrimaryContainer = PtOnPrimaryContainer,
    inversePrimary = PtInversePrimary,
    secondary = PtSecondary,
    onSecondary = PtOnSecondary,
    secondaryContainer = PtSecondaryContainer,
    onSecondaryContainer = PtOnSecondaryContainer,
    tertiary = PtTertiary,
    onTertiary = PtOnTertiary,
    tertiaryContainer = PtTertiaryContainer,
    onTertiaryContainer = PtOnTertiaryContainer,
    surface = PtSurface,
    onSurface = PtOnSurface,
    surfaceVariant = PtSurfaceVariant,
    onSurfaceVariant = PtOnSurfaceVariant,
    surfaceTint = PtSurfaceTint,
    surfaceContainerLowest = PtSurfaceContainerLowest,
    surfaceContainerLow = PtSurfaceContainerLow,
    surfaceContainer = PtSurfaceContainer,
    surfaceContainerHigh = PtSurfaceContainerHigh,
    surfaceContainerHighest = PtSurfaceContainerHighest,
    surfaceBright = PtSurfaceBright,
    surfaceDim = PtSurfaceDim,
    outline = PtOutline,
    outlineVariant = PtOutlineVariant,
    background = PtBackground,
    onBackground = PtOnBackground,
    error = PtError,
    onError = PtOnError,
    errorContainer = PtErrorContainer,
    onErrorContainer = PtOnErrorContainer,
    inverseSurface = PtInverseSurface,
    inverseOnSurface = PtInverseOnSurface,
    scrim = Color.Black,
)

@Composable
fun PhotoTokTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = PtSurfaceContainerLowest.toArgb()
            window.navigationBarColor = PtSurfaceContainerLowest.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content,
    )
}
