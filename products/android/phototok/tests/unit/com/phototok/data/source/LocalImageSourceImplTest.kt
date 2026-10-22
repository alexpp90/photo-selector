package com.phototok.data.source

import com.photoselector.saf.SelectionSubfolder as SafSubfolder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PhotoTok's RAW/JPEG sorting rule ([LocalImageSourceImpl.selectionSubfolder]) and its mapping
 * onto the shared engine ([LocalImageSourceImpl.SUBFOLDER_RULE]). Where a subfolder is found or
 * created, and the fallback when it cannot be, is the engine's (`:saf` `SafEngineInternalsTest`).
 */
class LocalImageSourceImplTest {

    private fun rule(name: String) = LocalImageSourceImpl.SUBFOLDER_RULE.subfolderFor(name, listOf(name))

    @Test
    fun `raw files go to RAW subfolder`() {
        assertEquals(SafSubfolder.RAW, rule("IMG_001.ARW"))
        assertEquals(SafSubfolder.RAW, rule("img_002.dng"))
    }

    @Test
    fun `jpeg files go to JPEG subfolder`() {
        assertEquals(SafSubfolder.JPEG, rule("IMG_001.JPG"))
        assertEquals(SafSubfolder.JPEG, rule("img_002.jpeg"))
    }

    @Test
    fun `lightroom edits and Enhanced-NR derivatives go to RAW subfolder`() {
        assertEquals(SafSubfolder.RAW, rule("IMG_001-Edit.tif"))
        assertEquals(SafSubfolder.RAW, rule("IMG_001_Edit.psd"))
        assertEquals(SafSubfolder.RAW, rule("IMG_001-Enhanced-NR.tif"))
    }

    @Test
    fun `an edit saved as JPEG is sorted by its extension`() {
        assertEquals(SafSubfolder.JPEG, rule("IMG_001_Edit.jpg"))
    }

    @Test
    fun `xmp sidecar follows raw parent`() {
        assertEquals(SafSubfolder.RAW, rule("IMG_001.arw.xmp"))
    }

    @Test
    fun `xmp sidecar without raw parent stays in selection folder`() {
        assertEquals(SafSubfolder.ROOT, rule("IMG_001.xmp"))
    }

    private fun inBundle(name: String, vararg members: String) =
        LocalImageSourceImpl.SUBFOLDER_RULE.subfolderFor(name, members.toList())

    private val rawBundle = arrayOf("IMG.CR3", "IMG.JPG", "IMG.xmp", "IMG.CR3.xmp", "IMG-Enhanced-NR.tif", "IMG-Enhanced-NR.jpg", "IMG-Edit.jpg")
    private val jpegBundle = arrayOf("IMG.JPG", "IMG.xmp", "IMG-Edit.jpg", "IMG_Edit.tif")

    @Test
    fun `a stem sidecar follows the RAW of its bundle`() {
        assertEquals(SafSubfolder.RAW, inBundle("IMG.xmp", *rawBundle))
    }

    @Test
    fun `a file-named sidecar goes with the RAW`() {
        assertEquals(SafSubfolder.RAW, inBundle("IMG.CR3.xmp", *rawBundle))
    }

    @Test
    fun `every derivative goes with the RAW whatever its extension`() {
        assertEquals(SafSubfolder.RAW, inBundle("IMG-Enhanced-NR.tif", *rawBundle))
        assertEquals(SafSubfolder.RAW, inBundle("IMG-Enhanced-NR.jpg", *rawBundle))
        assertEquals(SafSubfolder.RAW, inBundle("IMG-Edit.jpg", *rawBundle))
    }

    @Test
    fun `the bundle's own JPEG stays in JPEG`() {
        assertEquals(SafSubfolder.JPEG, inBundle("IMG.JPG", *rawBundle))
        assertEquals(SafSubfolder.RAW, inBundle("IMG.CR3", *rawBundle))
    }

    @Test
    fun `a JPEG-only bundle sorts its sidecar and derivatives with the JPEG`() {
        assertEquals(SafSubfolder.JPEG, inBundle("IMG.xmp", *jpegBundle))
        assertEquals(SafSubfolder.JPEG, inBundle("IMG-Edit.jpg", *jpegBundle))
        assertEquals(SafSubfolder.JPEG, inBundle("IMG_Edit.tif", *jpegBundle))
    }

    @Test
    fun `a sidecar of a bundle without any image stays in the selection folder`() {
        assertEquals(SafSubfolder.ROOT, inBundle("IMG.xmp", "IMG.xmp"))
    }

    @Test
    fun `unknown extensions stay in selection folder`() {
        assertEquals(SafSubfolder.ROOT, rule("video.mp4"))
        assertEquals(SafSubfolder.ROOT, rule("notes.txt"))
    }

    @Test
    fun `the rule maps every PhotoTok subfolder onto the engine's`() {
        assertEquals(
            LocalImageSourceImpl.SelectionSubfolder.entries.map { it.name },
            SafSubfolder.entries.map { it.name },
        )
    }
}
