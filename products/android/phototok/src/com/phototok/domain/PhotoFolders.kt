package com.phototok.domain

/**
 * Single source of truth for the app-managed folder names created inside
 * user-selected folders (previously duplicated as constants on the repository).
 */
object PhotoFolders {
    const val SELECTION = "Selection"
    const val LEFT_SWIPE = "PhotoTok_LeftSwipe"

    /**
     * Where a Casual-mode swipe right on an SD card or camera copies to: this subfolder of the
     * phone's camera folder. An on-disk name, never translated.
     */
    const val CASUAL_IMPORT = "Jackpix"

    /** Where "Copy favourites to folder…" copies (a folder name on disk, never translated). */
    const val FAVOURITES = "Favourites"

    /**
     * Folders the app (and the sibling app) fill with output. A scan of a source tree skips them by
     * name so a copy is never offered as a new photo. Lower case, compared case-insensitively. A
     * Pro user's own Selection folder name is added by the caller (`excludedFolderNames`).
     */
    val OUTPUT_FOLDER_NAMES: Set<String> = setOf(
        "selection",
        "selected",
        "final",
        "phototok_selection",
        "phototok_leftswipe",
        // Casual SD/camera imports land in DCIM/Camera/Jackpix; the Camera source never re-ingests them.
        CASUAL_IMPORT.lowercase(),
        FAVOURITES.lowercase(),
        ".photoselector-trash",
    )

    /** The caller-supplied exclusion for a Selection folder named [selectionName], none when it is a default one. */
    fun extraOutputNames(selectionName: String): Set<String> =
        selectionName.trim().takeIf { it.isNotEmpty() && it.lowercase() !in OUTPUT_FOLDER_NAMES }
            ?.let(::setOf)
            ?: emptySet()

    /**
     * A folder's name taken from its SAF document id (`primary:DCIM/Camera` → `Camera`), for when
     * the provider does not report a display name. It is the folder's own name, so it needs no
     * translation, unlike the English "Collection" / "Folder" / "Photos" placeholders it replaces
     * (REQ-TOK-UX.05). Blank when the id has no usable last segment.
     */
    fun nameFromDocumentId(documentId: String?): String =
        documentId.orEmpty().substringAfterLast(':').trimEnd('/').substringAfterLast('/')
}
