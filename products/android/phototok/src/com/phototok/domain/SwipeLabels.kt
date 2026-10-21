package com.phototok.domain

import com.phototok.R

/**
 * Wording for the on-screen swipe indicators and coach marks.
 *
 * Jackpix is gesture-first, so the indicator that appears mid-swipe is the
 * only thing telling the user what is about to happen. It must therefore name
 * the action the user has actually configured: a swipe right that *moves* a
 * file must never be labelled "KEEP".
 *
 * Pure (Android-free apart from resource ids) so the wording is unit-testable and every call
 * site — viewer indicators, idle peeks, controls overlay — agrees on one vocabulary. Labels are
 * [UiText]; the UI upper-cases them for the indicators.
 */
object SwipeLabels {

    // ── Swipe right (collection / favourite action) ──────────────────────

    /** Short indicator label for a swipe right (shown in capitals). */
    fun rightLabel(action: CollectionAction): UiText = when (action) {
        CollectionAction.COPY -> UiText.of(R.string.label_swipe_copy)
        CollectionAction.MOVE -> UiText.of(R.string.label_swipe_move)
    }

    /** Short indicator label for a [SwipeRightVerb] (`U-17`). */
    fun rightLabel(verb: SwipeRightVerb): UiText = when (verb) {
        SwipeRightVerb.COPY -> UiText.of(R.string.label_swipe_copy)
        SwipeRightVerb.MOVE -> UiText.of(R.string.label_swipe_move)
        SwipeRightVerb.FAVOURITE -> UiText.of(R.string.label_swipe_favourite)
    }

    /** The folder a swipe right files into, shown under its label; a blank name falls back to Selection. */
    fun rightFolder(folderName: String): String = folderName.ifBlank { PhotoFolders.SELECTION }

    /** Spoken text naming the swipe-right target. */
    fun rightDescription(action: CollectionAction, folderName: String): UiText = when (action) {
        CollectionAction.COPY -> UiText.of(R.string.cd_swipe_copy, rightFolder(folderName))
        CollectionAction.MOVE -> UiText.of(R.string.cd_swipe_move, rightFolder(folderName))
    }

    /** Spoken text naming the [SwipeRightVerb] effect. */
    fun rightDescription(verb: SwipeRightVerb, folderName: String): UiText = when (verb) {
        SwipeRightVerb.FAVOURITE -> UiText.of(R.string.cd_swipe_favourite)
        SwipeRightVerb.COPY -> UiText.of(R.string.cd_swipe_copy, rightFolder(folderName))
        SwipeRightVerb.MOVE -> UiText.of(R.string.cd_swipe_move, rightFolder(folderName))
    }

    // ── Swipe left (configured left-swipe action) ────────────────────────

    /** Short indicator label for a swipe left (shown in capitals). */
    fun leftLabel(action: SwipeAction): UiText = when (action) {
        SwipeAction.COPY -> UiText.of(R.string.label_swipe_copy)
        SwipeAction.MOVE -> UiText.of(R.string.label_swipe_move)
        SwipeAction.DELETE -> UiText.of(R.string.label_swipe_delete)
    }

    /** The folder a swipe left files into, or `null` when it deletes; a blank name falls back to the default. */
    fun leftFolder(action: SwipeAction, folderName: String): String? = when (action) {
        SwipeAction.DELETE -> null
        SwipeAction.COPY, SwipeAction.MOVE -> folderName.ifBlank { PhotoFolders.LEFT_SWIPE }
    }

    /** Spoken text naming the swipe-left effect. */
    fun leftDescription(action: SwipeAction, folderName: String): UiText = when (action) {
        SwipeAction.DELETE -> UiText.of(R.string.cd_swipe_delete)
        SwipeAction.COPY -> UiText.of(R.string.cd_swipe_copy, leftFolder(action, folderName)!!)
        SwipeAction.MOVE -> UiText.of(R.string.cd_swipe_move, leftFolder(action, folderName)!!)
    }

    /** True when the left swipe destroys data and should be tinted as an error. */
    fun leftIsDestructive(action: SwipeAction): Boolean = action == SwipeAction.DELETE
}
