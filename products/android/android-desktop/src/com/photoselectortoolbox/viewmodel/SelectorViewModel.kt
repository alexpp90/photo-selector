package com.photoselectortoolbox.viewmodel

import com.photoselectortoolbox.domain.curation.SelectionDestinationPlan
import com.photoselectortoolbox.domain.curation.OpenedFolder
import com.photoselectortoolbox.domain.curation.DestinationSettings
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.photoselector.core.model.ExifData
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
import com.photoselectortoolbox.data.cache.ScoreDao
import java.io.File
import com.photoselectortoolbox.data.metadata.MetadataWriter
import com.photoselectortoolbox.data.metadata.MetadataWriterImpl
import com.photoselectortoolbox.domain.model.PhotoItem
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.FileOperationResult
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.di.ApplicationScope
import com.photoselectortoolbox.domain.curation.CurationAction
import com.photoselectortoolbox.domain.curation.DeferredDeletion
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.curation.ImageSlot
import com.photoselectortoolbox.domain.curation.OptimisticEdits
import com.photoselectortoolbox.domain.curation.ParkedUndo
import com.photoselectortoolbox.domain.curation.PendingDeleteJournal
import com.photoselectortoolbox.domain.curation.PendingDeletion
import com.photoselectortoolbox.domain.curation.SelectorListState
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import com.photoselectortoolbox.domain.curation.TransferSpace
import com.photoselectortoolbox.domain.curation.UndoParking
import com.photoselectortoolbox.domain.curation.UndoRestore
import com.photoselectortoolbox.domain.curation.UndoPolicy
import com.photoselectortoolbox.domain.curation.UndoableOperation
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.guidance.SelectorGuidance
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.guidance.SelectorHintText
import com.photoselectortoolbox.domain.grouping.ImageGrouper
import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.session.JumpPreview
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoDates
import com.photoselectortoolbox.domain.session.PhotoJump
import com.photoselectortoolbox.domain.session.ProgressiveMerge
import com.photoselectortoolbox.domain.session.ScoreMerge
import com.photoselectortoolbox.domain.session.SelectorReorder
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.domain.session.SelectorWindows
import com.photoselectortoolbox.domain.session.SelectorWork
import com.photoselectortoolbox.domain.session.SelectorWorkQueue
import com.photoselectortoolbox.domain.session.WorkDecision
import com.photoselectortoolbox.domain.session.WorkQueueState
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which of the three frames on screen a control refers to.
 *
 * Named rather than passed as an index because "1" and "the previous frame" are
 * different things: the index shifts every time the user advances, and every
 * bug in this area has come from an index that outlived the mutation it was
 * read before.
 */
enum class SelectorFrame { PREVIOUS, CURRENT, NEXT }

val SourceKind.isCloudProvider: Boolean
    get() = this == SourceKind.CLOUD_OTHER

val SourceKind.providerLabel: String
    get() = when (this) {
        SourceKind.PHONE_LIBRARY -> "local storage"
        SourceKind.REMOVABLE -> "removable storage"
        SourceKind.CAMERA_MTP -> "camera (MTP)"
        SourceKind.CLOUD_OTHER -> "cloud storage"
    }

/**
 * Recovery prompt raised on startup when uncommitted pending deletions from a
 * previous killed session are found in the durable journal (PRP WP-11 / U-05).
 */
data class UncompletedDeletionsPrompt(
    val uris: List<String>,
    val count: Int = uris.size,
    val message: String = SelectorLabels.uncompletedDeletionsMessage(count),
)

data class SelectorUiState(
    val images: List<ImageItem> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    /**
     * Whether folder enumeration is still producing photographs.
     *
     * Distinct from [isLoading], which covers only the wait for the *first*
     * batch. Discovery streams, so the folder keeps growing behind a fully
     * usable screen; this is what puts the `+` on `127 / 842+`.
     */
    val isEnumerating: Boolean = false,
    val isScanRunning: Boolean = false,
    /** Whether Group Similar Series is currently rebuilding the bursts. */
    val isGroupingRunning: Boolean = false,
    /**
     * The pass that has been asked for and is waiting for the running one.
     *
     * Never a disabled control: the sidebar shows this as a queued state with a
     * Cancel, per `ai/memory/palette.md` (2026-07-24).
     */
    val queuedWork: SelectorWork? = null,
    val scanProgress: Float = 0f,
    val scanStatusText: String = "",
    val folderUri: String? = null,
    val folderName: String = "",
    val error: String? = null,
    /**
     * The snackbar line, owned here rather than in the composable so it survives
     * recomposition and always matches the operation [undoOperation] describes.
     */
    val snackbarMessage: String? = null,
    /**
     * Which message this is, counted up on every one shown.
     *
     * The snackbar's timer is keyed on this, not on the text: two consecutive
     * moves produce the same sentence, and a timer keyed on the sentence never
     * restarted for the second one.
     */
    val snackbarSequence: Long = 0L,
    /** How long [snackbarMessage] stays and whether that is a real deadline. */
    val snackbarLifetime: SnackbarLifetime = SnackbarLifetime.NOTICE,
    /**
     * Unified undo stack (newest first, up to [UndoPolicy.MAX_UNDO_HISTORY] = 20).
     *
     * Holds both deferred deletions ([UndoableOperation.RevertPendingDelete]) and
     * completed moves ([UndoableOperation.ReverseMove]) in LIFO order.
     */
    val undoHistory: List<UndoableOperation> = emptyList(),
    /**
     * Durable undo records of folders left behind (finished moves, trashed deletes),
     * restored into [undoHistory] when their folder is selected again.
     */
    val parkedUndo: ParkedUndo = ParkedUndo(),
    /**
     * Whether the currently visible snackbar represents the top of [undoHistory]
     * and should offer an inline UNDO button.
     */
    val snackbarUndoAvailable: Boolean = false,
    /**
     * Recovery prompt when uncommitted deletions from a killed session are found
     * in the durable pending-delete journal.
     */
    val uncompletedDeletionsPrompt: UncompletedDeletionsPrompt? = null,
    val showDeleteConfirmation: Boolean = false,
    val groupingEnabled: Boolean = false,
    val groups: List<List<Int>> = emptyList(),
    val fullscreenButtonsEnabled: Boolean = true,
    /** Whether the fullscreen top bar (name, position, EXIF) stays up instead of fading (persisted). */
    val fullscreenInfoPinned: Boolean = false,
    /** Which of Copy and Move is the emphasised filing control (persisted). */
    val filingAction: FilingAction = FilingAction.DEFAULT,
    /** Whether the one-time on-image nav arrows have already been shown. */
    val hasSeenNavHint: Boolean = false,
    /** Whether the filmstrip along the bottom edge is shown (persisted). */
    val filmstripVisible: Boolean = true,
    /** Whether the readout block beside the current frame is shown (persisted). */
    val detailsVisible: Boolean = true,
    /** Whether Previous and Next carry their value overlay (persisted). */
    val overlayValuesVisible: Boolean = true,
    /**
     * Which frame, if any, is currently filling the image region.
     *
     * Null is the normal three-up state. Maximise is transient view state, not
     * a preference: it is a thing you do to look closer at one frame, and it
     * should not survive a relaunch the way a layout choice would.
     */
    val maximisedFrame: SelectorFrame? = null,
    /** Whether the one-time fullscreen gesture hint has already been dismissed. */
    val hasSeenFullscreenHint: Boolean = false,
    /** Every one-time explanation the photographer has already dismissed. */
    val seenHints: Set<SelectorHint> = emptySet(),
    /**
     * The explanation waiting to be shown, if any.
     *
     * Raised by the action whose *effect* is invisible — filing, deferred
     * deletion, maximise — and cleared when it is dismissed or auto-dismissed.
     * `SelectorGuidance` decides; this field only carries the answer.
     */
    val pendingHint: SelectorHint? = null,
    /**
     * The verb of the filing the [pendingHint] explains, which is not always the
     * configured one: press `M` with Copy configured and the card must say
     * "Moved", not "Copied" (AD-5). Null until something is filed.
     */
    val performedFilingAction: FilingAction? = null,
    /** The destination folder name, so the filing hint can name it (persisted). */
    val selectionFolderName: String = FilingAction.DEFAULT_SELECTION_FOLDER,
    /**
     * The second-pass folder name (`Final`) while the open folder is itself a Selection folder
     * and no custom destination is set (`U-32`, [SelectionDestinationPlan]); null otherwise.
     */
    val secondPassFolderName: String? = null,
    /**
     * The name of the custom destination folder when Copy and Move file into one
     * (`[USER 2026-11-04]`), else null. The confirmation and the filing hint name it.
     */
    val customDestinationName: String? = null,
    /** Whether RAW and JPEG are filed into subfolders (persisted). */
    val sortingEnabled: Boolean = true,
    /** Active rating filter driving the filmstrip and navigation order. */
    val ratingFilter: RatingFilter = RatingFilter.All,
    /** True when the folder's persisted SAF grant was revoked or the folder was moved/deleted. */
    val missingGrantPrompt: Boolean = false,
    /** Storage provider classification for the opened folder (`U-30`). */
    val sourceKind: SourceKind = SourceKind.PHONE_LIBRARY,
    /** Whether a full cloud scan confirmation prompt is pending (`U-30`). */
    val showCloudScanConfirmation: Boolean = false,
    /** Whether images are sorted ascending by sharpness for worst-first reject triage (`U-34`). */
    val sortWorstFirst: Boolean = false,
    /**
     * The persisted order the folder is shown in (`selector_sort_order`). Worst-first
     * triage is a session view over it: leaving triage returns to this order.
     */
    val sortOrder: SelectorSortOrder = SelectorSortOrder.DEFAULT,
    /** Optional sharpness stop-line threshold for worst-first reject triage (`U-34`). */
    val sharpnessStopThreshold: Double? = null,
    /** Session decision log counters (`U-33`, `U-35`). */
    val sessionMovedCount: Int = 0,
    val sessionCopiedCount: Int = 0,
    val sessionDeletedCount: Int = 0,
    val sessionKeptFileNames: Set<String> = emptySet(),
) {
    /** Whether worst-first triage has reached or passed the configured sharpness stop-line (`U-34`). */
    val hasReachedSharpnessThreshold: Boolean
        get() {
            if (!sortWorstFirst) return false
            val threshold = sharpnessStopThreshold ?: return false
            val score = currentImage?.scanResult?.sharpnessScore ?: return false
            return score >= threshold
        }

    /** Total photos kept (moved + copied) in the current culling session (`U-33`, `U-35`). */
    val sessionKeptCount: Int
        get() = sessionMovedCount + sessionCopiedCount
    /**
     * What, if anything, the snackbar's UNDO would actually reverse.
     *
     * Null means no UNDO is drawn on the snackbar (e.g. for Copy, Scan complete,
     * Error, or after the snackbar is dismissed), while [undoHistory] remains
     * available to `Ctrl+Z`.
     */
    val undoOperation: UndoableOperation?
        get() = if (snackbarUndoAvailable) undoHistory.firstOrNull() else null

    /**
     * This state showing a new snackbar message.
     *
     * The one way a message is raised, so the three facts that belong to it —
     * the sequence number its timer is keyed on, how long it stays, and whether
     * UNDO is offered — are always decided together (REQ-AND-LAYOUT.21).
     */
    fun withSnackbar(
        message: String,
        lifetime: SnackbarLifetime,
        undoAvailable: Boolean = false,
    ): SelectorUiState = copy(
        snackbarMessage = message,
        snackbarSequence = snackbarSequence + 1,
        snackbarLifetime = lifetime,
        snackbarUndoAvailable = undoAvailable,
    )

    /** The most recent deletion removed from the list but not yet committed to disk. */
    val pendingDeletion: PendingDeletion?
        get() = undoHistory.filterIsInstance<UndoableOperation.RevertPendingDelete>().firstOrNull()?.pending

    /** All deletions currently held in [undoHistory] and not yet committed to disk. */
    val pendingDeletions: List<PendingDeletion>
        get() = undoHistory.filterIsInstance<UndoableOperation.RevertPendingDelete>().map { it.pending }

    /** The active images adhering to the rating filter. */
    val filteredImages: List<ImageItem>
        get() = if (ratingFilter is RatingFilter.All) {
            images
        } else {
            images.filter { ratingFilter.matches(it.rating) }
        }

    /** Position within filtered collection (1-based), or 0 if none. */
    val filteredPosition: Int
        get() {
            val curr = currentImage ?: return 0
            val idx = filteredImages.indexOfFirst { it.uri == curr.uri }
            return if (idx >= 0) idx + 1 else 0
        }

    /** [filteredPosition] as a 0-based index into [filteredImages] (what list widgets take), or 0 if none. */
    val filteredIndex: Int
        get() = (filteredPosition - 1).coerceAtLeast(0)

    /** The frame being judged, or null when no folder is loaded. */
    val currentImage: ImageItem?
        get() = images.getOrNull(currentIndex)

    /** The frame before the current one, or null at the start of the folder. */
    val previousImage: ImageItem?
        get() = images.getOrNull(currentIndex - 1)

    /** The frame after the current one, or null at the end of the folder. */
    val nextImage: ImageItem?
        get() = images.getOrNull(currentIndex + 1)

    /** Human-readable position, 1-based, as shown in the app bar and rails. */
    val position: Int
        get() = if (images.isEmpty()) 0 else currentIndex + 1

    /** True once at least one frame carries scores, which is what reveals the legend. */
    val hasAnyScores: Boolean
        get() = images.any { it.scanResult != null }

    /** The list and the index as one value, for the pure `domain/curation` logic. */
    val listState: SelectorListState
        get() = SelectorListState(images, currentIndex)

    /**
     * Whether the coach-mark guide opens by itself, this being a first launch.
     *
     * Derived rather than stored: it is a function of the persisted hint set and
     * whether there is anything on screen to label, and a second copy of it
     * would be one more thing to keep in step with *Reset guidance*.
     */
    val showIntroTour: Boolean
        get() = SelectorGuidance.shouldShowTour(seenHints, images.isNotEmpty())

    /**
     * The pending explanation with its wording resolved from the current
     * settings, ready for the card to draw.
     *
     * Built here rather than in the composable so no default verb, folder name
     * or key can be hard-coded next to a `Text`.
     */
    /**
     * The folder a Copy or Move from here lands in, by name: the second-pass folder on a second
     * pass, else the Selection folder. Every confirmation, hint and flash names this one.
     */
    val filingFolderName: String
        get() = secondPassFolderName?.takeIf { customDestinationName == null } ?: selectionFolderName

    val pendingHintText: SelectorHintUi?
        get() = pendingHint?.let { hint ->
            // The filing card explains what *just happened*, so it takes the verb
            // that was performed; every other card is not about a verb.
            val verb = if (hint == SelectorHint.FILING) {
                performedFilingAction ?: filingAction
            } else {
                filingAction
            }
            SelectorHintUi(
                hint = hint,
                title = SelectorHintText.title(hint, verb, filingFolderName, customDestinationName),
                message = SelectorHintText.message(
                    hint = hint,
                    filingAction = verb,
                    selectionFolderName = filingFolderName,
                    sortingEnabled = sortingEnabled,
                    customFolderName = customDestinationName,
                ),
            )
        }
}

/** One resolved explanation: which hint it is, and the words for it. */
data class SelectorHintUi(
    val hint: SelectorHint,
    val title: String,
    val message: String,
)

@HiltViewModel
class SelectorViewModel @Inject constructor(
    private val imageRepository: ImageRepository,
    private val scanImagesUseCase: ScanImagesUseCase,
    private val cacheRepository: CacheRepository,
    private val settingsRepository: SettingsRepository,
    private val scoreDao: ScoreDao,
    private val metadataWriter: MetadataWriter = MetadataWriterImpl(Dispatchers.IO),
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SelectorUiState())
    val uiState: StateFlow<SelectorUiState> = _uiState.asStateFlow()

    private val _ratingFilter = MutableStateFlow<RatingFilter>(RatingFilter.All)
    val ratingFilter: StateFlow<RatingFilter> = _ratingFilter.asStateFlow()

    /** Dispatched to for Room / IO work; overridable in tests to keep virtual time intact. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Quiet period before coalesced rating changes are written back to disk; overridable in tests. */
    internal var ratingWriteDebounceMs: Long = RATING_WRITE_DEBOUNCE_MILLIS

    /**
     * Serialises every write to the pending-delete journal (FD-D-20). The add of a delete and the
     * remove of its commit or undo ran in separate launches on a multi-threaded scope, so a quick
     * delete-then-leave could remove before it added and leave already-deleted URIs in the journal.
     * An add takes the lock before [deleteCurrentImage] returns ([CoroutineStart.UNDISPATCHED]), so
     * every later remove waits for it.
     */
    private val journalMutex = Mutex()

    /**
     * Journal [files] with the identity each has now, read from the provider (finding 2026-11-05):
     * a later "Delete now" deletes only a file that still has it. The listing's size and date are
     * the fallback when the provider cannot be asked; with neither, the entry is unverifiable.
     */
    private fun journalAdd(files: List<CompanionFile>) {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            journalMutex.withLock {
                val identities = coroutineScope {
                    files.map { file ->
                        async {
                            file.uri to (readIdentity(file.uri) ?: FileIdentity(file.fileSize, file.lastModified))
                        }
                    }.awaitAll()
                }.filter { (_, identity) -> identity.isKnown }.toMap()
                settingsRepository.addPendingDeletes(files.map { it.uri }, identities)
            }
        }
    }

    /** The identity of [uri] now, or null when it cannot be read (logged; the caller decides). */
    private suspend fun readIdentity(uri: String): FileIdentity? = try {
        imageRepository.documentIdentity(context, Uri.parse(uri))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("SelectorViewModel", "Could not read the identity of $uri", e)
        null
    }

    private suspend fun journalRemove(uris: List<String>) {
        journalMutex.withLock { settingsRepository.removePendingDeletes(uris) }
    }

    /** In-flight debounced disk write-back jobs keyed by photo URI, running on [appScope]. */
    private val ratingWriteJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // ── Session state ────────────────────────────────────────────────────
    //
    // Everything below belongs to "the folder currently open" and is cleared in
    // exactly one place ([resetSession]). A stale `publishedUris` makes a new
    // folder look empty, and scattered resets are how one gets forgotten — the
    // same trap recorded against Jackpix's feed loader in
    // `ai/memory/code_health.md`.

    private var discoveryJob: Job? = null
    private var visibleDimensionsJob: Job? = null
    private var backfillDimensionsJob: Job? = null
    private var scoreRestoreJob: Job? = null
    private var scanJob: Job? = null
    private var groupingJob: Job? = null

    /**
     * Session-scoped set of folder URIs for which the photographer has already
     * confirmed permanent SAF deletion in this session (U-05).
     */
    private val confirmedDeleteFolders = mutableSetOf<String>()

    /**
     * Every URI ever published for this folder, including ones the photographer
     * has since moved or deleted. Discovery emits cumulatively, so "not in the
     * list" must never be read as "newly found".
     */
    private val publishedUris = mutableSetOf<String>()

    /**
     * URIs whose header has been read, so no URI costs a second open —
     * unreadable ones included, because a negative result is still an answer.
     *
     * Concurrent because the visible-range pass and the background backfill
     * touch it from different dispatchers.
     */
    private val dimensionsResolved: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Coil prefetch requests in flight, keyed by URI so they can be superseded. */
    private val prefetchRequests = mutableMapOf<String, Disposable>()

    /**
     * Whether the photographer has done anything since this folder opened:
     * navigated, rated, filed, deleted or undone. Decides where the one re-order
     * at enumeration settle lands them ([SelectorReorder.atSettle]). Reset in
     * [resetSession].
     */
    private var photographerHasActed = false

    private var workQueue = WorkQueueState()
    private var pendingGroupingLevel: GroupingLevel = GroupingLevel.TIME_FILENAME

    private val imageGrouper = ImageGrouper(context, ioDispatcher)

    init {
        // While the undo history holds entries, the folder they operate on keeps its SAF grant even
        // if the user opens another folder (finding D-10).
        viewModelScope.launch {
            _uiState
                .map { state -> UndoParking.foldersToPin(state.folderUri, state.undoHistory, state.parkedUndo) }
                .distinctUntilChanged()
                .collect { folders -> settingsRepository.pinUndoHistoryFolders(folders) }
        }

        viewModelScope.launch {
            settingsRepository.pendingDeleteJournal.take(1).collect { recovered ->
                if (recovered.isNotEmpty()) {
                    val sorted = recovered.toList()
                    _uiState.update {
                        it.copy(
                            uncompletedDeletionsPrompt = UncompletedDeletionsPrompt(
                                uris = sorted,
                                count = sorted.size,
                            )
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(
                settingsRepository.groupingEnabled,
                settingsRepository.groupingLevel
            ) { enabled, level ->
                Pair(enabled, level)
            }.collect { (enabled, level) ->
                _uiState.update { it.copy(groupingEnabled = enabled) }
                if (_uiState.value.images.isEmpty()) return@collect
                if (enabled) {
                    requestGrouping(level)
                } else {
                    cancelGroupingWork()
                    _uiState.update { it.copy(groups = emptyList()) }
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.selectorSortOrder.collect { order -> onPersistedSortOrder(order) }
        }

        viewModelScope.launch {
            settingsRepository.fullscreenButtonsEnabled.collect { enabled ->
                _uiState.update { it.copy(fullscreenButtonsEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            settingsRepository.fullscreenInfoPinned.collect { pinned ->
                _uiState.update { it.copy(fullscreenInfoPinned = pinned) }
            }
        }

        viewModelScope.launch {
            settingsRepository.filingAction.collect { action ->
                _uiState.update { it.copy(filingAction = action) }
            }
        }

        viewModelScope.launch {
            settingsRepository.overlayValuesVisible.collect { visible ->
                _uiState.update { it.copy(overlayValuesVisible = visible) }
            }
        }

        viewModelScope.launch {
            settingsRepository.filmstripVisible.collect { visible ->
                _uiState.update { it.copy(filmstripVisible = visible) }
            }
        }

        viewModelScope.launch {
            settingsRepository.detailsVisible.collect { visible ->
                _uiState.update { it.copy(detailsVisible = visible) }
            }
        }

        viewModelScope.launch {
            settingsRepository.hasSeenFullscreenGestureHint.collect { seen ->
                _uiState.update { it.copy(hasSeenFullscreenHint = seen) }
            }
        }

        viewModelScope.launch {
            settingsRepository.hasSeenNavHint.collect { seen ->
                _uiState.update { it.copy(hasSeenNavHint = seen) }
            }
        }

        // A card whose hint has just been recorded as seen goes with it, and
        // "Reset guidance" arrives through the same flow — so the reset brings
        // the explanations back without any second code path.
        viewModelScope.launch {
            settingsRepository.seenHints.collect { seen ->
                _uiState.update {
                    it.copy(
                        seenHints = seen,
                        pendingHint = SelectorGuidance.retainPending(it.pendingHint, seen),
                    )
                }
            }
        }

        // Both are read by the filing explanation, which names the folder the
        // photograph is actually in rather than the default one.
        viewModelScope.launch {
            settingsRepository.selectionFolderName.collect { name ->
                _uiState.update { it.copy(selectionFolderName = name) }
            }
        }

        viewModelScope.launch {
            settingsRepository.sortingEnabled.collect { enabled ->
                _uiState.update { it.copy(sortingEnabled = enabled) }
            }
        }

        // The custom destination folder, by name, so a filing confirmation can say where
        // the photo went. A lookup that fails leaves the name out (the plain sentence).
        viewModelScope.launch {
            settingsRepository.customSelectionDestinationUri.collectLatest { uri ->
                val name = uri?.takeIf { it.isNotBlank() }?.let { customFolderName(it) }
                _uiState.update { it.copy(customDestinationName = name) }
            }
        }

        viewModelScope.launch {
            settingsRepository.reconcilePersistedGrants()
            settingsRepository.lastFolderUri.collect { uri ->
                if (uri != null && _uiState.value.folderUri == null) {
                    selectFolder(Uri.parse(uri))
                }
            }
        }
    }

    /** The custom destination's own name (its display name, else the last segment of its path), or null. */
    private suspend fun customFolderName(uri: String): String? = try {
        val folder = imageRepository.describeFolder(context, Uri.parse(uri))
        folder.name?.takeIf { it.isNotBlank() } ?: folder.displayPath.lastOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("SelectorViewModel", "Could not name the custom destination folder", e)
        null
    }

    // ── Folder selection and progressive loading ─────────────────────────

    /**
     * Reset everything that belongs to the folder being left.
     *
     * One function, called from every path that changes folder, because the
     * failure mode of forgetting one field is silent: a surviving
     * [publishedUris] de-duplicates the *new* folder's first batch against the
     * old folder's URIs and the screen stays empty with no error anywhere.
     */
    private fun resetSession() {
        commitAllPendingDeletions()
        discoveryJob?.cancel()
        visibleDimensionsJob?.cancel()
        backfillDimensionsJob?.cancel()
        scoreRestoreJob?.cancel()
        scanJob?.cancel()
        groupingJob?.cancel()
        discoveryJob = null
        visibleDimensionsJob = null
        backfillDimensionsJob = null
        scoreRestoreJob = null
        scanJob = null
        groupingJob = null
        publishedUris.clear()
        dimensionsResolved.clear()
        cancelAllPrefetch()
        loadedExifCache.clear()
        workQueue = SelectorWorkQueue.reset()
        photographerHasActed = false
        _ratingFilter.value = RatingFilter.All
        _uiState.update {
            parkUndoHistory(it).copy(
                images = emptyList(),
                currentIndex = 0,
                groups = emptyList(),
                isScanRunning = false,
                isGroupingRunning = false,
                queuedWork = null,
                scanProgress = 0f,
                scanStatusText = "",
                snackbarMessage = null,
                snackbarUndoAvailable = false,
                ratingFilter = RatingFilter.All,
                missingGrantPrompt = false,
                sourceKind = SourceKind.PHONE_LIBRARY,
                showCloudScanConfirmation = false,
                sortWorstFirst = false,
                sessionMovedCount = 0,
                sessionCopiedCount = 0,
                sessionDeletedCount = 0,
                sessionKeptFileNames = emptySet(),
            )
        }
    }

    /**
     * Leaving [state]'s folder: its durable undo records move to [SelectorUiState.parkedUndo]
     * (pending deletions were committed by [commitAllPendingDeletions] and are dropped).
     */
    private fun parkUndoHistory(state: SelectorUiState): SelectorUiState {
        val folder = state.folderUri
        val parked = if (folder != null) state.parkedUndo.park(folder, state.undoHistory) else state.parkedUndo
        return state.copy(undoHistory = emptyList(), parkedUndo = parked)
    }

    fun selectFolder(uri: Uri) {
        resetSession()
        // Synchronous, in one update: from here on a late transfer result compares against the
        // folder that is really open, and the returning folder's records are live again.
        _uiState.update {
            val (restored, remaining) = it.parkedUndo.take(uri.toString())
            it.copy(folderUri = uri.toString(), undoHistory = restored, parkedUndo = remaining)
        }
        val classifiedKind = runCatching {
            val auth = uri.authority
            if (auth.isNullOrBlank() || auth == "test") SourceKind.PHONE_LIBRARY
            else SourceKindClassifier.classify(uri)
        }.getOrDefault(SourceKind.PHONE_LIBRARY)
        discoveryJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isEnumerating = true,
                    error = null,
                    missingGrantPrompt = false,
                    sourceKind = classifiedKind,
                )
            }

            // Claiming the folder — persisting the grant and confirming it is
            // still there — belongs to the repository, not here. See
            // [ImageRepository.openFolder].
            val folderName = imageRepository.openFolder(context, uri)
            if (folderName == null) {
                _uiState.update {
                    parkUndoHistory(it).copy(
                        isLoading = false,
                        isEnumerating = false,
                        folderUri = null,
                        folderName = "",
                        missingGrantPrompt = true,
                    )
                }
                reportError("Failed to load folder: permission revoked or directory deleted.")
                settingsRepository.setLastFolderUri(null)
                return@launch
            }

            _uiState.update { it.copy(folderName = folderName, missingGrantPrompt = false, secondPassFolderName = null) }
            resolveSecondPass(uri)

            settingsRepository.setLastFolderUri(uri.toString())
            recoverInterruptedRatingWrites(uri)

            collectDiscovery(uri, "images") { e ->
                if (e is SecurityException) {
                    _uiState.update {
                        parkUndoHistory(it).copy(
                            folderUri = null,
                            folderName = "",
                            missingGrantPrompt = true,
                        )
                    }
                    settingsRepository.setLastFolderUri(null)
                }
            }
        }
    }

    /**
     * Whether [folderUri] is itself a Selection folder (by name or marker), so a Copy or Move from
     * it lands in the second-pass folder (`Final`) and the confirmation must say so; the same
     * predicate the file operation and the Settings preview use ([SelectionDestinationPlan]).
     * A lookup that fails leaves the Selection name (the sentence the transfer used before).
     */
    private fun resolveSecondPass(folderUri: Uri) {
        val folder = folderUri.toString()
        viewModelScope.launch {
            val name = try {
                val described = imageRepository.describeFolder(context, folderUri)
                val settings = DestinationSettings(
                    selectionFolderName = settingsRepository.selectionFolderName.first(),
                    secondPassFolderName = settingsRepository.secondarySelectionFolderName.first(),
                )
                val opened = OpenedFolder(uri = folder, name = described.name, hasSelectionMarker = described.hasSelectionMarker)
                settings.secondPassName.takeIf { SelectionDestinationPlan.isSelectionFolder(opened, settings) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SelectorViewModel", "Could not tell whether $folder is a Selection folder", e)
                null
            }
            _uiState.update { if (it.folderUri != folder) it else it.copy(secondPassFolderName = name) }
        }
    }

    /**
     * Resolves the backups interrupted JPEG rating writes left in [folderUri] (FD-D-11,
     * [MetadataWriter.recoverInterruptedWrites]) beside the discovery, on [appScope] so a restore
     * that has started is not cut off by a folder switch. The result is told only while the folder
     * is still open.
     */
    private fun recoverInterruptedRatingWrites(folderUri: Uri) {
        val folder = folderUri.toString()
        appScope.launch(ioDispatcher) {
            val recovery = try {
                metadataWriter.recoverInterruptedWrites(context, folderUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SelectorViewModel", "Rating backup recovery failed for $folderUri", e)
                return@launch
            }
            val message = SelectorLabels.ratingRecoveryMessage(
                restored = recovery.restored,
                kept = recovery.kept.map { it.photoName to it.backupName },
            ) ?: return@launch
            val lifetime = if (recovery.kept.isEmpty()) SnackbarLifetime.NOTICE else SnackbarLifetime.ERROR
            _uiState.update { if (it.folderUri == folder) it.withSnackbar(message, lifetime) else it }
        }
    }

    /**
     * The shared tail of folder selection: stream, merge append-only, publish.
     *
     * Emissions are cumulative, so each one is merged against [publishedUris]
     * rather than replacing the list. Three things deliberately do **not** run
     * per batch:
     *
     * - `currentIndex = 0`, which would drag the photographer back to frame 1
     *   every time another 250 files finished enumerating.
     * - grouping, which rebuilds the candidate list they are looking at.
     * - the cached-score restore, which is a per-image cache read over the whole
     *   list; it is debounced onto the settled list instead.
     */
    private suspend fun collectDiscovery(
        folderUri: Uri,
        errorLabel: String,
        onError: suspend (Throwable) -> Unit = {},
    ) {
        try {
            imageRepository.discoverImages(folderUri).collect { batch ->
                publishBatch(batch)
            }
            settleOrder()
            // Enumeration has settled: restore scores over the complete list and
            // pick up the tail of the dimensions nobody has asked for yet.
            scheduleScoreRestore(immediate = true)
            startDimensionBackfill(force = true)
            if (_uiState.value.groupingEnabled && _uiState.value.images.isNotEmpty()) {
                requestGrouping(settingsRepository.groupingLevel.first())
            }
        } catch (e: CancellationException) {
            // A newer discovery owns the state now — do not touch it.
            throw e
        } catch (e: Exception) {
            Log.e("SelectorViewModel", "Failed to discover images in $folderUri", e)
            _uiState.update { it.copy(isLoading = false, isEnumerating = false) }
            reportError("Could not load $errorLabel")
            onError(e)
        }
    }

    /** Merge one cumulative batch into the visible list, append-only. */
    private fun publishBatch(batch: List<ImageItem>) {
        val isFirstBatch = publishedUris.isEmpty()
        // Each fresh batch is ordered before it is appended; only the settle
        // (see [settleOrder]) ever orders the list as a whole.
        val fresh = ProgressiveMerge.newItems(batch, publishedUris)
            .let { items ->
                val state = _uiState.value
                items.sortedWith(state.activeComparator())
            }
        if (fresh.isEmpty() && !isFirstBatch) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        publishedUris += fresh.map { it.uri }

        // Merged *inside* the update rather than from a snapshot read before it:
        // the dimension backfill publishes from `Dispatchers.IO`, so a snapshot
        // taken out here could be stale by the time it is written back, silently
        // dropping the aspect ratios that had just arrived. `fresh` is already
        // de-duplicated, so the append itself is pure and safe to retry.
        _uiState.update { current ->
            val merged = ProgressiveMerge.append(
                current = current.images,
                currentIndex = current.currentIndex,
                batch = fresh,
                published = emptySet(),
                isFirstBatch = isFirstBatch,
            )
            current.copy(
                images = merged.images,
                currentIndex = merged.currentIndex,
                isLoading = false,
            )
        }

        if (isFirstBatch) {
            loadMetadataForActiveRange()
            resolveVisibleDimensions()
            prefetchNeighbours()
        }
        startDimensionBackfill()
        scheduleScoreRestore()
    }

    // ── Sort order ───────────────────────────────────────────────────────

    /**
     * The one re-order of a folder open, when enumeration settles.
     *
     * "Newest" needs the whole folder, which only exists once discovery has
     * finished, so the batches appended in the active order are put in order as a
     * whole — once. An untouched session on frame 1 lands on the new frame 1;
     * anyone else keeps the frame on screen by URI ([SelectorReorder.atSettle]).
     * Burst groups are remapped in the same update, and the regrouping that follows
     * runs over the settled list. Worst-first triage is left alone: it is a view the
     * photographer chose, and leaving it returns to the persisted order.
     */
    private fun settleOrder() {
        val before = _uiState.value.images
        _uiState.update { state ->
            val settled = state.copy(isLoading = false, isEnumerating = false)
            if (state.sortWorstFirst) return@update settled
            val reordered = SelectorReorder.atSettle(state.listState, state.sortOrder, photographerHasActed)
            if (reordered.changed) settled.withList(reordered.state) else settled
        }
        if (_uiState.value.images !== before) refreshAfterListChange()
    }

    /** The order picked in the overflow menu: leaves worst-first, keeps the frame on screen, persists. */
    fun setSortOrder(order: SelectorSortOrder) {
        _uiState.update { state ->
            val reordered = SelectorReorder.keepingFrame(state.listState, order)
            state.withList(reordered.state).copy(sortOrder = order, sortWorstFirst = false)
        }
        refreshAfterListChange()
        viewModelScope.launch { settingsRepository.setSelectorSortOrder(order) }
    }

    /**
     * The persisted order as the repository reports it. Equal to what the state
     * already holds after [setSortOrder]; different on first load, when it
     * re-orders the list keeping the frame on screen.
     */
    private fun onPersistedSortOrder(order: SelectorSortOrder) {
        if (_uiState.value.sortOrder == order) return
        val before = _uiState.value.images
        _uiState.update { state ->
            if (state.sortWorstFirst) return@update state.copy(sortOrder = order)
            val reordered = SelectorReorder.keepingFrame(state.listState, order)
            state.withList(reordered.state).copy(sortOrder = order)
        }
        if (_uiState.value.images !== before) refreshAfterListChange()
    }

    /**
     * Put a list that has just had slots restored back in the active order,
     * keeping the frame on screen by URI. Restores go to their *recorded* index,
     * which is a position in the order at the time of removal — after a re-order
     * that is no longer where the photo belongs.
     *
     * While discovery is still running the list is only ordered per batch, so it
     * is left as restored: the settle orders it once.
     */
    private fun SelectorUiState.withRestored(restored: SelectorListState): SelectorUiState {
        val placed = if (isEnumerating) restored else SelectorReorder.keepingFrame(restored, activeComparator()).state
        return withList(placed)
    }

    /** The order the list is currently in: worst-first while triage is on, else the persisted order. */
    private fun SelectorUiState.activeComparator(): Comparator<ImageItem> =
        if (sortWorstFirst) WORST_FIRST else sortOrder.comparator

    /**
     * [next] as the list and index, with burst groups (index lists) carried over by
     * URI. Every update that removes, inserts or re-orders frames goes through here:
     * a stale index list points at a different photo.
     */
    private fun SelectorUiState.withList(next: SelectorListState): SelectorUiState = copy(
        images = next.images,
        currentIndex = next.currentIndex,
        groups = if (next.images === images) groups else SelectorReorder.remapGroups(groups, images, next.images),
    )

    /** What a changed list or frame implies, without counting as the photographer acting. */
    private fun refreshAfterListChange() {
        loadMetadataForActiveRange()
        resolveVisibleDimensions()
        prefetchNeighbours()
    }

    // ── Navigation & Rating Filtering ────────────────────────────────────

    /**
     * Go to the photo the "Go to photo" input names (number or file name), in the
     * list as it is ordered now. A photo the rating filter hides is shown anyway —
     * the photographer asked for it — by resetting the filter to All.
     *
     * Returns what the input resolved to, so the dialog can say why it did not move.
     */
    fun jumpToPhoto(input: String): JumpResolution {
        val state = _uiState.value
        val zone = zoneId()
        val resolution = PhotoJump.resolve(input, state.images, state.ratingFilter, zone, LocalDate.now(clock.withZone(zone)))
        goTo(resolution)
        return resolution
    }

    /**
     * Go to the first photo of [date] in the list as it is ordered now — the date
     * picker's entry. A day without photos lands on the nearest day that has one
     * ([JumpResolution.Resolved.date] says which); null when no photo has a known time
     * (nothing moves). A photo the rating filter hides is shown, resetting the filter.
     */
    fun jumpToDate(date: LocalDate): JumpResolution.Resolved? {
        val state = _uiState.value
        val resolution = PhotoJump.resolveDate(date, state.images, state.ratingFilter, zoneId())
        resolution?.let(::goTo)
        return resolution
    }

    /**
     * What "Go to photo" would do with [input], without doing it: the resolution plus
     * the target's file name and time, for the dialog's supporting line. Uses the same
     * zone and today as [jumpToPhoto], so the preview and the jump agree.
     */
    fun previewJump(input: String): JumpPreview {
        val state = _uiState.value
        val zone = zoneId()
        return PhotoJump.preview(input, state.images, state.ratingFilter, zone, LocalDate.now(clock.withZone(zone)))
    }

    /**
     * The days the list has photos on, for a date picker limited to them
     * (`SelectableDates`). Recomputed only when the list changes (cached by identity).
     */
    fun photoDates(): PhotoDates {
        val images = _uiState.value.images
        val zone = zoneId()
        photoDatesCache?.let { (cachedImages, cachedZone, dates) ->
            if (cachedImages === images && cachedZone == zone) return dates
        }
        return PhotoDates.of(images, zone).also { photoDatesCache = Triple(images, zone, it) }
    }

    /**
     * The month the date picker opens on: the day of the photo on screen, else the last day
     * with photos (the newest), else null when no photo has a known time.
     */
    fun pickerStartDay(): LocalDate? {
        val state = _uiState.value
        val zone = zoneId()
        return state.currentImage?.let { PhotoJump.dayOf(it, zone) } ?: photoDates().last
    }

    private var photoDatesCache: Triple<List<ImageItem>, ZoneId, PhotoDates>? = null

    /**
     * The clock "today" comes from (a `12.10.` without a year, a two-digit year) and the
     * zone photo times are read in — the device's, read at each jump so a zone change
     * takes effect. Overridable in tests.
     */
    internal var clock: Clock = Clock.systemDefaultZone()
    internal var zoneId: () -> ZoneId = { ZoneId.systemDefault() }

    private fun goTo(resolution: JumpResolution) {
        if (resolution is JumpResolution.Resolved) {
            if (resolution.hiddenByFilter) setRatingFilter(RatingFilter.All)
            navigateToImage(resolution.index)
        }
    }

    fun setRatingFilter(filter: RatingFilter) {
        photographerHasActed = true
        _ratingFilter.value = filter
        _uiState.update { it.copy(ratingFilter = filter) }
        adjustCurrentIndexForFilter(filter)
    }

    private fun adjustCurrentIndexForFilter(filter: RatingFilter) {
        val state = _uiState.value
        val current = state.currentImage ?: return
        if (filter.matches(current.rating)) return

        val matchingIndices = state.images.indices.filter { filter.matches(state.images[it].rating) }
        if (matchingIndices.isNotEmpty()) {
            val closest = matchingIndices.minByOrNull { kotlin.math.abs(it - state.currentIndex) } ?: matchingIndices.first()
            _uiState.update { it.copy(currentIndex = closest) }
            onFrameChanged()
        }
    }

    fun navigateToImage(index: Int) {
        val images = _uiState.value.images
        if (index in images.indices) {
            _uiState.update { it.copy(currentIndex = index) }
            onFrameChanged()
        }
    }

    fun navigateNext() {
        val state = _uiState.value
        if (state.ratingFilter is RatingFilter.All) {
            if (state.currentIndex < state.images.size - 1) {
                _uiState.update { it.copy(currentIndex = state.currentIndex + 1) }
                onFrameChanged()
            }
        } else {
            val nextIndex = state.images.indices
                .filter { it > state.currentIndex && state.ratingFilter.matches(state.images[it].rating) }
                .firstOrNull()
            if (nextIndex != null) {
                _uiState.update { it.copy(currentIndex = nextIndex) }
                onFrameChanged()
            }
        }
    }

    fun navigatePrevious() {
        val state = _uiState.value
        if (state.ratingFilter is RatingFilter.All) {
            if (state.currentIndex > 0) {
                _uiState.update { it.copy(currentIndex = state.currentIndex - 1) }
                onFrameChanged()
            }
        } else {
            val prevIndex = state.images.indices
                .filter { it < state.currentIndex && state.ratingFilter.matches(state.images[it].rating) }
                .lastOrNull()
            if (prevIndex != null) {
                _uiState.update { it.copy(currentIndex = prevIndex) }
                onFrameChanged()
            }
        }
    }

    /**
     * Sets user star rating (1..5, -1 for reject, 0 for unrated).
     *
     * 1. Updates in-memory rating state immediately for reactive UI feedback.
     * 2. Persists to Room (`user_ratings` + `scores_cache`) on [appScope] via [CacheRepository.updateRating].
     * 3. Debounces per-URI disk write-back on [appScope] using [MetadataWriter] so rapid
     *    key presses (`1 → 2 → 3 → 4 → 5`) coalesce into a single file write that survives
     *    leaving the folder or clearing the ViewModel.
     */
    fun setRating(photo: ImageItem, rating: Int) {
        photographerHasActed = true
        _uiState.update { state ->
            val updatedImages = state.images.map { img ->
                if (img.uri == photo.uri) img.copy(rating = rating) else img
            }
            state.copy(images = updatedImages)
        }

        appScope.launch(ioDispatcher) {
            try {
                cacheRepository.updateRating(photo.uri, rating)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectorViewModel", "Failed to persist rating to Room for ${photo.uri}", e)
            }
        }

        ratingWriteJobs.remove(photo.uri)?.cancel()
        val writeJob = appScope.launch(ioDispatcher) {
            try {
                if (ratingWriteDebounceMs > 0L) {
                    delay(ratingWriteDebounceMs)
                }
                val uri = Uri.parse(photo.uri)
                val file = when {
                    uri.scheme == "file" -> uri.path?.let { File(it) } ?: File(photo.uri)
                    uri.scheme == null -> File(photo.uri)
                    uri.path != null && File(uri.path!!).exists() -> File(uri.path!!)
                    else -> File(photo.uri)
                }
                var res = metadataWriter.writeRating(file, rating)
                if (res.isFailure && uri.scheme == "content") {
                    res = metadataWriter.writeRating(context, uri, rating)
                }
                if (res.isFailure) {
                    // The rating is kept in the app (Room); say that the file did not get it.
                    val reason = res.exceptionOrNull()?.message
                    _uiState.update {
                        it.withSnackbar(
                            SelectorLabels.ratingNotWrittenMessage(photo.fileName, reason),
                            SnackbarLifetime.NOTICE,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectorViewModel", "Failed to write rating metadata to disk for ${photo.uri}", e)
            }
        }
        ratingWriteJobs[photo.uri] = writeJob
        writeJob.invokeOnCompletion {
            ratingWriteJobs.remove(photo.uri, writeJob)
        }
    }

    fun setRatingForCurrent(rating: Int) {
        val current = _uiState.value.currentImage ?: return
        setRating(current, rating)
    }

    fun setRatingForMaximised(rating: Int) {
        val state = _uiState.value
        val target = when (state.maximisedFrame) {
            SelectorFrame.PREVIOUS -> state.previousImage
            SelectorFrame.NEXT -> state.nextImage
            else -> state.currentImage
        } ?: return
        setRating(target, rating)
    }

    /** Everything the frame under judgement changing implies. */
    private fun onFrameChanged() {
        photographerHasActed = true
        refreshAfterListChange()
    }

    // ── Scanning, grouping, and the queue between them ───────────────────

    fun startScan(forceCloudScan: Boolean = false) {
        val state = _uiState.value
        if (state.images.isEmpty()) return
        if (!forceCloudScan && state.sourceKind.isCloudProvider) {
            _uiState.update { it.copy(showCloudScanConfirmation = true) }
            return
        }
        applyWorkDecision(SelectorWorkQueue.request(workQueue, SelectorWork.SCAN))
    }

    fun confirmCloudScan() {
        _uiState.update { it.copy(showCloudScanConfirmation = false) }
        startScan(forceCloudScan = true)
    }

    fun dismissCloudScanConfirmation() {
        _uiState.update { it.copy(showCloudScanConfirmation = false) }
    }

    /**
     * Toggle Worst-First Reject Triage (`U-34`): sorts photos ascending by
     * sharpness score (lowest sharpness first, unscored photos at the end) so
     * obvious blurry/accidental shots can be deleted rapidly up to the stop-line.
     */
    fun toggleSortWorstFirst() {
        _uiState.update { state ->
            if (!state.sortWorstFirst) {
                // Triage starts at the blurriest frame.
                state.withList(SelectorListState(state.images.sortedWith(WORST_FIRST), currentIndex = 0))
                    .copy(sortWorstFirst = true)
            } else {
                // Leaving it returns to the persisted order, on the frame on screen.
                val back = SelectorReorder.keepingFrame(state.listState, state.sortOrder)
                state.withList(back.state).copy(sortWorstFirst = false)
            }
        }
        onFrameChanged()
    }

    fun setSharpnessStopThreshold(threshold: Double?) {
        _uiState.update { it.copy(sharpnessStopThreshold = threshold) }
    }

    private fun requestGrouping(level: GroupingLevel) {
        pendingGroupingLevel = level
        applyWorkDecision(SelectorWorkQueue.request(workQueue, SelectorWork.GROUPING))
    }

    private fun applyWorkDecision(outcome: Pair<WorkQueueState, WorkDecision>) {
        workQueue = outcome.first
        publishWorkState()
        when (val decision = outcome.second) {
            is WorkDecision.Start -> when (decision.work) {
                SelectorWork.SCAN -> runScan()
                SelectorWork.GROUPING -> runGrouping(pendingGroupingLevel)
            }
            is WorkDecision.Queue, WorkDecision.Idle -> Unit
        }
    }

    private fun publishWorkState() {
        _uiState.update {
            it.copy(
                isScanRunning = workQueue.isScanning,
                isGroupingRunning = workQueue.isGrouping,
                queuedWork = workQueue.queued,
            )
        }
    }

    /** The waiting request is dropped; whatever is running carries on. */
    fun cancelQueuedWork() {
        workQueue = SelectorWorkQueue.cancelQueued(workQueue)
        publishWorkState()
    }

    private fun finishWork(work: SelectorWork) {
        applyWorkDecision(SelectorWorkQueue.finish(workQueue, work))
    }

    private fun runScan() {
        val images = _uiState.value.images
        if (images.isEmpty()) {
            finishWork(SelectorWork.SCAN)
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanProgress = 0f,
                    scanStatusText = SelectorLabels.scanProgress(0, images.size),
                    error = null,
                )
            }

            var analysed = 0
            try {
                scanImagesUseCase(images).collect { progress ->
                    val fraction = if (progress.total > 0) {
                        progress.processed.toFloat() / progress.total.toFloat()
                    } else {
                        0f
                    }
                    analysed = progress.processed

                    // Merged *inside* the update, against the state as it is at
                    // that moment. Building the new list from a snapshot read
                    // beforehand loses whatever landed in between — the EXIF and
                    // dimension loads run on their own coroutines and a scan is
                    // long.
                    _uiState.update { state ->
                        val merged = ScoreMerge.apply(state.images, progress.results)
                        state.copy(
                            scanProgress = fraction,
                            scanStatusText = SelectorLabels.scanProgress(
                                progress.processed,
                                progress.total,
                            ),
                            images = merged,
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        scanProgress = 1f,
                        scanStatusText = "",
                    ).withSnackbar(SelectorLabels.scanCompleteMessage(analysed), SnackbarLifetime.NOTICE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(scanProgress = 0f, scanStatusText = "") }
                Log.e("SelectorViewModel", "Scan failed", e)
                reportError("Scan failed")
            } finally {
                finishWork(SelectorWork.SCAN)
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _uiState.update { it.copy(scanProgress = 0f, scanStatusText = "") }
        finishWork(SelectorWork.SCAN)
    }

    private fun runGrouping(level: GroupingLevel) {
        val images = _uiState.value.images
        if (images.isEmpty()) {
            _uiState.update { it.copy(groups = emptyList()) }
            finishWork(SelectorWork.GROUPING)
            return
        }

        groupingJob?.cancel()
        groupingJob = viewModelScope.launch {
            try {
                val groupedImages = imageGrouper.groupImages(images, level)
                val indexByUri = images.withIndex().associate { (i, img) -> img.uri to i }
                val indexGroups = groupedImages.map { group ->
                    group.mapNotNull { grouped -> indexByUri[grouped.uri] }
                }.filter { it.isNotEmpty() }

                // `images` was read before the suspension; the list may have been
                // re-ordered or lost frames since, so the indices are carried over by URI.
                _uiState.update {
                    it.copy(
                        groups = SelectorReorder.remapGroups(indexGroups, images, it.images),
                        groupingEnabled = true,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectorViewModel", "Grouping failed", e)
                reportError("Grouping failed")
            } finally {
                finishWork(SelectorWork.GROUPING)
            }
        }
    }

    /** Stop a grouping pass that is running or waiting. */
    private fun cancelGroupingWork() {
        groupingJob?.cancel()
        groupingJob = null
        if (workQueue.queued == SelectorWork.GROUPING) cancelQueuedWork()
        if (workQueue.isGrouping) finishWork(SelectorWork.GROUPING)
    }

    fun toggleGrouping() {
        viewModelScope.launch {
            val current = settingsRepository.groupingEnabled.first()
            settingsRepository.setGroupingEnabled(!current)
        }
    }

    fun setGroupingLevel(level: GroupingLevel) {
        viewModelScope.launch {
            settingsRepository.setGroupingLevel(level)
        }
    }

    // ── Deletion (deferred) ──────────────────────────────────────────────

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun requestDeleteCurrentImage() = requestDelete()

    /**
     * Delete the current frame, skipping the dialog when the file can be
     * recovered from the system trash or when the photographer has already
     * confirmed permanent deletion for this folder in the current session.
     */
    fun requestDelete() {
        val state = _uiState.value
        if (state.images.isEmpty()) return

        val currentImage = state.images[state.currentIndex]
        val uri = Uri.parse(currentImage.uri)
        val folderUri = state.folderUri

        if (imageRepository.canTrash(uri) || (folderUri != null && folderUri in confirmedDeleteFolders)) {
            deleteCurrentImage()
        } else {
            showDeleteConfirmation()
        }
    }

    /**
     * Remove the current frame from the list now and push it onto the 20-deep
     * [SelectorUiState.undoHistory] stack.
     *
     * Nothing touches the file system until the entry is evicted past
     * [UndoPolicy.MAX_UNDO_HISTORY], the folder changes, or the session exits,
     * which makes `Ctrl+Z` / `UNDO` an infallible list insertion while in the folder.
     */
    fun deleteCurrentImage() {
        val state = _uiState.value
        state.folderUri?.let { confirmedDeleteFolders.add(it) }

        val (listState, pending) =
            DeferredDeletion.beginForCurrent(state.listState, System.currentTimeMillis())
                ?: run {
                    _uiState.update { it.copy(showDeleteConfirmation = false) }
                    return
                }

        val operation = UndoPolicy.forPendingDelete(pending) ?: return
        var evictedPendings: List<PendingDeletion> = emptyList()

        _uiState.update { current ->
            val pushResult = UndoPolicy.push(current.undoHistory, operation)
            evictedPendings = pushResult.evictedPendingDeletions
            current.withList(listState).copy(
                showDeleteConfirmation = false,
                undoHistory = pushResult.history,
                sessionDeletedCount = current.sessionDeletedCount + pending.slots.size,
            ).withSnackbar(
                SelectorLabels.deletedMessage(pending.uris.size),
                SnackbarLifetime.DELETE,
                undoAvailable = true,
            )
        }
        onFrameChanged()
        raiseHint(SelectorHint.DELETE_UNDO)

        journalAdd(pending.slots.flatMap { it.deletedFiles })

        evictedPendings.forEach { evicted ->
            commitPendingDeletion(evicted, state.folderUri)
        }
    }

    /**
     * Commit every pending deletion currently in [SelectorUiState.undoHistory] to disk.
     *
     * Called when leaving the folder ([resetSession]) or when the ViewModel is
     * cleared ([onCleared]).
     */
    private fun commitAllPendingDeletions() {
        val state = _uiState.value
        val pendings = state.pendingDeletions
        if (pendings.isEmpty()) return
        val originFolderUri = state.folderUri
        _uiState.update { current ->
            val remaining = current.undoHistory.filterNot { it is UndoableOperation.RevertPendingDelete }
            current.copy(
                undoHistory = remaining,
                snackbarUndoAvailable = current.snackbarUndoAvailable && remaining.isNotEmpty(),
            )
        }
        pendings.forEach { pending ->
            commitPendingDeletion(pending, originFolderUri)
        }
    }

    /**
     * Write a single [pending] deletion to disk and remove its URIs from the
     * durable pending-delete journal.
     *
     * Every deleted file takes its rating and cached analysis with it (FD-D-14): document ids on
     * local and SD storage are paths, so a new photo at the same path (camera numbering restarts,
     * a reused card) would otherwise inherit them.
     *
     * When some files could not be deleted while [originFolderUri] is still open, the frame
     * comes back only with what is left of it (FD-D-16): untouched, it is restored as it was and
     * "Could not delete «name»" is reported; half deleted, it is rebuilt from the surviving files
     * ([DeferredDeletion.survivors]) and the message says how many went.
     */
    private fun commitPendingDeletion(pending: PendingDeletion, originFolderUri: String?) {
        appScope.launch {
            val failed = mutableSetOf<String>()
            val deleted = mutableListOf<String>()
            val kept = mutableListOf<String>()
            // Under the journal lock: the add of this delete has written its identities by then.
            val recorded = journalMutex.withLock { settingsRepository.pendingDeleteIdentities.first() }
            pending.uris.forEach { uri ->
                // Another photo at a deleted URI (a refilled card) is kept, as on recovery.
                val known = recorded[uri]
                if (PendingDeleteJournal.needsCurrentIdentity(known) &&
                    PendingDeleteJournal.keepsInSession(known, readIdentity(uri))
                ) {
                    kept += uri
                    failed += uri
                    return@forEach
                }
                val ok = try {
                    imageRepository.deleteImage(context, Uri.parse(uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Delete failed for $uri", e)
                    false
                }
                if (ok) deleted += uri else failed += uri
            }
            journalRemove(pending.uris)
            deleted.forEach { gone ->
                try {
                    cacheRepository.forgetDeletedFile(gone)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Could not drop the cached data of deleted $gone", e)
                }
            }
            // The folder may have been left meanwhile; its grant was kept only for these entries.
            originFolderUri?.let { settingsRepository.releaseFolderGrantIfUnreferenced(it) }

            if (failed.isEmpty()) return@launch
            val stillOpen = originFolderUri != null && _uiState.value.folderUri == originFolderUri
            if (!stillOpen) return@launch
            if (kept.isNotEmpty()) reportError(SelectorLabels.recoveryKeptMessage(kept.map(::recoveredName)))
            if (kept.size == failed.size) return@launch
            val firstName = pending.slots.firstOrNull()?.image?.fileName ?: "image"
            if (deleted.isEmpty()) {
                _uiState.update { state ->
                    if (state.folderUri != originFolderUri) state
                    else state.withRestored(DeferredDeletion.revert(state.listState, pending))
                }
                reportError("Could not delete $firstName")
            } else {
                val survivors = DeferredDeletion.survivors(pending, failed)
                if (survivors != null) {
                    _uiState.update { state ->
                        if (state.folderUri != originFolderUri) state
                        else state.withRestored(DeferredDeletion.revert(state.listState, survivors))
                    }
                }
                reportError(SelectorLabels.partialDeleteMessage(firstName, deleted.size, pending.uris.size))
            }
        }
    }

    /**
     * Complete uncommitted deletions recovered from a previous killed session.
     *
     * Only a file that still has the identity journalled with it is deleted
     * ([PendingDeleteJournal]): another photo now at the same URI, or an entry journalled with no
     * identity (by an earlier version), is kept and named in the message (REQ-AND-LAYOUT.23).
     */
    fun confirmUncompletedDeletions() {
        val prompt = _uiState.value.uncompletedDeletionsPrompt ?: return
        _uiState.update { it.copy(uncompletedDeletionsPrompt = null) }
        appScope.launch {
            val recorded = settingsRepository.pendingDeleteIdentities.first()
            val current = coroutineScope {
                prompt.uris
                    .filter { PendingDeleteJournal.needsCurrentIdentity(recorded[it]) }
                    .map { uri -> async { uri to readIdentity(uri) } }
                    .awaitAll()
                    .toMap()
            }
            val recovery = PendingDeleteJournal.classify(prompt.uris, recorded, current)
            if (recovery.toDelete.isNotEmpty()) {
                _uiState.update { state ->
                    state.withList(OptimisticEdits.apply(state.listState, CurationAction.DELETE, recovery.toDelete).state)
                }
            }
            val failures = recovery.missing.map(::recoveredName).toMutableList()
            recovery.toDelete.forEach { uri ->
                val deleted = try {
                    imageRepository.deleteImage(context, Uri.parse(uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Recovery delete failed for $uri", e)
                    false
                }
                if (!deleted) {
                    failures += recoveredName(uri)
                    return@forEach
                }
                // As for a committed delete (FD-D-14): a new photo at this path must not inherit them.
                try {
                    cacheRepository.forgetDeletedFile(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Could not drop the cached data of deleted $uri", e)
                }
            }
            // Drop only the recovered entries: the journal may already hold deletions of
            // the current session, which a wholesale clear would erase (D-9).
            journalRemove(prompt.uris)
            val messages = buildList {
                if (failures.isNotEmpty()) {
                    val names = failures.joinToString()
                    add(
                        if (failures.size == prompt.uris.size && failures.size == 1) {
                            "Could not delete $names"
                        } else {
                            "Could not delete ${failures.size} of ${prompt.uris.size} recovered photos: $names"
                        }
                    )
                }
                if (recovery.kept.isNotEmpty()) {
                    add(SelectorLabels.recoveryKeptMessage(recovery.kept.map(::recoveredName)))
                }
            }
            if (messages.isNotEmpty()) reportError(messages.joinToString(" · "))
        }
    }

    /**
     * Keep photos from a previous killed session's uncommitted deletions and
     * clear the recovery journal without deleting.
     */
    fun keepUncompletedDeletions() {
        val prompt = _uiState.value.uncompletedDeletionsPrompt ?: return
        _uiState.update { it.copy(uncompletedDeletionsPrompt = null) }
        appScope.launch {
            journalRemove(prompt.uris)
        }
    }

    /** File name shown for a recovered journal entry, from the last segment of its document id. */
    private fun recoveredName(uri: String): String =
        Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: uri

    // ── Filing (optimistic) ──────────────────────────────────────────────

    fun moveToSelection() = performFiling(CurationAction.MOVE)

    fun copyToSelection() = performFiling(CurationAction.COPY)

    /**
     * Apply the filing action to the list immediately and transfer behind it.
     *
     * No spinner and no `isLoading`: the whole point is that filing a frame
     * costs the photographer nothing, and a 60 MB raw copied over SAF is exactly
     * the moment the app must not stall. The transfer runs on [appScope] so it
     * finishes even if the selector is left mid-copy.
     *
     * On failure the list rewinds through [OptimisticEdits.rollback], which
     * re-derives the index from the URI on screen rather than a stored integer —
     * and rewinds **nothing** for a copy, because the source file never left.
     */
    private fun performFiling(action: CurationAction) {
        val state = _uiState.value
        val folderUri = state.folderUri ?: return
        if (state.images.isEmpty()) return

        val before = _uiState.value.listState
        val currentItem = before.images.getOrNull(before.currentIndex) ?: return
        val sourceUri = currentItem.uri
        val edit = OptimisticEdits.applyToCurrent(before, action)

        _uiState.update {
            it.withList(edit.state).copy(
                performedFilingAction = if (action == CurationAction.MOVE) {
                    FilingAction.MOVE
                } else {
                    FilingAction.COPY
                },
            ).withSnackbar(
                // One sentence, one source: the hint card heading is generated by
                // this same call, so the card and the snackbar beside it cannot
                // disagree about the verb or name a folder the user renamed away.
                SelectionActionLabels.confirmation(
                    action = if (action == CurationAction.MOVE) {
                        FilingAction.MOVE
                    } else {
                        FilingAction.COPY
                    },
                    selectionFolderName = state.filingFolderName,
                    customFolderName = state.customDestinationName,
                ),
                // A move's undo never expires and a copy has none, so neither is a
                // deadline. UNDO is populated once the transfer reports where the
                // file went; a move with no destination URI is not reversible.
                if (action == CurationAction.MOVE) SnackbarLifetime.MOVE else SnackbarLifetime.COPY,
            )
        }
        onFrameChanged()
        // The card this transfer showed. Its UNDO is offered only while that card is still the
        // one on screen: two quick moves share a sentence, and the first one's late result
        // must not hand its record to the second one's card (FD-B-7).
        val ownCardSequence = _uiState.value.snackbarSequence
        // The photograph left the centre of the screen and nothing on it says
        // where it went. This is the explanation that matters most.
        raiseHint(SelectorHint.FILING)

        appScope.launch {
            val result = try {
                val sorting = settingsRepository.sortingEnabled.first()
                val destination = Uri.parse(folderUri)
                if (action == CurationAction.COPY) {
                    imageRepository.copyBundle(context, currentItem, destination, sorting)
                } else {
                    imageRepository.moveBundle(context, currentItem, destination, sorting)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectorViewModel", "${action.name} failed for $sourceUri", e)
                null
            }

            // The folder may have been switched while the transfer ran. The outcome on disk is
            // durable (undo record, rating); everything on screen belongs to the folder it was
            // started in and is skipped when that folder is no longer open.
            fun stillOpen() = _uiState.value.folderUri == folderUri
            if (result != null && result.success) {
                _uiState.update { current ->
                    if (current.folderUri != folderUri) return@update current
                    current.copy(
                        sessionMovedCount = if (action == CurationAction.MOVE) {
                            current.sessionMovedCount + 1
                        } else {
                            current.sessionMovedCount
                        },
                        sessionCopiedCount = if (action == CurationAction.COPY) {
                            current.sessionCopiedCount + 1
                        } else {
                            current.sessionCopiedCount
                        },
                        sessionKeptFileNames = current.sessionKeptFileNames + currentItem.fileName,
                    )
                }
                val undo = UndoPolicy.forFiling(
                    action = action,
                    slots = edit.slots,
                    sourceUri = sourceUri,
                    destinationUri = result.destinationUri,
                    destinationUris = result.destinationUris,
                    parentDocumentId = result.parentDocumentId ?: currentItem.parentDocumentId,
                )
                if (undo != null) pushFilingUndo(undo, folderUri, snackbarOffersUndo = true, cardSequence = ownCardSequence)
                // The rating belongs to the photograph: it follows the file (a copy starts with it).
                result.destinationUri?.let { moved ->
                    try {
                        if (action == CurationAction.COPY) {
                            cacheRepository.copyRating(sourceUri, moved)
                        } else {
                            cacheRepository.moveRating(sourceUri, moved)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SelectorViewModel", "Could not carry the rating of $sourceUri to $moved", e)
                    }
                }
                // A bundle that had to take a "-2" says which name it got; a plain transfer keeps
                // the optimistic "Moved to Selection" already on screen.
                val resultName = (result.destinationUri ?: result.destinationUris.firstOrNull())
                    ?.let(SelectionActionLabels::displayNameOf)
                if (resultName != null && resultName != currentItem.fileName) {
                    _uiState.update {
                        if (it.folderUri != folderUri) return@update it
                        // The longer sentence replaces the plain one, so it is a new
                        // message: it gets its own sequence number, and with it a
                        // fresh read time. UNDO, already offered, stays.
                        it.withSnackbar(
                            SelectionActionLabels.suffixedConfirmation(
                                action = if (action == CurationAction.MOVE) FilingAction.MOVE else FilingAction.COPY,
                                selectionFolderName = it.filingFolderName,
                                resultName = resultName,
                                relatedCount = (result.destinationUris.size - 1).coerceAtLeast(0),
                                customFolderName = it.customDestinationName,
                            ),
                            if (action == CurationAction.MOVE) SnackbarLifetime.MOVE else SnackbarLifetime.COPY,
                            undoAvailable = it.snackbarUndoAvailable,
                        )
                    }
                }
            } else if (result?.partial == true) {
                if (stillOpen()) {
                    rollbackFiling(edit.action, edit.slots)
                    val folder = FilingAction.selectionFolderOrDefault(_uiState.value.filingFolderName)
                    reportError(result.error ?: "Copied to $folder — original could not be removed")
                }
                // The rating belongs to the photograph: a verified copy starts with it exactly as a
                // Copy does, and the original (still there) keeps its own. A primary that did move
                // (a bundle member failed instead) is re-keyed like in the success path. Runs even
                // after a folder switch: it is durable data, not presentation.
                result.destinationUri?.takeIf { it.isNotBlank() }?.let { primary ->
                    try {
                        if (primary in result.copiesOfSurvivingOriginals) {
                            cacheRepository.copyRating(sourceUri, primary)
                        } else {
                            cacheRepository.moveRating(sourceUri, primary)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SelectorViewModel", "Could not carry the rating of $sourceUri to $primary", e)
                    }
                }
                // The verified copies in the destination are the photographer's data now, so the
                // partial move stays undoable (Ctrl+Z): undo deletes exactly those copies while
                // their originals still exist. The list item was restored above, hence no slots.
                UndoPolicy.forFiling(
                    action = action,
                    slots = emptyList(),
                    sourceUri = sourceUri,
                    destinationUri = result.destinationUri,
                    destinationUris = result.destinationUris,
                    parentDocumentId = result.parentDocumentId ?: currentItem.parentDocumentId,
                    copiesOfSurvivingOriginals = result.copiesOfSurvivingOriginals,
                )?.let { pushFilingUndo(it, folderUri, snackbarOffersUndo = false) }
            } else if (stillOpen()) {
                rollbackFiling(edit.action, edit.slots)
                val verb = if (action == CurationAction.COPY) "Copy" else "Move"
                Log.w("SelectorViewModel", "$verb failed: ${result?.error}")
                // A refusal for lack of space says what it needs; other causes stay in the log (FD-UX-4).
                reportError(result?.error?.takeIf(TransferSpace::isRefusal) ?: "$verb failed")
            }
        }
    }

    private fun pushFilingUndo(
        undo: UndoableOperation,
        folderUri: String?,
        snackbarOffersUndo: Boolean,
        cardSequence: Long? = null,
    ) {
        var evictedPendings: List<PendingDeletion> = emptyList()
        _uiState.update { current ->
            // The record belongs to the folder the transfer started in. If the photographer has
            // moved on, it is parked there — never pushed into the folder now open.
            if (folderUri != null && current.folderUri != folderUri) {
                return@update current.copy(parkedUndo = current.parkedUndo.park(folderUri, listOf(undo)))
            }
            val pushResult = UndoPolicy.push(current.undoHistory, undo)
            evictedPendings = pushResult.evictedPendingDeletions
            current.copy(
                undoHistory = pushResult.history,
                snackbarUndoAvailable = snackbarOffersUndo && current.snackbarMessage != null &&
                    (cardSequence == null || cardSequence == current.snackbarSequence),
            )
        }
        evictedPendings.forEach { evicted ->
            commitPendingDeletion(evicted, folderUri)
        }
    }

    private fun rollbackFiling(action: CurationAction, slots: List<ImageSlot>) {
        _uiState.update { state ->
            val restored = OptimisticEdits.rollback(state.listState, action, slots)
            state.withRestored(restored).copy(snackbarUndoAvailable = false)
        }
    }

    // ── Undo ─────────────────────────────────────────────────────────────

    /**
     * Pop and reverse the newest operation in [SelectorUiState.undoHistory].
     *
     * Supports up to [UndoPolicy.MAX_UNDO_HISTORY] (20) interleaved deletions
     * and moves in LIFO order via `Ctrl+Z` / `⌘Z` or the snackbar `UNDO` button.
     */
    fun undoLastOperation() {
        val state = _uiState.value
        val (operation, remainingHistory) = UndoPolicy.pop(state.undoHistory)
        when (operation) {
            null -> return

            is UndoableOperation.RevertPendingDelete -> {
                _uiState.update {
                    it.withRestored(DeferredDeletion.revert(it.listState, operation.pending)).copy(
                        undoHistory = remainingHistory,
                        snackbarUndoAvailable = false,
                        snackbarMessage = null,
                    )
                }
                onFrameChanged()
                appScope.launch {
                    journalRemove(operation.pending.uris)
                }
            }

            is UndoableOperation.RestoreFromTrash -> {
                val folderUri = state.folderUri
                _uiState.update {
                    it.withRestored(OptimisticEdits.restore(it.listState, operation.slots)).copy(
                        undoHistory = remainingHistory,
                        snackbarUndoAvailable = false,
                        snackbarMessage = null,
                    )
                }
                onFrameChanged()
                appScope.launch {
                    val failed = mutableListOf<String>()
                    operation.uris.forEach { uri ->
                        val ok = try {
                            imageRepository.restoreFromTrash(context, Uri.parse(uri))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("SelectorViewModel", "Restore from trash failed for $uri", e)
                            false
                        }
                        if (!ok) failed += uri
                    }
                    val stillOpen = _uiState.value.folderUri == folderUri
                    if (failed.isNotEmpty() && stillOpen) reportError("Could not restore the deleted image")
                    // What stayed in the trash is still undoable: hide it again and keep it on the stack
                    // of the folder it belongs to — parked there once another folder is open (FD-B-3).
                    UndoPolicy.remainderAfterRestore(operation, failed)?.let { remainder ->
                        _uiState.update { current ->
                            if (folderUri != null && current.folderUri != folderUri) {
                                return@update current.copy(parkedUndo = current.parkedUndo.park(folderUri, listOf(remainder)))
                            }
                            val hidden = OptimisticEdits.apply(
                                current.listState,
                                CurationAction.DELETE,
                                remainder.slots.map { it.uri },
                            )
                            current.withList(hidden.state).copy(
                                undoHistory = UndoPolicy.push(current.undoHistory, remainder).history,
                            )
                        }
                        onFrameChanged()
                    }
                }
            }

            is UndoableOperation.ReverseMove -> {
                val folderUri = state.folderUri ?: return
                _uiState.update {
                    it.withRestored(OptimisticEdits.restore(it.listState, operation.slots)).copy(
                        undoHistory = remainingHistory,
                        snackbarUndoAvailable = false,
                        snackbarMessage = null,
                    )
                }
                onFrameChanged()
                appScope.launch {
                    val result = try {
                        imageRepository.undoMoveBundle(
                            context = context,
                            destinationUris = operation.destinationUris,
                            rootFolderUri = Uri.parse(folderUri),
                            parentDocumentId = operation.parentDocumentId,
                            copiesOfSurvivingOriginals = operation.copiesOfSurvivingOriginals,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("SelectorViewModel", "Undo move failed", e)
                        null
                    }
                    finishUndoMove(operation, folderUri, result)
                }
            }
        }
    }

    /**
     * The tail of a [UndoableOperation.ReverseMove] once the transfer reported back.
     *
     * Durable data (ratings) follows the files whatever is open now. Everything on screen belongs
     * to [folderUri], the folder the undo was started in, and is skipped once another folder is
     * open; a failed undo is then parked under [folderUri] instead of being pushed onto the open
     * folder's stack (FD-B-3, the rule [pushFilingUndo] applies to filing).
     *
     * A restore that created new documents rebuilds the list item at their URIs (FD-B-11). A
     * partial result restored the copies, but the files in Selection could not be removed: the
     * frame stays, the user is told, and nothing is pushed for a retry, which would only make
     * `-2` duplicates (FD-D-15).
     */
    private suspend fun finishUndoMove(
        operation: UndoableOperation.ReverseMove,
        folderUri: String,
        result: FileOperationResult?,
    ) {
        fun stillOpen() = _uiState.value.folderUri == folderUri
        val restored = result != null && (result.success || result.partial)
        if (restored) {
            // A copy deleted by a partial-move undo takes its own rating row with it; the
            // surviving original keeps the rating it has (it was never re-keyed).
            result!!.deletedCopies.forEach { gone ->
                try {
                    cacheRepository.deleteRating(gone)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Could not drop the rating of deleted copy $gone", e)
                }
            }
            // The rating follows the primary file back — unless the primary was one of the
            // deleted copies, in which case `back` is some other restored file. When the file in
            // Selection is still there (partial), both keep it.
            result.destinationUri?.takeIf { it.isNotBlank() && operation.destinationUri !in result.deletedCopies }?.let { back ->
                try {
                    if (result.partial && back in result.copiesOfSurvivingOriginals) {
                        cacheRepository.copyRating(operation.destinationUri, back)
                    } else {
                        cacheRepository.moveRating(operation.destinationUri, back)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Could not carry the rating back to $back", e)
                }
            }
            if (operation.slots.isNotEmpty() && result.destinationUris.isNotEmpty()) {
                _uiState.update { current ->
                    if (current.folderUri != folderUri) return@update current
                    val relocated = operation.slots.fold(current.listState) { list, slot ->
                        UndoRestore.replace(
                            list,
                            slot.uri,
                            UndoRestore.relocate(slot.image, operation.destinationUris, result.destinationUris),
                        )
                    }
                    if (relocated == current.listState) current else current.withList(relocated)
                }
            }
        }
        when {
            result?.success == true -> {
                if (!stillOpen()) return
                result.notice?.let { notice ->
                    _uiState.update { it.withSnackbar(notice, SnackbarLifetime.NOTICE) }
                    return
                }
                val restoredName = (result.destinationUri ?: result.destinationUris.firstOrNull())
                    ?.let(SelectionActionLabels::displayNameOf)
                if (restoredName != null && restoredName != SelectionActionLabels.displayNameOf(operation.sourceUri)) {
                    _uiState.update {
                        it.withSnackbar(SelectionActionLabels.restoredAs(restoredName), SnackbarLifetime.NOTICE)
                    }
                }
            }
            result?.partial == true -> {
                if (!stillOpen()) return
                val name = operation.slots.firstOrNull()?.image?.fileName
                    ?: SelectionActionLabels.displayNameOf(operation.sourceUri)
                reportError(
                    SelectionActionLabels.undoPartialMessage(
                        fileName = name,
                        selectionFolderName = FilingAction.selectionFolderOrDefault(_uiState.value.filingFolderName),
                    )
                )
            }
            else -> {
                _uiState.update { current ->
                    if (current.folderUri != folderUri) {
                        return@update current.copy(parkedUndo = current.parkedUndo.park(folderUri, listOf(operation)))
                    }
                    val reapplied = OptimisticEdits.apply(
                        current.listState,
                        CurationAction.MOVE,
                        operation.slots.map { it.uri },
                    )
                    // The failed undo already left the stack; put it back so it can be retried.
                    current.withList(reapplied.state).copy(
                        undoHistory = UndoPolicy.push(current.undoHistory, operation).history,
                    )
                }
                if (stillOpen()) reportError("Could not move the image back")
            }
        }
    }

    /** The snackbar has timed out or been dismissed; its inline undo goes with it. */
    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null, snackbarUndoAvailable = false) }
    }

    // ── Misc actions ─────────────────────────────────────────────────────

    fun clearScores() {
        viewModelScope.launch {
            try {
                cacheRepository.clearAll()

                val clearedImages = _uiState.value.images.map { image ->
                    image.copy(scanResult = null)
                }
                _uiState.update { it.copy(images = clearedImages) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectorViewModel", "Clearing the cache failed", e)
                reportError("Could not clear the cache")
            }
        }
    }

    /**
     * Fill the image region with one frame, or return to three-up.
     *
     * Passing the frame that is already maximised toggles back out, so the same
     * control and the same key both open and close it.
     */
    fun toggleMaximised(frame: SelectorFrame) {
        val entering = _uiState.value.maximisedFrame != frame
        _uiState.update {
            val next = if (it.maximisedFrame == frame) null else frame
            it.copy(maximisedFrame = next, pendingHint = it.pendingHint.keptIn(next))
        }
        // Nothing on the maximised screen says how to get back out, so it is
        // said once, on the way in.
        if (entering) raiseHint(SelectorHint.MAXIMISE)
    }

    /** Leave the maximised state, if in it. Bound to Escape. */
    fun clearMaximised() {
        if (_uiState.value.maximisedFrame != null) {
            _uiState.update {
                it.copy(maximisedFrame = null, pendingHint = it.pendingHint.keptIn(null))
            }
        }
    }

    /**
     * The maximise explanation describes the maximised state, so it does not
     * outlive it. Where no slack beside the frame was wide enough it was never
     * drawn; left pending it would greet the user in three-up, explaining how to
     * return to a view they are already in. Dropped without being marked seen, so
     * it is offered again on the next maximise.
     */
    private fun SelectorHint?.keptIn(maximised: SelectorFrame?): SelectorHint? =
        if (this == SelectorHint.MAXIMISE && maximised == null) null else this

    /** Persist that the one-time on-image navigation arrows have now been shown. */
    fun markNavHintSeen() {
        viewModelScope.launch {
            if (!settingsRepository.hasSeenNavHint.first()) {
                settingsRepository.setHasSeenNavHint(true)
            }
        }
    }

    /** Show or hide the filmstrip; the choice survives relaunch. */
    fun toggleFilmstrip() {
        viewModelScope.launch {
            settingsRepository.setFilmstripVisible(!settingsRepository.filmstripVisible.first())
        }
    }

    /** Show or hide the readout block; the choice survives relaunch. */
    fun toggleDetails() {
        viewModelScope.launch {
            settingsRepository.setDetailsVisible(!settingsRepository.detailsVisible.first())
        }
    }

    /** Show or hide the neighbour value overlays; the choice survives relaunch. */
    fun toggleOverlayValues() {
        viewModelScope.launch {
            settingsRepository.setOverlayValuesVisible(
                !settingsRepository.overlayValuesVisible.first()
            )
        }
    }

    // ── One-time guidance ────────────────────────────────────────────────

    /**
     * Offer an explanation, if this one has not been given before.
     *
     * The decision is [SelectorGuidance.nextHint]'s, not this method's: whether
     * a hint is a card at all, whether it has been seen, and what happens to one
     * already on screen are policy, and policy that lives in a ViewModel method
     * is policy no JVM test can reach.
     */
    private fun raiseHint(hint: SelectorHint) {
        _uiState.update {
            it.copy(
                pendingHint = SelectorGuidance.nextHint(
                    candidate = hint,
                    seen = it.seenHints,
                    current = it.pendingHint,
                )
            )
        }
    }

    /**
     * The explanation has been read, dismissed, or has timed out.
     *
     * Persisted rather than merely cleared: it fires exactly once, ever, until
     * *Reset guidance*. Clearing the field locally as well means the card goes
     * immediately rather than on the DataStore round trip.
     */
    fun dismissHint() {
        val hint = _uiState.value.pendingHint ?: return
        _uiState.update { it.copy(pendingHint = null) }
        viewModelScope.launch { settingsRepository.markHintSeen(hint) }
    }

    /** Persist that the coach-mark guide has been seen, so it stops opening itself. */
    fun markGuideSeen() {
        if (SelectorHint.TOUR in _uiState.value.seenHints) return
        viewModelScope.launch { settingsRepository.markHintSeen(SelectorHint.TOUR) }
    }

    /** Persist that the fullscreen gesture hint has been dismissed. */
    /** `I` in the fullscreen viewer: keep the top bar up, or let it fade again. */
    fun toggleFullscreenInfoPinned() {
        // Flipped in the state first and persisted after, so two quick presses
        // toggle twice instead of both reading the value the store has not
        // emitted yet.
        val pinned = !_uiState.value.fullscreenInfoPinned
        _uiState.update { it.copy(fullscreenInfoPinned = pinned) }
        viewModelScope.launch { settingsRepository.setFullscreenInfoPinned(pinned) }
    }

    fun markFullscreenHintSeen() {
        viewModelScope.launch {
            if (!settingsRepository.hasSeenFullscreenGestureHint.first()) {
                settingsRepository.setHasSeenFullscreenGestureHint(true)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(message: String) = reportError(message)

    /** Errors reach the photographer through the same snackbar as everything else. */
    private fun reportError(message: String) {
        _uiState.update {
            it.copy(error = message).withSnackbar(message, SnackbarLifetime.ERROR)
        }
    }

    // ── EXIF, dimensions, prefetch ───────────────────────────────────────

    private val loadedExifCache = object : LinkedHashMap<String, ExifData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExifData>?): Boolean {
            return size > MAX_EXIF_CACHE_SIZE
        }
    }

    private fun loadMetadataForActiveRange() {
        val state = _uiState.value
        val images = state.images
        if (images.isEmpty()) return

        val indicesToLoad = SelectorWindows.visibleIndices(state.currentIndex, images.size)

        viewModelScope.launch {
            indicesToLoad.forEach { index ->
                val image = images.getOrNull(index) ?: return@forEach
                if (image.exifData == null) {
                    val cachedExif = loadedExifCache[image.uri]
                    if (cachedExif != null) {
                        updateImageExif(image.uri, cachedExif)
                    } else {
                        val exif = imageRepository.getExifData(context, Uri.parse(image.uri))
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
                if (img.uri == uri) {
                    val rating = if (img.rating != 0) img.rating else exif.rating
                    img.copy(exifData = exif, rating = rating)
                } else img
            }
            state.copy(images = updatedImages)
        }
    }

    /**
     * Resolve dimensions for the frames actually on screen.
     *
     * Enumeration no longer reads image headers — that is why folders open fast
     * — so the aspect ratio the frame solver wants arrives here instead. This is
     * a progressive-enhancement path and never a blocking one: until it lands,
     * `FrameGeometry` uses its documented 3:2 default and the frames are drawn
     * anyway (REQUIREMENTS §7, "Dimensions Are Resolved Off the Enumeration
     * Path").
     *
     * Restarted on navigation, unlike [startDimensionBackfill], because it is at
     * most a handful of URIs and the photographer is waiting on precisely these.
     */
    private fun resolveVisibleDimensions() {
        val state = _uiState.value
        if (state.images.isEmpty()) return

        val wanted = SelectorWindows.metadataIndices(state.currentIndex, state.images.size)
            .mapNotNull { state.images.getOrNull(it) }
            .filter { it.aspectRatio == null && it.uri !in dimensionsResolved }
            .map { it.uri }
        if (wanted.isEmpty()) return

        visibleDimensionsJob?.cancel()
        visibleDimensionsJob = viewModelScope.launch {
            val resolved = try {
                imageRepository.resolveDimensions(context, wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SelectorViewModel", "Dimension resolution failed", e)
                emptyMap()
            }
            // Marked only once the read has actually happened, so a cancelled
            // pass does not leave URIs permanently claimed and unresolved.
            dimensionsResolved += resolved.keys
            applyDimensions(resolved)
        }
    }

    /**
     * Fill in the dimensions of everything else, nearest to the photographer
     * first.
     *
     * Results are applied in batches of [DIMENSION_BATCH_SIZE] rather than per
     * image: a per-image state update in an 842-frame folder is 842
     * recompositions of the whole selector for information almost none of the
     * frames on screen needed.
     *
     * Deliberately **not** restarted per navigation or per discovery batch —
     * cancelling and re-sorting each time would throw away in-flight work and
     * re-read headers already read. [dimensionsResolved] makes each URI cost one
     * open, ever, including the ones that turn out to be unreadable.
     */
    private fun startDimensionBackfill(force: Boolean = false) {
        if (!force && backfillDimensionsJob?.isActive == true) return
        backfillDimensionsJob?.cancel()
        backfillDimensionsJob = viewModelScope.launch(ioDispatcher) {
            val state = _uiState.value
            val images = state.images
            if (images.isEmpty()) return@launch

            val pending = SelectorWindows.dimensionOrder(state.currentIndex, images.size)
                .mapNotNull { images.getOrNull(it) }
                .filter { it.aspectRatio == null && it.uri !in dimensionsResolved }
                .map { it.uri }

            pending.chunked(DIMENSION_BATCH_SIZE).forEach { chunk ->
                val remaining = chunk.filterNot { it in dimensionsResolved }
                if (remaining.isEmpty()) return@forEach
                val resolved = try {
                    imageRepository.resolveDimensions(context, remaining)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SelectorViewModel", "Dimension backfill failed", e)
                    emptyMap()
                }
                dimensionsResolved += resolved.keys
                applyDimensions(resolved)
            }
        }
    }

    /** One state update per batch, so recomposition stays bounded. */
    private fun applyDimensions(dimensions: Map<String, ImageDimensions>) {
        val known = dimensions.filterValues { it.isKnown }
        if (known.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                images = state.images.map { image ->
                    val dims = known[image.uri] ?: return@map image
                    if (image.imageWidth == dims.width && image.imageHeight == dims.height) {
                        image
                    } else {
                        image.copy(imageWidth = dims.width, imageHeight = dims.height)
                    }
                }
            )
        }
    }

    /**
     * Warm the frames the photographer is about to reach.
     *
     * The three-up layout already decodes current ± 1, so this covers what is
     * beyond that — the desktop product's `preload_next_candidates`, in Coil
     * terms. Requests are sized to a frame rather than to the original, because
     * decoding a 6192 × 4128 raw to fill a 675 dp tile spends memory and time on
     * pixels nobody will see.
     *
     * Stale requests are disposed rather than left running: on a fast scrub
     * through a burst the frames enqueued three navigations ago are competing
     * with the ones on screen. Requests still inside the window are left alone,
     * so a scrub does not restart work already half done.
     */
    private fun prefetchNeighbours() {
        val state = _uiState.value
        if (state.images.isEmpty()) return

        val wanted = SelectorWindows.prefetchIndices(state.currentIndex, state.images.size)
            .mapNotNull { state.images.getOrNull(it)?.uri }
            .toSet()

        prefetchRequests.keys.toList().forEach { uri ->
            if (uri !in wanted) {
                prefetchRequests.remove(uri)?.dispose()
            }
        }

        // Prefetch is an optimisation, never a requirement: any failure to warm
        // a frame must leave the visible decode exactly as it was.
        try {
            val loader = Coil.imageLoader(context)
            wanted.forEach { uri ->
                if (prefetchRequests[uri]?.isDisposed == false) return@forEach
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(PREFETCH_WIDTH_PX, PREFETCH_HEIGHT_PX)
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    .build()
                prefetchRequests[uri] = loader.enqueue(request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SelectorViewModel", "Neighbour prefetch unavailable", e)
        }
    }

    private fun cancelAllPrefetch() {
        prefetchRequests.values.forEach { it.dispose() }
        prefetchRequests.clear()
    }

    /**
     * Restore cached scan scores from Room.
     *
     * Debounced rather than run per discovery batch: it is a cache read per
     * image over the whole list, and running it on every cumulative emission
     * re-reads the same rows once per batch for no new information.
     */
    private fun scheduleScoreRestore(immediate: Boolean = false) {
        scoreRestoreJob?.cancel()
        scoreRestoreJob = viewModelScope.launch {
            if (!immediate) delay(SCORE_RESTORE_DEBOUNCE_MILLIS)
            restoreCachedScores()
        }
    }

    private suspend fun restoreCachedScores() {
        val images = _uiState.value.images
        if (images.isEmpty()) return

        val (updatedImages, persistedRatings) = withContext(ioDispatcher) {
            val uris = images.map { it.uri }
            val userRatings = try {
                val fromRepo = cacheRepository.getRatings(uris)
                if (fromRepo.isNotEmpty()) {
                    fromRepo
                } else {
                    scoreDao.getUserRatings(uris).associate { entity -> entity.uri to entity.rating }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }

            val mapped = images.map { image ->
                val userRating = userRatings[image.uri]
                val cached = try {
                    scoreDao.getScore(image.uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }

                // Validate scan cache entry matches current file
                val validCache = cached != null &&
                    cached.fileSize == image.fileSize &&
                    cached.lastModified == image.lastModified

                val currentRating = when {
                    image.rating != 0 -> image.rating
                    userRating != null -> userRating
                    validCache -> cached.rating
                    else -> 0
                }

                val scanResult = image.scanResult ?: if (validCache) {
                    ScanResult(
                        filePath = image.uri,
                        sharpnessScore = cached.sharpnessScore,
                        noiseLevel = cached.noiseLevel,
                        highlightClipping = cached.highlightClipping,
                        shadowClipping = cached.shadowClipping,
                        aestheticScore = cached.aestheticScore,
                    )
                } else {
                    null
                }

                if (currentRating == image.rating && scanResult == image.scanResult) {
                    image
                } else {
                    image.copy(
                        rating = currentRating,
                        scanResult = scanResult,
                    )
                }
            }
            mapped to userRatings
        }

        // Only the scores travel forward out of the snapshot. See [ScoreMerge]
        // for why merging the items themselves is a rollback rather than a merge.
        val restored = ScoreMerge.scoresOf(updatedImages)
        val restoredRatingsByUri = updatedImages.associate { it.uri to it.rating }
        _uiState.update { state ->
            val withScores = ScoreMerge.apply(state.images, restored)
            val withRatings = withScores.map { img ->
                val explicitUserRating = persistedRatings[img.uri]
                val fallbackRating = restoredRatingsByUri[img.uri] ?: 0
                when {
                    explicitUserRating != null && (img.rating == 0 || img.rating == img.exifData?.rating) ->
                        img.copy(rating = explicitUserRating)
                    img.rating == 0 && fallbackRating != 0 ->
                        img.copy(rating = fallbackRating)
                    else ->
                        img
                }
            }
            state.copy(images = withRatings)
        }
    }

    /**
     * The selector is going away.
     *
     * The pending deletion is committed here rather than dropped: the
     * photographer asked for it, the frame has been gone from the list since then,
     * and a deletion that silently un-happens because a screen
     * was left is a data-integrity bug. The commit itself runs on [appScope], so
     * cancelling `viewModelScope` a moment later does not take it with it.
     */
    override fun onCleared() {
        super.onCleared()
        commitAllPendingDeletions()
        cancelAllPrefetch()
    }

    companion object {
        /**
         * Worst-first reject triage (`U-34`): lowest sharpness first, unscored frames
         * at the end, ties by file name. The comparator the list is re-sorted with
         * whenever slots are restored while triage is on.
         */
        internal val WORST_FIRST: Comparator<ImageItem> =
            compareBy<ImageItem> { it.scanResult?.sharpnessScore == null }
                .thenBy { it.scanResult?.sharpnessScore ?: Double.MAX_VALUE }
                .thenBy { it.fileName.lowercase() }

        /** Maximum number of EXIF data entries to keep in memory. */
        private const val MAX_EXIF_CACHE_SIZE = 50

        /** Dimension results are applied to the UI state in batches of this size. */
        internal const val DIMENSION_BATCH_SIZE = 24

        /** Quiet period after the last discovery batch before scores are restored. */
        internal const val SCORE_RESTORE_DEBOUNCE_MILLIS = 400L

        /** Quiet period before rapid per-URI rating changes are written back to disk. */
        internal const val RATING_WRITE_DEBOUNCE_MILLIS = 500L

        /**
         * The size a prefetched frame is decoded at.
         *
         * A three-up frame is at most 675 × 450 dp, which is 1350 × 900 px at
         * the 2× density of the reference tablet. Rounded up once so a slightly
         * denser display still gets a usable cache entry, and no further: the
         * point of prefetching is to have the frame ready, not to have the
         * original in memory.
         */
        internal const val PREFETCH_WIDTH_PX = 1440
        internal const val PREFETCH_HEIGHT_PX = 1080
    }
}
