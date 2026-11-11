package com.phototok.data.model

/**
 * What Settings needs to know about a custom destination folder beyond its path: [lost] is true only
 * when the folder is known to be unusable — its grant was revoked, or it was deleted or unmounted —
 * never as a default, so an unknown folder is not flagged (REQ-TOK-FEATURE.23).
 */
data class FolderLookup(
    val lost: Boolean = false,
)
