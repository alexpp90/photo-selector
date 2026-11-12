package com.phototok.domain

import com.photoselector.core.storage.SourceKind
import java.io.File
import java.net.URLDecoder

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

    /**
     * Record a successful copy or move of [sourceUri] out of the open folder [sourceFolderUri] of
     * kind [kind] into [destinationUri] (the created document, else the destination tree). Only a
     * card or camera source counts, and only when the destination is on another volume: a copy
     * into `<card>/Selection` stays on the card and is lost with it. A move off the card counts
     * as well: the photo is safe on the other volume.
     */
    fun recordTransfer(kind: SourceKind, sourceUri: String, sourceFolderUri: String?, destinationUri: String?) {
        if (kind != SourceKind.REMOVABLE && kind != SourceKind.CAMERA_MTP) return
        val folder = sourceFolderUri ?: return
        if (destinationUri == null || !CardVolumes.isOffCard(folder, destinationUri)) return
        recordCopy(sourceUri, folder)
    }

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

/**
 * Which storage volume a SAF URI lives on, from its string alone (no Android): the provider
 * authority plus, for document ids of the form `<volume>:<path>` (the external-storage provider:
 * `primary:DCIM`, `1A2B-3C4D:DCIM`), the volume part. A provider whose ids carry no volume (MTP:
 * numeric ids) is one volume per authority.
 */
object CardVolumes {

    fun volumeOf(uri: String): String? {
        val rest = uri.substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val segments = rest.split('/')
        val authority = segments.first()
        val docAt = segments.indexOf("document")
        val treeAt = segments.indexOf("tree")
        val rawId = when {
            docAt >= 0 && docAt + 1 < segments.size -> segments[docAt + 1]
            treeAt >= 0 && treeAt + 1 < segments.size -> segments[treeAt + 1]
            else -> return authority
        }
        val id = try {
            URLDecoder.decode(rawId, "UTF-8")
        } catch (_: IllegalArgumentException) {
            rawId // A malformed escape: the raw id still carries the volume before ':'.
        }
        val colon = id.indexOf(':')
        return if (colon > 0) "$authority|${id.substring(0, colon)}" else authority
    }

    /** True when [destinationUri] is on another volume than the card folder [cardFolderUri]. */
    fun isOffCard(cardFolderUri: String, destinationUri: String): Boolean {
        val card = volumeOf(cardFolderUri) ?: return false
        val dest = volumeOf(destinationUri) ?: return false
        return card != dest
    }
}
