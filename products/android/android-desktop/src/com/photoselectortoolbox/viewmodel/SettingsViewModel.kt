package com.photoselectortoolbox.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val phoneCollectionUri: String? = null,
    val customSelectionDestinationUri: String? = null,
    val fullscreenButtonsEnabled: Boolean = true,
    val filingAction: FilingAction = FilingAction.DEFAULT,
    val tvDisplayScaleEnabled: Boolean = SettingsRepository.DEFAULT_TV_DISPLAY_SCALE_ENABLED,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cacheRepository: CacheRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine<Any, (SettingsUiState) -> SettingsUiState>(
                settingsRepository.selectionFolderName,
                settingsRepository.sortingEnabled,
                settingsRepository.groupingEnabled,
                settingsRepository.groupingLevel,
                settingsRepository.analysisThreadCount,
                settingsRepository.fullscreenButtonsEnabled,
                settingsRepository.filingAction
            ) { arrays ->
                { current: SettingsUiState ->
                    current.copy(
                        selectionFolderName = arrays[0] as String,
                        sortingEnabled = arrays[1] as Boolean,
                        groupingEnabled = arrays[2] as Boolean,
                        groupingLevel = arrays[3] as GroupingLevel,
                        analysisThreadCount = arrays[4] as Int,
                        fullscreenButtonsEnabled = arrays[5] as Boolean,
                        filingAction = arrays[6] as FilingAction,
                    )
                }
            }.collect { apply ->
                // Applied to the state as it is now: a count refreshed meanwhile is not overwritten.
                _uiState.update(apply)
            }
        }

        collectInto(settingsRepository.phoneCollectionUri) { uri -> copy(phoneCollectionUri = uri) }
        collectInto(settingsRepository.secondarySelectionFolderName) { name -> copy(secondarySelectionFolderName = name) }
        collectInto(settingsRepository.customSelectionDestinationUri) { uri -> copy(customSelectionDestinationUri = uri) }
        collectInto(settingsRepository.tvDisplayScaleEnabled) { enabled -> copy(tvDisplayScaleEnabled = enabled) }

        refreshCachedScoreCount()
    }

    /**
     * Fold every value of [flow] into the state. A failing flow ends only its own field; the
     * scope's own cancellation is never swallowed.
     */
    private fun <T> collectInto(flow: Flow<T>, fold: SettingsUiState.(T) -> SettingsUiState) {
        viewModelScope.launch {
            try {
                flow.collect { value -> _uiState.update { it.fold(value) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A setting that cannot be read keeps its default; the screen still works.
            }
        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cache clear failure is non-critical
            }
        }
    }

    fun updatePhoneCollectionUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setPhoneCollectionUri(uri)
            settingsRepository.setCustomSelectionDestinationUri(uri)
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
                val stored = cacheRepository.count()
                _uiState.update { it.copy(cachedScoreCount = stored) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Non-critical
            }
        }
    }
}
