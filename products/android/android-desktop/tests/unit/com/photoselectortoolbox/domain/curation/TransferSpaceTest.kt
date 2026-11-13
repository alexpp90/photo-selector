package com.photoselectortoolbox.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

class TransferSpaceTest {

    private val card = TransferSpace.Volume("com.android.externalstorage.documents", "3A1F-1C0B:", 10L)
    private val tablet = TransferSpace.Volume("com.android.externalstorage.documents", "primary:", 10L)

    @Test
    fun `a copy larger than the free space is refused, one that fits is not`() {
        assertTrue(TransferSpace.refuses(11, card, tablet, isMove = false))
        assertFalse(TransferSpace.refuses(10, card, tablet, isMove = false))
    }

    @Test
    fun `a move on one volume needs no space, a move to another volume does`() {
        assertFalse(TransferSpace.refuses(11, tablet, tablet, isMove = true))
        assertTrue(TransferSpace.refuses(11, card, tablet, isMove = true))
    }

    @Test
    fun `a destination that reports no free space is not refused`() {
        assertFalse(TransferSpace.refuses(Long.MAX_VALUE, card, tablet.copy(availableBytes = null), isMove = false))
        assertFalse(TransferSpace.refuses(Long.MAX_VALUE, card, null, isMove = false))
    }

    @Test
    fun `a document belongs to the root whose id is its longest prefix`() {
        val roots = listOf("primary:", "3A1F-1C0B:", "root")
        assertEquals("primary:", TransferSpace.rootFor("primary:DCIM/Shoot", roots))
        assertEquals("primary:", TransferSpace.rootFor("primary:", roots))
        assertEquals("root", TransferSpace.rootFor("root/Selection", roots))
        assertNull(TransferSpace.rootFor("rooted/x", roots))
        assertNull(TransferSpace.rootFor("msf:12", roots))
    }

    @Test
    fun `the refusal says what it needs and what is free, and is recognised as one`() {
        val message = TransferSpace.refusalMessage(48L * 1024 * 1024, 12L * 1024 * 1024, isMove = false)
        assertEquals("Not enough space: needs 48 MB, 12 MB free. Nothing was copied.", message.en())
        val code = TransferSpace.refusalCode(48L * 1024 * 1024, 12L * 1024 * 1024, isMove = false)
        assertEquals(message, TransferSpace.refusalOf(code))
        assertEquals(null, TransferSpace.refusalOf("Copy failed"))
        assertEquals(
            "Not enough space: needs 2.5 GB, 0 MB free. Nothing was moved.",
            TransferSpace.refusalMessage(2560L * 1024 * 1024, 0, isMove = true).en(),
        )
    }
}
