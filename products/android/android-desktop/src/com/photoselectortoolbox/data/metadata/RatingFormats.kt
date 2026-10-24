package com.photoselectortoolbox.data.metadata

import com.photoselectortoolbox.domain.format.ImageFormats
import java.io.IOException

/** Where a rating of a file of a given format is stored (REQ-AND-RATING.01). */
enum class RatingStrategy {
    /** JPEG: the rating goes into the file's embedded XMP packet, via a verified temporary copy. */
    EMBEDDED_XMP,

    /** Camera RAW (incl. DNG) and TIFF: the rating goes into a companion `.xmp`; the image is never opened for writing. */
    SIDECAR,

    /** Every other format: the rating stays in the app only, and the user is told so. */
    UNSUPPORTED,
}

/** The one table that decides, for the File and the SAF write path alike, how a rating is stored. */
object RatingFormats {
    fun strategyFor(fileName: String): RatingStrategy = when {
        ImageFormats.isJpeg(fileName) -> RatingStrategy.EMBEDDED_XMP
        ImageFormats.isRaw(fileName) || ImageFormats.isTiff(fileName) -> RatingStrategy.SIDECAR
        else -> RatingStrategy.UNSUPPORTED
    }

    /** Why a rating cannot be stored in the file of [fileName]'s format. */
    fun unsupportedMessage(fileName: String): String =
        "Ratings are not written to ${ImageFormats.extensionOf(fileName).uppercase().ifEmpty { "this kind of" }} files"
}

/**
 * A rating write that was refused or failed with a reason the user can be told. Nothing was
 * changed in the photograph or in an existing sidecar when this is raised.
 */
class RatingWriteException(message: String, cause: Throwable? = null) : IOException(message, cause)
