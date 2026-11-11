package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.input.key.Key
import com.photoselector.core.Requirement
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.interaction.SelectorShortcut
import com.photoselectortoolbox.ui.inspect.handleInspectKey
import com.photoselectortoolbox.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk-app key table (`docs/shared/KEYBOARD.md`, backlog 2026-11-10 "the same key does
 * different things"): `T` is the filmstrip and `I` the info (readout) block in every desk app, so
 * on Frameduel `I` no longer opens Learn (`L` does) and no longer leaves it (`L` and `Esc` do);
 * `J` / `K` are next / previous as on Lensjet and Blitzlens. The guide lists every one of them.
 */
@Requirement("REQ-AND-LAYOUT.25")
class SelectorKeyAlignmentTest {

    private class Calls {
        var details = 0
        var filmstrip = 0
        var previous = 0
        var next = 0
        var navigated = mutableListOf<Screen>()
    }

    private fun press(key: Key, calls: Calls): Boolean = handleSelectorKey(
        key = key,
        sheetOpen = false,
        contextMenuOpen = false,
        fullscreenOpen = false,
        maximised = false,
        actions = SelectorActions(
            onMove = {}, onCopy = {}, onDelete = {}, onFullscreen = {},
            onToggleDetails = { calls.details++ },
            onToggleFilmstrip = { calls.filmstrip++ },
            onToggleOverlayValues = {}, onShowShortcuts = {},
        ),
        onPrevious = { calls.previous++ },
        onNext = { calls.next++ },
        onMaximise = {},
        onShowShortcuts = {},
        onCloseOverlays = {},
        onNavigate = { calls.navigated += it },
    )

    @Test
    fun `T toggles the filmstrip`() {
        val calls = Calls()
        assertTrue(press(Key.T, calls))
        assertEquals(1, calls.filmstrip)
    }

    @Test
    fun `I toggles the readout block and no longer opens Learn`() {
        val calls = Calls()
        assertTrue(press(Key.I, calls))
        assertEquals(1, calls.details)
        assertTrue(calls.navigated.isEmpty())
    }

    @Test
    fun `L still opens Learn`() {
        val calls = Calls()
        assertTrue(press(Key.L, calls))
        assertEquals(listOf(Screen.Inspect), calls.navigated)
    }

    @Test
    fun `J and K are next and previous`() {
        val calls = Calls()
        assertTrue(press(Key.J, calls))
        assertTrue(press(Key.K, calls))
        assertEquals(1, calls.next)
        assertEquals(1, calls.previous)
    }

    @Test
    fun `in Learn I no longer leaves, L and Esc do`() {
        var left = 0
        fun inspect(key: Key) = handleInspectKey(key, onNavigateToSelector = { left++ }, onPrevious = {}, onNext = {}, onSetRating = {})
        assertFalse(inspect(Key.I))
        assertEquals(0, left)
        assertTrue(inspect(Key.L))
        assertTrue(inspect(Key.Escape))
        assertEquals(2, left)
    }

    @Test
    fun `the guide lists L, I, T, J and K and Ctrl+Z, and words the digits as enlarging a frame`() {
        val inputs = SelectorShortcut.entries.map { it.input }
        for (key in listOf("L", "I", "T", "J / K", "Ctrl+Z")) assertTrue("$key missing from $inputs", key in inputs)
        val digits = SelectorShortcut.MAXIMISE.describeEffect(FilingAction.COPY)
        assertTrue(digits, digits.startsWith("enlarge"))
    }
}
