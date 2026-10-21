package com.phototok.domain

import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.de
import com.phototok.en
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-time explanations must describe what actually happened — the wrong
 * verb or the wrong folder is worse than no hint at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

        assertEquals(UiText.of(R.string.hint_title_swipe_right_copy, UiText.raw("Keepers")), title)
        assertEquals("Swipe right copies to Keepers", title.en())
        assertEquals("Rechts wischen kopiert nach Keepers", title.de())
    }

    @Test
    fun `swipe right uses move wording when configured to move`() {
        val title = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_RIGHT,
            collectionAction = CollectionAction.MOVE,
            collectionFolderName = "Keepers",
        )

        assertEquals(UiText.of(R.string.hint_title_swipe_right_move, UiText.raw("Keepers")), title)
        assertEquals("Swipe right moves to Keepers", title.en())
    }

    @Test
    fun `swipe right falls back to the folder the app creates without a folder name`() {
        val title = FirstRunHintText.title(
            hint = FirstRunHint.SWIPE_RIGHT,
            collectionAction = CollectionAction.COPY,
            collectionFolderName = "",
        )

        assertEquals(UiText.of(R.string.hint_title_swipe_right_copy, UiText.raw(PhotoFolders.SELECTION)), title)
    }

    @Test
    fun `swipe right favourite names no folder`() {
        val title = FirstRunHintText.title(hint = FirstRunHint.SWIPE_RIGHT, swipeRightVerb = SwipeRightVerb.FAVOURITE)

        assertEquals(UiText.of(R.string.hint_title_swipe_right_favourite), title)
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

        assertEquals(UiText.of(R.string.hint_title_swipe_left_copy, UiText.raw("Rejects")), copy)
        assertEquals(UiText.of(R.string.hint_title_swipe_left_move, UiText.raw("Rejects")), move)
        assertEquals("Swipe left copies to Rejects", copy.en())
        assertEquals("Swipe left moves to Rejects", move.en())
    }

    @Test
    fun `delete hint never claims a trash`() {
        val title = FirstRunHintText.title(FirstRunHint.SWIPE_LEFT_DELETE)
        val message = FirstRunHintText.message(FirstRunHint.SWIPE_LEFT_DELETE)

        assertEquals(UiText.of(R.string.hint_title_swipe_left_delete), title)
        // No second line: the controls guide names Undo once (REQ-TOK-UX.04).
        assertNull(message)
        val words = title.en() + " " + title.de()
        assertFalse(words, words.contains("trash", ignoreCase = true))
        assertFalse(words, words.contains("Papierkorb-Ordner", ignoreCase = true))
    }

    @Test
    fun `every hint has a title`() {
        FirstRunHint.entries.forEach { hint ->
            assertTrue(hint.name, FirstRunHintText.title(hint).en().isNotBlank())
            assertTrue(hint.name, FirstRunHintText.title(hint).de().isNotBlank())
        }
    }

    @Test
    @Requirement("REQ-TOK-UX.01")
    fun `hint copy stays short and never spells out a Settings pointer`() {
        FirstRunHint.entries.forEach { hint ->
            FileTypeFilter.entries.forEach { filter ->
                val title = FirstRunHintText.title(hint, fileTypeFilter = filter, collectionFolderName = "Selection")
                val message = FirstRunHintText.message(hint, fileTypeFilter = filter)
                val texts = listOfNotNull(title, message)
                texts.forEach { text ->
                    assertTrue("${hint.name} en: ${text.en()}", text.en().split(" ").size <= 6 + 1)
                    assertFalse(hint.name, text.en().contains("Settings"))
                    assertFalse(hint.name, text.de().contains("Einstellungen"))
                }
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
        assertEquals(
            UiText.of(R.string.hint_title_filter_raw),
            FirstRunHintText.title(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.RAW),
        )
        assertEquals(
            UiText.of(R.string.hint_title_filter_jpeg),
            FirstRunHintText.title(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.JPG),
        )
        assertEquals(
            UiText.of(R.string.hint_body_filter_mostly_jpeg),
            FirstRunHintText.message(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.RAW),
        )
        assertEquals(
            UiText.of(R.string.hint_body_filter_mostly_raw),
            FirstRunHintText.message(FirstRunHint.FILTER_MISMATCH, fileTypeFilter = FileTypeFilter.JPG),
        )
    }

    @Test
    fun `hints without a second line have no message`() {
        assertNull(FirstRunHintText.message(FirstRunHint.SWIPE_RIGHT))
        assertNull(FirstRunHintText.message(FirstRunHint.TAP_HUD))
    }
}
