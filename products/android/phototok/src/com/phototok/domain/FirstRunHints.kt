package com.phototok.domain

/**
 * One-time explanations shown the first time the user performs an action.
 *
 * Photo-Tok is gesture-first, so an action's *effect* (a file was copied, a
 * file was trashed) is invisible unless we say so. Each hint fires exactly
 * once, is persisted by its [key] in DataStore, and can be brought back via
 * "Reset tutorials" in Settings.
 */
enum class FirstRunHint(val key: String) {
    /** First swipe-right (add to collection). */
    SWIPE_RIGHT("swipe_right"),

    /** First swipe-left when it copies or moves to the custom folder. */
    SWIPE_LEFT_FOLDER("swipe_left_folder"),

    /** First swipe-left when it deletes. */
    SWIPE_LEFT_DELETE("swipe_left_delete"),

    /** First vertical swipe (browsing the feed). */
    SWIPE_VERTICAL("swipe_vertical"),

    /** First single tap (HUD toggle). */
    TAP_HUD("tap_hud"),

    /** First double tap (zoom). */
    DOUBLE_TAP_ZOOM("double_tap_zoom"),

    /** First use of Revert. */
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
 * a generic default — and so it is unit-testable without Android.
 *
 * Copy rules (docs/products/phototok/DESIGN.md, "Copy & messaging"): the title
 * states what happened in a few words, the message is one short sentence, and
 * a pointer to Settings is an action button ([offersSettings]), never text.
 */
object FirstRunHintText {

    /** Fallback wording when no custom folder name is known yet. */
    private const val DEFAULT_COLLECTION = "collection"
    private const val DEFAULT_LEFT_SWIPE = "folder"

    /** Hints whose behaviour is configurable; the card offers a Settings action. */
    fun offersSettings(hint: FirstRunHint): Boolean = hint in setOf(
        FirstRunHint.SWIPE_RIGHT,
        FirstRunHint.SWIPE_LEFT_FOLDER,
        FirstRunHint.SWIPE_LEFT_DELETE,
    )

    /**
     * The hint's headline. For the swipe hints it names the *rule* ("Swipe right
     * copies to Keepers"), not the event — the copy/move snackbar already says
     * what happened to this photo.
     */
    fun title(
        hint: FirstRunHint,
        collectionAction: CollectionAction = CollectionAction.DEFAULT,
        leftSwipeAction: SwipeAction = SwipeAction.DEFAULT,
        collectionFolderName: String = "",
        leftSwipeFolderName: String = "",
        fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
        swipeRightVerb: SwipeRightVerb = collectionAction.toSwipeRightVerb(),
    ): String = when (hint) {
        FirstRunHint.SWIPE_RIGHT -> when (swipeRightVerb) {
            SwipeRightVerb.FAVOURITE -> "Swipe right marks a favourite"
            SwipeRightVerb.COPY -> "Swipe right copies to ${collectionFolderName.ifBlank { DEFAULT_COLLECTION }}"
            SwipeRightVerb.MOVE -> "Swipe right moves to ${collectionFolderName.ifBlank { DEFAULT_COLLECTION }}"
        }
        FirstRunHint.SWIPE_LEFT_FOLDER -> {
            val verb = if (leftSwipeAction == SwipeAction.COPY) "copies" else "moves"
            "Swipe left $verb to ${leftSwipeFolderName.ifBlank { DEFAULT_LEFT_SWIPE }}"
        }
        FirstRunHint.SWIPE_LEFT_DELETE -> "Swipe left deletes"
        FirstRunHint.SWIPE_VERTICAL -> "Your place is saved per folder"
        FirstRunHint.TAP_HUD -> "Tap again to show the overlays"
        FirstRunHint.DOUBLE_TAP_ZOOM -> "Drag to pan, double-tap to reset"
        FirstRunHint.REVERT -> "Revert undoes the last delete only"
        FirstRunHint.RAW_JPEG_PAIRS -> "RAW + JPEG pairs"
        FirstRunHint.FILTER_MISMATCH -> when (fileTypeFilter) {
            FileTypeFilter.RAW -> "RAW only"
            FileTypeFilter.JPG -> "JPEG only"
            FileTypeFilter.ALL,
            FileTypeFilter.ONE_PER_SHOT,
            -> "Filter active"
        }
    }

    /** Optional second line; empty when the title says everything. */
    fun message(
        hint: FirstRunHint,
        fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
    ): String = when (hint) {
        FirstRunHint.SWIPE_LEFT_DELETE -> "Tap Revert to undo."
        FirstRunHint.RAW_JPEG_PAIRS -> "Link them or show one format."
        FirstRunHint.FILTER_MISMATCH -> when (fileTypeFilter) {
            FileTypeFilter.RAW -> "Most photos here are JPEG."
            FileTypeFilter.JPG -> "Most photos here are RAW."
            FileTypeFilter.ALL,
            FileTypeFilter.ONE_PER_SHOT,
            -> "Some photos are hidden."
        }
        FirstRunHint.SWIPE_RIGHT,
        FirstRunHint.SWIPE_LEFT_FOLDER,
        FirstRunHint.SWIPE_VERTICAL,
        FirstRunHint.TAP_HUD,
        FirstRunHint.DOUBLE_TAP_ZOOM,
        FirstRunHint.REVERT,
        -> ""
    }
}
