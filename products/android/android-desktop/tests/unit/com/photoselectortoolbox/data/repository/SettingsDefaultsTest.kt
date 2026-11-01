package com.photoselectortoolbox.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults a photographer gets before they have chosen anything.
 *
 * These are product decisions, not implementation details, so they are pinned
 * where a build can fail on them rather than left as a literal beside a
 * DataStore key. A default that drifts is a change nobody reviews: it does not
 * show up as a behaviour change in any test that sets the preference first, and
 * every test in the instrumented suite sets this one first.
 *
 * The constants are `const val`, so these assertions are resolved at compile
 * time and need no Android runtime.
 */
class SettingsDefaultsTest {

    @Test
    fun `the filmstrip starts hidden`() {
        // The compare-and-cull loop is Prev/Next and the two neighbour frames.
        // The strip is random access to the rest of the shoot — useful, but not
        // what the screen opens for, and the first thing a photographer sees
        // should be three photographs and nothing else. It is still a persisted
        // toggle; only the starting position changed (REQUIREMENTS §2).
        assertFalse(SettingsRepository.DEFAULT_FILMSTRIP_VISIBLE)
    }

    @Test
    fun `the fullscreen info bar starts unpinned`() {
        // A bar that never fades sits on the sky of the frame being inspected, so
        // pinning is a choice (`I`), not the opening state.
        assertFalse(SettingsRepository.DEFAULT_FULLSCREEN_INFO_PINNED)
    }

    @Test
    fun `sorting is on and grouping is off`() {
        // Pinned alongside, so this file is the one place the opening state of
        // the selector is stated.
        assertTrue(SettingsRepository.DEFAULT_SORTING_ENABLED)
        assertFalse(SettingsRepository.DEFAULT_GROUPING_ENABLED)
    }
}
