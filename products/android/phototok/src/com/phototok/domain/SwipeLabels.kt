package com.phototok.domain

/**
 * Wording for the on-screen swipe indicators and coach marks.
 *
 * Jackpix is gesture-first, so the indicator that appears mid-swipe is the
 * only thing telling the user what is about to happen. It must therefore name
 * the action the user has actually configured: a swipe right that *moves* a
 * file must never be labelled "KEEP".
 *
 * Pure (Android-free) so the wording is unit-testable and every call site —
 * viewer indicators, idle peeks, controls overlay — agrees on one vocabulary.
 */
object SwipeLabels {

    /** Fallback folder names used before the user has picked a target folder. */
    internal const val DEFAULT_COLLECTION = "your collection"
    internal const val DEFAULT_LEFT_SWIPE = "your chosen folder"

    // ── Swipe right (collection / favourite action) ──────────────────────

    /** Short, all-caps indicator label for a swipe right. */
    fun rightLabel(action: CollectionAction): String = when (action) {
        CollectionAction.COPY -> "COPY"
        CollectionAction.MOVE -> "MOVE"
    }

    /** Short indicator label for a [SwipeRightVerb] (`U-17`). */
    fun rightLabel(verb: SwipeRightVerb): String = when (verb) {
        SwipeRightVerb.COPY -> "COPY"
        SwipeRightVerb.MOVE -> "MOVE"
        SwipeRightVerb.FAVOURITE -> "★"
    }

    /** Sentence-case verb for a swipe right, e.g. for coach-mark captions. */
    fun rightVerb(action: CollectionAction): String = when (action) {
        CollectionAction.COPY -> "Copy"
        CollectionAction.MOVE -> "Move"
    }

    /** Sentence-case verb for a [SwipeRightVerb]. */
    fun rightVerb(verb: SwipeRightVerb): String = when (verb) {
        SwipeRightVerb.COPY -> "Copy"
        SwipeRightVerb.MOVE -> "Move"
        SwipeRightVerb.FAVOURITE -> "Favourite"
    }

    /** Accessibility / caption text naming the swipe-right target. */
    fun rightDescription(action: CollectionAction, folderName: String): String =
        "${rightVerb(action)} to ${folderName.ifBlank { DEFAULT_COLLECTION }}"

    /** Accessibility / caption text naming the [SwipeRightVerb] effect. */
    fun rightDescription(verb: SwipeRightVerb, folderName: String): String = when (verb) {
        SwipeRightVerb.FAVOURITE -> "Favourite photo"
        SwipeRightVerb.COPY,
        SwipeRightVerb.MOVE -> "${rightVerb(verb)} to ${folderName.ifBlank { DEFAULT_COLLECTION }}"
    }


    // ── Swipe left (configured left-swipe action) ────────────────────────

    /** Short, all-caps indicator label for a swipe left. */
    fun leftLabel(action: SwipeAction): String = when (action) {
        SwipeAction.COPY -> "COPY"
        SwipeAction.MOVE -> "MOVE"
        SwipeAction.DELETE -> "DELETE"
    }

    /** Sentence-case verb for a swipe left. */
    fun leftVerb(action: SwipeAction): String = when (action) {
        SwipeAction.COPY -> "Copy"
        SwipeAction.MOVE -> "Move"
        SwipeAction.DELETE -> "Delete"
    }

    /** Accessibility / caption text naming the swipe-left effect. */
    fun leftDescription(action: SwipeAction, folderName: String): String = when (action) {
        SwipeAction.DELETE -> "Delete — undo it with Revert"
        SwipeAction.COPY -> "Copy to ${folderName.ifBlank { DEFAULT_LEFT_SWIPE }}"
        SwipeAction.MOVE -> "Move to ${folderName.ifBlank { DEFAULT_LEFT_SWIPE }}"
    }

    /** True when the left swipe destroys data and should be tinted as an error. */
    fun leftIsDestructive(action: SwipeAction): Boolean = action == SwipeAction.DELETE
}
