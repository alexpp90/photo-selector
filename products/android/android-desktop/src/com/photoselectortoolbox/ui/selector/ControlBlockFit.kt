package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Which of the control block's three heights a row can afford (AD-1), and how the
 * block lays itself out at the width the flank gives it (AD-16, AD-17).
 *
 * The block's height is the one thing on the selector that does not follow the
 * frames: row 1 is exactly one frame tall, which is ~376 dp at 1280 x 800, ~336 dp
 * at 1024 x 720 and ~228 dp at 600 x 480, while the block as first drawn needs
 * 332 dp. Everything past the row's bottom edge was clipped — first the position
 * counter, then the toggles — so the screen quietly lost the controls that tell
 * the user where they are.
 *
 * This is a pure function of the height and width handed over and the font scale,
 * kept out of the composable so a JVM test can pin the thresholds. Every dimension
 * is the constant the composable itself draws with, so the price and the pixels
 * cannot drift apart.
 *
 * ## Width decides the arrangement, height decides the variant
 *
 * Every view toggle is a 48 x 48 dp hit target, at every size (AD-17). Four of them
 * need 192 dp; the flank is only 150 dp at 720 x 560 and 120 dp at 600 x 480
 * (`FrameGeometry`'s quarter-of-the-region cap beats the 212 dp
 * `MinimumControlBlockWidth` there, by design: it protects the frames). So:
 *
 * - [togglesPerRow]: four in a row from 192 dp of inner width, otherwise a 2 x 2 grid.
 * - [counterInline]: the counter shares the toggle block's row/grid only when it
 *   fits beside it; otherwise it leads the block on its own line.
 * - [buttonStyle]: the worded buttons are one line (icon, word, key cap) from a
 *   100 dp cell, word-over-key-cap with the icon below that, and word-over-key-cap
 *   without the icon below 64 dp. The word and key cap are never ellipsized.
 * - FULL needs [FullMinInnerWidth] so its 80 dp buttons and one row of toggles fit.
 *
 * ## Derivation of the prices (kept honest by `SelectorForcedSizeTest`)
 *
 * ```
 * FULL    = 3 x 80 (button rows) + (2 + 48) (toggle row) + (2 + 16 x fontScale) (counter)
 *           + 4 x 6 (gaps between the five children)          = 332 dp at scale 1.0
 * COMPACT = 3 x 48 (button rows) + toggle block (48 per row of toggles, abutting)
 *           + counter line (13.1 x fontScale) when it leads the block
 *           + 3 gaps of 3 dp (4 gaps when the counter leads)
 *           wide, counter inline       = 144 + 48 + 9  = 201 dp
 *           mid,  counter on top       = 144 + 48 + 13.1 + 12 = 217.1 dp
 *           narrow, 2 x 2 + counter on top = 144 + 96 + 13.1 + 12 = 265.1 dp
 *           narrow, 2 x 2 + counter inline = 144 + 96 + 9        = 249 dp
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
 * The dp sums above are what the block costs on a 2x screen only. Layout rounds
 * every child to whole pixels on its own — each 6 dp gap is 16.5 px at 2.75x and
 * is drawn as 17, each 2 dp padding 5.5 px drawn as 6 — and a text line is
 * rounded up. The full block therefore measured exactly 3 px over its dp price on
 * the Pixel 4a (2.75x), the Galaxy Tab S11 Ultra (1.75x) and a 1.25x Chromebook
 * (device matrix, 2026-10-19), which is 2.4 dp at 1.25x: more than any fixed dp
 * allowance small enough not to waste the row. [fullHeightPx] and
 * [compactHeightPx] round each child the way layout does, so the price is the
 * drawn height at every density rather than an estimate of it.
 */
object ControlBlockFit {

    enum class Mode {
        /** 80 dp two-line buttons, a 48 dp toggle row and the counter beneath it. */
        FULL,

        /** 48 dp buttons, 48 dp toggles (one row of four, or 2 x 2), the counter beside or above them. */
        COMPACT,

        /** Compact content, scrolled, counter first. Only when nothing else fits. */
        SCROLLING,
    }

    val FullButtonHeight: Dp = 80.dp
    val FullGap: Dp = 6.dp
    val FullToggleSize: Dp = 48.dp
    val CompactButtonHeight: Dp = 48.dp
    val CompactGap: Dp = 3.dp

    /** The hit target of every view toggle at every size (AD-17). */
    val ToggleHitSize: Dp = 48.dp

    /** Visual size of the hover chip drawn inside a toggle's 48 dp hit target. */
    val ToggleVisualSize: Dp = 36.dp

    /**
     * The counter's line height in the FULL block. Explicit, so the block's height
     * is a function of font scale alone rather than of whichever font the device
     * resolved.
     */
    const val CounterLineHeightSp: Float = 16f

    /** The counter's line height in the COMPACT block, which has no height to spare. */
    const val CompactCounterLineHeightSp: Float = 13f

    private val TopPadding: Dp = 2.dp

    /**
     * Slack a variant must leave, in pixels, on top of its pixel price. The price
     * already rounds every child as layout does; this pixel is for the text line,
     * whose drawn height is the font's, so a variant is never chosen at exactly
     * zero slack.
     */
    const val PixelSlack: Int = 1

    /** The ~64 dp `127 / 842+` counter, and the gap between it and the toggles. */
    val CounterWidth: Dp = 64.dp
    val CounterGap: Dp = 4.dp

    /** Inner width at which four 48 dp toggles fit one row. */
    val FourToggleRowWidth: Dp = ToggleHitSize * 4

    /** Inner width the FULL variant needs: four toggles with 6 dp gaps, one row. */
    val FullMinInnerWidth: Dp = ToggleHitSize * 4 + FullGap * 3

    /** Cell widths at which the compact buttons change shape. */
    val InlineButtonMinCell: Dp = 100.dp
    val IconStackedButtonMinCell: Dp = 64.dp

    enum class ButtonStyle {
        /** icon, word and key cap on one line. */
        INLINE,

        /** icon at the start, word over key cap beside it. */
        STACKED_WITH_ICON,

        /** word over key cap, no icon: the cell is too narrow for one. */
        STACKED,
    }

    /** Width of one button cell when two share an [innerWidth] row. */
    fun cellWidth(innerWidth: Dp, gap: Dp = CompactGap): Dp = (innerWidth - gap) / 2

    fun buttonStyle(innerWidth: Dp): ButtonStyle {
        val cell = cellWidth(innerWidth)
        return when {
            cell >= InlineButtonMinCell -> ButtonStyle.INLINE
            cell >= IconStackedButtonMinCell -> ButtonStyle.STACKED_WITH_ICON
            else -> ButtonStyle.STACKED
        }
    }

    /** Counter line height at [fontScale]. Linear scaling over-prices large scales, which is the safe side. */
    fun counterHeight(fontScale: Float): Dp = (CounterLineHeightSp * fontScale).dp

    /**
     * The compact counter's height at [fontScale]. Priced at 13.1 dp per unit of scale: the 13 sp
     * line height measured 17.0 dp at scale 1.3 (15.6 dp linear), because an 11 sp
     * glyph run does not shrink below its natural height as the scale grows.
     */
    fun compactCounterHeight(fontScale: Float): Dp = (13.1f * fontScale).dp

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

    /** Toggles per row in the compact block: all four, or a 2 x 2 grid. */
    fun togglesPerRow(innerWidth: Dp): Int = if (innerWidth >= FourToggleRowWidth) 4 else 2

    /** Whether the counter fits beside the toggle block in an [innerWidth] block. */
    fun counterInline(innerWidth: Dp): Boolean =
        innerWidth >= ToggleHitSize * togglesPerRow(innerWidth) + CounterGap + CounterWidth

    /** Height of the compact toggle block: one or two rows of abutting 48 dp slots. */
    fun compactToggleBlockHeight(innerWidth: Dp): Dp = ToggleHitSize * (4 / togglesPerRow(innerWidth))

    /**
     * Natural height of the COMPACT variant: 3 button rows + the toggle block, with
     * the counter's own line on top when it cannot sit beside the toggles.
     */
    fun compactHeight(fontScale: Float, innerWidth: Dp = Dp.Infinity): Dp {
        val toggles = compactToggleBlockHeight(innerWidth)
        val inline = counterInline(innerWidth)
        val children = if (inline) 4 else 5
        val counterLine = if (inline) 0.dp else compactCounterHeight(fontScale)
        return CompactButtonHeight * 3 + maxOf(toggles, if (inline) compactCounterHeight(fontScale) else 0.dp) +
            counterLine + CompactGap * (children - 1)
    }

    /** Drawn height of the COMPACT variant at [density]; see [compactHeight]. */
    fun compactHeightPx(fontScale: Float, density: Float, innerWidth: Dp = Dp.Infinity): Int {
        // Each toggle row is a row of 48 dp slots, so it rounds on its own.
        val toggles = ToggleHitSize.px(density) * (4 / togglesPerRow(innerWidth))
        val inline = counterInline(innerWidth)
        val children = if (inline) 4 else 5
        val counter = compactCounterHeight(fontScale).textPx(density)
        return CompactButtonHeight.px(density) * 3 +
            (if (inline) maxOf(toggles, counter) else toggles + counter) +
            CompactGap.px(density) * (children - 1)
    }

    /** The first variant whose drawn height at [density] fits [available]. */
    fun mode(available: Dp, fontScale: Float, density: Float, innerWidth: Dp = Dp.Infinity): Mode {
        if (available == Dp.Infinity) {
            return if (innerWidth >= FullMinInnerWidth) Mode.FULL else Mode.COMPACT
        }
        val availablePx = (available.value * density).roundToInt()
        return when {
            innerWidth >= FullMinInnerWidth &&
                fullHeightPx(fontScale, density) + PixelSlack <= availablePx -> Mode.FULL
            compactHeightPx(fontScale, density, innerWidth) + PixelSlack <= availablePx -> Mode.COMPACT
            else -> Mode.SCROLLING
        }
    }

    /** A fixed size as layout draws it: `Dp.roundToPx`, i.e. rounded to the nearest pixel. */
    private fun Dp.px(density: Float): Int = (value * density).roundToInt()

    /** A text line as layout draws it: rounded up to the next whole pixel. */
    private fun Dp.textPx(density: Float): Int = ceil(value * density - TextEpsilon).toInt()

    /** Float noise below which a line height is already a whole pixel (20.8 x 1.25 = 26.000002). */
    private const val TextEpsilon = 0.001f
}
