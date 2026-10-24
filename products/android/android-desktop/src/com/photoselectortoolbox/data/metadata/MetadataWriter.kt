package com.photoselectortoolbox.data.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
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
 * Non-destructive metadata write-back engine for ratings (REQ-AND-RATING.01). [RatingFormats]
 * decides per format, for file paths and SAF documents alike:
 *
 * - JPEG: updates the embedded XMP via [ExifInterface] on a temporary copy, verifies the copy's
 *   image bounds decode via [BitmapFactory], and only then replaces the original (atomically for
 *   files; for SAF documents see [SafRatingWriter]). A failed step fails the write — it never
 *   turns into a sidecar.
 * - RAW and TIFF: creates or updates the companion XMP sidecar (`<file>.xmp`, else an existing
 *   `<stem>.xmp`) with ISO 16684-1 `<xmp:Rating>`; the image itself is never opened for writing.
 * - Every other format: fails with a message; no sidecar is invented.
 * - Per-URI serialization: concurrent writes to the same file or URI are serialized via a [Mutex].
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
                try {
                    SafRatingWriter(context, validateJpegBounds).write(imageUri, rating)
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "SAF rating write failed for $imageUri", e)
                    Result.failure(e)
                }
            }
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

                    when (RatingFormats.strategyFor(imageFile.name)) {
                        RatingStrategy.SIDECAR -> writeSidecarRating(imageFile, rating)
                        RatingStrategy.EMBEDDED_XMP -> writeJpegRating(imageFile, rating)
                        RatingStrategy.UNSUPPORTED -> throw RatingWriteException(
                            RatingFormats.unsupportedMessage(imageFile.name)
                        )
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

        if (!XmpSidecarWriter.canUpdateInPlace(existingContent)) {
            throw RatingWriteException("${targetSidecar.name} has XMP content the rating cannot be merged into; it was left unchanged")
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

    /**
     * True when [injectOrUpdateRating] can put a rating into [existingXmp] while keeping its
     * content. When false (no `<rdf:Description>` and no rating to replace) the function would
     * start a fresh packet and drop what was there, so callers must fail instead of writing.
     */
    fun canUpdateInPlace(existingXmp: String?): Boolean =
        existingXmp.isNullOrBlank() ||
            RATING_TAG_REGEX.containsMatchIn(existingXmp) ||
            RATING_ATTR_REGEX.containsMatchIn(existingXmp) ||
            existingXmp.contains("<rdf:Description")

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
