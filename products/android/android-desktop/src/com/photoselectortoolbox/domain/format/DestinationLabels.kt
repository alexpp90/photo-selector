package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.data.model.FolderReachability
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.DestinationPreview

/**
 * The words of Settings → "Where photos go" (`[USER 2026-11-04]`), generated from a
 * [DestinationPreview] — the resolution Copy and Move use — so the line on screen can never
 * name a folder files do not go to. Pure, so every wording is unit-tested.
 */
object DestinationLabels {

    const val SECTION_TITLE = "Where photos go"
    const val WORKING_FOLDER = "Working folder"
    const val CUSTOM_FOLDER = "Custom folder"
    const val CHOOSE = "Choose"
    const val CHANGE = "Change"
    const val RAW_AND_JPEG_FOLDERS = "RAW and JPEG folders"

    /** The separator `SafTreeDisplayPath` joins a path with, and the end of a sorted destination. */
    const val SEPARATOR = " › "
    const val RAW_JPEG_SUFFIX = "RAW · JPEG"

    /** Shown in place of a path for a custom folder that will not take files. */
    const val FOLDER_UNAVAILABLE = "Folder unavailable"

    /**
     * Second-pass note: the opened folder is itself a Selection folder, so photos go into the
     * second-pass folder inside it. The path line already ends with that folder's name.
     */
    const val SECOND_PASS_NOTE = "Second pass — the open folder is a Selection folder"

    /**
     * Where the next Copy or Move goes, as a path: `Internal storage › DCIM › Trip › Selection`,
     * ending `› RAW · JPEG` only while sorting is on. Without a folder to name (Working folder
     * with none open) it says what will happen instead of inventing a path.
     */
    fun pathLine(preview: DestinationPreview): String? {
        val segments = preview.segments
        if (segments == null) {
            return if (preview.mode == DestinationMode.WORKING_FOLDER) {
                "«${preview.folderName}» inside the folder you open — no folder open yet"
            } else {
                null
            }
        }
        val path = segments.joinToString(SEPARATOR)
        return if (preview.sortsIntoRawAndJpeg) "$path$SEPARATOR$RAW_JPEG_SUFFIX" else path
    }

    /** The path line as a screen reader should say it. */
    fun pathDescription(preview: DestinationPreview): String? {
        val segments = preview.segments ?: return pathLine(preview)
        val path = segments.joinToString(", ")
        return "Photos go to $path" + if (preview.sortsIntoRawAndJpeg) ", in RAW and JPEG subfolders" else ""
    }

    /** Why a custom folder is unavailable, from what the storage layer found. */
    fun unavailableReason(reachability: FolderReachability): String? = when (reachability) {
        FolderReachability.REACHABLE -> null
        FolderReachability.NO_PERMISSION -> "No permission — choose the folder again"
        FolderReachability.MISSING -> "The folder is missing"
        FolderReachability.READ_ONLY -> "The folder is read-only"
    }

    /**
     * What a filing confirmation names when the destination is a custom folder: its name and
     * the Selection folder, `Keepers › Selection` (the caller wraps it in guillemets).
     */
    fun customDestinationName(customFolderName: String, selectionFolderName: String): String =
        "$customFolderName$SEPARATOR$selectionFolderName"
}
