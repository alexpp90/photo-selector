package com.photoselectortoolbox.domain.format

import android.content.Context
import com.photoselector.core.storage.SafTreeDisplayPath
import com.photoselectortoolbox.R
import com.photoselectortoolbox.data.model.FolderReachability
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.DestinationPreview
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.raw
import com.photoselectortoolbox.domain.text.text

/**
 * The words of Settings → "Where photos go" (`[USER 2026-11-04]`), generated from a
 * [DestinationPreview] — the resolution Copy and Move use — so the line on screen can never
 * name a folder files do not go to. Pure, so every wording is unit-tested.
 */
object DestinationLabels {

    /** The separator `SafTreeDisplayPath` joins a path with, and the end of a sorted destination. */
    const val SEPARATOR = " › "

    /**
     * The words `SafTreeDisplayPath` builds a folder's path from, in the UI's language
     * (`ai/rules/ui-text.md` §4: the shared module's text is the product's resources at every
     * call site).
     */
    fun pathLabels(context: Context): SafTreeDisplayPath.Labels = SafTreeDisplayPath.Labels(
        internalStorage = context.getString(R.string.label_internal_storage),
        documents = context.getString(R.string.label_documents),
        removableVolume = { uuid -> "${context.getString(R.string.label_sd_card)} ($uuid)" },
        unknownFolder = context.getString(R.string.label_folder),
    )

    /**
     * Where the next Copy or Move goes, as a path: `Internal storage › DCIM › Trip › Selection`,
     * ending `› RAW · JPEG` only while sorting is on. Without a folder to name (Working folder
     * with none open) it says what will happen instead of inventing a path.
     */
    fun pathLine(preview: DestinationPreview): UiText? {
        val segments = preview.segments
        if (segments == null) {
            return if (preview.mode == DestinationMode.WORKING_FOLDER) {
                text(R.string.setting_summary_no_folder_open, preview.folderName)
            } else {
                null
            }
        }
        val path = raw(segments.joinToString(SEPARATOR))
        return if (preview.sortsIntoRawAndJpeg) UiText.Joined(listOf(path, text(R.string.label_raw_jpeg_suffix)), SEPARATOR) else path
    }

    /** The path line as a screen reader should say it. */
    fun pathDescription(preview: DestinationPreview): UiText? {
        val segments = preview.segments ?: return pathLine(preview)
        val path = segments.joinToString(", ")
        return text(if (preview.sortsIntoRawAndJpeg) R.string.cd_destination_path_sorted else R.string.cd_destination_path, path)
    }

    /** Why a custom folder is unavailable, from what the storage layer found. */
    fun unavailableReason(reachability: FolderReachability): UiText? = when (reachability) {
        FolderReachability.REACHABLE -> null
        FolderReachability.NO_PERMISSION -> text(R.string.error_folder_no_permission)
        FolderReachability.MISSING -> text(R.string.error_folder_missing)
        FolderReachability.READ_ONLY -> text(R.string.error_folder_read_only)
    }


    /**
     * What a filing confirmation names when the destination is a custom folder: its name and
     * the Selection folder, `Keepers › Selection` (the caller wraps it in guillemets).
     */
    fun customDestinationName(customFolderName: String, selectionFolderName: String): String =
        "$customFolderName$SEPARATOR$selectionFolderName"
}
