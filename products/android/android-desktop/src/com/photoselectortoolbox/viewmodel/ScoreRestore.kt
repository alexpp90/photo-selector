package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult

/**
 * The cached-score restore of a folder open (REQ-AND-CACHE.03), pure: the view model reads the
 * folder's rows in one batched call (`CacheRepository.getCachedScores`, chunked at the SQLite bind
 * limit) and these functions put them on the photos. One query per photo used to cost 403 ms at
 * 10,000 photos against 39 ms batched (`ScoreRestoreScaleTest`, `SelectorViewModelScaleTest`).
 */
internal object ScoreRestore {

    /**
     * [images] with the scores of [cached] (by URI) that still match their file (size and
     * modification time) and the rating that wins: the photo's own, else the stored user rating
     * of [userRatings], else the cached row's.
     */
    fun restored(images: List<ImageItem>, cached: Map<String, ScoreEntity>, userRatings: Map<String, Int>): List<ImageItem> =
        images.map { image ->
            val row = cached[image.uri]?.takeIf { it.fileSize == image.fileSize && it.lastModified == image.lastModified }
            val rating = when {
                image.rating != 0 -> image.rating
                else -> userRatings[image.uri] ?: row?.rating ?: 0
            }
            val scanResult = image.scanResult ?: row?.let {
                ScanResult(
                    filePath = image.uri,
                    sharpnessScore = it.sharpnessScore,
                    noiseLevel = it.noiseLevel,
                    highlightClipping = it.highlightClipping,
                    shadowClipping = it.shadowClipping,
                    aestheticScore = it.aestheticScore,
                )
            }
            if (rating == image.rating && scanResult == image.scanResult) image else image.copy(rating = rating, scanResult = scanResult)
        }

    /**
     * The ratings of the restore, applied to the live list: a stored user rating replaces a photo
     * with none or with its file's own (EXIF) rating; otherwise a restored rating fills a photo
     * with none. Everything else the photographer set meanwhile stays.
     */
    fun withRatings(images: List<ImageItem>, userRatings: Map<String, Int>, restoredRatings: Map<String, Int>): List<ImageItem> =
        images.map { img ->
            val explicit = userRatings[img.uri]
            val fallback = restoredRatings[img.uri] ?: 0
            when {
                explicit != null && (img.rating == 0 || img.rating == img.exifData?.rating) -> img.copy(rating = explicit)
                img.rating == 0 && fallback != 0 -> img.copy(rating = fallback)
                else -> img
            }
        }
}
