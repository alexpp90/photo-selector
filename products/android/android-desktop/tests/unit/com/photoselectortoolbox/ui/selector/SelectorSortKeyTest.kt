package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.viewmodel.SelectorFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.photoselectortoolbox.testing.en

/**
 * `S` opens the Sort menu — it never cycles the order — and while the menu is open (it counts as
 * an open sheet) every selector key but Esc is suppressed.
 */
@Requirement("REQ-AND-WORKFLOW.06")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectorSortKeyTest {

    private class Calls {
        var sortMenu = 0
        var filed = 0
        var maximised = mutableListOf<SelectorFrame>()
        var closed = 0
    }

    private fun press(
        key: Key,
        calls: Calls,
        sheetOpen: Boolean = false,
        withSortMenu: Boolean = true,
        ctrl: Boolean = false,
    ): Boolean = handleSelectorKey(
        key = key,
        sheetOpen = sheetOpen,
        contextMenuOpen = false,
        fullscreenOpen = false,
        maximised = false,
        actions = SelectorActions({ calls.filed++ }, { calls.filed++ }, {}, {}, {}, {}, {}, {}),
        onPrevious = {},
        onNext = {},
        onMaximise = { calls.maximised += it },
        onShowShortcuts = {},
        onCloseOverlays = { calls.closed++ },
        onSortMenu = if (withSortMenu) ({ calls.sortMenu++ }) else null,
        ctrlOrMetaPressed = ctrl,
    )

    @Test
    fun `S opens the sort menu and is consumed`() {
        val calls = Calls()

        assertTrue(press(Key.S, calls))

        assertEquals(1, calls.sortMenu)
    }

    @Test
    fun `S only opens, so pressing it twice never reorders anything`() {
        val calls = Calls()

        press(Key.S, calls)
        press(Key.S, calls)

        // Each press asks for the menu; nothing else is invoked.
        assertEquals(2, calls.sortMenu)
        assertEquals(0, calls.filed)
    }

    @Test
    fun `S is not consumed where there is no menu to open`() {
        val calls = Calls()

        assertFalse(press(Key.S, calls, withSortMenu = false))
        assertEquals(0, calls.sortMenu)
    }

    @Test
    fun `with the menu open every selector key but Esc is suppressed`() {
        val calls = Calls()

        listOf(Key.S, Key.M, Key.C, Key.One, Key.Two, Key.Three, Key.G, Key.Delete).forEach { key ->
            assertFalse("$key must not be handled while the menu is open", press(key, calls, sheetOpen = true))
        }

        assertEquals(0, calls.sortMenu)
        assertEquals(0, calls.filed)
        assertTrue(calls.maximised.isEmpty())
    }

    @Test
    fun `Escape closes the open menu`() {
        val calls = Calls()

        assertTrue(press(Key.Escape, calls, sheetOpen = true))

        assertEquals(1, calls.closed)
    }

    @Test
    fun `Ctrl S is not the sort key`() {
        val calls = Calls()

        assertFalse(press(Key.S, calls, ctrl = true))
        assertEquals(0, calls.sortMenu)
    }

    @Test
    fun `the key the guide lists is the key that is bound`() {
        assertEquals("S", SelectorShortcut.SORT.input.en())
        assertEquals("choose the sort order", SelectorShortcut.SORT.describeEffect(FilingAction.COPY).en())
    }

    @Test
    fun `the choice is the order and the worst-first flag as one value`() {
        assertEquals(SortChoice.NEWEST_FIRST, SortChoice.of(SelectorSortOrder.NEWEST_FIRST, false))
        assertEquals(SortChoice.OLDEST_FIRST, SortChoice.of(SelectorSortOrder.OLDEST_FIRST, false))
        assertEquals(SortChoice.FILE_NAME, SortChoice.of(SelectorSortOrder.FILE_NAME, false))
        // Worst-first wins whatever plain order is kept underneath it.
        assertEquals(SortChoice.BLURRIEST_FIRST, SortChoice.of(SelectorSortOrder.OLDEST_FIRST, true))
        assertEquals(setOf("Newest", "Oldest", "Name", "Blurry"), SortChoice.entries.map { it.chipWord.en() }.toSet())
        assertEquals(
            listOf("Newest first", "Oldest first", "File name", "Blurriest first"),
            SortChoice.entries.map { it.menuLabel.en() },
        )
    }
}
