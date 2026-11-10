package com.photoselectortoolbox.domain.usecase

import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.analysis.ClippingAnalyzer
import com.photoselectortoolbox.domain.analysis.NoiseAnalyzer
import com.photoselectortoolbox.domain.analysis.SharpnessAnalyzer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ScanImagesUseCaseTest {

    // REQ-AND-FEATURE.01, REQ-AND-CACHE.02
    @Test
    fun cacheHitSkipsDecodingAndPreservesCachedAestheticScore() = runBlocking {
        val sharpnessAnalyzer = mockk<SharpnessAnalyzer>()
        val noiseAnalyzer = mockk<NoiseAnalyzer>()
        val clippingAnalyzer = mockk<ClippingAnalyzer>()
        val scoreDao = mockk<ScoreDao>()
        val settingsRepository = mockk<SettingsRepository>()
        val bitmapDecoder = mockk<com.photoselectortoolbox.data.source.PhotoBitmapDecoder>()
        val mockBitmap = mockk<android.graphics.Bitmap>(relaxed = true)

        every { bitmapDecoder.decodeForAnalysis(any(), any()) } returns mockBitmap
        every { sharpnessAnalyzer.analyze(any(), any()) } returns 50.0
        every { noiseAnalyzer.analyze(any()) } returns 10.0
        every { clippingAnalyzer.analyzeClipping(any()) } returns Pair(0.0, 0.0)
        every { settingsRepository.analysisThreadCount } returns flowOf(2)

        val images = listOf(
            ImageItem("content://media/external/images/media/1", "img1.jpg", 100L, 1000L, "image/jpeg"),
            ImageItem("content://media/external/images/media/2", "img2.jpg", 200L, 2000L, "image/jpeg")
        )

        val cachedScores = listOf(
            ScoreEntity(
                filePath = "content://media/external/images/media/1",
                fileSize = 100L,
                lastModified = 1000L,
                sharpnessScore = 50.0,
                noiseLevel = 10.0,
                highlightClipping = 5.0,
                shadowClipping = 5.0,
                aestheticScore = 8.0
            ),
            ScoreEntity(
                filePath = "content://media/external/images/media/2",
                fileSize = 200L,
                lastModified = 2000L,
                sharpnessScore = 60.0,
                noiseLevel = 10.0,
                highlightClipping = 5.0,
                shadowClipping = 5.0,
                aestheticScore = null
            )
        )

        coEvery { scoreDao.getScores(listOf("content://media/external/images/media/1", "content://media/external/images/media/2")) } returns cachedScores
        coEvery { scoreDao.updateAccessTimes(any(), any()) } returns Unit
        coEvery { scoreDao.getCount() } returns 2
        coEvery { scoreDao.deleteTechniqueFactsWithoutScore() } returns Unit

        val useCase = ScanImagesUseCase(
            sharpnessAnalyzer, noiseAnalyzer, clippingAnalyzer,
            scoreDao, settingsRepository, bitmapDecoder, Dispatchers.IO, Dispatchers.Default,
        )

        val progress = useCase(images).toList().last()
        assertEquals(2, progress.results.size)
        assertEquals(8.0, progress.results["content://media/external/images/media/1"]?.aestheticScore)
        assertEquals(null, progress.results["content://media/external/images/media/2"]?.aestheticScore)
        verify(exactly = 0) { bitmapDecoder.decodeForAnalysis(any(), any()) }
    }

    // REQ-AND-CACHE.02
    @Test
    fun `a scan that leaves 10 001 cached rows prunes the cache to 10 000 and drops orphaned facts`() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.photoselectortoolbox.data.cache.ScoresDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.scoreDao()
        try {
            (1..10_001).forEach { i ->
                dao.insertOrUpdate(ScoreEntity(filePath = "content://old/$i", fileSize = 1, lastModified = 1, lastAccessTime = i.toLong()))
            }
            dao.upsertTechniqueFacts(listOf(
                com.photoselectortoolbox.data.cache.TechniqueFactEntity("content://old/1", "light.hour", "golden_hour", 0.9, null, 1, 1, 1),
            ))
            val settings = mockk<SettingsRepository> { every { analysisThreadCount } returns flowOf(1) }
            val useCase = ScanImagesUseCase(
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                dao, settings, mockk(relaxed = true), Dispatchers.IO, Dispatchers.Default,
            )

            useCase(emptyList()).toList()

            assertEquals(10_000, dao.getCount())
            assertEquals(null, dao.getScore("content://old/1"))
            assertEquals(0, dao.getTechniqueFactsForUri("content://old/1").size)
        } finally {
            db.close()
        }
    }

    // REQ-AND-CACHE.03, REQ-AND-CACHE.06
    @Test
    fun `scan with 2500 cached images chunks getScores and updateAccessTimes at 900`() = runBlocking {
        val sharpnessAnalyzer = mockk<SharpnessAnalyzer>(relaxed = true)
        val noiseAnalyzer = mockk<NoiseAnalyzer>(relaxed = true)
        val clippingAnalyzer = mockk<ClippingAnalyzer>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository> {
            every { analysisThreadCount } returns flowOf(4)
        }
        val bitmapDecoder = mockk<com.photoselectortoolbox.data.source.PhotoBitmapDecoder>(relaxed = true)

        val images = (1..2500).map { idx ->
            ImageItem("content://test/img_$idx.jpg", "img_$idx.jpg", 100L, 1000L, "image/jpeg")
        }
        val scoreDao = com.photoselectortoolbox.data.cache.RecordingScoreDao(
            scoreFor = { uri ->
                ScoreEntity(
                    filePath = uri,
                    fileSize = 100L,
                    lastModified = 1000L,
                    sharpnessScore = 80.0,
                    noiseLevel = 5.0,
                    highlightClipping = 0.0,
                    shadowClipping = 0.0,
                )
            },
        )

        val useCase = ScanImagesUseCase(
            sharpnessAnalyzer, noiseAnalyzer, clippingAnalyzer,
            scoreDao, settingsRepository, bitmapDecoder, Dispatchers.IO, Dispatchers.Default,
        )

        val progress = useCase(images).toList().last()
        assertEquals(2500, progress.results.size)
        assertEquals(listOf(900, 900, 700), scoreDao.scoreBatches.map { it.size })
        assertEquals(listOf(900, 900, 700), scoreDao.accessBatches.map { it.size })
    }

    // Invariant "cancellation is not an error": a cancelled analysis is not cached as a photo without a score.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancelled analysis is rethrown, never cached as an empty score`() = kotlinx.coroutines.test.runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val sharpnessAnalyzer = mockk<SharpnessAnalyzer>()
        val scoreDao = mockk<ScoreDao>(relaxed = true)
        val bitmapDecoder = mockk<com.photoselectortoolbox.data.source.PhotoBitmapDecoder>()
        every { bitmapDecoder.decodeForAnalysis(any(), any()) } returns mockk<android.graphics.Bitmap>(relaxed = true)
        every { sharpnessAnalyzer.analyze(any(), any()) } throws kotlinx.coroutines.CancellationException("scan cancelled")
        coEvery { scoreDao.getScores(any()) } returns emptyList()
        val settingsRepository = mockk<SettingsRepository> { every { analysisThreadCount } returns flowOf(1) }
        val useCase = ScanImagesUseCase(
            sharpnessAnalyzer, mockk<NoiseAnalyzer>(relaxed = true), mockk<ClippingAnalyzer>(relaxed = true),
            scoreDao, settingsRepository, bitmapDecoder, dispatcher, dispatcher,
        )

        val collecting = launch(dispatcher) {
            useCase(listOf(ImageItem("content://test/a.jpg", "a.jpg", 100L, 1000L, "image/jpeg"))).collect {}
        }
        advanceUntilIdle()
        collecting.cancel()

        io.mockk.coVerify(exactly = 0) { scoreDao.insertOrUpdate(any()) }
    }
}
