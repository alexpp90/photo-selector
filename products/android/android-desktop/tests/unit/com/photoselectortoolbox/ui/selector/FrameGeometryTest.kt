package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.dp
import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the whole screen rests on.
 *
 * Two revisions of this selector shipped with frames far smaller than the
 * display allowed, and neither was caught, because "the images look a bit
 * small" is not something a test suite notices. These assertions turn the
 * layout argument into numbers that fail a build.
 */
class FrameGeometryTest {

    // Galaxy Tab S11 Ultra, landscape, minus the sidebar and outer padding.
    private val referenceWidth = 1376.dp
    private val referenceHeight = 908.dp

    @Test
    @Requirement("REQ-AND-LAYOUT.01", "REQ-AND-LAYOUT.03", "REQ-AND-LAYOUT.04")
    fun `one over two beats a row by a wide margin on the reference device`() {
        val oneOverTwo = FrameGeometry.frameSize(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            columns = 2,
            rows = 2,
        )
        val inARow = FrameGeometry.frameSize(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            columns = 3,
            rows = 1,
        )

        val stackedArea = oneOverTwo.width.value * oneOverTwo.height.value
        val rowArea = inARow.width.value * inARow.height.value

        assertTrue(
            "one-over-two gives ${stackedArea.toInt()} dp² but a row gives ${rowArea.toInt()} dp²",
            stackedArea > rowArea * 1.4f,
        )
    }

    @Test
    fun `the reference device is height-bound, which is why chrome goes sideways`() {
        val size = FrameGeometry.frameSize(referenceWidth, referenceHeight)

        // Two rows must fit the height exactly; the width has slack left over.
        val heightUsed = size.height.value * 2 + FrameGeometry.Gap.value
        assertEquals(referenceHeight.value, heightUsed, 1f)

        val widthUsed = size.width.value * 2 + FrameGeometry.Gap.value
        assertTrue(
            "expected spare width, used ${widthUsed}dp of ${referenceWidth.value}dp",
            widthUsed < referenceWidth.value,
        )
    }

    @Test
    fun `frames clear the minimum height on the reference device`() {
        val size = FrameGeometry.frameSize(referenceWidth, referenceHeight)

        assertTrue(
            "frame is only ${size.height.value}dp tall",
            size.height >= FrameGeometry.MinimumReferenceFrameHeight,
        )
    }

    @Test
    fun `frames stay 4 to 3 whichever constraint binds`() {
        listOf(
            referenceWidth to referenceHeight,
            600.dp to 900.dp,   // narrow: width binds
            2000.dp to 700.dp,  // wide: height binds
        ).forEach { (w, h) ->
            val size = FrameGeometry.frameSize(w, h)
            val ratio = size.width.value / size.height.value

            assertEquals("aspect drifted at ${w.value}x${h.value}", 4f / 3f, ratio, 0.01f)
        }
    }

    @Test
    fun `a narrow window is width-bound and shrinks rather than overflowing`() {
        // Tablet portrait. The solver must pick the width constraint here; if
        // it assumed height binds it would return frames wider than the window.
        val size = FrameGeometry.frameSize(regionWidth = 700.dp, regionHeight = 1100.dp)

        assertTrue(size.width.value * 2 + FrameGeometry.Gap.value <= 700f)
    }

    @Test
    fun `maximising one frame is worth several times the area`() {
        val threeUp = FrameGeometry.frameSize(referenceWidth, referenceHeight)
        val maximised = FrameGeometry.maximisedFrameSize(referenceWidth, referenceHeight)

        val gain = (maximised.width.value * maximised.height.value) /
            (threeUp.width.value * threeUp.height.value)

        assertTrue("maximising only gains ${gain}x", gain > 3.5f)
    }

    @Test
    fun `portrait frames use the reciprocal aspect without breaking the fit`() {
        val size = FrameGeometry.frameSize(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = FrameGeometry.PortraitAspect,
        )

        assertEquals(3f / 4f, size.width.value / size.height.value, 0.01f)
        assertTrue(size.height.value * 2 + FrameGeometry.Gap.value <= referenceHeight.value + 1f)
    }

    @Test
    fun `3 to 2 photos use 3 to 2 aspect ratio without breaking the fit`() {
        val size = FrameGeometry.frameSize(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = 1.5f,
        )

        assertEquals(1.5f, size.width.value / size.height.value, 0.01f)
        assertTrue(size.height.value * 2 + FrameGeometry.Gap.value <= referenceHeight.value + 1f)
        // Two rows of 450dp use the full 908dp height (with gap of 8dp)
        val heightUsed = size.height.value * 2 + FrameGeometry.Gap.value
        assertEquals(referenceHeight.value, heightUsed, 1f)
    }

    @Test
    fun `neighbour values overlay the frame on the reference device`() {
        val size = FrameGeometry.frameSize(referenceWidth, referenceHeight)

        assertFalse(FrameGeometry.overlayFitsOutside(referenceWidth, size.width))
    }

    @Test
    fun `neighbour values move outside the frame when the window is wide enough`() {
        // A wide DeX window. Same rule, different answer — which is the point
        // of expressing placement as a measurement rather than a constant.
        val wide = 2400.dp
        val size = FrameGeometry.frameSize(wide, referenceHeight)

        assertTrue(FrameGeometry.overlayFitsOutside(wide, size.width))
    }

    @Test
    fun `a degenerate window produces no negative sizes`() {
        val size = FrameGeometry.frameSize(regionWidth = 0.dp, regionHeight = 0.dp)

        assertTrue(size.width.value >= 0f)
        assertTrue(size.height.value >= 0f)
    }

    // ── The whole arrangement, shared with the coach-mark overlay ────────

    @Test
    fun `the arrangement leaves both flanks their space beside the frame`() {
        // The overlay reserves this arrangement so its callouts land in the
        // slack. If the flanks and the frame did not add up to the region, the
        // callouts would be laid out over the photographs.
        val layout = FrameGeometry.threeUpLayout(referenceWidth, referenceHeight)
        val used = layout.flankWidth.value * 2 + layout.frame.width.value +
            FrameGeometry.Gap.value * 2

        assertEquals(referenceWidth.value, used, 1f)
    }

    @Test
    fun `hiding the readouts costs the frames nothing on the reference device`() {
        // Width is the surplus axis here, so the readouts were never taking
        // anything off the frames and switching them off cannot give any back.
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            detailsVisible = false,
        )
        val withDetails = FrameGeometry.threeUpLayout(referenceWidth, referenceHeight)

        assertEquals(withDetails.frame.width.value, layout.frame.width.value, 0.01f)
        assertEquals(withDetails.flankWidth.value, layout.flankWidth.value, 0.01f)
    }

    @Test
    fun `hiding the readouts widens the frames only where width actually binds`() {
        // A narrow window is the case where the flank is genuinely competing with
        // the photographs, so that is where the toggle earns its keep.
        val narrow = 900.dp
        val hidden = FrameGeometry.threeUpLayout(
            regionWidth = narrow,
            regionHeight = referenceHeight,
            detailsVisible = false,
        )
        val shown = FrameGeometry.threeUpLayout(narrow, referenceHeight)

        assertTrue(
            "frame did not grow: ${hidden.frame.width.value}dp vs ${shown.frame.width.value}dp",
            hidden.frame.width > shown.frame.width,
        )
        // But never all of it. The control block lives in this flank, and the
        // details toggle lives in the control block, so a flank of 0 dp is a
        // one-way door: nothing on screen or on the keyboard could switch the
        // readouts back on.
        assertTrue(
            "flank collapsed to ${hidden.flankWidth.value}dp, stranding the controls",
            hidden.flankWidth >= FrameGeometry.MinimumControlBlockWidth,
        )
    }

    @Test
    fun `the controls keep a flank in every combination of the view toggles`() {
        for (details in listOf(true, false)) {
            for (filmstrip in listOf(true, false)) {
                val layout = FrameGeometry.threeUpLayout(
                    regionWidth = referenceWidth,
                    regionHeight = referenceHeight,
                    detailsVisible = details,
                    filmstripVisible = filmstrip,
                )
                assertTrue(
                    "details=$details filmstrip=$filmstrip left the controls " +
                        "${layout.flankWidth.value}dp",
                    layout.flankWidth >= FrameGeometry.MinimumControlBlockWidth,
                )
            }
        }
    }

    @Test
    fun `the top row never overflows the region, so the frame stays centred`() {
        // The frame is centred by arithmetic — flank | frame | flank — not by an
        // Alignment. If the three ever sum to more than the region, the surplus
        // is paid on one side and the frame slides off centre.
        for (details in listOf(true, false)) {
            for (filmstrip in listOf(true, false)) {
                for (width in listOf(700.dp, 1000.dp, referenceWidth, 2000.dp)) {
                    val layout = FrameGeometry.threeUpLayout(
                        regionWidth = width,
                        regionHeight = referenceHeight,
                        detailsVisible = details,
                        filmstripVisible = filmstrip,
                    )
                    val used = layout.flankWidth.value * 2 + layout.frame.width.value +
                        FrameGeometry.Gap.value * 2

                    assertTrue(
                        "width=$width details=$details filmstrip=$filmstrip " +
                            "used ${used}dp of ${width.value}dp",
                        used <= width.value + 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun `the filmstrip keeps a flank of its own when the readouts are hidden`() {
        // It shares the control flank, so with the readouts off the flank must
        // still hold the strip *and* the view toggles — otherwise the toggle
        // silently does nothing, or the strip lands on the controls.
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            detailsVisible = false,
            filmstripVisible = true,
        )

        assertTrue(
            "flank collapsed to ${layout.flankWidth.value}dp with the filmstrip on",
            layout.flankWidth >= FrameGeometry.MinimumFilmstripFlankWidth,
        )
        assertEquals(
            FrameGeometry.FilmstripWidth.value,
            layout.filmstripWidth.value,
            0.01f,
        )
    }

    @Test
    fun `showing the filmstrip costs the frames nothing`() {
        // The measured regression this replaces: a 76dp full-width strip took
        // the reference frames from 675x450 to 618x412. As a 72dp *vertical*
        // strip inside the control flank's 82.5dp of horizontal slack the frames
        // do not move at all when it is toggled, which is the whole reason it
        // sits there. This is the assertion that prices any future widening of
        // the strip: past 74.5dp it starts taking height off all three frames.
        val without = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = 1.5f,
            filmstripVisible = false,
        )
        val with = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = 1.5f,
            filmstripVisible = true,
        )

        assertEquals(without.frame.height.value, with.frame.height.value, 0.01f)
        assertEquals(without.frame.width.value, with.frame.width.value, 0.01f)
        assertEquals(0f, without.filmstripWidth.value, 0.01f)
        assertEquals(FrameGeometry.FilmstripWidth.value, with.filmstripWidth.value, 0.01f)
    }

    @Test
    fun `the strip and the controls both fit inside the flank they share`() {
        // Two controls that share bounds is the defect this arithmetic prevents.
        // Whatever the flank resolves to, the strip may only take what is left
        // once the view-toggle row has its width.
        listOf(
            referenceWidth to referenceHeight,
            2400.dp to 900.dp,
            1200.dp to 800.dp,
            700.dp to 1100.dp,
        ).forEach { (w, h) ->
            val layout = FrameGeometry.threeUpLayout(
                regionWidth = w,
                regionHeight = h,
                aspect = 1.5f,
                filmstripVisible = true,
            )

            assertTrue(
                "strip ${layout.filmstripWidth.value}dp overruns a " +
                    "${layout.flankWidth.value}dp flank at ${w.value}x${h.value}",
                layout.filmstripWidth + FrameGeometry.Gap +
                    FrameGeometry.MinimumControlBlockWidth <= layout.flankWidth ||
                    layout.filmstripWidth.value == 0f,
            )
        }
    }

    @Test
    fun `a flank never claims more than a quarter of the region`() {
        // Tablet portrait is 700dp wide and still Medium, so it still gets this
        // layout. Uncapped, the two 260dp minima plus the strip leave nothing
        // for the photographs and the solver returns a frame of a few dp.
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = 596.dp,
            regionHeight = 1084.dp,
            aspect = 1.5f,
            detailsVisible = true,
            filmstripVisible = true,
        )

        assertTrue(
            "flank took ${layout.flankWidth.value}dp of a 596dp region",
            layout.flankWidth.value <= 596f / 4f + 1f,
        )
        assertTrue(
            "frames collapsed to ${layout.frame.width.value}dp",
            layout.frame.width.value > 200f,
        )
    }

    @Test
    fun `the image region is the window minus the sidebar and the outer padding`() {
        // The whole permitted chrome budget. If anything else ever appears in
        // the vertical stack, the instrumented test that compares the drawn
        // frames against this fails.
        val region = FrameGeometry.imageRegion(1480.dp, 924.dp, sidebarWidth = 88.dp)

        assertEquals(referenceWidth.value, region.width.value, 0.01f)
        assertEquals(referenceHeight.value, region.height.value, 0.01f)
    }

    @Test
    fun `the image region accounts for top status bar when specified`() {
        val region = FrameGeometry.imageRegion(
            1480.dp,
            924.dp,
            sidebarWidth = 88.dp,
            statusBarHeight = 36.dp,
        )

        assertEquals(referenceWidth.value, region.width.value, 0.01f)
        assertEquals(872f, region.height.value, 0.01f)
    }

    @Test
    fun `a 16 to 9 frame on the reference device is width-bound, not short-changed`() {
        // Measured 2026-08-08: 684 x 385 on the reference device, and no amount
        // of freed height changes it — two 16:9 frames abreast want 1464dp of a
        // 1376dp region. Reading that 385 as a height leak is what sent the last
        // investigation looking for 146dp that were never spent.
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = 16f / 9f,
        )

        assertEquals(684f, layout.frame.width.value, 1f)
        assertEquals(385f, layout.frame.height.value, 1f)

        // Twice the height budget, same frame: the constraint is the width.
        val taller = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight * 2,
            aspect = 16f / 9f,
        )
        assertEquals(layout.frame.height.value, taller.frame.height.value, 0.01f)
    }

    @Test
    fun `3 to 2 frames clear the reference floor once nothing is stacked above or below`() {
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = 1.5f,
            filmstripVisible = true,
        )

        assertEquals(675f, layout.frame.width.value, 1f)
        assertEquals(450f, layout.frame.height.value, 1f)
        assertTrue(layout.frame.height >= FrameGeometry.MinimumReferenceFrameHeight)
    }

    @Test
    fun `the arrangement agrees with the frame solver it is built on`() {
        // One source of truth: two answers that differ by a dp is an overlay
        // that drifts onto a frame.
        listOf(
            referenceWidth to referenceHeight,
            2400.dp to 900.dp,
            700.dp to 1100.dp,
        ).forEach { (w, h) ->
            val layout = FrameGeometry.threeUpLayout(w, h)

            assertEquals(
                "overlay placement disagrees at ${w.value}x${h.value}",
                FrameGeometry.overlayFitsOutside(w, layout.frame.width),
                layout.overlayOutside,
            )
            assertTrue(layout.frame.height.value * 2 + FrameGeometry.Gap.value <= h.value + 1f)
        }
    }

    @Test
    fun `the arrangement honours the aspect it is given`() {
        val layout = FrameGeometry.threeUpLayout(
            regionWidth = referenceWidth,
            regionHeight = referenceHeight,
            aspect = 1.5f,
        )

        assertEquals(1.5f, layout.frame.width.value / layout.frame.height.value, 0.01f)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `frame sizes at the reference windows do not depend on what the readouts print`() {
        // The lens line (REQ-AND-LAYOUT.13) is one more line in the flank readout
        // and in the neighbour overlay. The solver takes no readout content at all,
        // so these 3:2 sizes must hold.
        fun frameAt(width: Float, height: Float): Pair<Float, Float> {
            val region = FrameGeometry.imageRegion(width.dp, height.dp, sidebarWidth = 88.dp)
            val frame = FrameGeometry.threeUpLayout(region.width, region.height, aspect = 3f / 2f).frame
            return frame.width.value to frame.height.value
        }
        val reference = frameAt(1480f, 924f)
        assertEquals(675f, reference.first, 0.5f)
        assertEquals(450f, reference.second, 0.5f)
        val small = frameAt(1280f, 800f)
        assertEquals(582f, small.first, 0.5f)
        assertEquals(388f, small.second, 0.5f)
    }
}
