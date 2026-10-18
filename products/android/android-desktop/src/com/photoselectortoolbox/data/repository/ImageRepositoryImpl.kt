package com.photoselectortoolbox.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.photoselector.core.model.ExifData
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.domain.format.ImageFormats
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
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

@Singleton
class ImageRepositoryImpl @Inject constructor(
    private val localImageSource: LocalImageSource,
    private val androidExifReader: AndroidExifReader,
    private val mediaStoreReader: MediaStoreReader,
    private val settingsRepository: SettingsRepository,
    private val folderAccessManager: FolderAccessManager,
) : ImageRepository {

    constructor(
        localImageSource: LocalImageSource,
        androidExifReader: AndroidExifReader,
        mediaStoreReader: MediaStoreReader,
        settingsRepository: SettingsRepository,
    ) : this(
        localImageSource = localImageSource,
        androidExifReader = androidExifReader,
        mediaStoreReader = mediaStoreReader,
        settingsRepository = settingsRepository,
        folderAccessManager = FolderAccessManager(),
    )

    companion object {
        private const val TAG = "ImageRepositoryImpl"
        private const val SELECTION_FOLDER_NAME = "Selection"
        private const val SECONDARY_SELECTION_FOLDER_NAME = "Final"
        const val SELECTION_MARKER_FILE_NAME = ".photoselector-selection"
        private const val RAW_SUBFOLDER = "RAW"
        private const val JPEG_SUBFOLDER = "JPEG"
        private const val SINGLE_FOLDER_KEY = "TARGET"
        internal const val MAX_CONCURRENT_TRANSFERS = 2

        private val DERIVATIVE_SUFFIXES = listOf("-Edit", "_Edit", "-Enhanced-NR", "_Enhanced-NR")

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
            folderForFile: (String) -> String = { ImageFormats.selectionSubfolder(it).name },
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

        data class SafeToFormatVerdict(
            val isSafe: Boolean,
            val verifiedCount: Int,
            val missingOrEmptyFiles: List<String>,
            val summaryMessage: String,
        )

        /**
         * Verify that all kept filenames from a culling session are present and non-empty
         * in the destination folder before formatting an OTG SD card (`U-33`).
         */
        internal fun verifySafeToFormat(
            keptFileNames: Collection<String>,
            destinationFilesWithSizes: Map<String, Long>,
        ): SafeToFormatVerdict {
            if (keptFileNames.isEmpty()) {
                return SafeToFormatVerdict(
                    isSafe = false,
                    verifiedCount = 0,
                    missingOrEmptyFiles = emptyList(),
                    summaryMessage = "No kept photos recorded in this session yet.",
                )
            }
            val lowerDest = destinationFilesWithSizes.entries.associate { (k, v) -> k.lowercase() to v }
            val missing = mutableListOf<String>()
            var verified = 0
            for (name in keptFileNames) {
                val stem = extractPrimaryStem(name).lowercase()
                val matchedSize = lowerDest[name.lowercase()]
                    ?: lowerDest.entries.firstOrNull { (destName, _) ->
                        extractPrimaryStem(destName).lowercase() == stem
                    }?.value
                if (matchedSize != null && matchedSize > 0L) {
                    verified++
                } else {
                    missing.add(name)
                }
            }
            val safe = missing.isEmpty() && verified == keptFileNames.size
            val msg = if (safe) {
                "Safe to format SD card: all $verified kept photo(s) verified on destination storage."
            } else {
                "Do NOT format SD card: ${missing.size} of ${keptFileNames.size} kept photo(s) missing or empty (${missing.take(3).joinToString()})."
            }
            return SafeToFormatVerdict(
                isSafe = safe,
                verifiedCount = verified,
                missingOrEmptyFiles = missing,
                summaryMessage = msg,
            )
        }
    }

    private val transferSemaphore = Semaphore(MAX_CONCURRENT_TRANSFERS)
    private val destinationLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun destinationLockFor(destinationKey: String): Mutex =
        destinationLocks.getOrPut(destinationKey) { Mutex() }

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var documentMover: SafDocumentMover = DefaultSafDocumentMover

    private sealed class CopyStepResult {
        data class Success(val destinationFile: DocumentFile, val destinationUri: String) : CopyStepResult()
        data class Failure(val reason: String) : CopyStepResult()
    }

    /**
     * Persist the grant, then confirm the folder resolves and still exists.
     *
     * The grant is taken on a best-effort basis: a folder handed over for this
     * process only — which is what the test fakes and some pickers give — is
     * still perfectly readable, so a failure to persist is logged and the open
     * continues. A folder that does not resolve is the real failure.
     */
    override suspend fun openFolder(context: Context, folderUri: Uri): String? =
        withContext(ioDispatcher) {
            folderAccessManager.takeGrant(
                uri = folderUri,
                purpose = FolderAccessPurpose.SOURCE,
                fallbackContext = context,
            )

            val folderDoc = try {
                DocumentFile.fromTreeUri(context, folderUri)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException opening folder $folderUri", e)
                null
            }

            if (folderDoc == null || !folderDoc.exists()) return@withContext null
            folderDoc.name ?: "Unknown"
        }

    override fun discoverImages(folderUri: Uri): Flow<List<ImageItem>> =
        localImageSource.discoverImages(folderUri)

    /**
     * The last emission is the complete folder by contract, so collecting to it
     * is the whole implementation — and it keeps the "progressive discovery
     * exists" knowledge in one place rather than at each one-shot call site.
     */
    override suspend fun discoverAllImages(folderUri: Uri): List<ImageItem> =
        withContext(ioDispatcher) { discoverImages(folderUri).lastOrNull().orEmpty() }

    override suspend fun getExifData(context: Context, uri: Uri): ExifData? {
        // Try the primary ExifInterface reader first
        val exifData = androidExifReader.readExif(context, uri)
        if (exifData != null) return exifData

        // Fall back to MediaStore reader
        return mediaStoreReader.readExif(context, uri)
    }

    /**
     * SAF deletes are unlinks with no trash behind them, so this is always false
     * and the UI offers no UNDO — the honest answer, and better than an UNDO
     * that silently does nothing. Kept as a question the repository answers
     * rather than a constant the UI assumes, because it is a property of the
     * backend and the selector must not start guessing at those again.
     */
    override fun canTrash(uri: Uri): Boolean = false

    override suspend fun restoreFromTrash(context: Context, uri: Uri): Boolean = false

    override suspend fun deleteImage(context: Context, uri: Uri): Boolean =
        withContext(ioDispatcher) {
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

    override suspend fun deleteBundle(context: Context, item: ImageItem): Boolean =
        withContext(ioDispatcher) {
            var allDeleted = true
            for (file in item.allFiles) {
                val deleted = deleteImage(context, Uri.parse(file.uri))
                if (!deleted) {
                    allDeleted = false
                }
            }
            allDeleted
        }

    override suspend fun moveImage(
        context: Context,
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean
    ): FileOperationResult =
        transferBundle(
            context = context,
            sourceUris = listOf(sourceUri),
            destFolderUri = destFolderUri,
            sorting = sorting,
            isMove = true,
        ).firstOrNull() ?: FileOperationResult.failure(sourceUri.toString(), "Move failed")

    override suspend fun copyImage(
        context: Context,
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean
    ): FileOperationResult =
        transferBundle(
            context = context,
            sourceUris = listOf(sourceUri),
            destFolderUri = destFolderUri,
            sorting = sorting,
            isMove = false,
        ).firstOrNull() ?: FileOperationResult.failure(sourceUri.toString(), "Copy failed")

    override suspend fun moveBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult = transferItemBundle(
        context = context,
        item = item,
        destFolderUri = destFolderUri,
        sorting = sorting,
        isMove = true,
    )

    override suspend fun copyBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult = transferItemBundle(
        context = context,
        item = item,
        destFolderUri = destFolderUri,
        sorting = sorting,
        isMove = false,
    )

    private suspend fun transferItemBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
        isMove: Boolean,
    ): FileOperationResult {
        val sourceUris = item.allFiles.map { Uri.parse(it.uri) }
        val results = transferBundle(
            context = context,
            sourceUris = sourceUris,
            destFolderUri = destFolderUri,
            sorting = sorting,
            isMove = isMove,
            parentDocumentId = item.parentDocumentId,
        )
        return aggregateBundleResults(
            primarySourceUri = item.uri,
            results = results,
            parentDocumentId = item.parentDocumentId,
        )
    }

    /**
     * Take a filed photograph back out of the Selection and restore it to
     * [restoreFolderUri] without creating a `Selection/` subfolder.
     */
    override suspend fun undoMove(
        context: Context,
        movedTo: Uri,
        restoreFolderUri: Uri,
    ): FileOperationResult = undoMoveBundle(
        context = context,
        destinationUris = listOf(movedTo.toString()),
        rootFolderUri = restoreFolderUri,
        parentDocumentId = null,
        copiesOfSurvivingOriginals = emptyMap(),
    )

    override suspend fun undoMoveBundle(
        context: Context,
        destinationUris: List<String>,
        rootFolderUri: Uri,
        parentDocumentId: String?,
        copiesOfSurvivingOriginals: Map<String, String>,
    ): FileOperationResult = transferSemaphore.withPermit {
        withContext(ioDispatcher) {
            val primarySource = destinationUris.firstOrNull().orEmpty()
            if (destinationUris.isEmpty()) {
                return@withContext FileOperationResult.failure(
                    sourceUri = "",
                    error = "No files to restore",
                    parentDocumentId = parentDocumentId,
                )
            }
            try {
                // A copy whose original is still there (partial move) is deleted rather than
                // restored: restoring would put a duplicate next to the original. A copy whose
                // original has since disappeared is the only one left, so it is restored.
                val deletableCopies = copiesOfSurvivingOriginals.filter { (_, original) ->
                    DocumentFile.fromSingleUri(context, Uri.parse(original))?.exists() == true
                }.keys
                var deleteFailed: String? = null
                deletableCopies.forEach { copyUri ->
                    if (!deleteImage(context, Uri.parse(copyUri))) deleteFailed = copyUri
                }
                deleteFailed?.let {
                    return@withContext FileOperationResult.failure(
                        sourceUri = it,
                        error = "Could not delete the copy in the destination folder",
                        parentDocumentId = parentDocumentId,
                    )
                }
                val deletedCopyUris = deletableCopies.toList()
                val toRestore = destinationUris.filterNot { it in deletableCopies }
                if (toRestore.isEmpty()) {
                    return@withContext FileOperationResult.success(
                        sourceUri = primarySource,
                        destinationUri = null,
                        destinationUris = emptyList(),
                        parentDocumentId = parentDocumentId,
                    ).copy(deletedCopies = deletedCopyUris)
                }
                val restore = resolveRestoreFolder(context, rootFolderUri, parentDocumentId)
                    ?: return@withContext FileOperationResult.failure(
                        sourceUri = primarySource,
                        error = "Could not resolve the original folder",
                        parentDocumentId = parentDocumentId,
                    )
                val restoreFolder = restore.folder
                val sourceUris = toRestore.map { Uri.parse(it) }
                val results = executeBundleTransfer(
                    context = context,
                    sourceUris = sourceUris,
                    treeRootUri = rootFolderUri,
                    baseTargetFolder = restoreFolder,
                    sorting = false,
                    targetFolderLabel = restoreFolder.name ?: "original folder",
                    isMove = true,
                    parentDocumentId = parentDocumentId,
                )
                val aggregated = aggregateBundleResults(
                    primarySourceUri = primarySource,
                    results = results,
                    parentDocumentId = parentDocumentId,
                )
                if (restore.fellBackToRoot && aggregated.success) {
                    aggregated.copy(
                        notice = "Restored to the opened folder — the original folder no longer exists",
                        deletedCopies = deletedCopyUris,
                    )
                } else {
                    aggregated.copy(deletedCopies = deletedCopyUris)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to undo bundle move: $destinationUris", e)
                FileOperationResult.failure(primarySource, e.message, parentDocumentId)
            }
        }
    }

    /**
     * Transfer a companion bundle (primary + RAW/JPEG twin + XMP sidecars +
     * `-Edit` derivatives) into `<destFolderUri>/<selectionFolderName>` using a
     * single cursor query per target folder and a shared collision suffix
     * (`-2`, `-3`, …) across the entire bundle so pairing is never broken.
     *
     * Always resolves or creates `<destFolderUri>/<selectionFolderName>`
     * (`Selection` by default); [sorting] only controls whether `RAW/` and
     * `JPEG/` subfolders are created inside that Selection folder (U-04c).
     */
    internal suspend fun transferBundle(
        context: Context,
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        isMove: Boolean,
        parentDocumentId: String? = null,
    ): List<FileOperationResult> = transferSemaphore.withPermit {
        withContext(ioDispatcher) {
            if (sourceUris.isEmpty()) return@withContext emptyList()
            try {
                val primaryFolderName = settingsRepository.selectionFolderName.first()
                    .trim().ifEmpty { SELECTION_FOLDER_NAME }
                val secondaryFolderName = try {
                    settingsRepository.secondarySelectionFolderName.firstOrNull()
                        ?.trim()?.ifEmpty { SECONDARY_SELECTION_FOLDER_NAME }
                        ?: SECONDARY_SELECTION_FOLDER_NAME
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SECONDARY_SELECTION_FOLDER_NAME
                }
                val customDestUriStr = try {
                    settingsRepository.customSelectionDestinationUri.firstOrNull()
                        ?.trim()?.takeIf { it.isNotEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val effectiveDestRootUri = customDestUriStr?.let { Uri.parse(it) } ?: destFolderUri

                // Two transfers into the same destination must not interleave "pick a free name ->
                // create the file" or "find the folder -> create it": the loser would overwrite the
                // winner's file or leave a second "Selection (1)" folder (finding D-5). One lock per
                // destination root; transfers to different destinations still run in parallel.
                destinationLockFor(effectiveDestRootUri.toString()).withLock {

                    val destFolder = DocumentFile.fromTreeUri(context, effectiveDestRootUri)
                        ?: return@withContext sourceUris.map { uri ->
                            FileOperationResult.failure(
                                sourceUri = uri.toString(),
                                error = "Could not write to the $primaryFolderName folder",
                                parentDocumentId = parentDocumentId,
                            )
                        }

                    val isSecondPass = customDestUriStr == null && (
                        destFolder.name?.equals(primaryFolderName, ignoreCase = true) == true ||
                            runCatching { destFolder.findFile(SELECTION_MARKER_FILE_NAME) != null }.getOrDefault(false)
                        )
                    val folderName = if (isSecondPass) secondaryFolderName else primaryFolderName

                    val selectionDir = destFolder.findFile(folderName)
                        ?: destFolder.createDirectory(folderName)
                        ?: return@withContext sourceUris.map { uri ->
                            FileOperationResult.failure(
                                sourceUri = uri.toString(),
                                error = "Could not write to the $folderName folder",
                                parentDocumentId = parentDocumentId,
                            )
                        }

                    val results = executeBundleTransfer(
                        context = context,
                        sourceUris = sourceUris,
                        treeRootUri = effectiveDestRootUri,
                        baseTargetFolder = selectionDir,
                        sorting = sorting,
                        targetFolderLabel = folderName,
                        isMove = isMove,
                        parentDocumentId = parentDocumentId,
                    )
                    if (results.any { it.success || it.partial }) {
                        ensureSelectionMarkerFile(selectionDir)
                    }
                    results
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transfer bundle: $sourceUris", e)
                sourceUris.map { uri ->
                    FileOperationResult.failure(uri.toString(), e.message, parentDocumentId)
                }
            }
        }
    }

    private fun ensureSelectionMarkerFile(selectionDir: DocumentFile) {
        try {
            if (selectionDir.findFile(SELECTION_MARKER_FILE_NAME) == null) {
                selectionDir.createFile("application/octet-stream", SELECTION_MARKER_FILE_NAME)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not create marker file in ${selectionDir.uri}: ${e.message}")
        }
    }

    private fun aggregateBundleResults(
        primarySourceUri: String,
        results: List<FileOperationResult>,
        parentDocumentId: String?,
    ): FileOperationResult {
        if (results.isEmpty()) {
            return FileOperationResult.failure(primarySourceUri, "No files in bundle", parentDocumentId)
        }
        val destUris = results.mapNotNull { it.destinationUri }
        val primaryDest = destUris.firstOrNull()
        return when {
            results.all { it.success } -> FileOperationResult.success(
                sourceUri = primarySourceUri,
                destinationUri = primaryDest,
                destinationUris = destUris,
                parentDocumentId = parentDocumentId,
            )
            results.any { it.partial } -> FileOperationResult.partial(
                sourceUri = primarySourceUri,
                destinationUri = primaryDest ?: "",
                error = results.firstNotNullOfOrNull { it.error }
                    ?: "Copied, but the original could not be removed",
                destinationUris = destUris,
                parentDocumentId = parentDocumentId,
                copiesOfSurvivingOriginals = results
                    .filter { it.partial }
                    .fold(emptyMap()) { acc, r -> acc + r.copiesOfSurvivingOriginals },
            )
            else -> FileOperationResult.failure(
                sourceUri = primarySourceUri,
                error = results.firstNotNullOfOrNull { it.error },
                parentDocumentId = parentDocumentId,
            )
        }
    }

    private data class ResolvedSource(
        val uri: Uri,
        val doc: DocumentFile?,
        val fileName: String?,
        val folderKey: String,
        val targetFolder: DocumentFile?,
    )

    private suspend fun executeBundleTransfer(
        context: Context,
        sourceUris: List<Uri>,
        treeRootUri: Uri,
        baseTargetFolder: DocumentFile,
        sorting: Boolean,
        targetFolderLabel: String,
        isMove: Boolean,
        parentDocumentId: String?,
    ): List<FileOperationResult> {
        val targetFoldersByKey = LinkedHashMap<String, DocumentFile>()
        val resolvedSources = sourceUris.map { uri ->
            val doc = DocumentFile.fromSingleUri(context, uri)
            val name = doc?.name
            if (name == null) {
                ResolvedSource(uri, doc, null, SINGLE_FOLDER_KEY, null)
            } else if (sorting) {
                val key = ImageFormats.selectionSubfolder(name).name
                val target = targetFoldersByKey.getOrPut(key) {
                    determineTargetFolder(name, baseTargetFolder)
                }
                ResolvedSource(uri, doc, name, key, target)
            } else {
                targetFoldersByKey.getOrPut(SINGLE_FOLDER_KEY) { baseTargetFolder }
                ResolvedSource(uri, doc, name, SINGLE_FOLDER_KEY, baseTargetFolder)
            }
        }

        if (resolvedSources.any { it.doc == null || it.fileName == null || it.targetFolder == null }) {
            return resolvedSources.map { src ->
                FileOperationResult.failure(
                    sourceUri = src.uri.toString(),
                    error = "Could not write to the $targetFolderLabel folder",
                    parentDocumentId = parentDocumentId,
                )
            }
        }

        val existingNamesByFolder = targetFoldersByKey.mapValues { (_, folder) ->
            queryExistingDisplayNames(context, folder)
        }
        val validNames = resolvedSources.mapNotNull { it.fileName }
        val primaryStem = validNames.firstOrNull()?.let(::extractPrimaryStem).orEmpty()
        val folderKeyByName = resolvedSources.associate { it.fileName!! to it.folderKey }

        val desiredNames = resolveCollisionFreeBundleNames(
            fileNames = validNames,
            primaryStem = primaryStem,
            existingNamesByFolder = existingNamesByFolder,
            folderForFile = { folderKeyByName[it] ?: SINGLE_FOLDER_KEY },
        )

        // Single-file fast path: attempt atomic DocumentsContract.moveDocument
        // when moving without a collision rename and the provider supports it.
        if (resolvedSources.size == 1 && isMove) {
            val single = resolvedSources.single()
            val name = single.fileName!!
            val desiredName = desiredNames[name] ?: name
            val targetFolder = single.targetFolder!!
            if (single.uri == targetFolder.uri) {
                val uriStr = single.uri.toString()
                return listOf(
                    FileOperationResult.success(
                        sourceUri = uriStr,
                        destinationUri = uriStr,
                        destinationUris = listOf(uriStr),
                        parentDocumentId = parentDocumentId,
                    )
                )
            }
            if (desiredName == name) {
                val sourceParentUri = resolveSourceParentUri(single.uri, treeRootUri)
                if (documentMover.canMoveDocument(context, single.uri, sourceParentUri, targetFolder.uri) &&
                    sourceParentUri != null
                ) {
                    val movedUri = documentMover.moveDocument(
                        context = context,
                        sourceUri = single.uri,
                        sourceParentUri = sourceParentUri,
                        targetFolderUri = targetFolder.uri,
                    )
                    if (movedUri != null) {
                        val movedStr = movedUri.toString()
                        return listOf(
                            FileOperationResult.success(
                                sourceUri = single.uri.toString(),
                                destinationUri = movedStr,
                                destinationUris = listOf(movedStr),
                                parentDocumentId = parentDocumentId,
                            )
                        )
                    }
                }
            }
        }

        // Phase 1: Copy and byte-verify every member of the bundle before
        // deleting any source file. If any copy fails, clean up all newly
        // created destination files from this transaction and keep all sources intact.
        val stagedCopies = ArrayList<Pair<ResolvedSource, DocumentFile>>(resolvedSources.size)
        for (src in resolvedSources) {
            val doc = src.doc!!
            val name = src.fileName!!
            val targetFolder = src.targetFolder!!
            val desiredName = desiredNames[name] ?: name
            val mimeType = doc.type ?: "application/octet-stream"

            val copyResult = try {
                copyToTargetFolder(
                    context = context,
                    sourceUri = src.uri,
                    sourceDoc = doc,
                    targetFolder = targetFolder,
                    mimeType = mimeType,
                    desiredName = desiredName,
                    selectionFolderName = targetFolderLabel,
                )
            } catch (e: CancellationException) {
                stagedCopies.forEach { (_, destDoc) -> cleanUpFailedCopy(destDoc) }
                throw e
            } catch (e: Exception) {
                stagedCopies.forEach { (_, destDoc) -> cleanUpFailedCopy(destDoc) }
                throw e
            }

            when (copyResult) {
                is CopyStepResult.Failure -> {
                    stagedCopies.forEach { (_, destDoc) -> cleanUpFailedCopy(destDoc) }
                    return resolvedSources.map { member ->
                        FileOperationResult.failure(
                            sourceUri = member.uri.toString(),
                            error = copyResult.reason,
                            parentDocumentId = parentDocumentId,
                        )
                    }
                }
                is CopyStepResult.Success -> {
                    stagedCopies.add(src to copyResult.destinationFile)
                }
            }
        }

        val allDestUris = stagedCopies.map { (_, destDoc) -> destDoc.uri.toString() }
        if (!isMove) {
            return stagedCopies.map { (src, destDoc) ->
                FileOperationResult.success(
                    sourceUri = src.uri.toString(),
                    destinationUri = destDoc.uri.toString(),
                    destinationUris = allDestUris,
                    parentDocumentId = parentDocumentId,
                )
            }
        }

        // Phase 2: All destination copies are verified on disk. Now delete all
        // source files; if any source deletion fails, keep the verified copies
        // and report a partial outcome.
        var anyDeleteFailed = false
        val survivingOriginals = LinkedHashMap<String, String>() // destination copy -> original kept
        for ((src, destDoc) in stagedCopies) {
            val deleted = try {
                deleteImage(context, src.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete source after verified copy: ${src.uri}", e)
                false
            }
            if (!deleted) {
                anyDeleteFailed = true
                survivingOriginals[destDoc.uri.toString()] = src.uri.toString()
            }
        }

        return if (!anyDeleteFailed) {
            stagedCopies.map { (src, destDoc) ->
                FileOperationResult.success(
                    sourceUri = src.uri.toString(),
                    destinationUri = destDoc.uri.toString(),
                    destinationUris = allDestUris,
                    parentDocumentId = parentDocumentId,
                )
            }
        } else {
            val partialError = "Copied to $targetFolderLabel — original could not be removed"
            stagedCopies.map { (src, destDoc) ->
                FileOperationResult.partial(
                    sourceUri = src.uri.toString(),
                    destinationUri = destDoc.uri.toString(),
                    error = partialError,
                    destinationUris = allDestUris,
                    parentDocumentId = parentDocumentId,
                    copiesOfSurvivingOriginals = survivingOriginals,
                )
            }
        }
    }

    /** The folder an undo restores into, and whether it had to fall back to the opened folder. */
    internal data class RestoreFolder(val folder: DocumentFile, val fellBackToRoot: Boolean)

    /**
     * Resolve the folder identified by [parentDocumentId] — the provider's own document id of
     * the folder the photo came from — within [rootFolderUri].
     *
     * The id is never interpreted as a path: providers such as Downloads (`msf:…`), MTP and the
     * cloud ones hand out opaque ids, so the id is passed to the provider as it is
     * (`buildDocumentUriUsingTree`). Nothing is created during a restore. When the folder no
     * longer exists, the opened folder (tree root) is used and [RestoreFolder.fellBackToRoot]
     * lets the caller tell the user.
     */
    internal fun resolveRestoreFolder(
        context: Context,
        rootFolderUri: Uri,
        parentDocumentId: String?,
    ): RestoreFolder? {
        val rootDoc = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return null
        if (parentDocumentId.isNullOrBlank()) return RestoreFolder(rootDoc, fellBackToRoot = false)

        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(rootFolderUri)
        } catch (_: Exception) {
            null
        }
        if (parentDocumentId == treeDocId) return RestoreFolder(rootDoc, fellBackToRoot = false)

        val resolved = try {
            val subUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, parentDocumentId)
            DocumentFile.fromTreeUri(context, subUri)?.takeIf { it.exists() && it.isDirectory }
        } catch (_: Exception) {
            null
        }
        return if (resolved != null) {
            RestoreFolder(resolved, fellBackToRoot = false)
        } else {
            RestoreFolder(rootDoc, fellBackToRoot = true)
        }
    }

    /**
     * Stream-copy [sourceUri] into [targetFolder] as [desiredName], verifying
     * both the created display name and the destination byte size before
     * reporting success.
     */
    private fun copyToTargetFolder(
        context: Context,
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
        } catch (e: CancellationException) {
            cleanUpFailedCopy(destFile)
            throw e
        } catch (e: Exception) {
            // Never leave a half-written file in the Selection: a truncated
            // photograph that looks filed is worse than a failed move.
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

        return CopyStepResult.Success(destFile, destFile.uri.toString())
    }

    /**
     * List existing child display names in [targetFolder] using a **single**
     * `ContentResolver` cursor query (`COLUMN_DISPLAY_NAME`), falling back to
     * [DocumentFile.listFiles] only when [targetFolder] is not backed by a tree
     * document URI in unit-test mocks.
     */
    internal fun queryExistingDisplayNames(
        context: Context,
        targetFolder: DocumentFile,
    ): Set<String> {
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

    private fun cleanUpFailedCopy(destFile: DocumentFile) {
        try {
            destFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean up failed copy: ${destFile.uri}", e)
        }
    }

    /**
     * The subfolder of [selectionDir] a file is sorted into — the rule itself
     * lives in [ImageFormats.selectionSubfolder].
     */
    private fun determineTargetFolder(
        fileName: String,
        selectionDir: DocumentFile
    ): DocumentFile {
        fun sub(name: String): DocumentFile? =
            selectionDir.findFile(name) ?: selectionDir.createDirectory(name)
        return when (ImageFormats.selectionSubfolder(fileName)) {
            ImageFormats.SelectionSubfolder.RAW -> sub(RAW_SUBFOLDER) ?: selectionDir
            ImageFormats.SelectionSubfolder.JPEG -> sub(JPEG_SUBFOLDER) ?: selectionDir
            ImageFormats.SelectionSubfolder.ROOT -> selectionDir
        }
    }

    override suspend fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> =
        localImageSource.getImageDimensions(uri)

    override suspend fun resolveDimensions(
        context: Context,
        uris: Collection<String>,
    ): Map<String, ImageDimensions> = withContext(ioDispatcher) {
        localImageSource.resolveDimensions(uris)
    }
}
