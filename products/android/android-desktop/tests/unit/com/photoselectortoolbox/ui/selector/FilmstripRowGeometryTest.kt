package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filmstrip row of tablet portrait (DESIGN §15.1), as the solver prices it.
 *
 * Portrait flips the scarce axis: the flank is too narrow for the column, the frames
 * are width-bound, and the region has height the photographs cannot use. The row goes
 * there and nowhere else, so the frames are the same size with the strip on and off;
 * where that height is missing the strip is dropped, as it always was.
 */
@Requirement("REQ-AND-LAYOUT.14", "REQ-AND-LAYOUT.05")
class FilmstripRowGeometryTest {

    /** The three-up layout for a window, the way the screen asks for it (88 dp sidebar). */
    private fun layoutAt(width: Dp, height: Dp, filmstrip: Boolean, details: Boolean = true): ThreeUpLayout {
        val region = FrameGeometry.imageRegion(width, height, sidebarWidth = SidebarWidth)
        return FrameGeometry.threeUpLayout(
            regionWidth = region.width,
            regionHeight = region.height,
            aspect = 1.5f,
            detailsVisible = details,
            filmstripVisible = filmstrip,
        )
    }

    private fun assertRowAt(width: Dp, height: Dp, frameWidth: Float) {
        val off = layoutAt(width, height, filmstrip = false)
        val on = layoutAt(width, height, filmstrip = true)
        val at = "${width.value}x${height.value}"

        assertEquals("placement at $at", FilmstripPlacement.ROW, on.filmstripPlacement)
        assertEquals("row height at $at", 64f, on.filmstripHeight.value, 0.01f)
        assertEquals("no column beside the row at $at", 0f, on.filmstripWidth.value, 0.01f)
        // Free: the frames are where they were with the strip off.
        assertEquals("frame width moved at $at", off.frame.width.value, on.frame.width.value, 0.01f)
        assertEquals("frame height moved at $at", off.frame.height.value, on.frame.height.value, 0.01f)
        assertEquals("flank moved at $at", off.flankWidth.value, on.flankWidth.value, 0.01f)
        assertEquals("frame width at $at", frameWidth, on.frame.width.value, 0.5f)
        // The two frame rows still hold the frames once the row and its gap are taken off.
        val region = FrameGeometry.imageRegion(width, height, sidebarWidth = SidebarWidth)
        val rowsLeft = (region.height - on.filmstripHeight - FrameGeometry.Gap * 2) / 2
        assertTrue("the frame rows at $at are ${rowsLeft.value} dp, under the frame", rowsLeft >= on.frame.height)
        assertEquals("off means no strip at $at", FilmstripPlacement.NONE, off.filmstripPlacement)
    }

    @Test
    fun `tablet portrait at 600 x 900 gets the row`() = assertRowAt(600.dp, 900.dp, frameWidth = 240f)

    @Test
    fun `tablet portrait at 720 x 1100 gets the row`() = assertRowAt(720.dp, 1100.dp, frameWidth = 300f)

    @Test
    fun `tablet portrait at 840 x 1200 gets the row`() = assertRowAt(840.dp, 1200.dp, frameWidth = 360f)

    @Test
    fun `the row also holds with the readouts hidden`() {
        val layout = layoutAt(720.dp, 1100.dp, filmstrip = true, details = false)
        assertEquals(FilmstripPlacement.ROW, layout.filmstripPlacement)
    }

    @Test
    fun `a window without spare height drops the strip rather than shrinking the frames`() {
        // 800 x 420: the frames are height-bound (297 x 198 in a 404 dp region), so a row
        // would take height from all three. The flank is too narrow for the column.
        val off = layoutAt(800.dp, 420.dp, filmstrip = false)
        val on = layoutAt(800.dp, 420.dp, filmstrip = true)
        assertEquals(FilmstripPlacement.NONE, on.filmstripPlacement)
        assertEquals(0f, on.filmstripHeight.value, 0.01f)
        assertEquals(off.frame.height.value, on.frame.height.value, 0.01f)
    }

    @Test
    fun `the row needs its height and its gap, and not a dp more`() {
        // A width-bound region exactly two frames, a gap, the row and its gap tall gets
        // the row; one dp less drops it.
        val probe = FrameGeometry.threeUpLayout(596.dp, 2000.dp, aspect = 1.5f, filmstripVisible = true)
        val exact = probe.frame.height * 2 + FrameGeometry.Gap * 2 + FrameGeometry.FilmstripRowHeight
        val fits = FrameGeometry.threeUpLayout(596.dp, exact + 0.01.dp, aspect = 1.5f, filmstripVisible = true)
        val short = FrameGeometry.threeUpLayout(596.dp, exact - 1.dp, aspect = 1.5f, filmstripVisible = true)
        assertEquals(FilmstripPlacement.ROW, fits.filmstripPlacement)
        assertEquals(FilmstripPlacement.NONE, short.filmstripPlacement)
    }

    @Test
    fun `the reference window keeps the column and never draws the row`() {
        val layout = layoutAt(1480.dp, 924.dp, filmstrip = true)
        assertEquals(FilmstripPlacement.COLUMN, layout.filmstripPlacement)
        assertEquals(FrameGeometry.FilmstripWidth.value, layout.filmstripWidth.value, 0.01f)
        assertEquals(0f, layout.filmstripHeight.value, 0.01f)
    }

    @Test
    fun `a column too narrow for a thumbnail becomes the row where height is spare`() {
        // 1024 x 720 left the column 6 dp wide, which clips every 60 dp thumbnail.
        val layout = layoutAt(1024.dp, 720.dp, filmstrip = true)
        assertEquals(FilmstripPlacement.ROW, layout.filmstripPlacement)
        // 1280 x 800 keeps a 70 dp column.
        val wide = layoutAt(1280.dp, 800.dp, filmstrip = true)
        assertEquals(FilmstripPlacement.COLUMN, wide.filmstripPlacement)
        assertEquals(70f, wide.filmstripWidth.value, 0.5f)
    }

    @Test
    fun `column and row are never both drawn`() {
        for (w in 600..2400 step 40) {
            for (h in 400..1400 step 40) {
                val layout = layoutAt(w.dp, h.dp, filmstrip = true)
                assertTrue(
                    "column ${layout.filmstripWidth} and row ${layout.filmstripHeight} at ${w}x$h",
                    layout.filmstripWidth == 0.dp || layout.filmstripHeight == 0.dp,
                )
                val off = layoutAt(w.dp, h.dp, filmstrip = false)
                if (layout.filmstripPlacement == FilmstripPlacement.ROW) {
                    assertEquals("the row moved the frames at ${w}x$h", off.frame.height.value, layout.frame.height.value, 0.01f)
                }
            }
        }
    }

    @Test
    fun `the first-run pill rises by the row and its gap`() {
        val without = FrameGeometry.firstRunPillBottomInset(1084.dp, 200.dp)
        val with = FrameGeometry.firstRunPillBottomInset(1084.dp, 200.dp, FrameGeometry.FilmstripRowHeight)
        // Measured from the region's bottom: the row and its gap, then half of what the
        // now shorter second row leaves around the frame.
        val rowsWithout = (1084f - 8f) / 2f
        val rowsWith = (1084f - 72f - 8f) / 2f
        val expectedShift = 72f + (rowsWith - 200f) / 2f - (rowsWithout - 200f) / 2f
        assertEquals(expectedShift, (with - without).value, 0.01f)
    }
}
