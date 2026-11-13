package com.phototok.viewmodel

import android.net.Uri
import android.provider.DocumentsContract
import com.photoselector.core.storage.ExternalVolume
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.RecentPath
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.source.VolumeMonitor
import com.phototok.domain.CollectionAction
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightOutcome
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The open source and its settings, for [PhoneModeViewModel]: the phone's settings and the
 * per-source-kind settings of the open folder (one observation at a time, restarted on a folder
 * switch), the effective swipe verbs and destinations (a cloud folder copies only), the quick-start
 * source pickers on the landing, Pro mode, and the choices made over the feed that write the open
 * folder's settings ([USER 2026-11-12] decision #10).
 *
 * A changed format filter or order re-filters or re-sorts the feed through [FeedDiscoveryHolder];
 * a Casual card import's destination comes from [TransferUndoHolder.cameraImportTarget].
 */
internal class SourceSettingsHolder(
    private val _uiState: MutableStateFlow<PhoneModeUiState>,
    /** The owning view model's `viewModelScope`; cancelled when the view model is cleared. */
    private val viewModelScope: CoroutineScope,
    private val feed: FeedDiscoveryHolder,
    private val transfer: TransferUndoHolder,
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository,
) {
    private var sourceSettingsJob: Job? = null

    /** Whether a per-SourceKind SourceSettings emission has arrived. */
    private var hasSourceSettingsEmission = false

    /** Whether the one-time cloud MOVE->COPY notice has been shown in this session. */
    var cloudMoveCoercedNoticeShown = false

    // ── Settings observation ──────────────────────────────────────────────

    fun observeSettings() {
        // One typed flow for all simple settings: a single state update per
        // DataStore emission, no positional casts.
        viewModelScope.launch {
            var previous: PhoneSettings? = null
            settingsRepository.phoneSettings.collect { s ->
                val derivedOrder = FeedOrderMode.fromLegacyFlags(s.sortByOrientation, s.randomizeOrder)
                val derivedVerb = SwipeRightVerb.fromCollectionAction(s.collectionAction)
                _uiState.update { state ->
                    if (!hasSourceSettingsEmission || state.sourceKind == SourceKind.PHONE_LIBRARY) {
                        state.copy(
                            collectionAction = s.collectionAction,
                            swipeRightVerb = if (hasSourceSettingsEmission) state.swipeRightVerb else derivedVerb,
                            feedOrder = if (hasSourceSettingsEmission) state.feedOrder else derivedOrder,
                            directDeleteConfirmEnabled = s.directDeleteConfirmEnabled,
                            sortByOrientation = s.sortByOrientation,
                            randomizeOrder = s.randomizeOrder,
                            fileTypeFilter = s.fileTypeFilter,
                            leftSwipeAction = s.leftSwipeAction,
                            showExifOverlay = s.showExifOverlay,
                            moveRelatedFiles = s.moveRelatedFiles,
                            recentPathsEnabled = s.recentPathsEnabled,
                            recentPathsCount = s.recentPathsCount,
                            recentPaths = s.recentPaths,
                            seenFirstRunHints = s.seenFirstRunHints,
                            proMode = s.proMode,
                            sourceOutcomes = SwipeRightOutcome.perKind(s),
                        )
                    } else {
                        state.copy(
                            proMode = s.proMode,
                            showExifOverlay = s.showExifOverlay,
                            recentPathsEnabled = s.recentPathsEnabled,
                            recentPathsCount = s.recentPathsCount,
                            recentPaths = s.recentPaths,
                            seenFirstRunHints = s.seenFirstRunHints,
                            sourceOutcomes = SwipeRightOutcome.perKind(s),
                        )
                    }
                }
                val prev = previous
                previous = s
                if (prev != null && (!hasSourceSettingsEmission || _uiState.value.sourceKind == SourceKind.PHONE_LIBRARY)) {
                    if (s.fileTypeFilter != prev.fileTypeFilter &&
                        _uiState.value.allImages.isNotEmpty()
                    ) {
                        feed.refilterAndSort()
                    } else if ((s.sortByOrientation != prev.sortByOrientation ||
                            s.randomizeOrder != prev.randomizeOrder) &&
                        _uiState.value.images.isNotEmpty()
                    ) {
                        feed.resortImages()
                    }
                }
            }
        }
        // URI settings resolve display names via the repository (I/O) — kept separate.
        viewModelScope.launch {
            settingsRepository.phoneCollectionUri.collect { uri ->
                val name = resolveFolderName(uri)
                _uiState.update {
                    // The phone's folder belongs to the phone source: another source (and a Casual
                    // card import, which has its own target) keeps the destinations its own settings give.
                    if (it.importToPhoneCamera || ownedByOtherSource(it)) it
                    else it.copy(collectionFolderUri = uri, collectionFolderName = name)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.phoneLeftSwipeUri.collect { uri ->
                val name = resolveFolderName(uri)
                _uiState.update {
                    if (ownedByOtherSource(it)) it
                    else it.copy(leftSwipeFolderUri = uri, leftSwipeFolderName = name)
                }
            }
        }
        observeSourceSettingsForKind(SourceKind.PHONE_LIBRARY)
    }

    /** True once the per-source settings are live and the open source is not the phone's own. */
    private fun ownedByOtherSource(state: PhoneModeUiState): Boolean =
        hasSourceSettingsEmission && state.sourceKind != SourceKind.PHONE_LIBRARY

    fun observeSourceSettingsForKind(kind: SourceKind) {
        sourceSettingsJob?.cancel()
        sourceSettingsJob = viewModelScope.launch {
            var previous: SourceSettings? = null
            settingsRepository.sourceSettingsFlow(kind).collect { src ->
                hasSourceSettingsEmission = true
                val isCloud = kind == SourceKind.CLOUD_OTHER
                val effectiveVerb = if (isCloud && src.swipeRightVerb == SwipeRightVerb.MOVE) {
                    SwipeRightVerb.COPY
                } else {
                    src.swipeRightVerb
                }
                val effectiveCollectionAction = if (isCloud && src.collectionAction == CollectionAction.MOVE) {
                    CollectionAction.COPY
                } else {
                    src.collectionAction
                }
                val effectiveLeftSwipe = if (isCloud && src.leftSwipeAction == SwipeAction.MOVE) {
                    SwipeAction.COPY
                } else {
                    src.leftSwipeAction
                }

                // Destinations belong to the source kind that is open: a source without a folder of
                // its own uses its own default (the source folder, name `Selection`), never the
                // previous source's folder. Names are resolved here, outside the state update.
                val collectionUri = if (src.importToPhoneCamera) transfer.cameraImportTarget else src.collectionFolderUri
                val collectionName = if (src.importToPhoneCamera) {
                    src.selectionSubfolder
                } else {
                    resolveFolderName(collectionUri)
                }
                val leftSwipeUri = src.leftSwipeFolderUri
                val leftSwipeName = resolveFolderName(leftSwipeUri)

                _uiState.update { state ->
                    state.copy(
                        swipeRightVerb = effectiveVerb,
                        collectionAction = effectiveCollectionAction,
                        feedOrder = src.feedOrder,
                        sortByOrientation = src.sortByOrientation,
                        randomizeOrder = src.randomizeOrder,
                        fileTypeFilter = src.fileTypeFilter,
                        moveRelatedFiles = src.moveRelatedFiles,
                        directDeleteConfirmEnabled = src.directDeleteConfirmEnabled,
                        leftSwipeAction = effectiveLeftSwipe,
                        collectionFolderUri = collectionUri,
                        collectionFolderName = collectionName,
                        leftSwipeFolderUri = leftSwipeUri,
                        leftSwipeFolderName = leftSwipeName,
                        importToPhoneCamera = src.importToPhoneCamera,
                        selectionSubfolderName = src.selectionSubfolder,
                    )
                }
                if (src.importToPhoneCamera) transfer.refreshCameraImportTarget()

                if (isCloud && !cloudMoveCoercedNoticeShown &&
                    (src.swipeRightVerb == SwipeRightVerb.MOVE || src.leftSwipeAction == SwipeAction.MOVE)
                ) {
                    cloudMoveCoercedNoticeShown = true
                    _uiState.showFeedback(UiText.of(R.string.snackbar_cloud_copy_only))
                }

                val prev = previous
                previous = src
                if (prev != null) {
                    if (src.fileTypeFilter != prev.fileTypeFilter &&
                        _uiState.value.allImages.isNotEmpty()
                    ) {
                        feed.refilterAndSort()
                    } else if (src.feedOrder != prev.feedOrder &&
                        _uiState.value.images.isNotEmpty()
                    ) {
                        feed.resortImages()
                    }
                }
            }
        }
    }

    private suspend fun resolveFolderName(uri: String?): String {
        if (uri == null) return ""
        val parsed = Uri.parse(uri)
        return imageRepository.resolveFolderName(parsed) ?: PhotoFolders.nameFromDocumentId(parsed.lastPathSegment)
    }

    /** Toggle the EXIF stats overlay (driven by tapping the Jackpix logo). */
    fun toggleExifOverlay() {
        // Camera info is a Pro feature: in Casual the logo toggle is not offered and never acts.
        if (!_uiState.value.proMode) return
        viewModelScope.launch {
            settingsRepository.setPhoneShowExifOverlay(!_uiState.value.showExifOverlay)
        }
    }

    /** Re-open a previously used source folder from the recents list. */
    fun selectRecentPath(path: RecentPath) {
        if (!VolumeMonitor.isRecentPathMounted(path, _uiState.value.mountedVolumes)) {
            _uiState.showFeedback(UiText.of(R.string.error_not_connected, path.displayName), isError = true)
            return
        }
        feed.selectSourceFolder(Uri.parse(path.uri))
    }

    /**
     * Quick-start for default phone camera roll.
     * If permission is already granted, opens immediately;
     * otherwise calls [onLaunchPicker] with the camera folder's document URI, so the picker
     * opens on DCIM/Camera instead of wherever it was last used.
     */
    fun selectCameraFolder(onLaunchPicker: (Uri) -> Unit) {
        val cameraTreeUri = DocumentsContract.buildTreeDocumentUri(
            PhoneModeViewModel.EXTERNAL_STORAGE_AUTHORITY,
            PhoneModeViewModel.CAMERA_DOCUMENT_ID,
        )
        viewModelScope.launch {
            val name = imageRepository.prepareSourceFolder(cameraTreeUri)
            if (name != null) {
                feed.selectSourceFolder(cameraTreeUri)
            } else {
                onLaunchPicker(PhoneModeViewModel.pickerInitialUri(PhoneModeViewModel.CAMERA_DOCUMENT_ID))
            }
        }
    }

    /**
     * Quick-start for a mounted removable volume (`SD Card / USB Reader` card on landing, `U-09` / `U-14a`).
     */
    fun selectRemovableVolume(volume: ExternalVolume?, onLaunchPicker: (Uri?) -> Unit) {
        val uuid = volume?.uuid
        if (uuid.isNullOrBlank()) {
            onLaunchPicker(null)
            return
        }
        val dcimDocumentId = "$uuid:DCIM"
        val dcimTreeUri = DocumentsContract.buildTreeDocumentUri(PhoneModeViewModel.EXTERNAL_STORAGE_AUTHORITY, dcimDocumentId)
        viewModelScope.launch {
            val name = imageRepository.prepareSourceFolder(dcimTreeUri)
            if (name != null) {
                feed.selectSourceFolder(dcimTreeUri)
            } else {
                onLaunchPicker(PhoneModeViewModel.pickerInitialUri(dcimDocumentId))
            }
        }
    }

    /**
     * Quick-start for the first mounted removable volume (`SD Card / USB Reader` card on landing).
     */
    fun selectSdCardFolder(onLaunchPicker: (Uri?) -> Unit) {
        val vol = _uiState.value.mountedVolumes.firstOrNull { it.isRemovable }
        selectRemovableVolume(vol, onLaunchPicker)
    }

    suspend fun resolveActiveFeedConfig(): ActiveFeedConfig {
        val state = _uiState.value
        if (hasSourceSettingsEmission) {
            return ActiveFeedConfig(
                fileTypeFilter = state.fileTypeFilter,
                feedOrder = state.feedOrder,
                sortByOrientation = state.sortByOrientation,
                randomizeOrder = state.randomizeOrder,
            )
        }
        val phone = settingsRepository.phoneSettings.first()
        return ActiveFeedConfig(
            fileTypeFilter = phone.fileTypeFilter,
            feedOrder = FeedOrderMode.fromLegacyFlags(phone.sortByOrientation, phone.randomizeOrder),
            sortByOrientation = phone.sortByOrientation,
            randomizeOrder = phone.randomizeOrder,
        )
    }

    /**
     * Switch Pro mode on or off (landing toggle). The snackbar names the visible change, the swipes
     * of the last-used source first ("Pro off: left deletes", with Undo; REQ-TOK-FEATURE.20).
     */
    fun setProMode(enabled: Boolean) {
        val state = _uiState.value
        val kind = state.sourceKind.takeIf { state.sourceFolderUri != null } ?: state.recentPaths.firstOrNull()?.kind
        viewModelScope.launch {
            val message = settingsRepository.setProMode(enabled, kind) ?: return@launch
            val undo = message in ProModePolicy.SWIPE_CHANGE_MESSAGES
            _uiState.update { it.copy(lastActionFeedback = ActionFeedback(message, offersUndoProOff = undo)) }
        }
    }

    /** The delete dialog's "Don't ask" acts on the open folder's source kind (REQ-TOK-GESTURE.03). */
    fun updateDirectDeleteConfirm(enabled: Boolean) {
        val kind = settingsKindOfOpenFolder()
        viewModelScope.launch { settingsRepository.setSourceDirectDeleteConfirmEnabled(kind, enabled) }
    }

    /**
     * The source kind whose settings a choice made over the feed belongs to: the open folder's, so
     * "Link RAW + JPEG" on an SD card acts on the card (it always wrote the phone's settings, JP-UI-1).
     * A cloud folder follows the phone's settings and has no Settings tab of its own
     * (`SettingsViewModel.showSourceKind`), so its choices go to the phone's.
     */
    private fun settingsKindOfOpenFolder(): SourceKind =
        _uiState.value.sourceKind.let { if (it == SourceKind.CLOUD_OTHER) SourceKind.PHONE_LIBRARY else it }

    fun applyRawJpegFilter(filter: FileTypeFilter) {
        val kind = settingsKindOfOpenFolder()
        _uiState.update { it.copy(showRawJpegSuggestion = false, firstRunHint = null) }
        viewModelScope.launch {
            settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS)
            settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH)
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneFileTypeFilter(filter)
                if (filter == FileTypeFilter.ONE_PER_SHOT) {
                    settingsRepository.setPhoneMoveRelatedFiles(true)
                }
            } else {
                settingsRepository.setSourceFileTypeFilter(kind, filter)
                if (filter == FileTypeFilter.ONE_PER_SHOT) {
                    settingsRepository.setSourceMoveRelatedFiles(kind, true)
                }
            }
        }
        _uiState.showFeedback(
            UiText.of(
                when (filter) {
                    FileTypeFilter.ONE_PER_SHOT -> R.string.snackbar_pairs_merged
                    FileTypeFilter.RAW -> R.string.snackbar_filter_raw
                    FileTypeFilter.JPG -> R.string.snackbar_filter_jpeg
                    FileTypeFilter.ALL -> R.string.snackbar_filter_all
                },
            ),
        )
    }

    /**
     * Show all on the landing when the format filter hides every photo of the folder (F-TOK-086):
     * turns the filter of the open source kind off, so the feed fills on the spot.
     */
    fun showAllFormats() {
        val kind = _uiState.value.sourceKind
        _uiState.update { it.copy(firstRunHint = null) }
        viewModelScope.launch {
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneFileTypeFilter(FileTypeFilter.ALL)
            } else {
                settingsRepository.setSourceFileTypeFilter(kind, FileTypeFilter.ALL)
            }
        }
        _uiState.showFeedback(UiText.of(R.string.snackbar_filter_all))
    }

    fun enableMoveRelatedFiles() {
        val kind = settingsKindOfOpenFolder()
        _uiState.update { it.copy(showRawJpegSuggestion = false) }
        viewModelScope.launch {
            settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS)
            if (kind == SourceKind.PHONE_LIBRARY) {
                settingsRepository.setPhoneMoveRelatedFiles(true)
            } else {
                settingsRepository.setSourceMoveRelatedFiles(kind, true)
            }
        }
        _uiState.showFeedback(UiText.of(R.string.snackbar_linked))
    }
}
