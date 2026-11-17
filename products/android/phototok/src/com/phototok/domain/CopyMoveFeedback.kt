package com.phototok.domain

import com.phototok.R

/**
 * Builds the user-facing feedback message for a copy/move action, including
 * partial-failure reporting when sibling (related) files are processed.
 * Pure logic so it is unit-testable; the message is a [UiText].
 */
object CopyMoveFeedback {

    /**
     * @param isCopy true for copy, false for move
     * @param destination the destination folder's name (a [UiText.Raw] for a configured folder)
     * @param succeeded number of files processed successfully
     * @param failed number of files that failed
     * @param relatedCount number of sibling files included beyond the primary image
     * @param partial number of move operations where the copy succeeded on disk
     *   but removing the source failed (`FileOperationResult.partial`)
     * @param renamedTo the name the primary file got when the transfer had to add a collision
     *   suffix (`-2`, `-3`, …), or null when it kept its name; only reported when everything
     *   succeeded ("Moved as IMG_0001-2.jpg +1")
     */
    fun message(
        isCopy: Boolean,
        destination: UiText,
        succeeded: Int,
        failed: Int,
        relatedCount: Int,
        partial: Int = 0,
        renamedTo: String? = null,
    ): UiText {
        val hasRelated = relatedCount > 0
        if (renamedTo != null && failed == 0 && partial == 0) {
            return when {
                isCopy && hasRelated -> UiText.of(R.string.snackbar_copied_renamed_related, renamedTo, relatedCount)
                isCopy -> UiText.of(R.string.snackbar_copied_renamed, renamedTo)
                hasRelated -> UiText.of(R.string.snackbar_moved_renamed_related, renamedTo, relatedCount)
                else -> UiText.of(R.string.snackbar_moved_renamed, renamedTo)
            }
        }
        if (!isCopy && partial > 0 && failed == 0) {
            return UiText.of(R.string.error_move_partial, destination)
        }
        val totalFailed = failed + partial
        return when {
            totalFailed == 0 -> when {
                isCopy && hasRelated -> UiText.of(R.string.snackbar_copied_related, destination, relatedCount)
                isCopy -> UiText.of(R.string.snackbar_copied, destination)
                hasRelated -> UiText.of(R.string.snackbar_moved_related, destination, relatedCount)
                else -> UiText.of(R.string.snackbar_moved, destination)
            }
            succeeded == 0 ->
                UiText.of(if (isCopy) R.string.error_copy_failed else R.string.error_move_failed, destination)
            else -> UiText.of(
                if (isCopy) R.string.error_copied_some else R.string.error_moved_some,
                succeeded,
                succeeded + totalFailed,
                destination,
            )
        }
    }

    /**
     * The name the primary file got when the transfer had to add a collision suffix, or null when it
     * kept its name or the engine could not read it. [destinationName] is the display name the
     * `:saf` engine read back from the written document (`FileOperationResult.destinationName`);
     * a name is never derived from the destination URI, whose last segment is the provider's
     * document id (`msf:123`, Drive and camera ids), not the file name (H-JP-2).
     */
    fun renamedName(sourceName: String, destinationName: String?): String? =
        destinationName?.takeIf { it.isNotEmpty() && it != sourceName }

    /** True when the message reports an error (any file failed or only partially moved). */
    fun isError(failed: Int, partial: Int = 0): Boolean = failed > 0 || partial > 0
}
