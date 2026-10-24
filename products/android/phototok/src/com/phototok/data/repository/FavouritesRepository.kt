package com.phototok.data.repository

import android.content.Context
import android.content.IntentSender
import android.util.Log
import com.phototok.data.model.ImageItem
import com.phototok.data.source.MediaStoreFavourites
import com.phototok.data.source.MediaStoreFavouritesImpl
import com.phototok.domain.FavouriteKeys
import com.phototok.domain.FavouriteSyncBatch
import com.phototok.domain.FavouriteSyncLogic
import com.phototok.domain.FavouritesSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Persisted [FavouritesSnapshot] (`U-17`): favourites, the MediaStore URIs they resolved to, the
 * system flag as last confirmed, and the queue of desired flags.
 *
 * File format, one entry per line, tab-separated:
 * `V 2` · `F <key>` favourite · `M <documentKey> <mediaUri>` · `S <mediaUri>` flag confirmed set ·
 * `Q <mediaUri> 1|0` wanted flag not yet confirmed. Version 1 (no `V` line) had `F <documentUri>`
 * and `U <documentUri>` (queued for sync); it loads without losing an entry: `F` entries become
 * document keys, and the `U` queue is rebuilt as soon as those documents resolve to MediaStore
 * URIs (every favourite with a MediaStore URI the system has not confirmed is queued).
 */
class FavouritesStore(
    private val storeFile: File? = null,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val _snapshot = MutableStateFlow(FavouritesSnapshot())
    val snapshot: StateFlow<FavouritesSnapshot> = _snapshot.asStateFlow()

    init {
        loadFromDisk()
    }

    /** Apply [transition] atomically and persist the result. */
    @Synchronized
    fun update(transition: (FavouritesSnapshot) -> FavouritesSnapshot): FavouritesSnapshot {
        val before = _snapshot.value
        val after = transition(before)
        if (after != before) {
            _snapshot.value = after
            persistLocked(after)
        }
        return after
    }

    private fun loadFromDisk() {
        val file = storeFile ?: return
        if (!file.exists()) return
        try {
            _snapshot.value = decode(file.readLines())
        } catch (e: Exception) {
            onError("Could not read ${file.name}", e)
        }
    }

    private fun persistLocked(snapshot: FavouritesSnapshot) {
        val file = storeFile ?: return
        try {
            file.parentFile?.mkdirs()
            // Write aside and rename, so a crash mid-write never leaves a truncated store.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encode(snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            onError("Could not write ${file.name}", e)
        }
    }

    companion object {
        const val VERSION = 2

        fun encode(snapshot: FavouritesSnapshot): String {
            // Only mappings something still refers to are kept; the rest are re-resolved.
            val referenced = snapshot.favourites + snapshot.systemFavourites + snapshot.pending.keys
            val sb = StringBuilder("V\t").append(VERSION).append('\n')
            for (key in snapshot.favourites) sb.append("F\t").append(key).append('\n')
            for ((documentKey, mediaUri) in snapshot.mediaUris) {
                if (mediaUri in referenced) {
                    sb.append("M\t").append(documentKey).append('\t').append(mediaUri).append('\n')
                }
            }
            for (mediaUri in snapshot.systemFavourites) sb.append("S\t").append(mediaUri).append('\n')
            for ((mediaUri, flag) in snapshot.pending) {
                sb.append("Q\t").append(mediaUri).append('\t').append(if (flag) '1' else '0').append('\n')
            }
            return sb.toString()
        }

        fun decode(lines: List<String>): FavouritesSnapshot {
            val favourites = LinkedHashSet<String>()
            val mediaUris = LinkedHashMap<String, String>()
            val system = LinkedHashSet<String>()
            val pending = LinkedHashMap<String, Boolean>()
            val version = lines.firstOrNull { it.startsWith("V\t") }?.substringAfter('\t')?.trim()?.toIntOrNull() ?: 1
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size < 2 || parts[1].isBlank()) continue
                when (parts[0]) {
                    // Version 1 stored tree document URIs; their document key matches every tree.
                    "F" -> favourites += if (version < 2) FavouriteKeys.documentKey(parts[1]) else parts[1]
                    "M" -> if (parts.size >= 3) mediaUris[parts[1]] = parts[2]
                    "S" -> system += parts[1]
                    "Q" -> if (parts.size >= 3) pending[parts[1]] = parts[2] == "1"
                    // "U" (version 1) is implied by its F line and re-queued once the document resolves.
                }
            }
            return FavouritesSnapshot(
                favourites = favourites,
                mediaUris = mediaUris,
                systemFavourites = system,
                pending = pending,
            )
        }
    }
}

/** One favourite request ready to launch, with the batch it carries. */
class FavouriteSyncRequest(
    val intentSender: IntentSender,
    val batch: FavouriteSyncBatch,
)

@Singleton
class FavouritesRepository(
    internal val store: FavouritesStore = FavouritesStore(storeFile = null),
    private val mediaStore: MediaStoreFavourites? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        store = FavouritesStore(
            storeFile = File(context.filesDir, "favourites_store.json"),
            onError = { message, e -> Log.w(TAG, message, e) },
        ),
        mediaStore = MediaStoreFavouritesImpl(context),
    )

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    val snapshot: StateFlow<FavouritesSnapshot> = store.snapshot

    /** MediaStore URIs whose request came back OK but whose flag did not change: not sent again this session. */
    private val failedThisSession: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    fun isFavourite(uri: String): Boolean = store.snapshot.value.isFavourite(uri)

    /** True on Android 11+, where favourites can reach MediaStore `IS_FAVORITE`. */
    val canSyncToSystem: Boolean get() = mediaStore?.canSync == true

    fun setFavourite(
        uri: String,
        favourite: Boolean,
        companionUris: Collection<String> = emptyList(),
    ) {
        store.update { FavouriteSyncLogic.setFavourite(it, listOf(uri) + companionUris, favourite) }
    }

    fun toggleFavourite(
        uri: String,
        companionUris: Collection<String> = emptyList(),
    ): Boolean {
        val newState = !isFavourite(uri)
        setFavourite(uri = uri, favourite = newState, companionUris = companionUris)
        return newState
    }

    /**
     * Resolve [items] to MediaStore and merge their `IS_FAVORITE` flags (read-back). Only
     * documents of the external-storage and Media providers can resolve; others stay
     * Jackpix-only. With [onlyUnresolved], documents resolved before are skipped.
     */
    suspend fun indexFromSystem(items: List<ImageItem>, onlyUnresolved: Boolean = false) {
        val gateway = mediaStore ?: return
        val known = store.snapshot.value.mediaUris
        val uris = items.map { it.uri }
            .filter { gateway.canResolve(it) && !(onlyUnresolved && FavouriteKeys.documentKey(it) in known) }
        if (uris.isEmpty()) return
        val resolved = withContext(ioDispatcher) { gateway.resolve(uris) }
        if (resolved.isEmpty()) return
        store.update { snapshot ->
            val attached = FavouriteSyncLogic.attachMediaUris(snapshot, resolved.mapValues { it.value.mediaUri })
            val flags = resolved.values.mapNotNull { r -> r.isFavourite?.let { r.mediaUri to it } }.toMap()
            FavouriteSyncLogic.mergeSystemFlags(attached, flags)
        }
    }

    /** How many MediaStore items wait for the system and can be sent now. */
    fun sendablePendingCount(): Int =
        if (canSyncToSystem) store.snapshot.value.pending.keys.count { it !in failedThisSession } else 0

    /** The next request of the queue, or null when nothing (more) can be sent. */
    fun nextSyncRequest(): FavouriteSyncRequest? {
        val gateway = mediaStore ?: return null
        if (!gateway.canSync) return null
        for (batch in pendingBatches()) {
            val sender = try {
                gateway.favoriteRequest(batch.mediaUris, batch.favourite)
            } catch (e: Exception) {
                Log.w(TAG, "Favourite request for ${batch.mediaUris.size} photos refused", e)
                failedThisSession += batch.mediaUris
                null
            }
            if (sender != null) return FavouriteSyncRequest(sender, batch)
        }
        return null
    }

    /**
     * The system answered [request]. MediaProvider reports OK even where a single update failed,
     * so an accepted batch is read back: a flag that did not change stays queued but is not sent
     * again this session. Declined: everything stays queued for the next flush point.
     */
    suspend fun onSyncResult(request: FavouriteSyncRequest, accepted: Boolean) {
        if (!accepted) return
        val batch = request.batch
        val actual = mediaStore?.let { gateway ->
            withContext(ioDispatcher) { gateway.readFlags(batch.mediaUris) }
        }.orEmpty()
        val notApplied = batch.mediaUris.filter { uri -> actual[uri]?.let { it != batch.favourite } == true }
        if (notApplied.isNotEmpty()) {
            Log.w(TAG, "${notApplied.size} favourite flags did not change")
            failedThisSession += notApplied
        }
        val applied = batch.copy(mediaUris = batch.mediaUris - notApplied.toSet())
        store.update { FavouriteSyncLogic.applyResult(it, applied, accepted = true) }
    }

    private fun pendingBatches(): List<FavouriteSyncBatch> {
        val snapshot = store.snapshot.value
        val sendable = snapshot.copy(pending = snapshot.pending.filterKeys { it !in failedThisSession })
        return FavouriteSyncLogic.batches(sendable)
    }

    private companion object {
        const val TAG = "Favourites"
    }
}
