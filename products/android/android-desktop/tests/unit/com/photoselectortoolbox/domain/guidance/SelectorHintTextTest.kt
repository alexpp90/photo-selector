package com.photoselectortoolbox.domain.guidance

import com.photoselectortoolbox.domain.format.SelectionActionLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.photoselectortoolbox.testing.en

/**
 * The explanations say what happened to the file, under whichever configuration
 * the photographer is running.
 *
 * The precedent is `SelectionActionLabelsTest`: PhotoTok shipped a mid-swipe
 * indicator reading **KEEP** for an action that copies *or moves* depending on a
 * setting, so under one configuration it was simply false. A first-run
 * explanation is the same hazard with a longer life — it is read once, believed,
 * and never checked again.
 */
class SelectorHintTextTest {

    private val everyConfiguration = FilingAction.entries

    @Test
    fun `no explanation is ever a euphemism`() {
        val banned = listOf("keep", "save", "like", "favourite", "favorite", "star")

        everyConfiguration.forEach { action ->
            SelectorHint.entries.forEach { hint ->
                val text = (
                    SelectorHintText.title(hint, action).en() + " " +
                        SelectorHintText.message(hint, action, "Selection", true).en()
                    ).lowercase()

                banned.forEach { word ->
                    assertFalse(
                        "$hint under $action reads '$text'",
                        text.contains(word),
                    )
                }
            }
        }
    }

    @Test
    fun `the filing explanation names the configured verb, not the other one`() {
        assertEquals("Moved to Selection", SelectorHintText.title(SelectorHint.FILING, FilingAction.MOVE).en())
        assertEquals("Copied to Selection", SelectorHintText.title(SelectorHint.FILING, FilingAction.COPY).en())
    }

    @Test
    fun `the filing heading and the snackbar are the same sentence`() {
        // They appear on screen together. The heading used to hard-code
        // "Selection" while the body took the folder name, so the two halves of
        // one card could disagree about where the photograph went.
        everyConfiguration.forEach { action ->
            assertEquals(
                SelectionActionLabels.confirmation(action, "Picks"),
                SelectorHintText.title(SelectorHint.FILING, action, "Picks"),
            )
        }

        assertEquals("Moved to Picks", SelectorHintText.title(SelectorHint.FILING, FilingAction.MOVE, "Picks").en())
    }

    @Test
    fun `the filing explanation names the configured destination folder`() {
        // The folder name is a setting. A hint that says "Selection" while the
        // file is in "Picks" is describing somebody else's configuration.
        val message = SelectorHintText.message(
            hint = SelectorHint.FILING,
            filingAction = FilingAction.MOVE,
            selectionFolderName = "Picks",
            sortingEnabled = true,
        ).en()

        assertTrue(message, message.contains("\"Picks\""))
        assertFalse(message, message.contains("Selection"))
    }

    @Test
    fun `a blank folder name falls back rather than printing empty quotes`() {
        val message = SelectorHintText.message(
            hint = SelectorHint.FILING,
            filingAction = FilingAction.COPY,
            selectionFolderName = "",
            sortingEnabled = false,
        ).en()

        assertTrue(message, message.contains("\"Selection\""))
    }

    @Test
    fun `sorting is mentioned only when it is switched on`() {
        val sorted = SelectorHintText.message(
            SelectorHint.FILING, FilingAction.MOVE, "Selection", sortingEnabled = true,
        ).en()
        val unsorted = SelectorHintText.message(
            SelectorHint.FILING, FilingAction.MOVE, "Selection", sortingEnabled = false,
        ).en()

        assertTrue(sorted, sorted.contains("RAW and JPEG"))
        assertFalse(unsorted, unsorted.contains("RAW and JPEG"))
    }

    @Test
    fun `the filing explanation points at the other verb and its key`() {
        // Both verbs are always available; the setting only decides emphasis.
        // The hint is where a photographer finds that out.
        val underMove = SelectorHintText.message(
            SelectorHint.FILING, FilingAction.MOVE, "Selection", true,
        ).en()
        assertTrue(underMove, underMove.contains(FilingAction.COPY.shortcut))
        assertTrue(underMove, underMove.contains("copies"))

        val underCopy = SelectorHintText.message(
            SelectorHint.FILING, FilingAction.COPY, "Selection", true,
        ).en()
        assertTrue(underCopy, underCopy.contains(FilingAction.MOVE.shortcut))
        assertTrue(underCopy, underCopy.contains("moves"))
    }

    @Test
    fun `the delete explanation promises no deadline and says what the delete dialog says`() {
        // Nothing commits a delete on a timer: it goes when the folder is left (FD-D-1).
        val text = SelectorHintText.title(SelectorHint.DELETE_UNDO, FilingAction.COPY).en() +
            SelectorHintText.message(SelectorHint.DELETE_UNDO, FilingAction.COPY, "Selection", true).en()
        val dialog = com.photoselectortoolbox.domain.format.SelectorLabels.deleteConfirmationMessage("a.jpg").en()

        for (said in listOf(text, dialog)) {
            assertTrue(said, said.contains("until you leave the folder"))
            assertFalse(said, Regex("\\b30\\b|second").containsMatchIn(said))
        }
    }

    @Test
    fun `the maximise explanation names the key that actually leaves the state`() {
        val message = SelectorHintText.message(
            SelectorHint.MAXIMISE, FilingAction.COPY, "Selection", true,
        ).en()

        assertTrue(message, message.startsWith(SelectorShortcut.ESCAPE.input.en()))
    }

    @Test
    fun `the guide names the key that reopens it`() {
        val message = SelectorHintText.message(
            SelectorHint.TOUR, FilingAction.COPY, "Selection", true,
        ).en()

        assertTrue(message, message.contains(SelectorShortcut.SHORTCUTS.input.en()))
    }

    @Test
    fun `every hint has words under every configuration`() {
        everyConfiguration.forEach { action ->
            SelectorHint.entries.forEach { hint ->
                assertTrue(
                    "$hint has no title under $action",
                    SelectorHintText.title(hint, action).en().isNotBlank(),
                )
                assertTrue(
                    "$hint has no message under $action",
                    SelectorHintText.message(hint, action, "Selection", true).en().isNotBlank(),
                )
            }
        }
    }
}
