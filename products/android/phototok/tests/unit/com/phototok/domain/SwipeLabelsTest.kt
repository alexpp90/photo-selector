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
 * The swipe indicators are the only thing telling a gesture-first user what is
 * about to happen, so the regression these tests exist for is a label that
 * describes a *default* rather than the user's configured action — the "KEEP"
 * label that appeared on a swipe right even when the photo was being moved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeLabelsTest {

    @Test
    @Requirement("REQ-TOK-GESTURE.01")
    fun `swipe right is labelled with the configured collection action`() {
        assertEquals(UiText.of(R.string.label_swipe_copy), SwipeLabels.rightLabel(CollectionAction.COPY))
        assertEquals(UiText.of(R.string.label_swipe_move), SwipeLabels.rightLabel(CollectionAction.MOVE))
        assertEquals("Copy", SwipeLabels.rightLabel(CollectionAction.COPY).en())
        assertEquals("Verschieben", SwipeLabels.rightLabel(CollectionAction.MOVE).de())
    }

    @Test
    fun `swipe right is never labelled KEEP`() {
        CollectionAction.entries.forEach { action ->
            assertFalse(
                "swipe right must name the action, not imply keeping",
                SwipeLabels.rightLabel(action).en().equals("KEEP", ignoreCase = true),
            )
        }
    }

    @Test
    fun `swipe left is labelled with the configured left-swipe action`() {
        assertEquals(UiText.of(R.string.label_swipe_delete), SwipeLabels.leftLabel(SwipeAction.DELETE))
        assertEquals(UiText.of(R.string.label_swipe_copy), SwipeLabels.leftLabel(SwipeAction.COPY))
        assertEquals(UiText.of(R.string.label_swipe_move), SwipeLabels.leftLabel(SwipeAction.MOVE))
    }

    @Test
    fun `every action has a distinct label so the two swipes are never ambiguous`() {
        val labels = SwipeAction.entries.map { SwipeLabels.leftLabel(it) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `right description names the target folder`() {
        assertEquals(
            UiText.of(R.string.cd_swipe_copy, "Keepers"),
            SwipeLabels.rightDescription(CollectionAction.COPY, "Keepers"),
        )
        assertEquals(
            UiText.of(R.string.cd_swipe_move, "Keepers"),
            SwipeLabels.rightDescription(CollectionAction.MOVE, "Keepers"),
        )
        assertEquals("Copy to Keepers", SwipeLabels.rightDescription(CollectionAction.COPY, "Keepers").en())
        assertEquals("Nach Keepers verschieben", SwipeLabels.rightDescription(CollectionAction.MOVE, "Keepers").de())
    }

    @Test
    fun `right folder falls back to the folder the app creates when no name is set`() {
        assertEquals(PhotoFolders.SELECTION, SwipeLabels.rightFolder(""))
        assertEquals(PhotoFolders.SELECTION, SwipeLabels.rightFolder("   "))
        assertEquals("Keepers", SwipeLabels.rightFolder("Keepers"))
        assertEquals(
            UiText.of(R.string.cd_swipe_copy, PhotoFolders.SELECTION),
            SwipeLabels.rightDescription(CollectionAction.COPY, ""),
        )
    }

    @Test
    fun `left description names the folder for copy and move but not for delete`() {
        assertEquals(
            UiText.of(R.string.cd_swipe_copy, "Rejects"),
            SwipeLabels.leftDescription(SwipeAction.COPY, "Rejects"),
        )
        assertEquals(
            UiText.of(R.string.cd_swipe_move, "Rejects"),
            SwipeLabels.leftDescription(SwipeAction.MOVE, "Rejects"),
        )
        // A delete has no destination folder to name.
        assertEquals(UiText.of(R.string.cd_swipe_delete), SwipeLabels.leftDescription(SwipeAction.DELETE, "Rejects"))
        assertNull(SwipeLabels.leftFolder(SwipeAction.DELETE, "Rejects"))
        assertEquals("Rejects", SwipeLabels.leftFolder(SwipeAction.COPY, "Rejects"))
    }

    @Test
    fun `left description falls back to the left-swipe folder the app creates`() {
        assertEquals(PhotoFolders.LEFT_SWIPE, SwipeLabels.leftFolder(SwipeAction.MOVE, ""))
        assertEquals(
            UiText.of(R.string.cd_swipe_move, PhotoFolders.LEFT_SWIPE),
            SwipeLabels.leftDescription(SwipeAction.MOVE, ""),
        )
    }

    @Test
    fun `only delete is destructive`() {
        assertTrue(SwipeLabels.leftIsDestructive(SwipeAction.DELETE))
        assertFalse(SwipeLabels.leftIsDestructive(SwipeAction.COPY))
        assertFalse(SwipeLabels.leftIsDestructive(SwipeAction.MOVE))
    }
}
