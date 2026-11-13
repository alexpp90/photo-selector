package com.photoselectortoolbox.domain.format

import com.photoselector.core.model.ExifData
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifFormatterTest {

    @Test
    fun `fractional exposures render as a fraction`() {
        assertEquals("1/250s", ExifFormatter.shutterSpeed(1.0 / 250.0))
        assertEquals("1/8000s", ExifFormatter.shutterSpeed(1.0 / 8000.0))
        // A value a hair under the fraction rounds to it, never down a step (full review 2026-11-09).
        assertEquals("1/250s", ExifFormatter.shutterSpeed(0.004000000000000001))
        assertEquals("1/60s", ExifFormatter.shutterSpeed(0.01667))
    }

    @Test
    fun `long exposures render as seconds`() {
        assertEquals("2.0s", ExifFormatter.shutterSpeed(2.0))
        assertEquals("1.0s", ExifFormatter.shutterSpeed(1.0))
    }

    @Test
    fun `a nonsensical exposure does not crash or divide by zero`() {
        assertEquals("—", ExifFormatter.shutterSpeed(0.0))
        assertEquals("—", ExifFormatter.shutterSpeed(-1.0))
    }

    @Test
    fun `aperture and focal length use the expected notation`() {
        assertEquals("f/2.8", ExifFormatter.aperture(2.8))
        assertEquals("35mm", ExifFormatter.focalLength(35.0))
        assertEquals("ISO 400", ExifFormatter.iso(400))
    }

    @Test
    fun `decimal separator is always a dot regardless of device locale`() {
        // A German device would otherwise render "f/2,8", which does not match
        // what the desktop app writes and forces the reader to re-parse.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("f/2.8", ExifFormatter.aperture(2.8))
            assertEquals("2.5s", ExifFormatter.shutterSpeed(2.5))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `summary line joins the values in shooting order`() {
        val exif = ExifData(
            shutterSpeed = 1.0 / 250.0,
            aperture = 2.8,
            focalLength = 35.0,
            iso = 400,
        )
        assertEquals("1/250s  ·  f/2.8  ·  35mm  ·  ISO 400", ExifFormatter.summaryLine(exif))
    }

    @Test
    fun `summary line drops missing values rather than padding with dashes`() {
        val exif = ExifData(aperture = 1.4, iso = 100)
        assertEquals("f/1.4  ·  ISO 100", ExifFormatter.summaryLine(exif))
    }

    @Test
    fun `summary line is null when nothing at all is known`() {
        assertNull(ExifFormatter.summaryLine(null))
        assertNull(ExifFormatter.summaryLine(ExifData()))
    }

    @Test
    fun `detail rows keep every field and label the unknowns`() {
        val rows = ExifFormatter.detailRows(ExifData(iso = 200))
        val map = rows.toMap()
        assertEquals(6, rows.size)
        assertEquals("200", map["ISO"])
        assertEquals(ExifFormatter.UNKNOWN, map["Aperture"])
        // "Unknown" is a legitimate value in the panel: not knowing the lens is
        // information the photographer wants, not a field to hide.
        assertEquals(ExifFormatter.UNKNOWN, map["Lens"])
    }

    @Test
    fun `detail rows are empty when there is no exif at all`() {
        assertTrue(ExifFormatter.detailRows(null).isEmpty())
    }

    // ── Neighbour overlay lines ──────────────────────────────────────────

    @Test
    fun `overlay splits exposure and optics onto two aligned lines`() {
        // Two lines, always split at the same point, because the comparison is
        // between Previous and Next: shutter sits above shutter and ISO above
        // ISO, so a difference shows up in one place rather than as two strings
        // to re-read.
        val lines = ExifFormatter.overlayLines(
            ExifData(
                shutterSpeed = 1.0 / 250.0,
                aperture = 2.8,
                focalLength = 35.0,
                iso = 400,
            )
        )

        assertEquals(2, lines.size)
        assertEquals("1/250s · f/2.8", lines[0])
        assertEquals("35mm · ISO 400", lines[1])
    }

    @Test
    fun `overlay omits the lens, which never fits and never varies in a burst`() {
        val lines = ExifFormatter.overlayLines(
            ExifData(shutterSpeed = 0.004, aperture = 4.0, lens = "XF 16-55mm F2.8 R LM WR")
        )

        lines.forEach { assertFalse(it.contains("16-55")) }
    }

    @Test
    fun `overlay drops a line rather than printing placeholders`() {
        // Unlike the details panel, where "Unknown" is information, an overlay
        // sits on the photograph: a line of em-dashes is obstruction that says
        // nothing.
        val lines = ExifFormatter.overlayLines(ExifData(shutterSpeed = 0.004, aperture = 2.8))

        assertEquals(1, lines.size)
        assertEquals("1/250s · f/2.8", lines[0])
    }

    @Test
    fun `overlay returns nothing when nothing is known`() {
        assertTrue(ExifFormatter.overlayLines(null).isEmpty())
        assertTrue(ExifFormatter.overlayLines(ExifData()).isEmpty())
    }

    @Test
    fun `overlay keeps the US decimal separator like every other surface`() {
        val lines = ExifFormatter.overlayLines(ExifData(aperture = 2.8, iso = 100))

        assertTrue(lines.first().contains("f/2.8"))
        assertFalse(lines.first().contains("f/2,8"))
    }

    @Test
    fun `lens line is the trimmed lens name`() {
        assertEquals("FE 85mm F1.4 GM", ExifFormatter.lensLine(ExifData(lens = "  FE 85mm F1.4 GM  ")))
    }

    @Test
    fun `lens line is null without EXIF, with a blank lens and with the Unknown placeholder`() {
        assertNull(ExifFormatter.lensLine(null))
        assertNull(ExifFormatter.lensLine(ExifData(lens = "")))
        assertNull(ExifFormatter.lensLine(ExifData(lens = "   ")))
        assertNull(ExifFormatter.lensLine(ExifData(lens = ExifFormatter.UNKNOWN)))
        assertNull("the default ExifData has no lens", ExifFormatter.lensLine(ExifData()))
    }
}
