package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Which of the control block's three heights a row can afford (AD-1).
 *
 * The block's height is the one thing on the selector that does not follow the
 * frames: row 1 is exactly one frame tall, which is ~376 dp at 1280 × 800, ~336 dp
 * at 1024 × 720 and ~210 dp at 600 × 480, while the block as first drawn needs
 * 332 dp. Everything past the row's bottom edge was clipped — first the position
 * counter, then the toggles — so the screen quietly lost the controls that tell
 * the user where they are.
 *
 * This is a pure function of the height handed over and the font scale, kept
 * out of the composable so a JVM test can pin the thresholds. Every dimension
 * is the constant the composable itself draws with, so the price and the pixels
 * cannot drift apart.
 *
 * ## Derivation of the prices (kept honest by `SelectorForcedSizeTest`)
 *
 * ```
 * FULL    = 3 x 80 (button rows) + (2 + 48) (toggle row) + (2 + 16 x fontScale) (counter)
 *           + 4 x 6 (gaps between the five children)          = 332 dp at scale 1.0
 * COMPACT = 3 x 48 (button rows) + 40 (toggle/counter row)
 *           + 3 x 4 (gaps)                                    = 196 dp
 * ```
 *
 * The price is analytic because a measure-then-choose `SubcomposeLayout` would leave
 * the losing variant composed and unplaced, duplicating every control tag in the
 * semantics tree. The analytic price is therefore asserted against the *measured*
 * natural height of each variant at font scales 1.0 and 1.3: it must be at least
 * the measured height and within 8 dp of it, so a change to the block that is not
 * priced here fails a test instead of clipping on a device.
 *
 * ## The choice is made in pixels
 *
 * The dp sums above are what the block costs on a 2× screen only. Layout rounds
 * every child to whole pixels on its own — each 6 dp gap is 16.5 px at 2.75× and
 * is drawn as 17, each 2 dp padding 5.5 px drawn as 6 — and the text line is
 * rounded up. The full block therefore measured exactly 3 px over its dp price on
 * the Pixel 4a (2.75×), the Galaxy Tab S11 Ultra (1.75×) and a 1.25× Chromebook
 * (device matrix, 2026-10-19), which is 2.4 dp at 1.25×: more than any fixed dp
 * allowance small enough not to waste the row. [fullHeightPx] and
 * [compactHeightPx] round each child the way layout does, so the price is the
 * drawn height at every density rather than an estimate of it.
 */
object ControlBlockFit {

    enum class Mode {
        /** 80 dp two-line buttons, a 48 dp toggle row and the counter beneath it. */
        FULL,

        /** 48 dp one-line buttons, 36 dp toggles with the counter in the same row. */
        COMPACT,

        /** Compact content, scrolled, counter first. Only when nothing else fits. */
        SCROLLING,
    }

    val FullButtonHeight: Dp = 80.dp
    val FullGap: Dp = 6.dp
    val FullToggleSize: Dp = 48.dp
    val CompactButtonHeight: Dp = 48.dp
    val CompactGap: Dp = 4.dp

    /** Row that holds the four compact toggles and the counter. */
    val CompactToggleRowHeight: Dp = 40.dp

    /** Visual size of a compact toggle; 32 dp when the block is under 240 dp wide. */
    val CompactToggle: Dp = 36.dp
    val CompactToggleNarrow: Dp = 32.dp

    /**
     * The counter's line height. Explicit, so the block's height is a function
     * of font scale alone rather than of whichever font the device resolved.
     */
    const val CounterLineHeightSp: Float = 16f

    private val TopPadding: Dp = 2.dp

    /**
     * Slack a variant must leave, in pixels, on top of its pixel price. The price
     * already rounds every child as layout does; this pixel is for the text line,
     * whose drawn height is the font's, so a variant is never chosen at exactly
     * zero slack.
     */
    const val PixelSlack: Int = 1

    /** Counter line height at [fontScale]. Linear scaling over-prices large scales, which is the safe side. */
    fun counterHeight(fontScale: Float): Dp = (CounterLineHeightSp * fontScale).dp

    /** Nominal height of the FULL variant: 3 button rows + toggle row + counter, 4 gaps. */
    fun fullHeight(fontScale: Float): Dp =
        FullButtonHeight * 3 +
            (TopPadding + FullToggleSize) +
            (TopPadding + counterHeight(fontScale)) +
            FullGap * 4

    /** Drawn height of the FULL variant at [density], each child rounded as layout rounds it. */
    fun fullHeightPx(fontScale: Float, density: Float): Int =
        FullButtonHeight.px(density) * 3 +
            (TopPadding.px(density) + FullToggleSize.px(density)) +
            (TopPadding.px(density) + counterHeight(fontScale).textPx(density)) +
            FullGap.px(density) * 4

    /**
     * Width the compact toggle row needs to hold four 32 dp toggles and the
     * ~64 dp `127 / 842+` counter. Below it (a 600 dp window gives the flank
     * only 120 dp) the counter takes its own line above the buttons instead of
     * squeezing the toggles to nothing.
     */
    val CompactInlineCounterWidth: Dp = CompactToggleNarrow * 4 + 64.dp

    /** Whether the counter fits beside the four toggles in an [innerWidth] block. */
    fun counterInline(innerWidth: Dp): Boolean = innerWidth >= CompactInlineCounterWidth

    /**
     * Natural height of the COMPACT variant: 3 button rows + toggle row, 3 gaps —
     * plus a counter line of its own when it cannot sit in the toggle row.
     */
    fun compactHeight(fontScale: Float, innerWidth: Dp = Dp.Infinity): Dp {
        val base = CompactButtonHeight * 3 +
            CompactGap * 3 +
            maxOf(CompactToggleRowHeight, counterHeight(fontScale))
        return if (counterInline(innerWidth)) base else base + CompactGap + counterHeight(fontScale)
    }

    /** Drawn height of the COMPACT variant at [density]; see [compactHeight]. */
    fun compactHeightPx(fontScale: Float, density: Float, innerWidth: Dp = Dp.Infinity): Int {
        val counter = counterHeight(fontScale).textPx(density)
        val base = CompactButtonHeight.px(density) * 3 +
            CompactGap.px(density) * 3 +
            maxOf(CompactToggleRowHeight.px(density), counter)
        return if (counterInline(innerWidth)) base else base + CompactGap.px(density) + counter
    }

    /** The first variant whose drawn height at [density] fits [available]. */
    fun mode(available: Dp, fontScale: Float, density: Float, innerWidth: Dp = Dp.Infinity): Mode {
        if (available == Dp.Infinity) return Mode.FULL
        val availablePx = (available.value * density).roundToInt()
        return when {
            fullHeightPx(fontScale, density) + PixelSlack <= availablePx -> Mode.FULL
            compactHeightPx(fontScale, density, innerWidth) + PixelSlack <= availablePx -> Mode.COMPACT
            else -> Mode.SCROLLING
        }
    }

    /** A fixed size as layout draws it: `Dp.roundToPx`, i.e. rounded to the nearest pixel. */
    private fun Dp.px(density: Float): Int = (value * density).roundToInt()

    /** A text line as layout draws it: rounded up to the next whole pixel. */
    private fun Dp.textPx(density: Float): Int = ceil(value * density - TextEpsilon).toInt()

    /** Float noise below which a line height is already a whole pixel (20.8 × 1.25 = 26.000002). */
    private const val TextEpsilon = 0.001f
}
