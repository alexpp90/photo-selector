package com.photoselectortoolbox.ui.selector

import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the confirmation card does with time: a move or copy just goes away, a
 * delete shows its deadline, a second identical message gets its own full
 * read time, and a screen-reader user gets the platform's longer one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectorSnackbarTest {

    @get:Rule
    val rule = createComposeRule()

    private class State {
        var message by mutableStateOf<String?>(null)
        var sequence by mutableStateOf(0L)
        var lifetime by mutableStateOf(SnackbarLifetime.MOVE)
        var dismissed = 0
    }

    private fun host(state: State, withUndo: Boolean = false) {
        rule.setContent {
            SelectorSnackbar(
                message = state.message,
                onUndo = if (withUndo) ({}) else null,
                onDismiss = {
                    state.dismissed++
                    state.message = null
                },
                sequence = state.sequence,
                lifetime = state.lifetime,
            )
        }
    }

    private fun count(tag: String) =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `a move's card has no countdown line and leaves after 4 seconds`() {
        val state = State().apply { message = "Moved to Selection"; sequence = 1; lifetime = SnackbarLifetime.MOVE }
        host(state, withUndo = true)

        assertEquals(1, count("selector_snackbar"))
        assertEquals("no line for a move", 0, count("snackbar_countdown"))
        assertEquals(1, count("snackbar_undo"))

        rule.mainClock.advanceTimeBy(3_900)
        assertEquals("still there at 3.9 s", 1, count("selector_snackbar"))
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        assertEquals("gone after 4 s", 0, count("selector_snackbar"))
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `a copy's card leaves after 3 seconds`() {
        val state = State().apply { message = "Copied to Selection"; sequence = 1; lifetime = SnackbarLifetime.COPY }
        host(state)

        rule.mainClock.advanceTimeBy(2_900)
        assertEquals(1, count("selector_snackbar"))
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        assertEquals(0, count("selector_snackbar"))
    }

    @Test
    fun `a delete's card draws the deadline line`() {
        val state = State().apply { message = "1 image deleted"; sequence = 1; lifetime = SnackbarLifetime.DELETE }
        host(state, withUndo = true)

        assertEquals(1, count("snackbar_countdown"))
        rule.mainClock.advanceTimeBy(5_000)
        assertEquals("a delete is not a 4 s notice", 1, count("selector_snackbar"))
    }

    @Test
    fun `a second identical message restarts the clock`() {
        // Keyed on the text, the second "Moved to Selection" inherited the first's
        // clock and vanished early.
        val state = State().apply { message = "Moved to Selection"; sequence = 1; lifetime = SnackbarLifetime.MOVE }
        host(state)

        rule.mainClock.advanceTimeBy(3_000)
        state.message = "Moved to Selection"
        state.sequence = 2
        rule.waitForIdle()

        rule.mainClock.advanceTimeBy(2_000)
        assertEquals("5 s after the first, 2 s after the second", 1, count("selector_snackbar"))
        assertEquals(0, state.dismissed)

        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        assertEquals(0, count("selector_snackbar"))
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `the platform's accessibility timeout keeps a notice up longer`() {
        // A context that hands out an AccessibilityManager recommending 10 s, the
        // way a user with a long "time to take action" setting would have.
        val manager = mockk<AccessibilityManager>(relaxed = true) {
            every { getRecommendedTimeoutMillis(any(), any()) } returns 10_000
        }
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.ACCESSIBILITY_SERVICE) manager else super.getSystemService(name)
        }

        val state = State().apply { message = "Moved to Selection"; sequence = 1; lifetime = SnackbarLifetime.MOVE }
        rule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                SelectorSnackbar(
                    message = state.message,
                    onUndo = {},
                    onDismiss = { state.dismissed++; state.message = null },
                    sequence = state.sequence,
                    lifetime = state.lifetime,
                )
            }
        }

        rule.mainClock.advanceTimeBy(9_000)
        assertEquals("a screen-reader user still has the card at 9 s", 1, count("selector_snackbar"))
        verify {
            manager.getRecommendedTimeoutMillis(
                4_000,
                AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
            )
        }
        rule.mainClock.advanceTimeBy(1_100)
        rule.waitForIdle()
        assertEquals(0, count("selector_snackbar"))
    }
}
