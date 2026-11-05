package com.photoselectortoolbox.ui

import kotlin.math.floor
import kotlin.math.min

/**
 * The arithmetic behind `WindowGeometryRule`: which size and density to force so that the
 * activity's usable window is the reference one (a Galaxy Tab S11 Ultra in landscape,
 * 2960 x 1848 px at 320 dpi = 1480 x 924 dp).
 *
 * Pure Kotlin, in `tests/common/` so that it is compiled into both the JVM unit tests (which prove
 * it, `ReferenceWindowMathTest`) and the instrumented tests (which use it). It touches no device.
 *
 * Every conversion rounds in the direction that never leaves the window narrower than the
 * reference. The first version of the cutout compensation accepted a window one pixel short
 * (`>= 2959`), which at 320 dpi is 1479.5 dp: the configuration reports that as `w1480dp`
 * (rounded), but `displayMetrics.widthPixels / density` is 1479.5 and the hosts that need
 * 1480 dp refused to start (Pixel 4a, 2026-11-05).
 */
internal object ReferenceWindowMath {
    const val LONG_EDGE_PX = 2960
    const val SHORT_EDGE_PX = 1848
    const val DENSITY = 320

    /** WindowManagerService clamps a forced display size to this multiple of the panel's size. */
    const val MAX_FORCED_SCALE = 2

    /** What to give `wm density` and `wm size`: the size in the panel's own (portrait or landscape) edges. */
    data class Forced(val density: Int, val shortPx: Int, val longPx: Int)

    /**
     * The factor, at most 1, by which the reference size (1848 x [longEdge] px at 320 dpi) and
     * density shrink so that the forced size stays within [MAX_FORCED_SCALE] times the panel.
     * The window in dp is unchanged.
     */
    fun referenceScale(physicalShort: Int, physicalLong: Int, longEdge: Int): Double =
        min(
            1.0,
            min(
                MAX_FORCED_SCALE * physicalShort.toDouble() / SHORT_EDGE_PX,
                MAX_FORCED_SCALE * physicalLong.toDouble() / longEdge,
            ),
        )

    /**
     * The display to force for a window whose long edge is [referenceLong] reference pixels.
     *
     * Density rounds down and the pixels up, so the window in dp is never narrower than the
     * reference (rounding both to nearest gave 1477 dp, three short of the 1480 dp hosts); the
     * pixels stay within the clamp because density <= 320 * scale.
     */
    fun forced(physicalShort: Int, physicalLong: Int, referenceLong: Int): Forced {
        val scale = referenceScale(physicalShort, physicalLong, referenceLong)
        val density = floor(DENSITY * scale).toInt()
        return Forced(
            density = density,
            shortPx = ceilDiv(SHORT_EDGE_PX * density, DENSITY),
            longPx = ceilDiv(referenceLong * density, DENSITY),
        )
    }

    /** The usable width, in px at [density], that the reference window needs: exactly 1480 dp. */
    fun requiredWidthPx(density: Int): Int = ceilDiv(LONG_EDGE_PX * density, DENSITY)

    /**
     * Whether a display forced to [forced] with [cutoutPx] taken from its width on the left and
     * right still gives the activity the reference width. The test is exact: one pixel short is
     * short (see the class comment).
     */
    fun reachesReferenceWidth(forced: Forced, cutoutPx: Int): Boolean =
        forced.longPx - cutoutPx >= requiredWidthPx(forced.density)

    /**
     * The next long edge, in reference pixels, to force when [forced] (built from
     * [referenceLong]) loses [cutoutPx] to a display cutout, or null when it already reaches the
     * reference width.
     *
     * A cutout scales with the forced size (a Pixel 4a's punch hole: 172 px at 2960, 182 at 3132,
     * 183 at 3142), so one correction is not enough; the caller measures again and asks again.
     * Each answer is the reference edge plus the cutout measured, rounded up, and is strictly
     * longer than [referenceLong], so a sequence of calls never stands still: when the cutout stops
     * changing it reaches the reference width.
     */
    fun widerLongEdge(referenceLong: Int, forced: Forced, cutoutPx: Int): Int? {
        if (reachesReferenceWidth(forced, cutoutPx)) return null
        val cutoutAtReferenceDensity = ceilDiv(cutoutPx * DENSITY, forced.density)
        return maxOf(LONG_EDGE_PX + cutoutAtReferenceDensity, referenceLong + 1)
    }

    private fun ceilDiv(numerator: Int, denominator: Int): Int = (numerator + denominator - 1) / denominator
}
