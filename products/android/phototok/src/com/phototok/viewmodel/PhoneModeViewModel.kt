package com.phototok.viewmodel

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.phototok.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselector.core.storage.ExternalVolume
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
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
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FirstRunHintText
import com.phototok.domain.PhotoFolders
import com.phototok.domain.ProModePolicy
import com.phototok.domain.SwipeRightOutcome
import com.phototok.domain.RelatedFiles
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val feed: FeedDiscoveryHolder = FeedDiscoveryHolder(
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

    /** Swipe transfers, the Casual card import, deletes with undo, the "Done with this card" sheet. */
    private val transfer: TransferUndoHolder = TransferUndoHolder(
        _uiState = _uiState,
        viewModelScope = viewModelScope,
        appScope = appScope,
        feed = feed,
        imageRepository = imageRepository,
        settingsRepository = settingsRepository,
        favouritesRepository = favouritesRepository,
        statsRepository = statsRepository,
        cardTransferJournal = cardTransferJournal,
        systemTrash = systemTrash,
        host = object : TransferUndoHolder.Host {
            override var cloudMoveCoercedNoticeShown: Boolean
                get() = this@PhoneModeViewModel.cloudMoveCoercedNoticeShown
                set(value) { this@PhoneModeViewModel.cloudMoveCoercedNoticeShown = value }
            override fun afterFavouriteChange(items: List<ImageItem>) = this@PhoneModeViewModel.afterFavouriteChange(items)
            override fun updateDirectDeleteConfirm(enabled: Boolean) = this@PhoneModeViewModel.updateDirectDeleteConfirm(enabled)
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

    companion object {
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

        internal const val EXTERNAL_STORAGE_AUTHORITY = ProModePolicy.CAMERA_IMPORT_AUTHORITY
        internal const val CAMERA_DOCUMENT_ID = ProModePolicy.CAMERA_IMPORT_DOCUMENT_ID

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

    init {
        observeSettings()
        observeRepositories()
        feed.restoreLastFolders()
    }

    private fun observeRepositories() {
        // Deletions still on the undo stack reference their folder, so its grant outlives a switch
        // to another folder until they are committed (D-10).
        viewModelScope.launch {
            _uiState
                .map { if (it.undoStack.isNotEmpty() || it.pendingDelete != null) it.sourceFolderUri else null }
                .distinctUntilChanged()
                .collect { transfer.syncUndoHistoryPin() }
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

    // ── Transfers, deletes and undo ([TransferUndoHolder]) ──

    fun addToCollection() = transfer.addToCollection()
    fun copyCurrentToSelection() = transfer.copyCurrentToSelection()
    fun moveCurrentToSelection() = transfer.moveCurrentToSelection()
    fun onCameraFolderGranted(uri: Uri) = transfer.onCameraFolderGranted(uri)
    fun onCameraFolderGrantCancelled() = transfer.onCameraFolderGrantCancelled()
    fun onSwipeLeft() = transfer.onSwipeLeft()
    fun confirmDelete(dontAskAgain: Boolean) = transfer.confirmDelete(dontAskAgain)
    fun cancelDelete() = transfer.cancelDelete()
    fun performLeftSwipeCopyOrMove() = transfer.performLeftSwipeCopyOrMove()
    fun requestDelete() = transfer.requestDelete()
    fun finalizePendingDelete() = transfer.finalizePendingDelete()
    fun revertDelete() = transfer.revertDelete()
    fun openCardDoneSheet() = transfer.openCardDoneSheet()
    fun dismissCardDoneSheet() = transfer.dismissCardDoneSheet()
    fun keepAllFilesOnCard() = transfer.keepAllFilesOnCard()
    fun deleteQueuedRejectsOnCard() = transfer.deleteQueuedRejectsOnCard()
    fun advanceClearCardConfirmation() = transfer.advanceClearCardConfirmation()
    fun cancelClearCardConfirmation() = transfer.cancelClearCardConfirmation()
    fun confirmClearEntireCard() = transfer.confirmClearEntireCard()

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
