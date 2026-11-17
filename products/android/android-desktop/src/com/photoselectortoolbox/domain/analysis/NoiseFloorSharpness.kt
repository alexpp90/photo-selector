package com.photoselectortoolbox.domain.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The sharpness maths on a grey image, free of OpenCV so it runs on plain JVM tests
 * (REQ-AND-ALGO.01, `[USER 2026-11-16]` D6).
 *
 * Centre 50 % crop → [gridSize]×[gridSize] blocks → the variance of the 4-neighbour Laplacian in
 * each block, minus a **noise floor** → the largest. Without the floor, sensor noise read as edge
 * detail: the Laplacian of pure noise has a variance of 20 σ², so a grainy blank wall outscored a
 * crisp low-ISO frame (agy FD-B-21).
 *
 * The floor is the robust noise estimate of [NoiseAnalyzer]'s formula over the crop's Laplacian,
 * σ = median(|∇²I − median(∇²I)|) / 0.6745: the median ignores the few large responses at real
 * edges, so it measures the grain, not the detail. Its square is what noise alone adds to a
 * block's variance. Each block takes it off with a margin of [NOISE_MARGIN_SIGMAS] standard
 * errors of a noise-only variance over that block's pixel count, because the score keeps the
 * maximum of many blocks and the largest of 64 noise readings sits above their mean. A block of
 * noise alone therefore scores 0; edges keep what they have above the grain.
 *
 * The unit stays the Laplacian variance, so a clean frame keeps about its old figure and the
 * scale, the thresholds and the sort order read the same.
 */
object NoiseFloorSharpness {

    const val DEFAULT_GRID_SIZE = 8
    const val CENTER_CROP_RATIO = 0.5

    /** Consistency constant of the MAD as an estimate of σ under a normal distribution. */
    const val MAD_SCALE_FACTOR = 0.6745

    /** Standard errors of a noise-only block variance taken off with the floor. */
    const val NOISE_MARGIN_SIGMAS = 4.0

    /**
     * Var(sample variance) · n / σ⁴ of the 4-neighbour Laplacian of white Gaussian noise:
     * 2 · Σρ², where ρ is the kernel's autocorrelation (1; −0.4 at the 4 direct neighbours, 0.1 at
     * the 4 diagonals, 0.05 two steps away), so 2 · 1.69.
     */
    private const val NOISE_VARIANCE_SPREAD = 3.38

    /** The 4-neighbour Laplacian of 8-bit values lies in [-1020, 1020]. */
    internal const val LAPLACIAN_MAX = 4 * 255

    /**
     * The sharpness of a grey image of [width] × [height] bytes ([luma], row by row, unsigned):
     * the largest block's Laplacian variance above the noise floor, 0 when no block rises above
     * it or the image is too small for the grid.
     */
    fun score(luma: ByteArray, width: Int, height: Int, gridSize: Int = DEFAULT_GRID_SIZE): Double =
        measure(luma, width, height, gridSize).score

    /** The score and what it was made of, for tests and diagnostics. */
    data class Measurement(
        /** The largest block variance above the floor and its margin, never below 0. */
        val score: Double,
        /** The largest block variance with nothing taken off (the score before D6). */
        val rawVariance: Double,
        /** σ² of the Laplacian's noise, the floor taken off every block before its margin. */
        val noiseFloor: Double,
    )

    fun measure(luma: ByteArray, width: Int, height: Int, gridSize: Int = DEFAULT_GRID_SIZE): Measurement {
        require(gridSize > 0) { "gridSize must be positive" }
        require(luma.size >= width.toLong() * height) { "luma holds fewer than width × height bytes" }
        val cropX = (width * (1.0 - CENTER_CROP_RATIO) / 2.0).toInt()
        val cropY = (height * (1.0 - CENTER_CROP_RATIO) / 2.0).toInt()
        val cropWidth = (width * CENTER_CROP_RATIO).toInt()
        val cropHeight = (height * CENTER_CROP_RATIO).toInt()
        val blockWidth = cropWidth / gridSize
        val blockHeight = cropHeight / gridSize
        if (blockWidth <= 0 || blockHeight <= 0) return Measurement(0.0, 0.0, 0.0)

        val blocks = gridSize * gridSize
        val sums = LongArray(blocks)
        val squares = LongArray(blocks)
        val counts = IntArray(blocks)
        val histogram = IntArray(2 * LAPLACIAN_MAX + 1)

        for (row in 0 until cropHeight) {
            val y = cropY + row
            val up = clampIndex(y - 1, height) * width
            val here = y * width
            val down = clampIndex(y + 1, height) * width
            val blockRow = minOf(row / blockHeight, gridSize - 1) * gridSize
            for (column in 0 until cropWidth) {
                val x = cropX + column
                val left = clampIndex(x - 1, width)
                val right = clampIndex(x + 1, width)
                val laplacian = luma.at(up + x) + luma.at(down + x) + luma.at(here + left) +
                    luma.at(here + right) - 4 * luma.at(here + x)
                val block = blockRow + minOf(column / blockWidth, gridSize - 1)
                sums[block] += laplacian.toLong()
                squares[block] += laplacian.toLong() * laplacian
                counts[block]++
                histogram[laplacian + LAPLACIAN_MAX]++
            }
        }

        val sigma = robustSigma(histogram)
        val floor = sigma * sigma
        var raw = 0.0
        var best = 0.0
        for (block in 0 until blocks) {
            val n = counts[block]
            if (n == 0) continue
            val mean = sums[block].toDouble() / n
            val variance = max(0.0, squares[block].toDouble() / n - mean * mean)
            raw = max(raw, variance)
            val margin = 1.0 + NOISE_MARGIN_SIGMAS * sqrt(NOISE_VARIANCE_SPREAD / n)
            best = max(best, variance - floor * margin)
        }
        return Measurement(score = best, rawVariance = raw, noiseFloor = floor)
    }

    /**
     * MAD / [MAD_SCALE_FACTOR] of the values counted in [histogram] (index − [LAPLACIAN_MAX] is the
     * value), exact, in O(bins): the median is taken from the counts, and so is the median of the
     * absolute deviations from it. Both medians of an even count average the two middle values,
     * as `NoiseAnalyzer` does.
     */
    internal fun robustSigma(histogram: IntArray): Double {
        val total = histogram.fold(0L) { acc, c -> acc + c }
        if (total == 0L) return 0.0
        // Twice the median, so a median between two values stays an integer.
        val twiceMedian = valueAtRank(histogram, (total - 1) / 2) + valueAtRank(histogram, total / 2)
        // |2v − 2·median| for every value, counted: twice the absolute deviation.
        val deviations = IntArray(4 * LAPLACIAN_MAX + 1)
        for (index in histogram.indices) {
            val count = histogram[index]
            if (count == 0) continue
            deviations[abs(2 * (index - LAPLACIAN_MAX) - twiceMedian)] += count
        }
        val twiceMad = (rankIn(deviations, (total - 1) / 2) + rankIn(deviations, total / 2)) / 2.0
        return twiceMad / 2.0 / MAD_SCALE_FACTOR
    }

    /** The value (index − [LAPLACIAN_MAX]) at 0-based [rank] in sorted order. */
    private fun valueAtRank(histogram: IntArray, rank: Long): Int = rankIn(histogram, rank) - LAPLACIAN_MAX

    /** The index at 0-based [rank] when every index is repeated as often as it is counted. */
    private fun rankIn(counts: IntArray, rank: Long): Int {
        var seen = 0L
        for (index in counts.indices) {
            seen += counts[index]
            if (seen > rank) return index
        }
        return counts.lastIndex
    }

    private fun clampIndex(i: Int, size: Int): Int = i.coerceIn(0, size - 1)

    private fun ByteArray.at(i: Int): Int = this[i].toInt() and 0xFF
}
