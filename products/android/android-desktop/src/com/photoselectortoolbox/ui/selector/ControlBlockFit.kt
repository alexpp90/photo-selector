package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Which of the control block's variants a row can afford (AD-1), and how the
 * block lays itself out at the width the flank gives it (AD-16, AD-17).
 *
 * ## The locator row (`[USER 2026-11-04]`, REQ-AND-WORKFLOW.06 / .07)
 *
 * The last child of the block is a 48 dp **locator row**: the Go chip (it holds the position
 * counter and opens "Go to photo") and the Sort chip (a menu). It is how the two are found
 * without opening the overflow menu. The variants are a ladder, tried in order:
 *
 * 1. **FULL + locator** (80 dp buttons) — [fullHeight] = 362 dp.
 * 2. **COMPACT + locator** (48 dp buttons) — [compactHeight] with `withLocator` = 252 dp.
 * 3. **COMPACT** with the counter alone, no locator row — 201..265 dp, as before.
 * 4. **SCROLLING** — counter first.
 *
 * FULL *without* the row no longer exists: a window whose row is 332..361 dp tall gets
 * COMPACT + locator (the buttons shrink, the row stays), not FULL without it. The row needs
 * [LocatorMinInnerWidth] of inner width (it is one row of two cells over four 48 dp toggles),
 * so the narrow windows (720 x 560, 600 x 480) go on to rung 3 and keep the counter tap and the
 * `G` and `S` keys.
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
 * FULL + locator = 3 x 80 (button rows) + (2 + 48) (toggle row) + 48 (locator row)
 *           + 4 x 6 (gaps between the five children)          = 362 dp, at any font scale
 * COMPACT + locator = 3 x 48 + 48 (toggles) + 48 (locator row) + 4 x 3 = 252 dp
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
        /** 80 dp two-line buttons, a 48 dp toggle row and the locator row beneath it. */
        FULL,

        /**
         * 48 dp buttons and 48 dp toggles (one row of four, or 2 x 2). With the locator row
         * ([Fit.locatorRow]) beneath them, else the counter beside or above the toggles.
         */
        COMPACT,

        /** Compact content, scrolled, counter first. Only when nothing else fits. */
        SCROLLING,
    }

    val FullButtonHeight: Dp = 80.dp
    val FullGap: Dp = 6.dp
    val FullToggleSize: Dp = 48.dp

    /** The locator row: a 48 dp hit slot, the chips drawn 40 dp tall inside it. */
    val LocatorRowHeight: Dp = 48.dp
    val LocatorChipHeight: Dp = 40.dp

    /** Inner width the locator row needs: four 48 dp toggles in one row, over two cells. */
    val LocatorMinInnerWidth: Dp = 192.dp

    /** Cell widths at which a locator chip changes shape: phrase and key cap, one word, counter or word only. */
    val LocatorWideMinCell: Dp = 160.dp
    val LocatorMidMinCell: Dp = 100.dp
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

    /** Where a worded button puts its key cap, if anywhere. */
    enum class KeyPlacement { BESIDE, BELOW, NONE }

    /** What a worded button draws: its glyph or not, and where its key cap goes. */
    data class ButtonContent(val icon: Boolean, val key: KeyPlacement)

    /**
     * A worded button's content box and its parts, measured in pixels at the font scale in force:
     * the word at its smallest auto-size step (it only grows from there), the key cap, the glyph and
     * the gap between parts.
     */
    data class ButtonMeasure(
        val width: Int,
        val height: Int,
        val icon: Int,
        val gap: Int,
        val wordWidth: Int,
        val wordHeight: Int,
        val keyWidth: Int,
        val keyHeight: Int,
    )

    /**
     * What a worded button can draw in the box it has, chosen from measured text (F-FD-084: at
     * font scale 2.0 "Delete" ellipsised and its key cap overflowed, because the arrangement was
     * chosen from the cell width alone, priced at font scale 1.0).
     *
     * The ladder starts at the arrangement the cell width affords ([preferred]; null is the FULL
     * 80 dp button, glyph on top) and steps down until one fits both ways. **The word is never
     * cut.** The glyph goes first, because the word already says what the glyph shows; the key cap
     * goes only when not even the word over its key cap fits, and stays in the spoken label and in
     * the guide's controls callout (REQ-AND-LAYOUT.07).
     */
    fun buttonContent(preferred: ButtonStyle?, m: ButtonMeasure): ButtonContent {
        val beside = m.wordWidth + m.gap + m.keyWidth
        val below = maxOf(m.wordWidth, m.keyWidth)
        val lineBeside = maxOf(m.wordHeight, m.keyHeight)
        val lineBelow = m.wordHeight + m.keyHeight
        fun fits(w: Int, h: Int) = w <= m.width && h <= m.height

        val full = listOf(
            ButtonContent(icon = true, KeyPlacement.BESIDE) to
                fits(maxOf(m.icon, beside), m.icon + m.gap + lineBeside),
            ButtonContent(icon = false, KeyPlacement.BESIDE) to fits(beside, lineBeside),
            ButtonContent(icon = false, KeyPlacement.BELOW) to fits(below, lineBelow),
            ButtonContent(icon = true, KeyPlacement.NONE) to
                fits(maxOf(m.icon, m.wordWidth), m.icon + m.gap + m.wordHeight),
        )
        val compact = listOf(
            ButtonStyle.INLINE to (ButtonContent(icon = true, KeyPlacement.BESIDE) to
                fits(m.icon + m.gap + beside, maxOf(m.icon, lineBeside))),
            ButtonStyle.STACKED_WITH_ICON to (ButtonContent(icon = true, KeyPlacement.BELOW) to
                fits(m.icon + m.gap + below, maxOf(m.icon, lineBelow))),
            ButtonStyle.STACKED to (ButtonContent(icon = false, KeyPlacement.BELOW) to fits(below, lineBelow)),
            null to (ButtonContent(icon = false, KeyPlacement.BESIDE) to fits(beside, lineBeside)),
            null to (ButtonContent(icon = true, KeyPlacement.NONE) to
                fits(m.icon + m.gap + m.wordWidth, maxOf(m.icon, m.wordHeight))),
        )
        val ladder = if (preferred == null) {
            full
        } else {
            compact.drop(compact.indexOfFirst { it.first == preferred }.coerceAtLeast(0)).map { it.second }
        }
        return ladder.firstOrNull { it.second }?.first ?: ButtonContent(icon = false, KeyPlacement.NONE)
    }

    /** Counter line height at [fontScale]. Linear scaling over-prices large scales, which is the safe side. */
    fun counterHeight(fontScale: Float): Dp = (CounterLineHeightSp * fontScale).dp

    /**
     * The compact counter's height at [fontScale]. Priced at 13.1 dp per unit of scale: the 13 sp
     * line height measured 17.0 dp at scale 1.3 (15.6 dp linear), because an 11 sp
     * glyph run does not shrink below its natural height as the scale grows.
     */
    fun compactCounterHeight(fontScale: Float): Dp = (13.1f * fontScale).dp

    /**
     * Nominal height of the FULL variant: 3 button rows, the toggle row and the locator row,
     * four gaps. The locator row is a fixed 48 dp (its chips are 40 dp and a line of 12 sp is
     * 24 dp even at font scale 2), so the price no longer depends on the font scale.
     */
    val fullHeight: Dp
        get() = FullButtonHeight * 3 +
            (TopPadding + FullToggleSize) +
            LocatorRowHeight +
            FullGap * 4

    /** Drawn height of the FULL variant at [density], each child rounded as layout rounds it. */
    fun fullHeightPx(density: Float): Int =
        FullButtonHeight.px(density) * 3 +
            (TopPadding.px(density) + FullToggleSize.px(density)) +
            LocatorRowHeight.px(density) +
            FullGap.px(density) * 4

    /** Toggles per row in the compact block: all four, or a 2 x 2 grid. */
    fun togglesPerRow(innerWidth: Dp): Int = if (innerWidth >= FourToggleRowWidth) 4 else 2

    /** Whether the counter fits beside the toggle block in an [innerWidth] block. */
    fun counterInline(innerWidth: Dp): Boolean =
        innerWidth >= ToggleHitSize * togglesPerRow(innerWidth) + CounterGap + CounterWidth

    /** Height of the compact toggle block: one or two rows of abutting 48 dp slots. */
    fun compactToggleBlockHeight(innerWidth: Dp): Dp = ToggleHitSize * (4 / togglesPerRow(innerWidth))

    /**
     * Natural height of the COMPACT variant: 3 button rows + the toggle block. Without the
     * locator row the counter takes its own line on top when it cannot sit beside the
     * toggles; with it ([withLocator], which needs [LocatorMinInnerWidth]) the counter is in
     * the Go chip and the block is 3 x 48 + 48 + 48 + 4 x 3 = 252 dp.
     */
    fun compactHeight(fontScale: Float, innerWidth: Dp = Dp.Infinity, withLocator: Boolean = false): Dp {
        if (withLocator) {
            return CompactButtonHeight * 3 + ToggleHitSize + LocatorRowHeight + CompactGap * 4
        }
        val toggles = compactToggleBlockHeight(innerWidth)
        val inline = counterInline(innerWidth)
        val children = if (inline) 4 else 5
        val counterLine = if (inline) 0.dp else compactCounterHeight(fontScale)
        return CompactButtonHeight * 3 + maxOf(toggles, if (inline) compactCounterHeight(fontScale) else 0.dp) +
            counterLine + CompactGap * (children - 1)
    }

    /** Drawn height of the COMPACT variant at [density]; see [compactHeight]. */
    fun compactHeightPx(
        fontScale: Float,
        density: Float,
        innerWidth: Dp = Dp.Infinity,
        withLocator: Boolean = false,
    ): Int {
        if (withLocator) {
            return CompactButtonHeight.px(density) * 3 + ToggleHitSize.px(density) +
                LocatorRowHeight.px(density) + CompactGap.px(density) * 4
        }
        // Each toggle row is a row of 48 dp slots, so it rounds on its own.
        val toggles = ToggleHitSize.px(density) * (4 / togglesPerRow(innerWidth))
        val inline = counterInline(innerWidth)
        val children = if (inline) 4 else 5
        val counter = compactCounterHeight(fontScale).textPx(density)
        return CompactButtonHeight.px(density) * 3 +
            (if (inline) maxOf(toggles, counter) else toggles + counter) +
            CompactGap.px(density) * (children - 1)
    }

    /**
     * The variant a row affords and whether it carries the locator row. [mode] is the
     * variant alone.
     */
    data class Fit(val mode: Mode, val locatorRow: Boolean)

    /** The ladder in the class doc: the first rung whose drawn height at [density] fits [available]. */
    fun fit(available: Dp, fontScale: Float, density: Float, innerWidth: Dp = Dp.Infinity): Fit {
        val locatorWidth = innerWidth >= LocatorMinInnerWidth
        if (available == Dp.Infinity) {
            return when {
                innerWidth >= FullMinInnerWidth -> Fit(Mode.FULL, locatorRow = true)
                locatorWidth -> Fit(Mode.COMPACT, locatorRow = true)
                else -> Fit(Mode.COMPACT, locatorRow = false)
            }
        }
        val availablePx = (available.value * density).roundToInt()
        return when {
            innerWidth >= FullMinInnerWidth &&
                fullHeightPx(density) + PixelSlack <= availablePx -> Fit(Mode.FULL, locatorRow = true)
            locatorWidth &&
                compactHeightPx(fontScale, density, innerWidth, withLocator = true) + PixelSlack <= availablePx ->
                Fit(Mode.COMPACT, locatorRow = true)
            compactHeightPx(fontScale, density, innerWidth) + PixelSlack <= availablePx ->
                Fit(Mode.COMPACT, locatorRow = false)
            else -> Fit(Mode.SCROLLING, locatorRow = false)
        }
    }

    /** The variant alone; see [fit] for whether it carries the locator row. */
    fun mode(available: Dp, fontScale: Float, density: Float, innerWidth: Dp = Dp.Infinity): Mode =
        fit(available, fontScale, density, innerWidth).mode

    /** How the two locator chips draw their content at an [innerWidth] block (two equal cells). */
    enum class LocatorStyle {
        /** icon, phrase and key cap: `⌕ 127 / 842 [G]`, `⇅ Newest first ▾ [S]`. */
        WIDE,

        /** icon and one word: `⌕ 127 / 842`, `⇅ Newest ▾`. */
        MID,

        /** the counter alone, the sort word and its caret alone. */
        NARROW,
    }

    fun locatorStyle(innerWidth: Dp): LocatorStyle {
        val cell = innerWidth / 2
        return when {
            cell >= LocatorWideMinCell -> LocatorStyle.WIDE
            cell >= LocatorMidMinCell -> LocatorStyle.MID
            else -> LocatorStyle.NARROW
        }
    }

    /** A fixed size as layout draws it: `Dp.roundToPx`, i.e. rounded to the nearest pixel. */
    private fun Dp.px(density: Float): Int = (value * density).roundToInt()

    /** A text line as layout draws it: rounded up to the next whole pixel. */
    private fun Dp.textPx(density: Float): Int = ceil(value * density - TextEpsilon).toInt()

    /** Float noise below which a line height is already a whole pixel (20.8 x 1.25 = 26.000002). */
    private const val TextEpsilon = 0.001f
}
