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
 * The control-block ladder — FULL + locator row, COMPACT + locator row, COMPACT with the
 * counter alone, SCROLLING — and the rows that choose them (AD-1), the overlay-hide rule
 * and the first-run pill's lift (AD-16, AD-4).
 */
class ControlBlockFitTest {

    /** The reference device's density (320 dpi), where every dp is two whole pixels. */
    private val Reference = 2f

    /** Height of row 1, which the control flank is handed, at a window size. */
    private fun rowHeight(windowHeight: Int, statusBar: Int = 24): Float {
        // Row 1 is half the region less the gap, whichever axis binds the frame.
        val region = FrameGeometry.imageRegion(1280.dp, windowHeight.dp, SidebarWidth, statusBar.dp)
        return ((region.height - FrameGeometry.Gap) / 2).value
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `full height is 3 button rows, the toggle row and the 48 dp locator row, 362 dp`() {
        // 3 x 80 + (2 + 48) + 48 + 4 x 6. The counter line it replaces is gone: the counter
        // is inside the Go chip, so the price no longer moves with the font scale.
        assertEquals(362f, ControlBlockFit.fullHeight.value, 0.01f)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `compact height with the locator row is 252 dp`() {
        // 3 x 48 + 48 + 48 + 4 x 3.
        assertEquals(252f, ControlBlockFit.compactHeight(1f, 240.dp, withLocator = true).value, 0.01f)
        assertEquals(252f, ControlBlockFit.compactHeight(2f, 192.dp, withLocator = true).value, 0.01f)
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
        assertEquals(Mode.FULL, ControlBlockFit.mode(376.dp, 1f, Reference))
        assertEquals(Mode.FULL, ControlBlockFit.mode(363.dp, 1f, Reference))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(362.dp, 1f, Reference))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(202.dp, 1f, Reference))
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(201.dp, 1f, Reference))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07", "REQ-AND-WORKFLOW.06", "REQ-AND-WORKFLOW.07")
    fun `the ladder is FULL with the locator row, COMPACT with it, COMPACT with the counter alone, then scrolling`() {
        val wide = 240.dp
        assertEquals(ControlBlockFit.Fit(Mode.FULL, true), ControlBlockFit.fit(363.dp, 1f, Reference, wide))
        // FULL without the row is retired: a row of 332..362 dp gets COMPACT with the row.
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(362.dp, 1f, Reference, wide))
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(340.dp, 1f, Reference, wide))
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(253.dp, 1f, Reference, wide))
        // Below 252 dp the row goes, the counter stays (201 dp wide / 217 dp at this width).
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, false), ControlBlockFit.fit(252.dp, 1f, Reference, wide))
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, false), ControlBlockFit.fit(219.dp, 1f, Reference, wide))
        assertEquals(ControlBlockFit.Fit(Mode.SCROLLING, false), ControlBlockFit.fit(217.dp, 1f, Reference, wide))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07", "REQ-AND-WORKFLOW.06", "REQ-AND-WORKFLOW.07")
    fun `the locator row needs 192 dp of inner width and never appears below it`() {
        assertTrue(ControlBlockFit.fit(500.dp, 1f, Reference, 192.dp).locatorRow)
        assertFalse(ControlBlockFit.fit(500.dp, 1f, Reference, 191.dp).locatorRow)
        // 720 x 560 (142 dp inside) and 600 x 480 (112 dp inside) keep the counter alone.
        assertFalse(ControlBlockFit.fit(500.dp, 1f, Reference, 142.dp).locatorRow)
        assertFalse(ControlBlockFit.fit(500.dp, 1f, Reference, 112.dp).locatorRow)
        // An unbounded height is the widest rung the width allows.
        assertEquals(ControlBlockFit.Fit(Mode.FULL, true), ControlBlockFit.fit(androidx.compose.ui.unit.Dp.Infinity, 1.3f, Reference, 240.dp))
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(androidx.compose.ui.unit.Dp.Infinity, 1.3f, Reference, 200.dp))
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, false), ControlBlockFit.fit(androidx.compose.ui.unit.Dp.Infinity, 1.3f, Reference, 150.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `a locator chip is wide from a 160 dp cell, one word from 100 dp, and the word alone below`() {
        assertEquals(ControlBlockFit.LocatorStyle.WIDE, ControlBlockFit.locatorStyle(320.dp))
        assertEquals(ControlBlockFit.LocatorStyle.MID, ControlBlockFit.locatorStyle(319.dp))
        assertEquals(ControlBlockFit.LocatorStyle.MID, ControlBlockFit.locatorStyle(200.dp))
        assertEquals(ControlBlockFit.LocatorStyle.NARROW, ControlBlockFit.locatorStyle(199.dp))
        assertEquals(ControlBlockFit.LocatorStyle.NARROW, ControlBlockFit.locatorStyle(ControlBlockFit.LocatorMinInnerWidth))
        // Every cell the row can have is a 48 dp target wide.
        assertTrue(ControlBlockFit.LocatorMinInnerWidth / 2 >= 48.dp)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `the pixel price is the height the device matrix measured`() {
        // The FULL block is 3 x 80 + (2 + 48) + 48 + 4 x 6 dp, each child rounded to whole
        // pixels as layout rounds it: the half-pixel 6 dp gaps and 2 dp padding are why it is
        // not a plain 362 dp x density at 1.25x, 1.75x and 2.75x (device matrix, 2026-10-19).
        assertEquals(724, ControlBlockFit.fullHeightPx(2f)) // Pixel Tablet, 320 dpi
        assertEquals(455, ControlBlockFit.fullHeightPx(1.25f)) // Chromebook 714, 200 dpi
        assertEquals(636, ControlBlockFit.fullHeightPx(1.75f)) // Tab S11 Ultra, 280 dpi
        assertEquals(998, ControlBlockFit.fullHeightPx(2.75f)) // Pixel 4a, 440 dpi
        // The compact block with the locator row is 3 x 48 + 48 + 48 + 4 x 3 dp.
        assertEquals(504, ControlBlockFit.compactHeightPx(1f, 2f, 240.dp, withLocator = true))
        assertEquals(316, ControlBlockFit.compactHeightPx(1f, 1.25f, 240.dp, withLocator = true))
        assertEquals(440, ControlBlockFit.compactHeightPx(1f, 1.75f, 240.dp, withLocator = true))
        assertEquals(692, ControlBlockFit.compactHeightPx(1f, 2.75f, 240.dp, withLocator = true))
        // The wide compact block with the counter alone is its 201 dp price exactly.
        assertEquals(402, ControlBlockFit.compactHeightPx(1f, 2f))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `the chosen variant fits its row at every density`() {
        // Every whole dpi a device can report, at the font scales users pick. The row
        // arrives as layout hands it over: a whole number of pixels, as dp.
        for (dpi in 120..640) {
            val density = dpi / 160f
            for (fontScale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f)) {
                // The flank widths that change the arrangement (AD-16b): 2 x 2, counter on top,
                // counter inline, full.
                for (width in listOf(112.dp, 142.dp, 192.dp, 200.dp, 240.dp)) {
                    for (availablePx in (150 * density).toInt()..(420 * density).toInt()) {
                        val available = (availablePx / density).dp
                        val fit = ControlBlockFit.fit(available, fontScale, density, width)
                        val drawn = when (fit.mode) {
                            Mode.FULL -> ControlBlockFit.fullHeightPx(density)
                            Mode.COMPACT ->
                                ControlBlockFit.compactHeightPx(fontScale, density, width, fit.locatorRow)
                            Mode.SCROLLING -> continue
                        }
                        assertTrue(
                            "a $drawn px block was chosen for a $availablePx px row at $dpi dpi, " +
                                "scale $fontScale, $width wide",
                            drawn + ControlBlockFit.PixelSlack <= availablePx,
                        )
                    }
                }
            }
        }
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `a price is chosen only with a pixel of slack to spare`() {
        val pricePx = ControlBlockFit.compactHeightPx(1f, Reference, 142.dp)
        val withSlack = ((pricePx + ControlBlockFit.PixelSlack) / Reference).dp
        val exact = (pricePx / Reference).dp
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(withSlack, 1f, Reference, 142.dp))
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(exact, 1f, Reference, 142.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `an unbounded height is always the full block`() {
        assertEquals(Mode.FULL, ControlBlockFit.mode(androidx.compose.ui.unit.Dp.Infinity, 1.3f, Reference))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `a block too narrow for one row of four toggles never takes the full variant`() {
        // 4 x 48 + 3 x 6 = 210 dp.
        assertEquals(Mode.FULL, ControlBlockFit.mode(500.dp, 1f, Reference, 210.dp))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(500.dp, 1f, Reference, 209.dp))
        assertEquals(Mode.COMPACT, ControlBlockFit.mode(500.dp, 1f, Reference, 142.dp))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `font scale 1_3 at 1280 by 800 still resolves to a mode that fits`() {
        val row = rowHeight(800).dp
        val mode = ControlBlockFit.mode(row, 1.3f, Reference)
        val needed = when (mode) {
            Mode.FULL -> ControlBlockFit.fullHeight
            Mode.COMPACT -> ControlBlockFit.compactHeight(1.3f)
            Mode.SCROLLING -> row
        }
        assertTrue(needed <= row)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `windows resolve as the lab measured them`() {
        val flank1280 = 289.dp - 20.dp
        // 1280x800 fits the full block with the locator row (row 1 is ~376 dp tall, it needs 363).
        assertEquals(Mode.FULL, ControlBlockFit.mode(rowHeight(800).dp, 1f, Reference, flank1280))
        assertTrue(ControlBlockFit.fit(rowHeight(800).dp, 1f, Reference, flank1280).locatorRow)
        // 1024x720 (336 dp of row, 330 dp with a 36 dp status bar) no longer gets FULL: it
        // needs 363 dp with the row, and FULL without it is retired. COMPACT with the row.
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(rowHeight(720).dp, 1f, Reference, 240.dp))
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(rowHeight(720, statusBar = 36).dp, 1f, Reference, 240.dp))
        // 960x600 and below: the row is 252+ dp only down to ~260 dp, then the counter alone.
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, true), ControlBlockFit.fit(rowHeight(600).dp, 1f, Reference, 240.dp))
        // 720x560 (Chromebook small): a 150 dp flank, 142 dp inside, too narrow for the row.
        // Without a status bar the 268 dp row holds the 265.1 dp compact block; with one it scrolls.
        assertEquals(ControlBlockFit.Fit(Mode.COMPACT, false), ControlBlockFit.fit(268.dp, 1f, Reference, 142.dp))
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(rowHeight(560).dp, 1f, Reference, 142.dp))
        // 600x480: 48 dp toggles cannot be 2 x 2 in 228 dp beside three 48 dp rows,
        // so the block scrolls with the counter first rather than shrinking a target.
        assertEquals(Mode.SCROLLING, ControlBlockFit.mode(228.dp, 1f, Reference, 112.dp))
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

    // ── Measured content (F-FD-084) ─────────────────────────────────────

    private fun measure(width: Int, height: Int, word: Int = 40, wordH: Int = 16, key: Int = 24, keyH: Int = 14) =
        ControlBlockFit.ButtonMeasure(
            width = width, height = height, icon = 20, gap = 4,
            wordWidth = word, wordHeight = wordH, keyWidth = key, keyHeight = keyH,
        )

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `with room the button draws what its cell width picked`() {
        val inline = ControlBlockFit.buttonContent(ControlBlockFit.ButtonStyle.INLINE, measure(200, 40))
        assertEquals(ControlBlockFit.ButtonContent(icon = true, key = ControlBlockFit.KeyPlacement.BESIDE), inline)
        val full = ControlBlockFit.buttonContent(null, measure(140, 68))
        assertEquals(ControlBlockFit.ButtonContent(icon = true, key = ControlBlockFit.KeyPlacement.BESIDE), full)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.07")
    fun `the glyph goes before the key cap, and the key cap only when nothing else fits`() {
        // At font scale 2.0 in a 48 dp button: word over key cap is too tall, side by side fits.
        val big = measure(width = 120, height = 40, word = 70, wordH = 24, key = 40, keyH = 22)
        assertEquals(
            ControlBlockFit.ButtonContent(icon = false, key = ControlBlockFit.KeyPlacement.BESIDE),
            ControlBlockFit.buttonContent(ControlBlockFit.ButtonStyle.STACKED_WITH_ICON, big),
        )
        // Narrower still: the word alone, never cut.
        val narrow = big.copy(width = 80)
        assertEquals(
            ControlBlockFit.ButtonContent(icon = false, key = ControlBlockFit.KeyPlacement.NONE),
            ControlBlockFit.buttonContent(ControlBlockFit.ButtonStyle.STACKED, narrow),
        )
        // FULL at 2.0: the glyph on top no longer fits with the word and key cap; they stay.
        val full = measure(width = 120, height = 50, word = 70, wordH = 30, key = 40, keyH = 24)
        assertEquals(
            ControlBlockFit.ButtonContent(icon = false, key = ControlBlockFit.KeyPlacement.BESIDE),
            ControlBlockFit.buttonContent(null, full),
        )
    }
}
