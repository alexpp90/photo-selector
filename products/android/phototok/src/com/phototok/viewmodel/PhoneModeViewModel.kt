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
import com.phototok.data.model.RecentPath
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
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FirstRunHintText
import com.phototok.domain.ProModePolicy
import com.phototok.domain.RelatedFiles
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
            override fun observeSourceSettingsForKind(kind: SourceKind) = settings.observeSourceSettingsForKind(kind)
            override suspend fun resolveActiveFeedConfig() = settings.resolveActiveFeedConfig()
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
                get() = settings.cloudMoveCoercedNoticeShown
                set(value) { settings.cloudMoveCoercedNoticeShown = value }
            override fun afterFavouriteChange(items: List<ImageItem>) = this@PhoneModeViewModel.afterFavouriteChange(items)
            override fun updateDirectDeleteConfirm(enabled: Boolean) = settings.updateDirectDeleteConfirm(enabled)
        },
    )

    /** The open source's settings and destinations, the landing's source pickers, Pro mode. */
    private val settings: SourceSettingsHolder = SourceSettingsHolder(
        _uiState = _uiState,
        viewModelScope = viewModelScope,
        feed = feed,
        transfer = transfer,
        imageRepository = imageRepository,
        settingsRepository = settingsRepository,
    )

    private var systemIndexJob: Job? = null

    /** The favourite request the UI is showing or about to show; one at a time. */
    private var inFlightSyncRequest: FavouriteSyncRequest? = null

    /** Queue size when the user last declined; a full queue flushes only once it grew past it. */
    private var favouriteFlushFloor = 0

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
        settings.observeSettings()
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

    // ── Source and settings ([SourceSettingsHolder]) ──

    fun selectRecentPath(path: RecentPath) = settings.selectRecentPath(path)
    fun selectCameraFolder(onLaunchPicker: (Uri) -> Unit) = settings.selectCameraFolder(onLaunchPicker)
    fun selectRemovableVolume(volume: ExternalVolume?, onLaunchPicker: (Uri?) -> Unit) =
        settings.selectRemovableVolume(volume, onLaunchPicker)
    fun selectSdCardFolder(onLaunchPicker: (Uri?) -> Unit) = settings.selectSdCardFolder(onLaunchPicker)
    fun toggleExifOverlay() = settings.toggleExifOverlay()
    fun setProMode(enabled: Boolean) = settings.setProMode(enabled)
    fun updateDirectDeleteConfirm(enabled: Boolean) = settings.updateDirectDeleteConfirm(enabled)
    fun applyRawJpegFilter(filter: FileTypeFilter) = settings.applyRawJpegFilter(filter)
    fun showAllFormats() = settings.showAllFormats()
    fun enableMoveRelatedFiles() = settings.enableMoveRelatedFiles()

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
                        onAction = { settings.applyRawJpegFilter(FileTypeFilter.ALL) },
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
