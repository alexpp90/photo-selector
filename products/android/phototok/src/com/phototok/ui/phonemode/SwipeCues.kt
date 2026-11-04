package com.phototok.ui.phonemode

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colours of the mid-swipe indicators, the edge hints and the copied flash, all from the Jackpix
 * scheme (review 2026-11-03, JP-UX-5): white on `SuccessGreen` was about 2.3:1 and the green sat
 * outside the lime/neutral palette. The labels and the edge hints sit on [cueScrim], so they stay
 * readable over a white photo as well as a black one. Pure, so `SwipeCueColorsTest` checks the pairs.
 */
internal data class SwipeCueColors(
    /** Swipe right: the disc behind the glyph, and the glyph. */
    val rightContainer: Color,
    val rightContent: Color,
    /** Swipe right: the label text and the edge-hint glyph. */
    val rightAccent: Color,
    /** Swipe left: the disc behind the glyph, and the glyph. */
    val leftContainer: Color,
    val leftContent: Color,
    /** Swipe left: the label text and the edge-hint glyph (error red for a delete). */
    val leftAccent: Color,
    /** The pill behind the labels and the edge hints. */
    val cueScrim: Color,
)

internal const val CUE_SCRIM_ALPHA = 0.8f

internal fun swipeCueColors(scheme: ColorScheme, leftIsDelete: Boolean): SwipeCueColors = SwipeCueColors(
    rightContainer = scheme.primary,
    rightContent = scheme.onPrimary,
    rightAccent = scheme.primary,
    leftContainer = if (leftIsDelete) scheme.errorContainer else scheme.primaryContainer,
    leftContent = if (leftIsDelete) scheme.onErrorContainer else scheme.onPrimaryContainer,
    leftAccent = if (leftIsDelete) scheme.error else scheme.primary,
    cueScrim = scheme.surfaceContainerLowest.copy(alpha = CUE_SCRIM_ALPHA),
)

/**
 * False when the user turned animations off (Developer options or Accessibility → Remove
 * animations sets the animator duration scale to 0): the edge hints then stand still instead of
 * bobbing forever (review JP-UX-3).
 */
internal fun cueMotionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
