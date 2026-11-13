package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult

/**
 * A scan that ran to its end (shoot debrief SD-1a, finding S14): what tells Learn that a folder's
 * facts are stored and its debrief can be computed. The cull screen's view model sets it in the
 * success branch of its scan and nowhere else, so a cancelled or failed scan leaves the previous
 * value; Learn reloads when it changes.
 *
 * [partial] when the folder was still being listed as the scan started — it took the photo list
 * as it was — or when photos stayed without a result (no decode): a debrief from it is said to be
 * of "n of m photos" and is never another shoot's "previous" one (SD-3).
 */
data class CompletedScan(
    val folderUri: String,
    val finishedAtEpochMs: Long,
    val photoCount: Int,
    val withoutResult: Int,
    val stillListing: Boolean,
) {
    val partial: Boolean get() = stillListing || withoutResult > 0

    /** Photos the scan gave a result to. */
    val scannedCount: Int get() = photoCount - withoutResult

    companion object {
        /**
         * The scan of [scanned] (the list it was started with) in [folderUri], which ended at
         * [finishedAtEpochMs] with [results] (its last progress emission). [stillListing]: the
         * folder listing had not settled when the scan took its list.
         */
        fun of(
            folderUri: String,
            scanned: List<ImageItem>,
            results: Map<String, ScanResult>,
            stillListing: Boolean,
            finishedAtEpochMs: Long,
        ): CompletedScan = CompletedScan(
            folderUri = folderUri,
            finishedAtEpochMs = finishedAtEpochMs,
            photoCount = scanned.size,
            withoutResult = scanned.count { it.uri !in results },
            stillListing = stillListing,
        )
    }
}
