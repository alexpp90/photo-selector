package com.photoselector.core.format

/**
 * The one table of camera RAW file extensions both Android apps use (Jackpix
 * `PhotoExtensions.RAW`, Frameduel `ImageFormats.RAW_EXTENSIONS` are this set).
 *
 * It holds every RAW extension of the cross-product contract `testdata/formats/manifest.json`
 * (`RawExtensionsTest` fails if one is missing) plus the further vendor RAWs both apps have always
 * discovered and sorted as RAW. Lower case, without the dot.
 */
object RawExtensions {

    val ALL: Set<String> = setOf(
        // the manifest's required tier
        "arw", "cr2", "cr3", "nef", "nrw", "raf", "rw2", "orf", "pef", "dng", "srw",
        // the manifest's recognized tier
        "3fr", "fff", "iiq", "rwl", "raw", "sr2", "srf", "mrw", "x3f", "erf", "mef", "mos",
        // further vendor RAWs
        "ari", "bay", "cap", "eip", "mdc", "obm", "ptx", "pxn", "rwz",
    )

    /** True when [fileName]'s last extension, case-insensitively, is a RAW extension. */
    fun isRaw(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        return dot >= 0 && fileName.substring(dot + 1).lowercase() in ALL
    }
}
