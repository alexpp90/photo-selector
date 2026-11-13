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
import com.photoselectortoolbox.data.model.SelectionFolderState
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.di.IoDispatcher
import com.photoselectortoolbox.domain.curation.DestinationSettings
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.curation.FolderKey
import com.photoselectortoolbox.domain.curation.OpenedFolder
import com.photoselectortoolbox.domain.curation.SelectionDestinationPlan
import com.photoselectortoolbox.domain.curation.TransferSpace
import com.photoselectortoolbox.domain.format.DestinationLabels
import com.photoselectortoolbox.domain.format.ImageFormats
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : ImageRepository {

    constructor(
        localImageSource: LocalImageSource,
        androidExifReader: AndroidExifReader,
        mediaStoreReader: MediaStoreReader,
        settingsRepository: SettingsRepository,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        localImageSource = localImageSource,
        androidExifReader = androidExifReader,
        mediaStoreReader = mediaStoreReader,
        settingsRepository = settingsRepository,
        folderAccessManager = FolderAccessManager(),
        ioDispatcher = ioDispatcher,
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
         * is at the destination as exactly the document its transfer wrote, with exactly that byte
         * size. Both maps share their keys: [com.photoselectortoolbox.data.model.KeptFiles] records the document id each transfer
         * wrote, and the listing ([SelectionFolderState.filesByDocumentId]) is by document id, so
         * a same-named file elsewhere in the Selection never stands in for a missing copy.
         *
         * No stem fallback (FD-C-11): a kept `DSC0001.ARW` is not verified by a `DSC0001.JPG`
         * that did arrive. A source of unknown (≤ 0) size cannot be verified. Read by the Done
         * card ([com.photoselectortoolbox.viewmodel.DoneSelection]).
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
    internal var ioDispatcher: CoroutineDispatcher = ioDispatcher
        set(value) {
            field = value
            engine.ioDispatcher = value
        }

    init {
        engine.ioDispatcher = ioDispatcher
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
            val children = if (exists && folder != null) {
                try {
                    listChildren(context, folder.uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not list $uriString for the Selection marker", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            val hasMarker = children.any { it.name == SELECTION_MARKER_FILE_NAME }
            val authority = folderUri.authority
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
                    labels = DestinationLabels.pathLabels(context),
                ),
                name = name,
                hasPersistedGrant = hasGrant,
                exists = exists,
                canWrite = canWrite,
                hasSelectionMarker = hasMarker,
                key = folderKeyOf(folderUri),
                childFolders = if (authority == null) {
                    emptyMap()
                } else {
                    children.filter { it.isDirectory }.associate { it.name to FolderKey(authority, it.documentId) }
                },
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

    /**
     * One query for both columns of the document. A query that fails or finds no row reads as
     * null, which the caller treats as "cannot be read" and reports; cancellation propagates.
     */
    override suspend fun documentIdentity(context: Context, uri: Uri): FileIdentity? =
        withContext(ioDispatcher) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    FileIdentity(
                        size = if (cursor.isNull(0)) 0L else cursor.getLong(0),
                        lastModified = if (cursor.isNull(1)) 0L else cursor.getLong(1),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the identity of $uri", e)
                null
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
            neededBytes = item.allFiles.sumOf { it.fileSize.coerceAtLeast(0L) },
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
        /** The bundle's size from the listing, checked against the destination's free space; null: no check. */
        neededBytes: Long? = null,
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
        if (neededBytes != null && neededBytes > 0L) {
            val (source, target) = withContext(ioDispatcher) {
                volumeOf(context, sourceUris.first()) to volumeOf(context, destination.rootUri)
            }
            if (TransferSpace.refuses(neededBytes, source, target, isMove)) {
                val message = TransferSpace.refusalCode(neededBytes, target?.availableBytes ?: 0L, isMove)
                Log.w(TAG, "$message (${destination.rootUri})")
                return sourceUris.map { FileOperationResult.failure(it.toString(), message, parentDocumentId) }
            }
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
     * The provider root [uri] lives on and the free space it reports, from one query of the
     * provider's roots. Null when the provider cannot be asked or the root is not found: the
     * transfer then goes ahead unchecked, as it always did.
     */
    private fun volumeOf(context: Context, uri: Uri): TransferSpace.Volume? {
        val authority = uri.authority ?: return null
        return try {
            val documentId = if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                DocumentsContract.getTreeDocumentId(uri)
            }
            val roots = mutableMapOf<String, Long?>()
            context.contentResolver.query(
                DocumentsContract.buildRootsUri(authority),
                arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rootDoc = cursor.getString(0) ?: continue
                    roots[rootDoc] = if (cursor.isNull(1)) null else cursor.getLong(1)
                }
            }
            val root = TransferSpace.rootFor(documentId, roots.keys) ?: return null
            TransferSpace.Volume(authority, root, roots[root])
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: a provider that cannot report its roots is not refused (see refuses()).
            Log.w(TAG, "Could not read the free space for $uri", e)
            null
        }
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
        val plan = SelectionDestinationPlan.resolve(
            settings = settings,
            openedFolder = OpenedFolder(uri = opened, key = folderKeyOf(destFolderUri)),
            customSelectionKey = settings.customUri?.let { custom ->
                withContext(ioDispatcher) { customSelectionKey(context, Uri.parse(custom), settings.selectionName) }
            },
        )
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
    private fun hasChildNamed(context: Context, directoryUri: Uri, childName: String): Boolean =
        listChildren(context, directoryUri).any { it.name == childName }

    private data class Child(val name: String, val documentId: String, val isDirectory: Boolean)

    /** The children of [directoryUri] (a tree or a document inside one): one children query. */
    private fun listChildren(context: Context, directoryUri: Uri): List<Child> {
        val documentId = folderKeyOf(directoryUri)?.documentId
            ?: throw IllegalArgumentException("Not a folder of a document tree: $directoryUri")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val children = mutableListOf<Child>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                children += Child(name, id, cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return children
    }

    /**
     * The provider key of the folder [uri] names: its document id when it is a document inside
     * a tree, else the tree's own. Null for a URI that names neither.
     */
    private fun folderKeyOf(uri: Uri): FolderKey? {
        val authority = uri.authority ?: return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        return FolderKey(authority, documentId)
    }

    /**
     * The key of the existing `<custom>/<selectionName>` folder, or null when there is none or
     * the custom folder cannot be listed (the transfer then targets it as before and reports
     * its own failure). Exact name, as the engine finds the folder it files into.
     */
    private fun customSelectionKey(context: Context, customUri: Uri, selectionName: String): FolderKey? {
        val authority = customUri.authority ?: return null
        return try {
            listChildren(context, customUri)
                .firstOrNull { it.isDirectory && it.name == selectionName }
                ?.let { FolderKey(authority, it.documentId) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list the custom destination $customUri", e)
            null
        }
    }

    /**
     * The Selection folder of a transfer from [folderUri], resolved by the transfer's own
     * [selectionDestination] and found by listing the destination root for its exact name — a
     * document id is never taken apart. Its files are listed with their sizes, one level of
     * subfolders included (`RAW/`, `JPEG/`), each folder in one children query. A lookup that
     * fails is logged and reads as "no Selection folder": the Done card then offers neither
     * *Open Selection* nor a safe-to-format verdict, which is the safe answer.
     */
    override suspend fun selectionFolder(context: Context, folderUri: Uri): SelectionFolderState? = try {
        val destination = selectionDestination(context, folderUri, sorting = false)
        withContext(ioDispatcher) {
            val root = DocumentFile.fromTreeUri(context, destination.rootUri)
            val name = root?.let(destination.folderNameIn) ?: destination.folderName
            val folder = listChildren(context, destination.rootUri).firstOrNull { it.isDirectory && it.name == name }
                ?: return@withContext null
            val uri = DocumentsContract.buildDocumentUriUsingTree(destination.rootUri, folder.documentId)
            val source = volumeOf(context, folderUri)
            val target = volumeOf(context, uri)
            SelectionFolderState(
                uri = uri.toString(),
                name = name,
                filesByDocumentId = filesWithSizes(context, uri, depth = 1),
                onSourceVolume = if (source == null || target == null) {
                    null
                } else {
                    source.authority == target.authority && source.rootDocumentId == target.rootDocumentId
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not look up the Selection folder of $folderUri", e)
        null
    }

    /** Files under [directoryUri] by document id with their provider-reported size, [depth] levels of subfolders down. */
    private fun filesWithSizes(context: Context, directoryUri: Uri, depth: Int): Map<String, Long> {
        val documentId = folderKeyOf(directoryUri)?.documentId ?: return emptyMap()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val files = mutableMapOf<String, Long>()
        val folders = mutableListOf<Uri>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += DocumentsContract.buildDocumentUriUsingTree(directoryUri, id)
                } else {
                    // An unknown size is -1: it never verifies a kept file.
                    files[id] = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                }
            }
        }
        if (depth > 0) folders.forEach { files.putAll(filesWithSizes(context, it, depth - 1)) }
        return files
    }

    override suspend fun resolveDimensions(
        context: Context,
        uris: Collection<String>,
    ): Map<String, ImageDimensions> = withContext(ioDispatcher) {
        localImageSource.resolveDimensions(uris)
    }
}
