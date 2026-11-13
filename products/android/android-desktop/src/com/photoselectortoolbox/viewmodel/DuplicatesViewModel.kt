package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.HeldDeleteCommitter
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.repository.ToolFolder
import com.photoselectortoolbox.di.ApplicationScope
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.curation.HeldDuplicateDelete
import com.photoselectortoolbox.domain.curation.HeldDuplicateDeletes
import com.photoselectortoolbox.domain.format.DuplicateLabels
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class DuplicatesUiState(
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val statusText: String = "",
    val folderUri: String? = null,
    val folderName: String = "",
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val selectedForDeletion: Set<String> = emptySet(),
    val error: String? = null,
    val missingGrantPrompt: Boolean = false,
    /**
     * Copies deleted by the latest delete still held back, or 0 when nothing can be undone. A Dupes
     * delete is held like a cull delete (REQ-AND-ALGO.05): the files leave the disk when the
     * screen or the folder is left.
     */
    val undoableDeleteCount: Int = 0,
)

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val findDuplicatesUseCase: FindDuplicatesUseCase,
    private val imageRepository: ImageRepository,
    private val heldDeletes: HeldDeleteCommitter,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    /**
     * The scanned photographs by URI, so a delete takes a copy's sidecars and companions with it
     * instead of leaving them orphaned in the folder (FD-B-5).
     */
    private var scanned: Map<String, ImageItem> = emptyMap()

    /** A held delete and its journal write, which records the identity each file had. */
    private class Held(val delete: HeldDuplicateDelete, val identities: Deferred<Map<String, FileIdentity>>)

    /** The held deletes, newest last ([HeldDuplicateDeletes]). Main-thread state, like [_uiState]. */
    private var held: List<Held> = emptyList()

    /** The commits started so far: a scan waits for them, so it never lists a copy being deleted. */
    private val commits = mutableListOf<Job>()

    fun selectFolder(uri: Uri) {
        scanJob?.cancel()
        // Another folder (or the same one again) is a new list: what was held is deleted now.
        commitHeldDeletes()
        viewModelScope.launch {
            val folderName = imageRepository.openFolder(context, uri)
            // A durable holder, or the next reference sync releases the grant mid-use.
            if (folderName != null) settingsRepository.setToolFolderUri(ToolFolder.DUPLICATES, uri.toString())
            if (folderName == null) {
                _uiState.update {
                    it.copy(
                        folderUri = null,
                        folderName = "",
                        duplicateGroups = emptyList(),
                        selectedForDeletion = emptySet(),
                        missingGrantPrompt = true,
                        error = "Failed to load folder: permission revoked or directory deleted.",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    folderUri = uri.toString(),
                    folderName = folderName,
                    duplicateGroups = emptyList(),
                    selectedForDeletion = emptySet(),
                    missingGrantPrompt = false,
                    error = null,
                )
            }
        }
    }

    fun startScan() {
        val folderUri = _uiState.value.folderUri ?: return

        scanJob?.cancel()
        // A new scan lists what is on disk, so the held copies must be gone first.
        commitHeldDeletes()
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    scanProgress = 0f,
                    statusText = "Discovering images...",
                    duplicateGroups = emptyList(),
                    selectedForDeletion = emptySet(),
                    error = null
                )
            }

            try {
                awaitCommits()
                val uri = Uri.parse(folderUri)
                // The whole folder, not the first progressive batch — a
                // duplicate set found in 24 of 842 photographs is not a
                // duplicate report, it is a sample.
                val images = imageRepository.discoverAllImages(uri)

                if (images.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            statusText = "No images found in folder"
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(statusText = "Scanning ${images.size} images for duplicates...")
                }

                scanned = images.associateBy { it.uri }
                val uriSizePairs = images.map { image ->
                    Uri.parse(image.uri) to image.fileSize
                }

                findDuplicatesUseCase(uriSizePairs).collect { progress ->
                    val fraction = if (progress.total > 0) {
                        progress.processed.toFloat() / progress.total.toFloat()
                    } else {
                        0f
                    }

                    val statusText = if (progress.total > 0) {
                        "Hashing files: ${progress.processed}/${progress.total}"
                    } else {
                        "Analyzing..."
                    }

                    _uiState.update {
                        it.copy(
                            scanProgress = fraction,
                            statusText = statusText,
                            duplicateGroups = progress.groups
                        )
                    }
                }

                val groups = _uiState.value.duplicateGroups
                val totalDuplicates = groups.sumOf { it.files.size - 1 }

                _uiState.update {
                    it.copy(
                        isScanning = false,
                        scanProgress = 1f,
                        statusText = if (groups.isEmpty()) {
                            "No duplicates found"
                        } else {
                            "Found $totalDuplicates duplicates in ${groups.size} groups"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DuplicatesViewModel", "Duplicate scan failed", e)
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        statusText = "",
                        error = "Scan failed"
                    )
                }
            }
        }
    }

    fun toggleSelection(uri: String) {
        _uiState.update { state ->
            val updated = state.selectedForDeletion.toMutableSet()
            if (uri in updated) {
                updated.remove(uri)
            } else {
                // Never every copy of a photograph: the last one left unticked stays unticked.
                val group = state.duplicateGroups.firstOrNull { uri in it.files }
                if (group != null && group.files.all { it == uri || it in updated }) {
                    return@update state.copy(error = KEEP_ONE_COPY)
                }
                updated.add(uri)
            }
            state.copy(selectedForDeletion = updated)
        }
    }

    fun selectAllButFirst(group: DuplicateGroup) {
        _uiState.update { state ->
            val updated = state.selectedForDeletion.toMutableSet()
            // Keep the first file in the group, select the rest
            group.files.drop(1).forEach { uri ->
                updated.add(uri)
            }
            state.copy(selectedForDeletion = updated)
        }
    }

    /**
     * Take the ticked copies out of the duplicate list and hold their deletion (REQ-AND-ALGO.05):
     * nothing leaves the disk until the screen or the folder is left, and [undoDelete] puts them
     * back. Each copy takes its sidecars and companions with it (FD-B-5), and the journal records
     * every file so a killed app offers the deletion again instead of losing or forgetting it.
     */
    fun deleteSelected() {
        // Whatever the screen allowed, at least one copy of each photograph survives.
        val state = _uiState.value
        val selected = keepingOneCopyPerGroup(state.selectedForDeletion)
        if (selected.isEmpty()) return

        val files: List<CompanionFile> = selected.flatMap { uri ->
            scanned[uri]?.allFiles ?: listOf(CompanionFile(uri, Uri.parse(uri).lastPathSegment ?: uri))
        }.distinctBy { it.uri }
        val delete = HeldDuplicateDelete(
            files = files,
            copies = selected.size,
            groupsBefore = state.duplicateGroups,
            selectionBefore = state.selectedForDeletion,
        )
        // UNDISPATCHED: the journal lock is taken before this returns, so an undo or a commit
        // that follows at once waits for the entries to be written.
        val identities = appScope.async(start = CoroutineStart.UNDISPATCHED) { heldDeletes.hold(files) }
        val overflow = HeldDuplicateDeletes.push(held.map { it.delete }, delete).second.size
        val evicted = held.take(overflow)
        held = held.drop(overflow) + Held(delete, identities)
        evicted.forEach(::commit)

        _uiState.update {
            it.copy(
                duplicateGroups = HeldDuplicateDeletes.remainingGroups(it.duplicateGroups, selected),
                selectedForDeletion = emptySet(),
                // The held-delete bar says it; the status line would say it twice.
                statusText = "",
                undoableDeleteCount = selected.size,
                error = null,
            )
        }
    }

    /** Put the copies of the latest held delete back, as the list was before it; nothing touched the disk. */
    fun undoDelete() {
        val last = held.lastOrNull() ?: return
        held = held.dropLast(1)
        appScope.launch {
            journalled(last)
            heldDeletes.release(last.delete.files.map { it.uri })
        }
        val next = held.lastOrNull()
        _uiState.update {
            it.copy(
                duplicateGroups = last.delete.groupsBefore,
                selectedForDeletion = last.delete.selectionBefore,
                undoableDeleteCount = next?.delete?.copies ?: 0,
            )
        }
    }

    /** The Dupes screen was left: every held delete goes to disk now. */
    fun onScreenLeft() = commitHeldDeletes()

    override fun onCleared() {
        // appScope, not viewModelScope: the deletion must finish after the screen is gone.
        commitHeldDeletes()
        super.onCleared()
    }

    /** Wait for every commit started so far: a scan must not list copies about to be deleted. */
    internal suspend fun awaitCommits() {
        commits.toList().joinAll()
    }

    private fun commitHeldDeletes() {
        if (held.isEmpty()) return
        val all = held
        held = emptyList()
        _uiState.update { it.copy(undoableDeleteCount = 0) }
        all.forEach(::commit)
    }

    /**
     * The identities [h]'s journal write recorded, once it has finished. A write that failed was
     * logged; the delete then goes ahead as an unjournalled one (no identity to check), as before.
     */
    private suspend fun journalled(h: Held): Map<String, FileIdentity> = try {
        h.identities.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("DuplicatesViewModel", "Could not journal a held delete", e)
        emptyMap()
    }

    private fun commit(h: Held) {
        val job = appScope.launch {
            val outcome = heldDeletes.commit(h.delete.files.map { it.uri }, journalled(h))
            val notDeleted = outcome.failed + outcome.kept
            if (notDeleted.isEmpty()) return@launch
            val names = notDeleted.joinToString { Uri.parse(it).lastPathSegment?.substringAfterLast('/') ?: it }
            Log.w("DuplicatesViewModel", "Held delete left ${notDeleted.size} files: $names")
            _uiState.update {
                it.copy(error = DuplicateLabels.notDeletedMessage(notDeleted.size, h.delete.files.size, names))
            }
        }
        commits.removeAll { it.isCompleted }
        commits += job
    }

    /** [selected] less, for every group that would lose all its copies, the first copy. */
    private fun keepingOneCopyPerGroup(selected: Set<String>): Set<String> {
        val spared = _uiState.value.duplicateGroups
            .filter { group -> group.files.all { it in selected } }
            .map { it.files.first() }
            .toSet()
        return selected - spared
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private companion object {
        const val KEEP_ONE_COPY = "Keep one copy"
    }
}
