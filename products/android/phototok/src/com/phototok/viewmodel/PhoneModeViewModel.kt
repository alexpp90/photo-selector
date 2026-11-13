package com.phototok.viewmodel

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.phototok.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselector.core.storage.ExternalVolume
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
import com.phototok.data.source.FileOperationResult
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.RecentPath
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.FavouriteSyncRequest
import com.phototok.data.repository.FavouritesRepository
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.StatsRepository
import com.phototok.data.repository.SystemTrashRepository
import com.phototok.data.share.ShareIntentBuilder
import com.phototok.data.source.VolumeMonitor
import com.phototok.di.ApplicationScope
import com.phototok.domain.CardTransferJournal
import com.phototok.domain.CollectionAction
import com.phototok.domain.CopyMoveFeedback
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FirstRunHintText
import com.phototok.domain.OptimisticFeed
import com.phototok.domain.PendingDeleteLogic
import com.phototok.domain.PhoneFeedOrdering
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import com.phototok.domain.SwipeRightOutcome
import com.phototok.domain.RelatedFiles
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeDestinations
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class PhoneModeViewModel @Inject constructor(
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    private val seenRepository: SeenRepository,
    private val favouritesRepository: FavouritesRepository,
    private val statsRepository: StatsRepository,
    private val volumeMonitor: VolumeMonitor,
    private val cardTransferJournal: CardTransferJournal = CardTransferJournal(),
    private val systemTrash: SystemTrashRepository = SystemTrashRepository(),
) : ViewModel() {

    constructor(
        imageRepository: ImageRepository,
        settingsRepository: SettingsRepository,
        appScope: CoroutineScope,
    ) : this(
        imageRepository = imageRepository,
        settingsRepository = settingsRepository,
        appScope = appScope,
        seenRepository = SeenRepository(),
        favouritesRepository = FavouritesRepository(),
        statsRepository = StatsRepository(),
        volumeMonitor = VolumeMonitor(),
    )

    private val _uiState = MutableStateFlow(PhoneModeUiState())
    val uiState: StateFlow<PhoneModeUiState> = _uiState.asStateFlow()

    /** Folder selection, discovery, ordering, navigation, seen tracking, Reload, EXIF and dimensions. */
    private val feed = FeedDiscoveryHolder(
        _uiState = _uiState,
        viewModelScope = viewModelScope,
        imageRepository = imageRepository,
        settingsRepository = settingsRepository,
        seenRepository = seenRepository,
        statsRepository = statsRepository,
        host = object : FeedDiscoveryHolder.Host {
            override fun finalizePendingDelete() = this@PhoneModeViewModel.finalizePendingDelete()
            override fun flushSystemFavourites() = this@PhoneModeViewModel.flushSystemFavourites()
            override fun cancelSystemIndex() { systemIndexJob?.cancel() }
            override fun indexFeedFromSystem() = this@PhoneModeViewModel.indexFeedFromSystem()
            override fun observeSourceSettingsForKind(kind: SourceKind) = this@PhoneModeViewModel.observeSourceSettingsForKind(kind)
            override suspend fun resolveActiveFeedConfig() = this@PhoneModeViewModel.resolveActiveFeedConfig()
            override fun checkGestureTutorial() = this@PhoneModeViewModel.checkGestureTutorial()
            override fun maybeShowFirstRunHint(hint: FirstRunHint) = this@PhoneModeViewModel.maybeShowFirstRunHint(hint)
            override fun maybeShowRawJpegSuggestion() = this@PhoneModeViewModel.maybeShowRawJpegSuggestion()
            override fun maybeShowFilterMismatchHint() = this@PhoneModeViewModel.maybeShowFilterMismatchHint()
        },
    )

    private var sourceSettingsJob: Job? = null
    private var systemIndexJob: Job? = null

    /** The favourite request the UI is showing or about to show; one at a time. */
    private var inFlightSyncRequest: FavouriteSyncRequest? = null

    /** Queue size when the user last declined; a full queue flushes only once it grew past it. */
    private var favouriteFlushFloor = 0

    /** Whether a per-SourceKind SourceSettings emission has arrived. */
    private var hasSourceSettingsEmission = false

    /** Whether the one-time cloud MOVE->COPY notice has been shown in this session. */
    private var cloudMoveCoercedNoticeShown = false

    /** The phone's camera-folder tree a Casual card import copies into, once verified writable. */
    private var cameraImportTarget: String? = null

    /** The photo whose swipe right is waiting for the camera-folder grant. */
    private var pendingCameraImportUri: String? = null

    private var pendingCameraImportIsCopy = true

    /** Guards against a second swipe while the camera-folder access check is running. */
    private var resolvingCameraImportTarget = false

    companion object {
        private const val TAG = "PhoneModeVM"
        private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** Dimension results are applied to the UI state in batches of this size. */
        internal const val DIMENSION_BATCH_SIZE = 24

        /**
         * Queued favourite changes (MediaStore items, companions included) that trigger a flush
         * while the user stays in the feed. The request starts a short system activity, so it
         * should not follow every swipe.
         */
        internal const val FAVOURITE_FLUSH_THRESHOLD = 25

        /** Feed photos read back from MediaStore per step. */
        private const val SYSTEM_INDEX_CHUNK = 50

        private const val EXTERNAL_STORAGE_AUTHORITY = ProModePolicy.CAMERA_IMPORT_AUTHORITY
        private const val CAMERA_DOCUMENT_ID = ProModePolicy.CAMERA_IMPORT_DOCUMENT_ID

        /**
         * The picker's `EXTRA_INITIAL_URI` for [documentId]. It must be a document URI: the system
         * picker ignores a bare tree URI and reopens wherever it was last used (Drive, say).
         */
        internal fun pickerInitialUri(documentId: String): Uri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)

        internal fun classifyUri(uri: Uri, volumes: List<ExternalVolume> = emptyList()): SourceKind {
            val auth = uri.authority
            // Shorthand URIs in unit tests (e.g. content://tree/photos) lack a dotted package authority.
            if (auth.isNullOrBlank() || !auth.contains('.')) {
                return SourceKind.PHONE_LIBRARY
            }
            return SourceKindClassifier.classify(uri, volumes)
        }
    }

    /** Folders whose committed-but-still-running deletions need their grant until they finish (D-TOK-4). */
    private val foldersWithRunningDeletes = mutableListOf<String>()

    init {
        observeSettings()
        observeRepositories()
        feed.restoreLastFolders()
    }

    /**
     * Pin the grants the undo history needs: the current folder while its deletions are on the
     * undo stack, plus every folder whose deferred deletes are still running on the application
     * scope. Without the second part, switching folders would drop the pin before the deletes ran.
     */
    private fun syncUndoHistoryPin() {
        val state = _uiState.value
        val pins = linkedSetOf<String>()
        if (state.undoStack.isNotEmpty() || state.pendingDelete != null) {
            state.sourceFolderUri?.let(pins::add)
        }
        synchronized(foldersWithRunningDeletes) { pins.addAll(foldersWithRunningDeletes) }
        settingsRepository.pinUndoHistoryFolders(pins)
    }

    private fun observeRepositories() {
        // Deletions still on the undo stack reference their folder, so its grant outlives a switch
        // to another folder until they are committed (D-10).
        viewModelScope.launch {
            _uiState
                .map { if (it.undoStack.isNotEmpty() || it.pendingDelete != null) it.sourceFolderUri else null }
                .distinctUntilChanged()
                .collect { syncUndoHistoryPin() }
        }
        viewModelScope.launch {
            favouritesRepository.snapshot.collect { favs ->
                _uiState.update { it.copy(favourites = favs) }
            }
        }
        // Photos that reached the system bin (a Retry's too) leave the feed if it still shows them.
        viewModelScope.launch { systemTrash.trashed.collect { feed.dropFromFeed(it) } }
        viewModelScope.launch {
            statsRepository.stats.collect { stats ->
                _uiState.update { it.copy(cullingStats = stats) }
            }
        }
        viewModelScope.launch {
            volumeMonitor.mountedVolumes.collect { volumes ->
                _uiState.update { it.copy(mountedVolumes = volumes) }
            }
        }
        viewModelScope.launch {
            volumeMonitor.unmountEvents.collect { unmountedUuid ->
                feed.handleVolumeUnmounted(unmountedUuid)
            }
        }
        viewModelScope.launch {
            settingsRepository.folderSeenProgressMap.collect { map ->
                _uiState.update { it.copy(folderSeenProgress = map) }
            }
        }
        viewModelScope.launch {
            settingsRepository.celebrationsEnabled.collect { enabled ->
                _uiState.update { it.copy(celebrationsEnabled = enabled) }
            }
        }
    }

    // ── Feed: folder selection, discovery, ordering, navigation ([FeedDiscoveryHolder]) ──

    fun selectSourceFolder(uri: Uri) = feed.selectSourceFolder(uri)
    fun reloadSourceFolder() = feed.reloadSourceFolder()
    fun goBackToLanding() = feed.goBackToLanding()
    fun navigateToImage(index: Int) = feed.navigateToImage(index)
    fun toggleSurpriseMe() = feed.toggleSurpriseMe()
    fun onCardDwelled(image: ImageItem) = feed.onCardDwelled(image)
    fun dismissCelebrationBanner() = feed.dismissCelebrationBanner()
    fun hasRawCompanion(image: ImageItem): Boolean = feed.hasRawCompanion(image)
    fun confirmReloadJumpToLatest() = feed.confirmReloadJumpToLatest()
    fun dismissReloadPrompt() = feed.dismissReloadPrompt()
    fun loadDimensionsOfCurrent() = feed.loadDimensionsOfCurrent()

    // ── Settings observation ──────────────────────────────────────────────

    private fun observeSettings() {
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

    private fun observeSourceSettingsForKind(kind: SourceKind) {
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
                val collectionUri = if (src.importToPhoneCamera) cameraImportTarget else src.collectionFolderUri
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
                if (src.importToPhoneCamera) refreshCameraImportTarget()

                if (isCloud && !cloudMoveCoercedNoticeShown &&
                    (src.swipeRightVerb == SwipeRightVerb.MOVE || src.leftSwipeAction == SwipeAction.MOVE)
                ) {
                    cloudMoveCoercedNoticeShown = true
                    showFeedback(UiText.of(R.string.snackbar_cloud_copy_only))
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
            showFeedback(UiText.of(R.string.error_not_connected, path.displayName), isError = true)
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
            EXTERNAL_STORAGE_AUTHORITY,
            CAMERA_DOCUMENT_ID,
        )
        viewModelScope.launch {
            val name = imageRepository.prepareSourceFolder(cameraTreeUri)
            if (name != null) {
                feed.selectSourceFolder(cameraTreeUri)
            } else {
                onLaunchPicker(pickerInitialUri(CAMERA_DOCUMENT_ID))
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
        val dcimTreeUri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, dcimDocumentId)
        viewModelScope.launch {
            val name = imageRepository.prepareSourceFolder(dcimTreeUri)
            if (name != null) {
                feed.selectSourceFolder(dcimTreeUri)
            } else {
                onLaunchPicker(pickerInitialUri(dcimDocumentId))
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

    private suspend fun resolveActiveFeedConfig(): ActiveFeedConfig {
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

    // ── Actions ───────────────────────────────────────────────────────────

    /**
     * Swipe right: perform the configured [SwipeRightVerb] (`FAVOURITE`, `COPY`, or `MOVE`).
     */
    fun addToCollection() {
        val state = _uiState.value
        if (state.images.isEmpty()) return
        val currentImage = state.images[state.currentIndex]
        feed.onCardDwelled(currentImage)
        // No first-run hint: the controls guide shows the swipe once and the result snackbar
        // (or the favourite flash) confirms it (REQ-TOK-UX.04, explain once).

        if (state.swipeRightVerb == SwipeRightVerb.FAVOURITE) {
            val related = if (state.moveRelatedFiles) feed.relatedFeedImages(currentImage) else emptyList()
            favouritesRepository.setFavourite(
                uri = currentImage.uri,
                favourite = true,
                companionUris = related.map { it.uri },
            )
            afterFavouriteChange(listOf(currentImage) + related)
            statsRepository.recordKeeper(state.sourceKind, 1 + related.size)
            feed.advanceToNextImage()
            // No snackbar: the star flash and the lit favourite badge already say it (REQ-TOK-UX.01).
            return
        }

        val isCopy = state.collectionAction == CollectionAction.COPY ||
            state.swipeRightVerb == SwipeRightVerb.COPY ||
            state.isCloudSource
        if (state.importToPhoneCamera) {
            importToPhoneCamera(isCopy = true)
            return
        }
        val target = resolveCollectionTargetUri(state)
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = isCopy,
            subfolderName = state.selectionSubfolderName,
            destination = collectionDestination(state),
        )
    }

    /** Toggle in-place favourite status on the current photo (`U-13`, `U-17`). */
    fun toggleCurrentFavourite() {
        val state = _uiState.value
        val currentImage = state.images.getOrNull(state.currentIndex) ?: return
        val related = if (state.moveRelatedFiles) feed.relatedFeedImages(currentImage) else emptyList()
        val nowFav = favouritesRepository.toggleFavourite(
            uri = currentImage.uri,
            companionUris = related.map { it.uri },
        )
        afterFavouriteChange(listOf(currentImage) + related)
        if (nowFav) {
            statsRepository.recordKeeper(state.sourceKind, 1 + related.size)
            // The favourite badge lights up on the photo; a snackbar would repeat it (REQ-TOK-UX.01).
        } else {
            showFeedback(UiText.of(R.string.snackbar_favourite_removed))
        }
    }

    /** Explicit Copy to Selection action from the long-press context menu (`U-13`). */
    fun copyCurrentToSelection() {
        val state = _uiState.value
        if (state.importToPhoneCamera) {
            importToPhoneCamera(isCopy = true)
            return
        }
        val target = resolveCollectionTargetUri(state)
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = true,
            subfolderName = state.selectionSubfolderName,
            destination = collectionDestination(state),
        )
    }

    /** Explicit Move to Selection action from the long-press context menu (`U-13`). */
    fun moveCurrentToSelection() {
        val state = _uiState.value
        if (state.importToPhoneCamera) {
            importToPhoneCamera(isCopy = false)
            return
        }
        val target = resolveCollectionTargetUri(state)
        val isCopy = state.isCloudSource
        if (isCopy && !cloudMoveCoercedNoticeShown) {
            cloudMoveCoercedNoticeShown = true
        }
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = isCopy,
            subfolderName = state.selectionSubfolderName,
            destination = collectionDestination(state),
        )
    }

    /** Build a system share sheet chooser (`ACTION_SEND`) for the current photo (`U-13`). */
    fun buildCurrentShareIntent(): Intent? {
        val state = _uiState.value
        val currentImage = state.images.getOrNull(state.currentIndex) ?: return null
        val target = ShareIntentBuilder.resolveShareTarget(
            item = currentImage,
            allImages = state.allImages,
            moveRelatedFiles = state.moveRelatedFiles,
            fileTypeFilter = state.fileTypeFilter,
        )
        return ShareIntentBuilder.buildSingleShareChooser(target)
    }

    /** Build an Open-With chooser (`ACTION_VIEW`) for the current photo (`U-13`). */
    fun buildCurrentOpenWithIntent(): Intent? {
        val state = _uiState.value
        val currentImage = state.images.getOrNull(state.currentIndex) ?: return null
        val target = ShareIntentBuilder.resolveShareTarget(
            item = currentImage,
            allImages = state.allImages,
            moveRelatedFiles = state.moveRelatedFiles,
            fileTypeFilter = state.fileTypeFilter,
        )
        return ShareIntentBuilder.buildOpenWithChooser(target)
    }

    // ── Casual card import: SD card / camera to the phone's camera folder ─────

    private fun cameraTreeUri(): Uri =
        DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, CAMERA_DOCUMENT_ID)

    /**
     * Casual swipe right on an SD card or camera: copy the current photo (with its companions) into
     * `DCIM/Camera/Jackpix` on the phone. Without access to the camera folder nothing is copied
     * and the photo stays: [PhoneModeUiState.cameraFolderGrantRequest] asks the UI to open the
     * folder picker, and [onCameraFolderGranted] / [onCameraFolderGrantCancelled] finish the swipe.
     */
    private fun importToPhoneCamera(isCopy: Boolean) {
        val state = _uiState.value
        val image = state.images.getOrNull(state.currentIndex) ?: return
        if (state.cameraFolderGrantRequest != null || resolvingCameraImportTarget) return
        val known = cameraImportTarget
        if (known != null) {
            runCameraImport(known, isCopy)
            return
        }
        resolvingCameraImportTarget = true
        viewModelScope.launch {
            val target = try {
                resolveCameraImportTarget()
            } finally {
                resolvingCameraImportTarget = false
            }
            if (target != null) {
                runCameraImport(target, isCopy)
            } else {
                pendingCameraImportUri = image.uri
                pendingCameraImportIsCopy = isCopy
                _uiState.update {
                    it.copy(cameraFolderGrantRequest = CameraFolderGrantRequest(pickerInitialUri(CAMERA_DOCUMENT_ID)))
                }
            }
        }
    }

    private fun runCameraImport(target: String, isCopy: Boolean) {
        val state = _uiState.value
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = isCopy,
            subfolderName = PhotoFolders.CASUAL_IMPORT,
            destination = UiText.raw(PhotoFolders.CASUAL_IMPORT),
        )
    }

    /**
     * A tree we can write to for the import: the one granted before, else the camera folder the
     * Camera source already holds. Remembered (with a durable grant purpose) once found.
     */
    private suspend fun resolveCameraImportTarget(): String? {
        val stored = settingsRepository.casualCameraTargetUri.first()
        val found = listOfNotNull(stored, cameraTreeUri().toString()).distinct()
            .firstOrNull { imageRepository.resolveFolderName(Uri.parse(it)) != null }
        if (found != null && found != stored) settingsRepository.setCasualCameraTarget(found)
        setCameraImportTarget(found)
        return found
    }

    private fun refreshCameraImportTarget() {
        viewModelScope.launch { resolveCameraImportTarget() }
    }

    private fun setCameraImportTarget(uri: String?) {
        cameraImportTarget = uri
        _uiState.update { if (it.importToPhoneCamera) it.copy(collectionFolderUri = uri) else it }
    }

    /** The user picked a folder for the pending Casual import: keep the grant and copy that photo. */
    fun onCameraFolderGranted(uri: Uri) {
        val pendingUri = pendingCameraImportUri
        val isCopy = pendingCameraImportIsCopy
        pendingCameraImportUri = null
        _uiState.update { it.copy(cameraFolderGrantRequest = null) }
        if (pendingUri == null) return
        viewModelScope.launch {
            if (imageRepository.resolveFolderName(uri) == null) {
                showFeedback(UiText.of(R.string.snackbar_not_copied), isError = true)
                return@launch
            }
            settingsRepository.setCasualCameraTarget(uri.toString())
            setCameraImportTarget(uri.toString())
            val state = _uiState.value
            val index = state.images.indexOfFirst { it.uri == pendingUri }
            if (index < 0) {
                showFeedback(UiText.of(R.string.snackbar_not_copied), isError = true)
                return@launch
            }
            if (index != state.currentIndex) feed.navigateToImage(index)
            runCameraImport(uri.toString(), isCopy)
        }
    }

    /** The picker was dismissed: nothing is copied and the photo stays. */
    fun onCameraFolderGrantCancelled() {
        val hadPending = pendingCameraImportUri != null
        pendingCameraImportUri = null
        _uiState.update { it.copy(cameraFolderGrantRequest = null) }
        if (hadPending) showFeedback(UiText.of(R.string.snackbar_not_copied))
    }

    /** The tree swipe right copies into; Settings shows the same resolution (REQ-TOK-FEATURE.23). */
    private fun resolveCollectionTargetUri(state: PhoneModeUiState): String? =
        SwipeDestinations.selection(state.collectionFolderUri, state.sourceFolderUri, state.selectionSubfolderName).treeUri

    /** Name of the Selection destination for feedback: the configured folder, else the default. */
    private fun collectionDestination(state: PhoneModeUiState): UiText =
        UiText.raw(state.collectionLabel)

    /**
     * Swipe left, by drag or the TalkBack action: the configured copy/move, or a delete, which
     * first asks when the confirmation is on (the dialog shows while [PhoneModeUiState.confirmDeleteRequested]).
     */
    fun onSwipeLeft() {
        val state = _uiState.value
        if (state.leftSwipeAction != SwipeAction.DELETE) {
            performLeftSwipeCopyOrMove()
            return
        }
        val image = state.images.getOrNull(state.currentIndex) ?: return
        if (state.directDeleteConfirmEnabled || settingsRepository.shouldAskDeleteOnce()) {
            // The dialog says whether this photo's files go to the system bin or for good.
            val files = listOf(image) + if (state.moveRelatedFiles) RelatedFiles.deleteBundleOf(state.allImages, image) else emptyList()
            viewModelScope.launch {
                val split = systemTrash.split(files.map { it.uri }, state.sourceKind)
                _uiState.update { it.copy(confirmDeleteRequested = true, confirmDeleteSplit = split) }
            }
        } else {
            requestDelete()
        }
    }

    /** Delete in the confirmation dialog; [dontAskAgain] is its "Don't ask" checkbox. */
    fun confirmDelete(dontAskAgain: Boolean) {
        _uiState.update { it.copy(confirmDeleteRequested = false) }
        settingsRepository.clearAskDeleteOnce()
        if (dontAskAgain) updateDirectDeleteConfirm(false)
        requestDelete()
    }

    /** Cancel in the confirmation dialog: nothing is deleted and the setting is unchanged. */
    fun cancelDelete() {
        _uiState.update { it.copy(confirmDeleteRequested = false) }
        settingsRepository.clearAskDeleteOnce()
    }

    /** Swipe left (copy/move mode): copy or move the current photo to the custom folder. */
    fun performLeftSwipeCopyOrMove() {
        val state = _uiState.value
        if (state.images.isNotEmpty()) {
            feed.onCardDwelled(state.images[state.currentIndex])
        }
        val destination = SwipeDestinations.leftSwipe(state.leftSwipeFolderUri, state.sourceFolderUri)
        copyOrMoveCurrent(
            targetUri = destination.treeUri,
            isCopy = state.leftSwipeAction == SwipeAction.COPY || state.isCloudSource,
            subfolderName = destination.subfolder,
            destination = UiText.raw(state.leftSwipeFolderName.ifBlank { PhotoFolders.LEFT_SWIPE }),
        )
    }

    /**
     * Copy or move the current image plus its related siblings.
     */
    private fun copyOrMoveCurrent(
        targetUri: String?,
        isCopy: Boolean,
        subfolderName: String,
        destination: UiText,
    ) {
        val state = _uiState.value
        if (state.images.isEmpty()) return
        if (targetUri == null) return

        val currentImage = state.images[state.currentIndex]
        val related = if (state.moveRelatedFiles) feed.relatedImages(currentImage) else emptyList()
        val targets = listOf(currentImage) + related
        val slots = OptimisticFeed.slotsOf(state.images, state.allImages, targets)
        val epochAtLaunch = feed.feedEpoch

        // ── Optimistic UI: show the next photo, then do the work ──────────
        if (isCopy) {
            feed.advanceToNextImage()
        } else {
            feed.removeImagesFromLists(targets.map { it.uri }.toSet())
        }

        appScope.launch {
            try {
                val sortingEnabled = settingsRepository.sortingEnabled.first()
                val folderUri = Uri.parse(targetUri)

                val succeededUris = mutableSetOf<String>()
                // (source, destination) pairs, so favourites follow their files.
                val movedFiles = mutableListOf<Pair<String, String>>()
                val copiedFiles = mutableListOf<Pair<String, String>>()
                var failed = 0
                var partial = 0
                var renamedTo: String? = null
                // A shot with companions travels as one bundle (shared collision suffix); a
                // single file keeps the per-file path.
                val results: List<FileOperationResult> = if (targets.size > 1) {
                    imageRepository.transferBundleDetailed(
                        sourceUris = targets.map { Uri.parse(it.uri) },
                        destFolderUri = folderUri,
                        sorting = sortingEnabled,
                        subfolderName = subfolderName,
                        isMove = !isCopy,
                    )
                } else {
                    targets.map { img ->
                        if (isCopy) {
                            imageRepository.copyImageDetailed(
                                sourceUri = Uri.parse(img.uri),
                                destFolderUri = folderUri,
                                sorting = sortingEnabled,
                                subfolderName = subfolderName,
                            )
                        } else {
                            imageRepository.moveImageDetailed(
                                sourceUri = Uri.parse(img.uri),
                                destFolderUri = folderUri,
                                sorting = sortingEnabled,
                                subfolderName = subfolderName,
                            )
                        }
                    }
                }
                targets.forEachIndexed { i, img ->
                    val result = results.getOrNull(i)
                        ?: FileOperationResult.failure(img.uri, "No result") // ui-text: ignore internal result code, never shown
                    when {
                        result.success -> {
                            succeededUris.add(img.uri)
                            result.destinationUri?.let { dest ->
                                (if (isCopy) copiedFiles else movedFiles) += img.uri to dest
                            }
                            if (img === currentImage) {
                                // The primary file got a collision suffix: say which name it became.
                                CopyMoveFeedback.renamedName(img.fileName, result.destinationUri)?.let { renamedTo = it }
                            }
                            val dest = result.destinationUri ?: targetUri
                            cardTransferJournal.recordTransfer(state.sourceKind, img.uri, state.sourceFolderUri, dest)
                        }
                        result.partial -> {
                            partial++
                            // The verified copy exists and the original stays: both are the photo.
                            result.destinationUri?.let { dest -> copiedFiles += img.uri to dest }
                            cardTransferJournal.recordTransfer(state.sourceKind, img.uri, state.sourceFolderUri, result.destinationUri)
                        }
                        else -> failed++
                    }
                }
                favouritesRepository.followTransfer(moved = movedFiles, copied = copiedFiles)
                val destinations = (movedFiles + copiedFiles).map { it.second }
                if (destinations.isNotEmpty()) {
                    // Best effort: a MediaStore lookup must never turn a finished transfer into a failure.
                    try {
                        favouritesRepository.indexUrisFromSystem(destinations, onlyUnresolved = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not index ${destinations.size} transferred photos", e)
                    }
                }

                // The feed may belong to another folder (or none) by now: files that stayed behind
                // are rediscovered by that folder's own scan, never inserted into a foreign feed.
                val feedStillCurrent = feed.feedEpoch == epochAtLaunch
                if (!isCopy && (failed > 0 || partial > 0) && feedStillCurrent) {
                    // The optimistic removal was wrong for files that are still in the source.
                    restoreToFeed(OptimisticFeed.slotsLeftBehind(slots, goneUris = succeededUris))
                }

                val message = CopyMoveFeedback.message(
                    isCopy = isCopy,
                    destination = destination,
                    succeeded = succeededUris.size,
                    failed = failed,
                    relatedCount = related.size,
                    partial = partial,
                    renamedTo = renamedTo,
                )
                val copiedOnCard = cardTransferJournal.copiedCountForFolder(state.sourceFolderUri ?: "")
                // The user did perform this action, so its outcome is still reported; the per-card
                // counter only follows the card that is on screen.
                _uiState.update {
                    it.copy(
                        cardCopiedCount = if (feedStillCurrent) copiedOnCard else it.cardCopiedCount,
                        lastActionFeedback = ActionFeedback(
                            message = message,
                            isError = CopyMoveFeedback.isError(failed, partial),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCopy && feed.feedEpoch == epochAtLaunch) restoreToFeed(slots)
                _uiState.update {
                    it.copy(
                        lastActionFeedback = ActionFeedback(UiText.of(R.string.error_transfer_failed), isError = true),
                    )
                }
            }
        }
    }

    /** Put optimistically removed photos back where they were. */
    private fun restoreToFeed(slots: List<OptimisticFeed.Slot>) {
        if (slots.isEmpty()) return
        // Runs on the application scope while swipes and discovery batches keep writing the feed:
        // the new lists are computed from the state this write replaces, never from an earlier read.
        _uiState.update { s ->
            val lists = OptimisticFeed.restore(
                images = s.images,
                allImages = s.allImages,
                slots = slots,
                currentIndex = s.currentIndex,
                sortByOrientation = s.sortByOrientation,
            )
            s.copy(
                images = lists.images,
                allImages = lists.allImages,
                currentIndex = lists.currentIndex,
                portraitSectionStart = lists.portraitSectionStart,
            )
        }
    }

    /**
     * Swipe left: request delete (pushes onto the bounded LIFO undo stack, `U-18`).
     */
    fun requestDelete() {
        val initialState = _uiState.value
        if (initialState.images.isEmpty()) return

        feed.onCardDwelled(initialState.images[initialState.currentIndex])

        val state = _uiState.value
        if (state.images.isEmpty()) return
        val imageToDelete = state.images[state.currentIndex]
        val related = if (state.moveRelatedFiles) {
            RelatedFiles.deleteBundleOf(state.allImages, imageToDelete)
        } else {
            emptyList()
        }

        val pushResult = PendingDeleteLogic.pushRemove(
            images = state.images,
            allImages = state.allImages,
            currentIndex = state.currentIndex,
            related = related,
            currentStack = state.undoStack,
            sortByOrientation = state.sortByOrientation,
        ) ?: return

        // If the bounded stack overflowed (51st item), finalize the evicted oldest entry on disk.
        pushResult.evictedOldest?.let { evicted ->
            finalizePendingEntriesOnDisk(listOf(evicted), state.sourceFolderUri, state.sourceKind, release = false)
        }

        val allQueuedUris = PendingDeleteLogic.allTargetUris(pushResult.updatedStack)
        val allQueuedBytes = pushResult.updatedStack.sumOf { p ->
            p.image.fileSize + p.related.sumOf { it.fileSize }
        }
        statsRepository.updateQueuedDeletes(allQueuedUris.size, allQueuedBytes)

        _uiState.update {
            it.copy(
                images = pushResult.state.images,
                allImages = pushResult.state.allImages,
                currentIndex = pushResult.state.currentIndex,
                portraitSectionStart = pushResult.state.portraitSectionStart,
                undoStack = pushResult.updatedStack,
                pendingDelete = pushResult.updatedStack.lastOrNull(),
            )
        }
        feed.persistCurrentFolderPosition()
        viewModelScope.launch {
            settingsRepository.setPersistedPendingDeleteUris(allQueuedUris.toSet())
        }
        feed.loadExifForCurrent()
        // The screen only shows the next photo, so say what went and offer the way back
        // ([USER 2026-10-24]: undo snackbar after a delete; the bar stays icon-only).
        _uiState.update {
            it.copy(
                lastActionFeedback = ActionFeedback(
                    message = UiText.of(R.string.snackbar_photo_deleted),
                    offersUndoDelete = true,
                ),
            )
        }
    }

    /**
     * Delete all pending images in the undo stack (and their siblings) from disk.
     * Runs on the application scope so it also completes when the ViewModel is cleared.
     */
    fun finalizePendingDelete() {
        val state = _uiState.value
        val entries = if (state.undoStack.isNotEmpty()) {
            state.undoStack
        } else {
            listOfNotNull(state.pendingDelete)
        }
        // Nothing queued: an evicted delete still waiting for the bin goes out now.
        if (entries.isEmpty()) return systemTrash.release()

        val folderAtDelete = state.sourceFolderUri
        val kindAtDelete = state.sourceKind
        _uiState.update { it.copy(pendingDelete = null, undoStack = emptyList()) }
        statsRepository.updateQueuedDeletes(0, 0L)

        finalizePendingEntriesOnDisk(entries, folderAtDelete, kindAtDelete)
    }

    private fun finalizePendingEntriesOnDisk(
        entries: List<PendingDeleteLogic.Pending>,
        folderAtDelete: String?,
        kindAtDelete: SourceKind,
        release: Boolean = true,
    ) {
        // Registered synchronously, before the caller's state change can drop the folder's pin.
        if (folderAtDelete != null) {
            synchronized(foldersWithRunningDeletes) { foldersWithRunningDeletes.add(folderAtDelete) }
            syncUndoHistoryPin()
        }
        appScope.launch {
            try {
                runPendingDeletes(entries, folderAtDelete, kindAtDelete, release)
            } finally {
                // The deletions are over: unpin first, or the release below would be refused.
                withContext(NonCancellable) {
                    if (folderAtDelete != null) {
                        synchronized(foldersWithRunningDeletes) { foldersWithRunningDeletes.remove(folderAtDelete) }
                        syncUndoHistoryPin()
                        // A folder that was left meanwhile no longer needs its grant.
                        settingsRepository.releaseFolderGrantIfUnreferenced(folderAtDelete)
                    }
                }
            }
        }
    }

    private suspend fun runPendingDeletes(
        entries: List<PendingDeleteLogic.Pending>,
        folderAtDelete: String?,
        kindAtDelete: SourceKind,
        release: Boolean,
    ) {
        // Only these entries leave the journal: others may have been queued meanwhile.
        settingsRepository.removePersistedPendingDeleteUris(
            entries.flatMap { p -> (listOf(p.image) + p.related).map { it.uri } },
        )
        // Phone photos wait here for the system bin's consent; the rest is deleted for good.
        val outcome = PendingDeleteCommit(imageRepository, systemTrash).commit(entries, kindAtDelete, release)
        if (outcome.restore.isNotEmpty() && _uiState.value.sourceFolderUri == folderAtDelete) {
            outcome.restore.forEach(::restoreToFeed)
            feed.loadExifForCurrent()
        }
        // A rescan that started while the unlinks ran may have listed these files again.
        if (outcome.gone.isNotEmpty()) feed.dropFromFeed(outcome.gone)
        if (outcome.permanentCount > 0) {
            val milestone = statsRepository.recordCommittedDelete(
                kind = kindAtDelete,
                bytesFreed = outcome.permanentBytes,
                fileCount = outcome.permanentCount,
                celebrationsEnabled = _uiState.value.celebrationsEnabled,
            )
            _uiState.update { state ->
                state.copy(
                    cardDeletedCount = state.cardDeletedCount + outcome.permanentCount,
                    celebrationBanner = milestone?.message ?: state.celebrationBanner,
                )
            }
        }
        val failed = outcome.failed
        if (failed.isNotEmpty()) {
            val done = outcome.gone.size
            val errorMsg = if (done == 0) {
                UiText.of(R.string.error_delete_one, failed.first().fileName)
            } else {
                UiText.of(R.string.error_delete_some, done, done + failed.size)
            }
            _uiState.update { it.copy(lastActionFeedback = ActionFeedback(message = errorMsg, isError = true)) }
        }
    }

    /**
     * Pop the most recent pending deletion from the LIFO undo stack and restore it (`U-18`).
     */
    fun revertDelete() {
        val state = _uiState.value
        val stack = if (state.undoStack.isNotEmpty()) {
            state.undoStack
        } else {
            listOfNotNull(state.pendingDelete)
        }
        if (stack.isEmpty()) return

        // No first-run hint here: the restored photo is already on screen (REQ-TOK-UX.01).

        val popResult = PendingDeleteLogic.popRestore(
            images = state.images,
            allImages = state.allImages,
            currentStack = stack,
            sortByOrientation = state.sortByOrientation,
        ) ?: return

        val remainingUris = PendingDeleteLogic.allTargetUris(popResult.remainingStack)
        val remainingBytes = popResult.remainingStack.sumOf { p ->
            p.image.fileSize + p.related.sumOf { it.fileSize }
        }
        statsRepository.updateQueuedDeletes(remainingUris.size, remainingBytes)

        _uiState.update {
            it.copy(
                images = popResult.state.images,
                allImages = popResult.state.allImages,
                currentIndex = popResult.state.currentIndex,
                portraitSectionStart = popResult.state.portraitSectionStart,
                undoStack = popResult.remainingStack,
                pendingDelete = popResult.remainingStack.lastOrNull(),
            )
        }
        feed.persistCurrentFolderPosition()
        viewModelScope.launch {
            settingsRepository.setPersistedPendingDeleteUris(remainingUris.toSet())
        }
        feed.loadExifForCurrent()
    }

    // ── "Done with this card" post-offload sheet (`U-14c`) ───────────────

    fun openCardDoneSheet() {
        val state = _uiState.value
        val copied = cardTransferJournal.copiedCountForFolder(state.sourceFolderUri ?: "")
        _uiState.update {
            it.copy(
                showCardDoneSheet = true,
                cardCopiedCount = copied,
                cardClearStep = 0,
                cardClearComplete = false,
            )
        }
    }

    fun dismissCardDoneSheet() {
        _uiState.update {
            it.copy(
                showCardDoneSheet = false,
                cardClearStep = 0,
            )
        }
    }

    /** Option 1 (default): Keep all files on card. */
    fun keepAllFilesOnCard() {
        _uiState.update {
            it.copy(
                showCardDoneSheet = false,
                cardClearStep = 0,
            )
        }
        showFeedback(UiText.of(R.string.snackbar_safe_to_unplug))
    }

    /** Option 2: Delete only the photos swiped left during this session. */
    fun deleteQueuedRejectsOnCard() {
        finalizePendingDelete()
        _uiState.update {
            it.copy(
                showCardDoneSheet = false,
                cardClearStep = 0,
            )
        }
        showFeedback(UiText.of(R.string.snackbar_rejects_deleted))
    }

    /** Step 1 -> Step 2 for Option 3 ("Clear entire card"). */
    fun advanceClearCardConfirmation() {
        _uiState.update { state ->
            state.copy(cardClearStep = (state.cardClearStep + 1).coerceAtMost(2))
        }
    }

    fun cancelClearCardConfirmation() {
        _uiState.update { it.copy(cardClearStep = 0) }
    }

    /**
     * Final confirmation for Option 3: Clear entire card (`U-14c`). It deletes exactly the files
     * [PhoneModeUiState.cardClearFileCount] announced, and only once the scan has finished. The
     * card counts as cleared only when nothing is left on it; files that could not be deleted stay
     * in the feed and the outcome says how many went.
     */
    fun confirmClearEntireCard() {
        val state = _uiState.value
        if (state.isDiscovering || state.isLoading) return
        val toDelete = state.cardClearTargets
        if (toDelete.isEmpty()) {
            _uiState.update {
                it.copy(
                    showCardDoneSheet = false,
                    cardClearStep = 0,
                    cardClearComplete = true,
                )
            }
            showFeedback(UiText.of(R.string.snackbar_safe_to_unplug))
            return
        }
        _uiState.update { it.copy(cardClearStep = 3) }
        val epochAtLaunch = feed.feedEpoch
        appScope.launch {
            val deletedUris = mutableSetOf<String>()
            var freedBytes = 0L
            for (img in toDelete) {
                val ok = try {
                    imageRepository.deleteImage(Uri.parse(img.uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    deletedUris.add(img.uri)
                    freedBytes += img.fileSize
                }
            }
            val deletedCount = deletedUris.size
            // A wiped card starts a new count; a card with files left keeps what was copied off it.
            if (deletedCount == toDelete.size) cardTransferJournal.clearSource(state.sourceFolderUri ?: "")
            if (deletedCount > 0) {
                statsRepository.recordCommittedDelete(
                    kind = state.sourceKind,
                    bytesFreed = freedBytes,
                    fileCount = deletedCount,
                    celebrationsEnabled = state.celebrationsEnabled,
                )
            }
            if (feed.feedEpoch != epochAtLaunch) {
                // Left the card meanwhile: the deletions stand, but the screen shows something else.
                _uiState.update { it.copy(cardClearStep = 0) }
                showFeedback(UiText.of(R.string.snackbar_deleted_count, deletedCount))
                return@launch
            }
            val leftBehind = toDelete.size - deletedCount
            _uiState.update { s ->
                val images = s.images.filter { it.uri !in deletedUris }
                val all = s.allImages.filter { it.uri !in deletedUris }
                s.copy(
                    images = images,
                    allImages = all,
                    currentIndex = s.currentIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
                    portraitSectionStart = PhoneFeedOrdering.portraitSplit(images, s.sortByOrientation),
                    showCardDoneSheet = false,
                    cardClearStep = 0,
                    cardClearComplete = leftBehind == 0 && all.isEmpty(),
                    cardDeletedCount = s.cardDeletedCount + deletedCount,
                    emptyFolderMessage = if (leftBehind == 0 && all.isEmpty()) {
                        UiText.of(R.string.empty_card_cleared)
                    } else {
                        s.emptyFolderMessage
                    },
                )
            }
            if (leftBehind == 0) {
                showFeedback(UiText.of(R.string.snackbar_deleted_count, deletedCount))
            } else {
                showFeedback(UiText.of(R.string.error_delete_some, deletedCount, toDelete.size), isError = true)
            }
        }
    }

    // ── System favourites (MediaStore IS_FAVORITE, `U-17`) ───────────────────

    /**
     * Resolve the photos just (un)favourited to MediaStore so the change is queued with its
     * MediaStore URI, then flush if the queue has grown past [FAVOURITE_FLUSH_THRESHOLD].
     */
    private fun afterFavouriteChange(items: List<ImageItem>) {
        viewModelScope.launch {
            favouritesRepository.indexFromSystem(items, onlyUnresolved = true)
            val queued = favouritesRepository.sendablePendingCount()
            if (queued - favouriteFlushFloor >= FAVOURITE_FLUSH_THRESHOLD) {
                flushSystemFavourites()
            }
        }
    }

    /**
     * Read the MediaStore favourite flags of the feed (read-back, conflict rule in
     * `FavouriteSyncLogic`), in chunks starting at the photo on screen, so favourites set in
     * another app show here and favourites set here have their MediaStore URI before the flush.
     */
    private fun indexFeedFromSystem() {
        systemIndexJob?.cancel()
        val state = _uiState.value
        if (state.sourceKind != SourceKind.PHONE_LIBRARY && state.sourceKind != SourceKind.REMOVABLE) return
        val current = state.images.getOrNull(state.currentIndex)?.uri
        val all = state.allImages
        val start = all.indexOfFirst { it.uri == current }.coerceAtLeast(0)
        val ordered = all.indices.sortedBy { abs(it - start) }.map { all[it] }
        systemIndexJob = viewModelScope.launch {
            for (chunk in ordered.chunked(SYSTEM_INDEX_CHUNK)) {
                favouritesRepository.indexFromSystem(chunk)
            }
        }
    }

    /**
     * A flush point (leaving the feed, switching folders, opening the Selection viewer, or a full
     * queue): hand the next favourite request to the UI. One request runs at a time; a long queue
     * goes out as consecutive requests of up to `FavouriteSyncLogic.MAX_BATCH` photos.
     */
    fun flushSystemFavourites() {
        if (inFlightSyncRequest != null) return
        val request = favouritesRepository.nextSyncRequest() ?: return
        inFlightSyncRequest = request
        _uiState.update { it.copy(pendingIntentSenderRequest = request) }
    }

    /** The UI launched [request]; it is not launched again (rotation, recomposition). */
    fun onSystemFavouriteRequestLaunched(request: FavouriteSyncRequest) {
        _uiState.update { if (it.pendingIntentSenderRequest === request) it.copy(pendingIntentSenderRequest = null) else it }
    }

    /**
     * The system answered. Accepted: confirm (with a read-back) and send the next batch.
     * Declined or failed to launch: keep everything queued and stop for now. The next flush point
     * tries again; a full queue only triggers again once it has grown by another threshold, so a
     * user who declines is not asked after every swipe.
     */
    fun onSystemFavouriteResult(request: FavouriteSyncRequest, accepted: Boolean) {
        if (inFlightSyncRequest !== request) return
        _uiState.update { if (it.pendingIntentSenderRequest === request) it.copy(pendingIntentSenderRequest = null) else it }
        viewModelScope.launch {
            favouritesRepository.onSyncResult(request, accepted)
            inFlightSyncRequest = null
            if (accepted) {
                favouriteFlushFloor = 0
                flushSystemFavourites()
            } else {
                favouriteFlushFloor = favouritesRepository.sendablePendingCount()
            }
        }
    }

    /** Result of the request the UI launched last (the launcher survives a rotation, the request object may not). */
    fun onLaunchedFavouriteRequestResult(accepted: Boolean) {
        inFlightSyncRequest?.let { onSystemFavouriteResult(it, accepted) }
    }

    /** Opening the Selection viewer is a flush point: the user is looking at their favourites. */
    fun onSelectionViewerOpened() {
        flushSystemFavourites()
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

    fun clearFeedback() {
        _uiState.update { it.copy(lastActionFeedback = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: UiText) {
        _uiState.update { it.copy(error = message) }
    }

    // ── Gesture tutorial ──────────────────────────────────────────────────

    private fun checkGestureTutorial() {
        viewModelScope.launch {
            val tutorialTs = settingsRepository.phoneGestureTutorialTs.first()
            val lastUsed = settingsRepository.lastAppUsedTs.first()
            val now = System.currentTimeMillis()
            // Tutorial fires on fresh install (never seen) OR after 7 days of inactivity (app not used for 7 days)
            if (tutorialTs == 0L || (lastUsed > 0L && (now - lastUsed) > ONE_WEEK_MS)) {
                _uiState.update { it.copy(showGestureTutorial = true) }
            }
            settingsRepository.recordAppUsed(now)
        }
    }

    fun dismissGestureTutorial() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            settingsRepository.setPhoneGestureTutorialTs(now)
            settingsRepository.recordAppUsed(now)
            _uiState.update { it.copy(showGestureTutorial = false) }
        }
    }

    // ── Controls guide (info button) ──────────────────────────────────────

    fun showControlsGuide() {
        _uiState.update { it.copy(showControlsGuide = true) }
    }

    fun hideControlsGuide() {
        _uiState.update { it.copy(showControlsGuide = false) }
    }

    // ── One-time action explanations ──────────────────────────────────────

    fun maybeShowFirstRunHint(hint: FirstRunHint) {
        val state = _uiState.value
        if (hint.key in state.seenFirstRunHints) return
        // A gesture hint explains the feed; without one it would be marked seen and never drawn.
        if (state.images.isEmpty()) return
        // Never stack a hint on top of the full-screen tutorial.
        if (state.showGestureTutorial) return

        _uiState.update {
            it.copy(
                seenFirstRunHints = it.seenFirstRunHints + hint.key,
                firstRunHint = FirstRunHintUi(
                    hint = hint,
                    title = FirstRunHintText.title(
                        hint = hint,
                        swipeRightVerb = state.swipeRightVerb,
                        collectionAction = state.collectionAction,
                        leftSwipeAction = state.leftSwipeAction,
                        collectionFolderName = state.collectionLabel,
                        leftSwipeFolderName = state.leftSwipeFolderName,
                    ),
                    message = FirstRunHintText.message(hint),
                ),
            )
        }

        viewModelScope.launch { settingsRepository.markFirstRunHintSeen(hint) }
    }

    fun dismissFirstRunHint() {
        _uiState.update { it.copy(firstRunHint = null) }
    }

    /** The hint that matches the configured left-swipe action. */
    // ── RAW + JPEG pair suggestions (`U-12`) ─────────────────────────────

    fun maybeShowRawJpegSuggestion() {
        val state = _uiState.value
        if (!state.proMode) return
        if (state.showRawJpegSuggestion) return
        if (FirstRunHint.RAW_JPEG_PAIRS.key in state.seenFirstRunHints) return
        if (state.fileTypeFilter == FileTypeFilter.ONE_PER_SHOT) return
        if (state.moveRelatedFiles && state.fileTypeFilter != FileTypeFilter.ALL) return
        if (RelatedFiles.hasRawJpegPairs(state.allImages)) {
            _uiState.update { it.copy(showRawJpegSuggestion = true) }
        }
    }

    fun dismissRawJpegSuggestion() {
        _uiState.update { it.copy(showRawJpegSuggestion = false) }
        viewModelScope.launch {
            settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS)
        }
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
        showFeedback(
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
        showFeedback(UiText.of(R.string.snackbar_filter_all))
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
        showFeedback(UiText.of(R.string.snackbar_linked))
    }

    // ── Filter mismatch hint ─────────────────────────────────────────────

    fun maybeShowFilterMismatchHint() {
        val state = _uiState.value
        if (!state.proMode) return
        if (state.showGestureTutorial || state.showControlsGuide) return
        if (state.showRawJpegSuggestion) return
        if (state.firstRunHint != null) return
        if (FirstRunHint.FILTER_MISMATCH.key in state.seenFirstRunHints) return
        if (state.fileTypeFilter == FileTypeFilter.ALL || state.fileTypeFilter == FileTypeFilter.ONE_PER_SHOT) return
        if (state.allImages.isEmpty()) return
        // The filter hides the whole folder: the landing says so with its own Show all, every time.
        if (state.images.isEmpty()) return

        if (RelatedFiles.hasFilterMismatch(state.allImages, state.fileTypeFilter)) {
            val title = FirstRunHintText.title(
                hint = FirstRunHint.FILTER_MISMATCH,
                fileTypeFilter = state.fileTypeFilter,
            )
            val message = FirstRunHintText.message(
                hint = FirstRunHint.FILTER_MISMATCH,
                fileTypeFilter = state.fileTypeFilter,
            )
            _uiState.update {
                it.copy(
                    seenFirstRunHints = it.seenFirstRunHints + FirstRunHint.FILTER_MISMATCH.key,
                    firstRunHint = FirstRunHintUi(
                        hint = FirstRunHint.FILTER_MISMATCH,
                        title = title,
                        message = message,
                        actionLabel = UiText.of(R.string.action_show_all),
                        onAction = { applyRawJpegFilter(FileTypeFilter.ALL) },
                    ),
                )
            }
            viewModelScope.launch {
                settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH)
            }
        }
    }

    private fun showFeedback(message: UiText, isError: Boolean = false) = _uiState.showFeedback(message, isError)

    public override fun onCleared() {
        // Finalizes primary AND sibling files on the application scope, so the
        // deletion completes even though the ViewModel is going away.
        finalizePendingDelete()
        super.onCleared()
    }
}
