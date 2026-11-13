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
import com.photoselectortoolbox.data.model.SelectionFolderState
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
import com.photoselectortoolbox.domain.scoring.ScanMeasures
import com.photoselectortoolbox.domain.session.JumpPreview
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoDates
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
     * What a screen reader says for [snackbarMessage] when it differs from the line on screen
     * (`CompanionConfirmation`: "+2" on screen, "and 2 companion files" spoken); null otherwise.
     */
    val snackbarSpoken: String? = null,
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
    /** Learn's rating filter: its filmstrip and stepping. Cull ignores it (REQ-AND-RATING.01). */
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
    /** Photos copied in this session, by URI: the copied mark, and a second Copy is a no-op. */
    val sessionCopiedUris: Set<String> = emptySet(),
    /** Every file a Copy or Move filed this session, companions included, by name with its size. */
    val sessionKeptFiles: Map<String, Long> = emptyMap(),
    /** The Selection folder as the Done card's lookup found it, and whether that lookup runs. */
    val doneSelection: SelectionFolderState? = null,
    val doneSelectionChecking: Boolean = false,
) {
    /** The Done card's safe-to-format line, or null for none ([DoneSelection]). */
    val safeToFormatLine: SafeToFormatLine?
        get() = DoneSelection.line(sourceKind, sessionKeptFiles, doneSelectionChecking, doneSelection)

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
        snackbarSpoken = null,
    )

    /** The most recent deletion removed from the list but not yet committed to disk. */
    val pendingDeletion: PendingDeletion?
        get() = undoHistory.filterIsInstance<UndoableOperation.RevertPendingDelete>().firstOrNull()?.pending

    /** All deletions currently held in [undoHistory] and not yet committed to disk. */
    val pendingDeletions: List<PendingDeletion>
        get() = undoHistory.filterIsInstance<UndoableOperation.RevertPendingDelete>().map { it.pending }

    /** The photos Learn's rating filter shows (Learn's filmstrip; Cull always uses [images]). */
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
