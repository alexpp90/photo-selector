package com.phototok.data.model

import com.photoselector.core.model.ExifData

data class ImageItem(
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val mimeType: String?,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val exifData: ExifData? = null,
    val groupId: Int? = null,
    val parentUri: String? = null,
    /**
     * `.xmp` sidecars of this shot found in the same directory listing. They are never feed
     * items; a linked move or delete takes them along (see `CompanionFiles`).
     */
    val sidecars: List<ImageItem> = emptyList(),
) {
    /** True when the image is wider than it is tall (or dimensions unknown). */
    val isLandscape: Boolean
        get() = imageWidth == 0 && imageHeight == 0 || imageWidth > imageHeight
}
