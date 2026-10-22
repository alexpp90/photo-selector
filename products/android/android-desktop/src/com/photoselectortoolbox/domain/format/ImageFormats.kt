package com.photoselectortoolbox.domain.format

/**
 * The one list of file formats Android Desktop knows — discovery, RAW/JPEG
 * sorting into the Selection, rating write-back and the decode path all ask
 * here. It implements the cross-product contract in
 * `testdata/formats/manifest.json` (docs/shared/SUPPORTED_FORMATS.md);
 * `FormatConformanceTest` fails if the two drift apart.
 *
 * Extensions are matched case-insensitively and without the dot.
 */
object ImageFormats {

    /**
     * Camera RAW containers. The contract's required and recognized RAW tiers,
     * plus further vendor formats that are discovered and sorted as RAW.
     */
    val RAW_EXTENSIONS: Set<String> = setOf(
        // required
        "arw", "cr2", "cr3", "nef", "nrw", "raf", "rw2", "orf", "pef", "dng", "srw",
        // recognized
        "3fr", "fff", "iiq", "rwl", "raw", "sr2", "srf", "mrw", "x3f", "erf", "mef", "mos",
        // further vendor RAWs
        "ari", "bay", "cap", "eip", "mdc", "obm", "ptx", "pxn", "rwz",
    )

    val JPEG_EXTENSIONS: Set<String> = setOf("jpg", "jpeg")

    val TIFF_EXTENSIONS: Set<String> = setOf("tif", "tiff")

    /** Rendered (non-RAW) images. */
    val BITMAP_EXTENSIONS: Set<String> =
        JPEG_EXTENSIONS + TIFF_EXTENSIONS + setOf("png", "webp", "heic", "heif", "bmp", "gif")

    /** Everything folder discovery picks up. */
    val SUPPORTED_EXTENSIONS: Set<String> = RAW_EXTENSIONS + BITMAP_EXTENSIONS

    const val XMP_EXTENSION = "xmp"

    /** Lightroom's suffix for an edited derivative (`DSC001-Edit.tif`). */
    const val EDIT_SUFFIX = "-Edit"

    /** Lower-case extension of [fileName] without the dot, or "" if it has none. */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun isSupported(fileName: String): Boolean = extensionOf(fileName) in SUPPORTED_EXTENSIONS
    fun isRaw(fileName: String): Boolean = extensionOf(fileName) in RAW_EXTENSIONS
    fun isJpeg(fileName: String): Boolean = extensionOf(fileName) in JPEG_EXTENSIONS
    fun isTiff(fileName: String): Boolean = extensionOf(fileName) in TIFF_EXTENSIONS
    fun isXmp(fileName: String): Boolean = extensionOf(fileName) == XMP_EXTENSION

    /** True for any supported image format or a companion `.xmp` sidecar. */
    fun isCompanionOrSupported(fileName: String): Boolean =
        isSupported(fileName) || isXmp(fileName)

    /**
     * Name suffixes of a shot's derivatives: Lightroom edits (`-Edit`, `_Edit`) and Adobe Denoise
     * results (`-Enhanced-NR`, `_Enhanced-NR`). They travel with their shot on Move, Trash and Undo
     * (shared table `testdata/formats/companions.json`).
     */
    private val DERIVATIVE_SUFFIXES = listOf(EDIT_SUFFIX, "_Edit", "-Enhanced-NR", "_Enhanced-NR")

    /** The derivative suffix [baseStem] ends with (it must have a shot name in front of it), or null. */
    private fun derivativeSuffixOf(baseStem: String): String? =
        DERIVATIVE_SUFFIXES.firstOrNull { baseStem.length > it.length && baseStem.endsWith(it, ignoreCase = true) }

    /** True when [fileName] is a `-Edit` / `_Edit` / `-Enhanced-NR` / `_Enhanced-NR` derivative. */
    fun isEditDerivative(fileName: String): Boolean =
        derivativeSuffixOf(baseStemWithoutExtensions(fileName)) != null

    /**
     * Canonical lower-case shot stem used to group a shot's files within a single directory:
     * - `IMG_0001.CR3`, `IMG_0001.JPG`, `IMG_0001.xmp` → `"img_0001"`
     * - `IMG_0001.CR3.xmp` → `"img_0001"`
     * - `IMG_0001-Edit.tif`, `IMG_0001_Edit.jpg`, `IMG_0001-Edit.xmp` → `"img_0001"`
     */
    fun shotStemOf(fileName: String): String {
        val baseStem = baseStemWithoutExtensions(fileName)
        val suffix = derivativeSuffixOf(baseStem)
        val stripped = if (suffix != null) baseStem.dropLast(suffix.length) else baseStem
        return stripped.lowercase()
    }

    private fun baseStemWithoutExtensions(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        if (!name.contains('.')) return name
        return if (isXmp(name)) {
            val withoutXmp = name.substringBeforeLast('.')
            if (withoutXmp.contains('.') && isSupported(withoutXmp)) {
                withoutXmp.substringBeforeLast('.')
            } else {
                withoutXmp
            }
        } else {
            name.substringBeforeLast('.')
        }
    }

    /** Where a file goes inside the Selection folder when File Sorting is on. */
    enum class SelectionSubfolder { RAW, JPEG, ROOT }

    /**
     * Sorting rule for the Selection folder, by file name alone:
     * - derivatives (`-Edit`, `_Edit`, `-Enhanced-NR`, `_Enhanced-NR`) → RAW, whatever their extension;
     * - RAW → RAW; JPEG → JPEG;
     * - XMP sidecars named after a RAW (`IMG_1.CR3.xmp`) → RAW;
     * - everything else → the Selection root.
     *
     * A file that belongs to a bundle is placed by [selectionSubfolderInBundle], which only
     * differs for files that cannot know their image from their name.
     */
    fun selectionSubfolder(fileName: String): SelectionSubfolder {
        val name = fileName.substringAfterLast('/')
        val extension = extensionOf(name)
        val stem = name.substringBeforeLast('.')
        return when {
            isEditDerivative(name) -> SelectionSubfolder.RAW
            extension in RAW_EXTENSIONS -> SelectionSubfolder.RAW
            extension in JPEG_EXTENSIONS -> SelectionSubfolder.JPEG
            extension == XMP_EXTENSION ->
                if (stem.contains('.') && isRaw(stem)) SelectionSubfolder.RAW else SelectionSubfolder.ROOT
            else -> SelectionSubfolder.ROOT
        }
    }

    /**
     * Bundle-aware sorting rule (FILE_OPERATIONS.md): every sidecar and every derivative goes into
     * the subfolder of the bundle's RAW, `RAW/` when the bundle has one, otherwise the subfolder of
     * its primary image (first member that is neither sidecar nor derivative); a file's own type
     * never splits it from its shot. RAW and plain images and `<file>.<raw>.xmp` follow
     * [selectionSubfolder]. When the bundle has no image at all a derivative stays in `RAW/` and a
     * stem sidecar stays at the Selection root.
     */
    fun selectionSubfolderInBundle(fileName: String, bundleFileNames: Collection<String>): SelectionSubfolder {
        val own = selectionSubfolder(fileName)
        val name = fileName.substringAfterLast('/')
        val isStemSidecar = isXmp(name) && own == SelectionSubfolder.ROOT
        val isDerivative = isEditDerivative(name)
        if (!isStemSidecar && !isDerivative) return own
        val others = bundleFileNames.map { it.substringAfterLast('/') }.filter { it != name }
        if (others.any { extensionOf(it) in RAW_EXTENSIONS }) return SelectionSubfolder.RAW
        val image = others.firstOrNull { !isXmp(it) && !isEditDerivative(it) && selectionSubfolder(it) != SelectionSubfolder.ROOT }
        return image?.let { selectionSubfolder(it) } ?: if (isDerivative) own else SelectionSubfolder.ROOT
    }
}
