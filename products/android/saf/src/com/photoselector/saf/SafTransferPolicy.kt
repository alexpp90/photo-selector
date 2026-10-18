package com.photoselector.saf

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** The subfolder of the Selection a file is sorted into when File Sorting is on. */
enum class SelectionSubfolder { RAW, JPEG, ROOT }

/**
 * Which [SelectionSubfolder] a file of a bundle goes to. The rule is the product's (it follows
 * the product's format table); [bundleNames] lets a sidecar follow the RAW it belongs to.
 */
fun interface SelectionSubfolderRule {
    fun subfolderFor(fileName: String, bundleNames: List<String>): SelectionSubfolder
}

/** How the members of one companion bundle are moved. */
enum class BundleAtomicity {
    /**
     * Copy and verify every member before any source is deleted; one failed copy removes every
     * copy made so far and fails the whole bundle. A source that cannot be deleted afterwards
     * makes the whole bundle partial, with [FileOperationResult.copiesOfSurvivingOriginals].
     * A single file whose name is free may use the provider's atomic move. (Android Desktop.)
     */
    ALL_OR_NOTHING,

    /**
     * Each member is copied, verified and (for a move) its source deleted on its own, sharing the
     * bundle's collision suffix; every member may use the provider's atomic move. (PhotoTok.)
     */
    PER_FILE,
}

/** When the Selection marker file (`.photoselector-selection`) is written into the Selection folder. */
enum class MarkerTiming {
    /** As soon as the Selection folder is found or created, before anything is transferred. (PhotoTok.) */
    WHEN_FOLDER_RESOLVED,

    /** Only once at least one file of the transfer landed (in full or partially). (Android Desktop.) */
    AFTER_FIRST_TRANSFER,
}

/**
 * The product's configuration of the engine. Nothing here is a fork: each field names one
 * product difference the engine implements both sides of.
 */
data class SafTransferPolicy(
    val bundleAtomicity: BundleAtomicity,
    val markerTiming: MarkerTiming,
    /**
     * True for a destination the engine must `fsync` after writing (removable media such as an
     * SD card or USB OTG drive, where an unflushed copy can be lost when the card is pulled).
     * Best effort: a provider without file-descriptor sync is not an error.
     */
    val syncDestination: (Uri) -> Boolean = { false },
)

/**
 * Where a transfer lands: the folder [folderName] (`Selection`, `PhotoTok_LeftSwipe`, …) inside
 * the tree [rootUri], found or created on first use.
 *
 * [folderNameIn] may choose another name once the destination root is resolved — Android
 * Desktop's second pass files into `Final` when the root already is a Selection folder. It runs
 * under the destination lock. [folderName] names the folder in feedback when the root itself
 * cannot be resolved.
 */
class SelectionDestination(
    val rootUri: Uri,
    val folderName: String,
    val folderNameIn: (destinationRoot: DocumentFile) -> String = { folderName },
)
