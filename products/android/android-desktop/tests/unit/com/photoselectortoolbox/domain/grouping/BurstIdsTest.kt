package com.photoselectortoolbox.domain.grouping

import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The selector's index-list bursts on the photos (`ImageItem.groupId`): two or more frames make one. */
class BurstIdsTest {

    private val photos = (0..4).map { ImageItem("content://f/$it.jpg", "$it.jpg", 1L, it.toLong(), "image/jpeg") }

    @Test
    fun `members of a burst share its number, singles and others get none`() {
        val tagged = BurstIds.tag(photos, listOf(listOf(0, 1, 2), listOf(3), listOf(4)))
        assertEquals(listOf(0, 0, 0, null, null), tagged.map { it.groupId })
    }

    @Test
    fun `a stale id is cleared and an unchanged photo is the same object`() {
        val stale = photos.map { it.copy(groupId = 7) }
        assertEquals(List(5) { null }, BurstIds.tag(stale, emptyList()).map { it.groupId })
        assertSame(photos[4], BurstIds.tag(photos, listOf(listOf(0, 1)))[4])
    }
}
