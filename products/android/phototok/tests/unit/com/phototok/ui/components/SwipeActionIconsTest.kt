package com.phototok.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import org.junit.Assert.assertEquals
import org.junit.Test

/** One glyph per swipe action, everywhere it is drawn (review 2026-11-03, JP-Q-5). */
class SwipeActionIconsTest {

    @Test
    fun everySwipeRightVerbHasItsGlyph() {
        assertEquals(Icons.Default.ContentCopy, SwipeActionIcons.right(SwipeRightVerb.COPY))
        assertEquals(Icons.AutoMirrored.Filled.DriveFileMove, SwipeActionIcons.right(SwipeRightVerb.MOVE))
        assertEquals(Icons.Default.Star, SwipeActionIcons.right(SwipeRightVerb.FAVOURITE))
    }

    @Test
    fun everySwipeLeftActionHasItsGlyph() {
        assertEquals(Icons.Default.Delete, SwipeActionIcons.left(SwipeAction.DELETE))
        assertEquals(Icons.Default.ContentCopy, SwipeActionIcons.left(SwipeAction.COPY))
        assertEquals(Icons.AutoMirrored.Filled.DriveFileMove, SwipeActionIcons.left(SwipeAction.MOVE))
    }

    @Test
    fun aCopyAndAMoveOfEitherSideLookTheSame() {
        assertEquals(SwipeActionIcons.right(SwipeRightVerb.COPY), SwipeActionIcons.left(SwipeAction.COPY))
        assertEquals(SwipeActionIcons.right(SwipeRightVerb.MOVE), SwipeActionIcons.left(SwipeAction.MOVE))
    }
}
