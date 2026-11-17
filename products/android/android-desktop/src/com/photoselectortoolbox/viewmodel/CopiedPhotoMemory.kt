package com.photoselectortoolbox.viewmodel

import android.util.Log
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.KeptFiles
import com.photoselectortoolbox.data.model.SelectionFolderState
import com.photoselectortoolbox.data.repository.CopiedPhotoRepository
import com.photoselectortoolbox.domain.curation.CopiedOnReopen
import kotlinx.coroutines.CancellationException

/**
 * A finished Copy or Move in [SelectorUiState]: the photo counts once under its decision, its
 * files are known to the safe-to-format line, and a copied photo carries the mark and refuses a
 * second filing (decision #29).
 */
internal fun SelectorUiState.withFiled(photo: ImageItem, copied: Boolean, destinationUris: List<String>): SelectorUiState =
    copy(
        tally = tally.record(listOf(photo.uri), if (copied) FilingDecision.COPIED else FilingDecision.MOVED),
        sessionKeptFileNames = sessionKeptFileNames + photo.fileName,
        sessionCopiedUris = sessionCopiedUris + listOfNotNull(photo.uri.takeIf { copied }),
        sessionKeptFiles = sessionKeptFiles + KeptFiles.of(photo.allFiles, destinationUris),
    )

/**
 * The copied photos of each folder across sessions (`docs/shared/FILE_OPERATIONS.md` §5,
 * "Survives a reopen", maintainer decision 2026-11-16, D3), between the view model and
 * [CopiedPhotoRepository]. A null store (tests that never reopen a folder) keeps the copied state
 * for the session only. A failure costs the reopen mark only, never a copy: it is logged and the
 * photos open undecided, as before D3.
 */
internal class CopiedPhotoMemory(private val store: CopiedPhotoRepository?) {

    /** Keeps a finished Copy of [photo] for the next time [folderUri] is opened. Durable: callers run it after a folder switch too. */
    suspend fun remember(folderUri: String, photo: ImageItem, destinationUris: List<String>) {
        val store = store ?: return
        try {
            store.record(folderUri, photo, KeptFiles.of(photo.allFiles, destinationUris), System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not remember the copy of ${photo.uri}", e)
        }
    }

    /**
     * The update that makes [folder]'s photos copied in an earlier session decided again — marked,
     * counted once, out of Remaining, a second Copy or a Move refused — or null when there is none.
     * [listed] is the settled list while [folder] is still open (null once it is not);
     * [selection] lists the Selection folder once. A record whose copy has left the Selection, or
     * whose photo changed, is deleted ([CopiedOnReopen]), durably, whether or not the folder is
     * still open. A photo with a decision in this session keeps it.
     */
    suspend fun restore(
        folder: String,
        listed: () -> List<ImageItem>?,
        selection: suspend () -> SelectionFolderState?,
    ): ((SelectorUiState) -> SelectorUiState)? {
        val store = store ?: return null
        return try {
            val records = store.copiedIn(folder)
            if (records.isEmpty()) return null
            val selectionFiles = selection()?.filesByDocumentId
            val photos = listed() ?: return null
            val outcome = CopiedOnReopen.resolve(records, photos, selectionFiles)
            if (outcome.stale.isNotEmpty()) store.forget(folder, outcome.stale)
            if (outcome.copied.isEmpty()) return null
            val restored: (SelectorUiState) -> SelectorUiState = { state ->
                val fresh = outcome.copied.filterKeys { state.tally.latest(it) == null && it !in state.sessionCopiedUris }
                if (state.folderUri != folder || fresh.isEmpty()) {
                    state
                } else {
                    state.copy(
                        tally = state.tally.record(fresh.keys, FilingDecision.COPIED),
                        sessionCopiedUris = state.sessionCopiedUris + fresh.keys,
                        sessionKeptFiles = fresh.values.fold(state.sessionKeptFiles) { kept, files -> kept + files },
                    )
                }
            }
            restored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore the copied photos of $folder", e)
            null
        }
    }

    private companion object {
        const val TAG = "CopiedPhotoMemory"
    }
}
