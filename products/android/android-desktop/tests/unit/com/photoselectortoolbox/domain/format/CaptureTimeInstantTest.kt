package com.photoselectortoolbox.domain.format

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `capture.time` fact's instant (shoot debrief SD-1a): the camera's offset, else the device's zone. */
class CaptureTimeInstantTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `the camera's own offset wins over the device's zone`() {
        val expected = OffsetDateTime.of(2026, 6, 21, 21, 25, 0, 0, ZoneOffset.ofHours(-5)).toInstant().toEpochMilli()
        assertEquals(expected, CaptureTime.instant("2026:06:21 21:25:00", "-05:00", berlin))
    }

    @Test
    fun `without an offset the wall clock is read in the fallback zone`() {
        val expected = LocalDateTime.of(2026, 1, 5, 8, 0).atZone(berlin).toInstant().toEpochMilli()
        assertEquals(expected, CaptureTime.instant("2026:01:05 08:00:00", null, berlin))
        assertEquals(expected, CaptureTime.instant("2026:01:05 08:00:00", "  :  ", berlin))
    }

    @Test
    fun `no date, an unset clock or an impossible date give no instant`() {
        assertNull(CaptureTime.instant(null, "+02:00", berlin))
        assertNull(CaptureTime.instant("0000:00:00 00:00:00", null, berlin))
        assertNull(CaptureTime.instant("2026:02:31 10:00:00", null, berlin))
    }

    @Test
    fun `offsets parse as EXIF writes them`() {
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), CaptureTime.offset("+05:30"))
        assertEquals(ZoneOffset.UTC, CaptureTime.offset("Z"))
        assertNull(CaptureTime.offset("garbage"))
    }

    @Test
    fun `parse stays the wall clock read as UTC`() {
        val expected = LocalDateTime.of(2026, 6, 21, 21, 25).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expected, CaptureTime.parse("2026:06:21 21:25:00"))
    }
}
