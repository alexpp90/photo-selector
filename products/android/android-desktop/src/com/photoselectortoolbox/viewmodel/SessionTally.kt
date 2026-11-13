package com.photoselectortoolbox.viewmodel

/** What the user decided about one photo in this session. */
enum class FilingDecision { MOVED, COPIED, DELETED }

/**
 * The session's tally, per photo (`[USER 2026-11-12]` decision #29, UX note F-29): every photo
 * counts once, under its **latest** decision — a copied photo deleted afterwards is Deleted, not
 * Copied and Deleted — and undoing a decision returns the photo to the one before it. Remaining
 * is the photos still listed with no decision; a copied photo stays listed and is not Remaining.
 * So Moved + Copied + Deleted + Remaining is the number of photos, whatever the order of actions.
 *
 * Immutable: the view model swaps it inside `_uiState.update`. Keyed by the photo's own URI (an
 * `ImageSlot.uri`), never a companion's: a RAW + JPEG + XMP bundle is one photo.
 */
data class SessionTally(private val history: Map<String, List<FilingDecision>> = emptyMap()) {

    /** [uris] were decided [decision] (a transfer or a delete that went through). */
    fun record(uris: Collection<String>, decision: FilingDecision): SessionTally =
        SessionTally(history + uris.associateWith { (history[it].orEmpty()) + decision })

    /** The latest [decision] of each of [uris] was taken back; the one before it counts again. */
    fun undo(uris: Collection<String>, decision: FilingDecision): SessionTally {
        val next = history.toMutableMap()
        uris.forEach { uri ->
            val decisions = next[uri] ?: return@forEach
            val at = decisions.lastIndexOf(decision)
            if (at < 0) return@forEach
            val rest = decisions.filterIndexed { i, _ -> i != at }
            if (rest.isEmpty()) next.remove(uri) else next[uri] = rest
        }
        return SessionTally(next)
    }

    fun latest(uri: String): FilingDecision? = history[uri]?.lastOrNull()

    val moved: Int get() = count(FilingDecision.MOVED)
    val copied: Int get() = count(FilingDecision.COPIED)
    val deleted: Int get() = count(FilingDecision.DELETED)

    /** Photos of [listedUris] with no decision yet. */
    fun remaining(listedUris: List<String>): Int = listedUris.count { latest(it) == null }

    private fun count(decision: FilingDecision): Int = history.values.count { it.lastOrNull() == decision }
}
