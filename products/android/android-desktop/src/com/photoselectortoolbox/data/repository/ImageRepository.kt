package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import com.photoselector.core.model.ExifData
import com.photoselector.saf.FolderAccessManager
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import kotlinx.coroutines.flow.Flow

/**
 * Everything the selector needs from storage, with the storage backend hidden.
 *
 * Callers above this interface must never branch on a URI scheme, and must never
 * reach past it for a storage API of their own. What a folder URI is worth
 * before discovery starts is a storage question, and it belongs here rather than
 * in a ViewModel.
 */
interface ImageRepository {
    /**
     * Claim a folder for this session: persist the permission grant and confirm
     * the folder is still there. Returns its display name, or `null` when the
     * grant was revoked or the directory has gone.
     *
     * This exists so the ViewModel does not touch `DocumentFile` or
     * [FolderAccessManager] itself. It did, once, and the cost was not
     * only the layering: it left the tests with no way to open a folder without
     * a real SAF grant, so both suites reached for a second URI scheme purely to
     * skip past it. A fake repository can satisfy this; a `DocumentFile` cannot
     * be faked at all.
     */
    suspend fun openFolder(context: Context, folderUri: Uri): String?

    /**
     * Progressive folder discovery. Emissions are cumulative and append-only —
     * a consumer merges by appending and must not re-sort what is already
     * visible.
     */
    fun discoverImages(folderUri: Uri): Flow<List<ImageItem>>

    /**
     * The complete folder, once.
     *
     * For consumers that cannot use a photograph until they have all of them —
     * duplicate detection, statistics. **Use this instead of
     * `discoverImages(uri).first()`**: since discovery became progressive, the
     * first emission is a batch of
     * [com.photoselectortoolbox.data.source.LocalImageSourceImpl.FIRST_BATCH_SIZE]
     * images, not the folder, so `first()` would silently analyse 24 photographs
     * and report the answer for the whole folder.
     */
    suspend fun discoverAllImages(folderUri: Uri): List<ImageItem>

    suspend fun getExifData(context: Context, uri: Uri): ExifData?

    suspend fun deleteImage(context: Context, uri: Uri): Boolean

    /**
     * Whether a delete of [uri] is recoverable rather than final.
     *
     * SAF offers no trash, so today this is always false and the UI offers no
     * UNDO on a delete. It stays a question rather than becoming a constant the
     * UI assumes: it is a property of the storage backend, and the selector
     * deciding that for itself is what this interface exists to prevent.
     */
    fun canTrash(uri: Uri): Boolean

    /**
     * Bring a trashed file back. Only meaningful where [canTrash] is true;
     * otherwise a delete is final and this returns false, which is the signal to
     * offer no UNDO at all rather than one that quietly fails.
     */
    suspend fun restoreFromTrash(context: Context, uri: Uri): Boolean

    /**
     * Move one image into the Selection of [destFolderUri].
     *
     * Returns the destination URI so the move can be reversed by
     * [undoMove]; a `Boolean` here is what previously made repository-level
     * undo impossible.
     */
    suspend fun moveImage(
        context: Context,
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult

    /** Copy one image into the Selection of [destFolderUri]. The source stays put. */
    suspend fun copyImage(
        context: Context,
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult

    /**
     * Reverse a completed move: take the file back out of the Selection and
     * return it to [restoreFolderUri].
     *
     * Sorting is deliberately off — putting it back through the RAW/JPEG sorter
     * would be a second filing decision, not an undo. For bundle-aware moves
     * with a recorded sub-folder (`parentDocumentId`), use [undoMoveBundle].
     */
    suspend fun undoMove(
        context: Context,
        movedTo: Uri,
        restoreFolderUri: Uri,
    ): FileOperationResult

    /**
     * Move an [ImageItem] and all of its [ImageItem.companions] into the
     * Selection of [destFolderUri] using bundle-wide collision-free naming.
     */
    suspend fun moveBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult = moveImage(
        context = context,
        sourceUri = Uri.parse(item.uri),
        destFolderUri = destFolderUri,
        sorting = sorting,
    ).let { result ->
        if (item.parentDocumentId != null && result.parentDocumentId == null) {
            result.copy(parentDocumentId = item.parentDocumentId)
        } else {
            result
        }
    }

    /**
     * Copy an [ImageItem] and all of its [ImageItem.companions] into the
     * Selection of [destFolderUri] using bundle-wide collision-free naming.
     */
    suspend fun copyBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult = copyImage(
        context = context,
        sourceUri = Uri.parse(item.uri),
        destFolderUri = destFolderUri,
        sorting = sorting,
    ).let { result ->
        if (item.parentDocumentId != null && result.parentDocumentId == null) {
            result.copy(parentDocumentId = item.parentDocumentId)
        } else {
            result
        }
    }

    /**
     * Reverse a completed bundle move: move all [destinationUris] back into
     * their original [parentDocumentId] sub-folder within [rootFolderUri] (or
     * [rootFolderUri] when [parentDocumentId] is null) without creating a
     * `Selection/` folder and without overwriting any existing file.
     *
     * After a *partial* move, [copiesOfSurvivingOriginals] (destination copy → original that
     * could not be removed) lists copies that are deleted instead of restored while their
     * original still exists; everything else is restored as usual.
     */
    suspend fun undoMoveBundle(
        context: Context,
        destinationUris: List<String>,
        rootFolderUri: Uri,
        parentDocumentId: String? = null,
        copiesOfSurvivingOriginals: Map<String, String> = emptyMap(),
    ): FileOperationResult {
        val first = destinationUris.firstOrNull()
            ?: return FileOperationResult.failure("", "No files to restore", parentDocumentId)
        return undoMove(context, Uri.parse(first), rootFolderUri)
    }

    /**
     * Delete an [ImageItem] and all of its [ImageItem.companions].
     */
    suspend fun deleteBundle(
        context: Context,
        item: ImageItem,
    ): Boolean = item.allFiles.all { deleteImage(context, Uri.parse(it.uri)) }

    suspend fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int>

    /**
     * Header-only dimensions for a set of images, keyed by URI string.
     *
     * Enumeration no longer reads dimensions (that cost one stream open per
     * photograph before the folder could be shown), so the caller drives this:
     * resolve the visible range first, then backfill the rest off the main
     * thread. Each URI is read at most once per process. Images whose header
     * cannot be parsed map to [ImageDimensions.UNKNOWN] — an absent ratio is a
     * safe state the frame solver already handles, never a failure.
     */
    suspend fun resolveDimensions(
        context: Context,
        uris: Collection<String>,
    ): Map<String, ImageDimensions>
}
