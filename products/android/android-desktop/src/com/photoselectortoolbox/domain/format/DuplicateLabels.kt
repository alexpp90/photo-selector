package com.photoselectortoolbox.domain.format

/**
 * The words of the Dupes delete, which is held like a cull delete (REQ-AND-ALGO.05): the copies
 * leave the list at once and the disk when the Dupes screen or its folder is left, and UNDO is
 * offered until then. Never "cannot be undone" while undo is offered. Frameduel has no trash
 * ([com.photoselectortoolbox.data.repository.ImageRepository.canTrash] is false), so the held
 * wording is the only one shipped (UX note F9, 2026-11-12).
 */
object DuplicateLabels {

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

    /** Title of the confirmation every Dupes delete asks for, whatever the count. */
    fun confirmTitle(count: Int): String = "Delete ${files(count)}?"

    /** Body of that confirmation: when the files really go. */
    const val CONFIRM_BODY = "Deleted when you leave this screen."

    /** Status line and the confirmation card after a held delete. */
    fun deletedMessage(count: Int): String = "Deleted ${files(count)}"

    /** The undo control of the confirmation card, as in the cull. */
    const val UNDO = "UNDO"

    /** Spoken label of the undo control. */
    const val UNDO_DESCRIPTION = "Undo delete"

    /** Error after a commit that left files on disk. */
    fun notDeletedMessage(notDeleted: Int, total: Int, names: String): String =
        "Could not delete $notDeleted of $total files: $names"
}
