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
