package com.phototok.domain

import java.nio.file.Files
import org.junit.Assert.assertEquals
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
}
