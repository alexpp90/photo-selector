package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.domain.format.ImageFormats.SelectionSubfolder.JPEG
import com.photoselectortoolbox.domain.format.ImageFormats.SelectionSubfolder.RAW
import com.photoselectortoolbox.domain.format.ImageFormats.SelectionSubfolder.ROOT
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFormatsBundleSortingTest {
    private val full = listOf("DSC_0003.JPG", "DSC_0003.ARW", "DSC_0003.xmp", "DSC_0003-Edit.JPG")

    @Test
    fun `stem xmp follows the RAW of its bundle`() =
        assertEquals(RAW, ImageFormats.selectionSubfolderInBundle("DSC_0003.xmp", full))

    @Test
    fun `stem xmp follows the primary image when the bundle has no RAW`() =
        assertEquals(JPEG, ImageFormats.selectionSubfolderInBundle("a.xmp", listOf("a.jpg", "a.xmp", "a-Edit.jpg")))

    @Test
    fun `stem xmp alone stays at the root`() =
        assertEquals(ROOT, ImageFormats.selectionSubfolderInBundle("a.xmp", listOf("a.xmp")))

    @Test
    fun `other members keep the per-file rule`() {
        assertEquals(JPEG, ImageFormats.selectionSubfolderInBundle("DSC_0003.JPG", full))
        assertEquals(RAW, ImageFormats.selectionSubfolderInBundle("DSC_0003-Edit.JPG", full))
        assertEquals(RAW, ImageFormats.selectionSubfolderInBundle("DSC_0003.ARW.xmp", full))
    }
}
