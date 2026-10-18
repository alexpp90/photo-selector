package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
     * Layout rounds every child to whole pixels, so the drawn block can exceed the
     * dp price: at 2.625× each 4 dp gap is 10.5 px drawn as 11, and the compact
     * block measured 196.57 dp against its 196 dp price (CI's phone emulator). A
     * variant is chosen only when it fits with this allowance to spare.
     */
    val PixelRoundingAllowance: Dp = 1.dp

    /** Counter line height at [fontScale]. Linear scaling over-prices large scales, which is the safe side. */
    fun counterHeight(fontScale: Float): Dp = (CounterLineHeightSp * fontScale).dp

    /** Natural height of the FULL variant: 3 button rows + toggle row + counter, 4 gaps. */
    fun fullHeight(fontScale: Float): Dp =
        FullButtonHeight * 3 +
            (TopPadding + FullToggleSize) +
            (TopPadding + counterHeight(fontScale)) +
            FullGap * 4

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

    /** The first variant whose natural height fits [available]. */
    fun mode(available: Dp, fontScale: Float, innerWidth: Dp = Dp.Infinity): Mode = when {
        fullHeight(fontScale) + PixelRoundingAllowance <= available -> Mode.FULL
        compactHeight(fontScale, innerWidth) + PixelRoundingAllowance <= available -> Mode.COMPACT
        else -> Mode.SCROLLING
    }
}
