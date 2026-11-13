package com.photoselectortoolbox.data.repository

import android.content.Context
import android.net.Uri
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.domain.usecase.PhotoExifReader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The scan's EXIF read ([PhotoExifReader], shoot debrief SD-1a) through the storage seam:
 * `ImageRepository.getExifData`, the read the cull screen uses for the photos it shows (one open
 * of the document per photo, `:core` `AndroidExifReader`, MediaStore as the fallback).
 */
class RepositoryExifReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageRepository: ImageRepository,
) : PhotoExifReader {
    override suspend fun read(uri: String): ExifData? = imageRepository.getExifData(context, Uri.parse(uri))
}
