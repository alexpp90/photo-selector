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

    private val nr = listOf(
        "IMG.ARW", "IMG.JPG", "IMG.xmp", "IMG.ARW.xmp", "IMG-Enhanced-NR.tif", "IMG-Enhanced-NR.jpg",
    )

    @Test
    fun `every sidecar and derivative of a RAW bundle goes to RAW, whatever its extension`() {
        for (n in nr - "IMG.JPG") assertEquals(n, RAW, ImageFormats.selectionSubfolderInBundle(n, nr))
        assertEquals(JPEG, ImageFormats.selectionSubfolderInBundle("IMG.JPG", nr))
        assertEquals(RAW, ImageFormats.selectionSubfolderInBundle("IMG_Enhanced-NR.png", nr))
        assertEquals(RAW, ImageFormats.selectionSubfolderInBundle("IMG-Edit.xmp", nr + "IMG-Edit.xmp"))
    }

    @Test
    fun `without a RAW sidecars and derivatives follow the primary image`() {
        val jpegOnly = listOf("IMG.JPG", "IMG.xmp", "IMG-Edit.jpg", "IMG-Enhanced-NR.tif")
        for (n in jpegOnly - "IMG.JPG") assertEquals(n, JPEG, ImageFormats.selectionSubfolderInBundle(n, jpegOnly))
    }

    @Test
    fun `a derivative alone is filed in RAW`() =
        assertEquals(RAW, ImageFormats.selectionSubfolderInBundle("a-Enhanced-NR.tif", listOf("a-Enhanced-NR.tif")))

    @Test
    fun `by name alone Enhanced-NR derivatives go to RAW`() {
        assertEquals(RAW, ImageFormats.selectionSubfolder("a-Enhanced-NR.tif"))
        assertEquals(RAW, ImageFormats.selectionSubfolder("a_Enhanced-NR.jpg"))
    }
}
