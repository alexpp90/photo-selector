package com.photoselectortoolbox.domain.usecase

import android.graphics.Bitmap
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.domain.analysis.CompositionAnalyzer
import com.photoselectortoolbox.domain.model.CompositionResult
import com.photoselectortoolbox.domain.model.DetectedLine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Learn's on-demand composition analysis (REQ-AND-COMPOSITION.01, finding B-AD-4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AnalyzeCompositionUseCaseTest {

    private val image = ImageItem("content://test/folder/IMG_1.JPG", "IMG_1.JPG", 4000L, 77L, "image/jpeg")
    private val result = CompositionResult(
        leadingLines = listOf(DetectedLine(0.1f, 0.9f, 0.6f, 0.4f, 0.8f)),
        massCentroidX = 0.62f,
        massCentroidY = 0.41f,
        gridMassDistribution = List(9) { 1f / 9 },
    )

    private val bitmap = mockk<Bitmap>(relaxed = true)
    private val decoder = mockk<PhotoBitmapDecoder> { every { decodeForAnalysis(any(), any()) } returns bitmap }
    private val analyzer = mockk<CompositionAnalyzer> { coEvery { analyze(any()) } returns result }
    private val scoreDao = mockk<ScoreDao>(relaxed = true) { coEvery { getScore(any()) } returns null }

    private fun useCase() = AnalyzeCompositionUseCase(decoder, analyzer, scoreDao, UnconfinedTestDispatcher())

    @Test
    fun `a photo without a stored result is decoded, analysed, stored and its bitmap released`() = runTest {
        assertEquals(result, useCase()(image))

        verify(exactly = 1) { decoder.decodeForAnalysis(any(), "IMG_1.JPG") }
        coVerify(exactly = 1) { analyzer.analyze(bitmap) }
        coVerify(exactly = 1) { scoreDao.updateCompositionAnalysis(image.uri, result.toJson()) }
        verify(exactly = 1) { bitmap.recycle() }
    }

    @Test
    fun `a stored result for the same file is used without decoding`() = runTest {
        coEvery { scoreDao.getScore(image.uri) } returns
            ScoreEntity(image.uri, fileSize = 4000L, lastModified = 77L, compositionAnalysis = result.toJson())

        assertEquals(result.toJson(), useCase()(image)?.toJson())

        verify(exactly = 0) { decoder.decodeForAnalysis(any(), any()) }
    }

    @Test
    fun `a stored result for another file at the same URI is analysed again`() = runTest {
        coEvery { scoreDao.getScore(image.uri) } returns
            ScoreEntity(image.uri, fileSize = 9999L, lastModified = 77L, compositionAnalysis = "{}")

        assertEquals(result, useCase()(image))
        coVerify(exactly = 1) { analyzer.analyze(bitmap) }
    }

    @Test
    fun `a photo that cannot be decoded or analysed has no composition, and the bitmap is still released`() = runTest {
        every { decoder.decodeForAnalysis(any(), any()) } returns null
        assertNull(useCase()(image))
        coVerify(exactly = 0) { analyzer.analyze(any()) }

        every { decoder.decodeForAnalysis(any(), any()) } returns bitmap
        coEvery { analyzer.analyze(any()) } throws IllegalStateException("OpenCV")
        assertNull(useCase()(image))
        verify(exactly = 1) { bitmap.recycle() }
        coVerify(exactly = 0) { scoreDao.updateCompositionAnalysis(any(), any()) }
    }
}
