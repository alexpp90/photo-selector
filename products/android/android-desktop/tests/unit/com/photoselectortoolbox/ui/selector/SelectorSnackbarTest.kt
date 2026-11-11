package com.photoselectortoolbox.ui.selector

import com.photoselector.core.Requirement
import android.content.Context
import android.content.ContextWrapper
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    /**
     * UNDO restores files, so REQ-AND-INPUT.04's 48 dp floor holds for it: measured on the tagged
     * node, which is the clickable, in whole pixels, at font scale 1.0 and 2.0 (NATIVE graphics,
     * so the text really grows). Seen red with the button's `defaultMinSize` removed.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Requirement("REQ-AND-INPUT.04", "REQ-AND-LAYOUT.21")
    fun `UNDO is a 48 dp button at font scale 1_0 and 2_0`() {
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                SelectorSnackbar(message = "Deleted", onUndo = {}, onDismiss = {}, sequence = 1, lifetime = SnackbarLifetime.DELETE)
            }
        }
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            rule.waitForIdle()
            val node = rule.onNodeWithTag("snackbar_undo", useUnmergedTree = true)
            node.assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val min = (48 * rule.density.density).roundToInt()
            assertTrue("UNDO ${bounds.width} px wide at font scale $scale, under $min", bounds.width.roundToInt() >= min)
            assertTrue("UNDO ${bounds.height} px tall at font scale $scale, under $min", bounds.height.roundToInt() >= min)
        }
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
    fun `a delete's card promises no deadline - no line, UNDO, and it leaves like a move's`() {
        val state = State().apply { message = "Deleted"; sequence = 1; lifetime = SnackbarLifetime.DELETE }
        host(state, withUndo = true)

        // Nothing is committed when the card leaves (FD-D-1): Ctrl+Z works until the folder is left.
        assertEquals(0, count("snackbar_countdown"))
        assertEquals(1, count("snackbar_undo"))
        rule.mainClock.advanceTimeBy(3_900)
        assertEquals(1, count("selector_snackbar"))
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        assertEquals(0, count("selector_snackbar"))
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

    @Test
    @Requirement("REQ-AND-LAYOUT.21")
    fun `the card is announced by a screen reader and UNDO is a button`() {
        val state = State().apply { message = "Moved to Selection" }
        host(state, withUndo = true)

        rule.onNodeWithTag("selector_snackbar", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithTag("snackbar_undo", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }
}
