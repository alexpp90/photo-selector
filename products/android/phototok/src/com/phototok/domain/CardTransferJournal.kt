package com.phototok.domain

import java.io.File

/**
 * Journal of what was copied off a removable card or camera (`filesDir/card_transfer_journal.tsv`,
 * one tab-separated `C<TAB>card folder<TAB>source uri` line per copied file) for the *"Done with
 * this card"* sheet (`U-14c`): "N copied" must still be right after the process was killed and the
 * card is opened again.
 *
 * It does **not** recover an interrupted copy. A copy killed half-way leaves a truncated file under
 * its final name in the destination, and the engine is the only party that knows that file
 * (handover H-JP-1: the engine should write under a marked temporary name and rename after the
 * verified copy; the journal can then grow an in-flight record again).
 *
 * Android-free; with no file it is an in-memory journal (unit tests).
 */
class CardTransferJournal(
    private val journalFile: File? = null,
) {

    private val copiedBySource = LinkedHashMap<String, MutableSet<String>>()

    init {
        loadFromDisk()
    }

    /** Number of distinct files copied out of the card folder [cardSourceKey]. */
    @Synchronized
    fun copiedCountForFolder(cardSourceKey: String): Int =
        copiedBySource[cardSourceKey]?.size ?: 0

    /** Record that [sourceUri] of the card folder [sourceFolderUri] was copied out. */
    @Synchronized
    fun recordCopy(sourceUri: String, sourceFolderUri: String) {
        val added = copiedBySource.getOrPut(sourceFolderUri) { LinkedHashSet() }.add(sourceUri)
        if (added) appendLocked(sourceFolderUri, sourceUri)
    }

    /** Forget a card folder, e.g. once the card has been cleared. */
    @Synchronized
    fun clearSource(cardSourceKey: String) {
        if (copiedBySource.remove(cardSourceKey) != null) rewriteLocked()
    }

    private fun loadFromDisk() {
        val file = journalFile ?: return
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 3 && parts[0] == "C" && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                    copiedBySource.getOrPut(parts[1]) { LinkedHashSet() }.add(parts[2])
                }
            }
        } catch (_: java.io.IOException) {
            // An unreadable journal only loses the counter, never a photo.
        }
    }

    private fun appendLocked(key: String, uri: String) {
        val file = journalFile ?: return
        try {
            file.parentFile?.mkdirs()
            file.appendText("C\t$key\t$uri\n")
        } catch (_: java.io.IOException) {
        }
    }

    private fun rewriteLocked() {
        val file = journalFile ?: return
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                buildString {
                    for ((key, uris) in copiedBySource) for (u in uris) append("C\t").append(key).append('\t').append(u).append('\n')
                },
            )
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: java.io.IOException) {
        }
    }
}
