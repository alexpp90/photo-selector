package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.R
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.plural
import com.photoselectortoolbox.domain.text.text
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.DerivativeDelete
import com.photoselectortoolbox.domain.curation.ImageSlot
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import com.photoselectortoolbox.domain.format.DestinationLabels
import com.photoselectortoolbox.domain.interaction.FilingAction

/** A line on screen and what a screen reader says for it, when the two differ. */
data class SpokenText(val visible: UiText, val spoken: UiText)

/**
 * File-action confirmations that name the photo, its companions and where it went
 * ([USER 2026-11-12] decision #33 (1)): "Moved IMG_0001 +2 · Selection", spoken "Moved
 * IMG_0001.JPG and 2 companion files to Selection". One formatter for the card and the delete
 * dialog, visible and spoken together, so the two cannot drift.
 *
 * The word "companion" is spoken only; on screen "+2" says it in the card's budget. A long name
 * is shortened in the middle, so "+N" and the destination are never the part that is cut.
 */
object CompanionConfirmation {

    /** Longest file stem shown before it is shortened in the middle. */
    const val MAX_STEM = 24

    /**
     * The card after a move or copy of [image] with all its companions (a bundle files whole,
     * REQ-AND-PLATFORM.06). [destination] is [destinationName]'s result.
     */
    fun filed(action: FilingAction, image: ImageItem, destination: String): SpokenText {
        val n = image.companions.size
        val copy = action == FilingAction.COPY
        val stem = shortStem(image.fileName)
        return SpokenText(
            visible = if (n > 0) {
                text(if (copy) R.string.snackbar_copied_stem_companions else R.string.snackbar_moved_stem_companions, stem, n, destination)
            } else {
                text(if (copy) R.string.snackbar_copied_stem else R.string.snackbar_moved_stem, stem, destination)
            },
            spoken = if (n > 0) {
                plural(if (copy) R.plurals.cd_copied_with_companions else R.plurals.cd_moved_with_companions, n, image.fileName, n, destination)
            } else {
                text(if (copy) R.string.cd_copied_file_to else R.string.cd_moved_file_to, image.fileName, destination)
            },
        )
    }

    /**
     * Where a filed photo goes, as the confirmations name it: the configured Selection folder, or
     * «Keepers › Selection» for a custom destination (`SelectionActionLabels.confirmation`).
     */
    fun destinationName(selectionFolderName: String, customFolderName: String?): String {
        val folder = FilingAction.selectionFolderOrDefault(selectionFolderName)
        return if (customFolderName.isNullOrBlank()) {
            folder
        } else {
            "«${DestinationLabels.customDestinationName(customFolderName, folder)}»"
        }
    }

    /**
     * The files a Delete of [image] takes besides the photo itself: all its companions, or for a
     * frame led by an edit only the edit's own sidecars (`DerivativeDelete`).
     */
    fun deletedCompanions(image: ImageItem): List<CompanionFile> =
        ImageSlot(image, 0, kept = DerivativeDelete.remainderOf(image)).deletedFiles
            .filterNot { it.uri == image.uri }

    /** The delete dialog's title when the photo takes companions with it; null when it takes none. */
    fun deleteTitle(image: ImageItem): SpokenText? {
        val n = deletedCompanions(image).size
        if (n == 0) return null
        return SpokenText(
            visible = text(R.string.dialog_title_delete_stem_companions, shortStem(image.fileName), n),
            spoken = plural(R.plurals.cd_delete_with_companions, n, image.fileName, n),
        )
    }

    /** "Also deletes JPG, XMP": the kinds of file the delete takes with the photo, or null. */
    fun deleteAlso(image: ImageItem): UiText? {
        val kinds = deletedCompanions(image)
            .map { it.fileName.substringAfterLast('.', "").uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (kinds.isEmpty()) null else text(R.string.dialog_body_also_deletes, kinds.joinToString(", "))
    }

    /** The file name without its extension, shortened in the middle past [MAX_STEM]. */
    fun shortStem(fileName: String): String {
        val stem = fileName.substringBeforeLast('.', fileName)
        if (stem.length <= MAX_STEM) return stem
        val head = (MAX_STEM - 1) / 2
        val tail = MAX_STEM - 1 - head
        return stem.take(head) + "…" + stem.takeLast(tail)
    }
}

/** This state showing the card of a move or copy of [image] (decision #33 (1)). */
internal fun SelectorUiState.withFilingSnackbar(action: FilingAction, image: ImageItem): SelectorUiState {
    val line = CompanionConfirmation.filed(
        action = action,
        image = image,
        destination = CompanionConfirmation.destinationName(filingFolderName, customDestinationName),
    )
    // A move's undo never expires and a copy has none, so neither is a deadline. UNDO is
    // populated once the transfer reports where the file went.
    val lifetime = if (action == FilingAction.MOVE) SnackbarLifetime.MOVE else SnackbarLifetime.COPY
    return withSnackbar(line.visible, lifetime).copy(snackbarSpoken = line.spoken)
}
