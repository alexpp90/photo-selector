package com.photoselectortoolbox.data.source

import android.net.Uri
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class FakeLocalImageSource @Inject constructor() : LocalImageSource {
    override fun discoverImages(folderUri: Uri): Flow<List<ImageItem>> {
        return flowOf(emptyList())
    }

    override suspend fun resolveDimensions(
        uris: Collection<String>,
    ): Map<String, ImageDimensions> = uris.associateWith { ImageDimensions.UNKNOWN }
}
