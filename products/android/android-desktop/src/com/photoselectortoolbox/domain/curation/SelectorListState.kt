package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.ImageItem

/**
 * The list the selector is showing, plus which frame the photographer is on.
 *
 * This product has exactly one list — unlike Jackpix, which carries a filtered
 * feed alongside an unfiltered source list and has to record two indices per
 * item. Modelling that here would be inventing a problem: one list, one index.
 *
 * Kept free of Android types so every index transition in this package is a JVM
 * unit test rather than an emulator run.
 */
data class SelectorListState(
    val images: List<ImageItem>,
    val currentIndex: Int,
) {
    /** The URI of the frame currently on screen, or null when the list is empty. */
    val currentUri: String?
        get() = images.getOrNull(currentIndex)?.uri

    /**
     * The same list with [currentIndex] re-derived from [uri].
     *
     * **Never adjust a stored index across a mutation.** Both this product and
     * the desktop have shipped the bug where a re-insertion ahead of the user
     * shifted them one frame without anything visibly happening (see the
     * 2026-07-24 and 2026-07-31 entries in `ai/memory/palette.md`). Identity
     * survives insertions; an integer does not.
     */
    fun focusedOn(uri: String?): SelectorListState {
        if (images.isEmpty()) return copy(currentIndex = 0)
        val index = uri?.let { target -> images.indexOfFirst { it.uri == target } } ?: -1
        return copy(
            currentIndex = if (index >= 0) index else currentIndex.coerceIn(0, images.size - 1),
        )
    }
}

/** Where an image sat in the list before it was optimistically removed. */
data class ImageSlot(
    val image: ImageItem,
    val index: Int,
) {
    val uri: String get() = image.uri
}

/**
 * What is being done to a photograph, and — the part that matters here —
 * whether the list runs ahead of the file system while it happens.
 *
 * A failed **copy** must not rewind anything: the source file never left, so the
 * frame never left the list either. Asymmetric operations get asymmetric
 * rollback, and stating that as data rather than as an `if` at each call site is
 * what stops the two from drifting.
 */
enum class CurationAction(
    /** Whether the frame leaves the list the instant the action is invoked. */
    val removesFrame: Boolean,
) {
    /** File a copy into the Selection. The original stays where it is. */
    COPY(removesFrame = false),

    /** File the original into the Selection. It leaves the source folder. */
    MOVE(removesFrame = true),

    /** Remove the photograph. Deferred: see [DeferredDeletion]. */
    DELETE(removesFrame = true);

    /** Only an action that removed something can put it back. */
    val rewindsOnFailure: Boolean
        get() = removesFrame
}
