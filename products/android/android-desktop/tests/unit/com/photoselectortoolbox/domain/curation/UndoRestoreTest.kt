package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UndoRestoreTest {

    private fun item(uri: String, name: String, vararg companions: Pair<String, String>) = ImageItem(
        uri = uri, fileName = name, fileSize = 1, lastModified = 1, mimeType = "image/jpeg",
        companions = companions.map { (u, n) -> CompanionFile(u, n) },
    )

    @Test
    fun `path-like URIs give the restored files their new names`() {
        val image = item("content://p/document/primary%3ADCIM%2FIMG_1.JPG", "IMG_1.JPG",
            "content://p/document/primary%3ADCIM%2FIMG_1.CR3" to "IMG_1.CR3")
        val relocated = UndoRestore.relocate(
            image,
            movedUris = listOf("m1", "m2"),
            restoredUris = listOf("content://p/document/primary%3ADCIM%2FIMG_1-2.JPG", "content://p/document/primary%3ADCIM%2FIMG_1-2.CR3"),
        )
        assertEquals("IMG_1-2.JPG", relocated.fileName)
        assertEquals(listOf("IMG_1-2.CR3"), relocated.companions.map { it.fileName })
    }

    @Test
    fun `a result that does not line up with the bundle moves only the primary`() {
        val image = item("a", "a.jpg", "a-raw" to "a.CR3")
        val relocated = UndoRestore.relocate(image, movedUris = listOf("m1", "m2"), restoredUris = listOf("r1"))
        assertEquals("r1", relocated.uri)
        assertEquals(listOf("a-raw"), relocated.companions.map { it.uri })
    }

    @Test
    fun `no restored URIs leaves the item as it is`() {
        val image = item("a", "a.jpg")
        assertSame(image, UndoRestore.relocate(image, listOf("m"), emptyList()))
    }

    @Test
    fun `replacing the frame on screen keeps the photographer on it`() {
        val a = item("a", "a.jpg"); val b = item("b", "b.jpg")
        val state = SelectorListState(listOf(a, b), currentIndex = 1)
        val next = UndoRestore.replace(state, "b", b.copy(uri = "b2"))
        assertEquals(listOf("a", "b2"), next.images.map { it.uri })
        assertEquals(1, next.currentIndex)
    }
}
