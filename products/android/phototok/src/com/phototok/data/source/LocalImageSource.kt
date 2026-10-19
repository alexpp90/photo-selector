package com.phototok.data.source

import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.photoselector.core.model.ExifData
import com.photoselector.core.preview.EmbeddedPreviewExtractor
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
import com.photoselector.saf.BundleAtomicity
import com.photoselector.saf.BundleNaming
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselector.saf.MarkerTiming
import com.photoselector.saf.SafStorage
import com.photoselector.saf.SafTransferEngine
import com.photoselector.saf.SafTransferPolicy
import com.photoselector.saf.SelectionDestination
import com.photoselector.saf.SelectionSubfolder as SafSubfolder
import com.photoselector.saf.SelectionSubfolderRule
import com.phototok.data.model.ImageItem
import com.phototok.domain.CompanionFiles
import com.phototok.domain.PhotoExtensions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Local (SAF / DocumentFile) image backend. */
interface LocalImageSource : ImageSource

@Singleton
class LocalImageSourceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val androidExifReader: AndroidExifReader,
    private val mediaStoreReader: MediaStoreReader,
    private val folderAccessManager: FolderAccessManager,
) : LocalImageSource {

    constructor(
        context: Context,
        androidExifReader: AndroidExifReader,
        mediaStoreReader: MediaStoreReader,
    ) : this(
        context = context,
        androidExifReader = androidExifReader,
        mediaStoreReader = mediaStoreReader,
        folderAccessManager = FolderAccessManager(context),
    )

    enum class SelectionSubfolder { RAW, JPEG, ROOT }

    companion object {
        private const val TAG = "LocalImageSource"
        /** Dropped into every Selection folder so scans skip it and a card is recognised as culled. */
        const val SELECTION_MARKER_FILE = SafTransferEngine.SELECTION_MARKER_FILE_NAME

        internal val EXCLUDED_FOLDER_NAMES = setOf(
            "selection",
            "selected",
            "phototok_selection",
            "phototok_leftswipe",
            ".photoselector-trash",
        )

        /**
         * How many images to gather before the very first emission. Small on
         * purpose: it is the number of photos the user needs before they can
         * start swiping, and the rest of the tree keeps streaming in behind
         * them. Must be > the viewer's prefetch radius so paging stays warm.
         */
        internal const val FIRST_BATCH_SIZE = 24

        /** How many further images to gather between subsequent emissions. */
        internal const val BATCH_SIZE = 250

        /**
         * One query per directory returning everything needed to build an
         * [ImageItem]. `DocumentFile` instead costs a separate ContentResolver
         * query per file *per attribute* (`name`, `length`, `lastModified`,
         * `type`), i.e. ~5 binder round-trips per photo — which is what made
         * opening a folder of a few thousand photos take tens of seconds.
         */
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * Determine the selection subfolder key (`RAW`, `JPEG`, or `ROOT`) for
         * [fileName] according to [PhotoExtensions] and companion derivative rules.
         */
        internal fun selectionSubfolder(fileName: String): SelectionSubfolder {
            val extension = PhotoExtensions.extensionOf(fileName)
            val stem = fileName.substringBeforeLast('.')
            return when {
                extension in PhotoExtensions.RAW -> SelectionSubfolder.RAW
                extension in PhotoExtensions.JPEG -> SelectionSubfolder.JPEG
                BundleNaming.DERIVATIVE_SUFFIXES.any { stem.endsWith(it, ignoreCase = true) } -> SelectionSubfolder.RAW
                extension == PhotoExtensions.XMP -> {
                    val dotIndex = stem.lastIndexOf('.')
                    val parentExt = if (dotIndex > 0) stem.substring(dotIndex + 1).lowercase() else null
                    if (parentExt != null && parentExt in PhotoExtensions.RAW) {
                        SelectionSubfolder.RAW
                    } else {
                        SelectionSubfolder.ROOT
                    }
                }
                else -> SelectionSubfolder.ROOT
            }
        }

        /** PhotoTok's sorting rule ([selectionSubfolder]) for the shared engine. */
        internal val SUBFOLDER_RULE = SelectionSubfolderRule { name, _ ->
            when (selectionSubfolder(name)) {
                SelectionSubfolder.RAW -> SafSubfolder.RAW
                SelectionSubfolder.JPEG -> SafSubfolder.JPEG
                SelectionSubfolder.ROOT -> SafSubfolder.ROOT
            }
        }

        /**
         * PhotoTok's transfer semantics (`docs/shared/FILE_OPERATIONS.md`): each file of a
         * bundle is transferred on its own under the bundle's shared name, the Selection marker
         * is written as soon as the folder exists, and copies onto removable media are fsynced.
         */
        internal val TRANSFER_POLICY = SafTransferPolicy(
            bundleAtomicity = BundleAtomicity.PER_FILE,
            markerTiming = MarkerTiming.WHEN_FOLDER_RESOLVED,
            syncDestination = { SourceKindClassifier.classify(it) == SourceKind.REMOVABLE },
        )
    }

    /** The shared SAF engine (`:saf`), configured for PhotoTok. */
    internal val engine = SafTransferEngine(SUBFOLDER_RULE, TRANSFER_POLICY)
    private val storage = SafStorage.of(context)

    /** Dispatcher for the source's own I/O; setting it also sets the engine's (tests). */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        set(value) {
            field = value
            engine.ioDispatcher = value
        }

    // ── ImageSource ───────────────────────────────────────────────────────

    /** The local source owns every URI no other source claims (content://, file://). */
    override fun owns(uri: Uri): Boolean =
        uri.scheme == "content" || uri.scheme == "file"

    /**
     * Walk the tree and emit the feed **progressively**: a first small batch as
     * soon as [FIRST_BATCH_SIZE] photos are known, then every [BATCH_SIZE]
     * photos, then a final complete list. Each emission is the cumulative list.
     *
     * The user can therefore start swiping while a folder with thousands of
     * photos is still being enumerated, instead of waiting for the whole tree.
     * Consumers must treat the emissions as append-only (see
     * `PhoneFeedOrdering.appendBatch`) so already-visible photos do not move.
     */
    override fun discoverImages(
        folderUri: Uri,
        excludedFolderNames: Set<String>,
        shuffleFirstBatch: Boolean,
    ): Flow<List<ImageItem>> = flow {
        val rootDocumentId = treeDocumentIdOf(folderUri)
        if (rootDocumentId == null) {
            Log.w(TAG, "Invalid folder URI: $folderUri")
            emit(emptyList())
            return@flow
        }

        val images = mutableListOf<ImageItem>()
        var nextEmitAt = if (shuffleFirstBatch) Int.MAX_VALUE else FIRST_BATCH_SIZE

        walkImages(
            treeUri = folderUri,
            startDocumentId = rootDocumentId,
            extraExcludedFolderNames = excludedFolderNames,
            skipSelectionMarkerDirs = true,
            onFirstDirectoryComplete = if (shuffleFirstBatch) {
                {
                    if (images.isNotEmpty()) {
                        emit(images.toList())
                    }
                    nextEmitAt = images.size + BATCH_SIZE
                }
            } else {
                null
            },
        ) { image ->
            images.add(image)
            if (images.size >= nextEmitAt) {
                emit(images.toList())
                nextEmitAt = images.size + BATCH_SIZE
            }
        }

        // Final snapshot — also the only emission for folders smaller than a batch.
        emit(images.toList())
    }.flowOn(Dispatchers.IO)

    override suspend fun prepareSourceFolder(folderUri: Uri): String? =
        withContext(ioDispatcher) {
            folderAccessManager.takeGrant(
                uri = folderUri,
                purpose = FolderAccessPurpose.SOURCE,
                fallbackContext = context,
            )
            val folderDoc = try {
                DocumentFile.fromTreeUri(context, folderUri)?.takeIf { it.exists() }
            } catch (e: SecurityException) {
                null
            }
            folderDoc?.let { it.name ?: "Photos" }
        }

    override suspend fun resolveFolderName(folderUri: Uri): String? =
        withContext(ioDispatcher) {
            try {
                DocumentFile.fromTreeUri(context, folderUri)?.name
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun getExifData(uri: Uri): ExifData? {
        val exifData = androidExifReader.readExif(context, uri)
        if (exifData != null) return exifData
        return mediaStoreReader.readExif(context, uri)
    }

    override suspend fun getImageDimensions(uri: Uri): Pair<Int, Int> =
        withContext(ioDispatcher) { readImageDimensions(uri) }

    override suspend fun deleteImage(uri: Uri): Boolean = engine.delete(storage, uri)

    override suspend fun copyImage(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): Boolean = copyImageWithResult(sourceUri, destFolderUri, sorting, subfolderName).success

    override suspend fun moveImage(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): Boolean = moveImageWithResult(sourceUri, destFolderUri, sorting, subfolderName).success

    override suspend fun copyImageDetailed(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult = copyImageWithResult(sourceUri, destFolderUri, sorting, subfolderName)

    override suspend fun moveImageDetailed(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult = moveImageWithResult(sourceUri, destFolderUri, sorting, subfolderName)

    internal suspend fun copyImageWithResult(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult = engine.transferSingle(
        storage = storage,
        sourceUri = sourceUri,
        destination = destination(destFolderUri, subfolderName),
        sorting = sorting,
        isMove = false,
    )

    internal suspend fun moveImageWithResult(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult = engine.transferSingle(
        storage = storage,
        sourceUri = sourceUri,
        destination = destination(destFolderUri, subfolderName),
        sorting = sorting,
        isMove = true,
        unresolvedSourceError = "Copy step failed", // ui-text: ignore diagnostic code, never shown
    )

    /**
     * Transfer a companion bundle (primary + RAW/JPEG twin + XMP sidecars + `-Edit`
     * derivatives) into the [subfolderName] folder of [destFolderUri] with one collision
     * suffix across the whole bundle, so pairing is never broken. File Sorting adds `RAW/` and
     * `JPEG/` inside it (REQ-TOK-FEATURE.05).
     */
    internal suspend fun transferBundle(
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
        isMove: Boolean,
    ): List<FileOperationResult> = engine.transferBundle(
        storage = storage,
        sourceUris = sourceUris,
        destination = destination(destFolderUri, subfolderName),
        sorting = sorting,
        isMove = isMove,
    )

    override suspend fun transferBundleDetailed(
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
        isMove: Boolean,
    ): List<FileOperationResult> = transferBundle(sourceUris, destFolderUri, sorting, subfolderName, isMove)

    /** The output folder: [subfolderName] (`Selection`, `PhotoTok_LeftSwipe`) inside [destFolderUri]. */
    private fun destination(destFolderUri: Uri, subfolderName: String) =
        SelectionDestination(destFolderUri, subfolderName.trim().ifEmpty { "Selection" })

    override suspend fun listSelectionImages(
        folderUri: Uri,
        subfolderName: String,
    ): SelectionListing = withContext(ioDispatcher) {
        val selectionDir = try {
            DocumentFile.fromTreeUri(context, folderUri)?.findFile(subfolderName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (selectionDir == null || !selectionDir.exists()) {
            return@withContext SelectionListing.Missing
        }
        // Walk the sub-folder directly: passing a child document URI through
        // discoverImages() would re-resolve to the tree root via fromTreeUri().
        val selectionDocumentId = documentIdOf(selectionDir.uri)
            ?: return@withContext SelectionListing.Missing
        val images = mutableListOf<ImageItem>()
        walkImages(folderUri, selectionDocumentId) { images.add(it) }
        SelectionListing.Available(
            images = images.sortedByDescending { it.lastModified },
            folderName = selectionDir.name ?: "Selection",
        )
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Read width and height from image header only (no pixel decode).
     * Returns (0, 0) if the dimensions cannot be determined.
     */
    private fun readImageDimensions(uri: Uri): Pair<Int, Int> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
            val w = opts.outWidth.coerceAtLeast(0)
            val h = opts.outHeight.coerceAtLeast(0)
            if (w > 0 && h > 0) Pair(w, h) else embeddedPreviewDimensions(uri)
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read dimensions for $uri: ${e.message}")
            Pair(0, 0)
        }
    }

    /**
     * Dimensions of a RAW the platform cannot size (e.g. CR3), from its largest
     * embedded preview — header parsing only, the preview is not decoded.
     * Orientations 5–8 are rotated by 90°, so width/height are swapped.
     */
    private fun embeddedPreviewDimensions(uri: Uri): Pair<Int, Int> = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            // The stream is not closed on purpose: the descriptor belongs to `pfd`.
            val preview = EmbeddedPreviewExtractor.find(FileInputStream(pfd.fileDescriptor).channel, minLongEdge = Int.MAX_VALUE)
            when {
                preview == null -> Pair(0, 0)
                preview.orientation in 5..8 -> Pair(preview.height, preview.width)
                else -> Pair(preview.width, preview.height)
            }
        } ?: Pair(0, 0)
    } catch (e: Exception) {
        Pair(0, 0)
    }

    /** The tree document id of a `content://…/tree/<id>` URI, or null. */
    private fun treeDocumentIdOf(treeUri: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        Log.w(TAG, "Not a tree URI: $treeUri", e)
        null
    }

    /** The document id of a document URI inside a tree, or null. */
    private fun documentIdOf(documentUri: Uri): String? = try {
        DocumentsContract.getDocumentId(documentUri)
    } catch (e: Exception) {
        Log.w(TAG, "Not a document URI: $documentUri", e)
        null
    }

    /**
     * Breadth-first walk of [treeUri] starting at [startDocumentId], invoking
     * [onImage] for every supported image found. Sub-folders whose name is in
     * [EXCLUDED_FOLDER_NAMES] are skipped, as are hidden files.
     *
     * Iterative rather than recursive (deep trees must not risk the stack) and
     * cursor-based rather than `DocumentFile`-based, which is what makes it
     * fast enough to stream: one query per directory instead of roughly five
     * per file. [onImage] is suspending so callers can emit mid-walk.
     */
    private suspend fun walkImages(
        treeUri: Uri,
        startDocumentId: String,
        extraExcludedFolderNames: Set<String> = emptySet(),
        skipSelectionMarkerDirs: Boolean = true,
        onFirstDirectoryComplete: (suspend () -> Unit)? = null,
        onImage: suspend (ImageItem) -> Unit,
    ) {
        val excludedLower = if (extraExcludedFolderNames.isEmpty()) {
            EXCLUDED_FOLDER_NAMES
        } else {
            EXCLUDED_FOLDER_NAMES + extraExcludedFolderNames.mapNotNull {
                it.trim().lowercase().takeIf { s -> s.isNotEmpty() }
            }
        }
        val queue = ArrayDeque<String>()
        val seenDirectories = mutableSetOf(startDocumentId)
        queue.addLast(startDocumentId)
        var firstDirectoryDone = false

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val parentId = queue.removeFirst()
            val cursor = queryChildren(treeUri, parentId)
            if (cursor == null) {
                if (!firstDirectoryDone) {
                    firstDirectoryDone = true
                    onFirstDirectoryComplete?.invoke()
                }
                continue
            }

            val dirsInParent = mutableListOf<String>()
            val imagesInParent = mutableListOf<ImageItem>()
            // `.xmp` sidecars seen in this very listing: kept per directory, never fed.
            val sidecarsInParent = mutableListOf<ImageItem>()
            var hasSelectionMarker = false

            cursor.use { c ->
                val idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    Log.w(TAG, "Document provider omitted required columns for $parentId")
                    return@use
                }

                while (c.moveToNext()) {
                    coroutineContext.ensureActive()
                    val documentId = c.getStringOrNull(idIndex) ?: continue
                    val name = c.getStringOrNull(nameIndex) ?: continue
                    val mimeType = c.getStringOrNull(mimeIndex)

                    if (name.equals(SELECTION_MARKER_FILE, ignoreCase = true)) {
                        hasSelectionMarker = true
                        continue
                    }

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name.lowercase() in excludedLower) {
                            Log.d(TAG, "Skipping excluded folder: $name")
                            continue
                        }
                        dirsInParent.add(documentId)
                        continue
                    }

                    if (name.startsWith(".")) continue
                    val isSidecar = CompanionFiles.isSidecar(name)
                    if (!isSidecar && !PhotoExtensions.isSupported(name)) continue

                    val parentUriString = DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, parentId)
                        .toString()

                    (if (isSidecar) sidecarsInParent else imagesInParent).add(
                        ImageItem(
                            uri = DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, documentId)
                                .toString(),
                            fileName = name,
                            fileSize = if (sizeIndex >= 0) c.getLongOrZero(sizeIndex) else 0L,
                            lastModified = if (modifiedIndex >= 0) c.getLongOrZero(modifiedIndex) else 0L,
                            mimeType = mimeType,
                            imageWidth = 0,
                            imageHeight = 0,
                            parentUri = parentUriString,
                        )
                    )
                }
            }

            // A folder that holds the Selection marker is an output folder, not a source.
            if (skipSelectionMarkerDirs && hasSelectionMarker && parentId != startDocumentId) {
                Log.d(TAG, "Skipping directory containing $SELECTION_MARKER_FILE: $parentId")
                continue
            }

            for (dirId in dirsInParent) {
                if (seenDirectories.add(dirId)) {
                    queue.addLast(dirId)
                }
            }
            for (img in CompanionFiles.attachSidecars(imagesInParent, sidecarsInParent)) {
                onImage(img)
            }
            if (!firstDirectoryDone) {
                firstDirectoryDone = true
                onFirstDirectoryComplete?.invoke()
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
            val sortOrder = "${DocumentsContract.Document.COLUMN_LAST_MODIFIED} ASC"
            context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, sortOrder)
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
