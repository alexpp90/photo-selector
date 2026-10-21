package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.photoselector.core.Requirement
import com.photoselectortoolbox.ui.selector.ControlBlockFit.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three control-block heights and the rows that choose them (AD-1), the
 * overlay-hide rule and the first-run pill's lift (AD-16, AD-4).
 */
class ControlBlockFitTest {

    /** Height of row 1, which the control flank is handed, at a window size. */
    private fun rowHeight(windowHeight: Int, statusBar: Int = 24): Float {
        // Row 1 is half the region less the gap, whichever axis binds the frame.
        val region = FrameGeometry.imageRegion(1280.dp, windowHeight.dp, SidebarWidth, statusBar.dp)
        return ((region.height - FrameGeometry.Gap) / 2).value
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `full height is the measured 332 dp at font scale 1`() {
        assertEquals(332f, ControlBlockFit.fullHeight(1f).value, 0.01f)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `compact height is 201 dp wide and grows where 48 dp toggles need a second row`() {
        // Wide: four 48 dp toggles and the counter share one row.
        assertEquals(201f, ControlBlockFit.compactHeight(1f).value, 0.01f)
        // 1024 wide (inner 240): the counter no longer fits beside four toggles and leads.
        assertEquals(217.1f, ControlBlockFit.compactHeight(1f, 240.dp).value, 0.01f)
        // Chromebook small / manifest minimum (inner 142 / 112): 2 x 2 toggles, counter on top.
        assertEquals(265.1f, ControlBlockFit.compactHeight(1f, 142.dp).value, 0.01f)
        assertEquals(265.1f, ControlBlockFit.compactHeight(1f, 112.dp).value, 0.01f)
        // A 2 x 2 grid with room for the counter beside it.
        assertEquals(249f, ControlBlockFit.compactHeight(1f, 170.dp).value, 0.01f)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `mode picks the first variant that fits`() {
        assertEquals(Mode.FULL, ControlBlockFit.mode(376.dp, 1f))
        assertEquals(Mode.FULL, ControlBlockFit.mode(333.dp, 1f))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(332.dp, 1f))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(202.dp, 1f))
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(201.dp, 1f))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `a price is chosen only with the pixel rounding allowance to spare`() {
        val price = ControlBlockFit.compactHeight(1f, 142.dp)
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(price + ControlBlockFit.PixelRoundingAllowance, 1f, 142.dp))
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(price, 1f, 142.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `an unbounded height is always the full block`() {
        assertEquals(Mode.FULL, ControlBlockFit.mode(androidx.compose.ui.unit.Dp.Infinity, 1.3f))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `a block too narrow for one row of four toggles never takes the full variant`() {
        // 4 x 48 + 3 x 6 = 210 dp.
        assertEquals(Mode.FULL, ControlBlockFit.mode(500.dp, 1f, 210.dp))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(500.dp, 1f, 209.dp))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(500.dp, 1f, 142.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `font scale 1_3 at 1280 by 800 still resolves to a mode that fits`() {
        val row = rowHeight(800).dp
        val mode = ControlBlockFit.mode(row, 1.3f)
        val needed = when (mode) {
            Mode.FULL -> ControlBlockFit.fullHeight(1.3f)
            Mode.COMPACT -> ControlBlockFit.compactHeight(1.3f)
            Mode.SCROLLING -> row
        }
        assertTrue(needed <= row)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `windows resolve as the lab measured them`() {
        val flank1280 = 289.dp - 20.dp
        // 1280x800 fits the full block at scale 1 (row 1 is ~376 dp tall).
        assertEquals(Mode.FULL, ControlBlockFit.mode(rowHeight(800).dp, 1f, flank1280))
        // 1024x720 is the knife edge: 336 dp of row with a 24 dp status bar (full
        // fits by 4 dp), 330 dp with the 36 dp one (compact). Both are legal.
        assertEquals(Mode.FULL, ControlBlockFit.mode(rowHeight(720).dp, 1f, 240.dp))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(rowHeight(720, statusBar = 36).dp, 1f, 240.dp))
        // 960x600 and below do not fit the full block; compact does.
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(rowHeight(600).dp, 1f, 240.dp))
        // 720x560 (Chromebook small): a 150 dp flank, 142 dp inside. Without a status
        // bar the 268 dp row holds the 265.1 dp compact block; with one it scrolls.
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(268.dp, 1f, 142.dp))
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(rowHeight(560).dp, 1f, 142.dp))
        // 600x480: 48 dp toggles cannot be 2 x 2 in 228 dp beside three 48 dp rows,
        // so the block scrolls with the counter first rather than shrinking a target.
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(228.dp, 1f, 112.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `a block too narrow for the counter beside the toggles gives it its own line`() {
        assertTrue(ControlBlockFit.counterInline(280.dp))
        assertFalse(ControlBlockFit.counterInline(240.dp))
        assertFalse(ControlBlockFit.counterInline(142.dp))
        assertTrue(
            ControlBlockFit.compactHeight(1f, innerWidth = 240.dp) >
                ControlBlockFit.compactHeight(1f, innerWidth = 280.dp)
        )
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `toggles are four to a row from 192 dp and a 2 by 2 grid below`() {
        assertEquals(4, ControlBlockFit.togglesPerRow(192.dp))
        assertEquals(2, ControlBlockFit.togglesPerRow(191.dp))
        assertEquals(48.dp, ControlBlockFit.compactToggleBlockHeight(240.dp))
        assertEquals(96.dp, ControlBlockFit.compactToggleBlockHeight(112.dp))
        // The hit target is 48 dp regardless of the width (AD-17).
        assertEquals(48.dp, ControlBlockFit.ToggleHitSize)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `the button shape follows the cell width so the word and key cap always fit`() {
        assertEquals(ControlBlockFit.ButtonStyle.INLINE, ControlBlockFit.buttonStyle(240.dp))
        assertEquals(ControlBlockFit.ButtonStyle.STACKED_WITH_ICON, ControlBlockFit.buttonStyle(142.dp))
        assertEquals(ControlBlockFit.ButtonStyle.STACKED, ControlBlockFit.buttonStyle(112.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `the compact counter never outgrows the 48 dp toggle row even at font scale 2`() {
        assertTrue(ControlBlockFit.compactCounterHeight(2f) <= ControlBlockFit.ToggleHitSize)
    }

    // ── AD-16 ────────────────────────────────────────────────────────────

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the in-frame overlay is hidden on a small frame and kept on a large one`() {
        // 600 x 480 window: frame ~342 x 228, the overlay would cover ~38 %.
        assertTrue(FrameGeometry.overlayHiddenInFrame(DpSize(342.dp, 228.dp), overlayOutside = false))
        // 1024 x 720: ~504 x 336, ~17 %.
        assertFalse(FrameGeometry.overlayHiddenInFrame(DpSize(504.dp, 336.dp), overlayOutside = false))
        // The reference device: 600 x 450 (or 675 x 450), ~11 %.
        assertFalse(FrameGeometry.overlayHiddenInFrame(DpSize(675.dp, 450.dp), overlayOutside = false))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `an outside overlay covers nothing so is never hidden`() {
        assertFalse(FrameGeometry.overlayHiddenInFrame(DpSize(100.dp, 70.dp), overlayOutside = true))
    }

    // ── AD-4 ─────────────────────────────────────────────────────────────

    @Test
    @Requirement("REQ-AND-LAYOUT.17")
    fun `the pill is lifted clear of the maximise badge band`() {
        // Height-bound: frames reach the bottom, so the band is badge + inset + clearance.
        val region = 872.dp
        val frame = FrameGeometry.threeUpLayout(1376.dp, region, 1.5f).frame
        val inset = FrameGeometry.firstRunPillBottomInset(region, frame.height)
        assertEquals(
            (MaximiseBadgeSize + MaximiseBadgeInset + FrameGeometry.PillBadgeClearance).value,
            inset.value,
            0.5f,
        )
        // Strictly above the badge top (badge top is badge + inset above the frame bottom).
        assertTrue(inset > MaximiseBadgeSize + MaximiseBadgeInset)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.17")
    fun `when frames are centred in a taller row the pill follows them up`() {
        val tall = FrameGeometry.firstRunPillBottomInset(regionHeight = 800.dp, frameHeight = 300.dp)
        val snug = FrameGeometry.firstRunPillBottomInset(regionHeight = 800.dp, frameHeight = 396.dp)
        assertTrue(tall > snug)
    }
}
