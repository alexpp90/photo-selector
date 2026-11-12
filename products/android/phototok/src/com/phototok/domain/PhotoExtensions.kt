package com.phototok.domain

import com.photoselector.core.format.RawExtensions

/**
 * Single source of truth for photographic file-extension sets.
 *
 * Discovery ([isSupported]), RAW+JPEG companion pairing ([isRaw]/[isJpeg]), the
 * file-type filter and the RAW/JPEG selection sub-folders all read these sets;
 * no other list of extensions may exist in `:phototok`. The supported-format
 * contract in `testdata/formats/manifest.json` is enforced against them by
 * `FormatConformanceTest`.
 */
object PhotoExtensions {

    /** Camera RAW formats (the table both Android apps share, `:core` [RawExtensions]). Classified as RAW for companion pairing and the RAW filter. */
    val RAW: Set<String> = RawExtensions.ALL

    val JPEG = setOf("jpg", "jpeg")

    /** TIFF has no Android platform codec; the feed decodes it through `:core`. */
    val TIFF = setOf("tif", "tiff")

    /** Non-RAW, non-JPEG bitmap formats the feed shows. Never paired as RAW. */
    val OTHER_BITMAP = setOf(
        "heic", "heif", "png", "webp", "avif", "bmp", "gif",
    )

    /** Every extension discovery puts into the feed. */
    val SUPPORTED: Set<String> = RAW + JPEG + TIFF + OTHER_BITMAP

    /**
     * Video files. Never in the feed: Jackpix shows photos only. Discovery counts them so an
     * otherwise empty folder can say "3 videos hidden" (REQ-TOK-FEATURE.04).
     */
    val VIDEO = setOf(
        "mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm", "avi", "mts", "m2ts", "mpg", "mpeg", "wmv",
    )

    const val XMP = "xmp"

    /** Lowercased extension of [fileName], or "" when there is none. */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isRaw(fileName: String): Boolean = extensionOf(fileName) in RAW

    fun isJpeg(fileName: String): Boolean = extensionOf(fileName) in JPEG

    fun isTiff(fileName: String): Boolean = extensionOf(fileName) in TIFF

    /** A video by extension or by its provider's MIME type (`video/…`). */
    fun isVideo(fileName: String, mimeType: String? = null): Boolean =
        extensionOf(fileName) in VIDEO || mimeType?.startsWith("video/") == true

    /** True when discovery should include [fileName] in the feed (case-insensitive). */
    fun isSupported(fileName: String): Boolean = extensionOf(fileName) in SUPPORTED
}
