package com.photoselector.saf

/**
 * The outcome of moving, copying or reversing a move (`docs/shared/FILE_OPERATIONS.md`).
 *
 * - [success] is true when the transfer completed in full (copy verified; for a
 *   move, the source was also removed).
 * - [partial] is true when a move's destination copy was verified on disk, but
 *   removing the original source failed. The destination copy is preserved and
 *   the caller must surface a partial-outcome warning (`Copied to … — original
 *   could not be removed`) and keep the source item where the user can see it.
 *
 * [destinationUri] is null on failure, and also on success for operations that
 * genuinely produce no new file. A caller deciding whether an undo is possible must
 * therefore test the URI, not just [success].
 */
data class FileOperationResult(
    val sourceUri: String,
    val destinationUri: String?,
    val success: Boolean,
    val error: String? = null,
    val partial: Boolean = false,
    val destinationUris: List<String> = listOfNotNull(destinationUri),
    val parentDocumentId: String? = null,
    /**
     * For a partial move: destination copy URI → URI of the original that could not be removed.
     * Undo deletes exactly these copies (while their originals still exist) instead of restoring
     * them next to the originals, which would only create a `-N` duplicate (finding D-3).
     */
    val copiesOfSurvivingOriginals: Map<String, String> = emptyMap(),
    /** Something the user should be told although the operation succeeded (e.g. an undo that fell back to the opened folder). */
    val notice: String? = null,
    /**
     * For an undo of a partial move: the copies that were deleted (their originals still exist)
     * rather than restored. Data keyed by them (ratings) must go with them; the originals' stays.
     */
    val deletedCopies: List<String> = emptyList(),
) {
    companion object {
        fun success(
            sourceUri: String,
            destinationUri: String?,
            destinationUris: List<String> = listOfNotNull(destinationUri),
            parentDocumentId: String? = null,
        ) = FileOperationResult(
            sourceUri = sourceUri,
            destinationUri = destinationUri,
            success = true,
            destinationUris = destinationUris,
            parentDocumentId = parentDocumentId,
        )

        fun failure(
            sourceUri: String,
            error: String?,
            parentDocumentId: String? = null,
        ) = FileOperationResult(
            sourceUri = sourceUri,
            destinationUri = null,
            success = false,
            error = error,
            destinationUris = emptyList(),
            parentDocumentId = parentDocumentId,
        )

        fun partial(
            sourceUri: String,
            destinationUri: String,
            error: String = "Copied, but the original could not be removed",
            destinationUris: List<String> = listOf(destinationUri),
            parentDocumentId: String? = null,
            copiesOfSurvivingOriginals: Map<String, String> = mapOf(destinationUri to sourceUri),
        ) = FileOperationResult(
            sourceUri = sourceUri,
            destinationUri = destinationUri,
            success = false,
            error = error,
            partial = true,
            destinationUris = destinationUris,
            parentDocumentId = parentDocumentId,
            copiesOfSurvivingOriginals = copiesOfSurvivingOriginals,
        )
    }
}
