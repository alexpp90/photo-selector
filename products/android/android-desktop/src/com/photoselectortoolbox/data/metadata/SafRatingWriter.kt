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

    private fun writeJpeg(imageUri: Uri, name: String, rating: Int) {
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
            exif.setAttribute(
                ExifInterface.TAG_XMP,
                XmpSidecarWriter.injectOrUpdateRating(exif.getAttribute(ExifInterface.TAG_XMP), rating),
            )
            exif.saveAttributes()
            if (!validateJpegBounds(work)) {
                throw RatingWriteException("The updated $name failed verification; it was left unchanged")
            }
            replaceVerified(imageUri, name, original, work.readBytes())
        } catch (e: RatingWriteException) {
            throw e
        } catch (e: Exception) {
            throw RatingWriteException("Could not write the rating into $name: ${e.message}", e)
        } finally {
            work.delete()
        }
    }

    // ── Verified in-place replacement ────────────────────────────────────

    /**
     * Writes [updated] over [uri], reads it back and compares. On any failure [original] is
     * written back (and compared too). Only reached with both byte arrays fully in hand.
     */
    private fun replaceVerified(uri: Uri, name: String, original: ByteArray, updated: ByteArray) {
        try {
            overwrite(uri, updated)
            if (!readAll(uri).contentEquals(updated)) throw IOException("$name did not read back as written")
        } catch (e: Exception) {
            val restored = runCatching {
                overwrite(uri, original)
                readAll(uri).contentEquals(original)
            }.getOrDefault(false)
            Log.w(TAG, "Rating write to $name failed; original restored=$restored", e)
            throw RatingWriteException(
                if (restored) "Could not write the rating into $name; it was left unchanged"
                else "Could not write the rating into $name and could not restore it; check the file",
                e,
            )
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
