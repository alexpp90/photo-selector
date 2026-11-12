package com.photoselectortoolbox.data.model

/**
 * The Selection folder a Copy or Move from the open folder files into, as it is on disk now
 * (`ImageRepository.selectionFolder`): where it is, what it holds, and whether it is on the
 * source's own volume. Read for the end of a cull — *Open Selection* and the safe-to-format
 * line (REQ-AND-WORKFLOW.02).
 */
data class SelectionFolderState(
    /** The folder, as a document inside the destination's tree: openable as a working folder. */
    val uri: String,
    val name: String,
    /** Every file in it and in its direct subfolders (`RAW/`, `JPEG/`), by name, with its size. */
    val filesWithSizes: Map<String, Long>,
    /** Whether it is on the same provider root as the culled folder; null when either is unknown. */
    val onSourceVolume: Boolean?,
)
