package com.photoselectortoolbox.domain.session

import com.photoselector.core.storage.SourceKind

/**
 * Where the background dimension backfill (REQ-AND-PERF.05) may open every photo of a folder.
 *
 * The backfill reads one header per photo, the whole folder through. That is cheap on the
 * device's own storage and on a card; it is not on the other sources of the usage-conditions
 * contract (`testdata/conditions/conditions.json`, `storage`): a cloud provider downloads each
 * file in full on open, so a backfill over 10,000 photos downloads the folder, and a camera over
 * MTP pays a round-trip per file on the one channel the visible frames also decode through.
 * There only the visible range is resolved, on navigation; every other frame keeps the 3:2
 * default until it is shown.
 */
object DimensionBackfill {

    /** Whether the folder-wide backfill runs for a folder of [kind]. */
    fun runsFor(kind: SourceKind): Boolean = when (kind) {
        SourceKind.PHONE_LIBRARY, SourceKind.REMOVABLE -> true
        SourceKind.CAMERA_MTP, SourceKind.CLOUD_OTHER -> false
    }
}
