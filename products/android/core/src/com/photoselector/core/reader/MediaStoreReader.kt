package com.photoselector.core.reader

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.photoselector.core.model.ExifData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback EXIF reader using MediaStore columns.
 * Used when ExifInterface cannot parse the file format.
 */
@Singleton
class MediaStoreReader @Inject constructor() : ExifReaderStrategy {

    companion object {
        private const val TAG = "MediaStoreReader"

        // MediaStore can provide basic metadata for most image types
        private val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heif",
            "image/heic",
            "image/bmp",
            "image/gif"
        )

        // Only the row's existence matters; the deprecated DATA path column is not read.
        private val PROJECTION = arrayOf(MediaStore.Images.Media.SIZE)
    }

    override suspend fun readExif(context: Context, uri: Uri): ExifData? =
        withContext(Dispatchers.IO) {
            try {
                queryMediaStore(context, uri)
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore query failed for $uri", e)
                null
            }
        }

    override fun supportedMimeTypes(): Set<String> = SUPPORTED_MIME_TYPES

    private fun queryMediaStore(context: Context, uri: Uri): ExifData? {
        // Try to query using the provided URI directly
        val cursor = context.contentResolver.query(
            uri,
            PROJECTION,
            null,
            null,
            null
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null

            // MediaStore provides limited metadata; return a fallback ExifData
            // indicating the data source is not full EXIF
            return ExifData(
                shutterSpeed = null,
                aperture = null,
                focalLength = null,
                focalLength35mm = null,
                iso = null,
                lens = "Unknown",
                isFallback = true
            )
        }
    }
}
