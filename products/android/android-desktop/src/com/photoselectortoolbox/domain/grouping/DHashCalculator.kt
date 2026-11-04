package com.photoselectortoolbox.domain.grouping

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Utility class for computing difference hashes (dHash) and comparing them.
 * dHash is a perceptual hash that captures relative gradient information,
 * making it robust to scaling and minor modifications.
 *
 * - Fast mode (hashSize=8): 9x8 resize -> 64-bit hash
 * - Detailed mode (hashSize=16): 17x16 resize -> 256-bit hash
 *
 * The hash holds all hashSize² bits, as the legacy desktop's `compute_dhash` does
 * (FD-C-12): the detailed hash used to keep only the first 64 of 256 bits — the top
 * quarter of the frame — and so saw nothing below it.
 */
class DHashCalculator {

    /**
     * Compute a difference hash for the given bitmap.
     *
     * The image is resized to (hashSize+1) x hashSize, converted to grayscale,
     * and a horizontal gradient is computed. Each bit represents whether a pixel
     * is brighter than its right neighbor; bit `i` (row-major) is bit `i % 64`
     * of word `i / 64`.
     *
     * @param bitmap The image to hash.
     * @param hashSize 8 for fast comparison, 16 for detailed comparison.
     * @return hashSize² bits packed into `ceil(hashSize² / 64)` words.
     */
    fun computeDHash(bitmap: Bitmap, hashSize: Int = 8): LongArray {
        val width = hashSize + 1
        val height = hashSize
        val bits = hashSize * hashSize
        val hash = LongArray((bits + 63) / 64)

        // Resize to (hashSize+1) x hashSize
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)

        try {
            var bitPosition = 0
            for (y in 0 until height) {
                for (x in 0 until hashSize) {
                    // Compare each pixel with its right neighbor
                    val leftLuminance = luminance(resized.getPixel(x, y))
                    val rightLuminance = luminance(resized.getPixel(x + 1, y))

                    if (leftLuminance > rightLuminance) {
                        hash[bitPosition / 64] = hash[bitPosition / 64] or (1L shl (bitPosition % 64))
                    }
                    bitPosition++
                }
            }
            return hash
        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }
    }

    /**
     * Compute the Hamming distance between two hashes of the same size: the number of
     * bit positions where they differ (0 = identical, hashSize² = maximally different).
     */
    fun hammingDistance(hash1: LongArray, hash2: LongArray): Int {
        require(hash1.size == hash2.size) { "hashes of different sizes" }
        var distance = 0
        for (i in hash1.indices) distance += (hash1[i] xor hash2[i]).countOneBits()
        return distance
    }

    /** The Hamming distance of two 64-bit words. */
    fun hammingDistance(hash1: Long, hash2: Long): Int = (hash1 xor hash2).countOneBits()

    /**
     * Convert an ARGB pixel to grayscale luminance using the standard
     * ITU-R BT.601 coefficients.
     */
    private fun luminance(pixel: Int): Double {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
