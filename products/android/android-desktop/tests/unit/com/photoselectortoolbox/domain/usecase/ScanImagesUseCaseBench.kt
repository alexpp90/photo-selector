package com.photoselectortoolbox.domain.usecase

import android.content.Context
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.domain.analysis.ClippingAnalyzer
import com.photoselectortoolbox.domain.analysis.NoiseAnalyzer
import com.photoselectortoolbox.domain.analysis.SharpnessAnalyzer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ScanImagesUseCaseBench {
    @Test
    fun benchmarkCacheChecking() = runBlocking {
        val sharpnessAnalyzer = mockk<SharpnessAnalyzer>()
        val noiseAnalyzer = mockk<NoiseAnalyzer>()
        val clippingAnalyzer = mockk<ClippingAnalyzer>()
        val scoreDao = mockk<ScoreDao>()
        val settingsRepository = mockk<SettingsRepository>()
        val context = mockk<Context>()

        every { settingsRepository.analysisThreadCount } returns flowOf(4)

        val useCase = ScanImagesUseCase(
            sharpnessAnalyzer, noiseAnalyzer, clippingAnalyzer,
            scoreDao, settingsRepository, PhotoBitmapDecoder(context)
        )

        val imageCount = 500
        val images = (1..imageCount).map {
            ImageItem(
                uri = "content://media/external/images/media/$it",
                fileName = "image_$it.jpg",
                fileSize = 1024L,
                lastModified = 1000L,
                mimeType = "image/jpeg"
            )
        }

        // Mock DB calls for ALL images to be cache hits
        coEvery { scoreDao.getScores(any()) } returns images.map { image -> ScoreEntity(
                filePath = image.uri,
                fileSize = image.fileSize,
                lastModified = image.lastModified,
                sharpnessScore = 50.0,
                noiseLevel = 10.0,
                highlightClipping = 5.0,
                shadowClipping = 5.0,
                aestheticScore = 8.0,
                lastAccessTime = 0L
            )}
        coEvery { scoreDao.updateAccessTimes(any(), any()) } returns Unit
        coEvery { scoreDao.getCount() } returns 0
        coEvery { scoreDao.deleteTechniqueFactsWithoutScore() } returns Unit
        images.forEach { image ->
            coEvery { scoreDao.getScore(image.uri) } returns ScoreEntity(
                filePath = image.uri,
                fileSize = image.fileSize,
                lastModified = image.lastModified,
                sharpnessScore = 50.0,
                noiseLevel = 10.0,
                highlightClipping = 5.0,
                shadowClipping = 5.0,
                aestheticScore = 8.0,
                lastAccessTime = 0L
            )
            coEvery { scoreDao.updateAccessTime(image.uri, any()) } returns Unit
        }

        val time = measureTimeMillis {
            useCase(images).toList()
        }
        println("Benchmark time for $imageCount cached images: $time ms")
    }
}
