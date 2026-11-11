package com.photoselectortoolbox.domain.curation

/**
 * What told one file from another at a URI when it was deleted: its byte size and last-modified
 * time as the document provider reports them. A field the provider does not report is 0.
 */
data class FileIdentity(val size: Long, val lastModified: Long) {
    /** False when the provider reported neither field: such a file cannot be told from another. */
    val isKnown: Boolean
        get() = size > 0L || lastModified > 0L

    /** True when every field known here has the same value in [current]. */
    fun matches(current: FileIdentity): Boolean =
        isKnown &&
            (size <= 0L || size == current.size) &&
            (lastModified <= 0L || lastModified == current.lastModified)
}

/**
 * The identity half of the durable pending-delete journal (REQ-AND-LAYOUT.23, finding
 * 2026-11-05). The journal itself keeps the URI of each deletion not yet committed; beside it
 * every entry records the [FileIdentity] of the file the photographer deleted. On local and SD
 * storage a document id is the path, so after a restart another photo can sit at the same URI (a
 * camera that restarted its numbering, a refilled folder): "Delete now" deletes a recovered URI
 * only when the file there still has the recorded identity.
 *
 * Pure: the encoding and the decision. Reading the current identity is the repository's
 * ([com.photoselectortoolbox.data.repository.ImageRepository.documentIdentity]).
 */
object PendingDeleteJournal {

    /** What "Delete now" does with one recovered entry. */
    enum class Verdict {
        /** The file at the URI is the one that was deleted. */
        DELETE,

        /** Another file is at the URI now: it is kept. */
        CHANGED,

        /** No identity was recorded (an entry an earlier version journalled, or a provider that reports no size or date): kept. */
        UNVERIFIABLE,

        /** Nothing can be read at the URI: nothing is deleted, the entry is reported as not deleted. */
        MISSING,
    }

    /** One recovered entry per [Verdict], each list in the order the URIs were given. */
    data class Recovery(
        val toDelete: List<String>,
        val changed: List<String>,
        val unverifiable: List<String>,
        val missing: List<String>,
    ) {
        /** Entries kept because they could not be confirmed as the deleted photo. */
        val kept: List<String>
            get() = changed + unverifiable
    }

    private const val SEPARATOR = '\t'

    /** One stored identity entry: `size<TAB>lastModified<TAB>uri`. A URI never holds a raw tab. */
    fun encode(uri: String, identity: FileIdentity): String =
        "${identity.size}$SEPARATOR${identity.lastModified}$SEPARATOR$uri"

    /** The URI and identity of a stored entry, or null for one this version cannot read. */
    fun decode(entry: String): Pair<String, FileIdentity>? {
        val parts = entry.split(SEPARATOR, limit = 3)
        if (parts.size != 3 || parts[2].isEmpty()) return null
        val size = parts[0].toLongOrNull() ?: return null
        val modified = parts[1].toLongOrNull() ?: return null
        return parts[2] to FileIdentity(size, modified)
    }

    /** Every readable stored entry, by URI; an unreadable one is skipped (its URI is then unverifiable). */
    fun identities(entries: Set<String>): Map<String, FileIdentity> =
        entries.mapNotNull(::decode).toMap()

    fun verdict(recorded: FileIdentity?, current: FileIdentity?): Verdict = when {
        recorded == null || !recorded.isKnown -> Verdict.UNVERIFIABLE
        current == null -> Verdict.MISSING
        recorded.matches(current) -> Verdict.DELETE
        else -> Verdict.CHANGED
    }

    /** Whether the current identity of a URI is needed at all: not for an entry that is unverifiable anyway. */
    fun needsCurrentIdentity(recorded: FileIdentity?): Boolean = recorded != null && recorded.isKnown

    fun classify(
        uris: List<String>,
        recorded: Map<String, FileIdentity>,
        current: Map<String, FileIdentity?>,
    ): Recovery {
        val byVerdict = uris.groupBy { verdict(recorded[it], current[it]) }
        return Recovery(
            toDelete = byVerdict[Verdict.DELETE].orEmpty(),
            changed = byVerdict[Verdict.CHANGED].orEmpty(),
            unverifiable = byVerdict[Verdict.UNVERIFIABLE].orEmpty(),
            missing = byVerdict[Verdict.MISSING].orEmpty(),
        )
    }
}
