package com.photoselectortoolbox.domain.session

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.SelectorListState

/**
 * The outcome of re-ordering the selector's list.
 *
 * [changed] is false when the list was already in the requested order; [state]
 * is then the input unchanged, and the caller has nothing to publish, regroup
 * or re-key.
 */
data class Reordered(
    val state: SelectorListState,
    val changed: Boolean,
)

/**
 * Pure re-ordering of the list the photographer is looking at.
 *
 * Discovery appends batch by batch (REQ-AND-PERF.03), so a time order over the
 * whole folder is only knowable once enumeration settles. The list is then
 * re-ordered once, and these functions decide where the photographer lands.
 * The frame on screen is always re-derived from its **URI**, never carried as an
 * integer across the re-order (`SelectorListState.focusedOn`).
 */
object SelectorReorder {

    /**
     * The one re-order when enumeration settles.
     *
     * - Already in [order]: nothing changes.
     * - The photographer is still on frame 1 and has done nothing since the
     *   folder opened ([photographerHasActed] false, index 0): they land on the
     *   new frame 1 — with [SelectorSortOrder.NEWEST_FIRST], the newest photo.
     * - Otherwise the frame on screen stays on screen, wherever it now sits.
     *
     * An untouched session that is somehow not on frame 1 keeps its frame: moving
     * someone off a photo is never the safe default.
     */
    fun atSettle(
        state: SelectorListState,
        order: SelectorSortOrder,
        photographerHasActed: Boolean,
    ): Reordered {
        if (order.isOrdered(state.images)) return Reordered(state, changed = false)
        val untouched = !photographerHasActed && state.currentIndex == 0
        if (!untouched) return keepingFrame(state, order.comparator)
        return Reordered(
            SelectorListState(order.order(state.images), currentIndex = 0),
            changed = true,
        )
    }

    /** [keepingFrame] in a persisted [order] — the order picked from the UI. */
    fun keepingFrame(state: SelectorListState, order: SelectorSortOrder): Reordered =
        keepingFrame(state, order.comparator)

    /**
     * Re-order by [comparator] and keep the frame on screen by URI.
     *
     * Takes a comparator rather than only a [SelectorSortOrder] so the session
     * views (worst-first triage) use the same frame-keeping rule, and so a
     * caller that has just restored undo slots — which go back to their
     * *recorded* indices, i.e. positions in the order at the time of removal —
     * can put them where the active order says they belong.
     */
    fun keepingFrame(state: SelectorListState, comparator: Comparator<ImageItem>): Reordered {
        val images = state.images
        val alreadyOrdered = (1 until images.size).all { i ->
            comparator.compare(images[i - 1], images[i]) <= 0
        }
        if (alreadyOrdered) return Reordered(state, changed = false)
        val sorted = images.sortedWith(comparator)
        return Reordered(
            SelectorListState(sorted, state.currentIndex).focusedOn(state.currentUri),
            changed = true,
        )
    }

    /**
     * Burst groups (`SelectorUiState.groups`, lists of indices into the list)
     * carried from [before] to [after] by URI.
     *
     * Members no longer in [after] are dropped, as are groups left empty. Each
     * group is ascending in [after]'s order — so a burst chip counts "1 of 5"
     * on the frame shown first, whichever way the list runs — and groups are
     * ordered by their first member.
     */
    fun remapGroups(
        groups: List<List<Int>>,
        before: List<ImageItem>,
        after: List<ImageItem>,
    ): List<List<Int>> {
        if (groups.isEmpty()) return groups
        val indexByUri = HashMap<String, Int>(after.size * 2)
        after.forEachIndexed { i, image -> indexByUri[image.uri] = i }
        return groups
            .map { group ->
                group.mapNotNull { oldIndex -> before.getOrNull(oldIndex)?.uri?.let(indexByUri::get) }
                    .distinct()
                    .sorted()
            }
            .filter { it.isNotEmpty() }
            .sortedBy { it.first() }
    }
}
