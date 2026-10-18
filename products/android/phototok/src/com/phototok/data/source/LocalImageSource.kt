package com.phototok.data.source

import android.content.Context
import android.content.Intent
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
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.FolderAccessManager
import com.phototok.data.repository.FolderAccessPurpose
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Collaborator for atomic SAF moves (`DocumentsContract.moveDocument`),
 * extracted so unit tests can inspect or override the provider move decision.
 */
internal interface SafDocumentMover {
    fun canMoveDocument(
        context: Context,
        sourceUri: Uri,
        sourceParentUri: Uri?,
        targetFolderUri: Uri,
    ): Boolean

    fun moveDocument(
        context: Context,
        sourceUri: Uri,
        sourceParentUri: Uri,
        targetFolderUri: Uri,
    ): Uri?
}

internal object DefaultSafDocumentMover : SafDocumentMover {
    private const val TAG = "DefaultSafDocumentMover"

    override fun canMoveDocument(
        context: Context,
        sourceUri: Uri,
        sourceParentUri: Uri?,
        targetFolderUri: Uri,
    ): Boolean {
        if (sourceParentUri == null) return false
        val authority = sourceUri.authority ?: return false
        if (authority != targetFolderUri.authority) return false

        val flags = try {
            context.contentResolver.query(
                sourceUri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    cursor.getInt(idx)
                } else {
                    0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
        return (flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE) != 0
    }

    override fun moveDocument(
        context: Context,
        sourceUri: Uri,
        sourceParentUri: Uri,
        targetFolderUri: Uri,
    ): Uri? = try {
        DocumentsContract.moveDocument(
            context.contentResolver,
            sourceUri,
            sourceParentUri,
            targetFolderUri,
        )
    } catch (e: Exception) {
        Log.w(TAG, "DocumentsContract.moveDocument failed for $sourceUri, falling back to stream copy", e)
        null
    }
}

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
        private const val RAW_SUBFOLDER = "RAW"
        private const val JPEG_SUBFOLDER = "JPEG"
        private const val SINGLE_FOLDER_KEY = "TARGET"
        internal const val MAX_CONCURRENT_TRANSFERS = 2

        private val DERIVATIVE_SUFFIXES = listOf("-Edit", "_Edit", "-Enhanced-NR", "_Enhanced-NR")

        /** Dropped into every Selection folder so scans skip it and a card is recognised as culled. */
        const val SELECTION_MARKER_FILE = ".photoselector-selection"

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
                DERIVATIVE_SUFFIXES.any { stem.endsWith(it, ignoreCase = true) } -> SelectionSubfolder.RAW
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

        /**
         * Extract the shared base stem of a photo, sidecar or derivative so a
         * collision suffix (`-2`, `-3`, …) is inserted before `.ext`, `.RAW.xmp`
         * or `-Edit` / `_Edit` / `-Enhanced-NR`.
         */
        internal fun extractPrimaryStem(fileName: String): String {
            val name = fileName.substringAfterLast('/')
            val withoutXmp = if (name.endsWith(".xmp", ignoreCase = true) && name.length > 4) {
                name.dropLast(4)
            } else {
                name
            }
            val withoutExt = if (withoutXmp.contains('.')) {
                withoutXmp.substringBeforeLast('.')
            } else {
                withoutXmp
            }
            for (suffix in DERIVATIVE_SUFFIXES) {
                if (withoutExt.endsWith(suffix, ignoreCase = true) && withoutExt.length > suffix.length) {
                    return withoutExt.dropLast(suffix.length)
                }
            }
            return withoutExt
        }

        /**
         * Insert `-[suffixNumber]` into [fileName] after [primaryStem] so that:
         * - `IMG_0001.CR3` → `IMG_0001-2.CR3`
         * - `IMG_0001.JPG` → `IMG_0001-2.JPG`
         * - `IMG_0001.xmp` → `IMG_0001-2.xmp`
         * - `IMG_0001.CR3.xmp` → `IMG_0001-2.CR3.xmp`
         * - `IMG_0001-Edit.tif` → `IMG_0001-2-Edit.tif`
         * - `IMG_0001_Edit.tif` → `IMG_0001-2_Edit.tif`
         */
        internal fun suffixFileName(
            fileName: String,
            suffixNumber: Int,
            primaryStem: String = extractPrimaryStem(fileName),
        ): String {
            val name = fileName.substringAfterLast('/')
            val stemLen = if (
                primaryStem.isNotEmpty() &&
                name.startsWith(primaryStem, ignoreCase = true) &&
                (name.length == primaryStem.length || name[primaryStem.length] in charArrayOf('.', '-', '_'))
            ) {
                primaryStem.length
            } else {
                extractPrimaryStem(name).length
            }
            return name.substring(0, stemLen) + "-$suffixNumber" + name.substring(stemLen)
        }

        /**
         * Compute bundle-wide collision-free destination filenames (`-2`, `-3`, …).
         *
         * If any file in [fileNames] collides case-insensitively with an existing
         * name in its target folder ([existingNamesByFolder]), the entire bundle
         * receives the lowest integer $N \ge 2$ for which **every** member is
         * collision-free in its target folder.
         */
        internal fun resolveCollisionFreeBundleNames(
            fileNames: List<String>,
            primaryStem: String = fileNames.firstOrNull()?.let(::extractPrimaryStem).orEmpty(),
            existingNamesByFolder: Map<String, Set<String>>,
            folderForFile: (String) -> String = { selectionSubfolder(it).name },
        ): Map<String, String> {
            if (fileNames.isEmpty()) return emptyMap()

            val lowercaseByFolder: Map<String, Set<String>> = existingNamesByFolder.mapValues { (_, names) ->
                names.mapTo(HashSet(names.size)) { it.lowercase() }
            }

            val anyCollision = fileNames.any { name ->
                val folder = folderForFile(name)
                name.lowercase() in lowercaseByFolder[folder].orEmpty()
            }
            if (!anyCollision) {
                return fileNames.associateWith { it }
            }

            var n = 2
            while (true) {
                val candidateMap = LinkedHashMap<String, String>(fileNames.size)
                var allFree = true
                for (name in fileNames) {
                    val candidate = suffixFileName(name, n, primaryStem)
                    val folder = folderForFile(name)
                    if (candidate.lowercase() in lowercaseByFolder[folder].orEmpty()) {
                        allFree = false
                        break
                    }
                    candidateMap[name] = candidate
                }
                if (allFree) {
                    return candidateMap
                }
                n++
            }
        }
    }

    private val transferSemaphore = Semaphore(MAX_CONCURRENT_TRANSFERS)
    private val destinationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Two transfers into the same destination must not interleave "pick a free name -> create the
     * file" or "find the folder -> create it": the loser would overwrite the winner's file or leave
     * a second "Selection (1)" folder (finding D-5). One lock per destination folder.
     */
    private suspend fun <T> withDestinationLock(destFolderUri: Uri, block: suspend () -> T): T =
        destinationLocks.getOrPut(destFolderUri.toString()) { Mutex() }.withLock { block() }

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var documentMover: SafDocumentMover = DefaultSafDocumentMover

    private sealed class CopyStepResult {
        data class Success(val destinationUri: String) : CopyStepResult()
        data class Failure(val reason: String) : CopyStepResult()
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

    override suspend fun deleteImage(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            val docFile = DocumentFile.fromSingleUri(context, uri)
            docFile?.delete() ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete image: $uri", e)
            false
        }
    }

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
    ): FileOperationResult = transferSemaphore.withPermit {
        withDestinationLock(destFolderUri) {
        withContext(ioDispatcher) {
            val source = sourceUri.toString()
            try {
                moveOrCopySingle(
                    sourceUri = sourceUri,
                    destFolderUri = destFolderUri,
                    sorting = sorting,
                    subfolderName = subfolderName,
                    isMove = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy image: $sourceUri", e)
                FileOperationResult.failure(source, e.message)
            }
        }
        }
    }

    internal suspend fun moveImageWithResult(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult = transferSemaphore.withPermit {
        withDestinationLock(destFolderUri) {
        withContext(ioDispatcher) {
            val source = sourceUri.toString()
            try {
                moveOrCopySingle(
                    sourceUri = sourceUri,
                    destFolderUri = destFolderUri,
                    sorting = sorting,
                    subfolderName = subfolderName,
                    isMove = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move image: $sourceUri", e)
                FileOperationResult.failure(source, e.message)
            }
        }
        }
    }

    /**
     * Transfer a companion bundle (primary + RAW/JPEG twin + XMP sidecars +
     * `-Edit` derivatives) into [destFolderUri] using a single cursor query per
     * target folder and a shared collision suffix (`-2`, `-3`, …) across the
     * entire bundle so pairing is never broken.
     */
    internal suspend fun transferBundle(
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
        isMove: Boolean,
    ): List<FileOperationResult> = transferSemaphore.withPermit {
        withDestinationLock(destFolderUri) {
        withContext(ioDispatcher) {
            if (sourceUris.isEmpty()) return@withContext emptyList()
            val folderName = subfolderName.trim().ifEmpty { "Selection" }

            val destFolder = DocumentFile.fromTreeUri(context, destFolderUri)
                ?: return@withContext sourceUris.map { uri ->
                    FileOperationResult.failure(uri.toString(), "Could not write to the $folderName folder")
                }

            val selectionDir = if (sorting) {
                destFolder.findFile(folderName)
                    ?: destFolder.createDirectory(folderName)
                    ?: return@withContext sourceUris.map { uri ->
                        FileOperationResult.failure(uri.toString(), "Could not write to the $folderName folder")
                    }
            } else {
                destFolder
            }
            ensureSelectionMarker(selectionDir)

            data class ResolvedSource(
                val uri: Uri,
                val doc: DocumentFile?,
                val fileName: String?,
                val folderKey: String,
                val targetFolder: DocumentFile?,
            )

            val targetFoldersByKey = LinkedHashMap<String, DocumentFile>()
            val resolvedSources = sourceUris.map { uri ->
                val doc = DocumentFile.fromSingleUri(context, uri)
                val name = doc?.name
                if (name == null) {
                    ResolvedSource(uri, doc, null, SINGLE_FOLDER_KEY, null)
                } else if (sorting) {
                    val key = selectionSubfolder(name).name
                    val target = targetFoldersByKey.getOrPut(key) {
                        determineTargetFolder(name, selectionDir)
                    }
                    ResolvedSource(uri, doc, name, key, target)
                } else {
                    targetFoldersByKey.getOrPut(SINGLE_FOLDER_KEY) { destFolder }
                    ResolvedSource(uri, doc, name, SINGLE_FOLDER_KEY, destFolder)
                }
            }

            val existingNamesByFolder = targetFoldersByKey.mapValues { (_, folder) ->
                queryExistingDisplayNames(folder)
            }
            val validNames = resolvedSources.mapNotNull { it.fileName }
            val primaryStem = validNames.firstOrNull()?.let(::extractPrimaryStem).orEmpty()
            val folderKeyByName = resolvedSources
                .filter { it.fileName != null }
                .associate { it.fileName!! to it.folderKey }

            val desiredNames = resolveCollisionFreeBundleNames(
                fileNames = validNames,
                primaryStem = primaryStem,
                existingNamesByFolder = existingNamesByFolder,
                folderForFile = { folderKeyByName[it] ?: SINGLE_FOLDER_KEY },
            )

            resolvedSources.map { src ->
                val sourceStr = src.uri.toString()
                val doc = src.doc
                val name = src.fileName
                val targetFolder = src.targetFolder
                if (doc == null || name == null || targetFolder == null) {
                    FileOperationResult.failure(sourceStr, "Could not write to the $folderName folder")
                } else {
                    val desiredName = desiredNames[name] ?: name
                    executeSingleTransfer(
                        sourceUri = src.uri,
                        sourceDoc = doc,
                        fileName = name,
                        desiredName = desiredName,
                        destFolderUri = destFolderUri,
                        targetFolder = targetFolder,
                        selectionFolderName = folderName,
                        isMove = isMove,
                    )
                }
            }
        }
        }
    }

    override suspend fun transferBundleDetailed(
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
        isMove: Boolean,
    ): List<FileOperationResult> = try {
        transferBundle(sourceUris, destFolderUri, sorting, subfolderName, isMove)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to transfer bundle into $destFolderUri", e)
        sourceUris.map { FileOperationResult.failure(it.toString(), e.message) }
    }

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

    /** Drop the Selection marker into [selectionDir] (best effort; never fails a transfer). */
    internal fun ensureSelectionMarker(selectionDir: DocumentFile) {
        try {
            if (selectionDir.findFile(SELECTION_MARKER_FILE) == null) {
                selectionDir.createFile("application/octet-stream", SELECTION_MARKER_FILE)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not create $SELECTION_MARKER_FILE in ${selectionDir.uri}: ${e.message}")
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

    private suspend fun moveOrCopySingle(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
        isMove: Boolean,
    ): FileOperationResult {
        val source = sourceUri.toString()
        val folderName = subfolderName.trim().ifEmpty { "Selection" }
        val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
            ?: return FileOperationResult.failure(source, if (isMove) "Copy step failed" else "Could not write to the $folderName folder")
        val fileName = sourceDoc.name
            ?: return FileOperationResult.failure(source, if (isMove) "Copy step failed" else "Could not write to the $folderName folder")

        val destFolder = DocumentFile.fromTreeUri(context, destFolderUri)
            ?: return FileOperationResult.failure(source, if (isMove) "Copy step failed" else "Could not write to the $folderName folder")

        val targetFolder = if (sorting) {
            val selectionDir = destFolder.findFile(folderName)
                ?: destFolder.createDirectory(folderName)
                ?: return FileOperationResult.failure(source, if (isMove) "Copy step failed" else "Could not write to the $folderName folder")
            ensureSelectionMarker(selectionDir)
            determineTargetFolder(fileName, selectionDir)
        } else {
            destFolder
        }

        // Single cursor query to find existing display names in targetFolder.
        val existingNames = queryExistingDisplayNames(targetFolder)
        val desiredName = resolveCollisionFreeBundleNames(
            fileNames = listOf(fileName),
            primaryStem = extractPrimaryStem(fileName),
            existingNamesByFolder = mapOf(SINGLE_FOLDER_KEY to existingNames),
            folderForFile = { SINGLE_FOLDER_KEY },
        ).getValue(fileName)

        return executeSingleTransfer(
            sourceUri = sourceUri,
            sourceDoc = sourceDoc,
            fileName = fileName,
            desiredName = desiredName,
            destFolderUri = destFolderUri,
            targetFolder = targetFolder,
            selectionFolderName = folderName,
            isMove = isMove,
        )
    }

    private suspend fun executeSingleTransfer(
        sourceUri: Uri,
        sourceDoc: DocumentFile,
        fileName: String,
        desiredName: String,
        destFolderUri: Uri,
        targetFolder: DocumentFile,
        selectionFolderName: String,
        isMove: Boolean,
    ): FileOperationResult {
        val source = sourceUri.toString()
        if (sourceUri == targetFolder.uri) {
            return FileOperationResult.success(source, source)
        }

        // Attempt atomic DocumentsContract.moveDocument when moving without a
        // collision rename and the provider supports FLAG_SUPPORTS_MOVE.
        if (isMove && desiredName == fileName) {
            val sourceParentUri = resolveSourceParentUri(sourceUri, destFolderUri)
            if (documentMover.canMoveDocument(context, sourceUri, sourceParentUri, targetFolder.uri) &&
                sourceParentUri != null
            ) {
                val movedUri = documentMover.moveDocument(
                    context = context,
                    sourceUri = sourceUri,
                    sourceParentUri = sourceParentUri,
                    targetFolderUri = targetFolder.uri,
                )
                if (movedUri != null) {
                    return FileOperationResult.success(source, movedUri.toString())
                }
            }
        }

        val mimeType = sourceDoc.type ?: "application/octet-stream"
        return when (
            val copyResult = copyToTargetFolder(
                sourceUri = sourceUri,
                sourceDoc = sourceDoc,
                targetFolder = targetFolder,
                mimeType = mimeType,
                desiredName = desiredName,
                selectionFolderName = selectionFolderName,
            )
        ) {
            is CopyStepResult.Failure -> FileOperationResult.failure(source, copyResult.reason)
            is CopyStepResult.Success -> {
                if (!isMove) {
                    FileOperationResult.success(source, copyResult.destinationUri)
                } else {
                    val deleted = try {
                        deleteImage(sourceUri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete source after verified copy: $sourceUri", e)
                        false
                    }
                    if (deleted) {
                        FileOperationResult.success(source, copyResult.destinationUri)
                    } else {
                        // The copy is verified on disk, so never delete it when source
                        // removal fails; report a partial outcome instead.
                        FileOperationResult.partial(
                            sourceUri = source,
                            destinationUri = copyResult.destinationUri,
                            error = "Copied to $selectionFolderName — original could not be removed",
                        )
                    }
                }
            }
        }
    }

    /**
     * Stream-copy [sourceUri] into [targetFolder] as [desiredName], verifying
     * both the created display name and the destination byte size before
     * reporting success.
     */
    private fun copyToTargetFolder(
        sourceUri: Uri,
        sourceDoc: DocumentFile,
        targetFolder: DocumentFile,
        mimeType: String,
        desiredName: String,
        selectionFolderName: String,
    ): CopyStepResult {
        val destFile = targetFolder.createFile(mimeType, desiredName)
            ?: return CopyStepResult.Failure("Could not write to the $selectionFolderName folder")

        // Guard against a SAF provider silently auto-renaming the created file
        // (e.g. "IMG_0001 (1).CR3") on a race, which would break companion pairing.
        val actualName = destFile.name
        if (!actualName.isNullOrEmpty() && !actualName.equals(desiredName, ignoreCase = true)) {
            cleanUpFailedCopy(destFile)
            return CopyStepResult.Failure(
                "Provider renamed destination file to '$actualName' instead of '$desiredName'"
            )
        }

        val sourceSize = sourceDoc.length()
        var bytesCopied = -1L

        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                    bytesCopied = input.copyTo(output)
                    output.flush()
                }
            }
            if (bytesCopied >= 0L && SourceKindClassifier.classify(destFile.uri) == SourceKind.REMOVABLE) {
                try {
                    context.contentResolver.openFileDescriptor(destFile.uri, "rw")?.use { pfd ->
                        pfd.fileDescriptor.sync()
                    }
                } catch (_: Exception) {
                    // Best-effort fsync when the destination provider supports file descriptor sync.
                }
            }
        } catch (e: CancellationException) {
            cleanUpFailedCopy(destFile)
            throw e
        } catch (e: Exception) {
            // Don't leave a partially written file behind.
            cleanUpFailedCopy(destFile)
            throw e
        }

        if (bytesCopied < 0L) {
            // Streams could not be opened — remove the empty placeholder file.
            cleanUpFailedCopy(destFile)
            return CopyStepResult.Failure("Could not write to the $selectionFolderName folder")
        }

        val expectedSize = if (sourceSize > 0L) sourceSize else bytesCopied
        val destSize = destFile.length()
        if ((sourceSize > 0L && bytesCopied != sourceSize) ||
            (expectedSize > 0L && destSize != expectedSize)
        ) {
            cleanUpFailedCopy(destFile)
            return CopyStepResult.Failure("Copied file size mismatch")
        }

        return CopyStepResult.Success(destFile.uri.toString())
    }

    /**
     * List existing child display names in [targetFolder] using a **single**
     * `ContentResolver` cursor query (`COLUMN_DISPLAY_NAME`), falling back to
     * [DocumentFile.listFiles] only when [targetFolder] is not backed by a tree
     * document URI in unit-test mocks.
     */
    internal fun queryExistingDisplayNames(targetFolder: DocumentFile): Set<String> {
        val folderUri = targetFolder.uri
        val childrenUri = try {
            val docId = DocumentsContract.getDocumentId(folderUri)
            DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
        } catch (_: Exception) {
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
                DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)
            } catch (_: Exception) {
                null
            }
        }

        if (childrenUri != null) {
            try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val names = LinkedHashSet<String>(cursor.count.coerceAtLeast(0))
                        while (cursor.moveToNext()) {
                            if (!cursor.isNull(nameIndex)) {
                                cursor.getString(nameIndex)?.let { names.add(it) }
                            }
                        }
                        return names
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cursor query for existing names failed in $folderUri, falling back to listFiles", e)
            }
        }

        return try {
            targetFolder.listFiles().mapNotNull { it.name }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Fallback listFiles failed in $folderUri", e)
            emptySet()
        }
    }

    /**
     * Derive the parent document URI of [sourceUri] within [treeFolderUri] when
     * the document ID encodes a slash-separated relative path or sits at the
     * root of the opened tree.
     */
    internal fun resolveSourceParentUri(sourceUri: Uri, treeFolderUri: Uri): Uri? {
        val authority = sourceUri.authority ?: return null
        if (authority != treeFolderUri.authority) return null
        val sourceDocId = try {
            DocumentsContract.getDocumentId(sourceUri)
        } catch (_: Exception) {
            null
        } ?: return null

        val parentDocId = if (sourceDocId.contains('/')) {
            sourceDocId.substringBeforeLast('/')
        } else {
            try {
                DocumentsContract.getTreeDocumentId(treeFolderUri)
            } catch (_: Exception) {
                null
            }
        } ?: return null

        return try {
            DocumentsContract.buildDocumentUriUsingTree(treeFolderUri, parentDocId)
        } catch (_: Exception) {
            try {
                DocumentsContract.buildDocumentUri(authority, parentDocId)
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun cleanUpFailedCopy(destFile: DocumentFile) {
        try {
            destFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean up failed copy: ${destFile.uri}", e)
        }
    }

    /**
     * Determine which subfolder a file belongs in based on its extension.
     * RAW files → RAW subfolder, JPEG → JPEG subfolder,
     * Lightroom edits → RAW subfolder, XMP sidecars → follow parent type.
     */
    // Internal (not private) so the sorting rules can be unit-tested directly.
    internal fun determineTargetFolder(
        fileName: String,
        selectionDir: DocumentFile,
    ): DocumentFile {
        val rawDir by lazy {
            selectionDir.findFile(RAW_SUBFOLDER)
                ?: selectionDir.createDirectory(RAW_SUBFOLDER)
        }
        val jpegDir by lazy {
            selectionDir.findFile(JPEG_SUBFOLDER)
                ?: selectionDir.createDirectory(JPEG_SUBFOLDER)
        }

        return when (selectionSubfolder(fileName)) {
            SelectionSubfolder.RAW -> rawDir ?: selectionDir
            SelectionSubfolder.JPEG -> jpegDir ?: selectionDir
            SelectionSubfolder.ROOT -> selectionDir
        }
    }
}
