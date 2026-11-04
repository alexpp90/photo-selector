package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselector.core.storage.SafTreeDisplayPath
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.DestinationPreview
import com.photoselectortoolbox.domain.curation.DestinationSettings
import com.photoselectortoolbox.domain.curation.OpenedFolder
import com.photoselectortoolbox.domain.curation.SelectionDestinationPlan
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectionFolderName: String = "Selection",
    val secondarySelectionFolderName: String = SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME,
    val sortingEnabled: Boolean = true,
    val groupingEnabled: Boolean = false,
    val groupingLevel: GroupingLevel = GroupingLevel.TIME_FILENAME,
    val analysisThreadCount: Int = 4,
    val cachedScoreCount: Int = 0,
    val customSelectionDestinationUri: String? = null,
    /** Where photos go: the choice, the folders involved and the resolved destination. */
    val destination: DestinationUiState = DestinationUiState(),
    val fullscreenButtonsEnabled: Boolean = true,
    val filingAction: FilingAction = FilingAction.DEFAULT,
    val tvDisplayScaleEnabled: Boolean = SettingsRepository.DEFAULT_TV_DISPLAY_SCALE_ENABLED,
)

/**
 * The "Where photos go" section (`[USER 2026-11-04]`): Working folder or Custom, the
 * folders involved as readable paths with their state, and the resolved destination —
 * from [SelectionDestinationPlan], the function Copy and Move use.
 *
 * @property mode the choice; Custom exactly when a custom folder is stored.
 * @property customFolder the stored custom folder as the storage layer found it (null:
 *   none stored, or not described yet). Its [FolderDescription.reachability] other than
 *   `REACHABLE` is a warning — the folder lost its permission, is gone or read-only.
 * @property openedFolder the folder the selector has open (`last_folder_uri`), or null.
 * @property preview the destination of the next Copy or Move.
 */
data class DestinationUiState(
    val mode: DestinationMode = DestinationMode.WORKING_FOLDER,
    val customFolder: FolderDescription? = null,
    val openedFolder: FolderDescription? = null,
    val preview: DestinationPreview = DestinationPreview.DEFAULT,
) {
    /** Whether the custom folder is stored but will not take files: show a warning. */
    val customFolderUnreachable: Boolean
        get() = mode == DestinationMode.CUSTOM && customFolder?.isReachable == false

    companion object {
        /**
         * The section's state for these inputs — pure, so every branch is tested without a
         * ViewModel. [customFolder] and [openedFolder] are the descriptions of
         * [DestinationSettings.customUri] and the opened folder (null when there is none).
         */
        fun of(
            settings: DestinationSettings,
            customFolder: FolderDescription?,
            openedFolder: FolderDescription?,
        ): DestinationUiState {
            val opened = openedFolder?.let {
                OpenedFolder(uri = it.uri, name = it.name, hasSelectionMarker = it.hasSelectionMarker)
            }
            val plan = SelectionDestinationPlan.resolve(
                settings = settings,
                openedFolder = opened,
                customReachable = customFolder?.isReachable ?: true,
            )
            val rootPath = when (plan.mode) {
                DestinationMode.CUSTOM -> customFolder?.displayPath
                DestinationMode.WORKING_FOLDER -> openedFolder?.displayPath
            }
            return DestinationUiState(
                mode = plan.mode,
                customFolder = customFolder.takeIf { settings.customUri != null },
                openedFolder = openedFolder,
                preview = DestinationPreview.of(plan, rootPath),
            )
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheRepository: CacheRepository,
    private val imageRepository: ImageRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    /** Bumped to describe the folders again (grant taken, screen resumed). */
    private val destinationRefresh = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine<Any, SettingsUiState>(
                settingsRepository.selectionFolderName,
                settingsRepository.sortingEnabled,
                settingsRepository.groupingEnabled,
                settingsRepository.groupingLevel,
                settingsRepository.analysisThreadCount,
                settingsRepository.fullscreenButtonsEnabled,
                settingsRepository.filingAction
            ) { arrays ->
                val current = _uiState.value
                current.copy(
                    selectionFolderName = arrays[0] as String,
                    sortingEnabled = arrays[1] as Boolean,
                    groupingEnabled = arrays[2] as Boolean,
                    groupingLevel = arrays[3] as GroupingLevel,
                    analysisThreadCount = arrays[4] as Int,
                    fullscreenButtonsEnabled = arrays[5] as Boolean,
                    filingAction = arrays[6] as FilingAction,
                )
            }.collect { newState ->
                _uiState.update { newState }
            }
        }

        viewModelScope.launch {
            runCatching {
                settingsRepository.secondarySelectionFolderName.collect { name ->
                    _uiState.update { it.copy(secondarySelectionFolderName = name) }
                }
            }
        }

        viewModelScope.launch {
            runCatching {
                settingsRepository.customSelectionDestinationUri.collect { uri ->
                    _uiState.update { it.copy(customSelectionDestinationUri = uri) }
                }
            }
        }

        viewModelScope.launch {
            runCatching {
                settingsRepository.tvDisplayScaleEnabled.collect { enabled ->
                    _uiState.update { it.copy(tvDisplayScaleEnabled = enabled) }
                }
            }
        }

        viewModelScope.launch { collectDestination() }

        refreshCachedScoreCount()
    }

    /**
     * Re-describe the folders whenever an input changes. `collectLatest`, so a newer input
     * cancels a description still in flight; storage lookups run in the repository on its
     * IO dispatcher.
     */
    private suspend fun collectDestination() {
        combine(
            settingsRepository.customSelectionDestinationUri,
            settingsRepository.lastFolderUri,
            settingsRepository.selectionFolderName,
            settingsRepository.secondarySelectionFolderName,
            settingsRepository.sortingEnabled,
        ) { custom, opened, name, secondName, sorting ->
            DestinationSettings(
                customDestinationUri = custom,
                selectionFolderName = name,
                secondPassFolderName = secondName,
                sortingEnabled = sorting,
            ) to opened?.trim()?.takeIf { it.isNotEmpty() }
        }.combine(destinationRefresh) { inputs, _ -> inputs }
            .collectLatest { (settings, openedUri) ->
                val custom = settings.customUri?.let { describe(it) }
                val opened = openedUri?.let { describe(it) }
                _uiState.update { it.copy(destination = DestinationUiState.of(settings, custom, opened)) }
            }
    }

    /**
     * [uri] as the storage layer finds it. A failure to look is shown as an unreachable
     * folder with its readable path — never as a valid destination, and never silently.
     */
    private suspend fun describe(uri: String): FolderDescription = try {
        imageRepository.describeFolder(context, Uri.parse(uri))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not describe $uri", e)
        FolderDescription(
            uri = uri,
            displayPath = SafTreeDisplayPath.segments(uri),
        )
    }

    /**
     * Custom destination: file into [uri], the folder the picker returned. Persists it
     * with its grant (`SELECTION_TARGET`, through `FolderAccessManager`) and releases the
     * previous custom folder's grant if nothing else needs it. A cancelled picker calls
     * nothing, so the mode stays as it was.
     */
    fun chooseCustomFolder(uri: String) {
        viewModelScope.launch {
            settingsRepository.setCustomSelectionDestinationUri(uri)
            // The grant is taken after the key is written; describe again now it is held.
            refreshDestination()
        }
    }

    /**
     * Working folder: file into the Selection folder of the folder being culled. Clears
     * the custom folder and releases its grant when nothing else references it (it is
     * not kept for later: a parked grant counts against the OS limit and goes stale).
     */
    fun useWorkingFolder() {
        viewModelScope.launch { settingsRepository.setCustomSelectionDestinationUri(null) }
    }

    /** Look at the folders again — call when Settings comes back to the foreground. */
    fun refreshDestination() {
        destinationRefresh.update { it + 1 }
    }

    fun updateSelectionFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            settingsRepository.setSelectionFolderName(trimmed)
        }
    }

    fun updateSecondarySelectionFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            settingsRepository.setSecondarySelectionFolderName(trimmed)
        }
    }

    fun updateSortingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSortingEnabled(enabled)
        }
    }

    fun updateGroupingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGroupingEnabled(enabled)
        }
    }

    fun updateGroupingLevel(level: GroupingLevel) {
        viewModelScope.launch {
            settingsRepository.setGroupingLevel(level)
        }
    }

    fun updateThreadCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.setAnalysisThreadCount(count)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                cacheRepository.clearAll()
                _uiState.update { it.copy(cachedScoreCount = 0) }
            } catch (e: Exception) {
                // Cache clear failure is non-critical
            }
        }
    }

    fun updateFullscreenButtonsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFullscreenButtonsEnabled(enabled) }
    }

    fun updateFilingAction(action: FilingAction) {
        viewModelScope.launch { settingsRepository.setFilingAction(action) }
    }

    fun updateTvDisplayScaleEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTvDisplayScaleEnabled(enabled) }
    }

    /**
     * Show every one-time explanation again.
     *
     * One repository call rather than a list of flags to clear here: the
     * repository owns the fact that two of the dismissals predate the hint set
     * and are stored separately, and a reset that leaves those two dismissed is
     * not a reset.
     */
    fun resetGuidance() {
        viewModelScope.launch { settingsRepository.resetGuidance() }
    }

    private fun refreshCachedScoreCount() {
        viewModelScope.launch {
            try {
                cacheRepository.pruneToLimit()
                // CacheRepository does not expose a count method directly,
                // so we note a zero count initially. The UI can refresh this
                // by calling refreshCachedScoreCount() when the settings screen
                // becomes visible.
                _uiState.update { it.copy(cachedScoreCount = 0) }
            } catch (e: Exception) {
                // Non-critical
            }
        }
    }
}
