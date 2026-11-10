package com.phototok.data.model

/**
 * What Settings needs to show a folder as a readable path: the provider's name for the tree and,
 * on a removable volume, the volume's label (`StorageVolume.getDescription`). [lost] is true only
 * when the folder is known to be unusable — its grant was revoked, or it was deleted or unmounted —
 * never as a default, so an unknown folder is not flagged.
 */
data class FolderLookup(
    val providerName: String? = null,
    val volumeLabel: String? = null,
    val lost: Boolean = false,
)
