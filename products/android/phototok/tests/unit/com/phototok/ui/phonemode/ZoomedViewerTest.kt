package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import com.photoselector.core.Requirement
import com.phototok.domain.FirstRunHint
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Zoom and paging must not fight (REQ-TOK-GESTURE.02). F-TOK-003 (fixed #491, guard `none` until
 * now): while zoomed, the idle swipe peeks at the screen edges showed as thin stripes over the
 * zoomed photo. Zoomed, the peeks are gone and neither a horizontal swipe nor a vertical one acts;
 * a second double tap brings the peeks back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class ZoomedViewerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val hints = mutableListOf<FirstRunHint>()
    private var kept = 0
    private var deleted = 0
    /** Pages the viewer reported settling on; page 0 is the one on screen. */
    private val navigations = mutableListOf<Int>()

    private val screen: Rect
        get() = RuntimeEnvironment.getApplication().resources.displayMetrics.let {
            Rect(0f, 0f, it.widthPixels.toFloat(), it.heightPixels.toFloat())
        }

    /** Peeks drawn on screen (the pager composes its neighbours' pages off screen too). */
    private fun peeksOnScreen(): Int = listOf("viewer.peek_right", "viewer.peek_left").sumOf { tag ->
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            .count { it.boundsInWindow.width > 0.5f && it.boundsInWindow.overlaps(screen) }
    }

    private fun show() {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = PhoneScreenHarness.PHOTOS,
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = { navigations += it },
                    onAddToCollection = { kept++ },
                    onRequestDelete = { deleted++ },
                    onFirstRunHint = { hints += it },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun doubleTap() {
        val before = hints.count { it == FirstRunHint.DOUBLE_TAP_ZOOM }
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { doubleClick(center) }
        // The double tap is processed when it reports its first-run hint.
        rule.waitUntil(5_000) { hints.count { it == FirstRunHint.DOUBLE_TAP_ZOOM } > before }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.02")
    fun zoomedPhotoShowsNoEdgePeeksAndIgnoresSwipes() {
        show()
        assertTrue("the idle edge peeks show on a fresh feed", peeksOnScreen() > 0)

        doubleTap() // zoom in
        rule.waitUntil(5_000) { peeksOnScreen() == 0 }
        assertEquals("no edge peeks over a zoomed photo (F-TOK-003)", 0, peeksOnScreen())

        val photo = rule.onAllNodes(hasTestTag("viewer.photo")).onFirst()
        photo.performTouchInput { swipeLeft() }
        photo.performTouchInput { swipeRight() }
        photo.performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("a zoomed swipe keeps nothing", 0, kept)
        assertEquals("a zoomed swipe deletes nothing", 0, deleted)
        assertTrue("a zoomed swipe does not page: $navigations", navigations.all { it == 0 })

        doubleTap() // back to fit
        rule.waitUntil(5_000) { peeksOnScreen() > 0 }
    }
}
