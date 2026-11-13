package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.text
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
    fun refusalMessage(neededBytes: Long, availableBytes: Long, isMove: Boolean): UiText =
        text(
            if (isMove) R.string.error_not_enough_space_move else R.string.error_not_enough_space_copy,
            size(neededBytes),
            size(availableBytes),
        )

    /**
     * The refusal as it travels through `FileOperationResult.error` (a `:saf` type that carries a
     * String): a code, not English, so the view model can say it in the UI's language.
     */
    fun refusalCode(neededBytes: Long, availableBytes: Long, isMove: Boolean): String =
        "$REFUSAL_CODE$neededBytes:$availableBytes:$isMove"

    /** The refusal [refusalCode] wrote, in words; null for any other error. */
    fun refusalOf(error: String?): UiText? {
        val parts = error?.takeIf { it.startsWith(REFUSAL_CODE) }?.removePrefix(REFUSAL_CODE)?.split(':') ?: return null
        val needed = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val available = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return refusalMessage(needed, available, parts.getOrNull(2) == "true")
    }

    private const val REFUSAL_CODE = "transfer-space-refused:"

    private fun size(bytes: Long): UiText {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 1024 -> text(R.string.label_size_gb, String.format(Locale.ROOT, "%.1f", mb / 1024))
            bytes > 0 && mb < 1 -> text(R.string.label_size_under_1_mb)
            else -> text(R.string.label_size_mb, String.format(Locale.ROOT, "%.0f", mb))
        }
    }
}
