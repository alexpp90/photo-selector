package com.photoselectortoolbox.domain.format

import com.photoselector.core.format.RawExtensions
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frameduel's RAW extensions are the table `:core` shares with Jackpix, not a copy (plan D-6). */
class ImageFormatsSharedTableTest {
    @Test
    fun `the RAW set is the shared one`() {
        assertEquals(RawExtensions.ALL, ImageFormats.RAW_EXTENSIONS)
        assertEquals(true, ImageFormats.isRaw("IMG_1.CR3"))
    }
}
