package com.phototok.domain

import com.photoselector.core.format.RawExtensions
import org.junit.Assert.assertEquals
import org.junit.Test

/** Jackpix's RAW extensions are the table `:core` shares with Frameduel, not a copy (plan D-6). */
class PhotoExtensionsSharedTableTest {
    @Test
    fun `the RAW set is the shared one`() {
        assertEquals(RawExtensions.ALL, PhotoExtensions.RAW)
        assertEquals(true, PhotoExtensions.isRaw("IMG_1.CR3"))
    }
}
