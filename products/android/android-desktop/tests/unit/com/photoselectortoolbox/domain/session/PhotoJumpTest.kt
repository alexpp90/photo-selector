package com.photoselectortoolbox.domain.session

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Go to photo": a position as the counter shows it, or part of a file name. */
@Requirement("REQ-AND-WORKFLOW.07")
class PhotoJumpTest {

    private fun image(name: String, rating: Int = 0, companions: List<String> = emptyList()) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        rating = rating,
        companions = companions.map { CompanionFile(uri = "content://photos/$it", fileName = it) },
    )

    // Newest first, as the counter numbers it: 1 = DSC_0125, 5 = DSC_0121.
    private val images = listOf(
        image("DSC_0125.JPG"),
        image("DSC_0124.JPG", rating = 4),
        image("DSC_0123.JPG", companions = listOf("DSC_0123.ARW")),
        image("DSC_0122.JPG", rating = 5),
        image("DSC_0121.JPG", rating = 4),
    )

    @Test
    fun `a number is the counter's position, 1-based`() {
        assertEquals(JumpResolution.Resolved(0), PhotoJump.resolve("1", images))
        assertEquals(JumpResolution.Resolved(4), PhotoJump.resolve("5", images))
        assertEquals(JumpResolution.Resolved(2), PhotoJump.resolve("  3 ", images))
        assertEquals(JumpResolution.Resolved(1), PhotoJump.resolve("002", images))
        assertEquals(JumpResolution.Resolved(1), PhotoJump.resolve("+2", images))
    }

    @Test
    fun `a number outside 1 to N is out of range with the total`() {
        assertEquals(JumpResolution.OutOfRange(5), PhotoJump.resolve("0", images))
        assertEquals(JumpResolution.OutOfRange(5), PhotoJump.resolve("6", images))
        assertEquals(JumpResolution.OutOfRange(5), PhotoJump.resolve("-1", images))
        assertEquals(JumpResolution.OutOfRange(5), PhotoJump.resolve("99999999999999999999", images))
        assertEquals(JumpResolution.OutOfRange(0), PhotoJump.resolve("1", emptyList()))
    }

    @Test
    fun `nothing typed is empty`() {
        assertEquals(JumpResolution.Empty, PhotoJump.resolve("", images))
        assertEquals(JumpResolution.Empty, PhotoJump.resolve("   ", images))
    }

    @Test
    fun `text matches a file name case-insensitively, first in list order`() {
        assertEquals(JumpResolution.Resolved(2), PhotoJump.resolve("DSC_0123", images))
        assertEquals(JumpResolution.Resolved(2), PhotoJump.resolve("dsc_0123.jpg", images))
        // Every name contains "DSC_": the first in list order wins.
        assertEquals(JumpResolution.Resolved(0), PhotoJump.resolve("dsc_", images))
    }

    @Test
    fun `text matches a companion's file name too`() {
        assertEquals(JumpResolution.Resolved(2), PhotoJump.resolve("0123.ARW", images))
    }

    @Test
    fun `text that names nothing reports the trimmed text`() {
        assertEquals(JumpResolution.NoNameMatch("IMG_9"), PhotoJump.resolve(" IMG_9 ", images))
        assertEquals(JumpResolution.NoNameMatch("a"), PhotoJump.resolve("a", emptyList()))
    }

    @Test
    fun `a rating filter does not renumber, it only flags a hidden frame`() {
        val fourPlus = RatingFilter.MinRating(4)
        // Position 2 is DSC_0124 (4 stars): shown.
        assertEquals(JumpResolution.Resolved(1), PhotoJump.resolve("2", images, fourPlus))
        // Position 1 is DSC_0125 (unrated): still position 1, but hidden.
        assertEquals(JumpResolution.Resolved(0, hiddenByFilter = true), PhotoJump.resolve("1", images, fourPlus))
        // The range is the whole list, not the three that pass the filter.
        assertEquals(JumpResolution.Resolved(4), PhotoJump.resolve("5", images, fourPlus))
        assertEquals(JumpResolution.OutOfRange(5), PhotoJump.resolve("6", images, fourPlus))
    }

    @Test
    fun `a name match prefers a frame the filter shows`() {
        val fourPlus = RatingFilter.MinRating(4)
        assertEquals(JumpResolution.Resolved(1), PhotoJump.resolve("dsc_", images, fourPlus))
        // Only a hidden frame matches: it is still found, flagged.
        assertEquals(
            JumpResolution.Resolved(2, hiddenByFilter = true),
            PhotoJump.resolve("_0123", images, fourPlus),
        )
    }

    @Test
    fun `digits alone are a position, never a name`() {
        // "0123" is position 123, out of range here, not DSC_0123: a name needs
        // at least one character that is not a digit.
        assertEquals(JumpResolution.OutOfRange(5), PhotoJump.resolve("0123", images))
    }
}
