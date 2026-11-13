package com.photoselectortoolbox.domain.usecase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.cache.ScoresDatabase
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.domain.analysis.ClippingAnalyzer
import com.photoselectortoolbox.domain.analysis.NoiseAnalyzer
import com.photoselectortoolbox.domain.analysis.SharpnessAnalyzer
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scan gives every photo it analyses Learn's facts and keeps them current ([USER 2026-11-12]
 * decision #19, option C; shoot debrief SD-1a, findings S12 and S13), against a real Room database:
 * - a photo the cull screen never showed (no EXIF loaded) gets its EXIF-based facts: the scan
 *   reads the EXIF itself;
 * - only the debrief's smaller set is stored, at the current detector version, with a marker;
 * - a photo with a cached score and stale facts is decoded again for its facts alone, and leaves
 *   no row of the old version; one with current facts is not decoded again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ScanStoresFactsTest {

    private lateinit var db: ScoresDatabase
    private lateinit var dao: ScoreDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ScoresDatabase::class.java).allowMainThreadQueries().build()
        dao = db.scoreDao()
    }

    @After
    fun tearDown() = db.close()

    /** A dusk telephoto frame in Berlin, the camera writing its own offset. */
    private val exif = ExifData(
        shutterSpeed = 1.0 / 2000.0,
        focalLength = 200.0,
        focalLength35mm = 300.0,
        iso = 400,
        make = "Nikon",
        model = "Z 50",
        gpsLatitude = 52.52,
        gpsLongitude = 13.40,
        dateTimeOriginal = "2026:06:21 21:25:00",
        offsetTimeOriginal = "+02:00",
    )

    private val photo = ImageItem("content://test/dusk.jpg", "dusk.jpg", 4_000_000L, 1_000L, "image/jpeg")

    private inner class Rig(exifFor: (String) -> ExifData? = { exif }) {
        val exifReads = AtomicInteger()
        val sharpness = mockk<SharpnessAnalyzer> { every { analyze(any(), any()) } returns 42.0 }
        val decoder = mockk<PhotoBitmapDecoder> {
            every { decodeForAnalysis(any(), any()) } answers {
                // A frame with a bright sky over darker ground, so the image detectors have data.
                mockk<android.graphics.Bitmap>(relaxed = true) {
                    every { width } returns 640
                    every { height } returns 480
                    every { getPixel(any(), any()) } answers {
                        val v = if (secondArg<Int>() < 200) 0xD0 else 0x30
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
            }
        }
        val useCase = ScanImagesUseCase(
            sharpness,
            mockk<NoiseAnalyzer> { every { analyze(any()) } returns 3.0 },
            mockk<ClippingAnalyzer> { every { analyzeClipping(any()) } returns Pair(0.5, 0.5) },
            dao,
            mockk<SettingsRepository> { every { analysisThreadCount } returns flowOf(2) },
            decoder,
            Dispatchers.IO,
            Dispatchers.Default,
            ScanFactRecorder(
                TechniqueFactDetector(),
                { uri -> exifReads.incrementAndGet(); exifFor(uri) },
                dao,
                Dispatchers.Default,
            ) { ZoneOffset.UTC },
        )
    }

    private suspend fun factsOf(uri: String) = dao.getTechniqueFacts(listOf(uri)).associateBy { it.kind }

    @Test
    fun `a photo whose EXIF was never loaded gets its EXIF-based facts from the scan`() = runBlocking {
        val rig = Rig()

        rig.useCase(listOf(photo)).toList()

        val facts = factsOf(photo.uri)
        assertEquals(1, rig.exifReads.get())
        assertEquals("1.50", facts.getValue("optics.crop_factor").value)
        assertEquals("300.0", facts.getValue("optics.effective_focal_length").value)
        assertEquals("action_freeze", facts.getValue("intent.shutter").value)
        assertTrue(facts.getValue("light.hour").value in setOf("golden_hour", "blue_hour", "day", "night"))
        // The class is kept; the solar elevation, which with the time narrows down the place, is not.
        assertNull(facts.getValue("light.hour").geometryJson)
        val takenAt = OffsetDateTime.of(2026, 6, 21, 21, 25, 0, 0, ZoneOffset.ofHours(2)).toInstant().toEpochMilli()
        assertEquals(takenAt.toString(), facts.getValue(ScanFacts.CAPTURE_TIME).value)
        assertTrue(ScanFacts.MARKER in facts)
        // Only the debrief's set, all of the current version, for this file.
        assertTrue("stored kinds ${facts.keys}", facts.keys.all { it in ScanFacts.ALL_KINDS })
        assertTrue(facts.values.all { it.detectorVersion == TechniqueFactDetector.DETECTOR_VERSION })
        assertTrue(facts.values.all { it.fileSize == photo.fileSize && it.lastModified == photo.lastModified })
    }

    @Test
    fun `a photo whose EXIF is loaded is not read again, and one without EXIF still gets a marker`() = runBlocking {
        val shown = photo.copy(exifData = exif)
        val bare = ImageItem("content://test/scan.png", "scan.png", 10L, 2L, "image/png")
        val rig = Rig(exifFor = { null })

        rig.useCase(listOf(shown, bare)).toList()

        assertEquals(1, rig.exifReads.get()) // only for the bare photo
        assertTrue(ScanFacts.CAPTURE_TIME in factsOf(shown.uri))
        val bareFacts = factsOf(bare.uri)
        assertTrue(ScanFacts.MARKER in bareFacts)
        assertTrue(ScanFacts.CAPTURE_TIME !in bareFacts)
    }

    @Test
    fun `a cached score with facts of version 1 is analysed again and leaves no version-1 row`() = runBlocking {
        dao.insertOrUpdate(
            ScoreEntity(
                filePath = photo.uri, fileSize = photo.fileSize, lastModified = photo.lastModified,
                sharpnessScore = 77.0, noiseLevel = 2.0, highlightClipping = 0.1, shadowClipping = 0.2,
            ),
        )
        dao.upsertTechniqueFacts(
            listOf(
                // What the old scan wrote: no EXIF, and kinds the new set does not keep.
                TechniqueFactEntity(photo.uri, "composition.symmetry", "vertical", 0.8, null, photo.fileSize, photo.lastModified, 1),
                TechniqueFactEntity(photo.uri, "exposure.clipping", "well_exposed", 0.88, null, photo.fileSize, photo.lastModified, 1),
            ),
        )
        val rig = Rig()

        val result = rig.useCase(listOf(photo)).toList().last().results.getValue(photo.uri)

        verify(exactly = 1) { rig.decoder.decodeForAnalysis(any(), any()) }
        // The cached scores are kept, not computed again.
        verify(exactly = 0) { rig.sharpness.analyze(any(), any()) }
        assertEquals(77.0, result.sharpnessScore)
        val rows = dao.getTechniqueFacts(listOf(photo.uri))
        assertTrue(rows.none { it.detectorVersion == 1 })
        assertTrue(rows.none { it.kind == "composition.symmetry" })
        assertTrue(rows.any { it.kind == "optics.crop_factor" })
    }

    @Test
    fun `a cached score with current facts is not decoded again`() = runBlocking {
        val rig = Rig()

        rig.useCase(listOf(photo)).toList()
        rig.useCase(listOf(photo)).toList()

        verify(exactly = 1) { rig.decoder.decodeForAnalysis(any(), any()) }
        assertEquals(1, rig.exifReads.get())
    }

    @Test
    fun `a photo whose file changed gets its facts anew`() = runBlocking {
        val rig = Rig()
        rig.useCase(listOf(photo)).toList()

        val edited = photo.copy(fileSize = photo.fileSize + 1, lastModified = 5_000L)
        rig.useCase(listOf(edited)).toList()

        verify(exactly = 2) { rig.decoder.decodeForAnalysis(any(), any()) }
        assertTrue(dao.getTechniqueFacts(listOf(photo.uri)).all { it.fileSize == edited.fileSize && it.lastModified == 5_000L })
    }
}
