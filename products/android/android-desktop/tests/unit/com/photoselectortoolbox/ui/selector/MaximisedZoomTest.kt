package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.ui.fit.tagExists
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorFrame
import com.photoselectortoolbox.viewmodel.SelectorViewModel
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `Space` on a maximised frame toggles fit ↔ 100 % (backlog 2026-11-11, `docs/shared/KEYBOARD.md`);
 * `Shift`+arrows pan while zoomed, `→` steps keeping zoom and pan, and `Esc` leaves the zoom
 * before the maximised frame. Through the real screen, view model and key handler.
 */
@Requirement("REQ-AND-LAYOUT.06", "REQ-AND-INPUT.02")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class MaximisedZoomTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel
    private val zoom = PhotoZoom()

    @Before
    fun setUp() {
        installNoImageLoader(ApplicationProvider.getApplicationContext())
        Dispatchers.setMain(dispatcher)
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            photos = (1..3).map { fitPhoto("P$it.JPG", 10_000L - it, width = 6000, height = 4000) },
        )
        val cell = FitCell(1280, 800, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme {
                SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel, maximisedZoom = zoom)
            }
        }
        rule.waitUntil(5_000) { rule.tagExists("column_current") }
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun press(key: Key, shift: Boolean = false) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput {
            if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
        }
        rule.waitForIdle()
    }

    private fun current() = viewModel.uiState.value.currentImage?.fileName

    @Test
    fun `Space toggles fit and 100 percent on the maximised frame, and does nothing in three-up`() {
        press(Key.Spacebar)
        assertFalse("three-up has no zoom", zoom.isZoomed)

        press(Key.Two)
        assertEquals(SelectorFrame.CURRENT, viewModel.uiState.value.maximisedFrame)
        press(Key.Spacebar)
        assertTrue(zoom.isZoomed)
        assertEquals(zoom.oneToOne, zoom.scale)

        press(Key.Spacebar)
        assertFalse(zoom.isZoomed)
    }

    @Test
    fun `zoomed, Shift+arrow pans and the arrow steps keeping zoom and pan`() {
        press(Key.Two)
        press(Key.Spacebar)
        val scale = zoom.scale
        assertEquals("P1.JPG", current())

        press(Key.DirectionRight, shift = true)
        assertEquals("Shift+→ pans, it does not step", "P1.JPG", current())
        val panned = zoom.offset
        assertEquals(-zoom.viewport.width * PhotoZoom.PAN_STEP, panned.x, 0.5f)

        press(Key.DirectionRight)
        assertEquals("P2.JPG", current())
        assertEquals(scale, zoom.scale)
        assertEquals(panned, zoom.offset)
    }

    @Test
    fun `zoomed, Shift+arrow pans stop at the frame's edge (FD-UI-4)`() {
        press(Key.Two)
        press(Key.Spacebar)
        repeat(20) { press(Key.DirectionRight, shift = true) }
        // The maximised frame is sized to the photo, so the photo's overhang is the frame's.
        val bound = (zoom.viewport.width * zoom.scale - zoom.viewport.width) / 2f
        assertTrue("there is something to pan", bound > 0f)
        assertEquals(-bound, zoom.offset.x, 1f)
        assertEquals("P1.JPG", current())
    }

    @Test
    fun `Esc leaves the zoom, then the maximised frame`() {
        press(Key.Two)
        press(Key.Spacebar)

        press(Key.Escape)
        assertFalse(zoom.isZoomed)
        assertEquals(SelectorFrame.CURRENT, viewModel.uiState.value.maximisedFrame)

        press(Key.Escape)
        assertEquals(null, viewModel.uiState.value.maximisedFrame)
    }
}
