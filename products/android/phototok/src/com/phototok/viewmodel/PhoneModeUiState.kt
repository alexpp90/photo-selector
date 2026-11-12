package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.storage.ExternalVolume
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.data.model.RecentPath
import com.phototok.data.repository.CullingStats
import com.phototok.data.repository.FavouriteSyncRequest
import com.phototok.data.source.VolumeMonitor
import com.phototok.domain.CollectionAction
import com.phototok.domain.DeleteSplit
import com.phototok.domain.FavouritesSnapshot
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.domain.PendingDeleteLogic
import com.phototok.domain.PhotoFolders
import com.phototok.domain.RelatedFiles
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightOutcome
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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
    val error: UiText? = null,
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
    /** A swipe-left delete waits for the Direct Delete Confirmation dialog (REQ-TOK-GESTURE.03). */
    val confirmDeleteRequested: Boolean = false,
    /** What that dialog says: how many of the photo's files go to the system bin, how many for good. */
    val confirmDeleteSplit: DeleteSplit = DeleteSplit.permanentOnly(1),
    val sortByOrientation: Boolean = false,
    val randomizeOrder: Boolean = false,
    /** Casual "surprise me" dealt this folder unseen-first and shuffled; every open resets it. */
    val surpriseMe: Boolean = false,
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
    /** Prompt shown when a reopened folder holds photos newer than the last visit (not after Reload). */
    val reloadPrompt: ReloadPrompt? = null,
    /** Friendly explanation when a selected folder contains 0 supported photos. */
    val emptyFolderMessage: UiText? = null,
    /** True when the folder's persisted SAF grant was revoked or the folder was moved/deleted. */
    val missingGrantPrompt: Boolean = false,
    /** When true, Selection folder is stored inside each scanned folder. */
    val selectionUseSourceRoot: Boolean = false,
    /** In-place favourites and how far they reached MediaStore (`U-17`); match feed URIs with `isFavourite`. */
    val favourites: FavouritesSnapshot = FavouritesSnapshot(),
    /** Count of seen photos in the active folder (`U-15`). */
    val seenCountInFolder: Int = 0,
    /** Per-folder `(seen, total)` summary map for the landing screen (`U-15`). */
    val folderSeenProgress: Map<String, Pair<Int, Int>> = emptyMap(),
    /** Currently mounted removable volumes (`U-14a`). */
    val mountedVolumes: List<ExternalVolume> = emptyList(),
    /** Session and lifetime culling statistics (`U-19`). */
    val cullingStats: CullingStats = CullingStats(),
    /** Milestone celebration banner text (`U-19`). */
    val celebrationBanner: UiText? = null,
    val celebrationsEnabled: Boolean = true,
    /** The stats when the open folder was opened, and whether the landing shows that session (T1.4). */
    val feedStatsBaseline: CullingStats? = null,
    val showSessionStats: Boolean = false,
    /** What swipe right does per source, for the landing cues; and videos skipped by an empty folder. */
    val sourceOutcomes: Map<SourceKind, SwipeRightOutcome> = emptyMap(),
    val hiddenVideoCount: Int = 0,
    /** "Done with this card" sheet state (`U-14c`). */
    val showCardDoneSheet: Boolean = false,
    val cardCopiedCount: Int = 0,
    val cardDeletedCount: Int = 0,
    val cardClearStep: Int = 0,
    val cardClearComplete: Boolean = false,
    /** Favourite request (`createFavoriteRequest`) for the UI to launch once (`U-17`). */
    val pendingIntentSenderRequest: FavouriteSyncRequest? = null,
    /** Pro mode on? The settings above are already the effective (gated) values. */
    val proMode: Boolean = false,
    /**
     * Casual mode on an SD card or camera: swipe right copies into the phone's camera folder,
     * subfolder [selectionSubfolderName] (`Jackpix`), not into `<card>/Selection`.
     */
    val importToPhoneCamera: Boolean = false,
    /** Name of the subfolder swipe right files into: `Selection`, or `Jackpix` for a Casual card import. */
    val selectionSubfolderName: String = PhotoFolders.SELECTION,
    /**
     * Set when a Casual card import has no write access to the phone's camera folder yet: the UI
     * opens the system folder picker at [CameraFolderGrantRequest.initialUri] and answers with
     * [PhoneModeViewModel.onCameraFolderGranted] or [PhoneModeViewModel.onCameraFolderGrantCancelled].
     */
    val cameraFolderGrantRequest: CameraFolderGrantRequest? = null,
) {
    /**
     * What "Delete all" removes and counts: every discovered file of the card, the hidden RAW
     * companions, the filtered-out types and the `.xmp` sidecars included. Not the number of cards
     * in the feed.
     */
    val cardClearTargets: List<ImageItem>
        get() = (allImages + allImages.flatMap { it.sidecars }).distinctBy { it.uri }

    val cardClearFileCount: Int get() = cardClearTargets.size

    /**
     * What swipe right names as its destination: the folder the files land in, which is always the
     * Selection subfolder (`Selection`, the configured name in Pro, `Jackpix` for a Casual import),
     * also inside a custom location. Naming the custom root said "Copied to Pictures" for a file in
     * `Pictures/Selection/` (review JP-UX-1).
     */
    val collectionLabel: String get() = selectionSubfolderName

    /** Delete all is offered once the scan has finished: a count taken mid-scan would be too small. */
    val cardClearReady: Boolean get() = !isDiscovering && !isLoading && allImages.isNotEmpty()

    /**
     * The format filter that hides every photo of the open folder, or null. The landing then says
     * how many it hides and offers Show all, on every visit (F-TOK-086), instead of "no photos".
     */
    val filterHidingEveryPhoto: FileTypeFilter?
        get() = fileTypeFilter.takeIf {
            it != FileTypeFilter.ALL && !isLoading && images.isEmpty() && allImages.isNotEmpty()
        }
}

/**
 * The landing's message for an empty feed: null when the feed shows photos; "N hidden by filter"
 * when the folder has photos the format [filter] hides; else "«folder»: no photos".
 */
internal fun emptyFeedMessage(
    shown: List<ImageItem>,
    all: List<ImageItem>,
    filter: FileTypeFilter,
    folderName: String,
): UiText? = when {
    shown.isNotEmpty() -> null
    all.isNotEmpty() && filter != FileTypeFilter.ALL -> UiText.of(R.string.empty_hidden_by_filter, all.size)
    else -> UiText.of(R.string.empty_folder, folderName.ifEmpty { PhotoFolders.SELECTION })
}

/** Ask the UI to let the user grant the phone's camera folder (the picker opens at [initialUri]). */
data class CameraFolderGrantRequest(
    val initialUri: Uri,
    val id: Long = System.nanoTime(),
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
    val message: UiText,
    val isError: Boolean = false,
    val id: Long = System.nanoTime(),
    /** The snackbar offers Undo, which restores the last deletion ([PhoneModeViewModel.revertDelete]). */
    val offersUndoDelete: Boolean = false,
    /** The snackbar offers Undo, which turns Pro mode back on (the swipes it changed). */
    val offersUndoProOff: Boolean = false,
)

/** Show [message] in the feed's snackbar: the one way every holder of the feed reports. */
internal fun MutableStateFlow<PhoneModeUiState>.showFeedback(message: UiText, isError: Boolean = false) {
    update { it.copy(lastActionFeedback = ActionFeedback(message = message, isError = isError)) }
}

/** A one-time explanation, resolved against the user's current settings. */
data class FirstRunHintUi(
    val hint: FirstRunHint,
    val title: UiText,
    val message: UiText? = null,
    val actionLabel: UiText? = null,
    val onAction: (() -> Unit)? = null,
    val id: Long = System.nanoTime(),
)
