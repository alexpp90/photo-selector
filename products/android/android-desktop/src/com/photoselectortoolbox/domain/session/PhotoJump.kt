package com.photoselectortoolbox.domain.session

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TreeSet

/** What the "Go to photo" input resolves to. */
sealed interface JumpResolution {

    /**
     * Jump to [index] in the selector's list (`SelectorUiState.images`).
     *
     * [hiddenByFilter] is true when the frame does not pass the active rating
     * filter: it exists and has the number the photographer typed, but
     * Previous/Next would skip it. What to do then is the screen's decision.
     *
     * [date] is set when the input was a date: which day was asked for and which
     * day the jump landed on — another day when the one asked for has no photo.
     */
    data class Resolved(
        val index: Int,
        val hiddenByFilter: Boolean = false,
        val date: DateJump? = null,
    ) : JumpResolution

    /** A number outside 1…[total]. [total] is 0 for an empty list. */
    data class OutOfRange(val total: Int) : JumpResolution

    /** Nothing typed (or only whitespace). */
    data object Empty : JumpResolution

    /**
     * Text that names no photo; [text] is the trimmed input, for the message.
     *
     * Also what a date resolves to when no photo in the list has a known time (and
     * the text names no file either): there is no day to land on.
     */
    data class NoNameMatch(val text: String) : JumpResolution
}

/**
 * A date jump: the day [requested] and the day it [landed] on. They differ when the
 * requested day has no photo and the nearest day that has one was taken instead —
 * the UI then says so ("No photos on 12 Oct 2026 — showing 14 Oct 2026").
 */
data class DateJump(val requested: LocalDate, val landed: LocalDate) {
    val isExact: Boolean get() = requested == landed
}

/**
 * What the "Go to photo" text is, before it is looked up in the list.
 *
 * - Digits only (optionally signed): a [Position], as before.
 * - `2026-10-12` (ISO), `12.10.2026`, `12.10.26`: a [Day]. Day and month may have one
 *   digit (`2.3.2026`). A two-digit year is in this century unless that is after the
 *   current year, then in the last (`12.10.98` is 1998).
 * - `12.10.`: a [DayOfAnyYear] — day and month, the year picked from the photos.
 * - A date shape that is no date (`31.02.2026`, `12.13.`): [NotADate] — looked up
 *   as a file name, because anything that is neither a date nor a position is one.
 * - Anything else: a [Name].
 */
sealed interface JumpQuery {
    data object Empty : JumpQuery
    data class Position(val text: String) : JumpQuery
    data class Day(val date: LocalDate) : JumpQuery
    data class DayOfAnyYear(val monthDay: MonthDay) : JumpQuery
    data class NotADate(val text: String) : JumpQuery
    data class Name(val text: String) : JumpQuery

    companion object {
        private val NUMBER = Regex("[+-]?[0-9]+")
        private val ISO = Regex("([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})")
        private val DOTTED = Regex("([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{2}|[0-9]{4})")
        private val DOTTED_NO_YEAR = Regex("([0-9]{1,2})\\.([0-9]{1,2})\\.")

        /** [input] classified; [today] decides the century of a two-digit year. */
        fun parse(input: String, today: LocalDate): JumpQuery {
            val text = input.trim()
            if (text.isEmpty()) return Empty
            if (NUMBER.matches(text)) return Position(text)
            ISO.matchEntire(text)?.let { m ->
                val (y, mo, d) = m.destructured
                return dayOrNotADate(text) { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }
            }
            DOTTED.matchEntire(text)?.let { m ->
                val (d, mo, y) = m.destructured
                val year = if (y.length == 2) fullYear(y.toInt(), today) else y.toInt()
                return dayOrNotADate(text) { LocalDate.of(year, mo.toInt(), d.toInt()) }
            }
            DOTTED_NO_YEAR.matchEntire(text)?.let { m ->
                val (d, mo) = m.destructured
                return try {
                    DayOfAnyYear(MonthDay.of(mo.toInt(), d.toInt()))
                } catch (_: DateTimeException) {
                    NotADate(text)
                }
            }
            return Name(text)
        }

        private inline fun dayOrNotADate(text: String, date: () -> LocalDate): JumpQuery = try {
            Day(date())
        } catch (_: DateTimeException) {
            NotADate(text)
        }

        private fun fullYear(twoDigits: Int, today: LocalDate): Int {
            val thisCentury = 2000 + twoDigits
            return if (thisCentury > today.year) 1900 + twoDigits else thisCentury
        }
    }
}

/**
 * The local days on which the list has photos — what a date picker may offer
 * (Material 3 `SelectableDates`) — keyed on the same timestamp as the sort order.
 *
 * @property dates every such day, ascending.
 */
data class PhotoDates(val dates: List<LocalDate>) {
    private val lookup: Set<LocalDate> by lazy { dates.toHashSet() }
    private val yearSet: Set<Int> by lazy { dates.mapTo(HashSet()) { it.year } }

    val first: LocalDate? get() = dates.firstOrNull()
    val last: LocalDate? get() = dates.lastOrNull()
    val isEmpty: Boolean get() = dates.isEmpty()

    operator fun contains(date: LocalDate): Boolean = date in lookup

    /** Whether some photo was taken in [year] (`SelectableDates.isSelectableYear`). */
    fun isSelectableYear(year: Int): Boolean = year in yearSet

    /**
     * Whether the picker day [utcTimeMillis] has photos (`SelectableDates.isSelectableDate`).
     * The picker passes a day as UTC midnight, whatever the device's zone.
     */
    fun isSelectableUtcMillis(utcTimeMillis: Long): Boolean = dateOfPickerMillis(utcTimeMillis) in lookup

    companion object {
        val EMPTY = PhotoDates(emptyList())

        /** The days of [images] in [zone]; photos with an unknown time have none. */
        fun of(images: List<ImageItem>, zone: ZoneId): PhotoDates =
            PhotoDates(images.mapNotNullTo(TreeSet()) { PhotoJump.dayOf(it, zone) }.toList())

        /** The day a Material date picker means by [utcTimeMillis] (UTC midnight). */
        fun dateOfPickerMillis(utcTimeMillis: Long): LocalDate =
            Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate()

        /** [date] as a Material date picker's millis (UTC midnight), e.g. its initial selection. */
        fun pickerMillisOf(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}

/**
 * The dialog's supporting line, from the same resolution as the jump: the file it would
 * open, and when that photo was taken (null when its time is unknown).
 */
data class JumpPreview(
    val resolution: JumpResolution,
    val fileName: String? = null,
    val takenAt: LocalDateTime? = null,
)

/**
 * Resolution of the "Go to photo" input, pure.
 *
 * **The numbering is the counter's.** Every position readout in the selector
 * (control block `n / N`, the readout block, the neighbour readouts, fullscreen)
 * shows `currentIndex + 1` of `images.size` — the position in the whole list in
 * its current order, **not** in the rating-filtered subset; a rating filter
 * changes which frames Previous/Next visit, not their numbers. So the number
 * typed is a 1-based index into `images`, whatever filter is active.
 *
 * Input that is a whole number (optionally signed) is a position; input shaped
 * as a date (`2026-10-12`, `12.10.2026`, `12.10.26`, `12.10.`) is a day
 * ([JumpQuery], `[USER 2026-11-04]`). Anything else, an impossible date such as
 * `31.02.2026` included, is matched against file names — the primary file's and its companions', so
 * `0123.ARW` finds the RAW twin of `DSC_0123.JPG` — case-insensitively as a
 * substring, first match in list order, preferring a frame the rating filter
 * shows over one it hides.
 */
object PhotoJump {

    /**
     * Resolve [input] against [images] in their current order.
     *
     * A date (see [JumpQuery]) lands on the **first photo of that day in list order**
     * — newest first, that is the day's latest photo — preferring a frame the rating
     * filter shows. The day is the local date of [ImageItem.lastModified] in [zone],
     * the timestamp the sort order uses, so a date jump and Newest/Oldest first never
     * disagree. Photos with an unknown time (`lastModified <= 0`) are never date targets.
     * A day without photos lands on the nearest day that has one (equally near: the
     * later day), reported in [JumpResolution.Resolved.date]. `12.10.` takes the current
     * year if it has photos that day, else the most recent year that does, else the
     * current year (then the nearest day). When no photo has a known time, a date is
     * looked up as a file name instead.
     */
    fun resolve(
        input: String,
        images: List<ImageItem>,
        ratingFilter: RatingFilter = RatingFilter.All,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): JumpResolution = when (val query = JumpQuery.parse(input, today)) {
        JumpQuery.Empty -> JumpResolution.Empty
        is JumpQuery.Position -> resolvePosition(query.text, images, ratingFilter)
        is JumpQuery.Day ->
            resolveDate(query.date, images, ratingFilter, zone) ?: resolveName(input.trim(), images, ratingFilter)
        is JumpQuery.DayOfAnyYear -> {
            val days = images.map { dayOf(it, zone) }
            val year = yearFor(query.monthDay, days, today)
            resolveDay(query.monthDay.atYear(year), images, days, ratingFilter)
                ?: resolveName(input.trim(), images, ratingFilter)
        }
        is JumpQuery.NotADate -> resolveName(query.text, images, ratingFilter)
        is JumpQuery.Name -> resolveName(query.text, images, ratingFilter)
    }

    /**
     * Land on [date] (the date picker's entry): the first photo of that day in list
     * order, preferring a frame [ratingFilter] shows; a day without photos lands on the
     * nearest day that has one (ties: the later day). Null when no photo has a known time.
     */
    fun resolveDate(
        date: LocalDate,
        images: List<ImageItem>,
        ratingFilter: RatingFilter = RatingFilter.All,
        zone: ZoneId = ZoneId.systemDefault(),
    ): JumpResolution.Resolved? = resolveDay(date, images, images.map { dayOf(it, zone) }, ratingFilter)

    /** [resolve], with the file name and time of the photo it lands on, for the supporting line. */
    fun preview(
        input: String,
        images: List<ImageItem>,
        ratingFilter: RatingFilter = RatingFilter.All,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): JumpPreview {
        val resolution = resolve(input, images, ratingFilter, zone, today)
        val target = (resolution as? JumpResolution.Resolved)?.let { images.getOrNull(it.index) }
            ?: return JumpPreview(resolution)
        return JumpPreview(resolution, target.fileName, takenAt(target, zone))
    }

    /** The local day [image] was taken on in [zone], or null when its time is unknown. */
    fun dayOf(image: ImageItem, zone: ZoneId): LocalDate? =
        if (image.lastModified > 0L) Instant.ofEpochMilli(image.lastModified).atZone(zone).toLocalDate() else null

    /** The local date and time of [image] in [zone], or null when its time is unknown. */
    fun takenAt(image: ImageItem, zone: ZoneId): LocalDateTime? =
        if (image.lastModified > 0L) Instant.ofEpochMilli(image.lastModified).atZone(zone).toLocalDateTime() else null

    private fun resolvePosition(text: String, images: List<ImageItem>, ratingFilter: RatingFilter): JumpResolution {
        // Too many digits for an Int is still just a number out of range.
        val position = text.toIntOrNull() ?: return JumpResolution.OutOfRange(images.size)
        if (position !in 1..images.size) return JumpResolution.OutOfRange(images.size)
        val index = position - 1
        return JumpResolution.Resolved(
            index = index,
            hiddenByFilter = !ratingFilter.matches(images[index].rating),
        )
    }

    /** A file-name match for the trimmed [text]: first in list order, a shown frame first. */
    private fun resolveName(text: String, images: List<ImageItem>, ratingFilter: RatingFilter): JumpResolution {
        val needle = text.lowercase()
        var firstHidden = -1
        images.forEachIndexed { index, image ->
            if (namesMatch(image, needle)) {
                if (ratingFilter.matches(image.rating)) return JumpResolution.Resolved(index)
                if (firstHidden < 0) firstHidden = index
            }
        }
        return if (firstHidden >= 0) {
            JumpResolution.Resolved(firstHidden, hiddenByFilter = true)
        } else {
            JumpResolution.NoNameMatch(text)
        }
    }

    private fun resolveDay(
        requested: LocalDate,
        images: List<ImageItem>,
        days: List<LocalDate?>,
        ratingFilter: RatingFilter,
    ): JumpResolution.Resolved? {
        val dated = days.filterNotNullTo(TreeSet())
        if (dated.isEmpty()) return null
        val landed = if (requested in dated) requested else nearest(requested, dated)
        var firstHidden = -1
        days.forEachIndexed { index, day ->
            if (day == landed) {
                if (ratingFilter.matches(images[index].rating)) {
                    return JumpResolution.Resolved(index, date = DateJump(requested, landed))
                }
                if (firstHidden < 0) firstHidden = index
            }
        }
        return JumpResolution.Resolved(firstHidden, hiddenByFilter = true, date = DateJump(requested, landed))
    }

    /** The day in [dated] nearest to [day]; equally near on both sides, the later one. */
    private fun nearest(day: LocalDate, dated: TreeSet<LocalDate>): LocalDate {
        val later = dated.ceiling(day)
        val earlier = dated.floor(day)
        if (later == null) return earlier!!
        if (earlier == null) return later
        val toLater = ChronoUnit.DAYS.between(day, later)
        val toEarlier = ChronoUnit.DAYS.between(earlier, day)
        return if (toLater <= toEarlier) later else earlier
    }

    /**
     * The year for a day and month typed without one: the current year if it has photos
     * on that day, else the most recent year that does, else the current year (29 Feb:
     * the latest leap year up to it).
     */
    private fun yearFor(monthDay: MonthDay, days: List<LocalDate?>, today: LocalDate): Int {
        val years = days.asSequence()
            .filterNotNull()
            .filter { MonthDay.from(it) == monthDay }
            .map { it.year }
            .toSet()
        if (today.year in years) return today.year
        years.maxOrNull()?.let { return it }
        var year = today.year
        while (!monthDay.isValidYear(year)) year--
        return year
    }

    private fun namesMatch(image: ImageItem, needle: String): Boolean =
        needle in image.fileName.lowercase() ||
            image.companions.any { needle in it.fileName.lowercase() }
}
