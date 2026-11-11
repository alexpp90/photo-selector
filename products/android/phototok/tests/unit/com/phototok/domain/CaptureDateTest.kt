package com.phototok.domain

import com.photoselector.core.Requirement
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The info panel's capture date: EXIF `DateTimeOriginal`, wall clock, localised (F-TOK-028). */
class CaptureDateTest {

    @Test
    @Requirement("REQ-TOK-FEATURE.07")
    fun `English shows month, day, year and a 12-hour time`() {
        val text = CaptureDate.format("2026:08:15 10:36:00", Locale.US)
        // JDK/ICU put a narrow no-break space before AM; compare the visible words.
        assertEquals("Aug 15, 2026, 10:36 AM", text?.replace(' ', ' ')?.replace(' ', ' '))
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.07")
    fun `German shows day, month, year and a 24-hour time`() {
        assertEquals("15.08.2026, 10:36", CaptureDate.format("2026:08:15 10:36:00", Locale.GERMANY))
    }

    @Test
    fun `the wall-clock time is shown as recorded, whatever the device time zone`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("15.08.2026, 23:59", CaptureDate.format("2026:08:15 23:59:59", Locale.GERMANY))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `missing, blank, zeroed or malformed dates are omitted`() {
        for (raw in listOf(null, "", "   ", "0000:00:00 00:00:00", "    :  :     :  :  ", "yesterday", "2026:13:45 10:00:00")) {
            assertNull("'$raw' is not a date", CaptureDate.format(raw, Locale.US))
        }
    }

    @Test
    fun `a date without seconds or with sub-second digits still parses`() {
        assertEquals("15.08.2026, 10:36", CaptureDate.format("2026:08:15 10:36", Locale.GERMANY))
        assertEquals("15.08.2026, 10:36", CaptureDate.format("2026:08:15 10:36:00.123", Locale.GERMANY))
    }
}
