package com.phototok.domain

/** One file a finalised delete sends to the system bin: its document, MediaStore item and size. */
data class TrashItem(
    val documentUri: String,
    val mediaUri: String,
    val bytes: Long,
)

/**
 * How a delete splits between the system bin and a permanent delete. [bin] files are
 * MediaStore-indexed phone photos (REQ-TOK-GESTURE.03, [USER 2026-11-12] decision #13); [permanent]
 * files are everything without a bin: SD card, USB, cloud, and files MediaStore does not index.
 */
data class DeleteSplit(val bin: Int, val permanent: Int) {
    val total: Int get() = bin + permanent
    val allToBin: Boolean get() = bin > 0 && permanent == 0
    val mixed: Boolean get() = bin > 0 && permanent > 0

    companion object {
        /** Nothing goes to a bin: the dialog says "Delete permanently?". */
        fun permanentOnly(count: Int) = DeleteSplit(bin = 0, permanent = count)
    }
}

/**
 * The bookkeeping of the system-bin consent requests, Android-free. Every finalisation
 * [submit]s its bin files; a [release]d submission joins the next consent request (one system
 * dialog per finalisation, at most [maxBatch] files per request, consecutive requests for more).
 * An unreleased submission (the 51st delete evicting the oldest, REQ-TOK-FEATURE.18) waits for the
 * next finalisation, so the swipe that evicted it never opens a dialog.
 *
 * The answer to a request settles its files; a submission is [Answer]ed once all its files are.
 * Not thread-safe: the owner serialises the calls.
 */
class SystemTrashQueue(private val maxBatch: Int = MAX_BATCH) {

    class Request internal constructor(val id: Long, val items: List<TrashItem>)

    /** A submission whose every file has an answer: [trashed] went to the bin, [kept] stay. */
    data class Answer(val submissionId: Long, val trashed: List<TrashItem>, val kept: List<TrashItem>)

    private class Submission(val id: Long, items: List<TrashItem>) {
        val open = items.map { it.documentUri }.toMutableSet()
        val trashed = mutableListOf<TrashItem>()
        val kept = mutableListOf<TrashItem>()
    }

    private var nextId = 1L
    private val held = mutableListOf<Pair<Long, TrashItem>>()
    private val ready = ArrayDeque<Request>()
    private val submissions = LinkedHashMap<Long, Submission>()
    private val owner = HashMap<Long, MutableMap<String, Long>>()

    /** The request on screen or about to be: one at a time. */
    var inFlight: Request? = null
        private set

    /** Queue [items]; with [release] they (and everything held) go into the next request. */
    fun submit(items: List<TrashItem>, release: Boolean): Long {
        val id = nextId++
        val unique = items.distinctBy { it.documentUri }
        submissions[id] = Submission(id, unique)
        unique.forEach { held += id to it }
        if (release) release()
        return id
    }

    /** Everything held goes into consent requests of at most [maxBatch] files. */
    fun release() {
        if (held.isEmpty()) return
        for (chunk in held.chunked(maxBatch)) {
            val request = Request(nextId++, chunk.map { it.second })
            owner[request.id] = chunk.associate { (sub, item) -> item.documentUri to sub }.toMutableMap()
            ready.addLast(request)
        }
        held.clear()
    }

    /** Files waiting for a later finalisation. */
    val heldCount: Int get() = held.size

    /** The next request to show, or null while one is in flight or nothing is ready. */
    fun take(): Request? {
        if (inFlight != null) return null
        val next = ready.removeFirstOrNull() ?: return null
        inFlight = next
        return next
    }

    /**
     * The system answered [request]; [trashedDocuments] are the files now in the bin (read back,
     * never assumed from the result code). Returns the submissions this answer completed.
     */
    fun answer(request: Request, trashedDocuments: Set<String>): List<Answer> {
        if (inFlight !== request) return emptyList()
        inFlight = null
        val owners = owner.remove(request.id).orEmpty()
        for (item in request.items) {
            val sub = owners[item.documentUri]?.let { submissions[it] } ?: continue
            if (item.documentUri in trashedDocuments) sub.trashed += item else sub.kept += item
            sub.open -= item.documentUri
        }
        val done = submissions.values.filter { it.open.isEmpty() }
        done.forEach { submissions.remove(it.id) }
        return done.map { Answer(it.id, it.trashed.toList(), it.kept.toList()) }
    }

    companion object {
        /** As `FavouriteSyncLogic.MAX_BATCH`: one MediaStore request carries at most this many items. */
        const val MAX_BATCH = 500
    }
}
