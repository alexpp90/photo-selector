package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.data.model.FolderReachability
import com.photoselectortoolbox.domain.curation.DestinationMode
import com.photoselectortoolbox.domain.curation.DestinationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The words of Settings → "Where photos go" (`[USER 2026-11-04]`): the path line is the
 * resolved destination, ending `› RAW · JPEG` only while sorting is on.
 */
class DestinationLabelsTest {

    private fun preview(
        segments: List<String>?,
        sorting: Boolean = true,
        mode: DestinationMode = DestinationMode.WORKING_FOLDER,
        folder: String = "Selection",
    ) = DestinationPreview(
        mode = mode,
        segments = segments,
        folderName = folder,
        sortsIntoRawAndJpeg = sorting,
        isSecondPass = false,
    )

    @Test
    fun `the path is the segments joined with the arrow and ends in RAW and JPEG when sorting is on`() {
        assertEquals(
            "Internal storage › DCIM › Trip › Selection › RAW · JPEG",
            DestinationLabels.pathLine(preview(listOf("Internal storage", "DCIM", "Trip", "Selection"))),
        )
    }

    @Test
    fun `with sorting off the path ends at the Selection folder`() {
        assertEquals(
            "Internal storage › DCIM › Trip › Selection",
            DestinationLabels.pathLine(preview(listOf("Internal storage", "DCIM", "Trip", "Selection"), sorting = false)),
        )
    }

    @Test
    fun `no folder open says what will happen, with the configured name`() {
        assertEquals(
            "«Selection» inside the folder you open — no folder open yet",
            DestinationLabels.pathLine(preview(null)),
        )
        assertEquals(
            "«Picks» inside the folder you open — no folder open yet",
            DestinationLabels.pathLine(preview(null, folder = "Picks")),
        )
    }

    @Test
    fun `a custom folder with no readable path shows no path rather than a guess`() {
        assertNull(DestinationLabels.pathLine(preview(null, mode = DestinationMode.CUSTOM)))
    }

    @Test
    fun `the spoken path has commas and names the RAW and JPEG subfolders`() {
        assertEquals(
            "Photos go to SD card, Photos, Keepers, Selection, in RAW and JPEG subfolders",
            DestinationLabels.pathDescription(preview(listOf("SD card", "Photos", "Keepers", "Selection"))),
        )
    }

    @Test
    fun `each way a custom folder is lost has its own reason`() {
        assertNull(DestinationLabels.unavailableReason(FolderReachability.REACHABLE))
        val reasons = listOf(FolderReachability.NO_PERMISSION, FolderReachability.MISSING, FolderReachability.READ_ONLY)
            .map { DestinationLabels.unavailableReason(it)!! }
        assertEquals(3, reasons.toSet().size)
        assertFalse(reasons.any { it.isBlank() })
    }

    @Test
    fun `the custom destination is named folder then Selection`() {
        assertEquals("Keepers › Selection", DestinationLabels.customDestinationName("Keepers", "Selection"))
    }
}
