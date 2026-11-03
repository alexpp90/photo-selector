package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeImageRepository @Inject constructor() : ImageRepository {

    val imagesFlow = MutableStateFlow<List<ImageItem>>(emptyList())

    /**
     * Whether discovery **completes** after publishing the folder.
     *
     * Real enumeration ends: a first batch, then chunks, then a final snapshot
     * and the flow closes, which is what clears `isEnumerating` and drops the
     * `+` from `127 / 842+`. The default therefore models a settled folder.
     * Setting this false leaves the folder permanently mid-enumeration, which is
     * how a test can assert the still-growing readout.
     */
    var completeAfterFirstBatch: Boolean = true

    /**
     * The name [openFolder] reports, or null to model a folder that has gone.
     *
     * This is the seam that lets a test open a folder at all. Before it existed
     * the ViewModel resolved the folder itself through `DocumentFile`, which no
     * fake can stand in for, so both suites opened Google Drive folders purely
     * to take a branch that skipped SAF — testing the production path by
     * avoiding it. An ordinary `content://` URI works here.
     */
    var folderName: String? = "Test Folder"

    val openedFolders = mutableListOf<String>()

    override suspend fun openFolder(context: Context, folderUri: Uri): String? {
        openedFolders += folderUri.toString()
        return folderName
    }

    override fun discoverImages(folderUri: Uri): Flow<List<ImageItem>> =
        if (completeAfterFirstBatch) flowOf(imagesFlow.value) else imagesFlow

    override suspend fun discoverAllImages(folderUri: Uri): List<ImageItem> = imagesFlow.value

    override suspend fun getExifData(context: Context, uri: Uri): ExifData? {
        return imagesFlow.value.find { it.uri == uri.toString() }?.exifData
    }

    override suspend fun deleteImage(context: Context, uri: Uri): Boolean {
        val current = imagesFlow.value
        imagesFlow.value = current.filter { it.uri != uri.toString() }
        return true
    }

    var canTrashResult: Boolean = false

    override fun canTrash(uri: Uri): Boolean = canTrashResult

    val restoredFromTrash = mutableListOf<String>()

    override suspend fun restoreFromTrash(context: Context, uri: Uri): Boolean {
        if (!canTrashResult) return false
        restoredFromTrash += uri.toString()
        return true
    }

    override suspend fun moveImage(
        context: Context,
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean
    ): FileOperationResult {
        deleteImage(context, sourceUri)
        return FileOperationResult.success(sourceUri.toString(), destinationOf(sourceUri))
    }

    override suspend fun copyImage(
        context: Context,
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean
    ): FileOperationResult =
        FileOperationResult.success(sourceUri.toString(), destinationOf(sourceUri))

    override suspend fun undoMove(
        context: Context,
        movedTo: Uri,
        restoreFolderUri: Uri,
    ): FileOperationResult =
        FileOperationResult.success(movedTo.toString(), movedTo.toString())

    override suspend fun moveBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult {
        val files = item.allFiles.map { Uri.parse(it.uri) }
        files.forEach { deleteImage(context, it) }
        val destinations = files.map(::destinationOf)
        return FileOperationResult.success(item.uri, destinations.first(), destinations, item.parentDocumentId)
    }

    override suspend fun copyBundle(
        context: Context,
        item: ImageItem,
        destFolderUri: Uri,
        sorting: Boolean,
    ): FileOperationResult {
        val destinations = item.allFiles.map { destinationOf(Uri.parse(it.uri)) }
        return FileOperationResult.success(item.uri, destinations.first(), destinations, item.parentDocumentId)
    }

    /** Puts every file back at the URI it had before the move (the stand-in Selection is a URI rewrite). */
    override suspend fun undoMoveBundle(
        context: Context,
        destinationUris: List<String>,
        rootFolderUri: Uri,
        parentDocumentId: String?,
        copiesOfSurvivingOriginals: Map<String, String>,
    ): FileOperationResult {
        if (destinationUris.isEmpty()) return FileOperationResult.failure("", "No files to restore", parentDocumentId)
        val restored = destinationUris.filterNot { it in copiesOfSurvivingOriginals }.map { it.replace("/Selection/", "/") }
        return FileOperationResult.success(destinationUris.first(), restored.firstOrNull(), restored, parentDocumentId)
            .copy(deletedCopies = destinationUris.filter { it in copiesOfSurvivingOriginals })
    }

    override suspend fun deleteBundle(context: Context, item: ImageItem): Boolean =
        item.allFiles.map { deleteImage(context, Uri.parse(it.uri)) }.all { it }

    /**
     * A stand-in Selection destination, so undo paths have a URI to work with. The file keeps its
     * name, as a real transfer without a collision does (a different name would be reported to the
     * user as a collision-suffixed copy).
     */
    private fun destinationOf(sourceUri: Uri): String {
        val uri = sourceUri.toString()
        return uri.substringBeforeLast('/') + "/Selection/" + uri.substringAfterLast('/')
    }

    override suspend fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        val img = imagesFlow.value.find { it.uri == uri.toString() }
        return if (img != null) Pair(img.imageWidth, img.imageHeight) else Pair(0, 0)
    }

    override suspend fun resolveDimensions(
        context: Context,
        uris: Collection<String>,
    ): Map<String, ImageDimensions> = uris.associateWith { uri ->
        val img = imagesFlow.value.find { it.uri == uri }
        if (img != null) ImageDimensions.of(img.imageWidth, img.imageHeight) else ImageDimensions.UNKNOWN
    }
}
