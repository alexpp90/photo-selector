package com.phototok.domain

import java.text.DateFormat
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The capture date the info panel shows first: EXIF `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`) in
 * the user's locale (EN "Aug 15, 2026, 10:36 AM", DE "15.08.2026, 10:36").
 *
 * EXIF stores the camera's wall clock without a zone, so it is parsed and printed in one fixed
 * zone: the time is shown as the camera recorded it, never shifted to the phone's zone.
 */
object CaptureDate {
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun format(dateTimeOriginal: String?, locale: Locale): String? {
        val raw = dateTimeOriginal?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("0000")) return null
        val date = PATTERNS.firstNotNullOfOrNull { pattern ->
            val parser = SimpleDateFormat(pattern, Locale.ROOT).apply {
                timeZone = UTC
                isLenient = false
            }
            parser.parse(raw, ParsePosition(0))
        } ?: return null
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            .apply { timeZone = UTC }
            .format(date)
    }

    // Seconds are optional on some cameras; trailing sub-seconds are ignored.
    private val PATTERNS = listOf(
        "yyyy:MM:dd HH:mm:ss", // ui-text: ignore EXIF parse pattern, never shown
        "yyyy:MM:dd HH:mm", // ui-text: ignore EXIF parse pattern, never shown
    )
}
