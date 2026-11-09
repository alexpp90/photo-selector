package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.tagExists
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorUiState
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coVerify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regressions of the compact (< 600 dp, REQ-AND-LAYOUT.02) selector from PR #426.
 *
 * - F-FD-002: the action buttons sat inside a swipe-to-dismiss box, so a tap and a swipe fought
 *   and a swipe could delete. A tap fires its own action once; a horizontal swipe over the
 *   buttons or the info card fires none (REQ-AND-INPUT.01: a gesture that destroys a file never
 *   shares a direction with browsing).
 * - F-FD-004: the gesture hint came back although it had been seen. It shows on a first visit,
 *   records the dismissal, and never shows once seen.
 *
 * F-FD-003 (the compact controls did not fit) is held by `ScreenFitMatrixTest` at 360 x 720.
 */
@Requirement("REQ-AND-LAYOUT.02", "REQ-AND-INPUT.01")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp-xhdpi")
class CompactSelectorRegressionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private var viewModel: SelectorViewModel? = null

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private val photos: List<ImageItem> = (1..4).map { fitPhoto("P$it.JPG", 10_000L - it) }

    private class Calls {
        var moves = 0
        var copies = 0
        var deletes = 0
        var fullscreens = 0
        val total get() = moves + copies + deletes + fullscreens
    }

    private fun hostLayout(): Calls {
        val calls = Calls()
        val state = SelectorUiState(images = photos, currentIndex = 0, hasSeenFullscreenHint = true)
        rule.setContent {
            PhotoSelectorToolboxTheme {
                CompactSelectorLayout(
                    uiState = state,
                    onNavigateToImage = {},
                    onFullscreen = { calls.fullscreens++ },
                    onMoveToSelection = { calls.moves++ },
                    onCopyToSelection = { calls.copies++ },
                    onDelete = { calls.deletes++ },
                )
            }
        }
        rule.waitForIdle()
        return calls
    }

    @Test
    fun `F-FD-002 a tap on each compact action fires that action once`() {
        val calls = hostLayout()
        rule.onNodeWithTag("copy_button_compact").performClick()
        rule.onNodeWithTag("move_button_compact").performClick()
        rule.onNodeWithTag("delete_button_compact").performClick()
        rule.waitForIdle()
        assertEquals(1, calls.copies)
        assertEquals(1, calls.moves)
        assertEquals(1, calls.deletes)
        assertEquals(0, calls.fullscreens)
    }

    @Test
    fun `F-FD-002 a horizontal swipe across the action row fires no action`() {
        val calls = hostLayout()
        // A swipe the length of the row, starting on each button: what a photographer does who
        // expects the row to browse like the photo above it. (A drag that never leaves one
        // button is a press on it, as on any Android button.)
        val row = listOf("copy_button_compact", "move_button_compact", "delete_button_compact")
            .map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val left = row.minOf { it.left }
        val right = row.maxOf { it.right }
        for (tag in listOf("copy_button_compact", "move_button_compact", "delete_button_compact")) {
            rule.onNodeWithTag(tag).performTouchInput {
                val y = centerY
                swipe(Offset(width - 1f, y), Offset(left - visibleSize.width - (right - left), y), durationMillis = 200)
            }
            rule.waitForIdle()
            rule.onNodeWithTag(tag).performTouchInput {
                val y = centerY
                swipe(Offset(1f, y), Offset(width + (right - left), y), durationMillis = 200)
            }
            rule.waitForIdle()
        }
        assertEquals("a swipe filed or deleted a photo", 0, calls.total)
        // The gestures reached the layout: a tap afterwards still lands.
        rule.onNodeWithTag("delete_button_compact").performClick()
        rule.waitForIdle()
        assertEquals(1, calls.deletes)
    }

    // ── F-FD-004 through the real screen and ViewModel ─────────────────────

    private fun hostScreen(seen: Boolean): Pair<FitSelectorSettings, () -> Unit> {
        Dispatchers.setMain(dispatcher)
        val settings = FitSelectorSettings(fullscreenHintSeen = seen)
        val vm = fitSelectorViewModel(ApplicationProvider.getApplicationContext(), appScope, settings, photos)
        viewModel = vm
        var generation by mutableIntStateOf(0)
        val cell = FitCell(360, 720, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme {
                // A new generation is the screen composed afresh: the app reopened onto it.
                key(generation) { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm) }
            }
        }
        rule.waitUntil(5_000) { rule.tagExists("copy_button_compact") }
        return settings to { generation++; rule.waitForIdle() }
    }

    @Test
    fun `F-FD-004 a seen gesture hint is not shown again`() {
        val (_, reopen) = hostScreen(seen = true)
        assertTrue("the compact layout is up", rule.tagExists("delete_button_compact"))
        assertFalse("hint shown although seen", rule.tagExists("gesture_tutorial_overlay"))
        reopen()
        assertFalse("hint shown on return although seen", rule.tagExists("gesture_tutorial_overlay"))
    }

    @Test
    fun `F-FD-004 the gesture hint shows once, its dismissal is stored, and it does not return`() {
        val (settings, reopen) = hostScreen(seen = false)
        assertTrue("first visit shows the hint", rule.tagExists("gesture_tutorial_overlay"))
        rule.onNodeWithTag("gesture_tutorial_overlay").performClick()
        rule.waitForIdle()
        coVerify { settings.repository.setHasSeenFullscreenGestureHint(true) }
        rule.waitForIdle()
        assertFalse("dismissed hint still drawn", rule.tagExists("gesture_tutorial_overlay"))
        reopen()
        assertFalse("the hint came back after it was seen", rule.tagExists("gesture_tutorial_overlay"))
    }
}
