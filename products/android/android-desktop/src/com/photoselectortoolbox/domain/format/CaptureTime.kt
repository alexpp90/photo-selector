package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.data.model.ImageItem
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * When a photo was taken: EXIF `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`, the form every camera
 * writes), read as wall-clock time. Wall-clock is enough for what it is used for — ordering one
 * shoot and telling whether two frames are minutes apart — because the frames of one shoot share
 * the camera's clock and time zone; it is never shown as an instant.
 */
object CaptureTime {

    /** [raw] in milliseconds of wall-clock time, or null when it is absent or not a date. */
    fun parse(raw: String?): Long? = wallClock(raw)?.toInstant(ZoneOffset.UTC)?.toEpochMilli()

    /**
     * [raw] (`DateTimeOriginal`) as the camera's wall-clock date and time, or null when it is
     * absent or not a date. The one parser behind [parse], [instant] and the solar elevation of
     * `TechniqueFactDetector` (shoot debrief SD-1a).
     */
    fun wallClock(raw: String?): LocalDateTime? {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = cleaned.split(' ', 'T')
        if (parts.size < 2) return null
        val date = parts[0].split(':', '-')
        val time = parts[1].split(':')
        if (date.size < 3 || time.size < 2) return null
        val year = date[0].toIntOrNull() ?: return null
        val month = date[1].toIntOrNull() ?: return null
        val day = date[2].toIntOrNull() ?: return null
        val hour = time[0].toIntOrNull() ?: return null
        val minute = time[1].toIntOrNull() ?: return null
        val second = time.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        // "0000:00:00 00:00:00" is what a camera without a set clock writes: no date.
        if (year < 1900 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
            return null
        }
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second.coerceAtMost(59))
        } catch (e: java.time.DateTimeException) {
            null // 31 February and the like
        }
    }

    /** EXIF `OffsetTimeOriginal` (`+02:00`, `-05:30`, `Z`) as an offset, or null when absent or garbled. */
    fun offset(raw: String?): ZoneOffset? {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            ZoneOffset.of(cleaned)
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    /**
     * The moment a photo was taken, in epoch milliseconds: [dateTimeOriginal] at the camera's
     * [offsetTimeOriginal], or — for a camera that writes no offset — in [fallbackZone], the
     * device's zone, which is where most shoots are culled. Null without a date. Learn's
     * `capture.time` fact (shoot debrief SD-1a): unlike [parse] it is compared with real
     * instants (a practice goal's start), and across shoots.
     */
    fun instant(dateTimeOriginal: String?, offsetTimeOriginal: String?, fallbackZone: ZoneId): Long? {
        val local = wallClock(dateTimeOriginal) ?: return null
        val zone: ZoneId = offset(offsetTimeOriginal) ?: fallbackZone
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /** [image]'s capture time, or null when its EXIF has none (or has not been read). */
    fun of(image: ImageItem): Long? = parse(image.exifData?.dateTimeOriginal)
}
