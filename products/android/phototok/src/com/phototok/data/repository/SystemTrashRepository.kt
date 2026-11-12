package com.phototok.data.repository

import android.content.Context
import android.content.IntentSender
import android.util.Log
import com.photoselector.core.storage.SourceKind
import com.phototok.data.source.MediaStoreTrash
import com.phototok.data.source.MediaStoreTrashImpl
import com.phototok.di.IoDispatcher
import com.phototok.domain.DeleteSplit
import com.phototok.domain.SystemTrashQueue
import com.phototok.domain.TrashItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A system-bin consent request ready to launch from an Activity. */
class SystemTrashRequest(
    val id: Long,
    val intentSender: IntentSender,
    val count: Int,
)

/** Files the user kept by declining the bin's consent: "N not deleted", with Retry. */
data class TrashNotice(val id: Long, val items: List<TrashItem>)

/** What an Activity shows: the next consent request, and the notice after a decline. */
data class SystemTrashUiState(
    val request: SystemTrashRequest? = null,
    val notice: TrashNotice? = null,
)

/**
 * Finalised deletes of MediaStore-indexed phone photos go to the system bin ([USER 2026-11-12]
 * decision #13, REQ-TOK-GESTURE.03); everything else stays a permanent delete.
 *
 * Finalisation runs on the application scope and may outlive the ViewModel and the Activity
 * (REQ-TOK-ARCH.08), but the consent dialog needs an Activity. So this singleton holds the
 * requests: [state] publishes the next one, whichever Activity collects it launches it (a
 * batch finalised while no Activity is shown waits for the next one), and [onResult] settles it.
 * A declined request keeps its photos and publishes a [TrashNotice].
 */
@Singleton
class SystemTrashRepository(
    private val gateway: MediaStoreTrash? = null,
    private val statsRepository: StatsRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        statsRepository: StatsRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(MediaStoreTrashImpl(context), statsRepository, ioDispatcher)

    private val queue = SystemTrashQueue()
    private val waiting = HashMap<Long, CompletableDeferred<SystemTrashQueue.Answer>>()
    private var inFlight: Pair<SystemTrashQueue.Request, SystemTrashRequest>? = null
    private var nextNoticeId = 1L

    private val _state = MutableStateFlow(SystemTrashUiState())
    val state: StateFlow<SystemTrashUiState> = _state.asStateFlow()

    private val _trashed = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)

    /** Document URIs that reached the bin, for a feed that is still open (Retry included). */
    val trashed: SharedFlow<Set<String>> = _trashed.asSharedFlow()

    /** Whether files of [kind] can go to the bin at all (the phone's storage on Android 11+). */
    fun offersBin(kind: SourceKind): Boolean = kind == SourceKind.PHONE_LIBRARY && gateway?.canTrash == true

    /** Document URI -> MediaStore item for the [documentUris] of [kind] that go to the bin. */
    suspend fun binTargets(documentUris: List<String>, kind: SourceKind): Map<String, String> {
        val g = gateway ?: return emptyMap()
        if (!offersBin(kind) || documentUris.isEmpty()) return emptyMap()
        return withContext(ioDispatcher) { g.resolve(documentUris) }
    }

    /** The delete dialog's split of [documentUris] of [kind] (the same rule as the finalisation). */
    suspend fun split(documentUris: List<String>, kind: SourceKind): DeleteSplit {
        val bin = binTargets(documentUris, kind).size
        return DeleteSplit(bin = bin, permanent = documentUris.size - bin)
    }

    /**
     * Queue [items] for the bin. Released (every finalisation but the evicting 51st delete) they
     * go out with the next consent request. Completes once the user answered for all of them.
     */
    fun submit(items: List<TrashItem>, release: Boolean = true): Deferred<SystemTrashQueue.Answer> {
        val done = CompletableDeferred<SystemTrashQueue.Answer>()
        if (items.isEmpty()) {
            done.complete(SystemTrashQueue.Answer(0, emptyList(), emptyList()))
            return done
        }
        synchronized(this) {
            val id = queue.submit(items, release)
            waiting[id] = done
        }
        publishNext()
        return done
    }

    /** Send everything held (an evicted delete) with this finalisation, even an empty one. */
    fun release() {
        synchronized(this) { queue.release() }
        publishNext()
    }

    /** The Activity launched [request]: it is not launched again (rotation, recomposition). */
    fun onLaunched(request: SystemTrashRequest) {
        _state.update { if (it.request === request) it.copy(request = null) else it }
    }

    /**
     * The system answered the request in flight. Accepted: read `IS_TRASHED` back and act on that.
     * Declined or not launched: every file stays, and the notice offers Retry.
     */
    suspend fun onResult(accepted: Boolean) {
        val (request, shown) = synchronized(this) { inFlight ?: return }
        val mediaUris = request.items.map { it.mediaUri }
        val flags = if (accepted && gateway != null) withContext(ioDispatcher) { gateway.readTrashed(mediaUris) } else emptyMap()
        // No row after an accepted request: the item is gone. A flag still 0: it stayed.
        val trashedDocs = if (accepted) {
            request.items.filter { flags[it.mediaUri] != false }.map { it.documentUri }.toSet()
        } else {
            emptySet()
        }
        val answers = synchronized(this) {
            if (inFlight?.first !== request) return
            inFlight = null
            queue.answer(request, trashedDocs)
        }
        _state.update { if (it.request === shown) it.copy(request = null) else it }
        settle(request, trashedDocs, answers)
    }

    /** Retry from the notice: the same files, one new consent request. */
    fun retry(notice: TrashNotice) {
        clearNotice(notice)
        submit(notice.items, release = true)
    }

    fun clearNotice(notice: TrashNotice) {
        _state.update { if (it.notice?.id == notice.id) it.copy(notice = null) else it }
    }

    private fun settle(
        request: SystemTrashQueue.Request,
        trashedDocs: Set<String>,
        answers: List<SystemTrashQueue.Answer>,
    ) {
        val trashed = request.items.filter { it.documentUri in trashedDocs }
        val kept = request.items.filter { it.documentUri !in trashedDocs }
        if (trashed.isNotEmpty()) {
            // A file in the bin is not freed space until the bin empties: counted, 0 bytes.
            statsRepository?.recordCommittedDelete(kind = SourceKind.PHONE_LIBRARY, bytesFreed = 0L, fileCount = trashed.size)
            _trashed.tryEmit(trashedDocs)
        }
        if (kept.isNotEmpty()) {
            _state.update { s ->
                // A second decline before the first notice was shown: one notice for all of them.
                val earlier = s.notice?.items.orEmpty()
                s.copy(notice = TrashNotice(id = nextNoticeId++, items = (earlier + kept).distinctBy { it.documentUri }))
            }
        }
        val completed = synchronized(this) { answers.mapNotNull { a -> waiting.remove(a.submissionId)?.let { it to a } } }
        completed.forEach { (deferred, answer) -> deferred.complete(answer) }
        publishNext()
    }

    /** Publish the next ready request unless one is already shown or in flight. */
    private fun publishNext() {
        val request = synchronized(this) {
            if (inFlight != null) return
            queue.take() ?: return
        }
        val sender = try {
            gateway?.trashRequest(request.items.map { it.mediaUri })
        } catch (e: Exception) {
            Log.w(TAG, "Trash request for ${request.items.size} files refused", e)
            null
        }
        if (sender == null) {
            // MediaProvider refused the list: nothing was deleted, the photos stay (notice).
            val answers = synchronized(this) { queue.answer(request, emptySet()) }
            settle(request, emptySet(), answers) // publishes the next request
            return
        }
        val shown = SystemTrashRequest(id = request.id, intentSender = sender, count = request.items.size)
        synchronized(this) { inFlight = request to shown }
        _state.update { it.copy(request = shown) }
    }

    private companion object {
        const val TAG = "SystemTrash"
    }
}
