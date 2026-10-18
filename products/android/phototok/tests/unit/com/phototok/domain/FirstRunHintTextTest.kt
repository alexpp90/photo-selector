package com.phototok.domain

import com.photoselector.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time explanations must describe what actually happened — the wrong
 * verb or the wrong folder is worse than no hint at all.
 */
class FirstRunHintTextTest {

    @Test
    fun `hint keys round-trip and are unique`() {
        val keys = FirstRunHint.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        FirstRunHint.entries.forEach { hint ->
            assertEquals(hint, FirstRunHint.fromKey(hint.key))
        }
        assertNull(FirstRunHint.fromKey("not_a_hint"))
        assertNull(FirstRunHint.fromKey(null))
    }

    @Test
    fun `swipe right names the rule with copy wording and the collection folder`() {
        val title = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_RIGHT,
            collectionAction = CollectionAction.COPY,
            collectionFolderName = "Keepers",
        )

        assertEquals("Swipe right copies to Keepers", title)
    }

    @Test
    fun `swipe right uses move wording when configured to move`() {
        val title = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_RIGHT,
            collectionAction = CollectionAction.MOVE,
            collectionFolderName = "Keepers",
        )

        assertEquals("Swipe right moves to Keepers", title)
    }

    @Test
    fun `swipe right falls back to generic wording without a folder name`() {
        val title = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_RIGHT,
            collectionAction = CollectionAction.COPY,
            collectionFolderName = "",
        )

        assertEquals("Swipe right copies to collection", title)
    }

    @Test
    fun `left swipe folder hint reflects copy versus move`() {
        val copy = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_LEFT_FOLDER,
            leftSwipeAction = SwipeAction.COPY,
            leftSwipeFolderName = "Rejects",
        )
        val move = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_LEFT_FOLDER,
            leftSwipeAction = SwipeAction.MOVE,
            leftSwipeFolderName = "Rejects",
        )

        assertEquals("Swipe left copies to Rejects", copy)
        assertEquals("Swipe left moves to Rejects", move)
    }

    @Test
    fun `delete hint points at revert and never claims a trash`() {
        val title = FirstRunHintText.title(FirstRunHint.SWIPE_LEFT_DELETE)
        val message = FirstRunHintText.message(FirstRunHint.SWIPE_LEFT_DELETE)

        assertEquals("Swipe left deletes", title)
        assertTrue(message, message.contains("Revert"))
        assertFalse(title + message, (title + message).contains("trash", ignoreCase = true))
    }

    @Test
    fun `every hint has a non-empty title`() {
        FirstRunHint.entries.forEach { hint ->
            assertTrue(hint.name, FirstRunHintText.title(hint).isNotBlank())
        }
    }

    @Test
    @Requirement("REQ-TOK-UX.01")
    fun `hint copy stays short and never spells out a Settings pointer`() {
        FirstRunHint.entries.forEach { hint ->
            FileTypeFilter.entries.forEach { filter ->
                val title = FirstRunHintText.title(hint, fileTypeFilter = filter, collectionFolderName = "Selection")
                val message = FirstRunHintText.message(hint, fileTypeFilter = filter)
                assertTrue("${hint.name} title: $title", title.split(" ").size <= 6)
                assertTrue("${hint.name} message: $message", message.split(" ").size <= 6)
                assertFalse(hint.name, (title + message).contains("Settings"))
            }
        }
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.05")
    fun `configurable swipe hints offer a Settings action instead`() {
        assertTrue(FirstRunHintText.offersSettings(FirstRunHint.SWIPE_RIGHT))
        assertTrue(FirstRunHintText.offersSettings(FirstRunHint.SWIPE_LEFT_FOLDER))
        assertTrue(FirstRunHintText.offersSettings(FirstRunHint.SWIPE_LEFT_DELETE))
        assertFalse(FirstRunHintText.offersSettings(FirstRunHint.FILTER_MISMATCH))
        assertFalse(FirstRunHintText.offersSettings(FirstRunHint.TAP_HUD))
    }

    @Test
    fun `filter mismatch hint reflects active filter`() {
        assertEquals("RAW only", FirstRunHintText.title(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.RAW))
        assertEquals("JPEG only", FirstRunHintText.title(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.JPG))
        assertEquals(
            "Most photos here are JPEG.",
            FirstRunHintText.message(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.RAW),
        )
        assertEquals(
            "Most photos here are RAW.",
            FirstRunHintText.message(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.JPG),
        )
    }
}
