package com.photoselectortoolbox.data.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.photoselectortoolbox.domain.format.ImageFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-destructive metadata write-back engine for ratings.
 *
 * - JPEG Files: Updates embedded XMP via [ExifInterface] on a temporary copy, verifies the
 *   modified file is non-empty and its image bounds decode cleanly via [BitmapFactory], and
 *   only then atomically replaces the target file — leaving image pixel bytes and EXIF capture
 *   timestamps ([ExifInterface.TAG_DATETIME_ORIGINAL]) untouched and guaranteeing the original
 *   photo is never corrupted if write-back fails.
 * - RAW & DNG Files (CR2, CR3, NEF, ARW, ORF, RAF, DNG, etc.): Creates or updates companion `<filename>.xmp`
 *   sidecar files formatted with ISO 16684-1 standard `<xmp:Rating>`.
 * - Non-destructive fallback: If in-place write fails (read-only, unsupported header, or failed
 *   post-write verification), falls back gracefully to a companion `.xmp` sidecar.
 * - Per-URI serialization: Concurrent writes to the same file or URI are serialized via a per-key [Mutex].
 */
interface MetadataWriter {
    suspend fun writeRating(imageFile: File, rating: Int): Result<Unit>

    suspend fun writeRating(context: Context, imageUri: Uri, rating: Int): Result<Unit> {
        val file = when {
            imageUri.scheme == "file" -> imageUri.path?.let { File(it) } ?: File(imageUri.toString())
            imageUri.scheme == null -> File(imageUri.toString())
            imageUri.path != null && File(imageUri.path!!).exists() -> File(imageUri.path!!)
            else -> File(imageUri.toString())
        }
        return writeRating(file, rating)
    }
}

@Singleton
class MetadataWriterImpl internal constructor(
    private val validateJpegBounds: (File) -> Boolean
) : MetadataWriter {

    @Inject
    constructor() : this(Companion::defaultValidateJpegBounds)

    companion object {
        private const val TAG = "MetadataWriter"

        internal fun defaultValidateJpegBounds(file: File): Boolean {
            if (!file.exists() || file.length() <= 0L) return false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            return opts.outWidth > 0 && opts.outHeight > 0
        }
    }

    private val uriMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(key: String): Mutex =
        uriMutexes.computeIfAbsent(key) { Mutex() }

    override suspend fun writeRating(context: Context, imageUri: Uri, rating: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val directFile = when {
                imageUri.scheme == "file" -> imageUri.path?.let { File(it) }
                imageUri.scheme == null && imageUri.path != null -> File(imageUri.path!!)
                else -> imageUri.path?.let { p -> val f = File(p); if (f.exists()) f else null }
            }
            if (directFile != null && directFile.exists()) {
                return@withContext writeRating(directFile, rating)
            }

            mutexFor(imageUri.toString()).withLock {
                var tempFile: File? = null
                try {
                    val displayName = resolveSafDisplayName(context, imageUri)
                    if (ImageFormats.isRaw(displayName) || !ImageFormats.isJpeg(displayName)) {
                        writeSafSidecarRating(context, imageUri, displayName, rating)
                        return@withLock Result.success(Unit)
                    }

                    val cacheDir = context.cacheDir ?: throw IOException("Cache directory unavailable")
                    val createdTemp = File.createTempFile("saf_rating_", ".tmp", cacheDir)
                    tempFile = createdTemp

                    val inputStream = context.contentResolver.openInputStream(imageUri)
                        ?: return@withLock Result.failure(
                            IOException("Unable to open input stream for $imageUri")
                        )
                    inputStream.use { input ->
                        createdTemp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val exif = ExifInterface(createdTemp.absolutePath)
                    val currentXmp = exif.getAttribute(ExifInterface.TAG_XMP)
                    val updatedXmp = XmpSidecarWriter.injectOrUpdateRating(currentXmp, rating)
                    exif.setAttribute(ExifInterface.TAG_XMP, updatedXmp)
                    exif.saveAttributes()

                    if (!validateJpegBounds(createdTemp)) {
                        Log.w(TAG, "Modified SAF JPEG failed bounds verification for $imageUri; falling back to sidecar")
                        writeSafSidecarRating(context, imageUri, displayName, rating)
                        return@withLock Result.success(Unit)
                    }

                    val outputStream = try {
                        context.contentResolver.openOutputStream(imageUri, "wt")
                    } catch (_: Exception) {
                        context.contentResolver.openOutputStream(imageUri, "w")
                    } ?: return@withLock Result.failure(
                        IOException("Unable to open output stream for $imageUri")
                    )

                    outputStream.use { output ->
                        createdTemp.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.w(TAG, "SAF rating write skipped/failed for $imageUri", e)
                    Result.failure(e)
                } finally {
                    tempFile?.delete()
                }
            }
        }

    private fun resolveSafDisplayName(context: Context, imageUri: Uri): String {
        runCatching {
            context.contentResolver.query(
                imageUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        val docId = runCatching { DocumentsContract.getDocumentId(imageUri) }.getOrNull()
        if (!docId.isNullOrBlank()) {
            return docId.substringAfterLast('/')
        }
        return imageUri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    private fun writeSafSidecarRating(
        context: Context,
        imageUri: Uri,
        displayName: String,
        rating: Int,
    ) {
        if (displayName.isBlank()) {
            throw IOException("Cannot resolve display name for SAF sidecar rating: $imageUri")
        }
        val docId = runCatching { DocumentsContract.getDocumentId(imageUri) }.getOrNull()
            ?: throw IOException("URI is not a SAF document URI: $imageUri")
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(imageUri) }.getOrNull()
            ?: throw IOException("SAF document URI lacks tree grant for sidecar creation: $imageUri")
        val parentDocId = if (docId.contains('/')) {
            docId.substringBeforeLast('/')
        } else {
            treeDocId
        }

        val filenameSidecarName = "$displayName.xmp"
        val stem = displayName.substringBeforeLast('.', displayName)
        val stemSidecarName = "$stem.xmp"

        var existingFilenameSidecarUri: Uri? = null
        var existingStemSidecarUri: Uri? = null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(imageUri, parentDocId)
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val childId = if (idIdx >= 0 && !cursor.isNull(idIdx)) cursor.getString(idIdx) else null
                    val childName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                    if (childId != null && childName != null) {
                        if (childName.equals(filenameSidecarName, ignoreCase = true)) {
                            existingFilenameSidecarUri = DocumentsContract.buildDocumentUriUsingTree(imageUri, childId)
                        } else if (childName.equals(stemSidecarName, ignoreCase = true)) {
                            existingStemSidecarUri = DocumentsContract.buildDocumentUriUsingTree(imageUri, childId)
                        }
                    }
                }
            }
        }

        val targetSidecarUri = existingFilenameSidecarUri
            ?: existingStemSidecarUri
            ?: run {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(imageUri, parentDocId)
                try {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        "application/rdf+xml",
                        filenameSidecarName,
                    )
                } catch (_: Exception) {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parentUri,
                        "application/octet-stream",
                        filenameSidecarName,
                    )
                } ?: throw IOException("Unable to create SAF sidecar document $filenameSidecarName in $parentUri")
            }

        val existingContent = if (existingFilenameSidecarUri != null || existingStemSidecarUri != null) {
            runCatching {
                context.contentResolver.openInputStream(targetSidecarUri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        } else {
            null
        }
        val updatedContent = XmpSidecarWriter.injectOrUpdateRating(existingContent, rating)
        val out = try {
            context.contentResolver.openOutputStream(targetSidecarUri, "wt")
        } catch (_: Exception) {
            context.contentResolver.openOutputStream(targetSidecarUri, "w")
        } ?: throw IOException("Unable to open output stream for SAF sidecar $targetSidecarUri")
        out.bufferedWriter().use { it.write(updatedContent) }
    }

    override suspend fun writeRating(imageFile: File, rating: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutexFor(imageFile.absolutePath).withLock {
                try {
                    if (!imageFile.exists()) {
                        return@withLock Result.failure(
                            IOException("Image file does not exist: ${imageFile.absolutePath}")
                        )
                    }

                    // RAW (and TIFF, via the final branch) get a sidecar; only JPEG is
                    // edited in place via a verified temporary copy.
                    if (ImageFormats.isRaw(imageFile.name)) {
                        writeSidecarRating(imageFile, rating)
                    } else if (ImageFormats.isJpeg(imageFile.name)) {
                        try {
                            writeJpegRating(imageFile, rating)
                        } catch (e: Exception) {
                            Log.w(TAG, "Verified JPEG write failed for ${imageFile.name}; falling back to sidecar", e)
                            writeSidecarRating(imageFile, rating)
                        }
                    } else {
                        // For any other format, use sidecar non-destructively
                        writeSidecarRating(imageFile, rating)
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write rating to ${imageFile.absolutePath}", e)
                    Result.failure(e)
                }
            }
        }

    private fun writeJpegRating(imageFile: File, rating: Int) {
        if (!imageFile.canWrite()) {
            throw IOException("JPEG file is not writable: ${imageFile.absolutePath}")
        }
        val parent = imageFile.parentFile ?: File(".")
        val tempFile = File(parent, ".${imageFile.name}.rating.tmp.${System.nanoTime()}")
        try {
            imageFile.copyTo(tempFile, overwrite = true)

            val exif = ExifInterface(tempFile.absolutePath)
            val currentXmp = exif.getAttribute(ExifInterface.TAG_XMP)
            val updatedXmp = XmpSidecarWriter.injectOrUpdateRating(currentXmp, rating)

            exif.setAttribute(ExifInterface.TAG_XMP, updatedXmp)
            exif.saveAttributes()

            if (!validateJpegBounds(tempFile)) {
                throw IOException("Modified temp JPEG failed bounds validation: ${imageFile.name}")
            }

            atomicReplace(tempFile, imageFile)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun writeSidecarRating(imageFile: File, rating: Int) {
        val parent = imageFile.parentFile ?: File(".")
        // Standard ISO 16684-1 companion: <filename>.xmp (e.g. IMG_0001.CR3.xmp) takes priority over <stem>.xmp
        val filenameSidecar = File(parent, "${imageFile.name}.xmp")
        val stemSidecar = File(parent, "${imageFile.nameWithoutExtension}.xmp")
        val targetSidecar = if (filenameSidecar.exists() && filenameSidecar.isFile) {
            filenameSidecar
        } else if (stemSidecar.exists() && stemSidecar.isFile) {
            stemSidecar
        } else {
            filenameSidecar
        }

        val existingContent = if (targetSidecar.exists() && targetSidecar.isFile) {
            targetSidecar.readText()
        } else {
            null
        }

        val updatedContent = XmpSidecarWriter.injectOrUpdateRating(existingContent, rating)
        val tempSidecar = File(parent, ".${targetSidecar.name}.tmp.${System.nanoTime()}")
        try {
            tempSidecar.writeText(updatedContent)
            atomicReplace(tempSidecar, targetSidecar)
        } finally {
            if (tempSidecar.exists()) {
                tempSidecar.delete()
            }
        }
    }

    private fun atomicReplace(sourceTemp: File, target: File) {
        try {
            Files.move(
                sourceTemp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                sourceTemp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

object XmpSidecarWriter {

    private val RATING_TAG_REGEX = Regex(
        "<(?:[a-zA-Z0-9_-]+:)?Rating>\\s*(-?\\d+(?:\\.\\d+)?)\\s*</(?:[a-zA-Z0-9_-]+:)?Rating>",
        RegexOption.IGNORE_CASE
    )
    private val RATING_ATTR_REGEX = Regex(
        "((?:[a-zA-Z0-9_-]+:)?Rating\\s*=\\s*[\"'])(-?\\d+(?:\\.\\d+)?)([\"'])",
        RegexOption.IGNORE_CASE
    )

    fun createXmpPacket(rating: Int): String {
        return buildString {
            append("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("    <rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n")
            append("      <xmp:Rating>$rating</xmp:Rating>\n")
            append("    </rdf:Description>\n")
            append("  </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }
    }

    fun injectOrUpdateRating(existingXmp: String?, rating: Int): String {
        if (existingXmp.isNullOrBlank()) {
            return createXmpPacket(rating)
        }

        // 1. If <xmp:Rating>...</xmp:Rating> exists, replace it
        if (RATING_TAG_REGEX.containsMatchIn(existingXmp)) {
            return existingXmp.replace(RATING_TAG_REGEX, "<xmp:Rating>$rating</xmp:Rating>")
        }

        // 2. If xmp:Rating="..." attribute exists, replace it
        if (RATING_ATTR_REGEX.containsMatchIn(existingXmp)) {
            return existingXmp.replace(RATING_ATTR_REGEX, "$1$rating$3")
        }

        // 3. If <rdf:Description exists, inject inside it
        val descIndex = existingXmp.indexOf("<rdf:Description")
        if (descIndex != -1) {
            val closeIndex = existingXmp.indexOf("</rdf:Description>", descIndex)
            if (closeIndex != -1) {
                val descTagEnd = existingXmp.indexOf(">", descIndex)
                val hasXmpNs = existingXmp.substring(descIndex, descTagEnd).contains("xmlns:xmp")
                val ratingTag = if (hasXmpNs) {
                    "      <xmp:Rating>$rating</xmp:Rating>\n"
                } else {
                    "      <xmp:Rating xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">$rating</xmp:Rating>\n"
                }
                return StringBuilder(existingXmp)
                    .insert(closeIndex, ratingTag)
                    .toString()
            } else {
                // Self-closing <rdf:Description ... />
                val selfCloseIndex = existingXmp.indexOf("/>", descIndex)
                if (selfCloseIndex != -1) {
                    val before = existingXmp.substring(0, selfCloseIndex)
                    val after = existingXmp.substring(selfCloseIndex + 2)
                    return "$before>\n      <xmp:Rating xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">$rating</xmp:Rating>\n    </rdf:Description>$after"
                }
            }
        }

        // Fallback: create fresh packet
        return createXmpPacket(rating)
    }
}
