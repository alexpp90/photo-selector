package com.photoselectortoolbox.domain.grouping

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.source.PhotoBitmapDecoder
import com.photoselectortoolbox.domain.format.ImageFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Grouping granularity levels, matching the desktop implementation.
 */
enum class GroupingLevel(
    val label: String,
    val description: String,
) {
    /** Group by filename prefix + modification time proximity. */
    TIME_FILENAME(
        label = "Time & Filename",
        description = "Group by capture time and filename similarity"
    ),
    /** Above + fast 8x8 dHash similarity. */
    TIME_FAST_SIMILARITY(
        label = "Time + Fast Similarity",
        description = "Group by time with fast perceptual hash comparison"
    ),
    /** Above + detailed 16x16 dHash similarity. */
    DETAILED_SIMILARITY(
        label = "Detailed Similarity",
        description = "Full similarity analysis (slower, more accurate)"
    )
}

/**
 * Groups images into sequences (bursts, brackets, etc.) based on temporal
 * proximity, filename patterns, and optional perceptual similarity.
 *
 * Ported from the desktop Python implementation with three grouping levels.
 */
class ImageGrouper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val bitmapDecoder = PhotoBitmapDecoder(context)

    companion object {
        /** Long edge of the rendition a RAW/TIFF is hashed from. */
        private const val DHASH_SOURCE_LONG_EDGE = 512

        /** Maximum time difference (ms) between consecutive images to be candidates. */
        private const val TIME_THRESHOLD_MS = 30_000L

        /** Hamming distance threshold for fast (8x8) dHash comparison. */
        private const val FAST_HASH_THRESHOLD = 10

        /** Hamming distance threshold for detailed (16x16) dHash comparison. */
        private const val DETAILED_HASH_THRESHOLD = 24

        /** Fast dHash size. */
        private const val FAST_HASH_SIZE = 8

        /** Detailed dHash size. */
        private const val DETAILED_HASH_SIZE = 16
    }

    private val dHashCalculator = DHashCalculator()

    /** Per-run cache of computed dHash values, keyed by "uri:hashSize". */
    private val dHashCache = mutableMapOf<String, Long>()

    /**
     * Group images into sequences based on the specified grouping level.
     * Images should ideally be pre-sorted by modification time.
     *
     * @param images List of images to group.
     * @param level The grouping granularity level.
     * @return List of groups, where each group is a list of related images.
     */
    suspend fun groupImages(
        images: List<ImageItem>,
        level: GroupingLevel
    ): List<List<ImageItem>> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext emptyList()

        // Clear per-run dHash cache to avoid stale entries
        dHashCache.clear()

        // Sort by modification time
        val sorted = images.sortedBy { it.lastModified }

        val groups = mutableListOf<MutableList<ImageItem>>()
        var currentGroup = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            ensureActive()

            val prev = sorted[i - 1]
            val curr = sorted[i]

            val shouldGroup = when (level) {
                GroupingLevel.TIME_FILENAME ->
                    isTemporalCandidate(prev, curr) && hasSameNamePrefix(prev, curr)

                GroupingLevel.TIME_FAST_SIMILARITY ->
                    isTemporalCandidate(prev, curr) &&
                        hasSameNamePrefix(prev, curr) &&
                        isSimilarByDHash(prev, curr, FAST_HASH_SIZE, FAST_HASH_THRESHOLD)

                GroupingLevel.DETAILED_SIMILARITY ->
                    isTemporalCandidate(prev, curr) &&
                        hasSameNamePrefix(prev, curr) &&
                        isSimilarByDHash(prev, curr, DETAILED_HASH_SIZE, DETAILED_HASH_THRESHOLD)
            }

            if (shouldGroup) {
                currentGroup.add(curr)
            } else {
                groups.add(currentGroup)
                currentGroup = mutableListOf(curr)
            }
        }

        // Add the last group
        groups.add(currentGroup)

        // Clear cache to free memory after grouping is complete
        dHashCache.clear()

        groups
    }

    /**
     * Check if two consecutive images are within the time threshold.
     */
    private fun isTemporalCandidate(a: ImageItem, b: ImageItem): Boolean {
        return abs(a.lastModified - b.lastModified) <= TIME_THRESHOLD_MS
    }

    /**
     * Check if two images share the same filename prefix.
     * The prefix is everything before trailing digits in the filename stem.
     * E.g., "IMG_0001.jpg" -> prefix "IMG_", "DSC_1234.ARW" -> prefix "DSC_".
     */
    private fun hasSameNamePrefix(a: ImageItem, b: ImageItem): Boolean {
        val prefixA = extractNamePrefix(a.fileName)
        val prefixB = extractNamePrefix(b.fileName)
        return prefixA.isNotEmpty() && prefixA == prefixB
    }

    /**
     * Extract the prefix from a filename by removing the extension and
     * stripping trailing digits.
     * "IMG_0001.jpg" -> "IMG_"
     * "DSC01234.ARW" -> "DSC"
     * "photo.jpg" -> "photo"
     */
    internal fun extractNamePrefix(fileName: String): String {
        // Remove extension
        val stem = fileName.substringBeforeLast('.')
        // Extract prefix before first digit
        val firstDigitIdx = stem.indexOfFirst { it.isDigit() }
        return if (firstDigitIdx != -1) {
            stem.substring(0, firstDigitIdx)
        } else {
            stem
        }
    }

    /**
     * Check if two images are visually similar using dHash.
     * Only computes hashes for temporal candidates (already checked before calling).
     */
    private fun isSimilarByDHash(
        a: ImageItem,
        b: ImageItem,
        hashSize: Int,
        threshold: Int
    ): Boolean {
        val hashA = computeImageDHash(a, hashSize) ?: return false
        val hashB = computeImageDHash(b, hashSize) ?: return false
        return dHashCalculator.hammingDistance(hashA, hashB) <= threshold
    }

    /**
     * Compute the dHash for an image by loading a small thumbnail via content resolver.
     * Results are cached for the duration of a groupImages() call to avoid
     * redundant bitmap decoding for consecutive pair comparisons.
     * Returns null if the image cannot be loaded.
     */
    private fun computeImageDHash(image: ImageItem, hashSize: Int): Long? {
        val cacheKey = "${image.uri}:$hashSize"
        dHashCache[cacheKey]?.let { return it }

        val hash = try {
            val uri = Uri.parse(image.uri)

            if (ImageFormats.isRaw(image.fileName) || ImageFormats.isTiff(image.fileName)) {
                // No platform codec for these (CR3, TIFF): hash the decode path's
                // small rendition — the embedded camera JPEG for a RAW.
                bitmapDecoder.decodeForDisplay(uri, image.fileName, DHASH_SOURCE_LONG_EDGE)?.let { bmp ->
                    try {
                        dHashCalculator.computeDHash(bmp, hashSize)
                    } finally {
                        bmp.recycle()
                    }
                }
            } else {
                // Load a small version to compute hash efficiently
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 8 // Downsample significantly since we only need a tiny hash
                }

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream, null, options)
                    bitmap?.let { bmp ->
                        try {
                            dHashCalculator.computeDHash(bmp, hashSize)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }

        if (hash != null) {
            dHashCache[cacheKey] = hash
        }
        return hash
    }
}
