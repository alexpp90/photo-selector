package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
)

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val findDuplicatesUseCase: FindDuplicatesUseCase,
    private val imageRepository: ImageRepository,
    @ApplicationContext private val context: Context,
    private val cacheRepository: CacheRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicatesUiState())
    val uiState: StateFlow<DuplicatesUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    /**
     * The scanned photographs by URI, so a delete takes a copy's sidecars and companions with it
     * instead of leaving them orphaned in the folder (FD-B-5).
     */
    private var scanned: Map<String, ImageItem> = emptyMap()

    fun selectFolder(uri: Uri) {
        scanJob?.cancel()
        viewModelScope.launch {
            val folderName = imageRepository.openFolder(context, uri)
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

    fun deleteSelected() {
        // Whatever the screen allowed, at least one copy of each photograph survives.
        val selected = keepingOneCopyPerGroup(_uiState.value.selectedForDeletion)
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    statusText = "Deleting ${selected.size} files..."
                )
            }

            var deletedCount = 0
            val failedUris = mutableListOf<String>()

            for (uriString in selected) {
                try {
                    val item = scanned[uriString]
                    val deleted = if (item != null) {
                        imageRepository.deleteBundle(context, item)
                    } else {
                        imageRepository.deleteImage(context, Uri.parse(uriString))
                    }
                    if (deleted) {
                        deletedCount++
                        cacheRepository.forgetDeletedFile(uriString)
                    } else {
                        failedUris.add(uriString)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("DuplicatesViewModel", "Delete failed for $uriString", e)
                    failedUris.add(uriString)
                }
            }

            // Remove deleted files from duplicate groups
            val updatedGroups = _uiState.value.duplicateGroups.mapNotNull { group ->
                val remainingFiles = group.files.filter { it !in selected || it in failedUris }
                if (remainingFiles.size > 1) {
                    group.copy(files = remainingFiles)
                } else {
                    null // Group no longer has duplicates
                }
            }

            _uiState.update {
                it.copy(
                    isScanning = false,
                    duplicateGroups = updatedGroups,
                    selectedForDeletion = failedUris.toSet(),
                    statusText = if (failedUris.isEmpty()) {
                        "Deleted $deletedCount files"
                    } else {
                        "Deleted $deletedCount files, ${failedUris.size} failed"
                    },
                    error = if (failedUris.isNotEmpty()) {
                        "Failed to delete ${failedUris.size} files"
                    } else {
                        null
                    }
                )
            }
        }
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
