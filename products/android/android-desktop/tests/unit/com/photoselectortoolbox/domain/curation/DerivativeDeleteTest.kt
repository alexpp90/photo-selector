package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Delete on a frame led by a derivative takes the derivative, never the original. */
class DerivativeDeleteTest {

    private fun file(name: String) = CompanionFile("content://tree/$name", name)

    private fun shot(primary: String, vararg companions: String) = ImageItem(
        uri = "content://tree/$primary",
        fileName = primary,
        fileSize = 1,
        lastModified = 0,
        mimeType = null,
        companions = companions.map(::file),
    )

    @Test
    fun `an edit with its RAW keeps the RAW and its sidecar`() {
        val kept = DerivativeDelete.remainderOf(
            shot("IMG_1-Edit.tif", "IMG_1.CR3", "IMG_1.CR3.xmp", "IMG_1-Edit.tif.xmp", "IMG_1-Edit.xmp"),
        )!!

        assertEquals("IMG_1.CR3", kept.fileName)
        assertEquals(listOf("IMG_1.CR3.xmp"), kept.companions.map { it.fileName })
    }

    @Test
    fun `another derivative stays when only the displayed one goes`() {
        val kept = DerivativeDelete.remainderOf(
            shot("IMG_1-Edit.tif", "IMG_1.CR3", "IMG_1-Enhanced-NR.dng"),
        )!!

        assertEquals("IMG_1.CR3", kept.fileName)
        assertEquals(listOf("IMG_1-Enhanced-NR.dng"), kept.companions.map { it.fileName })
    }

    @Test
    fun `a shot of derivatives only has no original and goes whole`() {
        assertNull(DerivativeDelete.remainderOf(shot("IMG_1-Edit.tif", "IMG_1-Enhanced-NR.dng", "IMG_1-Edit.xmp")))
    }

    @Test
    fun `a frame led by an original goes whole, with its derivatives`() {
        assertNull(DerivativeDelete.remainderOf(shot("IMG_1.jpg", "IMG_1-Edit.tif", "IMG_1.CR3")))
    }

    @Test
    fun `a partly failed commit rebuilds the shot from what stayed plus what is still on disk`() {
        val full = shot("IMG_1-Edit.tif", "IMG_1.CR3", "IMG_1-Edit.tif.xmp")
        val (list, pending) = DeferredDeletion.begin(
            SelectorListState(listOf(full), 0),
            listOf(full.uri),
            0L,
        )!!
        assertEquals(listOf("IMG_1.CR3"), list.images.map { it.fileName })
        assertEquals(listOf(full.uri, "content://tree/IMG_1-Edit.tif.xmp"), pending.uris)

        val survivors = DeferredDeletion.survivors(pending, setOf(full.uri))!!
        val back = DeferredDeletion.revert(list, survivors)

        assertEquals(1, back.images.size)
        assertEquals("IMG_1-Edit.tif", back.images[0].fileName)
        assertEquals(listOf("IMG_1.CR3"), back.images[0].companions.map { it.fileName })
        assertNotNull(back.images[0])
    }
}
