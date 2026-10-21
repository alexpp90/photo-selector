package com.phototok.domain

import com.phototok.R

/**
 * One-time explanations shown the first time the user performs an action.
 *
 * Jackpix is gesture-first, so an action's *effect* (a file was copied, a
 * file was trashed) is invisible unless we say so. Each hint fires exactly
 * once, is persisted by its [key] in DataStore, and can be brought back via
 * "Reset tutorials" in Settings.
 */
enum class FirstRunHint(val key: String) {
    /** First swipe-right (add to collection). No longer shown (2026-10-19, REQ-TOK-UX.04): the guide explains it once. */
    SWIPE_RIGHT("swipe_right"),

    /** First swipe-left when it copies or moves to the custom folder. No longer shown (2026-10-19, REQ-TOK-UX.04): the guide explains it once. */
    SWIPE_LEFT_FOLDER("swipe_left_folder"),

    /** First swipe-left when it deletes. No longer shown (2026-10-19, REQ-TOK-UX.04): the guide explains it once. */
    SWIPE_LEFT_DELETE("swipe_left_delete"),

    /** First vertical swipe. No longer shown (2026-10-19, REQ-TOK-UX.04): the guide shows paging and
     *  the user sees the page change. Kept so persisted seen-keys stay readable. */
    SWIPE_VERTICAL("swipe_vertical"),

    /** First single tap (HUD toggle). */
    TAP_HUD("tap_hud"),

    /** First double tap (zoom). */
    DOUBLE_TAP_ZOOM("double_tap_zoom"),

    /** First use of Revert. No longer shown (2026-10-19, REQ-TOK-UX.01): the restored photo is on
     *  screen. Kept so persisted seen-keys stay readable. */
    REVERT("revert"),

    /** Folder contains RAW+JPEG pairs of the same pictures. */
    RAW_JPEG_PAIRS("raw_jpeg_pairs"),

    /** Active file filter hides the majority of photos in the folder. */
    FILTER_MISMATCH("filter_mismatch");

    companion object {
        fun fromKey(key: String?): FirstRunHint? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Builds the wording for each [FirstRunHint].
 *
 * Kept as a pure function of the user's current settings so the copy always
 * describes what *actually* happened (copy vs. move, which folder) rather than
 * a generic default — and so it is unit-testable without Android. It returns
 * [UiText] (a string-resource id plus arguments); the card resolves it.
 *
 * Copy rules (docs/products/phototok/DESIGN.md, "Copy & messaging"): the title
 * states the rule in at most six words, the message is one short line, and
 * a pointer to Settings is an action button ([offersSettings]), never text.
 */
object FirstRunHintText {

    /** Hints whose behaviour is configurable; the card offers a Settings action. */
    fun offersSettings(hint: FirstRunHint): Boolean = hint in setOf(
        FirstRunHint.SWIPE_RIGHT,
        FirstRunHint.SWIPE_LEFT_FOLDER,
        FirstRunHint.SWIPE_LEFT_DELETE,
    )

    /**
     * The hint's headline. For the swipe hints it names the *rule* ("Swipe right
     * copies to Keepers"), not the event — the copy/move snackbar already says
     * what happened to this photo. A blank folder name falls back to the folder the
     * app really creates ([PhotoFolders]).
     */
    fun title(
        hint: FirstRunHint,
        collectionAction: CollectionAction = CollectionAction.DEFAULT,
        leftSwipeAction: SwipeAction = SwipeAction.DEFAULT,
        collectionFolderName: String = "",
        leftSwipeFolderName: String = "",
        fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
        swipeRightVerb: SwipeRightVerb = collectionAction.toSwipeRightVerb(),
    ): UiText = when (hint) {
        FirstRunHint.SWIPE_RIGHT -> when (swipeRightVerb) {
            SwipeRightVerb.FAVOURITE -> UiText.of(R.string.hint_title_swipe_right_favourite)
            SwipeRightVerb.COPY ->
                UiText.of(R.string.hint_title_swipe_right_copy, collectionFolderName.orDefault(PhotoFolders.SELECTION))
            SwipeRightVerb.MOVE ->
                UiText.of(R.string.hint_title_swipe_right_move, collectionFolderName.orDefault(PhotoFolders.SELECTION))
        }
        FirstRunHint.SWIPE_LEFT_FOLDER -> {
            val folder = leftSwipeFolderName.orDefault(PhotoFolders.LEFT_SWIPE)
            if (leftSwipeAction == SwipeAction.COPY) {
                UiText.of(R.string.hint_title_swipe_left_copy, folder)
            } else {
                UiText.of(R.string.hint_title_swipe_left_move, folder)
            }
        }
        FirstRunHint.SWIPE_LEFT_DELETE -> UiText.of(R.string.hint_title_swipe_left_delete)
        FirstRunHint.SWIPE_VERTICAL -> UiText.of(R.string.hint_title_swipe_vertical)
        FirstRunHint.TAP_HUD -> UiText.of(R.string.hint_title_tap_hud)
        FirstRunHint.DOUBLE_TAP_ZOOM -> UiText.of(R.string.hint_title_double_tap_zoom)
        FirstRunHint.REVERT -> UiText.of(R.string.hint_title_revert)
        FirstRunHint.RAW_JPEG_PAIRS -> UiText.of(R.string.hint_title_raw_jpeg_pairs)
        FirstRunHint.FILTER_MISMATCH -> when (fileTypeFilter) {
            FileTypeFilter.RAW -> UiText.of(R.string.hint_title_filter_raw)
            FileTypeFilter.JPG -> UiText.of(R.string.hint_title_filter_jpeg)
            FileTypeFilter.ALL,
            FileTypeFilter.ONE_PER_SHOT,
            -> UiText.of(R.string.hint_title_filter_active)
        }
    }

    /** Optional second line; `null` when the title says everything. */
    fun message(
        hint: FirstRunHint,
        fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
    ): UiText? = when (hint) {
        FirstRunHint.RAW_JPEG_PAIRS -> UiText.of(R.string.hint_body_raw_jpeg_pairs)
        FirstRunHint.FILTER_MISMATCH -> when (fileTypeFilter) {
            FileTypeFilter.RAW -> UiText.of(R.string.hint_body_filter_mostly_jpeg)
            FileTypeFilter.JPG -> UiText.of(R.string.hint_body_filter_mostly_raw)
            FileTypeFilter.ALL,
            FileTypeFilter.ONE_PER_SHOT,
            -> UiText.of(R.string.hint_body_filter_hidden)
        }
        FirstRunHint.SWIPE_RIGHT,
        FirstRunHint.SWIPE_LEFT_FOLDER,
        FirstRunHint.SWIPE_LEFT_DELETE, // the controls guide names Undo; no second line
        FirstRunHint.SWIPE_VERTICAL,
        FirstRunHint.TAP_HUD,
        FirstRunHint.DOUBLE_TAP_ZOOM,
        FirstRunHint.REVERT,
        -> null
    }

    private fun String.orDefault(default: String): UiText = UiText.raw(ifBlank { default })
}
