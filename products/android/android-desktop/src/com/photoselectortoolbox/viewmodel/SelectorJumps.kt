package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.JumpPreview
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoDates
import com.photoselectortoolbox.domain.session.PhotoJump
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * What *Go to photo* resolves to (REQ-AND-WORKFLOW.07), over the list as it is ordered now.
 * Go-to is a Cull control and the rating filter is Learn's (REQ-AND-RATING.01), so every photo
 * of the folder is a target. "Today" comes from [clock] and photo times are read in [zone] —
 * the device's, read at each call so a zone change takes effect; the view model's test seams.
 */
internal class SelectorJumps(private val clock: () -> Clock, private val zone: () -> ZoneId) {

    private var datesCache: Triple<List<ImageItem>, ZoneId, PhotoDates>? = null

    private fun today(zone: ZoneId): LocalDate = LocalDate.now(clock().withZone(zone))

    /** A number, name or date typed into the dialog. */
    fun resolve(input: String, state: SelectorUiState): JumpResolution =
        zone().let { z -> PhotoJump.resolve(input, state.images, RatingFilter.All, z, today(z)) }

    /** The first photo of [date], else of the nearest day with photos; null when no photo has a time. */
    fun resolveDate(date: LocalDate, state: SelectorUiState): JumpResolution.Resolved? =
        PhotoJump.resolveDate(date, state.images, RatingFilter.All, zone())

    /** What [resolve] would do, with the target's name and time, for the dialog's supporting line. */
    fun preview(input: String, state: SelectorUiState): JumpPreview =
        zone().let { z -> PhotoJump.preview(input, state.images, RatingFilter.All, z, today(z)) }

    /** The days [images] has photos on, for the date picker; recomputed only when the list changes. */
    fun dates(images: List<ImageItem>): PhotoDates {
        val z = zone()
        datesCache?.let { (cachedImages, cachedZone, dates) -> if (cachedImages === images && cachedZone == z) return dates }
        return PhotoDates.of(images, z).also { datesCache = Triple(images, z, it) }
    }

    /** The picker's first month: the photo on screen's day, else the newest day with photos. */
    fun pickerStartDay(state: SelectorUiState): LocalDate? =
        state.currentImage?.let { PhotoJump.dayOf(it, zone()) } ?: dates(state.images).last
}
