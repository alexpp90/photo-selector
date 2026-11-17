package com.photoselectortoolbox.domain.analysis

import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import java.util.Random
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The noise floor of the sharpness score (`[USER 2026-11-16]` D6, agy FD-B-21) on synthetic grey
 * images at an analysis size the scan decodes: before it, the Laplacian variance of sensor noise
 * read as edge detail, and a grainy blank frame outscored a crisp clean one.
 */
@Requirement("REQ-AND-ALGO.01")
class NoiseFloorSharpnessTest {

    private val width = 1024
    private val height = 768

    private fun image(pixel: (x: Int, y: Int) -> Double): ByteArray = ByteArray(width * height) { i ->
        pixel(i % width, i / width).roundToInt().coerceIn(0, 255).toByte()
    }

    private fun noisyFlat(sigma: Double, seed: Long): ByteArray {
        val random = Random(seed)
        return image { _, _ -> 128 + sigma * random.nextGaussian() }
    }

    /** Hard edges between two grey levels [contrast] apart, 32 px squares, no noise. */
    private fun cleanChecker(contrast: Int): ByteArray =
        image { x, y -> if ((x / 32 + y / 32) % 2 == 0) 128.0 - contrast / 2 else 128.0 + contrast / 2 }

    private fun measure(luma: ByteArray) = NoiseFloorSharpness.measure(luma, width, height)

    @Test
    fun `a noisy flat frame no longer outscores a sharp clean one`() {
        val noisy = measure(noisyFlat(sigma = 8.0, seed = 1))
        val sharp = measure(cleanChecker(contrast = 56))

        // The defect: on the old maths (the raw block variance) the grain wins.
        assertTrue(
            "raw noisy ${noisy.rawVariance} vs raw sharp ${sharp.rawVariance}",
            noisy.rawVariance > sharp.rawVariance,
        )
        // With the floor the sharp frame wins, by far.
        assertTrue("noisy ${noisy.score} vs sharp ${sharp.score}", sharp.score > 10 * (noisy.score + 1))
    }

    @Test
    fun `noise alone reads as not sharp at every grain strength`() {
        for (sigma in listOf(1.0, 2.0, 4.0, 8.0, 16.0)) {
            for (seed in 1L..3L) {
                val noisy = measure(noisyFlat(sigma, seed))
                assertTrue(
                    "sigma $sigma seed $seed: ${noisy.score} (raw ${noisy.rawVariance}, floor ${noisy.noiseFloor})",
                    ScoreMetric.SHARPNESS.goodness(noisy.score) == 0.0,
                )
            }
        }
    }

    @Test
    fun `a clean frame keeps its figure`() {
        val sharp = measure(cleanChecker(contrast = 128))

        assertEquals(0.0, sharp.noiseFloor, 0.0)
        assertEquals(sharp.rawVariance, sharp.score, 1e-9)
        assertTrue(ScoreMetric.SHARPNESS.goodness(sharp.score) == 1.0)
    }

    @Test
    fun `edges under grain keep what they have above the floor`() {
        val random = Random(7)
        val checker = cleanChecker(contrast = 128)
        val grainy = ByteArray(checker.size) { i ->
            ((checker[i].toInt() and 0xFF) + 8 * random.nextGaussian()).roundToInt().coerceIn(0, 255).toByte()
        }
        val clean = measure(checker)
        val noisy = measure(grainy)
        val noiseOnly = measure(noisyFlat(sigma = 8.0, seed = 7))

        assertTrue(noisy.noiseFloor > 0.0)
        assertTrue("grainy ${noisy.score} vs noise only ${noiseOnly.score}", noisy.score > 100 * (noiseOnly.score + 1))
        // The floor takes the grain off, not the detail: within 30 % of the clean frame (the
        // edges lift the median a little, so the floor is a little above the grain alone).
        assertEquals(clean.score, noisy.score, clean.score * 0.3)
    }

    @Test
    fun `a blur scores below the same scene in focus`() {
        // A soft ramp between the squares instead of a step.
        val soft = image { x, y ->
            val ramp = (kotlin.math.sin(x * Math.PI / 32) * kotlin.math.sin(y * Math.PI / 32))
            128.0 + 28.0 * ramp
        }
        assertTrue(measure(soft).score < measure(cleanChecker(contrast = 56)).score)
    }

    @Test
    fun `an image too small for the grid scores zero`() {
        assertEquals(0.0, NoiseFloorSharpness.score(ByteArray(16) { 50 }, 4, 4), 0.0)
    }

    @Test
    fun `the robust sigma is the MAD of the counted values over 0_6745`() {
        fun sigmaOf(vararg values: Int): Double {
            val histogram = IntArray(2 * NoiseFloorSharpness.LAPLACIAN_MAX + 1)
            values.forEach { histogram[it + NoiseFloorSharpness.LAPLACIAN_MAX]++ }
            return NoiseFloorSharpness.robustSigma(histogram)
        }
        // Odd: median 0, deviations 2 0 0 1 5 → MAD 1.
        assertEquals(1.0 / 0.6745, sigmaOf(-2, 0, 0, 1, 5), 1e-12)
        // Even: median 2.5, deviations 1.5 0.5 0.5 1.5 → MAD 1.
        assertEquals(1.0 / 0.6745, sigmaOf(1, 2, 3, 4), 1e-12)
        // The extremes of the range count like any other value.
        assertEquals(1020.0 / 0.6745, sigmaOf(-1020, 1020), 1e-9)
        assertEquals(0.0, sigmaOf(), 0.0)
    }
}
