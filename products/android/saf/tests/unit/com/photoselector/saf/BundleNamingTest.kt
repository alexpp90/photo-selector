package com.photoselector.saf

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Collision-free bundle naming (`-2`, `-3`, … across the whole bundle; never an overwrite).
 * "AD"/"TOK" name the former product tests each case replaces.
 */
class BundleNamingTest {

    private fun folderOf(name: String) = TestSubfolderRule.subfolderFor(name, emptyList()).name

    // AD + TOK: collision in Selection JPEG suffixes entire CR3 + JPG + CR3 xmp + -Edit tif bundle with -2
    @Test
    fun `a collision in Selection JPEG suffixes the entire CR3, JPG, CR3 xmp and -Edit tif bundle with -2`() {
        val resolved = BundleNaming.resolveCollisionFreeBundleNames(
            fileNames = listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.CR3.xmp", "IMG_0001-Edit.tif"),
            primaryStem = "IMG_0001",
            existingNamesByFolder = mapOf(
                SelectionSubfolder.RAW.name to emptySet(),
                SelectionSubfolder.JPEG.name to setOf("img_0001.jpg"), // case-insensitive collision
            ),
            folderForFile = ::folderOf,
        )

        assertEquals("IMG_0001-2.CR3", resolved["IMG_0001.CR3"])
        assertEquals("IMG_0001-2.JPG", resolved["IMG_0001.JPG"])
        assertEquals("IMG_0001-2.CR3.xmp", resolved["IMG_0001.CR3.xmp"])
        assertEquals("IMG_0001-2-Edit.tif", resolved["IMG_0001-Edit.tif"])
    }

    // AD: collision resolution advances to -3 when -2 is already taken in any target subfolder
    // TOK: collision resolution skips -2 when -2 already exists in any target subfolder
    @Test
    fun `collision resolution advances to -3 when -2 is taken in any target subfolder`() {
        val resolved = BundleNaming.resolveCollisionFreeBundleNames(
            fileNames = listOf("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.xmp", "IMG_0001_Edit.tif"),
            primaryStem = "IMG_0001",
            existingNamesByFolder = mapOf(
                SelectionSubfolder.RAW.name to setOf("IMG_0001-2.CR3"),
                SelectionSubfolder.JPEG.name to setOf("IMG_0001.JPG"),
                SelectionSubfolder.ROOT.name to emptySet(),
            ),
            folderForFile = ::folderOf,
        )

        assertEquals("IMG_0001-3.CR3", resolved["IMG_0001.CR3"])
        assertEquals("IMG_0001-3.JPG", resolved["IMG_0001.JPG"])
        assertEquals("IMG_0001-3.xmp", resolved["IMG_0001.xmp"])
        assertEquals("IMG_0001-3_Edit.tif", resolved["IMG_0001_Edit.tif"])
    }

    @Test
    fun `no collision keeps every name`() {
        val names = listOf("IMG_0002.CR3", "IMG_0002.JPG")
        val resolved = BundleNaming.resolveCollisionFreeBundleNames(
            fileNames = names,
            existingNamesByFolder = mapOf(SelectionSubfolder.RAW.name to setOf("IMG_0003.CR3")),
            folderForFile = ::folderOf,
        )

        assertEquals(names.associateWith { it }, resolved)
        assertEquals(emptyMap<String, String>(), BundleNaming.resolveCollisionFreeBundleNames(emptyList(), existingNamesByFolder = emptyMap(), folderForFile = ::folderOf))
    }

    @Test
    fun `the primary stem drops the extension, an xmp sidecar suffix and the derivative suffixes`() {
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("IMG_0001.CR3"))
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("IMG_0001.CR3.xmp"))
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("IMG_0001.xmp"))
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("IMG_0001-Edit.tif"))
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("IMG_0001_Edit.jpg"))
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("IMG_0001-Enhanced-NR.dng"))
        assertEquals("IMG_0001", BundleNaming.extractPrimaryStem("sub/IMG_0001.JPG"))
        assertEquals("README", BundleNaming.extractPrimaryStem("README"))
    }

    @Test
    fun `the suffix goes after the stem and before the extension or derivative suffix`() {
        assertEquals("IMG_0001-2.CR3", BundleNaming.suffixFileName("IMG_0001.CR3", 2))
        assertEquals("IMG_0001-2.CR3.xmp", BundleNaming.suffixFileName("IMG_0001.CR3.xmp", 2))
        assertEquals("IMG_0001-2-Edit.tif", BundleNaming.suffixFileName("IMG_0001-Edit.tif", 2))
        assertEquals("IMG_0001-2_Edit.tif", BundleNaming.suffixFileName("IMG_0001_Edit.tif", 2))
        assertEquals("IMG_0001-3-Enhanced-NR.dng", BundleNaming.suffixFileName("IMG_0001-Enhanced-NR.dng", 3))
        // A member whose name does not start with the bundle's stem uses its own stem.
        assertEquals("OTHER-2.JPG", BundleNaming.suffixFileName("OTHER.JPG", 2, primaryStem = "IMG_0001"))
    }
}
