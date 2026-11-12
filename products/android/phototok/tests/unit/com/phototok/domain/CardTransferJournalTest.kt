package com.phototok.domain

import com.photoselector.core.storage.SourceKind
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTransferJournalTest {

    @Test
    fun `the copied count survives a new instance and counts each file once`() {
        val dir = Files.createTempDirectory("card_journal_test").toFile()
        try {
            val file = java.io.File(dir, "card_transfer_journal.tsv")
            val journal = CardTransferJournal(file)
            journal.recordCopy("content://card/1", "content://card/folder")
            journal.recordCopy("content://card/2", "content://card/folder")
            journal.recordCopy("content://card/2", "content://card/folder")
            journal.recordCopy("content://other/1", "content://other/folder")
            assertEquals(2, journal.copiedCountForFolder("content://card/folder"))

            val reloaded = CardTransferJournal(file)
            assertEquals(2, reloaded.copiedCountForFolder("content://card/folder"))
            assertEquals(1, reloaded.copiedCountForFolder("content://other/folder"))
            assertEquals(0, reloaded.copiedCountForFolder("content://nothing"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clearing a card folder forgets only that folder, also after a reload`() {
        val dir = Files.createTempDirectory("card_journal_test").toFile()
        try {
            val file = java.io.File(dir, "card_transfer_journal.tsv")
            val journal = CardTransferJournal(file)
            journal.recordCopy("content://card/1", "content://card/folder")
            journal.recordCopy("content://other/1", "content://other/folder")

            journal.clearSource("content://card/folder")

            val reloaded = CardTransferJournal(file)
            assertEquals(0, reloaded.copiedCountForFolder("content://card/folder"))
            assertEquals(1, reloaded.copiedCountForFolder("content://other/folder"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a journal without a file counts in memory and a damaged file is ignored`() {
        val memory = CardTransferJournal()
        memory.recordCopy("a", "f")
        assertEquals(1, memory.copiedCountForFolder("f"))

        val dir = Files.createTempDirectory("card_journal_test").toFile()
        try {
            val file = java.io.File(dir, "j.tsv")
            file.writeText("garbage\nC\tonly-two\nC\tf\turi-1\n")
            assertEquals(1, CardTransferJournal(file).copiedCountForFolder("f"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private val card = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM"
    private val cardSelectionDoc =
        "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ADCIM/document/1A2B-3C4D%3ADCIM%2FSelection%2Fa.jpg"
    private val phoneDoc =
        "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FCamera%2FJackpix%2Fa.jpg"

    @Test
    fun `a copy into a folder on the card is not copied off it, a copy to the phone is, and the count survives`() {
        val dir = Files.createTempDirectory("card_journal_test").toFile()
        try {
            val file = java.io.File(dir, "card_transfer_journal.tsv")
            val journal = CardTransferJournal(file)
            journal.recordTransfer(SourceKind.REMOVABLE, "content://card/1", card, cardSelectionDoc)
            assertEquals("<card>/Selection stays on the card", 0, journal.copiedCountForFolder(card))
            journal.recordTransfer(SourceKind.REMOVABLE, "content://card/2", card, phoneDoc)
            journal.recordTransfer(SourceKind.CAMERA_MTP, "content://card/3", card, phoneDoc)
            assertEquals(2, journal.copiedCountForFolder(card))

            assertEquals("a new journal on the same file", 2, CardTransferJournal(file).copiedCountForFolder(card))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `only a card or camera source is counted`() {
        val journal = CardTransferJournal()
        journal.recordTransfer(SourceKind.PHONE_LIBRARY, "content://phone/1", card, phoneDoc)
        journal.recordTransfer(SourceKind.REMOVABLE, "content://card/1", null, phoneDoc)
        journal.recordTransfer(SourceKind.REMOVABLE, "content://card/2", card, null)
        assertEquals(0, journal.copiedCountForFolder(card))
    }

    @Test
    fun `volumes are told apart by provider and volume id`() {
        assertFalse(CardVolumes.isOffCard(card, card))
        assertFalse(CardVolumes.isOffCard(card, cardSelectionDoc))
        assertTrue(CardVolumes.isOffCard(card, phoneDoc))
        val mtp = "content://com.android.mtp.documents/tree/4/document/17"
        assertFalse("one camera is one volume", CardVolumes.isOffCard(mtp, "content://com.android.mtp.documents/tree/4/document/99"))
        assertTrue(CardVolumes.isOffCard(mtp, phoneDoc))
        assertFalse("an unparsable destination is not counted", CardVolumes.isOffCard(card, "not a uri"))
    }
}
