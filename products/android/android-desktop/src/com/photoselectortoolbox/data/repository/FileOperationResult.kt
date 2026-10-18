package com.photoselectortoolbox.data.repository

/**
 * The outcome of moving, copying or reversing a move for a single file.
 *
 * `moveImage`/`copyImage` used to return `Boolean`, which discarded the one
 * piece of information an undo needs: **where the file went**. Without the
 * destination URI nothing below the UI could reverse a move, so the selector's
 * snackbar drew a 30-second countdown next to a null `onUndo` (see the
 * `[OPEN] 2026-07-27 - No Repository-Level Undo` entry in
 * `ai/memory/code_health.md`).
 *
 * The shape deliberately matches the `MoveResult` that
 * [com.photoselectortoolbox.domain.usecase.MoveToSelectionUseCase] already
 * returned — that type is now a typealias of this one, so there is exactly one
 * result shape for file operations in this product rather than two that drift.
 *
 * [destinationUri] is null on failure, and also on success for operations that
 * genuinely produce no new file (a reversal that only untrashes, for instance).
 * A caller deciding whether an undo is possible must therefore test the URI, not
 * just [success] — that decision lives in
 * [com.photoselectortoolbox.domain.curation.UndoPolicy].
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
     * rather than restored. Their rating rows must go with them; the originals' stay untouched.
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
