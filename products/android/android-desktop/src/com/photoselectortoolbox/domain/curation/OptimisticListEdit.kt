package com.photoselectortoolbox.domain.curation

/**
 * The list edit an action applies **before** the file system has confirmed it,
 * together with everything needed to take it back.
 *
 * Filing a frame must feel instant: waiting on a SAF copy of a 60 MB raw file
 * before the next photograph appears is exactly the moment the app must not
 * stall. So the list moves first and the transfer runs on the
 * `@ApplicationScope` scope behind it. That makes the list briefly *ahead* of
 * the file system, and a failure then has to be undone — otherwise the app has
 * told the photographer a frame was filed while it is still sitting in the
 * source folder.
 */
data class OptimisticListEdit(
    val action: CurationAction,
    /** The list as the user now sees it. */
    val state: SelectorListState,
    /** What was removed, and from where. Empty for actions that remove nothing. */
    val slots: List<ImageSlot>,
)

/**
 * Pure list transitions for optimistically applied actions.
 *
 * Every function here is total and side-effect free, so the index arithmetic —
 * which is where all the real bugs live — is unit-tested without an emulator.
 */
object OptimisticEdits {

    /**
     * Apply [action] to the frames at [uris], optimistically.
     *
     * For [CurationAction.COPY] this is a no-op on the list by design: nothing
     * left the folder, so nothing leaves the list, and the photographer keeps
     * comparing the frame they just filed against its neighbours.
     */
    fun apply(
        state: SelectorListState,
        action: CurationAction,
        uris: List<String>,
    ): OptimisticListEdit {
        if (!action.removesFrame || uris.isEmpty()) {
            return OptimisticListEdit(action, state, emptyList())
        }

        val slots = slotsOf(state, uris)
        if (slots.isEmpty()) return OptimisticListEdit(action, state, emptyList())

        val removed = slots.map { it.uri }.toSet()
        val images = state.images.filterNot { it.uri in removed }
        // The frame that slid up into the freed slot becomes the current one;
        // at the end of the folder we step back instead.
        val index = state.currentIndex
            .coerceAtMost(images.size - 1)
            .coerceAtLeast(0)

        return OptimisticListEdit(
            action = action,
            state = SelectorListState(images, index),
            slots = slots,
        )
    }

    /** Apply [action] to the frame on screen. */
    fun applyToCurrent(
        state: SelectorListState,
        action: CurationAction,
    ): OptimisticListEdit {
        val uri = state.currentUri ?: return OptimisticListEdit(action, state, emptyList())
        return apply(state, action, listOf(uri))
    }

    /** Where [uris] currently sit, in list order. Unknown URIs are dropped. */
    fun slotsOf(state: SelectorListState, uris: List<String>): List<ImageSlot> =
        uris.mapNotNull { uri ->
            val index = state.images.indexOfFirst { it.uri == uri }
            if (index >= 0) ImageSlot(state.images[index], index) else null
        }.sortedBy { it.index }

    /**
     * Undo an optimistic [edit] because the file operation failed.
     *
     * Three rules, each of which has been a shipped bug somewhere in this
     * repository:
     *
     * - A [CurationAction.COPY] rewinds **nothing**. The source file never left,
     *   so there is nothing to put back, and re-inserting would duplicate a
     *   frame that is already on screen.
     * - Slots are re-inserted in **ascending index order**, so an earlier
     *   insertion cannot displace a later one's position.
     * - The resulting index is re-derived from the **URI** of the frame that was
     *   on screen, never from the stored integer. Re-inserting ahead of the
     *   photographer must not move them onto a different photograph.
     */
    fun rollback(
        state: SelectorListState,
        edit: OptimisticListEdit,
    ): SelectorListState = rollback(state, edit.action, edit.slots)

    /** [rollback] for callers that kept the slots but not the whole edit. */
    fun rollback(
        state: SelectorListState,
        action: CurationAction,
        slots: List<ImageSlot>,
    ): SelectorListState {
        if (!action.rewindsOnFailure || slots.isEmpty()) return state
        return restore(state, slots)
    }

    /**
     * Put [slots] back where they were, keeping the photographer on the frame
     * they are looking at.
     *
     * Used both by rollback and by the undo of a deferred deletion; the list
     * arithmetic is identical, only the reason differs.
     */
    fun restore(
        state: SelectorListState,
        slots: List<ImageSlot>,
    ): SelectorListState {
        if (slots.isEmpty()) return state

        val activeUri = state.currentUri
        val images = state.images.toMutableList()
        // A frame that stayed with only its original is whole again where it stands.
        var activeFollowsRestoredShot: String? = null

        slots.sortedBy { it.index }.forEach { slot ->
            val keptAt = slot.kept?.let { kept -> images.indexOfFirst { it.uri == kept.uri } } ?: -1
            if (keptAt >= 0) {
                if (images[keptAt].uri == activeUri) activeFollowsRestoredShot = slot.uri
                images[keptAt] = slot.image
            } else if (images.none { it.uri == slot.uri }) {
                images.add(slot.index.coerceIn(0, images.size), slot.image)
            }
        }

        val restoredActiveUri = activeFollowsRestoredShot ?: activeUri ?: slots.minByOrNull { it.index }?.uri
        return SelectorListState(images, state.currentIndex).focusedOn(restoredActiveUri)
    }
}
