package com.photoselectortoolbox.data.model

/**
 * What Settings may show about a folder, as the storage layer found it just now
 * (`ImageRepository.describeFolder`). Nothing here is an operation: [displayPath] is a
 * label built by `SafTreeDisplayPath`, never a path to act on.
 *
 * @property displayPath the readable path, outermost first (`Internal storage`, `DCIM`, `Trip`); never empty.
 * @property name the folder's own display name from its provider, or null when it did not resolve.
 * @property hasPersistedGrant whether the app holds a persisted read/write grant covering the tree,
 *   which is what keeps it usable after a restart.
 * @property exists whether the tree resolves to an existing directory now.
 * @property canWrite whether the provider reports the directory as writable.
 * @property hasSelectionMarker whether it holds the `.photoselector-selection` marker, i.e. is itself
 *   a Selection folder (`U-32`).
 */
data class FolderDescription(
    val uri: String,
    val displayPath: List<String>,
    val name: String? = null,
    val hasPersistedGrant: Boolean = false,
    val exists: Boolean = false,
    val canWrite: Boolean = false,
    val hasSelectionMarker: Boolean = false,
) {
    /** How the folder stands as a destination for Copy and Move. */
    val reachability: FolderReachability
        get() = when {
            !hasPersistedGrant -> FolderReachability.NO_PERMISSION
            !exists -> FolderReachability.MISSING
            !canWrite -> FolderReachability.READ_ONLY
            else -> FolderReachability.REACHABLE
        }

    /** Whether Copy and Move can file into it, now and after a restart. */
    val isReachable: Boolean get() = reachability == FolderReachability.REACHABLE
}

/** How a folder stands as a destination, in the order it is checked. */
enum class FolderReachability {
    REACHABLE,

    /** No persisted grant: the folder may work in this session but not after a restart. */
    NO_PERMISSION,

    /** Deleted, moved, or on a volume that is not mounted. */
    MISSING,

    /** The provider will not let the app create files there. */
    READ_ONLY,
}
