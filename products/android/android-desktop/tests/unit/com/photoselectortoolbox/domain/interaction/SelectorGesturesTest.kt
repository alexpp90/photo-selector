package com.photoselectortoolbox.domain.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression suite for the bug that started this work.
 *
 * The shipped fullscreen viewer told the user "swipe ← → navigate" while a
 * leftward swipe deleted the photograph, a rightward swipe dismissed the
 * viewer, and navigation was a vertical pager. Three of five lines on that card
 * were false. Nothing caught it, because the card was a hand-written list next
 * to bindings written elsewhere.
 *
 * These tests assert the properties that make that class of bug impossible
 * rather than just re-checking today's strings.
 */
class SelectorGesturesTest {

    @Test
    fun `horizontal swipe navigates and never deletes`() {
        val row = SelectorGestures.fullscreenHintRows()
            .single { it.input == FullscreenGesture.HORIZONTAL_SWIPE.input }

        assertEquals("previous / next", row.effect)
    }

    @Test
    fun `no gesture is destructive`() {
        // The product rule — a destructive action is never a bare gesture —
        // expressed as data so it survives the next refactor. If someone binds
        // delete to a swipe again, they have to set this flag to do it, and
        // this test is where they find out that is not allowed.
        val destructive = FullscreenGesture.entries.filter { it.destructive } +
            FrameGesture.entries.filter { it.destructive }

        assertTrue(
            "these gestures are marked destructive: $destructive",
            destructive.isEmpty(),
        )
    }

    @Test
    fun `every frame gesture row corresponds to a binding`() {
        val bound = FrameGesture.entries.map { it.input }.toSet()
        val advertised = SelectorGestures.frameGestureRows().map { it.input }.toSet()

        assertEquals(bound, advertised)
    }

    @Test
    fun `no frame gesture row names a glyph`() {
        // The retired shortcut sheet wrote "tap the ⛱ badge" by hand next to a
        // badge that renders an open-in-full arrow pair. Prose that asserts what
        // a drawable looks like has nothing keeping it true.
        SelectorGestures.frameGestureRows().forEach { row ->
            listOf("⛱", "⛶", "▤", "⌨").forEach { glyph ->
                assertFalse("'${row.input}' names $glyph", row.input.contains(glyph))
                assertFalse("'${row.effect}' names $glyph", row.effect.contains(glyph))
            }
        }
    }

    @Test
    fun `a filtered shortcut row set keeps declaration order and drops nothing else`() {
        // The coach marks render only the keys belonging to the control they
        // label, and must not be able to invent one on the way.
        val subset = setOf(SelectorShortcut.PREVIOUS, SelectorShortcut.NEXT)
        val rows = SelectorGestures.selectorShortcutRows(FilingAction.COPY, subset)

        assertEquals(listOf(SelectorShortcut.PREVIOUS.input, SelectorShortcut.NEXT.input),
            rows.map { it.input })
    }

    @Test
    fun `no hint row describes filing or deleting`() {
        // Catches the wording drifting even if the boolean above is respected:
        // a card that says "swipe to keep" is as wrong as one that deletes.
        val forbidden = listOf("delete", "remove", "trash", "keep", "selection")

        SelectorGestures.fullscreenHintRows().forEach { row ->
            forbidden.forEach { word ->
                assertFalse(
                    "gesture '${row.input}' claims to '${row.effect}'",
                    row.effect.lowercase().contains(word),
                )
            }
        }
    }

    @Test
    fun `every hint row corresponds to a binding`() {
        val bound = FullscreenGesture.entries.map { it.input }.toSet()
        val advertised = SelectorGestures.fullscreenHintRows().map { it.input }.toSet()

        assertEquals(bound, advertised)
    }

    @Test
    fun `the fullscreen card lists I as the info pin`() {
        val row = SelectorGestures.fullscreenHintRows().single { it.input == "I" }

        assertEquals(FullscreenGesture.INFO.effect, row.effect)
        assertTrue(row.effect, row.effect.contains("info"))
    }

    @Test
    fun `every shortcut row has a non-empty effect`() {
        FilingAction.entries.forEach { action ->
            SelectorGestures.selectorShortcutRows(action).forEach { row ->
                assertTrue("'${row.input}' has no effect text", row.effect.isNotBlank())
                assertTrue("'${row.input}' has no key", row.input.isNotBlank())
            }
        }
    }

    @Test
    fun `shortcut sheet names both filing verbs whichever is configured`() {
        // The user can always reach both Copy and Move; the setting only
        // decides which one is emphasised. The sheet has to say so, or the
        // other verb is undiscoverable.
        FilingAction.entries.forEach { configured ->
            val row = SelectorGestures.selectorShortcutRows(configured)
                .single { it.input == SelectorShortcut.FILE_PRIMARY.input }

            assertTrue(row.effect.contains("Copy to Selection"))
            assertTrue(row.effect.contains("Move to Selection"))
        }
    }

    @Test
    fun `the filing shortcut row names the configured folder`() {
        // Same rule as the hint card and the snackbar: the destination is the
        // `selection_folder_name` setting, never a literal. The default is only
        // for call sites that have no settings in hand.
        FilingAction.entries.forEach { configured ->
            val row = SelectorGestures
                .selectorShortcutRows(configured, selectionFolderName = "Picks")
                .single { it.input == SelectorShortcut.FILE_PRIMARY.input }

            assertTrue(row.effect, row.effect.contains("Copy to Picks"))
            assertTrue(row.effect, row.effect.contains("Move to Picks"))
        }
    }

    @Test
    fun `a blank folder name falls back to the default in every phrase`() {
        FilingAction.entries.forEach { action ->
            assertEquals("${action.verb} to Selection", action.phraseFor(""))
            assertEquals("${action.verb} to Selection", action.phrase)
        }
    }

    @Test
    fun `filing action round-trips through its stored value`() {
        FilingAction.entries.forEach { action ->
            assertEquals(action, FilingAction.fromStored(action.storedValue))
        }
    }

    @Test
    fun `unknown or missing stored filing action falls back rather than throwing`() {
        // A settings string that has survived an upgrade is not trustworthy
        // input, and crashing on launch is worse than defaulting.
        assertEquals(FilingAction.DEFAULT, FilingAction.fromStored(null))
        assertEquals(FilingAction.DEFAULT, FilingAction.fromStored(""))
        assertEquals(FilingAction.DEFAULT, FilingAction.fromStored("archive"))
    }

    @Test
    fun `stored values are the ones already on users devices`() {
        // Changing these silently resets everyone's preference.
        assertEquals("copy", FilingAction.COPY.storedValue)
        assertEquals("move", FilingAction.MOVE.storedValue)
    }

    @Test
    fun `each filing action points at the other one`() {
        assertEquals(FilingAction.MOVE, FilingAction.COPY.other)
        assertEquals(FilingAction.COPY, FilingAction.MOVE.other)
    }

    @Test
    fun `every selector shortcut is listed exactly once`() {
        val inputs = SelectorGestures.selectorShortcutRows(FilingAction.COPY).map { it.input }

        assertEquals(inputs.size, inputs.toSet().size)
        assertNotNull(inputs.firstOrNull { it == SelectorShortcut.MAXIMISE.input })
    }
}
