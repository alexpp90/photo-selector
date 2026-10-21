package com.phototok.data.repository

import android.net.Uri
import android.util.Log
import com.photoselector.core.model.ExifData
import com.phototok.data.model.ImageItem
import com.phototok.data.source.FileOperationResult
import com.phototok.data.source.ImageSourceResolver
import com.phototok.data.source.SelectionListing
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Thin router over the [com.phototok.data.source.ImageSource] backends.
 * All backend-specific behavior lives in the source implementations;
 * this class only resolves which backend owns a URI and delegates.
 */
@Singleton
class ImageRepositoryImpl @Inject constructor(
    private val resolver: ImageSourceResolver,
) : ImageRepository {

    companion object {
        private const val TAG = "ImageRepositoryImpl"
    }

    override fun discoverImages(
        folderUri: Uri,
        excludedFolderNames: Set<String>,
        shuffleFirstBatch: Boolean,
    ): Flow<List<ImageItem>> =
        resolver.sourceFor(folderUri).discoverImages(
            folderUri = folderUri,
            excludedFolderNames = excludedFolderNames,
            shuffleFirstBatch = shuffleFirstBatch,
        )

    override suspend fun prepareSourceFolder(folderUri: Uri): String? =
        resolver.sourceFor(folderUri).prepareSourceFolder(folderUri)

    override suspend fun resolveFolderName(folderUri: Uri): String? =
        resolver.sourceFor(folderUri).resolveFolderName(folderUri)

    override suspend fun getExifData(uri: Uri): ExifData? =
        resolver.sourceFor(uri).getExifData(uri)

    override suspend fun deleteImage(uri: Uri): Boolean =
        resolver.sourceFor(uri).deleteImage(uri)

    override suspend fun moveImage(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): Boolean {
        if (!resolver.sameSource(sourceUri, destFolderUri)) {
            Log.w(TAG, "Cross-source move is not supported: $sourceUri -> $destFolderUri")
            return false
        }
        return resolver.sourceFor(sourceUri)
            .moveImage(sourceUri, destFolderUri, sorting, subfolderName)
    }

    override suspend fun copyImage(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): Boolean {
        if (!resolver.sameSource(sourceUri, destFolderUri)) {
            Log.w(TAG, "Cross-source copy is not supported: $sourceUri -> $destFolderUri")
            return false
        }
        return resolver.sourceFor(sourceUri)
            .copyImage(sourceUri, destFolderUri, sorting, subfolderName)
    }

    override suspend fun moveImageDetailed(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult {
        if (!resolver.sameSource(sourceUri, destFolderUri)) {
            Log.w(TAG, "Cross-source move is not supported: $sourceUri -> $destFolderUri")
            return FileOperationResult.failure(sourceUri.toString(), "Cross-source move is not supported") // ui-text: ignore diagnostic code, never shown
        }
        return resolver.sourceFor(sourceUri)
            .moveImageDetailed(sourceUri, destFolderUri, sorting, subfolderName)
    }

    override suspend fun copyImageDetailed(
        sourceUri: Uri,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
    ): FileOperationResult {
        if (!resolver.sameSource(sourceUri, destFolderUri)) {
            Log.w(TAG, "Cross-source copy is not supported: $sourceUri -> $destFolderUri")
            return FileOperationResult.failure(sourceUri.toString(), "Cross-source copy is not supported") // ui-text: ignore diagnostic code, never shown
        }
        return resolver.sourceFor(sourceUri)
            .copyImageDetailed(sourceUri, destFolderUri, sorting, subfolderName)
    }

    override suspend fun transferBundleDetailed(
        sourceUris: List<Uri>,
        destFolderUri: Uri,
        sorting: Boolean,
        subfolderName: String,
        isMove: Boolean,
    ): List<FileOperationResult> {
        if (sourceUris.isEmpty()) return emptyList()
        if (sourceUris.any { !resolver.sameSource(it, destFolderUri) }) {
            Log.w(TAG, "Cross-source bundle transfer is not supported: $destFolderUri")
            return sourceUris.map {
                FileOperationResult.failure(it.toString(), "Cross-source transfer is not supported") // ui-text: ignore diagnostic code, never shown
            }
        }
        return resolver.sourceFor(sourceUris.first())
            .transferBundleDetailed(sourceUris, destFolderUri, sorting, subfolderName, isMove)
    }

    override suspend fun getImageDimensions(uri: Uri): Pair<Int, Int> =
        resolver.sourceFor(uri).getImageDimensions(uri)

    override suspend fun listSelectionImages(
        folderUri: Uri,
        subfolderName: String,
    ): SelectionListing =
        resolver.sourceFor(folderUri).listSelectionImages(folderUri, subfolderName)
}
