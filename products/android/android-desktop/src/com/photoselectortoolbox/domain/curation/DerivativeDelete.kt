package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.ImageFormats

/**
 * Delete on a frame led by a derivative takes the derivative, never the original.
 *
 * A shot with no JPEG leads with its `-Edit` (`-Enhanced-NR`, …) and carries the RAW as a
 * companion, so Delete on that frame used to remove the RAW too: the photographer rejected an edit
 * and lost the negative. Now only the displayed derivative and its own sidecars
 * (`IMG_1-Edit.tif.xmp`, `IMG_1-Edit.xmp`) are deleted, and the shot stays in the list, led by its
 * original. A frame led by an original still deletes the whole shot, derivatives included. A shot
 * that has only derivatives has no original to protect, so it goes whole.
 */
object DerivativeDelete {

    /**
     * What stays of [image] when Delete removes its displayed derivative, or null when Delete
     * takes the whole shot.
     */
    fun remainderOf(image: ImageItem): ImageItem? {
        if (!ImageFormats.isEditDerivative(image.fileName)) return null

        val staying = image.companions.filterNot { ImageFormats.sameFileBase(it.fileName, image.fileName) }
        val originals = staying.filter {
            ImageFormats.isSupported(it.fileName) && !ImageFormats.isEditDerivative(it.fileName)
        }
        if (originals.isEmpty()) return null

        val leader: CompanionFile = originals.minWith(
            compareBy<CompanionFile> { ImageFormats.primaryRank(it.fileName) }.thenBy { it.fileName.lowercase() },
        )
        return image.copy(
            uri = leader.uri,
            fileName = leader.fileName,
            fileSize = leader.fileSize,
            lastModified = leader.lastModified,
            mimeType = leader.mimeType,
            // The derivative's pixels, EXIF and scores are not the leader's: resolved afresh.
            imageWidth = 0,
            imageHeight = 0,
            exifData = null,
            scanResult = null,
            rating = 0,
            companions = staying.filter { it.uri != leader.uri },
        )
    }
}
