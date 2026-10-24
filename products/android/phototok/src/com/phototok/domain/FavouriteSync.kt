package com.phototok.domain

/**
 * Identity of a favourite across sources (`U-17`).
 *
 * A feed URI is a SAF document URI *inside a tree*, so the same file opened through two trees
 * (`DCIM` and `DCIM/Camera`) has two URIs. The document key drops the tree part and keeps the
 * provider's document id, which is the same for both. Where MediaStore indexes the file, its
 * MediaStore URI identifies it even across providers (external storage vs. the Media provider).
 */
object FavouriteKeys {
    private const val TREE = "/tree/"
    private const val DOCUMENT = "/document/"

    /** `content://a/tree/T/document/D` -> `content://a/document/D`; any other URI unchanged. */
    fun documentKey(uri: String): String {
        val tree = uri.indexOf(TREE)
        val document = uri.indexOf(DOCUMENT)
        if (tree < 0 || document < tree) return uri
        return uri.substring(0, tree) + uri.substring(document)
    }

    /** True for a `content://media/...` URI, the only kind `MediaStore.createFavoriteRequest` takes. */
    fun isMediaStoreUri(key: String): Boolean = key.startsWith("content://media/")
}

/**
 * Everything Jackpix knows about favourites, as one immutable value (`U-17`).
 *
 * [favourites] is what the user wants. [systemFavourites] is the MediaStore `IS_FAVORITE` flag as
 * the system last confirmed it (a successful favourite request, or a read-back): the base of a
 * three-way merge. [pending] is the desired flag per MediaStore URI that differs from that base:
 * it survives a declined request and is offered again at the next flush point.
 */
data class FavouritesSnapshot(
    /** Favourite identity keys: the MediaStore URI where one is known, else the document key. */
    val favourites: Set<String> = emptySet(),
    /** Document key -> MediaStore URI, for documents that resolved to one. */
    val mediaUris: Map<String, String> = emptyMap(),
    /** MediaStore URIs whose `IS_FAVORITE` flag the system confirmed as set. */
    val systemFavourites: Set<String> = emptySet(),
    /** MediaStore URI -> wanted `IS_FAVORITE` flag the system has not confirmed yet. */
    val pending: Map<String, Boolean> = emptyMap(),
) {
    private val documentKeysByMediaUri: Map<String, Set<String>> by lazy {
        mediaUris.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
    }

    /** Document keys of every favourite: what a feed URI is matched against. */
    val favouriteDocumentKeys: Set<String> by lazy {
        favourites.flatMapTo(HashSet()) { key -> documentKeysByMediaUri[key].orEmpty() + key }
    }

    /** Document keys of the favourites the system flag carries too (shown as synced). */
    val syncedDocumentKeys: Set<String> by lazy {
        favourites.filter { it in systemFavourites && it !in pending }
            .flatMapTo(HashSet()) { documentKeysByMediaUri[it].orEmpty() }
    }

    fun keyOf(documentKey: String): String = mediaUris[documentKey] ?: documentKey

    fun isFavourite(uri: String): Boolean = FavouriteKeys.documentKey(uri) in favouriteDocumentKeys

    fun isSynced(uri: String): Boolean = FavouriteKeys.documentKey(uri) in syncedDocumentKeys
}

/** One `MediaStore.createFavoriteRequest`: one flag for a list of MediaStore URIs. */
data class FavouriteSyncBatch(
    val mediaUris: List<String>,
    val favourite: Boolean,
)

/**
 * Pure transitions of [FavouritesSnapshot] (`U-17`). The repository persists the result.
 *
 * Conflict rule (read-back): a change still queued in Jackpix wins, because it is newer than
 * anything Jackpix has seen in the system. Otherwise the system flag wins, because it was changed
 * in another app after the last sync. A Jackpix favourite the system has never confirmed is pushed
 * to the system, never dropped.
 */
object FavouriteSyncLogic {

    /**
     * Upper bound of one request. A request carries its URIs in a `PendingIntent`; 500 keeps the
     * parcel far below the binder limit. A longer queue goes out in several requests.
     */
    const val MAX_BATCH = 500

    /** Favourite or un-favourite [uris] (a photo and its companions). */
    fun setFavourite(snapshot: FavouritesSnapshot, uris: Collection<String>, favourite: Boolean): FavouritesSnapshot {
        var favourites = snapshot.favourites
        val touched = mutableListOf<String>()
        for (uri in uris.distinct()) {
            val documentKey = FavouriteKeys.documentKey(uri)
            val key = snapshot.keyOf(documentKey)
            favourites = if (favourite) favourites + key else favourites - key - documentKey
            touched += key
        }
        return requeue(snapshot.copy(favourites = favourites), touched)
    }

    /**
     * Record that [documentUri] is the MediaStore item [mediaUri]. A favourite kept under the
     * document key moves to the MediaStore URI, so the same photo is recognised from any source.
     */
    fun attachMediaUris(snapshot: FavouritesSnapshot, resolved: Map<String, String>): FavouritesSnapshot {
        if (resolved.isEmpty()) return snapshot
        var favourites = snapshot.favourites
        val mediaUris = snapshot.mediaUris.toMutableMap()
        for ((documentUri, mediaUri) in resolved) {
            val documentKey = FavouriteKeys.documentKey(documentUri)
            mediaUris[documentKey] = mediaUri
            if (documentKey in favourites) favourites = favourites - documentKey + mediaUri
        }
        return requeue(snapshot.copy(favourites = favourites, mediaUris = mediaUris), resolved.values)
    }

    /** Merge the `IS_FAVORITE` flags read from MediaStore (see the conflict rule above). */
    fun mergeSystemFlags(snapshot: FavouritesSnapshot, flags: Map<String, Boolean>): FavouritesSnapshot {
        if (flags.isEmpty()) return snapshot
        val favourites = snapshot.favourites.toMutableSet()
        val system = snapshot.systemFavourites.toMutableSet()
        for ((mediaUri, flag) in flags) {
            // The system changed since Jackpix last looked. With nothing queued that is another
            // app's change and wins. With a change queued, the queued wish already equals the new
            // flag (both differ from the base), so taking the flag keeps Jackpix's change too.
            if (flag != (mediaUri in system)) {
                if (flag) favourites += mediaUri else favourites -= mediaUri
            }
            if (flag) system += mediaUri else system -= mediaUri
        }
        return requeue(snapshot.copy(favourites = favourites, systemFavourites = system), flags.keys)
    }

    /** The requests that bring the system in line: additions first, at most [maxBatch] URIs each. */
    fun batches(snapshot: FavouritesSnapshot, maxBatch: Int = MAX_BATCH): List<FavouriteSyncBatch> =
        listOf(true, false).flatMap { flag ->
            snapshot.pending.filterValues { it == flag }.keys.sorted()
                .chunked(maxBatch)
                .map { FavouriteSyncBatch(mediaUris = it, favourite = flag) }
        }

    /**
     * The system answered [batch]. Accepted: its flag is now the base, and what the user changed
     * meanwhile stays queued. Declined: nothing changes, the batch stays queued for the next flush.
     */
    fun applyResult(snapshot: FavouritesSnapshot, batch: FavouriteSyncBatch, accepted: Boolean): FavouritesSnapshot {
        if (!accepted) return snapshot
        val system = if (batch.favourite) {
            snapshot.systemFavourites + batch.mediaUris
        } else {
            snapshot.systemFavourites - batch.mediaUris.toSet()
        }
        return requeue(snapshot.copy(systemFavourites = system), batch.mediaUris)
    }

    /** Drop the favourites and every queued change; what the system confirmed stays the base. */
    fun clearFavourites(snapshot: FavouritesSnapshot): FavouritesSnapshot =
        requeue(snapshot.copy(favourites = emptySet()), snapshot.systemFavourites + snapshot.pending.keys)

    /** Recompute [FavouritesSnapshot.pending] for [keys]: queued exactly when wish and base differ. */
    private fun requeue(snapshot: FavouritesSnapshot, keys: Collection<String>): FavouritesSnapshot {
        val pending = snapshot.pending.toMutableMap()
        for (key in keys) {
            if (!FavouriteKeys.isMediaStoreUri(key)) continue
            val wanted = key in snapshot.favourites
            if (wanted == (key in snapshot.systemFavourites)) pending -= key else pending[key] = wanted
        }
        return snapshot.copy(pending = pending)
    }
}
