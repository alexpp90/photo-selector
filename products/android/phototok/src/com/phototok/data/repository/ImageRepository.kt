package com.phototok.data.repository

import android.net.Uri
import com.photoselector.core.model.ExifData
import com.phototok.data.model.ImageItem
import com.phototok.data.source.FileOperationResult
import com.phototok.data.source.SelectionListing
import com.phototok.domain.PhotoFolders
import kotlinx.coroutines.flow.Flow

interface ImageRepository {
    fun discoverImages(
        folderUri: Uri,
        excludedFolderNames: Set<String> = emptySet(),
        shuffleFirstBatch: Boolean = false,
    ): Flow<List<ImageItem>>

    /**
     * Prepare a folder for use as the swipe source (persist permission, verify
     * access). Returns the display name, or null when the folder is inaccessible.
     */
    suspend fun prepareSourceFolder(folderUri: Uri): String?

    /** Resolve a folder's display name, or null when unavailable. */
    suspend fun resolveFolderName(folderUri: Uri): String?

    suspend fun getExifData(uri: Uri): ExifData?

    suspend fun deleteImage(uri: Uri): Boolean

    suspend fun moveImage(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String = PhotoFolders.SELECTION,
    ): Boolean

    suspend fun copyImage(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String = PhotoFolders.SELECTION,
    ): Boolean

    /** Like [moveImage], but reports a verified copy whose original could not be removed as partial. */
    suspend fun moveImageDetailed(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String = PhotoFolders.SELECTION,
    ): FileOperationResult

    /** Like [copyImage], with the destination or the error in the result. */
    suspend fun copyImageDetailed(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String = PhotoFolders.SELECTION,
    ): FileOperationResult

    /**
     * Copy or move a shot's files together: one shared collision suffix across the bundle so
     * sidecars and derivatives stay paired with their photo. One result per source, in order.
     */
    suspend fun transferBundleDetailed(
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String = PhotoFolders.SELECTION,
        isMove: Boolean,
    ): List<FileOperationResult>

    suspend fun getImageDimensions(uri: Uri): Pair<Int, Int>

    /** List the images collected in the selection subfolder of [folderUri]. */
    suspend fun listSelectionImages(
        folderUri: Uri,
        subfolderName: String = PhotoFolders.SELECTION,
    ): SelectionListing
}
