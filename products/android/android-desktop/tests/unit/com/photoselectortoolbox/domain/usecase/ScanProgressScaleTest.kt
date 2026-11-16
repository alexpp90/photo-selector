package com.photoselectortoolbox.domain.usecase

import com.photoselectortoolbox.data.cache.RecordingScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.testing.ScaleConditions
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * A scan's progress at the folder sizes users have (testdata/conditions/conditions.json).
 *
 * Re-scanning a scanned folder of 10,000 photos is all cache hits: no decode, so the cost is what
 * the scan does per photo around the cache. Each progress emission used to carry a copy of every
 * result so far (`results.toMap()` per photo), which is quadratic in the folder.
 */
class ScanProgressScaleTest {

    private val settings = mockk<SettingsRepository> { every { analysisThreadCount } returns kotlinx.coroutines.flow.flowOf(4) }

    private fun images(n: Int) = (1..n).map { i ->
        ImageItem("content://scale/doc/IMG_$i.jpg", "IMG_$i.jpg", 1_000L + i, 1_000L, "image/jpeg")
    }

    /** A use case over [photos], every one with a valid cached row and current facts (hand-written fakes). */
    private fun useCaseFor(photos: List<ImageItem>): ScanImagesUseCase {
        val byUri = photos.associateBy { it.uri }
        val dao = RecordingScoreDao(
            scoreFor = { uri ->
                byUri[uri]?.let { ScoreEntity(uri, it.fileSize, it.lastModified, 80.0, 5.0, 0.1, 0.2) }
            },
            factsFor = { uri ->
                byUri[uri]?.let {
                    listOf(TechniqueFactEntity(uri, ScanFacts.MARKER, "1", 1.0, null, it.fileSize, it.lastModified, TechniqueFactDetector.DETECTOR_VERSION))
                }.orEmpty()
            },
        )
        return ScanImagesUseCase(
            mockk(), mockk(), mockk(), dao, settings, mockk(), Dispatchers.IO, Dispatchers.Default,
            ScanFactRecorder(TechniqueFactDetector(), { null }, dao, Dispatchers.Default) { ZoneOffset.UTC },
        )
    }

    /**
     * REQ-AND-PERF.02, REQ-AND-PERF.04, REQ-AND-PERF.10: the scan streams its progress in time
     * linear in the folder — scanning 4N cached photos takes about 4 times as long as N.
     *
     * Scale: pr
     */
    @Test
    fun `a re-scan of a cached folder streams its progress in linear time`() {
        ScaleConditions.assertGrowth("re-scan of a cached folder") { n ->
            val photos = images(n)
            val useCase = useCaseFor(photos);
            {
                runBlocking {
                    // What the selector does with each emission: fold the new results in.
                    val seen = HashMap<String, com.photoselectortoolbox.data.model.ScanResult>()
                    var processed = 0
                    useCase(photos).collect { progress -> seen.putAll(progress.scored); processed = progress.processed }
                    check(processed == n) { "scan reported $processed of $n processed" }
                    check(seen.size == n) { "scan delivered ${seen.size} of $n results" }
                }
            }
        }
    }

    // REQ-AND-PERF.04
    @Test
    fun `each result arrives in exactly one emission and the cache hits arrive together`() = runBlocking {
        val photos = images(30)
        val emissions = useCaseFor(photos)(photos).toList()

        val delivered = emissions.flatMap { it.scored.keys }
        org.junit.Assert.assertEquals("no result is sent twice", delivered.distinct(), delivered)
        org.junit.Assert.assertEquals(photos.map { it.uri }.toSet(), emissions.allResults().keys)
        // The opening emission, then one for all 30 cached photos.
        org.junit.Assert.assertEquals(listOf(0, 30), emissions.map { it.scored.size })
        org.junit.Assert.assertEquals(30, emissions.last().processed)
    }
}
