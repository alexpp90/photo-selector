package com.photoselectortoolbox.viewmodel

import com.photoselector.core.storage.SourceKind
import com.photoselectortoolbox.data.model.SelectionFolderState
import com.photoselectortoolbox.data.repository.ImageRepositoryImpl

/** The safe-to-format line of the Done card (REQ-AND-WORKFLOW.02, `U-33`). */
sealed interface SafeToFormatLine {
    data object Checking : SafeToFormatLine
    data object Safe : SafeToFormatLine
    data class Missing(val count: Int) : SafeToFormatLine
}

/**
 * Whether the Done card says the card can be formatted, pure (backlog 2026-11-11). Only for a
 * removable source (REQ-AND-SOURCE.01) whose Selection is on another volume; anything else —
 * phone storage, a cloud folder, a Selection on the card itself, an unknown volume, nothing kept —
 * draws no line, because no verdict would be true there. The verdict is
 * [ImageRepositoryImpl.verifySafeToFormat]: every kept file under its exact name with its size.
 */
internal object DoneSelection {

    fun line(
        sourceKind: SourceKind,
        keptFiles: Map<String, Long>,
        checking: Boolean,
        selection: SelectionFolderState?,
    ): SafeToFormatLine? {
        if (sourceKind != SourceKind.REMOVABLE || keptFiles.isEmpty()) return null
        if (checking) return SafeToFormatLine.Checking
        if (selection == null || selection.onSourceVolume != false) return null
        val verdict = ImageRepositoryImpl.verifySafeToFormat(keptFiles, selection.filesWithSizes)
        return if (verdict.isSafe) SafeToFormatLine.Safe else SafeToFormatLine.Missing(verdict.missingOrMismatchedFiles.size)
    }
}
