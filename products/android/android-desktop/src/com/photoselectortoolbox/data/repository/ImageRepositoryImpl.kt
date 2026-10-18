package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.photoselector.core.model.ExifData
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselector.saf.BundleAtomicity
import com.photoselector.saf.BundleNaming
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselector.saf.MarkerTiming
import com.photoselector.saf.SafStorage
import com.photoselector.saf.SafTransferEngine
import com.photoselector.saf.SafTransferPolicy
import com.photoselector.saf.SelectionDestination
import com.photoselector.saf.SelectionSubfolder
import com.photoselector.saf.SelectionSubfolderRule
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
import kotlinx.coroutines.withContext

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
        const val SELECTION_MARKER_FILE_NAME = SafTransferEngine.SELECTION_MARKER_FILE_NAME

        /**
         * Android Desktop's sorting rule ([ImageFormats.selectionSubfolder]) for the shared
         * engine.
         */
        internal val SUBFOLDER_RULE = SelectionSubfolderRule { name, _ ->
            when (ImageFormats.selectionSubfolder(name)) {
                ImageFormats.SelectionSubfolder.RAW -> SelectionSubfolder.RAW
                ImageFormats.SelectionSubfolder.JPEG -> SelectionSubfolder.JPEG
                ImageFormats.SelectionSubfolder.ROOT -> SelectionSubfolder.ROOT
            }
        }

        /**
         * Android Desktop's transfer semantics (`docs/shared/FILE_OPERATIONS.md`): a bundle is
         * copied and verified in full before any source is deleted, and the Selection marker is
         * written once something landed.
         */
        internal val TRANSFER_POLICY = SafTransferPolicy(
            bundleAtomicity = BundleAtomicity.ALL_OR_NOTHING,
            markerTiming = MarkerTiming.AFTER_FIRST_TRANSFER,
        )

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
                val stem = BundleNaming.extractPrimaryStem(name).lowercase()
                val matchedSize = lowerDest[name.lowercase()]
                    ?: lowerDest.entries.firstOrNull { (destName, _) ->
                        BundleNaming.extractPrimaryStem(destName).lowercase() == stem
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


    /** The shared SAF engine (`:saf`), configured for Android Desktop. */
    internal val engine = SafTransferEngine(SUBFOLDER_RULE, TRANSFER_POLICY)

    /** Dispatcher for the repository's own I/O; setting it also sets the engine's (tests). */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        set(value) {
            field = value
            engine.ioDispatcher = value
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
        engine.delete(SafStorage.of(context), uri)

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
    ): FileOperationResult = transferItemBundle(context, item, destFolderUri, sorting, isMove = true)

    override suspend fun copyBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult = transferItemBundle(context, item, destFolderUri, sorting, isMove = false)

    private suspend fun transferItemBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
        isMove: Boolean,
    ): FileOperationResult {
        val results = transferBundle(
            context = context,
            sourceUris = item.allFiles.map { Uri.parse(it.uri) },
            destFolderUri = destFolderUri,
            sorting = sorting,
            isMove = isMove,
            parentDocumentId = item.parentDocumentId,
        )
        return engine.aggregateBundleResults(
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
    ): FileOperationResult = engine.undoMoveBundle(
        storage = SafStorage.of(context),
        destinationUris = destinationUris,
        rootFolderUri = rootFolderUri,
        parentDocumentId = parentDocumentId,
        copiesOfSurvivingOriginals = copiesOfSurvivingOriginals,
    )

    /**
     * Transfer a companion bundle (primary + RAW/JPEG twin + XMP sidecars + `-Edit`
     * derivatives) into `<destination>/<selection folder>` with the shared engine.
     *
     * The destination is the custom Selection destination when one is set, else
     * [destFolderUri]. The folder is the configured Selection folder name — or, on a second
     * pass (the destination already is a Selection folder, by name or by its marker, and no
     * custom destination is set), the secondary name (`Final`). [sorting] only controls
     * whether `RAW/` and `JPEG/` subfolders are created inside it (U-04c).
     */
    internal suspend fun transferBundle(
        context: Context,
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        isMove: Boolean,
        parentDocumentId: String? = null,
    ): List<FileOperationResult> {
        if (sourceUris.isEmpty()) return emptyList()
        val destination = try {
            selectionDestination(destFolderUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transfer bundle: $sourceUris", e)
            return sourceUris.map { FileOperationResult.failure(it.toString(), e.message, parentDocumentId) }
        }
        return engine.transferBundle(
            storage = SafStorage.of(context),
            sourceUris = sourceUris,
            destination = destination,
            sorting = sorting,
            isMove = isMove,
            parentDocumentId = parentDocumentId,
        )
    }

    private suspend fun selectionDestination(destFolderUri: Uri): SelectionDestination {
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
        return SelectionDestination(
            rootUri = customDestUriStr?.let { Uri.parse(it) } ?: destFolderUri,
            folderName = primaryFolderName,
        ) { destRoot ->
            val isSecondPass = customDestUriStr == null && (
                destRoot.name?.equals(primaryFolderName, ignoreCase = true) == true ||
                    runCatching { destRoot.findFile(SELECTION_MARKER_FILE_NAME) != null }.getOrDefault(false)
                )
            if (isSecondPass) secondaryFolderName else primaryFolderName
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
