package com.photoselectortoolbox.data.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.photoselectortoolbox.di.IoDispatcher
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Non-destructive metadata write-back engine for ratings (REQ-AND-RATING.01). [RatingFormats]
 * decides per format, for file paths and SAF documents alike:
 *
 * - JPEG: updates the embedded XMP via [ExifInterface] on a temporary copy, verifies the copy's
 *   image bounds decode via [BitmapFactory], and only then replaces the original (atomically for
 *   files; for SAF documents behind a verified backup document, see [SafRatingWriter] and
 *   [RatingBackups]). A failed step fails the write — it never turns into a sidecar.
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

    /**
     * Resolves the backups that interrupted JPEG rating writes left under the opened tree
     * [folderUri] ([RatingBackups], finding FD-D-11): restores a cut-off photo, discards a backup
     * of a photo that was never touched, and keeps (and returns) the rest. Never throws; a folder
     * that cannot be read yields [RatingRecovery.NONE].
     */
    suspend fun recoverInterruptedWrites(context: Context, folderUri: Uri): RatingRecovery
}

@Singleton
class MetadataWriterImpl internal constructor(
    private val validateJpegBounds: (File) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
) : MetadataWriter {

    @Inject
    constructor(@IoDispatcher ioDispatcher: CoroutineDispatcher) : this(Companion::defaultValidateJpegBounds, ioDispatcher)

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
        withContext(ioDispatcher) {
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

    override suspend fun recoverInterruptedWrites(context: Context, folderUri: Uri): RatingRecovery =
        withContext(ioDispatcher) {
            try {
                SafRatingWriter(context, validateJpegBounds).recover(folderUri) { photoUri, block ->
                    mutexFor(photoUri.toString()).withLock { block() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Rating backup recovery failed for $folderUri", e)
                RatingRecovery.NONE
            }
        }

    override suspend fun writeRating(imageFile: File, rating: Int): Result<Unit> =
        withContext(ioDispatcher) {
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
                ?: throw RatingWriteException("The XMP inside ${imageFile.name} has content the rating cannot be merged into; it was left unchanged")

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
            ?: throw RatingWriteException("${targetSidecar.name} has XMP content the rating cannot be merged into; it was left unchanged")
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

/**
 * Puts a rating into an XMP packet (sidecar or embedded) while keeping everything else in it.
 *
 * Only the `Rating` of the XMP basic namespace ([XMP_NS]) is touched, under the prefix the packet
 * itself binds to it (`xmp:`, the older `xap:`, or any other; FD-D-12). A `Rating` in another
 * namespace — `MicrosoftPhoto:Rating`, which Windows reads on a 1–99 scale — is left exactly as it
 * is. An undeclared `xmp:` or `xap:` prefix (seen in hand-written sidecars) counts as the XMP
 * namespace unless the packet binds it to another one.
 */
object XmpSidecarWriter {

    const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

    private val XMP_NS_DECLARATION = Regex(
        "xmlns:([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*[\"']" + Regex.escape(XMP_NS) + "[\"']"
    )
    private const val NUMBER = "-?\\d+(?:\\.\\d+)?"

    fun createXmpPacket(rating: Int): String {
        return buildString {
            append("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
            append("    <rdf:Description rdf:about=\"\" xmlns:xmp=\"$XMP_NS\">\n")
            append("      <xmp:Rating>$rating</xmp:Rating>\n")
            append("    </rdf:Description>\n")
            append("  </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }
    }

    /**
     * True when [injectOrUpdateRating] can put a rating into [existingXmp] while keeping its
     * content — the same predicate, so a caller that checks this never gets a null back. When
     * false, callers must fail instead of writing.
     */
    fun canUpdateInPlace(existingXmp: String?): Boolean = injectOrUpdateRating(existingXmp, 0) != null

    /**
     * [existingXmp] with its XMP rating set to [rating], or a fresh packet when there is none.
     * Null when [existingXmp] has content the rating cannot be merged into (no rating of the XMP
     * namespace and no well-formed `<rdf:Description>`): replacing it would drop that content
     * (FD-D-13).
     */
    fun injectOrUpdateRating(existingXmp: String?, rating: Int): String? {
        if (existingXmp.isNullOrBlank()) {
            return createXmpPacket(rating)
        }
        val prefixes = xmpPrefixes(existingXmp)

        // 1. An element or an attribute rating under a prefix bound to the XMP namespace: replace it, keep the prefix.
        for (prefix in prefixes) {
            val p = Regex.escape(prefix)
            val element = Regex("<$p:Rating>\\s*$NUMBER\\s*</$p:Rating>")
            if (element.containsMatchIn(existingXmp)) {
                return existingXmp.replace(element, "<$prefix:Rating>$rating</$prefix:Rating>")
            }
            val attribute = Regex("(?<![A-Za-z0-9_.:-])($p:Rating\\s*=\\s*[\"'])$NUMBER([\"'])")
            if (attribute.containsMatchIn(existingXmp)) {
                return existingXmp.replace(attribute) { it.groupValues[1] + rating + it.groupValues[2] }
            }
        }

        // 2. Otherwise inject into the first <rdf:Description>.
        val descIndex = existingXmp.indexOf("<rdf:Description")
        if (descIndex == -1) return null
        val tagEnd = existingXmp.indexOf('>', descIndex)
        if (tagEnd == -1) return null
        val startTag = existingXmp.substring(descIndex, tagEnd)
        val declared = prefixes.firstOrNull { Regex("xmlns:${Regex.escape(it)}\\s*=").containsMatchIn(startTag) }
        val ratingElement = if (declared != null) {
            "<$declared:Rating>$rating</$declared:Rating>"
        } else {
            "<xmp:Rating xmlns:xmp=\"$XMP_NS\">$rating</xmp:Rating>"
        }
        if (startTag.endsWith("/")) {
            // Self-closing <rdf:Description … />: open it up around the rating.
            return existingXmp.substring(0, tagEnd - 1).trimEnd() + ">\n      " + ratingElement +
                "\n    </rdf:Description>" + existingXmp.substring(tagEnd + 1)
        }
        val closeIndex = existingXmp.indexOf("</rdf:Description>", tagEnd)
        if (closeIndex == -1) return null
        return StringBuilder(existingXmp).insert(closeIndex, "      $ratingElement\n").toString()
    }

    /** The prefixes [xmp] binds to [XMP_NS], plus `xmp` and `xap` where they are not bound to anything else. */
    private fun xmpPrefixes(xmp: String): List<String> {
        val declared = XMP_NS_DECLARATION.findAll(xmp).map { it.groupValues[1] }.toMutableList()
        for (conventional in listOf("xmp", "xap")) {
            if (conventional in declared) continue
            val boundElsewhere = Regex("xmlns:$conventional\\s*=").containsMatchIn(xmp)
            if (!boundElsewhere) declared += conventional
        }
        return declared.distinct()
    }
}
