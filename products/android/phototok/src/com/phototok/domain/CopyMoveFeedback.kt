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
     * File name of a document URI string: the last path segment of the (percent-decoded) document
     * id, so `…/document/primary%3ADCIM%2FIMG_1.JPG` gives `IMG_1.JPG`.
     */
    fun displayNameOf(uri: String): String {
        val last = uri.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(last, "UTF-8")
        } catch (_: Exception) {
            last
        }
        return decoded.substringAfterLast('/').substringAfterLast(':')
    }

    /** True when the message reports an error (any file failed or only partially moved). */
    fun isError(failed: Int, partial: Int = 0): Boolean = failed > 0 || partial > 0
}
