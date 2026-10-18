package com.phototok.viewmodel

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photoselector.core.model.ExifData
import com.photoselector.core.storage.ExternalVolume
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
import com.phototok.data.source.FileOperationResult
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.RecentPath
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.CullingStats
import com.phototok.data.repository.FavouritesRepository
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.StatsRepository
import com.phototok.data.source.VolumeMonitor
import com.phototok.di.ApplicationScope
import com.phototok.domain.CardTransferJournal
import com.phototok.domain.CollectionAction
import com.phototok.domain.CompanionFiles
import com.phototok.domain.CopyMoveFeedback
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FirstRunHintText
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.FolderScanLogic
import com.phototok.domain.Milestones
import com.phototok.domain.OptimisticFeed
import com.phototok.domain.PendingDeleteLogic
import com.phototok.domain.PhoneFeedOrdering
import com.phototok.domain.PhotoFolders
import com.phototok.domain.RelatedFiles
import com.phototok.domain.ShareIntentBuilder
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.SystemFavouriteSync
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
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

data class PhoneModeUiState(
    val images: List<ImageItem> = emptyList(),
    /** All images before file type filtering (kept for re-filtering). */
    val allImages: List<ImageItem> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    /**
     * True while the folder is still being enumerated in the background. The
     * feed is already usable — this only tells the UI that the photo count is
     * still growing.
     */
    val isDiscovering: Boolean = false,
    val sourceFolderUri: String? = null,
    val sourceFolderName: String = "",
    val sourceKind: SourceKind = SourceKind.PHONE_LIBRARY,
    val sourceVolumeUuid: String? = null,
    val isCloudSource: Boolean = false,
    val collectionFolderUri: String? = null,
    val collectionFolderName: String = "",
    val leftSwipeAction: SwipeAction = SwipeAction.DEFAULT,
    val leftSwipeFolderUri: String? = null,
    val leftSwipeFolderName: String = "",
    val error: String? = null,
    val showGestureTutorial: Boolean = false,
    /** Set when the user opens the controls guide from the info button. */
    val showControlsGuide: Boolean = false,
    val lastActionFeedback: ActionFeedback? = null,
    /** One-time explanation for an action the user just performed for the first time. */
    val firstRunHint: FirstRunHintUi? = null,
    /** Hint keys already shown, so each explanation fires exactly once. */
    val seenFirstRunHints: Set<String> = emptySet(),
    // Settings (observed)
    val swipeRightVerb: SwipeRightVerb = SwipeRightVerb.COPY,
    val feedOrder: FeedOrderMode = FeedOrderMode.NEWEST_FIRST,
    val collectionAction: CollectionAction = CollectionAction.DEFAULT,
    val directDeleteConfirmEnabled: Boolean = true,
    val sortByOrientation: Boolean = false,
    val randomizeOrder: Boolean = false,
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.DEFAULT,
    /** Index where portrait section starts (-1 = no split). */
    val portraitSectionStart: Int = -1,
    val showExifOverlay: Boolean = false,
    /** Top-of-stack deletion applied to the UI but not yet finalized on disk (revertable). */
    val pendingDelete: PendingDeleteLogic.Pending? = null,
    /** Bounded LIFO stack of pending deletions (`U-18`). */
    val undoStack: List<PendingDeleteLogic.Pending> = emptyList(),
    /** Whether collection/delete actions also move/delete same-name sibling files. */
    val moveRelatedFiles: Boolean = false,
    // Recent folders (landing quick-select)
    val recentPaths: List<RecentPath> = emptyList(),
    val recentPathsEnabled: Boolean = true,
    val recentPathsCount: Int = 3,
    /** Whether to show suggestion for folders containing both RAW and JPEG pairs. */
    val showRawJpegSuggestion: Boolean = false,
    /** Prompt shown when a reload or a reopened folder found photos newer than the last visit. */
    val reloadPrompt: ReloadPrompt? = null,
    /** Friendly explanation when a selected folder contains 0 supported photos. */
    val emptyFolderMessage: String? = null,
    /** True when the folder's persisted SAF grant was revoked or the folder was moved/deleted. */
    val missingGrantPrompt: Boolean = false,
    /** When true, Selection folder is stored inside each scanned folder. */
    val selectionUseSourceRoot: Boolean = false,
    /** In-place favourited photo URIs (`U-17`). */
    val favouritedUris: Set<String> = emptySet(),
    /** Count of seen photos in the active folder (`U-15`). */
    val seenCountInFolder: Int = 0,
    /** Per-folder `(seen, total)` summary map for the landing screen (`U-15`). */
    val folderSeenProgress: Map<String, Pair<Int, Int>> = emptyMap(),
    /** Currently mounted removable volumes (`U-14a`). */
    val mountedVolumes: List<ExternalVolume> = emptyList(),
    /** Session and lifetime culling statistics (`U-19`). */
    val cullingStats: CullingStats = CullingStats(),
    /** Milestone celebration banner text (`U-19`). */
    val celebrationBanner: String? = null,
    val celebrationsEnabled: Boolean = true,
    /** "Done with this card" sheet state (`U-14c`). */
    val showCardDoneSheet: Boolean = false,
    val cardCopiedCount: Int = 0,
    val cardDeletedCount: Int = 0,
    val cardClearStep: Int = 0,
    val cardClearComplete: Boolean = false,
    /** Optional API 30+ batch MediaStore request (`U-17`, `U-18`). */
    val pendingIntentSenderRequest: SystemFavouriteSync.BatchIntentRequest? = null,
)

data class ReloadPrompt(
    val newCount: Int,
    val firstNewIndex: Int,
    val resumeIndex: Int = 0,
    val totalCount: Int = 0,
)

/** True when there is a pending deletion that can still be reverted. */
val PhoneModeUiState.canRevert: Boolean
    get() = pendingDelete != null || undoStack.isNotEmpty()

/** Set of image URIs that have a RAW companion in [PhoneModeUiState.allImages] (`U-12`). */
val PhoneModeUiState.rawCompanionUris: Set<String>
    get() = images.filter { RelatedFiles.hasRawCompanion(allImages, it) }.map { it.uri }.toSet()

/** Set of recent-path URIs whose removable volume is currently unmounted (`U-09`, `U-14a`). */
val PhoneModeUiState.unmountedRecentUris: Set<String>
    get() = recentPaths.filter { !VolumeMonitor.isRecentPathMounted(it, mountedVolumes) }.map { it.uri }.toSet()

data class ActionFeedback(
    val message: String,
    val isError: Boolean = false,
    val id: Long = System.nanoTime(),
)

/** A one-time explanation, resolved against the user's current settings. */
data class FirstRunHintUi(
    val hint: FirstRunHint,
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val id: Long = System.nanoTime(),
)

@HiltViewModel
class PhoneModeViewModel @Inject constructor(
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    private val seenRepository: SeenRepository,
    private val favouritesRepository: FavouritesRepository,
    private val statsRepository: StatsRepository,
    private val volumeMonitor: VolumeMonitor,
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

    internal val cardTransferJournal = CardTransferJournal()

    /**
     * Bumped whenever the visible feed is discarded or rebuilt (folder switch, reload, landing,
     * card unmount). Late results of work launched on [appScope] compare it with the value captured
     * at launch before touching the feed: a stale result may only affect durable outcomes (journal,
     * stats), never the presentation of a feed that belongs to something else now.
     */
    private var feedEpoch = 0

    private val loadedExifCache = object : LinkedHashMap<String, ExifData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExifData>?): Boolean =
            size > MAX_EXIF_CACHE_SIZE
    }

    /** Active folder-discovery collection; cancelled when a new folder is selected. */
    private var discoveryJob: Job? = null
    private var dimensionsJob: Job? = null
    private var sourceSettingsJob: Job? = null

    /**
     * Every URI published for the current folder. Discovery streams cumulative
     * batches, and the user may remove photos from the feed while it is still
     * running, so "absent from the feed" must not be read as "newly found".
     */
    private val publishedUris = mutableSetOf<String>()

    /** URIs whose dimensions have been read, so batch restarts do no double I/O. */
    private val dimensionsResolved = mutableSetOf<String>()

    /** Set once the user changes page themselves. */
    private var userHasNavigated = false

    /**
     * True while batches may re-order the whole feed newest-first: the user has not
     * swiped yet and no specific photo is being kept (active-session reload).
     */
    private var newestFirstReorderAllowed = true

    /** Whether a per-SourceKind SourceSettings emission has arrived. */
    private var hasSourceSettingsEmission = false

    /** Whether the one-time cloud MOVE->COPY notice has been shown in this session. */
    private var cloudMoveCoercedNoticeShown = false

    companion object {
        private const val TAG = "PhoneModeVM"
        private const val MAX_EXIF_CACHE_SIZE = 30
        private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** Dimension results are applied to the UI state in batches of this size. */
        internal const val DIMENSION_BATCH_SIZE = 24

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
        restoreLastFolders()
    }

    private fun observeRepositories() {
        // Deletions still on the undo stack reference their folder, so its grant outlives a switch
        // to another folder until they are committed (D-10).
        viewModelScope.launch {
            _uiState
                .map { if (it.undoStack.isNotEmpty() || it.pendingDelete != null) it.sourceFolderUri else null }
                .distinctUntilChanged()
                .collect { settingsRepository.pinUndoHistoryFolder(it) }
        }
        viewModelScope.launch {
            favouritesRepository.favouritedUris.collect { favs ->
                _uiState.update { it.copy(favouritedUris = favs) }
            }
        }
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
                handleVolumeUnmounted(unmountedUuid)
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

    private fun handleVolumeUnmounted(unmountedUuid: String?) {
        val state = _uiState.value
        if (state.sourceKind != SourceKind.REMOVABLE) return
        val currentUuid = state.sourceVolumeUuid
        if (unmountedUuid != null && currentUuid != null && !currentUuid.equals(unmountedUuid, ignoreCase = true)) {
            return
        }
        feedEpoch++
        // Surprise unmount while viewing a removable card: abort discovery and return to landing safely.
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        publishedUris.clear()
        dimensionsResolved.clear()
        _uiState.update {
            it.copy(
                images = emptyList(),
                allImages = emptyList(),
                currentIndex = 0,
                portraitSectionStart = -1,
                isLoading = false,
                isDiscovering = false,
                pendingDelete = null,
                undoStack = emptyList(),
                showCardDoneSheet = false,
                lastActionFeedback = ActionFeedback(
                    message = "Card disconnected — progress saved",
                    isError = false,
                ),
            )
        }
    }

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
                        )
                    } else {
                        state.copy(
                            showExifOverlay = s.showExifOverlay,
                            recentPathsEnabled = s.recentPathsEnabled,
                            recentPathsCount = s.recentPathsCount,
                            recentPaths = s.recentPaths,
                            seenFirstRunHints = s.seenFirstRunHints,
                        )
                    }
                }
                val prev = previous
                previous = s
                if (prev != null && (!hasSourceSettingsEmission || _uiState.value.sourceKind == SourceKind.PHONE_LIBRARY)) {
                    if (s.fileTypeFilter != prev.fileTypeFilter &&
                        _uiState.value.allImages.isNotEmpty()
                    ) {
                        refilterAndSort()
                    } else if ((s.sortByOrientation != prev.sortByOrientation ||
                            s.randomizeOrder != prev.randomizeOrder) &&
                        _uiState.value.images.isNotEmpty()
                    ) {
                        resortImages()
                    }
                }
            }
        }
        // URI settings resolve display names via the repository (I/O) — kept separate.
        viewModelScope.launch {
            settingsRepository.phoneCollectionUri.collect { uri ->
                val name = resolveFolderName(uri, fallback = "Collection")
                _uiState.update {
                    it.copy(collectionFolderUri = uri, collectionFolderName = name)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.phoneLeftSwipeUri.collect { uri ->
                val name = resolveFolderName(uri, fallback = "Folder")
                _uiState.update {
                    it.copy(leftSwipeFolderUri = uri, leftSwipeFolderName = name)
                }
            }
        }
        observeSourceSettingsForKind(SourceKind.PHONE_LIBRARY)
    }

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
                        collectionFolderUri = src.collectionFolderUri ?: state.collectionFolderUri,
                        leftSwipeFolderUri = src.leftSwipeFolderUri ?: state.leftSwipeFolderUri,
                    )
                }

                if (isCloud && !cloudMoveCoercedNoticeShown &&
                    (src.swipeRightVerb == SwipeRightVerb.MOVE || src.leftSwipeAction == SwipeAction.MOVE)
                ) {
                    cloudMoveCoercedNoticeShown = true
                    showFeedback("Cloud folder: copying, not moving")
                }

                val prev = previous
                previous = src
                if (prev != null) {
                    if (src.fileTypeFilter != prev.fileTypeFilter &&
                        _uiState.value.allImages.isNotEmpty()
                    ) {
                        refilterAndSort()
                    } else if (src.feedOrder != prev.feedOrder &&
                        _uiState.value.images.isNotEmpty()
                    ) {
                        resortImages()
                    }
                }
            }
        }
    }

    private suspend fun resolveFolderName(uri: String?, fallback: String): String {
        if (uri == null) return ""
        return imageRepository.resolveFolderName(Uri.parse(uri)) ?: fallback
    }

    /** Toggle the EXIF stats overlay (driven by tapping the Photo-Tok logo). */
    fun toggleExifOverlay() {
        viewModelScope.launch {
            settingsRepository.setPhoneShowExifOverlay(!_uiState.value.showExifOverlay)
        }
    }

    /** Re-open a previously used source folder from the recents list. */
    fun selectRecentPath(path: RecentPath) {
        if (!VolumeMonitor.isRecentPathMounted(path, _uiState.value.mountedVolumes)) {
            showFeedback("${path.displayName} is not connected", isError = true)
            return
        }
        selectSourceFolder(Uri.parse(path.uri))
    }

    /**
     * Quick-start for default phone camera roll.
     * If permission is already granted, opens immediately;
     * otherwise calls [onLaunchPicker] with the camera tree URI to prompt the user.
     */
    fun selectCameraFolder(onLaunchPicker: (Uri) -> Unit) {
        val cameraUri = android.provider.DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:DCIM/Camera",
        )
        viewModelScope.launch {
            val name = imageRepository.prepareSourceFolder(cameraUri)
            if (name != null) {
                selectSourceFolder(cameraUri)
            } else {
                onLaunchPicker(cameraUri)
            }
        }
    }

    /**
     * Quick-start for a mounted removable volume (`SD Card / USB Reader` card on landing, `U-09` / `U-14a`).
     */
    fun selectRemovableVolume(volume: ExternalVolume?, onLaunchPicker: (Uri?) -> Unit) {
        val uuid = volume?.uuid
        val initialUri = if (!uuid.isNullOrBlank()) {
            android.provider.DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "$uuid:DCIM",
            )
        } else {
            null
        }
        if (initialUri == null) {
            onLaunchPicker(null)
            return
        }
        viewModelScope.launch {
            val name = imageRepository.prepareSourceFolder(initialUri)
            if (name != null) {
                selectSourceFolder(initialUri)
            } else {
                onLaunchPicker(initialUri)
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

    private fun restoreLastFolders() {
        viewModelScope.launch {
            settingsRepository.reconcilePersistedGrants()
            val lastUri = settingsRepository.lastFolderUri.first()
            if (lastUri != null && _uiState.value.sourceFolderUri == null) {
                selectSourceFolder(Uri.parse(lastUri))
            }
        }
    }

    // ── Folder selection ──────────────────────────────────────────────────

    fun selectSourceFolder(uri: Uri) {
        finalizePendingDelete()
        feedEpoch++
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        publishedUris.clear()
        dimensionsResolved.clear()
        _uiState.update { it.copy(emptyFolderMessage = null, missingGrantPrompt = false) }

        val kind = classifyUri(uri, _uiState.value.mountedVolumes)
        val volUuid = SourceKindClassifier.extractRemovableUuid(uri)
        val isCloud = kind == SourceKind.CLOUD_OTHER
        observeSourceSettingsForKind(kind)

        _uiState.update {
            it.copy(
                emptyFolderMessage = null,
                missingGrantPrompt = false,
                sourceKind = kind,
                sourceVolumeUuid = volUuid,
                isCloudSource = isCloud,
                showCardDoneSheet = false,
                cardClearStep = 0,
                cardClearComplete = false,
            )
        }
        discoveryJob = viewModelScope.launch {
            // The previous folder's feed is dropped up front: batches are merged
            // by appending, so a stale list would be prefixed to the new folder.
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isDiscovering = true,
                    error = null,
                    emptyFolderMessage = null,
                    missingGrantPrompt = false,
                    sourceFolderUri = uri.toString(),
                    images = emptyList(),
                    allImages = emptyList(),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    showRawJpegSuggestion = false,
                )
            }

            val folderName = imageRepository.prepareSourceFolder(uri)
            if (folderName == null) {
                if (settingsRepository.lastFolderUri.first() == uri.toString()) {
                    settingsRepository.setLastFolderUri(null)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isDiscovering = false,
                        sourceFolderUri = null,
                        sourceFolderName = "",
                        missingGrantPrompt = true,
                        error = "Can't access this folder",
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(sourceFolderName = folderName, missingGrantPrompt = false) }
            settingsRepository.setLastFolderUri(uri.toString())
            settingsRepository.addRecentPath(uri.toString(), folderName)

            val scanInfo = settingsRepository.getFolderScanInfo(uri.toString())
            collectDiscoveredImages(
                folderUri = uri,
                errorLabel = "images",
                previousScanInfo = scanInfo,
                wasActiveSession = false,
            )
        }
    }

    private data class ActiveFeedConfig(
        val fileTypeFilter: FileTypeFilter,
        val feedOrder: FeedOrderMode,
        val sortByOrientation: Boolean,
        val randomizeOrder: Boolean,
    )

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

    /**
     * Shared tail of folder selection: stream, filter, order, publish.
     *
     * Discovery emits progressively (a small first batch, then chunks), so this
     * publishes each batch as it arrives instead of waiting for the whole tree.
     * The user is swiping from the first batch onwards.
     */
    private suspend fun collectDiscoveredImages(
        folderUri: Uri,
        errorLabel: String,
        targetUriToKeep: String? = null,
        previousScanInfo: FolderScanInfo = FolderScanInfo(),
        wasActiveSession: Boolean = false,
        urisBeforeReload: Set<String> = emptySet(),
    ) {
        publishedUris.clear()
        dimensionsResolved.clear()
        userHasNavigated = false
        val config = resolveActiveFeedConfig()
        newestFirstReorderAllowed = targetUriToKeep == null

        try {
            imageRepository.discoverImages(folderUri).collect { discovered ->
                publishBatch(discovered, config, targetUriToKeep)
            }
            // Enumeration finished:
            // If not randomized, sort cumulative list according to active feedOrder to guarantee whole folder order,
            // while preserving current item or targetUriToKeep.
            val current = _uiState.value
            val uriToPreserve = targetUriToKeep
                ?: current.images.getOrNull(current.currentIndex)?.uri
            val filtered = PhoneFeedOrdering.filterByType(current.allImages, config.fileTypeFilter)
            val seenUris = seenRepository.filterSeenUris(filtered, folderUri.toString())
            val sorted = if (!config.randomizeOrder && config.feedOrder != FeedOrderMode.SHUFFLE && current.allImages.isNotEmpty()) {
                PhoneFeedOrdering.order(
                    images = filtered,
                    mode = config.feedOrder,
                    seenUris = seenUris,
                )
            } else {
                PhoneFeedOrdering.Result(current.images, current.portraitSectionStart)
            }

            var promptToSet: ReloadPrompt? = null
            var confirmNoNewPhotos = false
            val newIndex: Int

            val shouldPromptResume = sorted.images.isNotEmpty() &&
                (wasActiveSession || previousScanInfo.hasPreviousSession)

            if (shouldPromptResume) {
                val anchorUri = targetUriToKeep ?: previousScanInfo.lastViewedUri
                val fallbackIdx = if (wasActiveSession) current.currentIndex else previousScanInfo.lastViewedIndex
                val resumeIdx = FolderScanLogic.resolveResumeIndex(
                    images = sorted.images,
                    lastViewedUri = anchorUri,
                    lastViewedIndex = fallbackIdx,
                )
                newIndex = if (userHasNavigated) {
                    resolveFeedIndex(current.currentIndex, sorted.images.size, uriToPreserve, sorted.images)
                } else {
                    resumeIdx
                }

                val newPhotos = FolderScanLogic.findNewPhotos(
                    images = sorted.images,
                    previousMaxLastModified = previousScanInfo.maxLastModified,
                    urisBeforeReload = urisBeforeReload,
                )
                if (newPhotos.isNotEmpty()) {
                    val firstNewIdx = sorted.images.indexOfFirst { img ->
                        newPhotos.any { it.uri == img.uri }
                    }.coerceAtLeast(0)
                    promptToSet = ReloadPrompt(
                        newCount = newPhotos.size,
                        firstNewIndex = firstNewIdx,
                        resumeIndex = newIndex,
                        totalCount = sorted.images.size,
                    )
                } else {
                    // Nothing new means nothing to decide: resume silently at the saved
                    // photo. Only an explicit Reload confirms the rescan, in a snackbar.
                    val updatedScan = FolderScanLogic.computeUpdatedScanInfo(
                        images = sorted.images,
                        lastViewedUri = sorted.images.getOrNull(newIndex)?.uri,
                        lastViewedIndex = newIndex,
                    )
                    settingsRepository.setFolderScanInfo(folderUri.toString(), updatedScan)
                    confirmNoNewPhotos = wasActiveSession
                }
            } else {
                newIndex = FolderScanLogic.resolveLandingIndex(
                    images = sorted.images,
                    previousMaxLastModified = previousScanInfo.maxLastModified,
                    userHasNavigated = userHasNavigated,
                    randomized = config.randomizeOrder,
                    preservedUri = uriToPreserve,
                    currentIndex = current.currentIndex,
                    mode = config.feedOrder,
                    seenUris = seenUris,
                )
                val updatedScan = FolderScanLogic.computeUpdatedScanInfo(
                    images = sorted.images,
                    lastViewedUri = sorted.images.getOrNull(newIndex)?.uri,
                    lastViewedIndex = newIndex,
                )
                settingsRepository.setFolderScanInfo(folderUri.toString(), updatedScan)
            }

            val emptyMsg = if (sorted.images.isEmpty()) {
                val fName = current.sourceFolderName.ifEmpty { "This folder" }
                "$fName has no supported photos"
            } else null

            val seenCount = seenRepository.countSeen(sorted.images, folderUri.toString())
            if (sorted.images.isNotEmpty()) {
                settingsRepository.setFolderSeenProgress(
                    folderUri = folderUri.toString(),
                    seenCount = seenCount,
                    totalCount = sorted.images.size,
                )
            }

            _uiState.update {
                it.copy(
                    images = sorted.images,
                    portraitSectionStart = sorted.portraitSectionStart,
                    currentIndex = newIndex,
                    seenCountInFolder = seenCount,
                    isLoading = false,
                    isDiscovering = false,
                    reloadPrompt = promptToSet,
                    emptyFolderMessage = emptyMsg,
                )
            }
            loadExifForCurrent()
            if (confirmNoNewPhotos) showFeedback("No new photos")
            maybeShowRawJpegSuggestion()
            maybeShowFilterMismatchHint()
            loadDimensionsAsynchronously(force = true)
        } catch (e: CancellationException) {
            // A newer discovery job owns the state now — do not touch it.
            throw e
        } catch (e: SecurityException) {
            if (settingsRepository.lastFolderUri.first() == folderUri.toString()) {
                settingsRepository.setLastFolderUri(null)
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isDiscovering = false,
                    sourceFolderUri = null,
                    sourceFolderName = "",
                    missingGrantPrompt = true,
                    error = "Can't access this folder",
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isDiscovering = false,
                    error = "Failed to load $errorLabel: ${e.message}",
                )
            }
        }
    }

    /**
     * Merge one discovery batch into the feed.
     */
    private fun publishBatch(
        discovered: List<ImageItem>,
        config: ActiveFeedConfig,
        targetUriToKeep: String? = null,
    ) {
        val state = _uiState.value
        val isFirstBatch = state.images.isEmpty() && state.allImages.isEmpty()
        val fresh = PhoneFeedOrdering.newItems(discovered, publishedUris)
        if (fresh.isEmpty() && !isFirstBatch) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        publishedUris += fresh.map { it.uri }

        val allCombined = state.allImages + fresh
        val seenUris = seenRepository.filterSeenUris(allCombined, state.sourceFolderUri)
        val freshFiltered = PhoneFeedOrdering.filterByType(fresh, config.fileTypeFilter)
        val merged = PhoneFeedOrdering.appendBatch(
            current = state.images,
            fresh = freshFiltered,
            mode = config.feedOrder,
            reorder = newestFirstReorderAllowed && !userHasNavigated,
            currentIndex = state.currentIndex,
            seenUris = seenUris,
        )

        val nextIndex = resolveFeedIndex(state.currentIndex, merged.images.size, targetUriToKeep, merged.images)

        _uiState.update {
            it.copy(
                allImages = allCombined,
                images = merged.images,
                portraitSectionStart = merged.portraitSectionStart,
                currentIndex = nextIndex,
                isLoading = false,
            )
        }

        if (isFirstBatch) checkGestureTutorial()
        maybeShowRawJpegSuggestion()
        maybeShowFilterMismatchHint()
        loadExifForCurrent()
        loadDimensionsAsynchronously()
    }

    /**
     * The index to show after a batch landed: keep the photo being chased by URI, else
     * leave the user exactly where they are.
     */
    private fun resolveFeedIndex(
        currentIndex: Int,
        size: Int,
        targetUri: String? = null,
        currentImages: List<ImageItem> = emptyList(),
    ): Int {
        if (size == 0) return 0
        if (userHasNavigated) return currentIndex.coerceIn(0, size - 1)
        if (targetUri != null) {
            val idx = currentImages.indexOfFirst { it.uri == targetUri }
            if (idx >= 0) return idx
        }
        return currentIndex.coerceIn(0, size - 1)
    }

    fun selectCollectionFolder(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.setPhoneCollectionUri(uri.toString())
            val name = resolveFolderName(uri.toString(), fallback = "Collection")
            _uiState.update {
                it.copy(collectionFolderUri = uri.toString(), collectionFolderName = name)
            }
        }
    }

    fun selectLeftSwipeFolder(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.setPhoneLeftSwipeUri(uri.toString())
            val name = resolveFolderName(uri.toString(), fallback = "Folder")
            _uiState.update {
                it.copy(leftSwipeFolderUri = uri.toString(), leftSwipeFolderName = name)
            }
        }
    }

    // ── Sorting ───────────────────────────────────────────────────────────

    private suspend fun sortImages(images: List<ImageItem>): PhoneFeedOrdering.Result {
        val config = resolveActiveFeedConfig()
        val seenUris = seenRepository.filterSeenUris(images, _uiState.value.sourceFolderUri)
        return PhoneFeedOrdering.order(
            images = images,
            mode = config.feedOrder,
            seenUris = seenUris,
        )
    }

    private fun resortImages() {
        finalizePendingDelete()
        viewModelScope.launch {
            val current = _uiState.value
            if (current.images.isEmpty()) return@launch
            val currentUri = current.images.getOrNull(current.currentIndex)?.uri
            val sorted = sortImages(current.images)
            val newIndex = if (currentUri != null) {
                sorted.images.indexOfFirst { it.uri == currentUri }.coerceAtLeast(0)
            } else {
                0
            }
            _uiState.update {
                it.copy(
                    images = sorted.images,
                    portraitSectionStart = sorted.portraitSectionStart,
                    currentIndex = newIndex,
                )
            }
        }
    }

    private fun refilterAndSort() {
        viewModelScope.launch {
            val state = _uiState.value
            val currentUri = state.images.getOrNull(state.currentIndex)?.uri
            val filtered = PhoneFeedOrdering.filterByType(state.allImages, state.fileTypeFilter)
            val sorted = sortImages(filtered)
            val newIndex = if (currentUri != null) {
                sorted.images.indexOfFirst { it.uri == currentUri }.coerceAtLeast(0)
            } else {
                0
            }
            _uiState.update {
                it.copy(
                    images = sorted.images,
                    portraitSectionStart = sorted.portraitSectionStart,
                    currentIndex = newIndex,
                )
            }
            loadExifForCurrent()
        }
    }

    // ── Seen & Milestone Tracking (`U-15`, `U-19`) ────────────────────────

    /**
     * Called when a card has been dwelled on for `>= 500 ms` (`SeenStore.SEEN_DWELL_MS`)
     * or acted upon by the user (`U-15`, `U-19`).
     */
    fun onCardDwelled(image: ImageItem) {
        val state = _uiState.value
        val sourceUri = state.sourceFolderUri ?: return
        val wasNewlySeen = seenRepository.markSeen(image, sourceUri)
        if (state.fileTypeFilter == FileTypeFilter.ONE_PER_SHOT) {
            relatedFeedImages(image).forEach { sibling ->
                seenRepository.markSeen(sibling, sourceUri)
            }
        }
        if (wasNewlySeen) {
            val newSeenCount = seenRepository.countSeen(state.images, sourceUri)
            _uiState.update { it.copy(seenCountInFolder = newSeenCount) }
            viewModelScope.launch {
                settingsRepository.setFolderSeenProgress(
                    folderUri = sourceUri,
                    seenCount = newSeenCount,
                    totalCount = state.images.size,
                )
            }
            val milestone = statsRepository.recordPhotoReviewed(
                kind = state.sourceKind,
                celebrationsEnabled = state.celebrationsEnabled,
            )
            if (milestone != null) {
                _uiState.update { it.copy(celebrationBanner = milestone.message) }
            }
        }
    }

    fun dismissCelebrationBanner() {
        _uiState.update { it.copy(celebrationBanner = null) }
    }

    // ── Navigation ────────────────────────────────────────────────────────

    fun navigateToImage(index: Int) {
        if (index in _uiState.value.images.indices) {
            val state = _uiState.value
            if (index != state.currentIndex) {
                // A real page change means the user has taken over; batches stay append-only.
                userHasNavigated = true
            }
            _uiState.update { it.copy(currentIndex = index) }
            persistCurrentFolderPosition()
            maybeShowRawJpegSuggestion()
            maybeShowFilterMismatchHint()
            loadExifForCurrent()
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    /**
     * Sibling files that share the same base name (stem) but differ in extension,
     * e.g. IMG_001.JPG and IMG_001.ARW. Computed from the unfiltered list so it is
     * independent of the user's file-type filter selection.
     */
    private fun relatedImages(target: ImageItem): List<ImageItem> =
        RelatedFiles.bundleOf(_uiState.value.allImages, target)

    /** The feed-visible part of [relatedImages] (no `.xmp` sidecars): favourites, seen and counts. */
    private fun relatedFeedImages(target: ImageItem): List<ImageItem> =
        relatedImages(target).filterNot { CompanionFiles.isSidecar(it.fileName) }

    /**
     * Returns true if [image] has a RAW+JPEG companion pair in [PhoneModeUiState.allImages] (`U-12`).
     */
    fun hasRawCompanion(image: ImageItem): Boolean =
        RelatedFiles.hasRawCompanion(_uiState.value.allImages, image)

    /**
     * Swipe right: perform the configured [SwipeRightVerb] (`FAVOURITE`, `COPY`, or `MOVE`).
     */
    fun addToCollection() {
        val state = _uiState.value
        if (state.images.isEmpty()) return
        val currentImage = state.images[state.currentIndex]
        onCardDwelled(currentImage)
        maybeShowFirstRunHint(FirstRunHint.SWIPE_RIGHT)

        if (state.swipeRightVerb == SwipeRightVerb.FAVOURITE) {
            val related = if (state.moveRelatedFiles) relatedFeedImages(currentImage) else emptyList()
            favouritesRepository.setFavourite(
                uri = currentImage.uri,
                favourite = true,
                companionUris = related.map { it.uri },
                queueForSystemSync = state.sourceKind == SourceKind.PHONE_LIBRARY,
            )
            statsRepository.recordKeeper(state.sourceKind, 1 + related.size)
            advanceToNextImage()
            showFeedback("★ Added to Favourites")
            return
        }

        val target = resolveCollectionTargetUri(state)
        val isCopy = state.collectionAction == CollectionAction.COPY ||
            state.swipeRightVerb == SwipeRightVerb.COPY ||
            state.isCloudSource
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = isCopy,
            subfolderName = PhotoFolders.SELECTION,
            destinationNoun = "collection",
        )
    }

    /** Toggle in-place favourite status on the current photo (`U-13`, `U-17`). */
    fun toggleCurrentFavourite() {
        val state = _uiState.value
        val currentImage = state.images.getOrNull(state.currentIndex) ?: return
        val related = if (state.moveRelatedFiles) relatedFeedImages(currentImage) else emptyList()
        val nowFav = favouritesRepository.toggleFavourite(
            uri = currentImage.uri,
            companionUris = related.map { it.uri },
            queueForSystemSync = state.sourceKind == SourceKind.PHONE_LIBRARY,
        )
        if (nowFav) {
            statsRepository.recordKeeper(state.sourceKind, 1 + related.size)
            showFeedback("★ Added to Favourites")
        } else {
            showFeedback("Removed from Favourites")
        }
    }

    /** Explicit Copy to Selection action from the long-press context menu (`U-13`). */
    fun copyCurrentToSelection() {
        val state = _uiState.value
        val target = resolveCollectionTargetUri(state)
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = true,
            subfolderName = PhotoFolders.SELECTION,
            destinationNoun = "collection",
        )
    }

    /** Explicit Move to Selection action from the long-press context menu (`U-13`). */
    fun moveCurrentToSelection() {
        val state = _uiState.value
        val target = resolveCollectionTargetUri(state)
        val isCopy = state.isCloudSource
        if (isCopy && !cloudMoveCoercedNoticeShown) {
            cloudMoveCoercedNoticeShown = true
        }
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = isCopy,
            subfolderName = PhotoFolders.SELECTION,
            destinationNoun = "collection",
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

    private fun resolveCollectionTargetUri(state: PhoneModeUiState): String? {
        if (state.collectionFolderUri != null) return state.collectionFolderUri
        return state.sourceFolderUri
    }

    /** Swipe left (copy/move mode): copy or move the current photo to the custom folder. */
    fun performLeftSwipeCopyOrMove() {
        val state = _uiState.value
        if (state.images.isNotEmpty()) {
            onCardDwelled(state.images[state.currentIndex])
        }
        maybeShowFirstRunHint(FirstRunHint.SWIPE_LEFT_FOLDER)
        copyOrMoveCurrent(
            targetUri = state.leftSwipeFolderUri ?: state.sourceFolderUri,
            isCopy = state.leftSwipeAction == SwipeAction.COPY || state.isCloudSource,
            subfolderName = PhotoFolders.LEFT_SWIPE,
            destinationNoun = "folder",
        )
    }

    /**
     * Copy or move the current image plus its related siblings.
     */
    private fun copyOrMoveCurrent(
        targetUri: String?,
        isCopy: Boolean,
        subfolderName: String,
        destinationNoun: String,
    ) {
        val state = _uiState.value
        if (state.images.isEmpty()) return
        if (targetUri == null) return

        val currentImage = state.images[state.currentIndex]
        val related = if (state.moveRelatedFiles) relatedImages(currentImage) else emptyList()
        val targets = listOf(currentImage) + related
        val slots = OptimisticFeed.slotsOf(state.images, state.allImages, targets)
        val epochAtLaunch = feedEpoch

        // ── Optimistic UI: show the next photo, then do the work ──────────
        if (isCopy) {
            advanceToNextImage()
        } else {
            removeImagesFromLists(targets.map { it.uri }.toSet())
        }

        appScope.launch {
            try {
                val sortingEnabled = settingsRepository.sortingEnabled.first()
                val folderUri = Uri.parse(targetUri)

                val succeededUris = mutableSetOf<String>()
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
                        ?: FileOperationResult.failure(img.uri, "No result")
                    when {
                        result.success -> {
                            succeededUris.add(img.uri)
                            if (img === currentImage) {
                                // The primary file got a collision suffix: say which name it became.
                                val resultName = result.destinationUri?.let(CopyMoveFeedback::displayNameOf)
                                if (resultName != null && resultName != img.fileName) renamedTo = resultName
                            }
                            if (state.sourceKind == SourceKind.REMOVABLE ||
                                state.sourceKind == SourceKind.CAMERA_MTP
                            ) {
                                cardTransferJournal.recordCopy(
                                    sourceUri = img.uri,
                                    destUri = result.destinationUri ?: targetUri,
                                    byteSize = img.fileSize,
                                    sourceFolderUri = state.sourceFolderUri ?: "",
                                )
                            }
                        }
                        result.partial -> partial++
                        else -> failed++
                    }
                }

                // The feed may belong to another folder (or none) by now: files that stayed behind
                // are rediscovered by that folder's own scan, never inserted into a foreign feed.
                val feedStillCurrent = feedEpoch == epochAtLaunch
                if (!isCopy && (failed > 0 || partial > 0) && feedStillCurrent) {
                    // The optimistic removal was wrong for files that are still in the source.
                    restoreToFeed(OptimisticFeed.slotsLeftBehind(slots, goneUris = succeededUris))
                }

                val message = CopyMoveFeedback.message(
                    isCopy = isCopy,
                    destinationNoun = destinationNoun,
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
                if (!isCopy && feedEpoch == epochAtLaunch) restoreToFeed(slots)
                _uiState.update {
                    it.copy(lastActionFeedback = ActionFeedback("Failed: ${e.message}", isError = true))
                }
            }
        }
    }

    /** Move to the next photo without waiting for anything. */
    private fun advanceToNextImage() {
        val state = _uiState.value
        val next = state.currentIndex + 1
        if (next in state.images.indices) navigateToImage(next)
    }

    /** Put optimistically removed photos back where they were. */
    private fun restoreToFeed(slots: List<OptimisticFeed.Slot>) {
        if (slots.isEmpty()) return
        val state = _uiState.value
        val lists = OptimisticFeed.restore(
            images = state.images,
            allImages = state.allImages,
            slots = slots,
            currentIndex = state.currentIndex,
            sortByOrientation = state.sortByOrientation,
        )
        _uiState.update {
            it.copy(
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

        onCardDwelled(initialState.images[initialState.currentIndex])
        maybeShowFirstRunHint(leftSwipeHint())

        val state = _uiState.value
        if (state.images.isEmpty()) return
        val imageToDelete = state.images[state.currentIndex]
        val related = if (state.moveRelatedFiles) relatedImages(imageToDelete) else emptyList()

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
            finalizePendingEntriesOnDisk(listOf(evicted), state.sourceFolderUri, state.sourceKind)
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
        persistCurrentFolderPosition()
        viewModelScope.launch {
            settingsRepository.setPersistedPendingDeleteUris(allQueuedUris.toSet())
        }
        loadExifForCurrent()
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
        if (entries.isEmpty()) return

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
    ) {
        appScope.launch {
            // Only these entries leave the journal: others may have been queued meanwhile.
            settingsRepository.removePersistedPendingDeleteUris(
                entries.flatMap { p -> (listOf(p.image) + p.related).map { it.uri } },
            )
            // Process from oldest to newest
            val orderedEntries = entries
            val allSucceededUris = mutableSetOf<String>()
            val allFailedItems = mutableListOf<Pair<ImageItem, String?>>()
            var totalBytesFreed = 0L

            for (pending in orderedEntries) {
                val targets = listOf(pending.image) + pending.related
                val succeededUris = mutableSetOf<String>()
                val failedForEntry = mutableListOf<Pair<ImageItem, String?>>()

                for (img in targets) {
                    try {
                        val deleted = imageRepository.deleteImage(Uri.parse(img.uri))
                        if (deleted) {
                            succeededUris.add(img.uri)
                            allSucceededUris.add(img.uri)
                            totalBytesFreed += img.fileSize
                        } else {
                            Log.e(TAG, "Failed to delete file on disk: ${img.uri}")
                            failedForEntry.add(img to "could not be deleted")
                            allFailedItems.add(img to "could not be deleted")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting file: ${img.uri}", e)
                        val reason = e.message ?: "delete failed"
                        failedForEntry.add(img to reason)
                        allFailedItems.add(img to reason)
                    }
                }

                if (failedForEntry.isNotEmpty() && _uiState.value.sourceFolderUri == folderAtDelete) {
                    val failedSlots = OptimisticFeed.slotsLeftBehind(pending.slots, goneUris = succeededUris)
                    restoreToFeed(failedSlots)
                    loadExifForCurrent()
                }
            }

            if (allSucceededUris.isNotEmpty()) {
                val milestone = statsRepository.recordCommittedDelete(
                    kind = kindAtDelete,
                    bytesFreed = totalBytesFreed,
                    fileCount = allSucceededUris.size,
                    celebrationsEnabled = _uiState.value.celebrationsEnabled,
                )
                _uiState.update { state ->
                    state.copy(
                        cardDeletedCount = state.cardDeletedCount + allSucceededUris.size,
                        celebrationBanner = milestone?.message ?: state.celebrationBanner,
                    )
                }
            }

            if (allFailedItems.isNotEmpty()) {
                val errorMsg = if (allSucceededUris.isEmpty()) {
                    val (firstImg, reason) = allFailedItems.first()
                    "Could not delete ${firstImg.fileName}: ${reason ?: "delete failed"}"
                } else {
                    val failedNames = allFailedItems.joinToString { it.first.fileName }
                    val reason = allFailedItems.first().second ?: "delete failed"
                    "Deleted ${allSucceededUris.size} of ${allSucceededUris.size + allFailedItems.size} — could not delete $failedNames ($reason)"
                }
                _uiState.update {
                    it.copy(
                        lastActionFeedback = ActionFeedback(
                            message = errorMsg,
                            isError = true,
                        )
                    )
                }
            }

            // The deletions are committed: a folder that was left meanwhile no longer needs its grant.
            if (folderAtDelete != null) settingsRepository.releaseFolderGrantIfUnreferenced(folderAtDelete)
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

        maybeShowFirstRunHint(FirstRunHint.REVERT)

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
        persistCurrentFolderPosition()
        viewModelScope.launch {
            settingsRepository.setPersistedPendingDeleteUris(remainingUris.toSet())
        }
        loadExifForCurrent()
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
        showFeedback("Safe to unplug")
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
        showFeedback("Rejects deleted")
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

    /** Final confirmation for Option 3: Clear entire card (`U-14c`). */
    fun confirmClearEntireCard() {
        val state = _uiState.value
        val toDelete = state.allImages
        if (toDelete.isEmpty()) {
            _uiState.update {
                it.copy(
                    showCardDoneSheet = false,
                    cardClearStep = 0,
                    cardClearComplete = true,
                )
            }
            showFeedback("Safe to unplug card")
            return
        }
        _uiState.update { it.copy(cardClearStep = 3) }
        val epochAtLaunch = feedEpoch
        appScope.launch {
            var deletedCount = 0
            var freedBytes = 0L
            for (img in toDelete) {
                val ok = try {
                    imageRepository.deleteImage(Uri.parse(img.uri))
                } catch (_: Exception) {
                    false
                }
                if (ok) {
                    deletedCount++
                    freedBytes += img.fileSize
                }
            }
            if (deletedCount > 0) {
                statsRepository.recordCommittedDelete(
                    kind = state.sourceKind,
                    bytesFreed = freedBytes,
                    fileCount = deletedCount,
                    celebrationsEnabled = state.celebrationsEnabled,
                )
            }
            if (feedEpoch != epochAtLaunch) {
                // Left the card meanwhile: the deletions stand, but the screen shows something else.
                _uiState.update { it.copy(cardClearStep = 0) }
                showFeedback(if (deletedCount == 1) "1 photo deleted" else "$deletedCount photos deleted")
                return@launch
            }
            _uiState.update {
                it.copy(
                    images = emptyList(),
                    allImages = emptyList(),
                    currentIndex = 0,
                    showCardDoneSheet = false,
                    cardClearStep = 0,
                    cardClearComplete = true,
                    cardDeletedCount = it.cardDeletedCount + deletedCount,
                    emptyFolderMessage = "Card cleared · safe to unplug",
                )
            }
            showFeedback(if (deletedCount == 1) "1 photo deleted" else "$deletedCount photos deleted")
        }
    }

    fun clearPendingIntentSenderRequest() {
        _uiState.update { it.copy(pendingIntentSenderRequest = null) }
    }

    fun updateDirectDeleteConfirm(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPhoneDirectDeleteConfirmEnabled(enabled)
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(lastActionFeedback = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: String) {
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
                        collectionFolderName = state.collectionFolderName,
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
    private fun leftSwipeHint(): FirstRunHint =
        if (_uiState.value.leftSwipeAction == SwipeAction.DELETE) {
            FirstRunHint.SWIPE_LEFT_DELETE
        } else {
            FirstRunHint.SWIPE_LEFT_FOLDER
        }

    // ── RAW + JPEG pair suggestions (`U-12`) ─────────────────────────────

    fun maybeShowRawJpegSuggestion() {
        val state = _uiState.value
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

    fun applyRawJpegFilter(filter: FileTypeFilter) {
        _uiState.update { it.copy(showRawJpegSuggestion = false, firstRunHint = null) }
        viewModelScope.launch {
            settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS)
            settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH)
            settingsRepository.setPhoneFileTypeFilter(filter)
            if (filter == FileTypeFilter.ONE_PER_SHOT) {
                settingsRepository.setPhoneMoveRelatedFiles(true)
            }
        }
        val label = when (filter) {
            FileTypeFilter.ONE_PER_SHOT -> "1 card per shot"
            FileTypeFilter.RAW -> "RAW only"
            FileTypeFilter.JPG -> "JPEG only"
            FileTypeFilter.ALL -> "all photos"
        }
        showFeedback("Showing $label")
    }

    fun enableMoveRelatedFiles() {
        _uiState.update { it.copy(showRawJpegSuggestion = false) }
        viewModelScope.launch {
            settingsRepository.markFirstRunHintSeen(FirstRunHint.RAW_JPEG_PAIRS)
            settingsRepository.setPhoneMoveRelatedFiles(true)
        }
        showFeedback("RAW + JPEG linked")
    }

    // ── Filter mismatch hint ─────────────────────────────────────────────

    fun maybeShowFilterMismatchHint() {
        val state = _uiState.value
        if (state.showGestureTutorial || state.showControlsGuide) return
        if (state.showRawJpegSuggestion) return
        if (state.firstRunHint != null) return
        if (FirstRunHint.FILTER_MISMATCH.key in state.seenFirstRunHints) return
        if (state.fileTypeFilter == FileTypeFilter.ALL || state.fileTypeFilter == FileTypeFilter.ONE_PER_SHOT) return
        if (state.allImages.isEmpty()) return

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
                        actionLabel = "SHOW ALL",
                        onAction = { applyRawJpegFilter(FileTypeFilter.ALL) },
                    ),
                )
            }
            viewModelScope.launch {
                settingsRepository.markFirstRunHintSeen(FirstRunHint.FILTER_MISMATCH)
            }
        }
    }

    /**
     * Rescan the current source folder from disk.
     */
    fun reloadSourceFolder() {
        val folderUriStr = _uiState.value.sourceFolderUri ?: return
        val folderUri = Uri.parse(folderUriStr)
        val currentIndexBefore = _uiState.value.currentIndex
        val currentUri = _uiState.value.images.getOrNull(currentIndexBefore)?.uri
        val wasActive = _uiState.value.images.isNotEmpty()
        val urisBefore = _uiState.value.images.map { it.uri }.toSet()
        finalizePendingDelete()
        feedEpoch++
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        publishedUris.clear()
        dimensionsResolved.clear()
        loadedExifCache.clear()

        discoveryJob = viewModelScope.launch {
            val scanInfo = settingsRepository.getFolderScanInfo(folderUriStr)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isDiscovering = true,
                    error = null,
                    missingGrantPrompt = false,
                    images = emptyList(),
                    allImages = emptyList(),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    showRawJpegSuggestion = false,
                    reloadPrompt = null,
                )
            }
            val folderName = imageRepository.prepareSourceFolder(folderUri)
            if (folderName == null) {
                if (settingsRepository.lastFolderUri.first() == folderUriStr) {
                    settingsRepository.setLastFolderUri(null)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isDiscovering = false,
                        sourceFolderUri = null,
                        sourceFolderName = "",
                        missingGrantPrompt = true,
                        error = "Can't access this folder",
                    )
                }
                return@launch
            }
            collectDiscoveredImages(
                folderUri = folderUri,
                errorLabel = "images",
                targetUriToKeep = currentUri,
                previousScanInfo = scanInfo.copy(
                    lastViewedUri = currentUri ?: scanInfo.lastViewedUri,
                    lastViewedIndex = if (wasActive) currentIndexBefore else scanInfo.lastViewedIndex,
                ),
                wasActiveSession = wasActive,
                urisBeforeReload = urisBefore,
            )
        }
    }

    /** User chose to jump to the new photos from the reload/reopen prompt. */
    fun confirmReloadJumpToLatest() {
        val prompt = _uiState.value.reloadPrompt ?: return
        val images = _uiState.value.images
        val target = if (prompt.firstNewIndex in images.indices) {
            prompt.firstNewIndex
        } else {
            0 // newest-first feed: the latest photo is at the start
        }
        _uiState.update { it.copy(reloadPrompt = null) }
        navigateToImage(target)
        _uiState.value.sourceFolderUri?.let { uri ->
            viewModelScope.launch {
                val currentImages = _uiState.value.images
                val updatedScan = FolderScanLogic.computeUpdatedScanInfo(
                    images = currentImages,
                    lastViewedUri = currentImages.getOrNull(target)?.uri,
                    lastViewedIndex = target,
                )
                settingsRepository.setFolderScanInfo(uri, updatedScan)
            }
        }
    }

    /** User chose to continue where they left off from the resume/reload prompt. */
    fun dismissReloadPrompt() {
        val prompt = _uiState.value.reloadPrompt
        val images = _uiState.value.images
        val target = if (prompt != null && images.isNotEmpty()) {
            prompt.resumeIndex.coerceIn(0, images.size - 1)
        } else {
            _uiState.value.currentIndex
        }
        _uiState.update { it.copy(reloadPrompt = null, currentIndex = target) }
        loadExifForCurrent()
        _uiState.value.sourceFolderUri?.let { uri ->
            viewModelScope.launch {
                val currentImages = _uiState.value.images
                val updatedScan = FolderScanLogic.computeUpdatedScanInfo(
                    images = currentImages,
                    lastViewedUri = currentImages.getOrNull(target)?.uri,
                    lastViewedIndex = target,
                )
                settingsRepository.setFolderScanInfo(uri, updatedScan)
            }
        }
    }

    private fun persistCurrentFolderPosition() {
        val state = _uiState.value
        val folderUri = state.sourceFolderUri ?: return
        if (state.images.isEmpty()) return
        val idx = state.currentIndex.coerceIn(0, state.images.size - 1)
        val currentUri = state.images.getOrNull(idx)?.uri
        viewModelScope.launch {
            settingsRepository.setFolderLastPosition(folderUri, currentUri, idx)
        }
    }

    private fun showFeedback(message: String, isError: Boolean = false) {
        _uiState.update {
            it.copy(lastActionFeedback = ActionFeedback(message = message, isError = isError))
        }
    }

    // ── Navigation helpers ───────────────────────────────────────────────

    /** Go back to the landing screen (clears images). */
    fun goBackToLanding() {
        persistCurrentFolderPosition()
        finalizePendingDelete()
        feedEpoch++
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        publishedUris.clear()
        dimensionsResolved.clear()
        _uiState.update {
            it.copy(
                images = emptyList(),
                allImages = emptyList(),
                currentIndex = 0,
                portraitSectionStart = -1,
                isDiscovering = false,
                reloadPrompt = null,
                showCardDoneSheet = false,
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** Remove a set of images (by URI) from both the filtered and unfiltered lists. */
    private fun removeImagesFromLists(uris: Set<String>) {
        val state = _uiState.value
        val updatedImages = state.images.filter { it.uri !in uris }
        val updatedAll = state.allImages.filter { it.uri !in uris }
        val newIndex = state.currentIndex.coerceAtMost(updatedImages.size - 1).coerceAtLeast(0)

        _uiState.update {
            it.copy(
                images = updatedImages,
                allImages = updatedAll,
                currentIndex = newIndex,
                portraitSectionStart = PhoneFeedOrdering.portraitSplit(
                    updatedImages,
                    state.sortByOrientation,
                ),
            )
        }
        persistCurrentFolderPosition()
        loadExifForCurrent()
    }

    private fun loadExifForCurrent() {
        val state = _uiState.value
        if (state.images.isEmpty()) return

        val indices = listOf(state.currentIndex, state.currentIndex - 1, state.currentIndex + 1)
            .filter { it in state.images.indices }

        viewModelScope.launch {
            indices.forEach { idx ->
                val image = state.images[idx]
                if (image.exifData == null) {
                    val cached = loadedExifCache[image.uri]
                    if (cached != null) {
                        updateImageExif(image.uri, cached)
                    } else {
                        val exif = imageRepository.getExifData(Uri.parse(image.uri))
                        if (exif != null) {
                            loadedExifCache[image.uri] = exif
                            updateImageExif(image.uri, exif)
                        }
                    }
                }
            }
        }
    }

    private fun updateImageExif(uri: String, exif: ExifData) {
        _uiState.update { state ->
            val updatedImages = state.images.map { img ->
                if (img.uri == uri) img.copy(exifData = exif) else img
            }
            state.copy(images = updatedImages)
        }
    }

    private fun loadDimensionsAsynchronously(force: Boolean = false) {
        if (!force && dimensionsJob?.isActive == true) return
        dimensionsJob?.cancel()
        dimensionsJob = viewModelScope.launch {
            val images = _uiState.value.images
            val currentIndex = _uiState.value.currentIndex
            val sortedIndices = images.indices.sortedBy { abs(it - currentIndex) }

            val batch = mutableMapOf<String, Pair<Int, Int>>()
            for (idx in sortedIndices) {
                val image = images.getOrNull(idx) ?: continue
                if (image.uri in dimensionsResolved) continue
                if (image.imageWidth == 0 && image.imageHeight == 0) {
                    val (w, h) = try {
                        imageRepository.getImageDimensions(Uri.parse(image.uri))
                    } catch (e: Exception) {
                        Pair(0, 0)
                    }
                    dimensionsResolved.add(image.uri)
                    if (w > 0 && h > 0) {
                        batch[image.uri] = Pair(w, h)
                        if (batch.size >= DIMENSION_BATCH_SIZE) {
                            applyDimensions(batch.toMap())
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) {
                applyDimensions(batch.toMap())
            }
        }
    }

    private fun applyDimensions(dimensions: Map<String, Pair<Int, Int>>) {
        _uiState.update { state ->
            fun ImageItem.withDims(): ImageItem {
                val dims = dimensions[uri] ?: return this
                return copy(imageWidth = dims.first, imageHeight = dims.second)
            }

            val updatedAll = state.allImages.map { it.withDims() }
            val updatedImages = state.images.map { it.withDims() }

            if (!state.randomizeOrder && state.sortByOrientation) {
                val currentActiveUri = state.images.getOrNull(state.currentIndex)?.uri
                val landscape = updatedImages.filter { it.isLandscape }
                val portrait = updatedImages.filter { !it.isLandscape }
                val regrouped = landscape + portrait
                val newIndex = if (currentActiveUri != null) {
                    regrouped.indexOfFirst { it.uri == currentActiveUri }.coerceAtLeast(0)
                } else {
                    state.currentIndex
                }
                state.copy(
                    allImages = updatedAll,
                    images = regrouped,
                    portraitSectionStart = if (portrait.isEmpty()) -1 else landscape.size,
                    currentIndex = newIndex,
                )
            } else {
                state.copy(allImages = updatedAll, images = updatedImages)
            }
        }
    }

    public override fun onCleared() {
        // Finalizes primary AND sibling files on the application scope, so the
        // deletion completes even though the ViewModel is going away.
        finalizePendingDelete()
        super.onCleared()
    }
}
