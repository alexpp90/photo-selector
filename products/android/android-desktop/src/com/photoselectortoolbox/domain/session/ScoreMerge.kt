package com.photoselectortoolbox.domain.session

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.scoring.filledFrom

/**
 * Folding computed scores back into a list that did not stand still.
 *
 * Both producers of a [ScanResult] — the Room cache restore and the scan itself
 * — work from a snapshot: the restore reads one row per photograph off the IO
 * dispatcher, the scan streams progress over minutes. Meanwhile EXIF and image
 * dimensions are being written into the same items by their own coroutines.
 *
 * The bug this exists to prevent is subtle because the wrong version looks like
 * a merge:
 *
 * ```
 * if (image.scanResult != null) image else byUri[image.uri] ?: image
 * ```
 *
 * `byUri` holds copies of the *pre-work* snapshot, so for every image that did
 * not already have a score — which, in an unscanned folder, is all of them —
 * that line replaces a live item with a stale one and reverts whatever landed
 * in between. EXIF is only re-fetched on navigation, so the frame the
 * photographer was looking at simply stayed blank.
 *
 * The rule, then: **merge the field you computed, never the item you computed
 * it from.** Kept pure and free of Android and coroutine types so it can be
 * tested without a dispatcher, which is the other half of why the original went
 * unnoticed.
 */
object ScoreMerge {

    /**
     * Apply [scores] to [images] by URI, writing only [ImageItem.scanResult].
     *
     * An image that already has a score keeps every value it has: a scan in
     * flight and a cache restore can both land on the same photograph, and the
     * one already on screen is the one the photographer has seen. Only a
     * measurement it has no value for is filled in — the one a later scan
     * measured after an earlier scan left it unticked (REQ-AND-ALGO.07).
     */
    fun apply(
        images: List<ImageItem>,
        scores: Map<String, ScanResult>,
    ): List<ImageItem> {
        if (scores.isEmpty()) return images
        return images.map { image ->
            val score = scores[image.uri] ?: return@map image
            val existing = image.scanResult ?: return@map image.copy(scanResult = score)
            val filled = existing.filledFrom(score)
            if (filled == existing) image else image.copy(scanResult = filled)
        }
    }

    /**
     * The scores carried by [items], keyed by URI — the half of a snapshot that
     * is safe to merge forward.
     *
     * Exists so a caller cannot accidentally pass the whole items through
     * [apply]; taking the scores out is the step that makes the rollback
     * impossible to express.
     */
    fun scoresOf(items: List<ImageItem>): Map<String, ScanResult> =
        items.mapNotNull { item -> item.scanResult?.let { item.uri to it } }.toMap()
}
