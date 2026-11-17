package com.photoselectortoolbox.domain.analysis

import android.app.Application
import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Config

/**
 * Robolectric shadow for OpenCV's [Mat] class to bypass loading native C++
 * JNI binaries (`libopencv_java5.so`) during host JVM unit testing.
 */
@Implements(Mat::class)
class ShadowMat {
    @Implementation
    fun __constructor__() {
    }

    @Implementation
    fun __constructor__(m: Mat, r: Rect) {
    }

    companion object {
        @Implementation
        @JvmStatic
        fun n_Mat(): Long = 1L

        // OpenCV 5: Mat(Mat, Rect) passes this to CleanableMat's constructor,
        // which rejects a 0 address before the shadowed constructor body runs.
        @Implementation
        @JvmStatic
        fun n_Mat(addr: Long, rowStart: Int, rowEnd: Int, colStart: Int, colEnd: Int): Long = 1L

        @Implementation
        @JvmStatic
        fun n_submat(addr: Long, rowStart: Int, rowEnd: Int, colStart: Int, colEnd: Int): Long = 1L

        @Implementation
        @JvmStatic
        fun n_release(addr: Long) {
        }

        @Implementation
        @JvmStatic
        fun n_delete(addr: Long) {
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [ShadowMat::class])
class SharpnessAnalyzerTest {

    private var cols = 0
    private var rows = 0
    private var channels = 4

    /** The grey bytes OpenCV hands back; filled into the analyzer's buffer by the mocked `get`. */
    private var gray: ByteArray = ByteArray(0)

    @Before
    fun setUp() {
        mockkStatic(OpenCVLoader::class)
        every { OpenCVLoader.initLocal() } returns true

        mockkStatic(Utils::class)
        mockkStatic(Imgproc::class)
        mockkConstructor(Mat::class)

        every { Utils.bitmapToMat(any(), any()) } returns Unit
        every { Imgproc.cvtColor(any(), any(), any()) } returns Unit
        every { anyConstructed<Mat>().cols() } answers { cols }
        every { anyConstructed<Mat>().rows() } answers { rows }
        every { anyConstructed<Mat>().channels() } answers { channels }
        every { anyConstructed<Mat>().release() } returns Unit
        every { anyConstructed<Mat>().copyTo(any()) } returns Unit
        every { anyConstructed<Mat>().get(0, 0, any<ByteArray>()) } answers {
            val target = thirdArg<ByteArray>()
            gray.copyInto(target, endIndex = minOf(gray.size, target.size))
            target.size
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun checker(width: Int, height: Int): ByteArray =
        ByteArray(width * height) { i -> if ((i % width / 8 + i / width / 8) % 2 == 0) 64 else -64 }

    @Test
    fun `a colour bitmap is turned grey and scored by the noise-floor maths`() {
        cols = 160; rows = 120; channels = 4
        gray = checker(cols, rows)

        val score = SharpnessAnalyzer().analyze(mockk<Bitmap>())

        assertEquals(NoiseFloorSharpness.score(gray, cols, rows), score, 1e-9)
        assertTrue(score > 0.0)
        verify { Imgproc.cvtColor(any(), any(), Imgproc.COLOR_RGBA2GRAY) }
        verify(exactly = 0) { Imgproc.Laplacian(any(), any(), any()) }
        verify(exactly = 2) { anyConstructed<Mat>().release() }
    }

    @Test
    fun `a single-channel bitmap is copied instead of converted`() {
        cols = 160; rows = 120; channels = 1
        gray = checker(cols, rows)

        val score = SharpnessAnalyzer().analyze(mockk<Bitmap>(), gridSize = 2)

        assertEquals(NoiseFloorSharpness.score(gray, cols, rows, gridSize = 2), score, 1e-9)
        verify { anyConstructed<Mat>().copyTo(any()) }
        verify(exactly = 0) { Imgproc.cvtColor(any(), any(), any()) }
    }

    @Test
    fun `an empty bitmap scores zero and still releases its mats`() {
        cols = 0; rows = 0

        assertEquals(0.0, SharpnessAnalyzer().analyze(mockk<Bitmap>()), 0.0)
        verify(exactly = 2) { anyConstructed<Mat>().release() }
    }

    @Test
    fun `an image too small for the grid scores zero`() {
        cols = 4; rows = 4; channels = 4
        gray = ByteArray(16) { 30 }

        assertEquals(0.0, SharpnessAnalyzer().analyze(mockk<Bitmap>(), gridSize = 8), 0.0)
    }
}
