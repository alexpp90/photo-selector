package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A held key auto-repeats. Delete, Backspace, M and C act on its first press only (FD-D-2): a
 * repeated delete commits frames to disk once 20 are stacked, a repeated copy cannot be undone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectorHeldKeyTest {

    private var moves = 0
    private var copies = 0
    private var deletes = 0
    private var nexts = 0

    private fun press(key: Key, repeat: Boolean): Boolean = handleSelectorKey(
        key = key,
        sheetOpen = false,
        contextMenuOpen = false,
        fullscreenOpen = false,
        maximised = false,
        actions = SelectorActions({ moves++ }, { copies++ }, { deletes++ }, {}, {}, {}, {}, {}),
        onPrevious = {},
        onNext = { nexts++ },
        onMaximise = {},
        onShowShortcuts = {},
        onCloseOverlays = {},
        repeat = repeat,
    )

    @Test
    fun `held Delete, Backspace, M and C act once and swallow their repeats`() {
        for (key in listOf(Key.Delete, Key.Backspace, Key.M, Key.C)) {
            assertTrue(press(key, repeat = false))
            repeat(10) { assertTrue("a repeat of $key is consumed", press(key, repeat = true)) }
        }

        assertEquals(2, deletes)
        assertEquals(1, moves)
        assertEquals(1, copies)
    }

    @Test
    fun `a held arrow keeps navigating`() {
        press(Key.DirectionRight, repeat = false)
        repeat(3) { press(Key.DirectionRight, repeat = true) }

        assertEquals(4, nexts)
    }
}
