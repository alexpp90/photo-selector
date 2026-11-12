package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.domain.curation.SelectorListState
import com.photoselectortoolbox.domain.model.RatingFilter
import kotlin.math.abs

/**
 * Where a step lands, pure. The cull steps with [RatingFilter.All] (every photo of the folder);
 * Learn steps with its own rating filter (REQ-AND-RATING.01). Null means the step goes nowhere.
 */
internal object SelectorStepping {

    /**
     * A copy, like a move, moves the photographer on: the copied frame becomes the left
     * "previous" frame, still there to compare against (backlog 2026-11-11). The last frame stays.
     */
    fun afterCopy(list: SelectorListState): SelectorListState =
        list.copy(currentIndex = (list.currentIndex + 1).coerceAtMost((list.images.size - 1).coerceAtLeast(0)))

    fun next(state: SelectorUiState, filter: RatingFilter): Int? =
        ((state.currentIndex + 1) until state.images.size).firstOrNull { filter.matches(state.images[it].rating) }

    fun previous(state: SelectorUiState, filter: RatingFilter): Int? =
        (state.currentIndex - 1 downTo 0).firstOrNull { filter.matches(state.images[it].rating) }

    /** The photo to show once [filter] is set: the current one if it matches, else the nearest that does. */
    fun nearestMatching(state: SelectorUiState, filter: RatingFilter): Int? {
        val current = state.currentImage ?: return null
        if (filter.matches(current.rating)) return null
        return state.images.indices.filter { filter.matches(state.images[it].rating) }
            .minByOrNull { abs(it - state.currentIndex) }
    }
}
