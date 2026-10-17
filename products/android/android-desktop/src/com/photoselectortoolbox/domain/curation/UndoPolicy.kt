package com.photoselectortoolbox.domain.curation

/**
 * What, if anything, can genuinely be undone about an action that has happened.
 *
 * The rule this type exists to enforce: **an UNDO that silently does nothing is
 * worse than no UNDO at all.** The selector snackbar takes `onUndo` from
 * [UndoPolicy], and a null here means the affordance is simply not drawn.
 */
sealed interface UndoableOperation {

    /** The list slots to restore. Restoring the list is always possible. */
    val slots: List<ImageSlot>

    /**
     * A deletion still inside its window. Nothing has been deleted on disk, so
     * undoing is a list insertion and a cancelled timer.
     */
    data class RevertPendingDelete(
        val pending: PendingDeletion,
    ) : UndoableOperation {
        override val slots: List<ImageSlot> get() = pending.slots
    }

    /**
     * A deletion already committed to a backend that trashes rather than
     * destroys. The file comes back out of the trash.
     */
    data class RestoreFromTrash(
        override val slots: List<ImageSlot>,
        val uris: List<String>,
    ) : UndoableOperation

    /**
     * A completed move. The file (and any companions) exist at
     * [destinationUris] and can be moved back into the sub-folder
     * ([parentDocumentId]) or root folder the photographer opened.
     */
    data class ReverseMove(
        override val slots: List<ImageSlot>,
        val sourceUri: String,
        val destinationUri: String,
        val destinationUris: List<String> = listOf(destinationUri),
        val parentDocumentId: String? = null,
        /**
         * Set after a *partial* move: destination copy → original that could not be removed.
         * Undo deletes those copies (while the original exists) instead of restoring them.
         */
        val copiesOfSurvivingOriginals: Map<String, String> = emptyMap(),
    ) : UndoableOperation
}

/**
 * Decides whether an action is reversible, and how.
 *
 * Pure and Android-free: the caller supplies the facts (was the delete deferred,
 * does this backend trash, did the move report a destination) and gets back
 * either an operation to run or null.
 */
object UndoPolicy {

    /**
     * Maximum number of reversible operations (deferred deletes + moves) held in
     * the session undo stack before the oldest entry is evicted and committed.
     */
    const val MAX_UNDO_HISTORY = 20

    /**
     * Push [operation] onto the front of [history] (newest first), evicting any
     * entries beyond [maxDepth].
     */
    fun push(
        history: List<UndoableOperation>,
        operation: UndoableOperation,
        maxDepth: Int = MAX_UNDO_HISTORY,
    ): UndoPushResult {
        val combined = listOf(operation) + history
        return if (combined.size <= maxDepth) {
            UndoPushResult(history = combined)
        } else {
            UndoPushResult(
                history = combined.take(maxDepth),
                evicted = combined.drop(maxDepth),
            )
        }
    }

    /**
     * Pop the newest operation from [history], returning the popped operation
     * (or null when empty) and the remaining history.
     */
    fun pop(
        history: List<UndoableOperation>,
    ): Pair<UndoableOperation?, List<UndoableOperation>> {
        val head = history.firstOrNull() ?: return null to emptyList()
        return head to history.drop(1)
    }

    /**
     * Undo for a deletion that is still deferred — nothing on disk has changed
     * yet, which is the strongest form of undo available.
     */
    fun forPendingDelete(pending: PendingDeletion?): UndoableOperation? {
        if (pending == null || pending.slots.isEmpty()) return null
        return UndoableOperation.RevertPendingDelete(pending)
    }

    /**
     * Undo for a deletion that has already been committed to disk.
     *
     * Only offered where the backend trashes rather than destroys. A SAF delete
     * is an unlink with nothing behind it, so this returns null and the snackbar
     * shows no UNDO — the honest outcome.
     */
    fun forCommittedDelete(
        slots: List<ImageSlot>,
        canTrash: Boolean,
    ): UndoableOperation? {
        if (slots.isEmpty() || !canTrash) return null
        return UndoableOperation.RestoreFromTrash(
            slots = slots,
            uris = slots.flatMap { slot -> slot.image.allFiles.map { it.uri } },
        )
    }

    /**
     * Undo for a filing action.
     *
     * - **Move** is reversible when the repository reported where the file went;
     *   without a destination URI the file cannot be found again, so no UNDO.
     * - **Copy** is deliberately *not* offered. Undoing a copy means deleting a
     *   file the photographer just created, and an undo must never be the thing
     *   that destroys data. The original was never touched, so there is nothing
     *   to restore either.
     */
    fun forFiling(
        action: CurationAction,
        slots: List<ImageSlot>,
        sourceUri: String,
        destinationUri: String?,
        destinationUris: List<String> = listOfNotNull(destinationUri),
        parentDocumentId: String? = null,
        copiesOfSurvivingOriginals: Map<String, String> = emptyMap(),
    ): UndoableOperation? {
        if (action != CurationAction.MOVE) return null
        val effectiveDestinations = if (destinationUris.isNotEmpty()) {
            destinationUris
        } else {
            listOfNotNull(destinationUri)
        }
        val primaryDestination = destinationUri ?: effectiveDestinations.firstOrNull() ?: return null
        return UndoableOperation.ReverseMove(
            slots = slots,
            sourceUri = sourceUri,
            destinationUri = primaryDestination,
            destinationUris = effectiveDestinations,
            parentDocumentId = parentDocumentId,
            copiesOfSurvivingOriginals = copiesOfSurvivingOriginals,
        )
    }
}

/** Result of pushing a new operation onto the bounded undo history. */
data class UndoPushResult(
    val history: List<UndoableOperation>,
    val evicted: List<UndoableOperation> = emptyList(),
) {
    /** Pending deletions evicted from the tail that must now be committed to disk. */
    val evictedPendingDeletions: List<PendingDeletion>
        get() = evicted.filterIsInstance<UndoableOperation.RevertPendingDelete>().map { it.pending }
}
