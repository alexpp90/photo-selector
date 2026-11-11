package com.phototok.domain

/**
 * Where a swipe's files land: the subfolder [subfolder] of [treeUri], which is the custom folder
 * the user chose ([custom]) or else the source folder being culled. Null [treeUri]: no folder is
 * open and none was chosen, so there is nowhere to copy yet.
 *
 * One resolution for both sides: the feed copies and moves into it, and Settings shows it, so the
 * path a user reads is the path the files go to (REQ-TOK-FEATURE.23, `[USER 2026-11-04]`).
 */
data class SwipeDestination(val treeUri: String?, val subfolder: String, val custom: Boolean)

object SwipeDestinations {
    /** Swipe right (copy or move): `<custom or source>/<Selection folder name>`. */
    fun selection(customUri: String?, sourceUri: String?, subfolder: String): SwipeDestination =
        SwipeDestination(treeUri = customUri ?: sourceUri, subfolder = subfolder, custom = customUri != null)

    /** Swipe left when it copies or moves: `<custom or source>/Rejects`. */
    fun leftSwipe(customUri: String?, sourceUri: String?): SwipeDestination =
        SwipeDestination(treeUri = customUri ?: sourceUri, subfolder = PhotoFolders.LEFT_SWIPE, custom = customUri != null)
}
