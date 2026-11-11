package com.phototok.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.SourceSettings
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.phototok.domain.SwipeDestinations
import com.phototok.domain.SwipeDestination
import com.phototok.data.model.FolderLookup
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.domain.PhotoFolders
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectionFolderName: String = "Selection",
    val sortingEnabled: Boolean = true,
    val selectedSourceKind: SourceKind = SourceKind.PHONE_LIBRARY,
    val swipeRightVerb: SwipeRightVerb = SwipeRightVerb.FAVOURITE,
    val feedOrder: FeedOrderMode = FeedOrderMode.NEWEST_FIRST,
    val collectionAction: CollectionAction = CollectionAction.DEFAULT,
    val directDeleteConfirmEnabled: Boolean = true,
    val sortByOrientation: Boolean = false,
    val randomizeOrder: Boolean = false,
    val collectionUri: String? = null,
    val leftSwipeAction: SwipeAction = SwipeAction.DEFAULT,
    val leftSwipeUri: String? = null,
    /**
     * The names the providers report for [collectionUri] and [leftSwipeUri], null until resolved or
     * when they report none. Only the display path of a provider with opaque ids (cloud, camera) needs
     * them; the external-storage path is read from the URI itself.
     */
    val collectionFolderName: String? = null,
    val leftSwipeFolderName: String? = null,
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
    val showExifOverlay: Boolean = false,
    val moveRelatedFiles: Boolean = false,
    val recentPathsEnabled: Boolean = true,
    val recentPathsCount: Int = 3,
    val sourceFolderUri: String? = null,
    val sourceFolderName: String = "",
    val celebrationsEnabled: Boolean = true,
    val cloudInheritsPhone: Boolean = true,
    val sourceSettingsByKind: Map<SourceKind, SourceSettings> = emptyMap(),
    /** Pro mode on? In Casual the settings above are the Casual values and the Pro rows are hidden. */
    val proMode: Boolean = false,
    /** The kind of the folder being browsed, when Settings was opened over one. */
    val browsedSourceKind: SourceKind? = null,
    /** Whether the custom destination folders are lost, keyed by tree URI. */
    val folderLookups: Map<String, FolderLookup> = emptyMap(),
) {
    /** The open folder, when it belongs to the tab shown: a default destination lives inside it. */
    private val sourceOfTab: String?
        get() = sourceFolderUri.takeIf { browsedSourceKind != null && browsedSourceKind.tabKind() == selectedSourceKind }

    /** Where swipe right puts photos for this tab: the same resolution the feed copies with. */
    val selectionDestination: SwipeDestination
        get() = SwipeDestinations.selection(
            collectionUri,
            sourceOfTab,
            sourceSettingsByKind[selectedSourceKind]?.selectionSubfolder ?: selectionFolderName,
        )

    /** Where a copy/move swipe left puts photos for this tab. */
    val leftSwipeDestination: SwipeDestination
        get() = SwipeDestinations.leftSwipe(leftSwipeUri, sourceOfTab)
}

/** Settings has no Cloud tab: a cloud folder uses the Phone + cloud tab. */
internal fun SourceKind.tabKind(): SourceKind = if (this == SourceKind.CLOUD_OTHER) SourceKind.PHONE_LIBRARY else this

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val imageRepository: ImageRepository,
    private val seenRepository: SeenRepository,
) : ViewModel() {

    constructor(
        settingsRepository: SettingsRepository,
        imageRepository: ImageRepository,
    ) : this(settingsRepository, imageRepository, SeenRepository())

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Typed flows only — no positional array casts.
            combine(
                settingsRepository.phoneSettings,
                settingsRepository.selectionFolderName,
                settingsRepository.sortingEnabled,
            ) { phone, selectionFolderName, sortingEnabled ->
                Triple(phone, selectionFolderName, sortingEnabled)
            }.collect { (phone, selectionFolderName, sortingEnabled) ->
                _uiState.update { state ->
                    val isPhoneSelected = state.selectedSourceKind == SourceKind.PHONE_LIBRARY
                    val hasSourceOverride = state.sourceSettingsByKind.containsKey(SourceKind.PHONE_LIBRARY)
                    if (isPhoneSelected && !hasSourceOverride) {
                        state.copy(
                            proMode = phone.proMode,
                            selectionFolderName = selectionFolderName,
                            sortingEnabled = sortingEnabled,
                            collectionAction = phone.collectionAction,
                            swipeRightVerb = SwipeRightVerb.fromCollectionAction(phone.collectionAction),
                            feedOrder = FeedOrderMode.fromLegacyFlags(phone.sortByOrientation, phone.randomizeOrder),
                            directDeleteConfirmEnabled = phone.directDeleteConfirmEnabled,
                            sortByOrientation = phone.sortByOrientation,
                            randomizeOrder = phone.randomizeOrder,
                            fileTypeFilter = phone.fileTypeFilter,
                            showExifOverlay = phone.showExifOverlay,
                            moveRelatedFiles = phone.moveRelatedFiles,
                            recentPathsEnabled = phone.recentPathsEnabled,
                            recentPathsCount = phone.recentPathsCount,
                            leftSwipeAction = phone.leftSwipeAction,
                        )
                    } else {
                        state.copy(
                            proMode = phone.proMode,
                            selectionFolderName = selectionFolderName,
                            sortingEnabled = sortingEnabled,
                            showExifOverlay = phone.showExifOverlay,
                            recentPathsEnabled = phone.recentPathsEnabled,
                            recentPathsCount = phone.recentPathsCount,
                        )
                    }
                }
            }
        }

        // Observe per-SourceKind settings for Phone, SD/Card, Camera.
        listOf(
            SourceKind.PHONE_LIBRARY,
            SourceKind.REMOVABLE,
            SourceKind.CAMERA_MTP,
        ).forEach { kind ->
            viewModelScope.launch {
                settingsRepository.sourceSettingsFlow(kind).collect { src ->
                    _uiState.update { state ->
                        val updatedMap = state.sourceSettingsByKind + (kind to src)
                        if (state.selectedSourceKind == kind) {
                            state.copy(
                                sourceSettingsByKind = updatedMap,
                                swipeRightVerb = src.swipeRightVerb,
                                collectionAction = src.collectionAction,
                                feedOrder = src.feedOrder,
                                sortByOrientation = src.sortByOrientation,
                                randomizeOrder = src.randomizeOrder,
                                fileTypeFilter = src.fileTypeFilter,
                                moveRelatedFiles = src.moveRelatedFiles,
                                directDeleteConfirmEnabled = src.directDeleteConfirmEnabled,
                                leftSwipeAction = src.leftSwipeAction,
                                collectionUri = src.collectionFolderUri,
                                leftSwipeUri = src.leftSwipeFolderUri,
                            )
                        } else {
                            state.copy(sourceSettingsByKind = updatedMap)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.celebrationsEnabled.collect { enabled ->
                _uiState.update { it.copy(celebrationsEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.cloudInheritsPhone.collect { inherits ->
                _uiState.update { it.copy(cloudInheritsPhone = inherits) }
            }
        }

        viewModelScope.launch {
            settingsRepository.phoneCollectionUri.collect { uri ->
                _uiState.update { state ->
                    if (state.selectedSourceKind == SourceKind.PHONE_LIBRARY) {
                        state.copy(collectionUri = uri)
                    } else state
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.phoneLeftSwipeUri.collect { uri ->
                _uiState.update { state ->
                    if (state.selectedSourceKind == SourceKind.PHONE_LIBRARY) {
                        state.copy(leftSwipeUri = uri)
                    } else state
                }
            }
        }

        // The custom folders' provider names, resolved off the state update; a tab switch or a new
        // folder cancels a lookup still running for the previous one.
        viewModelScope.launch {
            _uiState.map { it.collectionUri }.distinctUntilChanged().collectLatest { uri ->
                val name = uri?.let { providerFolderName(it) }
                _uiState.update { if (it.collectionUri == uri) it.copy(collectionFolderName = name) else it }
            }
        }
        viewModelScope.launch {
            _uiState.map { it.leftSwipeUri }.distinctUntilChanged().collectLatest { uri ->
                val name = uri?.let { providerFolderName(it) }
                _uiState.update { if (it.leftSwipeUri == uri) it.copy(leftSwipeFolderName = name) else it }
            }
        }

        viewModelScope.launch {
            settingsRepository.lastFolderUri.collect { uri ->
                val name = if (uri != null) {
                    imageRepository.resolveFolderName(Uri.parse(uri))
                        ?: PhotoFolders.nameFromDocumentId(Uri.parse(uri).lastPathSegment)
                } else ""
                _uiState.update { it.copy(sourceFolderUri = uri, sourceFolderName = name) }
            }
        }

        // Whether a custom destination folder lost its access (REQ-TOK-FEATURE.23); the path itself
        // comes from the URI and the provider names above.
        viewModelScope.launch {
            _uiState
                .map { listOfNotNull(it.collectionUri, it.leftSwipeUri).toSet() }
                .distinctUntilChanged()
                .collect { uris -> lookUpFolders(uris) }
        }
    }

    /** Re-reads the folders after the sheet reopens: a grant may have been revoked meanwhile. */
    fun refreshFolderLookups() {
        viewModelScope.launch {
            val state = _uiState.value
            lookUpFolders(listOfNotNull(state.collectionUri, state.leftSwipeUri).toSet())
        }
    }

    private suspend fun lookUpFolders(uris: Set<String>) {
        for (uri in uris) {
            val lookup = imageRepository.describeFolder(Uri.parse(uri))
            _uiState.update { it.copy(folderLookups = it.folderLookups + (uri to lookup)) }
        }
    }

    private suspend fun providerFolderName(uri: String): String? =
        imageRepository.resolveFolderName(Uri.parse(uri))?.takeIf { it.isNotBlank() }

    fun selectSourceKindTab(kind: SourceKind) {
        _uiState.update { state ->
            val src = state.sourceSettingsByKind[kind] ?: SourceSettings.defaultFor(kind)
            state.copy(
                selectedSourceKind = kind,
                swipeRightVerb = src.swipeRightVerb,
                collectionAction = src.collectionAction,
                feedOrder = src.feedOrder,
                sortByOrientation = src.sortByOrientation,
                randomizeOrder = src.randomizeOrder,
                fileTypeFilter = src.fileTypeFilter,
                moveRelatedFiles = src.moveRelatedFiles,
                directDeleteConfirmEnabled = src.directDeleteConfirmEnabled,
                leftSwipeAction = src.leftSwipeAction,
                collectionUri = src.collectionFolderUri,
                leftSwipeUri = src.leftSwipeFolderUri,
            )
        }
    }

    /**
     * Show the tab of the folder being browsed, so a change in Settings acts on that folder. Cloud
     * has no tab of its own: it follows the phone's settings (`cloudInheritsPhone`).
     */
    fun showSourceKind(kind: SourceKind) {
        _uiState.update { it.copy(browsedSourceKind = kind) }
        selectSourceKindTab(kind.tabKind())
    }

    fun updateSelectionFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { settingsRepository.setSelectionFolderName(trimmed) }
    }

    fun updateSortingEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSortingEnabled(enabled) }
    }

    fun updateSwipeRightVerb(verb: SwipeRightVerb) {
        val kind = _uiState.value.selectedSourceKind
        _uiState.update {
            it.copy(
                swipeRightVerb = verb,
                collectionAction = verb.toCollectionActionOrNull() ?: it.collectionAction,
            )
        }
        viewModelScope.launch {
            settingsRepository.setSourceSwipeRightVerb(kind, verb)
            if (kind == SourceKind.PHONE_LIBRARY) {
                verb.toCollectionActionOrNull()?.let { settingsRepository.setPhoneCollectionAction(it) }
            }
        }
    }

    fun updateFeedOrder(order: FeedOrderMode) {
        val kind = _uiState.value.selectedSourceKind
        _uiState.update {
            it.copy(
                feedOrder = order,
                sortByOrientation = order == FeedOrderMode.ORIENTATION_THEN_NEWEST,
                randomizeOrder = order == FeedOrderMode.SHUFFLE,
            )
        }
        viewModelScope.launch {
            settingsRepository.setSourceFeedOrder(kind, order)
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneSortByOrientation(order == FeedOrderMode.ORIENTATION_THEN_NEWEST)
                settingsRepository.setPhoneRandomizeOrder(order == FeedOrderMode.SHUFFLE)
            }
        }
    }

    fun updateCollectionAction(action: CollectionAction) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneCollectionAction(action)
            } else {
                settingsRepository.setSourceSwipeRightVerb(kind, SwipeRightVerb.fromCollectionAction(action))
            }
        }
    }

    fun updateDirectDeleteConfirm(enabled: Boolean) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneDirectDeleteConfirmEnabled(enabled)
            } else {
                settingsRepository.setSourceDirectDeleteConfirmEnabled(kind, enabled)
            }
        }
    }

    fun updateSortByOrientation(enabled: Boolean) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneSortByOrientation(enabled)
            } else {
                val order = if (enabled) FeedOrderMode.ORIENTATION_THEN_NEWEST else FeedOrderMode.NEWEST_FIRST
                settingsRepository.setSourceFeedOrder(kind, order)
            }
        }
    }

    fun updateRandomizeOrder(enabled: Boolean) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneRandomizeOrder(enabled)
            } else {
                val order = if (enabled) FeedOrderMode.SHUFFLE else FeedOrderMode.NEWEST_FIRST
                settingsRepository.setSourceFeedOrder(kind, order)
            }
        }
    }

    fun updateCollectionUri(uri: String?) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneCollectionUri(uri)
            } else {
                settingsRepository.setSourceCollectionUri(kind, uri)
            }
        }
    }

    fun updateFileTypeFilter(filter: FileTypeFilter) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneFileTypeFilter(filter)
            } else {
                settingsRepository.setSourceFileTypeFilter(kind, filter)
            }
        }
    }

    fun updateShowExifOverlay(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPhoneShowExifOverlay(enabled) }
    }

    fun updateMoveRelatedFiles(enabled: Boolean) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneMoveRelatedFiles(enabled)
            } else {
                settingsRepository.setSourceMoveRelatedFiles(kind, enabled)
            }
        }
    }

    fun updateRecentPathsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPhoneRecentPathsEnabled(enabled) }
    }

    fun updateRecentPathsCount(count: Int) {
        viewModelScope.launch { settingsRepository.setPhoneRecentPathsCount(count) }
    }

    fun updateLeftSwipeAction(action: SwipeAction) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneLeftSwipeAction(action)
            } else {
                settingsRepository.setSourceLeftSwipeAction(kind, action)
            }
        }
    }

    fun updateLeftSwipeUri(uri: String?) {
        val kind = _uiState.value.selectedSourceKind
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneLeftSwipeUri(uri)
            } else {
                settingsRepository.setSourceLeftSwipeUri(kind, uri)
            }
        }
    }

    /**
     * Switch Pro mode from the Settings row. [onMessage] receives the snackbar text naming the
     * visible change (null when the mode did not change); `PhoneModeViewModel.setProMode` is the
     * same action with the snackbar already wired.
     */
    fun setProMode(enabled: Boolean, onMessage: (UiText?) -> Unit = {}) {
        viewModelScope.launch { onMessage(settingsRepository.setProMode(enabled)) }
    }

    fun updateCelebrationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCelebrationsEnabled(enabled) }
    }

    fun updateCloudInheritsPhone(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCloudInheritsPhone(enabled) }
    }

    /** Reset seen history for the currently active source folder (`U-15`). */
    fun resetSeenForCurrentFolder(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val uri = _uiState.value.sourceFolderUri
            if (uri != null) {
                seenRepository.resetSource(uri)
                settingsRepository.removeFolderSeenProgress(uri)
            }
            onDone()
        }
    }

    /** Reset seen history across all folders (`U-15`). */
    fun resetAllSeenHistory(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            seenRepository.resetAll()
            settingsRepository.clearFolderPositions()
            onDone()
        }
    }

    /**
     * Bring back every one-time explanation and the full gesture tutorial.
     * [onDone] fires after the write so the UI can confirm it happened.
     */
    fun resetTutorials(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepository.resetFirstRunHints()
            onDone()
        }
    }

    /**
     * Clear Coil thumbnail cache and per-folder scan history (what counts as a new photo).
     * [onDone] fires after cache is cleared.
     */
    fun clearCache(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            seenRepository.resetAll()
            settingsRepository.clearCache()
            onDone()
        }
    }
}
