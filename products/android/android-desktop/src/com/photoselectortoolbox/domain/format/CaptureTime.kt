package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.data.model.ImageItem
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * When a photo was taken: EXIF `DateTimeOriginal` (`yyyy:MM:dd HH:mm:ss`, the form every camera
 * writes), read as wall-clock time. Wall-clock is enough for what it is used for — ordering one
 * shoot and telling whether two frames are minutes apart — because the frames of one shoot share
 * the camera's clock and time zone; it is never shown as an instant.
 */
object CaptureTime {

    /** [raw] in milliseconds of wall-clock time, or null when it is absent or not a date. */
    fun parse(raw: String?): Long? {
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
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            null // 31 February and the like
        }
    }

    /** [image]'s capture time, or null when its EXIF has none (or has not been read). */
    fun of(image: ImageItem): Long? = parse(image.exifData?.dateTimeOriginal)
}
