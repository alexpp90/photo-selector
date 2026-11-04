package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.photoselector.core.model.ExifData
import com.photoselector.core.reader.AndroidExifReader
import com.photoselector.core.reader.MediaStoreReader
import com.photoselector.core.storage.ExternalStorageDetector
import com.photoselector.core.storage.SafTreeDisplayPath
import com.photoselector.core.storage.SourceKind
import com.photoselector.core.storage.SourceKindClassifier
import com.photoselector.saf.BundleAtomicity
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.FolderAccessPurpose
import com.photoselector.saf.MarkerTiming
import com.photoselector.saf.SafStorage
import com.photoselector.saf.SafTransferEngine
import com.photoselector.saf.SafTransferPolicy
import com.photoselector.saf.SelectionDestination
import com.photoselector.saf.SelectionSubfolder
import com.photoselector.saf.SelectionSubfolderRule
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.domain.curation.DestinationSettings
import com.photoselectortoolbox.domain.curation.OpenedFolder
import com.photoselectortoolbox.domain.curation.SelectionDestinationPlan
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
        const val SELECTION_MARKER_FILE_NAME = SafTransferEngine.SELECTION_MARKER_FILE_NAME

        /**
         * Android Desktop's sorting rule for the shared engine: bundle-aware
         * ([ImageFormats.selectionSubfolderInBundle]), so a stem sidecar (`DSC_0003.xmp`)
         * follows its RAW instead of landing in the Selection root (device lab AD-6).
         */
        internal val SUBFOLDER_RULE = SelectionSubfolderRule { name, bundle ->
            when (ImageFormats.selectionSubfolderInBundle(name, bundle)) {
                ImageFormats.SelectionSubfolder.RAW -> SelectionSubfolder.RAW
                ImageFormats.SelectionSubfolder.JPEG -> SelectionSubfolder.JPEG
                ImageFormats.SelectionSubfolder.ROOT -> SelectionSubfolder.ROOT
            }
        }

        /**
         * Android Desktop's transfer semantics (`docs/shared/FILE_OPERATIONS.md`): a bundle is
         * copied and verified in full before any source is deleted, and the Selection marker is
         * written once something landed. Copies onto removable media (an OTG card, the case this
         * product is for) are fsynced, as Jackpix does: an unflushed copy is lost when the card is
         * pulled right after the original was deleted (FILE_OPERATIONS section 2).
         */
        internal val TRANSFER_POLICY = SafTransferPolicy(
            bundleAtomicity = BundleAtomicity.ALL_OR_NOTHING,
            markerTiming = MarkerTiming.AFTER_FIRST_TRANSFER,
            syncDestination = { SourceKindClassifier.classify(it) == SourceKind.REMOVABLE },
        )

        data class SafeToFormatVerification(
            val isSafe: Boolean,
            val verifiedCount: Int,
            val missingOrMismatchedFiles: List<String>,
            val summaryMessage: String,
        )

        /**
         * Verify, before an OTG SD card is formatted (`U-33`), that every kept *file* of a culling
         * session — each bundle member, RAW twin and sidecar included, mapped to its source size —
         * is at the destination under exactly its name with exactly that byte size.
         *
         * No stem fallback (FD-C-11): a kept `DSC0001.ARW` is not verified by a `DSC0001.JPG`
         * that did arrive. A source of unknown (≤ 0) size cannot be verified. Not called by any UI
         * yet; the requirement says so until one does.
         */
        internal fun verifySafeToFormat(
            keptFiles: Map<String, Long>,
            destinationFilesWithSizes: Map<String, Long>,
        ): SafeToFormatVerification {
            if (keptFiles.isEmpty()) {
                return SafeToFormatVerification(
                    isSafe = false,
                    verifiedCount = 0,
                    missingOrMismatchedFiles = emptyList(),
                    summaryMessage = "No kept photos recorded in this session yet.",
                )
            }
            val missing = mutableListOf<String>()
            var verified = 0
            for ((name, sourceSize) in keptFiles) {
                val destinationSize = destinationFilesWithSizes[name]
                if (sourceSize > 0L && destinationSize == sourceSize) {
                    verified++
                } else {
                    missing.add(name)
                }
            }
            val safe = missing.isEmpty() && verified == keptFiles.size
            val msg = if (safe) {
                "Safe to format SD card: all $verified kept file(s) verified on destination storage."
            } else {
                "Do NOT format SD card: ${missing.size} of ${keptFiles.size} kept file(s) missing or of another size (${missing.take(3).joinToString()})."
            }
            return SafeToFormatVerification(
                isSafe = safe,
                verifiedCount = verified,
                missingOrMismatchedFiles = missing,
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

    /**
     * Read-only: the readable path, the grant, whether the tree resolves and takes files,
     * and whether it is itself a Selection folder. Takes no grant and creates nothing, so
     * Settings can call it for any stored folder. Every lookup that fails reads as "no"
     * (the folder is then shown as unreachable, which is the truthful answer), except
     * cancellation.
     */
    override suspend fun describeFolder(context: Context, folderUri: Uri): FolderDescription =
        withContext(ioDispatcher) {
            val uriString = folderUri.toString()
            val hasGrant = try {
                val wanted = FolderAccessManager.canonicalUri(uriString)
                context.contentResolver.persistedUriPermissions.any { permission ->
                    val tree = FolderAccessManager.canonicalUri(permission.uri.toString())
                    permission.isReadPermission && permission.isWritePermission &&
                        (tree == wanted || wanted.startsWith("$tree/document/"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read persisted grants for $uriString", e)
                false
            }
            val folder = try {
                DocumentFile.fromTreeUri(context, folderUri)
            } catch (e: Exception) {
                // SecurityException (no grant) or IllegalArgumentException (not a tree URI).
                Log.w(TAG, "Folder $uriString does not resolve", e)
                null
            }
            val exists = folder != null && runCatching { folder.exists() && folder.isDirectory }.getOrDefault(false)
            val name = if (exists) runCatching { folder?.name }.getOrNull() else null
            val canWrite = exists && runCatching { folder?.canWrite() == true }.getOrDefault(false)
            val hasMarker = exists && folder != null && try {
                hasChildNamed(context, folder.uri, SELECTION_MARKER_FILE_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Could not list $uriString for the Selection marker", e)
                false
            }
            val volumeLabel = SafTreeDisplayPath.removableVolumeId(uriString)?.let { volumeId ->
                ExternalStorageDetector(context).detectExternalVolumes()
                    .firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }
                    ?.description
            }
            FolderDescription(
                uri = uriString,
                displayPath = SafTreeDisplayPath.segments(
                    treeUri = uriString,
                    volumeLabel = volumeLabel,
                    providerTreeName = name,
                ),
                name = name,
                hasPersistedGrant = hasGrant,
                exists = exists,
                canWrite = canWrite,
                hasSelectionMarker = hasMarker,
            )
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
            selectionDestination(context, destFolderUri, sorting)
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

    /**
     * The destination of a transfer from [destFolderUri] (the opened folder), resolved by
     * [SelectionDestinationPlan] — the same function the Settings preview shows, so the
     * two cannot disagree. Whether the opened folder is itself a Selection folder (second
     * pass) is looked up on the resolved root only in Working folder mode, where it matters.
     */
    private suspend fun selectionDestination(
        context: Context,
        destFolderUri: Uri,
        sorting: Boolean,
    ): SelectionDestination {
        val settings = destinationSettings(sorting)
        val opened = destFolderUri.toString()
        val plan = SelectionDestinationPlan.resolve(settings, OpenedFolder(uri = opened))
        return SelectionDestination(
            rootUri = plan.rootUri?.let(Uri::parse) ?: destFolderUri,
            folderName = plan.folderName,
        ) { destRoot ->
            if (!SelectionDestinationPlan.needsOpenedFolderDetails(settings)) {
                plan.folderName
            } else {
                val folder = OpenedFolder(
                    uri = opened,
                    name = destRoot.name,
                    hasSelectionMarker = runCatching {
                        hasChildNamed(context, destRoot.uri, SELECTION_MARKER_FILE_NAME)
                    }.getOrDefault(false),
                )
                SelectionDestinationPlan.resolve(settings, folder).folderName
            }
        }
    }

    /**
     * The settings [SelectionDestinationPlan] needs. The Selection name is read strictly —
     * a failing read fails the transfer with its message; the second-pass name and the
     * custom destination fall back to their defaults on a failed read, as they always did.
     */
    private suspend fun destinationSettings(sorting: Boolean): DestinationSettings {
        val primaryFolderName = settingsRepository.selectionFolderName.first()
        val secondaryFolderName = try {
            settingsRepository.secondarySelectionFolderName.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Second-pass folder name unreadable, using the default", e)
            null
        }
        val customDestUriStr = try {
            settingsRepository.customSelectionDestinationUri.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Custom Selection destination unreadable, using the working folder", e)
            null
        }
        return DestinationSettings(
            customDestinationUri = customDestUriStr,
            selectionFolderName = primaryFolderName,
            secondPassFolderName = secondaryFolderName.orEmpty(),
            sortingEnabled = sorting,
        )
    }

    /**
     * Whether the directory [directoryUri] (a document URI inside a tree) has a child named
     * [childName]: one `ContentResolver.query` over its children, display names only —
     * never `DocumentFile.findFile`, which costs a binder call per child.
     */
    private fun hasChildNamed(context: Context, directoryUri: Uri, childName: String): Boolean {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            directoryUri,
            DocumentsContract.getDocumentId(directoryUri),
        )
        resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) == childName) return true
                }
            }
        return false
    }

    override suspend fun resolveDimensions(
        context: Context,
        uris: Collection<String>,
    ): Map<String, ImageDimensions> = withContext(ioDispatcher) {
        localImageSource.resolveDimensions(uris)
    }
}
