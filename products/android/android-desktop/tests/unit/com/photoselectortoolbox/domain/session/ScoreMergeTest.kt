package com.photoselectortoolbox.domain.session

import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The merge rule that keeps a background pass from rolling the screen back.
 *
 * Pure, so none of it needs a dispatcher — which matters, because the version
 * this replaces was wrong for two years and no test caught it: the only way to
 * reach it was through a `withContext(Dispatchers.IO)` hop that escapes virtual
 * time, so every attempt to test it through the ViewModel was a race.
 */
class ScoreMergeTest {

    private fun image(
        name: String,
        exif: ExifData? = null,
        score: ScanResult? = null,
        width: Int = 0,
    ) = ImageItem(
        uri = "content://test/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = width,
        exifData = exif,
        scanResult = score,
    )

    private fun score(sharpness: Double, name: String = "a") =
        ScanResult(filePath = "content://test/$name", sharpnessScore = sharpness)

    @Test
    fun `a score is written onto the image that lacks one`() {
        val merged = ScoreMerge.apply(
            listOf(image("a")),
            mapOf("content://test/a" to score(61.9)),
        )

        assertEquals(61.9, merged.single().scanResult?.sharpnessScore!!, 0.01)
    }

    @Test
    fun `EXIF that arrived while the scores were computed survives the merge`() {
        // The regression. EXIF is loaded on its own coroutine and only re-fetched
        // on navigation, so an item silently reverted here stays blank for as
        // long as the photographer looks at it.
        val exif = ExifData(iso = 400, aperture = 2.8)
        val live = image("a", exif = exif)

        val merged = ScoreMerge.apply(
            listOf(live),
            mapOf("content://test/a" to score(61.9)),
        )

        assertEquals(exif, merged.single().exifData)
        assertEquals(61.9, merged.single().scanResult?.sharpnessScore!!, 0.01)
    }

    @Test
    fun `dimensions resolved while the scores were computed survive too`() {
        val merged = ScoreMerge.apply(
            listOf(image("a", width = 6000)),
            mapOf("content://test/a" to score(61.9)),
        )

        assertEquals(6000, merged.single().imageWidth)
    }

    @Test
    fun `an image that already has a score is left exactly as it is`() {
        // A cache restore and a scan can both land on one photograph. The score
        // already on screen is the one that has been seen.
        val existing = image("a", score = score(90.0))

        val merged = ScoreMerge.apply(
            listOf(existing),
            mapOf("content://test/a" to score(10.0)),
        )

        assertSame(existing, merged.single())
    }

    @Test
    fun `a measurement the shown score lacks is filled in and the others stay`() {
        // The first scan had noise unticked; the second measured it (REQ-AND-ALGO.07).
        val existing = image("a", score = score(90.0))

        val merged = ScoreMerge.apply(
            listOf(existing),
            mapOf("content://test/a" to ScanResult("content://test/a", sharpnessScore = 10.0, noiseLevel = 4.0)),
        )

        assertEquals(90.0, merged.single().scanResult?.sharpnessScore)
        assertEquals(4.0, merged.single().scanResult?.noiseLevel)
    }

    @Test
    fun `images the pass knew nothing about are untouched`() {
        // Batches append while a scan runs, and frames get filed away.
        val merged = ScoreMerge.apply(
            listOf(image("a"), image("b")),
            mapOf("content://test/a" to score(61.9)),
        )

        assertEquals(61.9, merged[0].scanResult?.sharpnessScore!!, 0.01)
        assertNull(merged[1].scanResult)
    }

    @Test
    fun `an empty result set returns the list itself rather than a copy`() {
        val images = listOf(image("a"))
        assertSame(images, ScoreMerge.apply(images, emptyMap()))
    }

    @Test
    fun `scoresOf takes only the scores out of a snapshot`() {
        val snapshot = listOf(
            image("a", score = score(61.9)),
            image("b", exif = ExifData(iso = 100)),
        )

        val scores = ScoreMerge.scoresOf(snapshot)

        assertEquals(setOf("content://test/a"), scores.keys)
        assertEquals(61.9, scores.getValue("content://test/a").sharpnessScore!!, 0.01)
    }
}
