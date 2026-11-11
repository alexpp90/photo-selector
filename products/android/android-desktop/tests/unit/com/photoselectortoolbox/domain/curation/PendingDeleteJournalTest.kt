package com.photoselectortoolbox.domain.curation

import com.photoselectortoolbox.domain.curation.PendingDeleteJournal.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The identity half of the pending-delete journal (REQ-AND-LAYOUT.23, finding 2026-11-05). */
class PendingDeleteJournalTest {

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FIMG_0001.JPG"

    @Test
    fun `an entry survives encoding with its URI intact`() {
        val identity = FileIdentity(size = 4_812_334, lastModified = 1_700_000_000_123)
        assertEquals(uri to identity, PendingDeleteJournal.decode(PendingDeleteJournal.encode(uri, identity)))
    }

    @Test
    fun `an entry this version cannot read is no identity at all`() {
        assertNull(PendingDeleteJournal.decode(uri))
        assertNull(PendingDeleteJournal.decode("12\tx\t$uri"))
        assertNull(PendingDeleteJournal.decode("12\t3\t"))
        assertEquals(emptyMap<String, FileIdentity>(), PendingDeleteJournal.identities(setOf(uri, "garbage")))
    }

    @Test
    fun `only the same size and date delete`() {
        val recorded = FileIdentity(100, 5)
        assertEquals(Verdict.DELETE, PendingDeleteJournal.verdict(recorded, FileIdentity(100, 5)))
        assertEquals(Verdict.CHANGED, PendingDeleteJournal.verdict(recorded, FileIdentity(101, 5)))
        assertEquals(Verdict.CHANGED, PendingDeleteJournal.verdict(recorded, FileIdentity(100, 6)))
        assertEquals(Verdict.MISSING, PendingDeleteJournal.verdict(recorded, null))
    }

    @Test
    fun `a field the provider did not report is not compared, and with neither nothing is deleted`() {
        assertEquals(Verdict.DELETE, PendingDeleteJournal.verdict(FileIdentity(100, 0), FileIdentity(100, 9)))
        assertEquals(Verdict.CHANGED, PendingDeleteJournal.verdict(FileIdentity(0, 5), FileIdentity(100, 9)))
        assertEquals(Verdict.UNVERIFIABLE, PendingDeleteJournal.verdict(FileIdentity(0, 0), FileIdentity(0, 0)))
        assertEquals(Verdict.UNVERIFIABLE, PendingDeleteJournal.verdict(null, FileIdentity(100, 5)))
    }

    @Test
    fun `classify sorts every recovered entry into exactly one group`() {
        val recovery = PendingDeleteJournal.classify(
            uris = listOf("a", "b", "c", "d"),
            recorded = mapOf("a" to FileIdentity(1, 1), "b" to FileIdentity(1, 1), "d" to FileIdentity(1, 1)),
            current = mapOf("a" to FileIdentity(1, 1), "b" to FileIdentity(2, 1), "d" to null),
        )
        assertEquals(listOf("a"), recovery.toDelete)
        assertEquals(listOf("b"), recovery.changed)
        assertEquals(listOf("c"), recovery.unverifiable)
        assertEquals(listOf("d"), recovery.missing)
        assertEquals(listOf("b", "c"), recovery.kept)
    }
}
