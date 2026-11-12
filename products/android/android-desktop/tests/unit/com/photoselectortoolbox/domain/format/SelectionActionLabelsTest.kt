package com.photoselectortoolbox.domain.format

import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.interaction.FilingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The button says what it does — and names the folder the file actually goes to.
 *
 * An earlier draft of the selector called the copy control "Keep" — a word that
 * describes how the photographer feels about the frame rather than what happens
 * to the file, and which is actively false under a "move" configuration, where
 * the file leaves the source folder. PhotoTok shipped the same mistake with a
 * "KEEP" swipe indicator (see the 2026-07-31 entry in `ai/memory/palette.md`),
 * so this is the second time; hence a test rather than a code review comment.
 *
 * The destination is the milder form of the same defect: the wording used to say
 * "Selection" while the folder name is the `selection_folder_name` setting, so a
 * photographer who renamed it to "Picks" was told about a folder that does not
 * exist. Both rules are asserted here.
 */
class SelectionActionLabelsTest {

    /** A name nothing in the source can have hard-coded. */
    private val renamed = "Picks"

    @Test
    fun `the word is never a euphemism`() {
        val banned = listOf("keep", "save", "like", "favourite", "favorite", "star")

        FilingAction.entries.forEach { configured ->
            SelectionActionLabels.both(configured, renamed).forEach { label ->
                banned.forEach { word ->
                    assertFalse(
                        "filing control is labelled '${label.verb}'",
                        label.verb.lowercase().contains(word),
                    )
                    assertFalse(
                        "filing control is described as '${label.phrase}'",
                        label.phrase.lowercase().contains(word),
                    )
                }
            }
        }
    }

    @Test
    fun `the verb is copy or move and nothing else`() {
        FilingAction.entries.forEach { configured ->
            val verbs = SelectionActionLabels.both(configured).map { it.verb }.toSet()
            assertEquals(setOf("Copy", "Move"), verbs)
        }
    }

    @Test
    fun `the configured action is the primary one and comes first`() {
        FilingAction.entries.forEach { configured ->
            val both = SelectionActionLabels.both(configured)

            assertEquals(configured, both.first().action)
            assertTrue(both.first().isPrimary)
            assertFalse(both.last().isPrimary)
        }
    }

    @Test
    fun `both verbs are always offered whichever is configured`() {
        // The setting decides emphasis, not availability. A photographer who
        // normally moves must still be able to copy this one frame without
        // opening Settings.
        FilingAction.entries.forEach { configured ->
            val actions = SelectionActionLabels.both(configured).map { it.action }.toSet()
            assertEquals(FilingAction.entries.toSet(), actions)
        }
    }

    @Test
    fun `each control carries its own shortcut`() {
        val labels = SelectionActionLabels.both(FilingAction.COPY)

        assertEquals("C", labels.single { it.action == FilingAction.COPY }.shortcut)
        assertEquals("M", labels.single { it.action == FilingAction.MOVE }.shortcut)
    }

    @Test
    fun `accessibility label states the action and its shortcut`() {
        val label = SelectionActionLabels.primary(FilingAction.MOVE)

        assertEquals("Move to Selection, shortcut M", label.accessibilityLabel)
    }

    @Test
    fun `confirmation wording matches the verb that ran`() {
        assertEquals("Copied to Selection", SelectionActionLabels.confirmation(FilingAction.COPY))
        assertEquals("Moved to Selection", SelectionActionLabels.confirmation(FilingAction.MOVE))
    }

    @Test
    fun `the snackbar names the folder the photographer configured`() {
        // The snackbar is the only thing on screen that says where the
        // photograph went. Naming a folder that does not exist is the same class
        // of defect as the euphemism above: the words do not describe what
        // happened.
        assertEquals(
            "Copied to $renamed",
            SelectionActionLabels.confirmation(FilingAction.COPY, renamed),
        )
        assertEquals(
            "Moved to $renamed",
            SelectionActionLabels.confirmation(FilingAction.MOVE, renamed),
        )
    }

    @Test
    fun `a blank folder name falls back to the default rather than trailing off`() {
        assertEquals("Moved to Selection", SelectionActionLabels.confirmation(FilingAction.MOVE, ""))
        assertEquals(
            "Moved to Selection",
            SelectionActionLabels.confirmation(FilingAction.MOVE, "   "),
        )
    }

    @Test
    fun `the accessibility phrase names the folder but the button keeps the verb`() {
        // The control block is 190dp wide with two filing buttons in it, so a
        // long folder name must not reach the button. It reaches the screen
        // reader and the context menu, which have room for it.
        val label = SelectionActionLabels.primary(FilingAction.MOVE, "2026 client selects")

        assertEquals("Move", label.verb)
        assertEquals("Move to 2026 client selects", label.phrase)
        assertEquals("Move to 2026 client selects, shortcut M", label.accessibilityLabel)
    }

    @Test
    fun `both controls name the same folder`() {
        SelectionActionLabels.both(FilingAction.MOVE, renamed).forEach { label ->
            assertTrue(
                "'${label.phrase}' does not name the configured folder",
                label.phrase.endsWith(renamed),
            )
        }
    }

    @Test
    fun `the wording default and the persisted default are the same folder`() {
        // Two constants describing one folder. If they drift, the first snackbar
        // after a fresh install names a folder the app did not create.
        assertEquals(
            SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME,
            FilingAction.DEFAULT_SELECTION_FOLDER,
        )
    }

    @Test
    fun `move and copy both advance`() {
        // One key per decision (backlog 2026-11-11); the copied frame stays beside as Previous.
        assertTrue(SelectionActionLabels.advancesAfter(FilingAction.MOVE))
        assertTrue(SelectionActionLabels.advancesAfter(FilingAction.COPY))
    }

    @Test
    fun `a collision-suffixed move names the file it became and counts the companions`() {
        assertEquals(
            "Moved to Selection as IMG_0001-2.JPG (+2 related)",
            SelectionActionLabels.suffixedConfirmation(
                action = FilingAction.MOVE,
                selectionFolderName = "Selection",
                resultName = "IMG_0001-2.JPG",
                relatedCount = 2,
            ),
        )
    }

    @Test
    fun `a collision-suffixed copy without companions has no related count`() {
        assertEquals(
            "Copied to Picks as IMG_0001-3.JPG",
            SelectionActionLabels.suffixedConfirmation(FilingAction.COPY, "Picks", "IMG_0001-3.JPG", 0),
        )
    }

    @Test
    fun `an undo that had to suffix says so`() {
        assertEquals(
            "Restored as IMG_0001-2.JPG (original name was in use)",
            SelectionActionLabels.restoredAs("IMG_0001-2.JPG"),
        )
    }

    @Test
    fun `display name is the last path segment of a document id`() {
        assertEquals("IMG_0001-2.JPG", SelectionActionLabels.displayNameOf("content://x/tree/root/document/root%2FSelection%2FJPEG%2FIMG_0001-2.JPG"))
        assertEquals("IMG_1.JPG", SelectionActionLabels.displayNameOf("content://x/tree/primary%3ADCIM/document/primary%3ADCIM%2FIMG_1.JPG"))
    }

    @Test
    fun `a custom destination names its folder in the confirmation, the control keeps the bare verb`() {
        assertEquals(
            "Moved to «Keepers › Selection»",
            SelectionActionLabels.confirmation(FilingAction.MOVE, "Selection", customFolderName = "Keepers"),
        )
        assertEquals(
            "Copied to «Keepers › Picks»",
            SelectionActionLabels.confirmation(FilingAction.COPY, "Picks", customFolderName = "Keepers"),
        )
        // The button never carries the place (REQ-AND-LAYOUT.08): the bare verb.
        FilingAction.entries.forEach { configured ->
            SelectionActionLabels.both(configured, "Selection").forEach { label ->
                assertFalse(label.verb, label.verb.contains("Keepers") || label.verb.contains("›"))
            }
        }
    }

    @Test
    fun `without a custom folder the confirmation is the sentence it always was`() {
        assertEquals("Moved to Selection", SelectionActionLabels.confirmation(FilingAction.MOVE, "Selection"))
        assertEquals("Moved to Selection", SelectionActionLabels.confirmation(FilingAction.MOVE, "Selection", null))
        assertEquals("Moved to Selection", SelectionActionLabels.confirmation(FilingAction.MOVE, "Selection", " "))
    }

    @Test
    fun `a renamed file in a custom folder keeps the folder in the longer sentence`() {
        assertEquals(
            "Moved to «Keepers › Selection» as IMG_0001-2.JPG (+2 related)",
            SelectionActionLabels.suffixedConfirmation(
                FilingAction.MOVE, "Selection", "IMG_0001-2.JPG", 2, customFolderName = "Keepers",
            ),
        )
    }
}
