package com.photoselectortoolbox.ui.selector

import android.view.KeyEvent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * In the fullscreen viewer the pan stops at the photo's edge, through the wiring the viewer ships:
 * the page reports the photo's fitted size to the shared [PhotoZoom], so twenty `Shift`+→ presses
 * at 100 % end with the photo's right edge on the window's (review 2026-11-09, FD-UI-4), and the
 * navigator's outline is the visible part of the photo, not a fixed box.
 */
@Requirement("REQ-AND-INPUT.03")
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class FullscreenPanClampTest {

    @get:Rule
    val rule = createComposeRule()

    private val photos = (1..2).map { n ->
        ImageItem(
            uri = "content://test/folder/p$n.jpg", fileName = "p$n.jpg", fileSize = 1, lastModified = 1,
            mimeType = "image/jpeg", imageWidth = 6000, imageHeight = 4000,
        )
    }
    private val zoom = PhotoZoom()

    @Before
    fun setUp() = installNoImageLoader(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = removeNoImageLoader()

    private fun open() {
        rule.setContent {
            FullscreenViewer(
                images = photos,
                initialIndex = 0,
                onDismiss = {},
                onDelete = {},
                onMoveToSelection = {},
                onCopyToSelection = {},
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                onPageSelected = {},
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

    @Test
    fun `twenty Shift+right presses at 100 percent end at the photo's right edge`() {
        open()
        press(KeyEvent.KEYCODE_SPACE)
        assertTrue(zoom.isZoomed)
        repeat(20) { press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON) }

        val viewport = zoom.viewport
        // The 3:2 photo fitted into the window, then scaled: its half-overhang is the bound.
        val fittedWidth = minOf(viewport.width.toFloat(), viewport.height * 1.5f)
        val bound = (fittedWidth * zoom.scale - viewport.width) / 2f
        assertTrue("there is something to pan", bound > 0f)
        assertEquals("the pan stops where the photo's edge meets the window's", -bound, zoom.offset.x, 1f)
        assertEquals(1f, zoom.visibleRegion().right, 0.01f)
    }

    @Test
    fun `the navigator outline is the visible part of the photo`() {
        open()
        press(KeyEvent.KEYCODE_SPACE)
        val navigator = rule.onNodeWithTag("zoom_navigator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val centred = rule.onNodeWithTag("zoom_navigator_outline", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("at 100 % the outline is smaller than the thumbnail", centred.width < navigator.width * 0.6f)
        assertEquals("centred", navigator.center.x, centred.center.x, 1.5f)

        repeat(20) { press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON) }
        val right = rule.onNodeWithTag("zoom_navigator_outline", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("panned right, the outline moves right", right.center.x > centred.center.x + 5f)
        assertTrue("and stays on the thumbnail", right.right <= navigator.right + 1f)
    }
}
