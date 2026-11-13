package com.phototok.viewmodel

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.FavouritesRepository
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.repository.StatsRepository
import com.phototok.data.repository.SystemTrashRepository
import com.phototok.data.source.FileOperationResult
import com.phototok.domain.CardTransferJournal
import com.phototok.domain.CollectionAction
import com.phototok.domain.CopyMoveFeedback
import com.phototok.domain.OptimisticFeed
import com.phototok.domain.PendingDeleteLogic
import com.phototok.domain.PhoneFeedOrdering
import com.phototok.domain.PhotoFolders
import com.phototok.domain.RelatedFiles
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeDestinations
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a swipe does to files, for [PhoneModeViewModel]: swipe right (favourite, copy or move to
 * Selection, the Casual card import into the phone's camera folder), swipe left (copy/move, or a
 * delete with its confirmation), the bounded undo stack and the finalisation of deletions, and
 * the "Done with this card" sheet ([USER 2026-11-12] decision #10).
 *
 * Copies and moves are optimistic (the feed changes at once, a failure puts the files back) and,
 * like finalised deletions, run on [appScope] so they finish after the view model is gone
 * (REQ-TOK-ARCH.08). A late result touches the feed only while [FeedDiscoveryHolder.feedEpoch]
 * still has the value it had at launch. The folders whose deletions are queued or still running
 * stay pinned as grant holders ([syncUndoHistoryPin]).
 */
internal class TransferUndoHolder(
    private val _uiState: MutableStateFlow<PhoneModeUiState>,
    /** The owning view model's `viewModelScope`; cancelled when the view model is cleared. */
    private val viewModelScope: CoroutineScope,
    /** The application scope: work that must outlive the view model. */
    private val appScope: CoroutineScope,
    private val feed: FeedDiscoveryHolder,
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository,
    private val favouritesRepository: FavouritesRepository,
    private val statsRepository: StatsRepository,
    private val cardTransferJournal: CardTransferJournal,
    private val systemTrash: SystemTrashRepository,
    private val host: Host,
) {
    /** What transfers ask of the view model and the source settings. */
    interface Host {
        /** The one-time "cloud folders copy only" notice; a coerced Move marks it shown too. */
        var cloudMoveCoercedNoticeShown: Boolean
        fun afterFavouriteChange(items: List<ImageItem>)
        fun updateDirectDeleteConfirm(enabled: Boolean)
    }

    private companion object {
        const val TAG = "PhoneModeVM"
    }

    /** The phone's camera-folder tree a Casual card import copies into, once verified writable. */
    var cameraImportTarget: String? = null
        private set

    /** The photo whose swipe right is waiting for the camera-folder grant. */
    private var pendingCameraImportUri: String? = null

    private var pendingCameraImportIsCopy = true

    /** Guards against a second swipe while the camera-folder access check is running. */
    private var resolvingCameraImportTarget = false

    /** Folders whose committed-but-still-running deletions need their grant until they finish (D-TOK-4). */
    private val foldersWithRunningDeletes = mutableListOf<String>()

    /**
     * Pin the grants the undo history needs: the current folder while its deletions are on the
     * undo stack, plus every folder whose deferred deletes are still running on the application
     * scope. Without the second part, switching folders would drop the pin before the deletes ran.
     */
    fun syncUndoHistoryPin() {
        val state = _uiState.value
        val pins = linkedSetOf<String>()
        if (state.undoStack.isNotEmpty() || state.pendingDelete != null) {
            state.sourceFolderUri?.let(pins::add)
        }
        synchronized(foldersWithRunningDeletes) { pins.addAll(foldersWithRunningDeletes) }
        settingsRepository.pinUndoHistoryFolders(pins)
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
            host.afterFavouriteChange(listOf(currentImage) + related)
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
        if (isCopy && !host.cloudMoveCoercedNoticeShown) {
            host.cloudMoveCoercedNoticeShown = true
        }
        statsRepository.recordKeeper(state.sourceKind, 1)
        copyOrMoveCurrent(
            targetUri = target,
            isCopy = isCopy,
            subfolderName = state.selectionSubfolderName,
            destination = collectionDestination(state),
        )
    }

    // ── Casual card import: SD card / camera to the phone's camera folder ─────

    private fun cameraTreeUri(): Uri =
        DocumentsContract.buildTreeDocumentUri(PhoneModeViewModel.EXTERNAL_STORAGE_AUTHORITY, PhoneModeViewModel.CAMERA_DOCUMENT_ID)

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
                    it.copy(cameraFolderGrantRequest = CameraFolderGrantRequest(PhoneModeViewModel.pickerInitialUri(PhoneModeViewModel.CAMERA_DOCUMENT_ID)))
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

    fun refreshCameraImportTarget() {
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
                _uiState.showFeedback(UiText.of(R.string.snackbar_not_copied), isError = true)
                return@launch
            }
            settingsRepository.setCasualCameraTarget(uri.toString())
            setCameraImportTarget(uri.toString())
            val state = _uiState.value
            val index = state.images.indexOfFirst { it.uri == pendingUri }
            if (index < 0) {
                _uiState.showFeedback(UiText.of(R.string.snackbar_not_copied), isError = true)
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
        if (hadPending) _uiState.showFeedback(UiText.of(R.string.snackbar_not_copied))
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
        if (dontAskAgain) host.updateDirectDeleteConfirm(false)
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
        _uiState.showFeedback(UiText.of(R.string.snackbar_safe_to_unplug))
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
        _uiState.showFeedback(UiText.of(R.string.snackbar_rejects_deleted))
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
            _uiState.showFeedback(UiText.of(R.string.snackbar_safe_to_unplug))
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
                _uiState.showFeedback(UiText.of(R.string.snackbar_deleted_count, deletedCount))
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
                _uiState.showFeedback(UiText.of(R.string.snackbar_deleted_count, deletedCount))
            } else {
                _uiState.showFeedback(UiText.of(R.string.error_delete_some, deletedCount, toDelete.size), isError = true)
            }
        }
    }
}
