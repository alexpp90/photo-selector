package com.photoselectortoolbox.data.source

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.di.IoDispatcher
import com.photoselectortoolbox.domain.format.ImageFormats
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

interface LocalImageSource {
    /**
     * Walk the tree and emit the folder **progressively**: a small first batch,
     * then fixed-size batches, then a final complete snapshot. Every emission is
     * the cumulative list, and emissions are append-only — see
     * [LocalImageSourceImpl.discoverImages] for why.
     */
    fun discoverImages(folderUri: Uri): Flow<List<ImageItem>>

    /**
     * Header-only dimensions for a set of images, keyed by URI string.
     *
     * This is the *only* place dimensions are read now: enumeration no longer
     * touches image content. Drive it for the visible range first and backfill
     * the rest in the background — each URI is read at most once per process,
     * repeated calls are served from memory.
     */
    suspend fun resolveDimensions(uris: Collection<String>): Map<String, ImageDimensions>
}

@Singleton
class LocalImageSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LocalImageSource {

    private val bitmapDecoder = PhotoBitmapDecoder(context)

    companion object {
        private const val TAG = "LocalImageSource"

        const val SELECTION_MARKER_FILE_NAME = ".photoselector-selection"
        private val EXCLUDED_FOLDER_NAMES = setOf("selection", "selected", "final", "phototok_selection", "phototok_leftswipe")

        /**
         * Determine whether a directory should be skipped during recursive traversal (`U-32`).
         *
         * The root directory opened by the user ([isRootDirectory] = true) is never skipped
         * so a user can open `Selection/` directly for a second-pass cull. Any nested child
         * directory ([isRootDirectory] = false) is skipped when it contains
         * [SELECTION_MARKER_FILE_NAME] or matches [EXCLUDED_FOLDER_NAMES] / [customExcludedNames].
         */
        internal fun shouldSkipDirectory(
            dirName: String,
            isRootDirectory: Boolean,
            hasMarkerFile: Boolean,
            customExcludedNames: Set<String> = emptySet(),
        ): Boolean {
            if (isRootDirectory) return false
            if (hasMarkerFile) return true
            val lower = dirName.lowercase()
            if (lower in EXCLUDED_FOLDER_NAMES) return true
            return customExcludedNames.any { it.equals(dirName, ignoreCase = true) }
        }

        /**
         * How many images to gather before the very first emission.
         *
         * Small on purpose: it is the number of frames the selector needs before
         * a photographer can start culling — the current frame, its two
         * neighbours, and enough filmstrip either side to scrub — while the rest
         * of the tree keeps streaming in behind them. Anything larger simply
         * makes the first photograph appear later. It must stay comfortably
         * above the UI's neighbour-prefetch radius so paging is never starved.
         */
        internal const val FIRST_BATCH_SIZE = 24

        /**
         * How many further images to gather between subsequent emissions.
         *
         * Larger than the first batch because each emission costs a full list
         * copy plus a recomposition of the filmstrip, and by this point the user
         * is already looking at a photograph rather than at a spinner.
         */
        internal const val BATCH_SIZE = 250

        /**
         * One query per directory returning everything an [ImageItem] needs.
         *
         * `DocumentFile.listFiles()` plus `name`/`length()`/`lastModified()`/
         * `type` costs a separate `ContentResolver` query *per attribute per
         * file* — roughly five binder round-trips per photograph, which is what
         * made opening a large folder take tens of seconds (see the 2026-07-31
         * entry in `ai/memory/bolt.md`).
         */
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * Upper bound on the in-memory dimension cache.
         *
         * Each entry is two ints and a string key, so this is tens of kilobytes
         * for a very large folder — cheap next to re-opening an input stream and
         * decoding a header for a frame the user scrubs past repeatedly.
         */
        private const val DIMENSION_CACHE_LIMIT = 20_000
    }

    /**
     * URI → dimensions, including negative results.
     *
     * Caching failures matters as much as caching successes: a file whose header
     * cannot be parsed must not be re-opened every time the filmstrip scrolls
     * past it. Access-ordered with a hard cap so a session over several huge
     * folders cannot grow without bound.
     */
    private val dimensionCache = object : LinkedHashMap<String, ImageDimensions>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageDimensions>?
        ): Boolean = size > DIMENSION_CACHE_LIMIT
    }

    /**
     * Walk [folderUri] and emit the discovered images progressively.
     *
     * The first emission lands after [FIRST_BATCH_SIZE] photographs are known,
     * then one per [BATCH_SIZE], then a final complete snapshot (which is also
     * the only emission for a folder smaller than the first batch). Opening a
     * folder of several thousand photographs therefore shows the first frame
     * almost immediately instead of after the whole tree has been read.
     *
     * **Sorting is per batch, then appended.** The previous implementation
     * sorted the single terminal emission by filename; a progressive feed cannot
     * do that, because re-sorting a list the user is already looking at moves
     * photographs out from under them mid-cull. Each batch is sorted internally
     * and appended to what is already published, so nothing that has been seen
     * ever changes position. Consumers must merge append-only for the same
     * reason.
     */
    override fun discoverImages(folderUri: Uri): Flow<List<ImageItem>> = flow {
        val rootDocumentId = treeDocumentIdOf(folderUri)
        if (rootDocumentId == null) {
            Log.w(TAG, "Invalid folder URI: $folderUri")
            emit(emptyList())
            return@flow
        }

        val published = mutableListOf<ImageItem>()
        val pending = mutableListOf<ImageItem>()
        var nextBatchSize = FIRST_BATCH_SIZE

        walkImages(folderUri, rootDocumentId) { image ->
            pending.add(image)
            if (pending.size >= nextBatchSize) {
                published.addAll(pending.sortedBy { it.fileName.lowercase() })
                pending.clear()
                nextBatchSize = BATCH_SIZE
                emit(published.toList())
            }
        }

        if (pending.isNotEmpty()) {
            published.addAll(pending.sortedBy { it.fileName.lowercase() })
        }
        // Final snapshot — always emitted, so a consumer that only reads the
        // last value (statistics, duplicates) still sees the complete folder.
        emit(published.toList())
    }.flowOn(ioDispatcher)

    override suspend fun resolveDimensions(
        uris: Collection<String>
    ): Map<String, ImageDimensions> = withContext(ioDispatcher) {
        val resolved = LinkedHashMap<String, ImageDimensions>(uris.size)
        for (uri in uris) {
            coroutineContext.ensureActive()
            resolved[uri] = cachedOrRead(uri)
        }
        resolved
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Memoised header read. Each URI is opened at most once per process. */
    private fun cachedOrRead(uri: String): ImageDimensions {
        synchronized(dimensionCache) { dimensionCache[uri] }?.let { return it }
        val dimensions = readImageDimensions(Uri.parse(uri))
        synchronized(dimensionCache) { dimensionCache[uri] = dimensions }
        return dimensions
    }

    /**
     * Read width and height from the image header only (no pixel decode).
     * Returns [ImageDimensions.UNKNOWN] if they cannot be determined.
     */
    private fun readImageDimensions(uri: Uri): ImageDimensions {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            val dimensions = ImageDimensions.of(opts.outWidth, opts.outHeight)
            if (dimensions != ImageDimensions.UNKNOWN) {
                dimensions
            } else {
                // No platform codec (CR3 has none anywhere): size it from the
                // embedded preview's frame header — still header-only reads.
                bitmapDecoder.containerDimensions(uri, uri.lastPathSegment.orEmpty())
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read dimensions for $uri: ${e.message}")
            ImageDimensions.UNKNOWN
        }
    }

    /** The tree document id of a `content://…/tree/<id>` URI, or null. */
    private fun treeDocumentIdOf(treeUri: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        Log.w(TAG, "Not a tree URI: $treeUri", e)
        null
    }

    /**
     * Breadth-first walk of [treeUri] from [startDocumentId], invoking [onImage]
     * for every supported image found. Sub-folders named in
     * [EXCLUDED_FOLDER_NAMES] are skipped case-insensitively, as are hidden
     * files.
     *
     * Iterative rather than recursive: a deeply nested export tree must not risk
     * the stack. Cursor-based rather than `DocumentFile`-based: that is what
     * makes it fast enough to stream. [onImage] suspends so the caller can emit
     * mid-walk, and the loop is guarded by `ensureActive()` so closing the
     * folder stops the walk at the next document rather than at the next
     * directory. A directory that cannot be listed is logged and skipped — one
     * unreadable sub-folder must never abort a folder open.
     */
    private suspend fun walkImages(
        treeUri: Uri,
        startDocumentId: String,
        onImage: suspend (ImageItem) -> Unit,
    ) {
        val queue = ArrayDeque<String>()
        val seenDirectories = mutableSetOf(startDocumentId)
        queue.addLast(startDocumentId)

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val parentId = queue.removeFirst()
            val isRootDir = parentId == startDocumentId
            val cursor = queryChildren(treeUri, parentId) ?: continue
            val directoryEntries = mutableListOf<RawDirectoryEntry>()
            val childDirectories = mutableListOf<Pair<String, String>>()
            var hasMarkerFile = false

            cursor.use { c ->
                val idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex =
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    Log.w(TAG, "Document provider omitted required columns for $parentId")
                    return@use
                }

                while (c.moveToNext()) {
                    coroutineContext.ensureActive()
                    val documentId = c.getStringOrNull(idIndex) ?: continue
                    val name = c.getStringOrNull(nameIndex) ?: continue
                    val mimeType = c.getStringOrNull(mimeIndex)

                    if (name == SELECTION_MARKER_FILE_NAME) {
                        hasMarkerFile = true
                        continue
                    }

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        childDirectories.add(documentId to name)
                        continue
                    }

                    if (name.startsWith(".")) continue
                    if (!ImageFormats.isCompanionOrSupported(name)) continue

                    directoryEntries.add(
                        RawDirectoryEntry(
                            uri = DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, documentId)
                                .toString(),
                            fileName = name,
                            fileSize = if (sizeIndex >= 0) c.getLongOrZero(sizeIndex) else 0L,
                            lastModified = if (modifiedIndex >= 0) {
                                c.getLongOrZero(modifiedIndex)
                            } else {
                                0L
                            },
                            mimeType = mimeType,
                        )
                    )
                }
            }

            if (shouldSkipDirectory(
                    dirName = parentId.substringAfterLast('/'),
                    isRootDirectory = isRootDir,
                    hasMarkerFile = hasMarkerFile,
                )
            ) {
                Log.d(TAG, "Skipping selection directory with marker file: $parentId")
                continue
            }

            for ((childDocId, childDirName) in childDirectories) {
                if (shouldSkipDirectory(
                        dirName = childDirName,
                        isRootDirectory = false,
                        hasMarkerFile = false,
                    )
                ) {
                    Log.d(TAG, "Skipping excluded folder: $childDirName")
                    continue
                }
                if (seenDirectories.add(childDocId)) queue.addLast(childDocId)
            }

            for (item in groupCompanionsInDirectory(directoryEntries, parentId)) {
                coroutineContext.ensureActive()
                onImage(item)
            }
        }
    }

    /** Children of [parentDocumentId] with everything needed for an [ImageItem]. */
    private fun queryChildren(treeUri: Uri, parentDocumentId: String): Cursor? {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot build children URI for $parentDocumentId", e)
            return null
        }
        return try {
            context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
        } catch (e: Exception) {
            // A single unreadable directory must not abort the whole walk.
            Log.w(TAG, "Cannot list children of $parentDocumentId", e)
            null
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun Cursor.getLongOrZero(index: Int): Long =
        if (isNull(index)) 0L else getLong(index)
}

/**
 * Unprocessed file entry read from a single SAF directory cursor before
 * companion grouping.
 */
internal data class RawDirectoryEntry(
    val uri: String,
    val fileName: String,
    val fileSize: Long = 0L,
    val lastModified: Long = 0L,
    val mimeType: String? = null,
)

/**
 * Groups files from a single directory listing ([parentDocumentId]) by their
 * canonical shot stem (`IMG_0001.CR3`, `IMG_0001.JPG`, `IMG_0001.CR3.xmp`,
 * `IMG_0001-Edit.tif` → one [ImageItem]).
 *
 * - Standalone `.xmp` files with no matching supported image in the same
 *   directory are ignored.
 * - When both a processed image (JPEG/HEIC/PNG/TIFF) and a RAW exist for the
 *   same shot stem, the processed non-`-Edit` image is selected as the primary
 *   [ImageItem] so the UI decodes and displays it rapidly, while the RAW twin,
 *   `.xmp` sidecars, and `-Edit` derivatives are attached in [ImageItem.companions].
 */
internal fun groupCompanionsInDirectory(
    entries: List<RawDirectoryEntry>,
    parentDocumentId: String?,
): List<ImageItem> {
    if (entries.isEmpty()) return emptyList()

    val byStem = LinkedHashMap<String, MutableList<RawDirectoryEntry>>()
    for (entry in entries) {
        if (!ImageFormats.isCompanionOrSupported(entry.fileName)) continue
        val stem = ImageFormats.shotStemOf(entry.fileName)
        if (stem.isEmpty()) continue
        byStem.getOrPut(stem) { mutableListOf() }.add(entry)
    }

    val result = ArrayList<ImageItem>(byStem.size)
    for (group in byStem.values) {
        val supportedImages = group.filter { ImageFormats.isSupported(it.fileName) }
        if (supportedImages.isEmpty()) {
            // Orphan .xmp sidecar(s) with no matching photo in this directory.
            continue
        }

        val primary = supportedImages.minWith(
            compareBy<RawDirectoryEntry> { ImageFormats.primaryRank(it.fileName) }
                .thenBy { it.fileName.lowercase() }
        )

        val companions = group
            .filter { it !== primary }
            .sortedBy { it.fileName.lowercase() }
            .map {
                CompanionFile(
                    uri = it.uri,
                    fileName = it.fileName,
                    fileSize = it.fileSize,
                    lastModified = it.lastModified,
                    mimeType = it.mimeType,
                )
            }

        result.add(
            ImageItem(
                uri = primary.uri,
                fileName = primary.fileName,
                fileSize = primary.fileSize,
                lastModified = primary.lastModified,
                mimeType = primary.mimeType,
                // Deliberately unresolved: reading a header here is
                // an extra stream open per photograph, which is the
                // difference between a folder opening in a second
                // and in a minute. See resolveDimensions().
                imageWidth = 0,
                imageHeight = 0,
                companions = companions,
                parentDocumentId = parentDocumentId,
            )
        )
    }
    return result
}
