package com.phototok.domain

import com.phototok.data.model.ImageItem

/**
 * Companion grouping for PhotoTok, per the shared contract `testdata/formats/companions.json`
 * (`docs/shared/FILE_OPERATIONS.md`): one shot is its RAW and JPEG, its `.xmp` sidecars
 * (`IMG.xmp` and `IMG.CR3.xmp`) and its derivatives (`-Edit`, `_Edit`, `-Enhanced-NR`,
 * `_Enhanced-NR`, with their own sidecars). Pure and Android-free.
 *
 * Grouping is only ever done inside **one directory**: camera counters repeat across folders, so
 * callers must compare the parent folder themselves (see [RelatedFiles.bundleOf]).
 */
object CompanionFiles {

    /** Name suffixes of a shot's derivatives, matched case-insensitively. */
    private val DERIVATIVE_SUFFIXES = listOf("-Edit", "_Edit", "-Enhanced-NR", "_Enhanced-NR")

    fun isSidecar(fileName: String): Boolean = PhotoExtensions.extensionOf(fileName) == PhotoExtensions.XMP

    /** True when [fileName] (photo or sidecar) is a derivative of a shot rather than the shot. */
    fun isDerivative(fileName: String): Boolean = derivativeSuffixOf(baseStem(fileName)) != null

    /**
     * Lower-case stem shared by every file of a shot: `IMG_1.CR3`, `IMG_1.xmp`, `IMG_1.CR3.xmp`,
     * `IMG_1-Edit.tif` and `IMG_1_Edit.xmp` all give `img_1`. The whole stem must match, so
     * `IMG_10` and `IMG_1` stay apart.
     */
    fun shotStemOf(fileName: String): String {
        val base = baseStem(fileName)
        val suffix = derivativeSuffixOf(base)
        return (if (suffix != null) base.dropLast(suffix.length) else base).lowercase()
    }

    private fun derivativeSuffixOf(base: String): String? =
        DERIVATIVE_SUFFIXES.firstOrNull { base.length > it.length && base.endsWith(it, ignoreCase = true) }

    private fun baseStem(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        if (!name.contains('.')) return name
        if (!isSidecar(name)) return name.substringBeforeLast('.')
        val withoutXmp = name.substringBeforeLast('.')
        return if (withoutXmp.contains('.') && PhotoExtensions.isSupported(withoutXmp)) {
            withoutXmp.substringBeforeLast('.')
        } else {
            withoutXmp
        }
    }

    /**
     * Groups the file names of ONE directory into bundles (photos first, then sidecars, each
     * group in the order given). Sidecars with no photo of their shot are orphans and dropped.
     * Mirrors what a linked move or delete takes for each shot.
     */
    fun bundlesIn(fileNames: List<String>): List<List<String>> =
        fileNames
            .filter { PhotoExtensions.isSupported(it) || isSidecar(it) }
            .groupBy { shotStemOf(it) }
            .values
            .filter { group -> group.any { PhotoExtensions.isSupported(it) } }
            .map { group ->
                val photos = group.filter { PhotoExtensions.isSupported(it) }
                val shot = photos.firstOrNull { !isDerivative(it) && PhotoExtensions.isRaw(it) }
                    ?: photos.firstOrNull { !isDerivative(it) }
                    ?: photos.first()
                listOf(shot) + group.filter { it != shot }
            }

    /**
     * Attaches every sidecar of a directory listing to the photos of its shot
     * ([ImageItem.sidecars]). [sidecars] come from the one listing discovery already makes of
     * that directory; nothing is looked up per file. Orphan sidecars attach to nothing, and
     * sidecars never become feed items.
     */
    fun attachSidecars(images: List<ImageItem>, sidecars: List<ImageItem>): List<ImageItem> {
        if (sidecars.isEmpty() || images.isEmpty()) return images
        val byShot = sidecars.groupBy { shotStemOf(it.fileName) }
        return images.map { image ->
            val own = byShot[shotStemOf(image.fileName)]
            if (own == null) image else image.copy(sidecars = own)
        }
    }
}
