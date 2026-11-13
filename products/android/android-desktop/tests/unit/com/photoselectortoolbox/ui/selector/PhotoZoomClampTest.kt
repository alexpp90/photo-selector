package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pan never takes the photo off its viewport (review 2026-11-09 finding FD-UI-4): every way
 * to move a zoomed photo — a drag, a pinch, the wheel, `Shift`+arrows — stops where the photo's
 * edge meets the viewport's, and an axis on which the zoomed photo is still smaller than the
 * viewport stays centred. The region the navigator outlines is the part of the photo on screen.
 */
class PhotoZoomClampTest {

    /** A 1000 x 500 px viewport holding a photo that fills it. */
    private fun filled() = PhotoZoom().apply {
        viewport = IntSize(1000, 500)
        content = Size(1000f, 500f)
    }

    @Test
    fun `a drag stops at the photo's edge`() {
        val zoom = filled()
        zoom.oneToOne = 2f
        zoom.toggle()
        zoom.panBy(Offset(5_000f, 5_000f))
        assertEquals("the left edge meets the viewport's", Offset(500f, 250f), zoom.offset)
        zoom.panBy(Offset(-50_000f, -50_000f))
        assertEquals(Offset(-500f, -250f), zoom.offset)
    }

    @Test
    fun `Shift+arrow steps stop at the edge`() {
        val zoom = filled()
        zoom.oneToOne = 2f
        zoom.toggle()
        repeat(20) { zoom.panStep(1, 0) }
        assertEquals(-500f, zoom.offset.x, 0.01f)
        assertEquals(0f, zoom.offset.y, 0.01f)
    }

    @Test
    fun `a pinch and the wheel stay inside too`() {
        val zoom = filled()
        zoom.transform(3f, Offset(10_000f, 0f))
        assertEquals("(3000 - 1000) / 2", 1000f, zoom.offset.x, 0.01f)
        zoom.zoomAt(1f / 1.5f, Offset(400f, 0f))
        assertEquals("zoomed out to 2x, the bound shrinks to (2000 - 1000) / 2", 500f, zoom.offset.x, 0.01f)
    }

    @Test
    fun `a letterboxed axis stays centred until the photo outgrows the viewport`() {
        val zoom = PhotoZoom().apply {
            viewport = IntSize(1000, 500)
            content = Size(500f, 500f)
        }
        zoom.transform(1.5f, Offset(300f, 300f))
        assertEquals("750 px wide in a 1000 px viewport: nothing to pan", 0f, zoom.offset.x, 0.01f)
        assertEquals("(750 - 500) / 2", 125f, zoom.offset.y, 0.01f)
    }

    @Test
    fun `a smaller photo shown next re-clamps the kept pan`() {
        val zoom = filled()
        zoom.oneToOne = 2f
        zoom.toggle()
        zoom.panBy(Offset(500f, 0f))
        zoom.content = Size(500f, 500f)
        assertEquals("1000 px wide at 2x in a 1000 px viewport: centred", 0f, zoom.offset.x, 0.01f)
    }

    @Test
    fun `an unknown photo size is taken as the viewport`() {
        val zoom = PhotoZoom().apply { viewport = IntSize(1000, 500) }
        zoom.oneToOne = 2f
        zoom.toggle()
        zoom.panBy(Offset(5_000f, 0f))
        assertEquals(500f, zoom.offset.x, 0.01f)
    }

    @Test
    fun `the visible region is the part of the photo on screen`() {
        val zoom = filled()
        val whole = zoom.visibleRegion()
        assertEquals(0f, whole.left, 0.001f)
        assertEquals(1f, whole.right, 0.001f)

        zoom.oneToOne = 2f
        zoom.toggle()
        val centre = zoom.visibleRegion()
        assertEquals(0.25f, centre.left, 0.001f)
        assertEquals(0.75f, centre.right, 0.001f)
        assertEquals(0.25f, centre.top, 0.001f)
        assertEquals(0.75f, centre.bottom, 0.001f)

        repeat(20) { zoom.panStep(1, 0) }
        val right = zoom.visibleRegion()
        assertEquals("panned to the right edge", 1f, right.right, 0.001f)
        assertEquals(0.5f, right.left, 0.001f)
        assertTrue(right.width < 1f)
    }
}
