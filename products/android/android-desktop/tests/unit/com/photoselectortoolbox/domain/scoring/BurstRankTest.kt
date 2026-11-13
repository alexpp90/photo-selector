package com.photoselectortoolbox.domain.scoring

import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sharpness as a place within the burst (backlog 2026-11-11, REQ-AND-ALGO.01). */
class BurstRankTest {

    private fun frame(name: String, sharpness: Double?, group: Int? = 1) = ImageItem(
        uri = "content://test/$name",
        fileName = name,
        fileSize = 1L,
        lastModified = 1L,
        mimeType = "image/jpeg",
        scanResult = ScanResult(filePath = "content://test/$name", sharpnessScore = sharpness),
        groupId = group,
    )

    private val burst = listOf(
        frame("a", 120.0), frame("b", 300.0), frame("c", 217.3), frame("d", 217.3), frame("e", 15.0),
        frame("other", 900.0, group = 2), frame("other2", 800.0, group = 2),
    )

    @Test
    fun `each frame of a burst is ranked among its own burst only`() {
        assertEquals(BurstRank(1, 5), BurstRank.sharpnessOf(burst[1], burst))
        assertEquals(BurstRank(4, 5), BurstRank.sharpnessOf(burst[0], burst))
        assertEquals(BurstRank(5, 5), BurstRank.sharpnessOf(burst[4], burst))
        assertEquals(BurstRank(2, 2), BurstRank.sharpnessOf(burst[6], burst))
    }

    @Test
    fun `equal scores share the better place`() {
        assertEquals(BurstRank(2, 5), BurstRank.sharpnessOf(burst[2], burst))
        assertEquals(BurstRank(2, 5), BurstRank.sharpnessOf(burst[3], burst))
    }

    @Test
    fun `an ungrouped frame, an unmeasured one and a burst of one measured frame have no rank`() {
        assertNull(BurstRank.sharpnessOf(frame("solo", 50.0, group = null), burst))
        val unmeasured = frame("u", null)
        assertNull(BurstRank.sharpnessOf(unmeasured, burst + unmeasured))
        val pair = listOf(frame("p", 10.0, group = 9), frame("q", null, group = 9))
        assertNull(BurstRank.sharpnessOf(pair[0], pair))
    }

    @Test
    fun `an unmeasured frame is not counted in the burst size`() {
        val withGap = burst + frame("f", null)
        assertEquals(BurstRank(1, 5), BurstRank.sharpnessOf(burst[1], withGap))
    }
}
