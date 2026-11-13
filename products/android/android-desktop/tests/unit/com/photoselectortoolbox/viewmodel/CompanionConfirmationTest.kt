package com.photoselectortoolbox.viewmodel

import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * File-action confirmations name the photo, its companions and the destination, on screen and
 * spoken ([USER 2026-11-12] decision #33 (1), UX notes F-33.1).
 */
@Requirement("REQ-AND-LAYOUT.21")
class CompanionConfirmationTest {

    private fun photo(name: String, vararg companions: String) = ImageItem(
        uri = "content://t/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        companions = companions.map { CompanionFile(uri = "content://t/folder/$it", fileName = it) },
    )

    private fun filed(action: FilingAction, image: ImageItem, destination: String = "Selection") =
        CompanionConfirmation.filed(action, image, destination)

    @Test
    fun `no companions names the photo and the folder`() {
        val line = filed(FilingAction.MOVE, photo("IMG_0001.JPG"))
        assertEquals("Moved IMG_0001 · Selection", line.visible.en())
        assertEquals("Moved IMG_0001.JPG to Selection", line.spoken.en())
    }

    @Test
    fun `one companion is counted and spoken in the singular`() {
        val line = filed(FilingAction.MOVE, photo("IMG_0001.JPG", "IMG_0001.CR3"))
        assertEquals("Moved IMG_0001 +1 · Selection", line.visible.en())
        assertEquals("Moved IMG_0001.JPG and 1 companion file to Selection", line.spoken.en())
    }

    @Test
    fun `two companions, and a copy says Copied`() {
        val image = photo("DSC_0003.JPG", "DSC_0003.ARW", "DSC_0003.xmp")
        assertEquals("Moved DSC_0003 +2 · Selection", filed(FilingAction.MOVE, image).visible.en())
        val copy = filed(FilingAction.COPY, image)
        assertEquals("Copied DSC_0003 +2 · Selection", copy.visible.en())
        assertEquals("Copied DSC_0003.JPG and 2 companion files to Selection", copy.spoken.en())
    }

    @Test
    fun `the word companion is spoken only`() {
        val line = filed(FilingAction.MOVE, photo("a.jpg", "a.arw", "a.xmp"))
        assertTrue(!line.visible.en().contains("companion"))
        assertTrue(line.spoken.en().contains("companion"))
    }

    @Test
    fun `a 40-character name is shortened in the middle and keeps +N and the destination`() {
        val name = "2026-11-12_wedding_ceremony_kiss_final_v3" // 41 characters before the extension
        val line = filed(FilingAction.MOVE, photo("$name.JPG", "$name.ARW", "$name.xmp"))
        assertTrue(line.visible.en(), line.visible.en().endsWith(" +2 · Selection"))
        val shown = line.visible.en().removePrefix("Moved ").removeSuffix(" +2 · Selection")
        assertEquals(CompanionConfirmation.MAX_STEM, shown.length)
        assertTrue(shown, shown.contains("…") && shown.startsWith("2026-11-12") && shown.endsWith("final_v3"))
        // The spoken line keeps the whole name.
        assertEquals("Moved $name.JPG and 2 companion files to Selection", line.spoken.en())
    }

    @Test
    fun `the destination is the configured folder, or the custom one in guillemets`() {
        assertEquals("Selection", CompanionConfirmation.destinationName("", null))
        assertEquals("Picks", CompanionConfirmation.destinationName("Picks", null))
        assertEquals("«Keepers › Selection»", CompanionConfirmation.destinationName("Selection", "Keepers"))
        val line = filed(FilingAction.MOVE, photo("a.jpg"), CompanionConfirmation.destinationName("Selection", "Keepers"))
        assertEquals("Moved a · «Keepers › Selection»", line.visible.en())
    }

    @Test
    fun `the delete dialog names the companions it takes and their kinds`() {
        val image = photo("IMG_0001.CR3", "IMG_0001.JPG", "IMG_0001.xmp")
        val title = CompanionConfirmation.deleteTitle(image)!!
        assertEquals("Delete IMG_0001 +2?", title.visible.en())
        assertEquals("Delete IMG_0001.CR3 and 2 companion files?", title.spoken.en())
        assertEquals("Also deletes JPG, XMP", CompanionConfirmation.deleteAlso(image)?.en())
    }

    @Test
    fun `a photo without companions keeps the plain delete dialog`() {
        assertNull(CompanionConfirmation.deleteTitle(photo("IMG_0001.JPG")))
        assertNull(CompanionConfirmation.deleteAlso(photo("IMG_0001.JPG")))
    }

    @Test
    fun `a frame led by an edit names only what its delete takes`() {
        // Delete on an edit removes the edit and its own sidecars; the original and its own stay.
        val edit = photo("IMG_0001-Edit.jpg", "IMG_0001.CR3", "IMG_0001.xmp")
        val taken = CompanionConfirmation.deletedCompanions(edit).map { it.fileName }
        assertTrue("the original RAW is not deleted with the edit: $taken", "IMG_0001.CR3" !in taken)
        val title = CompanionConfirmation.deleteTitle(edit)
        if (taken.isEmpty()) assertNull(title) else assertEquals("Delete IMG_0001-Edit +${taken.size}?", title!!.visible.en())
    }
}
