package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import com.photoselectortoolbox.viewmodel.SelectorFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `G` opens "Go to photo", and while the dialog is open (it counts as an open sheet)
 * every selector key but Esc is suppressed — so a `1` typed into its field cannot
 * maximise a frame.
 */
@Requirement("REQ-AND-WORKFLOW.07")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectorJumpKeyTest {

    private class Calls {
        var goTo = 0
        var maximised = mutableListOf<SelectorFrame>()
        var closed = 0
    }

    private fun press(
        key: Key,
        calls: Calls,
        sheetOpen: Boolean = false,
        withGoTo: Boolean = true,
    ): Boolean = handleSelectorKey(
        key = key,
        sheetOpen = sheetOpen,
        contextMenuOpen = false,
        fullscreenOpen = false,
        maximised = false,
        actions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {}),
        onPrevious = {},
        onNext = {},
        onMaximise = { calls.maximised += it },
        onShowShortcuts = {},
        onCloseOverlays = { calls.closed++ },
        onGoToPhoto = if (withGoTo) ({ calls.goTo++ }) else null,
    )

    @Test
    fun `G opens Go to photo and is consumed`() {
        val calls = Calls()

        assertTrue(press(Key.G, calls))

        assertEquals(1, calls.goTo)
    }

    @Test
    fun `G is not consumed where nothing can open the dialog`() {
        val calls = Calls()

        assertFalse(press(Key.G, calls, withGoTo = false))
        assertEquals(0, calls.goTo)
    }

    @Test
    fun `with the dialog open a 1, 2 or 3 maximises nothing and G opens nothing`() {
        val calls = Calls()

        listOf(Key.One, Key.Two, Key.Three, Key.G).forEach { key ->
            assertFalse("$key must not be handled by the selector", press(key, calls, sheetOpen = true))
        }

        assertTrue(calls.maximised.isEmpty())
        assertEquals(0, calls.goTo)
    }

    @Test
    fun `Escape still closes it`() {
        val calls = Calls()

        assertTrue(press(Key.Escape, calls, sheetOpen = true))

        assertEquals(1, calls.closed)
    }

    @Test
    fun `without the dialog a 1 still maximises the previous frame`() {
        val calls = Calls()

        assertTrue(press(Key.One, calls))

        assertEquals(listOf(SelectorFrame.PREVIOUS), calls.maximised)
    }

    @Test
    fun `the shortcut the guide lists is the key that is bound`() {
        assertEquals("G", SelectorShortcut.GO_TO.input)
        assertEquals("go to photo", SelectorShortcut.GO_TO.describeEffect(com.photoselectortoolbox.domain.interaction.FilingAction.COPY))
    }
}
