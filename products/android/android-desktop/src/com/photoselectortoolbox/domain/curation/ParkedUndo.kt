package com.photoselectortoolbox.domain.curation

/**
 * Undo records of folders the photographer has left, kept until they come back.
 *
 * The rule this type exists to enforce: **switching folders never drops a finished
 * operation's undo record.** A completed move or a trashed delete is durable; the
 * list the user is looking at is presentation. Pending (deferred) deletions are
 * the exception by design — they commit when their folder is left — so they are
 * never parked.
 *
 * Pure and Android-free. Keys are folder URI strings; each value is newest first,
 * bounded to [UndoPolicy.MAX_UNDO_HISTORY]. At most [MAX_PARKED_FOLDERS] folders
 * are held; the least recently parked folder drops its records first.
 */
data class ParkedUndo(
    private val byFolder: Map<String, List<UndoableOperation>> = emptyMap(),
) {

    /** Folders that currently have parked records, oldest parked first. */
    val folders: Set<String> get() = byFolder.keys

    /** The parked records of [folder], newest first (empty when none). */
    fun historyOf(folder: String): List<UndoableOperation> = byFolder[folder].orEmpty()

    /**
     * Park [operations] (newest first) in front of whatever [folder] already has.
     * Pending deletions are ignored — they are committed, not kept.
     */
    fun park(
        folder: String,
        operations: List<UndoableOperation>,
        maxFolders: Int = MAX_PARKED_FOLDERS,
    ): ParkedUndo {
        val durable = UndoParking.durable(operations)
        if (durable.isEmpty()) return this
        val merged = (durable + historyOf(folder)).take(UndoPolicy.MAX_UNDO_HISTORY)
        // Re-insert so [folder] becomes the most recently parked one.
        val next = LinkedHashMap(byFolder)
        next.remove(folder)
        next[folder] = merged
        while (next.size > maxFolders) next.remove(next.keys.first())
        return ParkedUndo(next)
    }

    /** Remove and return the records of [folder] (empty when none). */
    fun take(folder: String): Pair<List<UndoableOperation>, ParkedUndo> {
        val taken = byFolder[folder] ?: return emptyList<UndoableOperation>() to this
        return taken to ParkedUndo(byFolder - folder)
    }

    companion object {
        /** Folders whose undo records are kept while the photographer is elsewhere. */
        const val MAX_PARKED_FOLDERS = 8
    }
}

/** Pure helpers for moving undo records across a folder switch. */
object UndoParking {

    /** The operations of [history] that survive leaving a folder: everything but pending deletes. */
    fun durable(history: List<UndoableOperation>): List<UndoableOperation> =
        history.filterNot { it is UndoableOperation.RevertPendingDelete }

    /**
     * Every folder whose SAF grant must stay open for undo (finding D-10): the open
     * folder while its live [history] is non-empty, plus each folder with parked records.
     */
    fun foldersToPin(
        liveFolder: String?,
        history: List<UndoableOperation>,
        parked: ParkedUndo,
    ): Set<String> = buildSet {
        addAll(parked.folders)
        if (liveFolder != null && history.isNotEmpty()) add(liveFolder)
    }
}
