package com.photoselectortoolbox.ui.selector

import android.view.KeyEvent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * A 100 % check without touching the screen (backlog 2026-11-11, `docs/shared/KEYBOARD.md`):
 * in fullscreen `Space` toggles fit ↔ 100 %, the wheel zooms at the pointer, `Shift`+arrows pan
 * 20 % of the window while zoomed, `→` steps and keeps zoom and pan, and `Esc` leaves the zoom
 * before the viewer. Keys go through the dialog's own window, as a keyboard's do.
 */
@Requirement("REQ-AND-INPUT.03", "REQ-AND-INPUT.02")
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class FullscreenZoomTest {

    @get:Rule
    val rule = createComposeRule()

    private val photos = (1..3).map { n ->
        ImageItem(
            uri = "content://test/folder/p$n.jpg", fileName = "p$n.jpg", fileSize = 1, lastModified = 1,
            mimeType = "image/jpeg", imageWidth = 6000, imageHeight = 4000,
        )
    }
    private val zoom = PhotoZoom()
    private var dismissed = 0
    private var page = 0

    @Before
    fun setUp() = installNoImageLoader(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = removeNoImageLoader()

    private fun open() {
        rule.setContent {
            FullscreenViewer(
                images = photos,
                initialIndex = 0,
                onDismiss = { dismissed++ },
                onDelete = {},
                onMoveToSelection = {},
                onCopyToSelection = {},
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                onPageSelected = { page = it },
                fullscreenButtonsEnabled = true,
                filingAction = FilingAction.COPY,
                infoPinned = false,
                onToggleInfoPinned = {},
                snackbarMessage = null,
                snackbarSequence = 0L,
                snackbarLifetime = SnackbarLifetime.NOTICE,
                onUndo = null,
                onDismissSnackbar = {},
                zoom = zoom,
            )
        }
        rule.waitForIdle()
    }

    private fun press(keyCode: Int, meta: Int = 0) {
        val decor = checkNotNull(ShadowDialog.getLatestDialog()) { "the viewer opened no dialog" }.window!!.decorView
        rule.runOnUiThread {
            decor.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            decor.dispatchKeyEvent(KeyEvent(0L, 10L, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        rule.waitForIdle()
    }

    private fun pill(): String? = rule.onAllNodesWithTag("zoom_pill", useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `Space toggles fit and 100 percent, and the pill says so`() {
        open()
        assertEquals(1f, zoom.scale)

        press(KeyEvent.KEYCODE_SPACE)
        assertTrue(zoom.isZoomed)
        assertEquals(zoom.oneToOne, zoom.scale)
        assertEquals("Zoom 100 percent", pill())

        press(KeyEvent.KEYCODE_SPACE)
        assertEquals(1f, zoom.scale)
        assertEquals(null, pill())
    }

    @Test
    fun `at 100 percent Shift+arrows pan and the arrow steps keeping zoom and pan`() {
        open()
        press(KeyEvent.KEYCODE_SPACE)
        val scale = zoom.scale

        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        val panned = zoom.offset
        assertEquals("Shift+→ shows 20 % more to the right", -zoom.viewport.width * PhotoZoom.PAN_STEP, panned.x, 0.5f)
        assertEquals(0, page)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.waitUntil(5_000) { page == 1 }
        assertEquals(scale, zoom.scale)
        assertEquals(panned, zoom.offset)
    }

    @Test
    fun `unzoomed, Shift+arrow still steps`() {
        open()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        rule.waitUntil(5_000) { page == 1 }
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun `the wheel zooms in a notch at a time`() {
        open()
        rule.onAllNodesWithTag("fullscreen_image").onFirst().performMouseInput { scroll(-1f) }
        rule.waitForIdle()
        assertEquals(PhotoZoom.WHEEL_STEP, zoom.scale, 0.001f)

        rule.onAllNodesWithTag("fullscreen_image").onFirst().performMouseInput { scroll(1f) }
        rule.waitForIdle()
        assertEquals(1f, zoom.scale, 0.001f)
    }

    @Test
    fun `Esc leaves the zoom first, then the viewer`() {
        open()
        press(KeyEvent.KEYCODE_SPACE)

        press(KeyEvent.KEYCODE_ESCAPE)
        assertFalse(zoom.isZoomed)
        assertEquals(0, dismissed)

        press(KeyEvent.KEYCODE_ESCAPE)
        assertEquals(1, dismissed)
    }
}
