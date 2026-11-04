package com.phototok.data.share

import com.phototok.data.model.ImageItem
import com.phototok.domain.FileTypeFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentBuilderTest {

    private fun img(name: String, mime: String? = null) = ImageItem(
        uri = "content://photos/$name",
        fileName = name,
        fileSize = 100L,
        lastModified = 100L,
        mimeType = mime,
    )

    @Test
    fun `resolveShareTarget prefers JPEG companion over RAW when moveRelatedFiles is enabled`() {
        val raw = img("DSC_0001.ARW", "image/x-sony-arw")
        val jpg = img("DSC_0001.JPG", "image/jpeg")
        val all = listOf(raw, jpg)

        val target = ShareIntentBuilder.resolveShareTarget(
            item = raw,
            allImages = all,
            moveRelatedFiles = true,
            fileTypeFilter = FileTypeFilter.ALL,
        )

        assertEquals(jpg.uri, target.uri)
        assertEquals("image/jpeg", target.mimeType)
    }

    @Test
    fun `resolveShareTarget keeps lone RAW when no JPEG companion exists`() {
        val raw = img("DSC_0002.CR3", null)
        val target = ShareIntentBuilder.resolveShareTarget(
            item = raw,
            allImages = listOf(raw),
            moveRelatedFiles = true,
            fileTypeFilter = FileTypeFilter.ONE_PER_SHOT,
        )

        assertEquals(raw.uri, target.uri)
        assertEquals("image/x-raw", target.mimeType)
    }

    @Test
    fun `resolveShareBatch deduplicates RAW+JPEG pairs and caps at maxItems`() {
        val raw1 = img("DSC_0001.ARW")
        val jpg1 = img("DSC_0001.JPG")
        val extras = (2..6).map { img("DSC_000$it.JPG") }
        val all = listOf(raw1, jpg1) + extras

        val batch = ShareIntentBuilder.resolveShareBatch(
            items = all,
            allImages = all,
            moveRelatedFiles = true,
            fileTypeFilter = FileTypeFilter.ONE_PER_SHOT,
            maxItems = 3,
        )

        // raw1 and jpg1 collapse to 1 distinct URI, plus 5 extras = 6 distinct total -> 3 kept, 3 truncated
        assertEquals(3, batch.targets.size)
        assertEquals(3, batch.truncatedCount)
        assertEquals(jpg1.uri, batch.targets.first().uri)
    }
}
