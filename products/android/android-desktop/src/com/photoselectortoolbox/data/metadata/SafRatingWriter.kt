package com.photoselectortoolbox.data.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Rating write-back for a SAF document (REQ-AND-RATING.01). The strategy per format comes from
 * [RatingFormats]. Data-safety rules, all enforced here:
 *
 * - The folder that holds the sidecar is the image's real parent, asked of the provider
 *   (`findDocumentPath`, else a walk of the opened tree comparing provider-issued document ids).
 *   A document id is never split, parsed or guessed at.
 * - An existing sidecar is updated only after it was read in full (byte count equal to what the
 *   provider reports) and decoded as strict UTF-8; otherwise the write fails and it is untouched.
 * - A new sidecar is created under its final name, written, read back and compared; a failed
 *   one is deleted again. An existing sidecar or JPEG is rewritten only after the new content was
 *   built and verified in a cache copy, with the original bytes kept: after the write the
 *   document is read back and compared, and on any mismatch or error the original bytes are
 *   written back and checked too.
 * - A JPEG is the user's original, so before it is opened for writing a backup document of it is
 *   created beside it and read back ([RatingBackups]); it is deleted only after the photo read
 *   back as written or as restored. A backup left by a process that died mid-write is resolved at
 *   the next folder open ([recover]).
 * - A RAW/TIFF image document is never opened for writing; nothing is ever written next to a
 *   format that [RatingFormats] does not cover.
 */
internal class SafRatingWriter(
    private val context: Context,
    private val validateJpegBounds: (File) -> Boolean,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    private class Entry(val documentId: String, val name: String, val mime: String?, val size: Long?)

    suspend fun write(imageUri: Uri, rating: Int) {
        val name = displayNameOf(imageUri)
            ?: throw RatingWriteException("Could not read the file name from the storage provider")
        when (RatingFormats.strategyFor(name)) {
            RatingStrategy.UNSUPPORTED -> throw RatingWriteException(RatingFormats.unsupportedMessage(name))
            RatingStrategy.EMBEDDED_XMP -> writeJpeg(imageUri, name, rating)
            RatingStrategy.SIDECAR -> writeSidecar(imageUri, name, rating)
        }
    }

    // ── Sidecar ──────────────────────────────────────────────────────────

    private suspend fun writeSidecar(imageUri: Uri, name: String, rating: Int) {
        val parent = resolveParent(imageUri)
        val listing = children(parent.treeUri, parent.documentId)
            ?: throw RatingWriteException("Could not list the folder of $name to find its sidecar")

        val fileSidecarName = "$name.xmp"
        val stemSidecarName = "${name.substringBeforeLast('.', name)}.xmp"
        // Same order as the File path and the reader: <file>.xmp wins over <stem>.xmp.
        val existing = listOf(fileSidecarName, stemSidecarName).firstNotNullOfOrNull { candidate ->
            val matches = listing.filter { it.name == candidate && it.mime != Document.MIME_TYPE_DIR }
            if (matches.size > 1) throw RatingWriteException("$candidate exists more than once in the folder; left unchanged")
            matches.firstOrNull()
        }

        if (existing == null) {
            createSidecar(parent, fileSidecarName, rating)
        } else {
            updateSidecar(DocumentsContract.buildDocumentUriUsingTree(parent.treeUri, existing.documentId), existing, rating)
        }
    }

    private fun createSidecar(parent: Parent, sidecarName: String, rating: Int) {
        val folder = DocumentFile.fromTreeUri(context, parent.folderUri)
            ?: throw RatingWriteException("Could not open the folder to create $sidecarName")
        val created = folder.createFile("application/octet-stream", sidecarName)
            ?: throw RatingWriteException("Could not create $sidecarName")
        try {
            // The provider may rename on collision or add an extension; a sidecar with another name is no sidecar.
            if (created.name != sidecarName) {
                throw RatingWriteException("The storage provider named the sidecar '${created.name}' instead of '$sidecarName'")
            }
            val bytes = XmpSidecarWriter.createXmpPacket(rating).toByteArray(Charsets.UTF_8)
            // A document we just created is empty, so there is nothing to truncate.
            (resolver.openOutputStream(created.uri, "w") ?: throw IOException("Cannot open $sidecarName for writing"))
                .use { it.write(bytes) }
            if (!readAll(created.uri).contentEquals(bytes)) throw IOException("$sidecarName did not read back as written")
        } catch (e: Exception) {
            runCatching { created.delete() }
            throw e as? RatingWriteException ?: RatingWriteException("Could not write $sidecarName: ${e.message}", e)
        }
    }

    private fun updateSidecar(uri: Uri, entry: Entry, rating: Int) {
        val original = try {
            readAll(uri)
        } catch (e: Exception) {
            throw RatingWriteException("Could not read the existing ${entry.name}; it was left unchanged", e)
        }
        if (entry.size != null && entry.size > 0 && entry.size != original.size.toLong()) {
            throw RatingWriteException("Could not read all of the existing ${entry.name}; it was left unchanged")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(original)).toString()
        } catch (e: CharacterCodingException) {
            throw RatingWriteException("The existing ${entry.name} is not UTF-8 text; it was left unchanged", e)
        }
        if (!XmpSidecarWriter.canUpdateInPlace(text)) {
            throw RatingWriteException("The existing ${entry.name} has content the rating cannot be merged into; it was left unchanged")
        }
        val updated = XmpSidecarWriter.injectOrUpdateRating(text, rating).toByteArray(Charsets.UTF_8)
        replaceVerified(uri, entry.name, original, updated)
    }

    // ── JPEG ─────────────────────────────────────────────────────────────

    /**
     * Embeds the rating in the JPEG itself. The photo is the user's original, so it is opened for
     * writing only after a verified backup document of it exists beside it ([RatingBackups]): a
     * write that dies between the truncate and the last byte leaves the backup, and the next
     * folder open ([recover]) puts it back.
     */
    private suspend fun writeJpeg(imageUri: Uri, name: String, rating: Int) {
        val parent = resolveParent(imageUri)
        val listing = children(parent.treeUri, parent.documentId)
            ?: throw RatingWriteException("Could not list the folder of $name to keep a backup; it was left unchanged")
        val backupName = RatingBackups.backupNameFor(name)
        if (listing.any { it.name == backupName }) {
            throw RatingWriteException("$backupName from an earlier interrupted write is still beside $name; reopen the folder to recover it")
        }
        val cacheDir = context.cacheDir ?: throw RatingWriteException("Cache directory unavailable")
        val work = File.createTempFile("saf_rating_", ".tmp", cacheDir)
        try {
            val original = try {
                readAll(imageUri)
            } catch (e: Exception) {
                throw RatingWriteException("Could not read $name; it was left unchanged", e)
            }
            work.writeBytes(original)
            val exif = ExifInterface(work.absolutePath)
            val currentXmp = exif.getAttribute(ExifInterface.TAG_XMP)
            if (!XmpSidecarWriter.canUpdateInPlace(currentXmp)) {
                throw RatingWriteException("The XMP inside $name has content the rating cannot be merged into; it was left unchanged")
            }
            exif.setAttribute(ExifInterface.TAG_XMP, XmpSidecarWriter.injectOrUpdateRating(currentXmp, rating))
            exif.saveAttributes()
            if (!validateJpegBounds(work)) {
                throw RatingWriteException("The updated $name failed verification; it was left unchanged")
            }
            val updated = work.readBytes()
            val backup = createBackup(parent, backupName, original)
            replaceVerified(imageUri, name, original, updated, backup = backup, backupName = backupName)
        } catch (e: RatingWriteException) {
            throw e
        } catch (e: Exception) {
            throw RatingWriteException("Could not write the rating into $name: ${e.message}", e)
        } finally {
            work.delete()
        }
    }

    /** Creates [backupName] beside the photo holding [original], read back and compared. */
    private fun createBackup(parent: Parent, backupName: String, original: ByteArray): Uri {
        val folder = DocumentFile.fromTreeUri(context, parent.folderUri)
            ?: throw RatingWriteException("Could not open the folder to create $backupName; the photo was left unchanged")
        val created = folder.createFile("application/octet-stream", backupName)
            ?: throw RatingWriteException("Could not create $backupName; the photo was left unchanged")
        try {
            if (created.name != backupName) {
                throw RatingWriteException("The storage provider named the backup '${created.name}' instead of '$backupName'; the photo was left unchanged")
            }
            (resolver.openOutputStream(created.uri, "w") ?: throw IOException("Cannot open $backupName for writing"))
                .use { it.write(original) }
            if (!readAll(created.uri).contentEquals(original)) throw IOException("$backupName did not read back as written")
            return created.uri
        } catch (e: Exception) {
            runCatching { created.delete() }
            throw e as? RatingWriteException
                ?: RatingWriteException("Could not write $backupName: ${e.message}; the photo was left unchanged", e)
        }
    }

    // ── Recovery at folder open ──────────────────────────────────────────

    /**
     * Finds every [RatingBackups] document under [folderUri] (breadth first, one query per folder)
     * and resolves it as [RatingBackups] describes. [lockPhoto] serialises the work on a photo
     * with a rating write to the same document.
     */
    suspend fun recover(
        folderUri: Uri,
        lockPhoto: suspend (Uri, suspend () -> Unit) -> Unit,
    ): RatingRecovery {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(folderUri)
        } catch (_: Exception) {
            return RatingRecovery.NONE
        }
        val restored = mutableListOf<String>()
        val kept = mutableListOf<KeptRatingBackup>()
        val queue = ArrayDeque(listOf(rootId))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = queue.removeFirst()
            if (!seen.add(dir)) continue
            val entries = children(folderUri, dir) ?: continue
            entries.filter { it.mime == Document.MIME_TYPE_DIR }.forEach { queue.addLast(it.documentId) }
            for (backup in entries) {
                val photoName = RatingBackups.photoNameOf(backup.name) ?: continue
                if (backup.mime == Document.MIME_TYPE_DIR) continue
                coroutineContext.ensureActive()
                val photo = entries.singleOrNull { it.name == photoName && it.mime != Document.MIME_TYPE_DIR }
                val backupUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, backup.documentId)
                if (photo == null) {
                    kept += KeptRatingBackup(photoName, backup.name)
                    continue
                }
                val photoUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, photo.documentId)
                lockPhoto(photoUri) {
                    when (resolveBackup(photoUri, backupUri, photoName)) {
                        BackupOutcome.DISCARDED -> Unit
                        BackupOutcome.RESTORED -> restored += photoName
                        BackupOutcome.KEPT -> kept += KeptRatingBackup(photoName, backup.name)
                    }
                }
            }
        }
        return RatingRecovery(restored, kept)
    }

    private enum class BackupOutcome { DISCARDED, RESTORED, KEPT }

    private fun resolveBackup(photoUri: Uri, backupUri: Uri, photoName: String): BackupOutcome {
        val backupBytes = runCatching { readAll(backupUri) }.getOrElse {
            Log.w(TAG, "Cannot read the backup of $photoName", it)
            return BackupOutcome.KEPT
        }
        val photoBytes = runCatching { readAll(photoUri) }.getOrNull()
        if (photoBytes != null && RatingBackups.photoUntouched(photoBytes, backupBytes)) {
            return if (deleteDocument(backupUri)) BackupOutcome.DISCARDED else BackupOutcome.KEPT
        }
        if (photoBytes != null && RatingBackups.isCompleteJpeg(photoBytes, ::decodesBounds)) return BackupOutcome.KEPT
        if (!decodesBounds(backupBytes)) return BackupOutcome.KEPT
        return try {
            overwrite(photoUri, backupBytes)
            if (!readAll(photoUri).contentEquals(backupBytes)) throw IOException("$photoName did not read back as restored")
            deleteDocument(backupUri)
            BackupOutcome.RESTORED
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore $photoName from its backup", e)
            BackupOutcome.KEPT
        }
    }

    private fun decodesBounds(bytes: ByteArray): Boolean {
        val cacheDir = context.cacheDir ?: return false
        val probe = File.createTempFile("saf_rating_probe_", ".jpg", cacheDir)
        return try {
            probe.writeBytes(bytes)
            validateJpegBounds(probe)
        } catch (_: Exception) {
            false
        } finally {
            probe.delete()
        }
    }

    private fun deleteDocument(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot delete $uri", e)
        false
    }

    // ── Verified in-place replacement ────────────────────────────────────

    /**
     * Writes [updated] over [uri], reads it back and compares. On any failure the document is
     * read first: when it still holds [original] nothing was changed (a provider that refuses
     * `"wt"` fails before truncating, FD-D-19); otherwise [original] is written back and compared.
     * Only reached with both byte arrays fully in hand.
     *
     * [backup], when given, is a verified copy of [original] beside the document. It is deleted
     * once the document reads back as written or as restored, and kept when neither happened —
     * the next folder open recovers from it.
     */
    private fun replaceVerified(
        uri: Uri,
        name: String,
        original: ByteArray,
        updated: ByteArray,
        backup: Uri? = null,
        backupName: String? = null,
    ) {
        try {
            overwrite(uri, updated)
            if (!readAll(uri).contentEquals(updated)) throw IOException("$name did not read back as written")
        } catch (e: Exception) {
            val unchanged = runCatching { readAll(uri).contentEquals(original) }.getOrDefault(false)
            val restored = unchanged || runCatching {
                overwrite(uri, original)
                readAll(uri).contentEquals(original)
            }.getOrDefault(false)
            if (restored && backup != null) deleteDocument(backup)
            Log.w(TAG, "Rating write to $name failed; unchanged=$unchanged restored=$restored", e)
            throw RatingWriteException(
                when {
                    restored -> "Could not write the rating into $name; it was left unchanged"
                    backupName != null -> "Could not write the rating into $name and could not restore it; the original is kept as $backupName"
                    else -> "Could not write the rating into $name and could not restore it; check the file"
                },
                e,
            )
        }
        if (backup != null && !deleteDocument(backup)) {
            Log.w(TAG, "The rating is in $name, but its backup could not be deleted")
        }
    }

    private fun overwrite(uri: Uri, bytes: ByteArray) {
        // "wt" only: a plain "w" would leave stale bytes behind a shorter file.
        (resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing"))
            .use { it.write(bytes) }
    }

    private fun readAll(uri: Uri): ByteArray =
        (resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")).use { it.readBytes() }

    // ── Provider queries ─────────────────────────────────────────────────

    private class Parent(val treeUri: Uri, val documentId: String) {
        val folderUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    private fun displayNameOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getStringOrNull(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun resolveParent(imageUri: Uri): Parent {
        val docId = try {
            DocumentsContract.getDocumentId(imageUri)
        } catch (_: Exception) {
            null
        } ?: throw RatingWriteException("Not a document of an opened folder")
        val treeUri = try {
            if (!DocumentsContract.isTreeUri(imageUri)) null
            else DocumentsContract.buildTreeDocumentUri(imageUri.authority, DocumentsContract.getTreeDocumentId(imageUri))
        } catch (_: Exception) {
            null
        } ?: throw RatingWriteException("The photo is not inside an opened folder, so its sidecar folder cannot be found")

        // 1. The provider's own answer.
        val path = try {
            DocumentsContract.findDocumentPath(resolver, imageUri)?.path
        } catch (_: Exception) {
            null
        }
        if (path != null && path.size >= 2 && path.last() == docId) return Parent(treeUri, path[path.size - 2])

        // 2. Walk the opened tree, breadth first, one query per folder, comparing provider-issued ids.
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque(listOf(rootId))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dir = queue.removeFirst()
            if (!seen.add(dir)) continue
            val entries = children(treeUri, dir) ?: continue
            if (entries.any { it.documentId == docId }) return Parent(treeUri, dir)
            entries.filter { it.mime == Document.MIME_TYPE_DIR }.forEach { queue.addLast(it.documentId) }
        }
        throw RatingWriteException("Could not find the folder of the photo, so no sidecar was written")
    }

    private fun children(treeUri: Uri, parentId: String): List<Entry>? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            childrenUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getStringOrNull(0) ?: continue
                    val n = c.getStringOrNull(1) ?: continue
                    add(Entry(id, n, c.getStringOrNull(2), c.getLongOrNull(3)))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot list $parentId", e)
        null
    }

    private companion object {
        const val TAG = "SafRatingWriter"
    }
}
