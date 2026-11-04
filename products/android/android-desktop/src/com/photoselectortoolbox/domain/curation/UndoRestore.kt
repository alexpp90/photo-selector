package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectionActionLabels

/**
 * Where an undone move put the files back (finding FD-B-11).
 *
 * An undo restores the frame to the list before the transfer runs, with the URIs it had before
 * the move. The restore often creates *new* documents: a `-2` name when the original one was
 * taken, and on providers with opaque document ids (Drive, Downloads, MTP) every copy-and-delete
 * gets a new id. A list item left at the old URIs has no rating (the rating is re-keyed to the
 * new URI) and fails its next Move or Delete, so the item is rebuilt from what the undo reports.
 *
 * Pure and Android-free.
 */
object UndoRestore {

    /**
     * [item] at the URIs the undo restored it to. [movedUris] are the move's destinations, in
     * bundle order ([ImageItem.allFiles]: primary first); [restoredUris] the undo's results in
     * the same order. When the two do not line up with the bundle, only the primary is moved to
     * the first restored URI.
     *
     * A file name is taken from the new URI only where the provider's URIs carry names (the old
     * URI's last segment *is* the old name); an opaque id says nothing about the name, so the
     * old one is kept.
     */
    fun relocate(item: ImageItem, movedUris: List<String>, restoredUris: List<String>): ImageItem {
        if (restoredUris.isEmpty()) return item
        val files = item.allFiles
        val relocated = if (movedUris.size == files.size && restoredUris.size == files.size) {
            files.mapIndexed { i, file -> relocateFile(file, restoredUris[i]) }
        } else {
            listOf(relocateFile(files.first(), restoredUris.first())) + files.drop(1)
        }
        val primary = relocated.first()
        return item.copy(uri = primary.uri, fileName = primary.fileName, companions = relocated.drop(1))
    }

    private fun relocateFile(file: CompanionFile, newUri: String): CompanionFile {
        if (newUri == file.uri) return file
        val namesInUris = SelectionActionLabels.displayNameOf(file.uri) == file.fileName
        val name = if (namesInUris) SelectionActionLabels.displayNameOf(newUri).ifEmpty { file.fileName } else file.fileName
        return file.copy(uri = newUri, fileName = name)
    }

    /** [state] with the item at [oldUri] replaced by [replacement]; the frame on screen stays. */
    fun replace(state: SelectorListState, oldUri: String, replacement: ImageItem): SelectorListState {
        val index = state.images.indexOfFirst { it.uri == oldUri }
        if (index < 0) return state
        val focused = state.currentUri.let { if (it == oldUri) replacement.uri else it }
        return state.copy(images = state.images.toMutableList().also { it[index] = replacement }).focusedOn(focused)
    }
}
