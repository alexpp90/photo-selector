package com.photoselectortoolbox.domain.session

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.model.RatingFilter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Go to photo" by date (`[USER 2026-11-04]` "It would also be convenient to be able to
 * jump to a date"): the day of the sort order's timestamp, in the device's zone.
 */
@Requirement("REQ-AND-WORKFLOW.07", "REQ-AND-WORKFLOW.06")
class PhotoJumpDateTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 11, 4)

    private fun at(dateTime: String, zone: ZoneId = berlin): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    private fun image(name: String, modified: Long, rating: Int = 0) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
        rating = rating,
    )

    /** A shoot over three days, 11, 12 and 14 Oct 2026, plus one with an unknown time. */
    private val shoot = listOf(
        image("A_1011_0900.JPG", at("2026-10-11T09:00")),
        image("B_1012_0800.JPG", at("2026-10-12T08:00")),
        image("C_1012_1800.JPG", at("2026-10-12T18:00"), rating = 4),
        image("D_1014_1000.JPG", at("2026-10-14T10:00")),
        image("E_unknown.JPG", 0L),
    )

    private fun newest() = SelectorSortOrder.NEWEST_FIRST.order(shoot)
    private fun oldest() = SelectorSortOrder.OLDEST_FIRST.order(shoot)

    private fun resolve(
        input: String,
        images: List<ImageItem> = newest(),
        filter: RatingFilter = RatingFilter.All,
        zone: ZoneId = berlin,
    ) = PhotoJump.resolve(input, images, filter, zone, today)

    private fun landedName(resolution: JumpResolution, images: List<ImageItem>): String? =
        (resolution as? JumpResolution.Resolved)?.let { images[it.index].fileName }

    // ── Input forms ─────────────────────────────────────────────────────

    @Test
    fun `ISO and dotted dates are days`() {
        val day = JumpQuery.Day(LocalDate.of(2026, 10, 12))
        assertEquals(day, JumpQuery.parse("2026-10-12", today))
        assertEquals(day, JumpQuery.parse("12.10.2026", today))
        assertEquals(day, JumpQuery.parse("12.10.26", today))
        assertEquals(day, JumpQuery.parse(" 12.10.2026 ", today))
        assertEquals(JumpQuery.Day(LocalDate.of(2026, 3, 2)), JumpQuery.parse("2.3.2026", today))
        assertEquals(JumpQuery.Day(LocalDate.of(2026, 3, 2)), JumpQuery.parse("2026-3-2", today))
    }

    @Test
    fun `day and month without a year are a day of any year`() {
        assertEquals(JumpQuery.DayOfAnyYear(MonthDay.of(10, 12)), JumpQuery.parse("12.10.", today))
        assertEquals(JumpQuery.DayOfAnyYear(MonthDay.of(2, 29)), JumpQuery.parse("29.02.", today))
    }

    @Test
    fun `a two-digit year is this century unless that is in the future`() {
        assertEquals(JumpQuery.Day(LocalDate.of(2026, 10, 12)), JumpQuery.parse("12.10.26", today))
        assertEquals(JumpQuery.Day(LocalDate.of(2005, 1, 1)), JumpQuery.parse("1.1.05", today))
        assertEquals(JumpQuery.Day(LocalDate.of(1998, 10, 12)), JumpQuery.parse("12.10.98", today))
        assertEquals(JumpQuery.Day(LocalDate.of(1927, 10, 12)), JumpQuery.parse("12.10.27", today))
    }

    @Test
    fun `impossible dates are not dates`() {
        listOf("31.02.2026", "29.02.2026", "00.10.2026", "12.13.2026", "2026-02-30", "31.04.", "32.01.", "12.00.").forEach {
            assertEquals(it, JumpQuery.NotADate(it), JumpQuery.parse(it, today))
        }
        // 29 Feb exists in a leap year.
        assertEquals(JumpQuery.Day(LocalDate.of(2028, 2, 29)), JumpQuery.parse("29.02.2028", today))
    }

    @Test
    fun `digits alone stay a position and other text stays a name`() {
        assertEquals(JumpQuery.Position("12"), JumpQuery.parse("12", today))
        assertEquals(JumpQuery.Position("20261012"), JumpQuery.parse("20261012", today))
        assertEquals(JumpQuery.Empty, JumpQuery.parse("  ", today))
        listOf("12.10", "12.10.2", "12.10.202", "2026-10", "DSC_0123", "12/10/2026", "2026-10-12x").forEach {
            assertEquals(it, JumpQuery.Name(it), JumpQuery.parse(it, today))
        }
    }

    // ── Landing ─────────────────────────────────────────────────────────

    @Test
    fun `a date lands on the first photo of that day in newest-first order`() {
        val images = newest()
        val resolution = resolve("12.10.2026", images)
        assertEquals("C_1012_1800.JPG", landedName(resolution, images))
        assertEquals(DateJump(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12)), (resolution as JumpResolution.Resolved).date)
        assertTrue(resolution.date!!.isExact)
    }

    @Test
    fun `a date lands on the first photo of that day in oldest-first order`() {
        val images = oldest()
        assertEquals("B_1012_0800.JPG", landedName(resolve("2026-10-12", images), images))
    }

    @Test
    fun `a date lands on the first photo of that day in file-name order`() {
        val images = SelectorSortOrder.FILE_NAME.order(shoot)
        assertEquals("B_1012_0800.JPG", landedName(resolve("12.10.26", images), images))
    }

    @Test
    fun `the date is the local day in the given zone`() {
        // 23:30 UTC on 11 Oct is 01:30 on 12 Oct in Berlin, but 11 Oct in UTC.
        val late = listOf(
            image("LATE.JPG", at("2026-10-11T23:30", ZoneOffset.UTC)),
            image("NEXT.JPG", at("2026-10-13T12:00", ZoneOffset.UTC)),
        )
        val inBerlin = resolve("12.10.2026", late, zone = berlin) as JumpResolution.Resolved
        assertEquals("LATE.JPG", late[inBerlin.index].fileName)
        assertTrue(inBerlin.date!!.isExact)
        val inUtc = resolve("11.10.2026", late, zone = ZoneOffset.UTC) as JumpResolution.Resolved
        assertEquals("LATE.JPG", late[inUtc.index].fileName)
        assertTrue(inUtc.date!!.isExact)
    }

    @Test
    fun `a day without photos lands on the nearest day that has one, and says so`() {
        val images = newest()
        // 10 Oct: nearest is 11 Oct (one day later; nothing earlier).
        val before = resolve("10.10.2026", images) as JumpResolution.Resolved
        assertEquals("A_1011_0900.JPG", images[before.index].fileName)
        assertEquals(DateJump(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)), before.date)
        assertFalse(before.date!!.isExact)
        // 20 Oct: nearest is 14 Oct (nothing later).
        val after = resolve("20.10.2026", images) as JumpResolution.Resolved
        assertEquals(LocalDate.of(2026, 10, 14), after.date!!.landed)
    }

    @Test
    fun `equally near on both sides, the later day wins, in either order`() {
        // 13 Oct: 12 Oct and 14 Oct are both one day away.
        listOf(newest(), oldest()).forEach { images ->
            val tie = resolve("13.10.2026", images) as JumpResolution.Resolved
            assertEquals(LocalDate.of(2026, 10, 14), tie.date!!.landed)
            assertEquals("D_1014_1000.JPG", images[tie.index].fileName)
        }
    }

    @Test
    fun `a nearer earlier day beats a later one`() {
        // 12 Oct is one day before 13 Oct, 15 Oct two days after.
        val images = SelectorSortOrder.NEWEST_FIRST.order(
            shoot.filterNot { it.fileName.startsWith("D_") } + image("F_1015.JPG", at("2026-10-15T10:00")),
        )
        val resolution = resolve("2026-10-13", images) as JumpResolution.Resolved
        assertEquals(LocalDate.of(2026, 10, 12), resolution.date!!.landed)
        assertEquals("C_1012_1800.JPG", images[resolution.index].fileName)
    }

    @Test
    fun `photos with an unknown time are never date targets`() {
        val images = newest()
        val resolution = resolve("1.1.1970", images) as JumpResolution.Resolved
        assertEquals("A_1011_0900.JPG", images[resolution.index].fileName)
        assertEquals(3, PhotoDates.of(images, berlin).dates.size)
    }

    @Test
    fun `with no photo of known time a date is looked up as a file name`() {
        val undated = listOf(image("2026-10-12_trip.JPG", 0L), image("other.JPG", -1L))
        assertEquals(JumpResolution.Resolved(0), resolve("2026-10-12", undated))
        assertEquals(JumpResolution.NoNameMatch("12.10.2026"), resolve("12.10.2026", undated))
        assertEquals(JumpResolution.NoNameMatch("12.10."), resolve("12.10.", undated))
        assertEquals(JumpResolution.NoNameMatch("1.1.26"), resolve("1.1.26", emptyList()))
    }

    @Test
    fun `an impossible date is matched as a file name`() {
        val named = newest() + image("31.02.2026 scan.JPG", at("2026-01-01T10:00"))
        assertEquals("31.02.2026 scan.JPG", landedName(resolve("31.02.2026", named), named))
        assertEquals(JumpResolution.NoNameMatch("31.02.2026"), resolve("31.02.2026"))
    }

    @Test
    fun `the rating filter is preferred within the day, and a hidden frame is still found`() {
        val images = newest()
        val fourPlus = RatingFilter.MinRating(4)
        // 12 Oct newest first is C (4 stars) then B: C passes the filter.
        assertEquals(
            JumpResolution.Resolved(images.indexOfFirst { it.fileName.startsWith("C_") }, date = DateJump(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12))),
            resolve("12.10.2026", images, fourPlus),
        )
        // In oldest-first order B comes first but is hidden: C, later that day, is preferred.
        val oldestFirst = oldest()
        assertEquals("C_1012_1800.JPG", landedName(resolve("12.10.2026", oldestFirst, fourPlus), oldestFirst))
        // 14 Oct has only an unrated photo: it is found, flagged hidden.
        val hidden = resolve("14.10.2026", images, fourPlus) as JumpResolution.Resolved
        assertTrue(hidden.hiddenByFilter)
        assertEquals("D_1014_1000.JPG", images[hidden.index].fileName)
    }

    @Test
    fun `the filter does not change which day is nearest`() {
        // 13 Oct ties to 14 Oct (later), even though only 12 Oct has a 4-star photo.
        val tie = resolve("13.10.2026", newest(), RatingFilter.MinRating(4)) as JumpResolution.Resolved
        assertEquals(LocalDate.of(2026, 10, 14), tie.date!!.landed)
        assertTrue(tie.hiddenByFilter)
    }

    // ── Day and month without a year ───────────────────────────────────

    @Test
    fun `day and month take the current year when it has photos that day`() {
        val years = listOf(
            image("Y2024.JPG", at("2024-10-12T10:00")),
            image("Y2026.JPG", at("2026-10-12T10:00")),
        )
        val resolution = resolve("12.10.", years) as JumpResolution.Resolved
        assertEquals("Y2026.JPG", years[resolution.index].fileName)
        assertEquals(LocalDate.of(2026, 10, 12), resolution.date!!.requested)
    }

    @Test
    fun `day and month take the most recent year with photos that day otherwise`() {
        val years = listOf(
            image("Y2022.JPG", at("2022-10-12T10:00")),
            image("Y2024.JPG", at("2024-10-12T10:00")),
            image("Y2026_other_day.JPG", at("2026-10-20T10:00")),
        )
        val resolution = resolve("12.10.", years) as JumpResolution.Resolved
        assertEquals("Y2024.JPG", years[resolution.index].fileName)
        assertTrue(resolution.date!!.isExact)
    }

    @Test
    fun `day and month with no year having photos that day take the current year and the nearest day`() {
        val resolution = resolve("13.10.") as JumpResolution.Resolved
        assertEquals(LocalDate.of(2026, 10, 13), resolution.date!!.requested)
        assertEquals(LocalDate.of(2026, 10, 14), resolution.date!!.landed)
    }

    @Test
    fun `29 February without a year falls back to the latest leap year`() {
        val resolution = resolve("29.02.") as JumpResolution.Resolved
        assertEquals(LocalDate.of(2024, 2, 29), resolution.date!!.requested)
        assertEquals(LocalDate.of(2026, 10, 11), resolution.date!!.landed)
    }

    // ── The picker entry, the dates and the preview ────────────────────

    @Test
    fun `the picker entry lands like a typed date`() {
        val images = newest()
        assertEquals(resolve("13.10.2026", images), PhotoJump.resolveDate(LocalDate.of(2026, 10, 13), images, zone = berlin))
        assertNull(PhotoJump.resolveDate(LocalDate.of(2026, 10, 13), listOf(image("x.JPG", 0L)), zone = berlin))
    }

    @Test
    fun `the dates with photos are ascending, distinct and known-time only`() {
        val dates = PhotoDates.of(newest(), berlin)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)),
            dates.dates,
        )
        assertEquals(LocalDate.of(2026, 10, 11), dates.first)
        assertEquals(LocalDate.of(2026, 10, 14), dates.last)
        assertTrue(LocalDate.of(2026, 10, 12) in dates)
        assertFalse(LocalDate.of(2026, 10, 13) in dates)
        assertTrue(dates.isSelectableYear(2026))
        assertFalse(dates.isSelectableYear(2025))
        assertTrue(PhotoDates.EMPTY.isEmpty)
        assertNull(PhotoDates.EMPTY.first)
    }

    @Test
    fun `the picker's UTC-midnight millis map to the same day whatever the zone`() {
        val dates = PhotoDates.of(newest(), berlin)
        val millis = PhotoDates.pickerMillisOf(LocalDate.of(2026, 10, 12))
        assertEquals(LocalDate.of(2026, 10, 12), PhotoDates.dateOfPickerMillis(millis))
        assertTrue(dates.isSelectableUtcMillis(millis))
        assertFalse(dates.isSelectableUtcMillis(PhotoDates.pickerMillisOf(LocalDate.of(2026, 10, 13))))
    }

    @Test
    fun `the preview names the target file and when it was taken`() {
        val images = newest()
        val preview = PhotoJump.preview("12.10.2026", images, zone = berlin, today = today)
        assertEquals("C_1012_1800.JPG", preview.fileName)
        assertEquals(LocalDateTime.of(2026, 10, 12, 18, 0), preview.takenAt)
        assertEquals("C_1012_1800.JPG · 12 Oct 2026, 18:00", SelectorLabels.goToPhotoPreview(preview.fileName!!, preview.takenAt))
        // A photo without a known time previews by name alone.
        val unknown = PhotoJump.preview("E_unknown", images, zone = berlin, today = today)
        assertNull(unknown.takenAt)
        assertEquals("E_unknown.JPG", SelectorLabels.goToPhotoPreview(unknown.fileName!!, unknown.takenAt))
        // Nothing to land on: no file.
        assertNull(PhotoJump.preview("nothing", images, zone = berlin, today = today).fileName)
    }

    @Test
    fun `the nearest-day message names both days`() {
        assertEquals(
            "No photos on 13 Oct 2026 — showing 14 Oct 2026",
            SelectorLabels.goToPhotoNearestDay(DateJump(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14))),
        )
        assertNull(SelectorLabels.goToPhotoNearestDay(DateJump(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 14))))
    }

    @Test
    fun `number and name jumps carry no date`() {
        assertNull((resolve("1") as JumpResolution.Resolved).date)
        assertNull((resolve("B_1012") as JumpResolution.Resolved).date)
    }
}
