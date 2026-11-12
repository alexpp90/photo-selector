package com.photoselectortoolbox.domain.curation

import java.util.Locale

/**
 * Whether a copy or move fits where it is going (REQ-AND-WORKFLOW.02, backlog 2026-11-11). Before
 * a transfer starts, the bundle's size is compared with the free space the destination's document
 * provider reports for its root (`DocumentsContract.Root.COLUMN_AVAILABLE_BYTES`); a transfer that
 * does not fit is refused before it writes anything, instead of failing halfway on a full card.
 *
 * Pure: the decision and its words. Reading the provider is the repository's.
 */
object TransferSpace {

    /** Where a transfer goes, as far as space is concerned: the provider root and its free bytes. */
    data class Volume(val authority: String, val rootDocumentId: String, val availableBytes: Long?)

    private const val REFUSAL_PREFIX = "Not enough space"

    /**
     * True when the transfer must be refused: the destination reports its free space and it is less
     * than [neededBytes]. A move on one volume needs no space (the provider renames, or copies and
     * deletes file by file); a destination that reports nothing is not refused.
     */
    fun refuses(neededBytes: Long, source: Volume?, destination: Volume?, isMove: Boolean): Boolean {
        val available = destination?.availableBytes ?: return false
        if (isMove && source != null && sameVolume(source, destination)) return false
        return neededBytes > available
    }

    fun sameVolume(a: Volume, b: Volume): Boolean = a.authority == b.authority && a.rootDocumentId == b.rootDocumentId

    /**
     * The provider root a document id lives under: the one whose own document id is that id or its
     * longest prefix ending at a separator (`primary:` for `primary:DCIM/Shoot`).
     */
    fun rootFor(documentId: String, rootDocumentIds: Collection<String>): String? =
        rootDocumentIds
            .filter { root ->
                documentId == root ||
                    (documentId.startsWith(root) && (root.endsWith(":") || root.endsWith("/"))) ||
                    documentId.startsWith("$root/") || documentId.startsWith("$root:")
            }
            .maxByOrNull { it.length }

    /** The refusal, shown as it is: what the transfer needs and what is free. */
    fun refusalMessage(neededBytes: Long, availableBytes: Long, isMove: Boolean): String =
        "$REFUSAL_PREFIX: needs ${size(neededBytes)}, ${size(availableBytes)} free. " +
            "Nothing was ${if (isMove) "moved" else "copied"}."

    /** True for a message [refusalMessage] wrote, which is shown to the user as it is. */
    fun isRefusal(message: String?): Boolean = message?.startsWith(REFUSAL_PREFIX) == true

    private fun size(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> String.format(Locale.ROOT, "%.1f GB", mb / 1024)
            bytes > 0 && mb < 1 -> "under 1 MB"
            else -> String.format(Locale.ROOT, "%.0f MB", mb)
        }
    }
}
