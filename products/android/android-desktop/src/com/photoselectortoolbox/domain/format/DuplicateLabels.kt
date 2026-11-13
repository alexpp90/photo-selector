package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.plural
import com.photoselectortoolbox.domain.text.text

/**
 * The words of the Dupes delete, which is held like a cull delete (REQ-AND-ALGO.05): the copies
 * leave the list at once and the disk when the Dupes screen or its folder is left, and UNDO is
 * offered until then. Never "cannot be undone" while undo is offered. Frameduel has no trash
 * ([com.photoselectortoolbox.data.repository.ImageRepository.canTrash] is false), so the held
 * wording is the only one shipped (UX note F9, 2026-11-12).
 */
object DuplicateLabels {

    /** Title of the confirmation every Dupes delete asks for, whatever the count. */
    fun confirmTitle(count: Int): UiText = plural(R.plurals.dialog_title_delete_files, count)

    /** Body of that confirmation: when the files really go. */
    val CONFIRM_BODY: UiText = text(R.string.dialog_body_deleted_on_leave)

    /** Status line and the confirmation card after a held delete. */
    fun deletedMessage(count: Int): UiText = plural(R.plurals.snackbar_deleted_files, count)

    /** The undo control of the confirmation card, as in the cull. */
    val UNDO: UiText = text(R.string.snackbar_action_undo)

    /** Spoken label of the undo control. */
    val UNDO_DESCRIPTION: UiText = text(R.string.cd_undo_delete)

    /** Error after a commit that left files on disk. */
    fun notDeletedMessage(notDeleted: Int, total: Int, names: String): UiText =
        plural(R.plurals.error_files_not_deleted, total, notDeleted, total, names)
}
