package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.ImageItem

/** A Copy into the Selection, remembered for its folder (`docs/shared/FILE_OPERATIONS.md` §5, D3). */
data class CopiedRecord(
    val sourceUri: String,
    /** The photo's size and last-modified time when it was copied. */
    val identity: FileIdentity,
    /** Provider document id of the photo's own copy in the Selection. */
    val primaryCopyId: String,
    /** Every bundle member's copy, document id → size of the source file (`KeptFiles.of`). */
    val keptFiles: Map<String, Long>,
)

/**
 * Which photos of a reopened folder are still copied (`docs/shared/FILE_OPERATIONS.md` §5,
 * "Survives a reopen"). Pure: the caller supplies the records, the listed photos and one listing
 * of the Selection folder.
 *
 * A record counts when its photo is listed with the identity it had when copied — not by name
 * alone, so a different file under the same name opens undecided — and the photo's own copy is
 * still in the Selection with the size of its original. A copy that has left the Selection
 * (deleted or moved by hand), or a photo that changed since, makes the photo undecided again, and
 * the record is stale. Companions do not decide it: the photo is copied while its own copy is
 * there. A photo not listed (deleted since, or not reached by a listing) leaves its record alone.
 * Without a Selection listing nothing is restored and nothing is stale: an unreadable Selection
 * proves nothing either way.
 */
object CopiedOnReopen {

    data class Outcome(
        /** Still-copied photos by URI, with the files their copy put into the Selection. */
        val copied: Map<String, Map<String, Long>>,
        /** URIs whose record no longer holds: forget them. */
        val stale: Set<String>,
    )

    fun resolve(
        records: List<CopiedRecord>,
        listed: List<ImageItem>,
        selectionFiles: Map<String, Long>?,
    ): Outcome {
        if (selectionFiles == null || records.isEmpty()) return Outcome(emptyMap(), emptySet())
        val byUri = listed.associateBy { it.uri }
        val copied = linkedMapOf<String, Map<String, Long>>()
        val stale = linkedSetOf<String>()
        for (record in records) {
            val photo = byUri[record.sourceUri] ?: continue
            val sameFile = record.identity.matches(FileIdentity(photo.fileSize, photo.lastModified))
            val copySize = selectionFiles[record.primaryCopyId]
            val sourceSize = record.keptFiles[record.primaryCopyId] ?: record.identity.size
            val copyThere = copySize != null && (copySize < 0L || sourceSize <= 0L || copySize == sourceSize)
            if (sameFile && copyThere) copied[record.sourceUri] = record.keptFiles else stale += record.sourceUri
        }
        return Outcome(copied, stale)
    }
}
