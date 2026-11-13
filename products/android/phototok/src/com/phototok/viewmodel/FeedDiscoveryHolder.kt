package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.model.ExifData
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SeenRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.StatsRepository
import com.phototok.domain.CompanionFiles
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.FolderScanLogic
import com.phototok.domain.PhoneFeedOrdering
import com.phototok.domain.PhotoFolders
import com.phototok.domain.RelatedFiles
import com.phototok.domain.SurpriseMe
import com.phototok.domain.UiText
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The format filter and order the open feed is built with. */
internal data class ActiveFeedConfig(
    val fileTypeFilter: FileTypeFilter,
    val feedOrder: FeedOrderMode,
    val sortByOrientation: Boolean,
    val randomizeOrder: Boolean,
)

/**
 * The feed of [PhoneModeViewModel]: folder selection, progressive discovery (REQ-TOK-ARCH: first
 * batch fast, append-only merge against every URI ever published), ordering, navigation, seen
 * tracking, Reload and its prompt, EXIF and dimension loading. It writes the view model's one
 * [PhoneModeUiState] (`_uiState`) and launches on the view model's scope, so the screens do not
 * change ([USER 2026-11-12] decision #10).
 *
 * [feedEpoch] is the guard every late result checks: it is bumped whenever the visible feed is
 * discarded or rebuilt, and work launched on the application scope compares it before touching
 * the feed. What this holder needs from its siblings goes through [Host].
 */
internal class FeedDiscoveryHolder(
    private val _uiState: MutableStateFlow<PhoneModeUiState>,
    /** The owning view model's `viewModelScope`; cancelled when the view model is cleared. */
    private val viewModelScope: CoroutineScope,
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository,
    private val seenRepository: SeenRepository,
    private val statsRepository: StatsRepository,
    private val host: Host,
) {
    /** What the feed asks of the view model and the other holders. */
    interface Host {
        fun finalizePendingDelete()
        fun flushSystemFavourites()
        fun cancelSystemIndex()
        fun indexFeedFromSystem()
        fun observeSourceSettingsForKind(kind: SourceKind)
        suspend fun resolveActiveFeedConfig(): ActiveFeedConfig
        fun checkGestureTutorial()
        fun maybeShowFirstRunHint(hint: FirstRunHint)
        fun maybeShowRawJpegSuggestion()
        fun maybeShowFilterMismatchHint()
    }

    private companion object {
        const val MAX_EXIF_CACHE_SIZE = 30
    }

    /**
     * Bumped whenever the visible feed is discarded or rebuilt (folder switch, reload, landing,
     * card unmount). Late results of work launched on the application scope compare it with the value captured
     * at launch before touching the feed: a stale result may only affect durable outcomes (journal,
     * stats), never the presentation of a feed that belongs to something else now.
     */
    var feedEpoch = 0
        private set

    private val loadedExifCache = object : LinkedHashMap<String, ExifData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExifData>?): Boolean =
            size > MAX_EXIF_CACHE_SIZE
    }

    /** Active folder-discovery collection; cancelled when a new folder is selected. */
    private var discoveryJob: Job? = null
    private var dimensionsJob: Job? = null

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

    /**
     * False while a Reload streams in: a Reload ignores what was seen, so a shuffled feed is not
     * split unseen-first and a shooting-order feed does not land on the first unseen photo.
     * (Every other order never splits, see `PhoneFeedOrdering.order`.)
     */
    private var unseenFirstOrdering = true

    fun handleVolumeUnmounted(unmountedUuid: String?) {
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
                firstRunHint = null,
                lastActionFeedback = ActionFeedback(
                    message = UiText.of(R.string.snackbar_card_disconnected),
                    isError = false,
                ),
            )
        }
    }

    fun restoreLastFolders() {
        viewModelScope.launch {
            settingsRepository.discardStalePendingDeletes()
            settingsRepository.reconcilePersistedGrants()
            val lastUri = settingsRepository.lastFolderUri.first()
            if (lastUri != null && _uiState.value.sourceFolderUri == null) {
                selectSourceFolder(Uri.parse(lastUri))
            }
        }
    }

    // ── Folder selection ──────────────────────────────────────────────────

    fun selectSourceFolder(uri: Uri) {
        host.finalizePendingDelete()
        host.flushSystemFavourites()
        feedEpoch++
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        host.cancelSystemIndex()
        publishedUris.clear()
        dimensionsResolved.clear()
        _uiState.update {
            it.copy(emptyFolderMessage = null, missingGrantPrompt = false, feedStatsBaseline = it.cullingStats, showSessionStats = false)
        }

        val kind = PhoneModeViewModel.classifyUri(uri, _uiState.value.mountedVolumes)
        val volUuid = SourceKindClassifier.extractRemovableUuid(uri)
        val isCloud = kind == SourceKind.CLOUD_OTHER
        // Until this kind's settings arrive, no destination of the previous source applies.
        _uiState.update {
            if (it.sourceKind == kind) it else it.copy(
                collectionFolderUri = null,
                collectionFolderName = "",
                leftSwipeFolderUri = null,
                leftSwipeFolderName = "",
            )
        }
        host.observeSourceSettingsForKind(kind)

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
                    // A hint about the previous folder ("Most photos here are JPEG") is stale here.
                    firstRunHint = null,
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
                        error = UiText.of(R.string.error_folder_access),
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
                previousScanInfo = scanInfo,
            )
        }
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
        targetUriToKeep: String? = null,
        previousScanInfo: FolderScanInfo = FolderScanInfo(),
        jumpToLatest: Boolean = false,
    ) {
        publishedUris.clear()
        dimensionsResolved.clear()
        userHasNavigated = false
        _uiState.update { it.copy(surpriseMe = false) } // every open and Reload starts newest first
        val config = host.resolveActiveFeedConfig()
        newestFirstReorderAllowed = targetUriToKeep == null
        unseenFirstOrdering = !jumpToLatest

        try {
            // A Pro user's own Selection folder name is output too, wherever the marker is or is not.
            val outputNames = PhotoFolders.extraOutputNames(_uiState.value.selectionSubfolderName)
            imageRepository.discoverImages(folderUri, outputNames).collect { discovered ->
                publishBatch(discovered, config, targetUriToKeep)
            }
            // Enumeration finished:
            // If not randomized, sort cumulative list according to active feedOrder to guarantee whole folder order,
            // while preserving current item or targetUriToKeep.
            val current = _uiState.value
            val uriToPreserve = targetUriToKeep
                ?: current.images.getOrNull(current.currentIndex)?.uri
            val filtered = PhoneFeedOrdering.filterByType(current.allImages, config.fileTypeFilter)
            val seenUris = if (unseenFirstOrdering) {
                seenRepository.filterSeenUris(filtered, folderUri.toString())
            } else {
                emptySet()
            }
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
            val newIndex: Int

            val shouldPromptResume = sorted.images.isNotEmpty() && previousScanInfo.hasPreviousSession

            if (jumpToLatest && sorted.images.isNotEmpty()) {
                // An explicit Reload: no dialog and no snackbar, the user lands on the latest photo
                // (position 1 of a newest-first feed). Someone who already swiped while the folder
                // was being rescanned keeps their place.
                newIndex = if (userHasNavigated) {
                    // Follow the photo they are looking at through the final ordering.
                    sorted.images.indexOfFirst { it.uri == uriToPreserve }.takeIf { it >= 0 }
                        ?: current.currentIndex.coerceIn(0, sorted.images.size - 1)
                } else {
                    FolderScanLogic.latestIndex(sorted.images)
                }
                val updatedScan = FolderScanLogic.computeUpdatedScanInfo(
                    images = sorted.images,
                    lastViewedUri = sorted.images.getOrNull(newIndex)?.uri,
                    lastViewedIndex = newIndex,
                )
                settingsRepository.setFolderScanInfo(folderUri.toString(), updatedScan)
            } else if (shouldPromptResume) {
                val anchorUri = targetUriToKeep ?: previousScanInfo.lastViewedUri
                val resumeIdx = FolderScanLogic.resolveResumeIndex(
                    images = sorted.images,
                    lastViewedUri = anchorUri,
                    lastViewedIndex = previousScanInfo.lastViewedIndex,
                )
                newIndex = if (userHasNavigated) {
                    resolveFeedIndex(current.currentIndex, sorted.images.size, uriToPreserve, sorted.images)
                } else {
                    resumeIdx
                }

                val newPhotos = FolderScanLogic.findNewPhotos(
                    images = sorted.images,
                    previousMaxLastModified = previousScanInfo.maxLastModified,
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
                    // Nothing new means nothing to decide: resume silently at the saved photo.
                    val updatedScan = FolderScanLogic.computeUpdatedScanInfo(
                        images = sorted.images,
                        lastViewedUri = sorted.images.getOrNull(newIndex)?.uri,
                        lastViewedIndex = newIndex,
                    )
                    settingsRepository.setFolderScanInfo(folderUri.toString(), updatedScan)
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

            val emptyMsg = emptyFeedMessage(sorted.images, current.allImages, config.fileTypeFilter, current.sourceFolderName)

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
                    hiddenVideoCount = if (current.allImages.isEmpty()) imageRepository.skippedVideoCount(folderUri) else 0,
                )
            }
            if (_uiState.value.importToPhoneCamera) host.maybeShowFirstRunHint(FirstRunHint.CARD_IMPORT)
            loadExifForCurrent()
            host.maybeShowRawJpegSuggestion()
            host.maybeShowFilterMismatchHint()
            loadDimensionsAsynchronously(force = true)
            host.indexFeedFromSystem()
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
                    error = UiText.of(R.string.error_folder_access),
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isDiscovering = false,
                    error = UiText.of(R.string.error_load_failed),
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

        val freshFiltered = PhoneFeedOrdering.filterByType(fresh, config.fileTypeFilter)
        // Computed from the state the write replaces: a rollback or a swipe may have changed the
        // lists since the read above, and publishedUris stops a dropped photo from coming back.
        _uiState.update { s ->
            val allCombined = s.allImages + fresh
            // Only a shuffled feed deals unseen photos first; no other order reads the seen set, and
            // fingerprinting the whole feed for each 250-photo batch is quadratic.
            val seenUris = if (unseenFirstOrdering && config.feedOrder == FeedOrderMode.SHUFFLE) {
                seenRepository.filterSeenUris(allCombined, s.sourceFolderUri)
            } else {
                emptySet()
            }
            val merged = PhoneFeedOrdering.appendBatch(
                current = s.images,
                fresh = freshFiltered,
                mode = config.feedOrder,
                reorder = newestFirstReorderAllowed && !userHasNavigated,
                currentIndex = s.currentIndex,
                seenUris = seenUris,
            )
            s.copy(
                allImages = allCombined,
                images = merged.images,
                portraitSectionStart = merged.portraitSectionStart,
                currentIndex = resolveFeedIndex(s.currentIndex, merged.images.size, targetUriToKeep, merged.images),
                isLoading = false,
            )
        }

        if (isFirstBatch) host.checkGestureTutorial()
        host.maybeShowRawJpegSuggestion()
        host.maybeShowFilterMismatchHint()
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

    // ── Sorting ───────────────────────────────────────────────────────────

    private suspend fun sortImages(images: List<ImageItem>): PhoneFeedOrdering.Result {
        val config = host.resolveActiveFeedConfig()
        val seenUris = seenRepository.filterSeenUris(images, _uiState.value.sourceFolderUri)
        return PhoneFeedOrdering.order(
            images = images,
            mode = config.feedOrder,
            seenUris = seenUris,
        )
    }

    /** Casual "surprise me" ([USER 2026-11-12] decision #14): unseen photos first, shuffled, this visit only. */
    fun toggleSurpriseMe() {
        val s = _uiState.value
        if (s.surpriseMeShown == null) return
        val on = !s.surpriseMe
        host.finalizePendingDelete() // a new order invalidates the undo slots, as every re-sort does
        val images = _uiState.value.images
        val seen = seenRepository.filterSeenUris(images, s.sourceFolderUri)
        val dealt = PhoneFeedOrdering.order(images, SurpriseMe.order(on, s.feedOrder), seenUris = seen)
        _uiState.update { it.copy(images = dealt.images, portraitSectionStart = dealt.portraitSectionStart, currentIndex = 0, surpriseMe = on) }
        loadExifForCurrent()
        if (on && seen.containsAll(images.map { it.uri })) _uiState.showFeedback(UiText.of(R.string.snackbar_all_seen))
        if (on) host.maybeShowFirstRunHint(FirstRunHint.SURPRISE)
    }

    fun resortImages() {
        host.finalizePendingDelete()
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
            // Landscape first needs every photo's shape; a pass that is already running carries on.
            loadDimensionsAsynchronously()
        }
    }

    fun refilterAndSort() {
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
                    // Show all (or another filter) reveals photos; a filter hiding them all says so.
                    emptyFolderMessage = emptyFeedMessage(sorted.images, it.allImages, it.fileTypeFilter, it.sourceFolderName),
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
            // One more card is seen: count it instead of fingerprinting the whole feed on a swipe.
            // Discovery, reload and folder changes recompute the exact figure.
            val newSeenCount = (state.seenCountInFolder + 1).coerceAtMost(state.images.size)
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
            host.maybeShowRawJpegSuggestion()
            host.maybeShowFilterMismatchHint()
            loadExifForCurrent()
        }
    }

    /**
     * Sibling files that share the same base name (stem) but differ in extension,
     * e.g. IMG_001.JPG and IMG_001.ARW. Computed from the unfiltered list so it is
     * independent of the user's file-type filter selection.
     */
    fun relatedImages(target: ImageItem): List<ImageItem> =
        RelatedFiles.bundleOf(_uiState.value.allImages, target)

    /** The feed-visible part of [relatedImages] (no `.xmp` sidecars): favourites, seen and counts. */
    fun relatedFeedImages(target: ImageItem): List<ImageItem> =
        relatedImages(target).filterNot { CompanionFiles.isSidecar(it.fileName) }

    /**
     * Returns true if [image] has a RAW+JPEG companion pair in [PhoneModeUiState.allImages] (`U-12`).
     */
    fun hasRawCompanion(image: ImageItem): Boolean =
        RelatedFiles.hasRawCompanion(_uiState.value.allImages, image)

    /** Move to the next photo without waiting for anything. */
    fun advanceToNextImage() {
        val state = _uiState.value
        val next = state.currentIndex + 1
        if (next in state.images.indices) navigateToImage(next)
    }

    /**
     * Rescan the current source folder from disk and take the user to its latest photo, whether
     * or not anything new appeared (`REQ-TOK-FEATURE.09`). The rescanned feed drops the
     * unseen-first split, so in a newest-first feed that is position 1. No dialog and no
     * snackbar: the "N new photos / Show new / Stay" prompt belongs to reopening a folder.
     */
    fun reloadSourceFolder() {
        val folderUriStr = _uiState.value.sourceFolderUri ?: return
        val folderUri = Uri.parse(folderUriStr)
        host.finalizePendingDelete()
        feedEpoch++
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        host.cancelSystemIndex()
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
                        error = UiText.of(R.string.error_folder_access),
                    )
                }
                return@launch
            }
            // The old photo is not pinned during discovery: the user is heading for the latest one.
            collectDiscoveredImages(
                folderUri = folderUri,
                previousScanInfo = scanInfo,
                jumpToLatest = true,
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

    fun persistCurrentFolderPosition() {
        val state = _uiState.value
        val folderUri = state.sourceFolderUri ?: return
        if (state.images.isEmpty()) return
        val idx = state.currentIndex.coerceIn(0, state.images.size - 1)
        val currentUri = state.images.getOrNull(idx)?.uri
        viewModelScope.launch {
            settingsRepository.setFolderLastPosition(folderUri, currentUri, idx)
        }
    }

    // ── Navigation helpers ───────────────────────────────────────────────

    /** Go back to the landing screen (clears images). */
    fun goBackToLanding() {
        persistCurrentFolderPosition()
        host.finalizePendingDelete()
        host.flushSystemFavourites()
        feedEpoch++
        discoveryJob?.cancel()
        dimensionsJob?.cancel()
        host.cancelSystemIndex()
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
                showSessionStats = true,
                // Every first-run hint explains the feed the user just left (REQ-TOK-GESTURE.05).
                // It was marked seen when shown, so dropping it is what its own dismiss does.
                firstRunHint = null,
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** Remove a set of images (by URI) from both the filtered and unfiltered lists. */
    fun removeImagesFromLists(uris: Set<String>) {
        _uiState.update { s ->
            val updatedImages = s.images.filter { it.uri !in uris }
            val updatedAll = s.allImages.filter { it.uri !in uris }
            s.copy(
                images = updatedImages,
                allImages = updatedAll,
                currentIndex = s.currentIndex.coerceAtMost(updatedImages.size - 1).coerceAtLeast(0),
                portraitSectionStart = PhoneFeedOrdering.portraitSplit(updatedImages, s.sortByOrientation),
            )
        }
        persistCurrentFolderPosition()
        loadExifForCurrent()
    }

    /**
     * Take files that are gone from disk out of the current feed, if they are in it: nothing else
     * changes (no position persist, no EXIF load). A no-op for a feed that never listed them.
     */
    fun dropFromFeed(uris: Set<String>) {
        _uiState.update { s ->
            if (s.allImages.none { it.uri in uris } && s.images.none { it.uri in uris }) return@update s
            val activeUri = s.images.getOrNull(s.currentIndex)?.uri
            val images = s.images.filter { it.uri !in uris }
            val all = s.allImages.filter { it.uri !in uris }
            val index = activeUri?.let { u -> images.indexOfFirst { it.uri == u } }?.takeIf { it >= 0 }
                ?: s.currentIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
            s.copy(
                images = images,
                allImages = all,
                currentIndex = index,
                portraitSectionStart = PhoneFeedOrdering.portraitSplit(images, s.sortByOrientation),
            )
        }
    }

    fun loadExifForCurrent() {
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

    /**
     * Read the dimensions of the feed in the background, nearest photos first. Only "landscape
     * first" ordering needs them for the whole folder (everything else reads one photo's size when
     * its details are opened, see [loadDimensionsOfCurrent]), and each read opens the file.
     */
    private fun loadDimensionsAsynchronously(force: Boolean = false) {
        if (!_uiState.value.sortByOrientation) return
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
                    } catch (e: CancellationException) {
                        // A restarted pass reads this photo again: it must not count as resolved.
                        throw e
                    } catch (e: Exception) {
                        Pair(0, 0)
                    }
                    dimensionsResolved.add(image.uri)
                    if (w > 0 && h > 0) {
                        batch[image.uri] = Pair(w, h)
                        if (batch.size >= PhoneModeViewModel.DIMENSION_BATCH_SIZE) {
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

    /** Read the size of the photo on screen, for its details (the folder-wide pass is off by default). */
    fun loadDimensionsOfCurrent() {
        val state = _uiState.value
        val image = state.images.getOrNull(state.currentIndex) ?: return
        if (image.imageWidth > 0 || image.imageHeight > 0) return
        viewModelScope.launch {
            val (w, h) = try {
                imageRepository.getImageDimensions(Uri.parse(image.uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (w > 0 && h > 0) applyDimensions(mapOf(image.uri to Pair(w, h)))
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
}
