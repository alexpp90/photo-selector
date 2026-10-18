package com.photoselector.saf

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Non-destructive SAF copy and move of photographs and their companions
 * (`docs/shared/FILE_OPERATIONS.md`), shared by both Android products:
 *
 * - every member of a bundle gets the same collision-free `-N` name ([BundleNaming]); nothing
 *   is overwritten;
 * - a copy is verified (created name and byte size) before its source is deleted, and a
 *   half-written copy is removed;
 * - a source that cannot be deleted after a verified copy is a partial outcome, never a
 *   deleted copy;
 * - at most [maxConcurrentTransfers] transfers run at once, and transfers into one destination
 *   are serialised (finding D-5: no overwritten file, no second `Selection (1)` folder);
 * - an undo restores into the folder the photo came from, resolved by the provider's own
 *   document id (D-3/D-4).
 *
 * The product configures what differs ([SafTransferPolicy], [SelectionSubfolderRule]) and keeps
 * one instance for the life of the process, so the semaphore and the destination locks are
 * really shared.
 */
class SafTransferEngine(
    private val subfolderRule: SelectionSubfolderRule,
    private val policy: SafTransferPolicy,
    maxConcurrentTransfers: Int = MAX_CONCURRENT_TRANSFERS,
) {
    companion object {
        private const val TAG = "SafTransferEngine"

        /** Dropped into every Selection folder so scans skip it and a card is recognised as culled. */
        const val SELECTION_MARKER_FILE_NAME = ".photoselector-selection"
        const val RAW_SUBFOLDER = "RAW"
        const val JPEG_SUBFOLDER = "JPEG"
        const val MAX_CONCURRENT_TRANSFERS = 2
        private const val SINGLE_FOLDER_KEY = "TARGET"
    }

    private val transferSemaphore = Semaphore(maxConcurrentTransfers)
    private val destinationLocks = ConcurrentHashMap<String, Mutex>()

    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var documentMover: SafDocumentMover = DefaultSafDocumentMover

    private fun destinationLockFor(destinationKey: String): Mutex =
        destinationLocks.getOrPut(destinationKey) { Mutex() }

    private sealed class CopyStepResult {
        data class Success(val destinationFile: DocumentFile) : CopyStepResult()
        data class Failure(val reason: String) : CopyStepResult()
    }

    private data class ResolvedSource(
        val uri: Uri,
        val doc: DocumentFile?,
        val fileName: String?,
        val folderKey: String,
        val targetFolder: DocumentFile?,
    )

    // ── Transfer ─────────────────────────────────────────────────────────

    /**
     * Copy or move a companion bundle (or a single file) into the folder [destination] names,
     * using a single cursor query per target folder and one collision suffix across the bundle.
     *
     * The Selection folder is always found or created; [sorting] only decides whether `RAW/`
     * and `JPEG/` are created inside it. Returns one result per source, in order. Cancellation
     * is rethrown; any other exception fails every source with its message.
     *
     * [unresolvedSourceError] replaces the "Could not write to …" feedback when a source or the
     * destination cannot be resolved (PhotoTok's single-file move reports "Copy step failed").
     */
    suspend fun transferBundle(
        storage: SafStorage,
        sourceUris: List<Uri>,
        destination: SelectionDestination,
        sorting: Boolean,
        isMove: Boolean,
        parentDocumentId: String? = null,
        unresolvedSourceError: String? = null,
    ): List<FileOperationResult> = transferSemaphore.withPermit {
        withContext(ioDispatcher) {
            if (sourceUris.isEmpty()) return@withContext emptyList()
            fun failAll(error: String?) = sourceUris.map { uri ->
                FileOperationResult.failure(uri.toString(), error, parentDocumentId)
            }
            try {
                destinationLockFor(destination.rootUri.toString()).withLock {
                    val destRoot = storage.treeDocument(destination.rootUri)
                        ?: return@withContext failAll(
                            unresolvedSourceError ?: "Could not write to the ${destination.folderName} folder",
                        )
                    val folderName = destination.folderNameIn(destRoot)
                    val selectionDir = destRoot.findFile(folderName)
                        ?: destRoot.createDirectory(folderName)
                        ?: return@withContext failAll(
                            unresolvedSourceError ?: "Could not write to the $folderName folder",
                        )
                    if (policy.markerTiming == MarkerTiming.WHEN_FOLDER_RESOLVED) {
                        ensureSelectionMarker(selectionDir)
                    }

                    val results = when (policy.bundleAtomicity) {
                        BundleAtomicity.ALL_OR_NOTHING -> executeAllOrNothing(
                            storage = storage,
                            sourceUris = sourceUris,
                            treeRootUri = destination.rootUri,
                            baseTargetFolder = selectionDir,
                            sorting = sorting,
                            targetFolderLabel = folderName,
                            isMove = isMove,
                            parentDocumentId = parentDocumentId,
                            unresolvedSourceError = unresolvedSourceError,
                        )
                        BundleAtomicity.PER_FILE -> executePerFile(
                            storage = storage,
                            sourceUris = sourceUris,
                            treeRootUri = destination.rootUri,
                            selectionDir = selectionDir,
                            sorting = sorting,
                            folderName = folderName,
                            isMove = isMove,
                            parentDocumentId = parentDocumentId,
                            unresolvedSourceError = unresolvedSourceError,
                        )
                    }
                    if (policy.markerTiming == MarkerTiming.AFTER_FIRST_TRANSFER &&
                        results.any { it.success || it.partial }
                    ) {
                        ensureSelectionMarker(selectionDir)
                    }
                    results
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transfer bundle: $sourceUris", e)
                failAll(e.message)
            }
        }
    }

    /**
     * One file, [transferBundle] semantics, except that a source which cannot be resolved fails
     * before the destination is touched: no Selection folder and no marker are created for it
     * (PhotoTok's single copy/move; its move reports [unresolvedSourceError] "Copy step failed").
     */
    suspend fun transferSingle(
        storage: SafStorage,
        sourceUri: Uri,
        destination: SelectionDestination,
        sorting: Boolean,
        isMove: Boolean,
        parentDocumentId: String? = null,
        unresolvedSourceError: String? = null,
    ): FileOperationResult {
        val resolvable = withContext(ioDispatcher) {
            try {
                storage.singleDocument(sourceUri)?.name != null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        if (!resolvable) {
            return FileOperationResult.failure(
                sourceUri.toString(),
                unresolvedSourceError ?: "Could not write to the ${destination.folderName} folder",
                parentDocumentId,
            )
        }
        return transferBundle(
            storage, listOf(sourceUri), destination, sorting, isMove, parentDocumentId, unresolvedSourceError,
        ).single()
    }

    /** Unlink [uri]. SAF deletes have no trash behind them. */
    suspend fun delete(storage: SafStorage, uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            storage.singleDocument(uri)?.delete() ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete image: $uri", e)
            false
        }
    }

    // ── Undo ─────────────────────────────────────────────────────────────

    /**
     * Take filed photographs back out of the Selection and restore them into the folder
     * identified by [parentDocumentId] within [rootFolderUri] (or the root itself), without
     * creating a `Selection/` there.
     *
     * A copy listed in [copiesOfSurvivingOriginals] whose original still exists (a partial move)
     * is deleted rather than restored: restoring would put a duplicate next to the original. A
     * copy whose original has since disappeared is the only one left, so it is restored.
     */
    suspend fun undoMoveBundle(
        storage: SafStorage,
        destinationUris: List<String>,
        rootFolderUri: Uri,
        parentDocumentId: String?,
        copiesOfSurvivingOriginals: Map<String, String> = emptyMap(),
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
                val deletableCopies = copiesOfSurvivingOriginals.filter { (_, original) ->
                    storage.singleDocument(Uri.parse(original))?.exists() == true
                }.keys
                var deleteFailed: String? = null
                deletableCopies.forEach { copyUri ->
                    if (!delete(storage, Uri.parse(copyUri))) deleteFailed = copyUri
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
                val restore = resolveRestoreFolder(storage, rootFolderUri, parentDocumentId)
                    ?: return@withContext FileOperationResult.failure(
                        sourceUri = primarySource,
                        error = "Could not resolve the original folder",
                        parentDocumentId = parentDocumentId,
                    )
                val restoreFolder = restore.folder
                val results = executeAllOrNothing(
                    storage = storage,
                    sourceUris = toRestore.map { Uri.parse(it) },
                    treeRootUri = rootFolderUri,
                    baseTargetFolder = restoreFolder,
                    sorting = false,
                    targetFolderLabel = restoreFolder.name ?: "original folder",
                    isMove = true,
                    parentDocumentId = parentDocumentId,
                    unresolvedSourceError = null,
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
     * One result for a whole bundle: success when every member succeeded, partial (with the
     * union of [FileOperationResult.copiesOfSurvivingOriginals]) when any member is partial,
     * failure otherwise.
     */
    fun aggregateBundleResults(
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

    /** The folder an undo restores into, and whether it had to fall back to the opened folder. */
    data class RestoreFolder(val folder: DocumentFile, val fellBackToRoot: Boolean)

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
    fun resolveRestoreFolder(
        storage: SafStorage,
        rootFolderUri: Uri,
        parentDocumentId: String?,
    ): RestoreFolder? {
        val rootDoc = storage.treeDocument(rootFolderUri) ?: return null
        if (parentDocumentId.isNullOrBlank()) return RestoreFolder(rootDoc, fellBackToRoot = false)

        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(rootFolderUri)
        } catch (_: Exception) {
            null
        }
        if (parentDocumentId == treeDocId) return RestoreFolder(rootDoc, fellBackToRoot = false)

        val resolved = try {
            val subUri = DocumentsContract.buildDocumentUriUsingTree(rootFolderUri, parentDocumentId)
            storage.treeDocument(subUri)?.takeIf { it.exists() && it.isDirectory }
        } catch (_: Exception) {
            null
        }
        return if (resolved != null) {
            RestoreFolder(resolved, fellBackToRoot = false)
        } else {
            RestoreFolder(rootDoc, fellBackToRoot = true)
        }
    }

    // ── Bundle strategies ────────────────────────────────────────────────

    private fun resolveSources(
        storage: SafStorage,
        sourceUris: List<Uri>,
        baseTargetFolder: DocumentFile,
        sorting: Boolean,
        targetFoldersByKey: LinkedHashMap<String, DocumentFile>,
    ): List<ResolvedSource> {
        val docs = sourceUris.map { storage.singleDocument(it) }
        val bundleNames = docs.mapNotNull { it?.name }
        return sourceUris.mapIndexed { index, uri ->
            val doc = docs[index]
            val name = doc?.name
            if (name == null) {
                ResolvedSource(uri, doc, null, SINGLE_FOLDER_KEY, null)
            } else if (sorting) {
                val subfolder = subfolderRule.subfolderFor(name, bundleNames)
                val target = targetFoldersByKey.getOrPut(subfolder.name) {
                    subfolderIn(subfolder, baseTargetFolder)
                }
                ResolvedSource(uri, doc, name, subfolder.name, target)
            } else {
                targetFoldersByKey.getOrPut(SINGLE_FOLDER_KEY) { baseTargetFolder }
                ResolvedSource(uri, doc, name, SINGLE_FOLDER_KEY, baseTargetFolder)
            }
        }
    }

    private fun collisionFreeNames(
        storage: SafStorage,
        resolvedSources: List<ResolvedSource>,
        targetFoldersByKey: Map<String, DocumentFile>,
    ): Map<String, String> {
        val existingNamesByFolder = targetFoldersByKey.mapValues { (_, folder) ->
            queryExistingDisplayNames(storage, folder)
        }
        val validNames = resolvedSources.mapNotNull { it.fileName }
        val folderKeyByName = resolvedSources
            .filter { it.fileName != null }
            .associate { it.fileName!! to it.folderKey }
        return BundleNaming.resolveCollisionFreeBundleNames(
            fileNames = validNames,
            primaryStem = validNames.firstOrNull()?.let(BundleNaming::extractPrimaryStem).orEmpty(),
            existingNamesByFolder = existingNamesByFolder,
            folderForFile = { folderKeyByName[it] ?: SINGLE_FOLDER_KEY },
        )
    }

    /** [BundleAtomicity.ALL_OR_NOTHING]: stage and verify every copy, then delete every source. */
    private suspend fun executeAllOrNothing(
        storage: SafStorage,
        sourceUris: List<Uri>,
        treeRootUri: Uri,
        baseTargetFolder: DocumentFile,
        sorting: Boolean,
        targetFolderLabel: String,
        isMove: Boolean,
        parentDocumentId: String?,
        unresolvedSourceError: String?,
    ): List<FileOperationResult> {
        val targetFoldersByKey = LinkedHashMap<String, DocumentFile>()
        val resolvedSources = resolveSources(storage, sourceUris, baseTargetFolder, sorting, targetFoldersByKey)

        if (resolvedSources.any { it.doc == null || it.fileName == null || it.targetFolder == null }) {
            return resolvedSources.map { src ->
                FileOperationResult.failure(
                    sourceUri = src.uri.toString(),
                    error = unresolvedSourceError ?: "Could not write to the $targetFolderLabel folder",
                    parentDocumentId = parentDocumentId,
                )
            }
        }

        val desiredNames = collisionFreeNames(storage, resolvedSources, targetFoldersByKey)

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
                atomicMove(storage, single.uri, treeRootUri, targetFolder)?.let { movedStr ->
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

        // Phase 1: Copy and byte-verify every member of the bundle before
        // deleting any source file. If any copy fails, clean up all newly
        // created destination files from this transaction and keep all sources intact.
        val stagedCopies = ArrayList<Pair<ResolvedSource, DocumentFile>>(resolvedSources.size)
        for (src in resolvedSources) {
            val doc = src.doc!!
            val name = src.fileName!!
            val copyResult = try {
                copyToTargetFolder(
                    storage = storage,
                    sourceUri = src.uri,
                    sourceDoc = doc,
                    targetFolder = src.targetFolder!!,
                    mimeType = doc.type ?: "application/octet-stream",
                    desiredName = desiredNames[name] ?: name,
                    selectionFolderName = targetFolderLabel,
                )
            } catch (e: Exception) {
                // CancellationException included: never leave staged copies behind.
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
                is CopyStepResult.Success -> stagedCopies.add(src to copyResult.destinationFile)
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
            if (!deleteSourceAfterVerifiedCopy(storage, src.uri)) {
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

    /** [BundleAtomicity.PER_FILE]: each member is transferred on its own under the bundle's names. */
    private suspend fun executePerFile(
        storage: SafStorage,
        sourceUris: List<Uri>,
        treeRootUri: Uri,
        selectionDir: DocumentFile,
        sorting: Boolean,
        folderName: String,
        isMove: Boolean,
        parentDocumentId: String?,
        unresolvedSourceError: String?,
    ): List<FileOperationResult> {
        val targetFoldersByKey = LinkedHashMap<String, DocumentFile>()
        val resolvedSources = resolveSources(storage, sourceUris, selectionDir, sorting, targetFoldersByKey)
        val desiredNames = collisionFreeNames(storage, resolvedSources, targetFoldersByKey)

        return resolvedSources.map { src ->
            val sourceStr = src.uri.toString()
            val doc = src.doc
            val name = src.fileName
            val targetFolder = src.targetFolder
            if (doc == null || name == null || targetFolder == null) {
                FileOperationResult.failure(
                    sourceStr,
                    unresolvedSourceError ?: "Could not write to the $folderName folder",
                    parentDocumentId,
                )
            } else {
                transferOne(
                    storage = storage,
                    sourceUri = src.uri,
                    sourceDoc = doc,
                    fileName = name,
                    desiredName = desiredNames[name] ?: name,
                    treeRootUri = treeRootUri,
                    targetFolder = targetFolder,
                    selectionFolderName = folderName,
                    isMove = isMove,
                    parentDocumentId = parentDocumentId,
                )
            }
        }
    }

    private suspend fun transferOne(
        storage: SafStorage,
        sourceUri: Uri,
        sourceDoc: DocumentFile,
        fileName: String,
        desiredName: String,
        treeRootUri: Uri,
        targetFolder: DocumentFile,
        selectionFolderName: String,
        isMove: Boolean,
        parentDocumentId: String?,
    ): FileOperationResult {
        val source = sourceUri.toString()
        if (sourceUri == targetFolder.uri) {
            return FileOperationResult.success(source, source, parentDocumentId = parentDocumentId)
        }

        // Attempt atomic DocumentsContract.moveDocument when moving without a
        // collision rename and the provider supports FLAG_SUPPORTS_MOVE.
        if (isMove && desiredName == fileName) {
            atomicMove(storage, sourceUri, treeRootUri, targetFolder)?.let { moved ->
                return FileOperationResult.success(source, moved, parentDocumentId = parentDocumentId)
            }
        }

        return when (
            val copyResult = copyToTargetFolder(
                storage = storage,
                sourceUri = sourceUri,
                sourceDoc = sourceDoc,
                targetFolder = targetFolder,
                mimeType = sourceDoc.type ?: "application/octet-stream",
                desiredName = desiredName,
                selectionFolderName = selectionFolderName,
            )
        ) {
            is CopyStepResult.Failure -> FileOperationResult.failure(source, copyResult.reason, parentDocumentId)
            is CopyStepResult.Success -> {
                val destination = copyResult.destinationFile.uri.toString()
                if (!isMove || deleteSourceAfterVerifiedCopy(storage, sourceUri)) {
                    FileOperationResult.success(source, destination, parentDocumentId = parentDocumentId)
                } else {
                    // The copy is verified on disk, so never delete it when source
                    // removal fails; report a partial outcome instead.
                    FileOperationResult.partial(
                        sourceUri = source,
                        destinationUri = destination,
                        error = "Copied to $selectionFolderName — original could not be removed",
                        parentDocumentId = parentDocumentId,
                    )
                }
            }
        }
    }

    // ── Primitives ───────────────────────────────────────────────────────

    /** The moved document's URI when the provider moved [sourceUri] atomically, else null. */
    private fun atomicMove(
        storage: SafStorage,
        sourceUri: Uri,
        treeRootUri: Uri,
        targetFolder: DocumentFile,
    ): String? {
        val resolver = storage.contentResolver
        val sourceParentUri = resolveSourceParentUri(sourceUri, treeRootUri)
        // The mover is consulted even without a parent (it answers false), as both products did.
        if (!documentMover.canMoveDocument(resolver, sourceUri, sourceParentUri, targetFolder.uri) ||
            sourceParentUri == null
        ) {
            return null
        }
        return documentMover.moveDocument(
            contentResolver = resolver,
            sourceUri = sourceUri,
            sourceParentUri = sourceParentUri,
            targetFolderUri = targetFolder.uri,
        )?.toString()
    }

    private suspend fun deleteSourceAfterVerifiedCopy(storage: SafStorage, sourceUri: Uri): Boolean = try {
        delete(storage, sourceUri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete source after verified copy: $sourceUri", e)
        false
    }

    /** Drop the Selection marker into [selectionDir] (best effort; never fails a transfer). */
    fun ensureSelectionMarker(selectionDir: DocumentFile) {
        try {
            if (selectionDir.findFile(SELECTION_MARKER_FILE_NAME) == null) {
                selectionDir.createFile("application/octet-stream", SELECTION_MARKER_FILE_NAME)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not create $SELECTION_MARKER_FILE_NAME in ${selectionDir.uri}: ${e.message}")
        }
    }

    /**
     * Stream-copy [sourceUri] into [targetFolder] as [desiredName], verifying
     * both the created display name and the destination byte size before
     * reporting success.
     */
    private fun copyToTargetFolder(
        storage: SafStorage,
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

        val resolver = storage.contentResolver
        val sourceSize = sourceDoc.length()
        var bytesCopied = -1L

        try {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(destFile.uri)?.use { output ->
                    bytesCopied = input.copyTo(output)
                    output.flush()
                }
            }
            if (bytesCopied >= 0L && policy.syncDestination(destFile.uri)) {
                try {
                    resolver.openFileDescriptor(destFile.uri, "rw")?.use { pfd ->
                        pfd.fileDescriptor.sync()
                    }
                } catch (_: Exception) {
                    // Best-effort fsync when the destination provider supports file descriptor sync.
                }
            }
        } catch (e: Exception) {
            // CancellationException included. Never leave a half-written file in the
            // Selection: a truncated photograph that looks filed is worse than a failed move.
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

        return CopyStepResult.Success(destFile)
    }

    /**
     * List existing child display names in [targetFolder] using a **single**
     * `ContentResolver` cursor query (`COLUMN_DISPLAY_NAME`), falling back to
     * [DocumentFile.listFiles] only when [targetFolder] is not backed by a tree
     * document URI.
     */
    internal fun queryExistingDisplayNames(
        storage: SafStorage,
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
                storage.contentResolver.query(
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

    /** The `RAW/` or `JPEG/` folder inside [selectionDir] (found or created), or [selectionDir] itself. */
    internal fun subfolderIn(subfolder: SelectionSubfolder, selectionDir: DocumentFile): DocumentFile {
        fun sub(name: String): DocumentFile? =
            selectionDir.findFile(name) ?: selectionDir.createDirectory(name)
        return when (subfolder) {
            SelectionSubfolder.RAW -> sub(RAW_SUBFOLDER) ?: selectionDir
            SelectionSubfolder.JPEG -> sub(JPEG_SUBFOLDER) ?: selectionDir
            SelectionSubfolder.ROOT -> selectionDir
        }
    }
}
