package com.photoselectortoolbox.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.photoselectortoolbox.data.repository.SettingsRepository

private val DarkColorScheme = darkColorScheme(
    primary = BrandAccent,
    onPrimary = BrandOnAccent,
    primaryContainer = BrandAccentDeep,
    onPrimaryContainer = Zinc50,
    secondary = Zinc600,
    onSecondary = Zinc50,
    secondaryContainer = StudioCard,
    onSecondaryContainer = Zinc200,
    tertiary = BrandAccentGlow,
    onTertiary = StudioCanvas,
    tertiaryContainer = BrandAccentDeeper,
    onTertiaryContainer = Zinc100,
    surface = StudioCanvas,
    onSurface = Zinc50,
    surfaceVariant = StudioPanel,
    onSurfaceVariant = Zinc400,
    surfaceContainerLowest = StudioCanvas,
    surfaceContainerLow = StudioPanel,
    surfaceContainer = StudioCard,
    surfaceContainerHigh = Zinc700,
    surfaceContainerHighest = Zinc600,
    outline = StudioBorder,
    outlineVariant = Zinc600,
    background = StudioCanvas,
    onBackground = Zinc50,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Zinc100,
    inverseOnSurface = StudioCanvas,
    inversePrimary = BrandAccent,
    scrim = Color.Black,
)

fun computeEffectiveFontScale(baseFontScale: Float, tvDisplayScaleEnabled: Boolean): Float =
    if (tvDisplayScaleEnabled) baseFontScale * SettingsRepository.TV_DISPLAY_SCALE_FACTOR else baseFontScale

@Composable
fun PhotoSelectorToolboxTheme(
    tvDisplayScaleEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = StudioCanvas.toArgb()
            window.navigationBarColor = StudioCanvas.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    val currentDensity = LocalDensity.current
    val effectiveDensity = Density(
        density = currentDensity.density,
        fontScale = computeEffectiveFontScale(currentDensity.fontScale, tvDisplayScaleEnabled),
    )

    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
