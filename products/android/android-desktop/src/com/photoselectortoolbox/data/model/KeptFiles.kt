package com.photoselectortoolbox.data.model

import android.net.Uri
import android.provider.DocumentsContract

/**
 * The files a successful Copy or Move of this session put into the Selection, for the
 * safe-to-format line (REQ-AND-WORKFLOW.02, backlog 2026-11-12): each keyed by the document the
 * transfer wrote — its provider document id, the key [SelectionFolderState.filesByDocumentId] is
 * listed under — with the size of the source file it came from.
 *
 * A file name is no key: a Selection holding `DSC_0003.ARW` from an earlier shoot in `RAW/` and
 * this session's copy at its top level has two files of one name, and keyed by name the old one
 * stood in for a copy that was missing.
 */
object KeptFiles {

    /** Starts a key no listing produces: a member whose destination is unknown never verifies. */
    internal const val UNRECORDED = "unrecorded:"

    /**
     * The members of one transferred bundle, [files] in `ImageItem.allFiles` order, against the
     * [destinationUris] the transfer reported for them (the `:saf` engine lists them in source
     * order). When the two do not line up, every member is recorded as unverifiable: the Done
     * card then says files are missing, never that the card is safe to format.
     */
    fun of(
        files: List<CompanionFile>,
        destinationUris: List<String>,
        documentIdOf: (String) -> String? = ::documentIdOf,
    ): Map<String, Long> {
        if (files.size != destinationUris.size) {
            return files.associate { UNRECORDED + it.uri to it.fileSize }
        }
        return files.zip(destinationUris).associate { (file, destination) ->
            (documentIdOf(destination) ?: (UNRECORDED + file.uri)) to file.fileSize
        }
    }

    /**
     * The keys [of] recorded for the same transfer, to forget the files a move undo took back out:
     * the same derivation both ways, so an unrecorded member is forgotten too.
     */
    fun keysOf(
        files: List<CompanionFile>,
        destinationUris: List<String>,
        documentIdOf: (String) -> String? = ::documentIdOf,
    ): Set<String> = of(files, destinationUris, documentIdOf).keys

    /** The provider's document id of a document URI, or null when [uri] is not one. */
    fun documentIdOf(uri: String): String? = try {
        DocumentsContract.getDocumentId(Uri.parse(uri))
    } catch (e: IllegalArgumentException) {
        // Not a document URI: no listing can match it, which is the safe answer.
        null
    }
}
