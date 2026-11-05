package com.photoselectortoolbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The arithmetic of the instrumented `WindowGeometryRule`, on the JVM (device matrix, 2026-11-05:
 * a Pixel 4a's punch hole left the window 1479.5 dp wide and every 1480 x 844 dp host refused to
 * start, on all three attempts).
 */
class ReferenceWindowMathTest {

    /**
     * A Pixel 4a (1080 x 2340 px, top cutout 136 px): the window manager scales the cutout with the
     * forced long edge. The figures are the ones the device reported: 172 px at 2960, 182 at 3132,
     * 183 at 3142 (`mAppBounds` left edge, logcat 2026-11-05 20:31:49).
     */
    private fun pixel4aCutoutPx(forcedLongPx: Int) = (136.0 * forcedLongPx / 2340).roundToInt()

    /** The loop of `WindowGeometryRule.applyReference` with a device model in place of `wm`. */
    private fun settle(
        physicalShort: Int,
        physicalLong: Int,
        cutoutPx: (ReferenceWindowMath.Forced) -> Int,
        maxRounds: Int = 8,
    ): Triple<ReferenceWindowMath.Forced, Int, Int> {
        var referenceLong = ReferenceWindowMath.LONG_EDGE_PX
        var rounds = 0
        while (true) {
            rounds++
            val forced = ReferenceWindowMath.forced(physicalShort, physicalLong, referenceLong)
            val cutout = cutoutPx(forced)
            val wider = ReferenceWindowMath.widerLongEdge(referenceLong, forced, cutout) ?: return Triple(forced, cutout, rounds)
            if (rounds == maxRounds) return Triple(forced, cutout, rounds)
            referenceLong = wider
        }
    }

    private fun usableDp(forced: ReferenceWindowMath.Forced, cutout: Int): Double =
        (forced.longPx - cutout) / (forced.density / 160.0)

    @Test
    fun pixel4a_isGivenAWindowOfAtLeast1480Dp_notOnePixelLess() {
        val (forced, cutout, rounds) = settle(1080, 2340, { pixel4aCutoutPx(it.longPx) })

        assertEquals(320, forced.density)
        assertEquals(1848, forced.shortPx)
        // 2959 px (what the rule used to accept) is 1479.5 dp; the host needs the whole 1480.
        assertTrue("window ${usableDp(forced, cutout)} dp", usableDp(forced, cutout) >= 1480.0)
        assertEquals(3143, forced.longPx)
        assertEquals(183, cutout)
        assertTrue("settles within the rule's rounds, took $rounds", rounds <= 5)
    }

    @Test
    fun onePixelShort_isShort() {
        // The case the Pixel 4a hit: 3142 px minus a 183 px cutout is 2959 px.
        val forced = ReferenceWindowMath.forced(1080, 2340, 3142)
        assertEquals(3142, forced.longPx)
        assertFalse(ReferenceWindowMath.reachesReferenceWidth(forced, 183))
        assertEquals(3143, ReferenceWindowMath.widerLongEdge(3142, forced, 183))
        assertTrue(ReferenceWindowMath.reachesReferenceWidth(ReferenceWindowMath.forced(1080, 2340, 3143), 183))
    }

    @Test
    fun aPanelWithoutACutout_needsNoCorrection() {
        // The Pixel Tablet (1600 x 2560) and every phone without a cutout inset: first round.
        val (forced, _, rounds) = settle(1600, 2560, { 0 })
        assertEquals(1, rounds)
        assertEquals(ReferenceWindowMath.Forced(320, 1848, 2960), forced)
    }

    @Test
    fun ciEmulator320x640_isScaledNotClamped_andStillHoldsTheReferenceDp() {
        // CI's AVD (no device profile): 320 x 640 px at 160 dpi. The window manager clamps a forced
        // size to twice the panel, so size and density shrink together (commit 02210ccf).
        val (forced, cutout, _) = settle(320, 640, { 0 })

        assertEquals(0, cutout)
        assertTrue("short ${forced.shortPx} within the 2x clamp", forced.shortPx <= 2 * 320)
        assertTrue("long ${forced.longPx} within the 2x clamp", forced.longPx <= 2 * 640)
        assertTrue("window ${usableDp(forced, 0)} dp", usableDp(forced, 0) >= 1480.0)
        assertTrue(forced.density < ReferenceWindowMath.DENSITY)
    }

    @Test
    fun aCutoutOnAScaledPanel_isCompensatedInRealPixels() {
        // A small panel with a cutout: the correction is measured in px at the forced density and
        // converted to reference pixels, never added as raw px.
        val (forced, cutout, rounds) = settle(720, 1560, { (it.longPx * 0.05).roundToInt() })
        assertTrue("window ${usableDp(forced, cutout)} dp after $rounds rounds", usableDp(forced, cutout) >= 1480.0)
        assertTrue(forced.shortPx <= 2 * 720)
        assertTrue(forced.longPx <= 2 * 1560)
    }

    @Test
    fun everyAnswerIsLongerThanTheLast_soTheLoopNeverStandsStill() {
        var referenceLong = ReferenceWindowMath.LONG_EDGE_PX
        // A cutout that grows by 10 px with every round, faster than the loop converges.
        repeat(6) { round ->
            val forced = ReferenceWindowMath.forced(1600, 2560, referenceLong)
            val next = ReferenceWindowMath.widerLongEdge(referenceLong, forced, 200 + 10 * round)
            assertNotNull(next)
            assertTrue("$next > $referenceLong", next!! > referenceLong)
            referenceLong = next
        }
    }

    @Test
    fun referenceScale_isOneWhereThePanelIsBigEnough() {
        assertEquals(1.0, ReferenceWindowMath.referenceScale(1600, 2560, 2960), 0.0)
        assertEquals(1.0, ReferenceWindowMath.referenceScale(1080, 2340, 3143), 0.0)
        assertTrue(ReferenceWindowMath.referenceScale(320, 640, 2960) < 1.0)
        assertNull(ReferenceWindowMath.widerLongEdge(2960, ReferenceWindowMath.forced(1600, 2560, 2960), 0))
    }
}
