package com.photoselectortoolbox.domain.curation

/**
 * A deletion the photographer can still take back.
 *
 * The frame is out of the list immediately, but nothing has touched the disk
 * yet — which is what makes the undo trustworthy: reverting is a list insertion,
 * not a hope that the file system will cooperate. The files are removed for real
 * when the folder is left, when the session ends, or when 20 newer undoable
 * actions push this one off the stack ([UndoPolicy.MAX_UNDO_HISTORY]). There is
 * no timer (FD-D-1).
 */
data class PendingDeletion(
    /** What was removed, and from where. Ascending by index. */
    val slots: List<ImageSlot>,
    /** When the deletion was applied, in `System.currentTimeMillis()` terms. */
    val requestedAtMillis: Long,
) {
    /**
     * The URIs that must actually be deleted when this is committed: every
     * removed frame's primary URI plus all of its companion URIs.
     */
    val uris: List<String>
        get() = slots.flatMap { slot -> slot.image.allFiles.map { it.uri } }
}

/**
 * Pure state transitions for deferred deletes: the frame leaves the list now,
 * the files go when the folder is left. This is the half of the undo that has no
 * Android in it.
 */
object DeferredDeletion {

    /**
     * Remove [uris] from the list and hold them pending.
     *
     * Returns null when nothing matched, so a caller cannot end up with an empty
     * pending deletion whose snackbar offers an undo for nothing.
     */
    fun begin(
        state: SelectorListState,
        uris: List<String>,
        nowMillis: Long,
    ): Pair<SelectorListState, PendingDeletion>? {
        val edit = OptimisticEdits.apply(state, CurationAction.DELETE, uris)
        if (edit.slots.isEmpty()) return null
        return edit.state to PendingDeletion(edit.slots, nowMillis)
    }

    /** Remove the frame on screen and hold it pending. */
    fun beginForCurrent(
        state: SelectorListState,
        nowMillis: Long,
    ): Pair<SelectorListState, PendingDeletion>? {
        val uri = state.currentUri ?: return null
        return begin(state, listOf(uri), nowMillis)
    }

    /**
     * Take the deletion back: the frames return to their original slots and the
     * photographer stays on the frame they are looking at.
     *
     * No file operation is involved — that is the entire point of deferring.
     */
    fun revert(
        state: SelectorListState,
        pending: PendingDeletion,
    ): SelectorListState = OptimisticEdits.restore(state, pending.slots)
}
