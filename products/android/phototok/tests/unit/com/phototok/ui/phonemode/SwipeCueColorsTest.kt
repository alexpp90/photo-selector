package com.phototok.ui.phonemode

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import com.phototok.data.model.ImageItem
import com.phototok.ui.theme.DarkColorScheme
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.ui.theme.SuccessGreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The swipe indicators, their labels and the idle edge hints are readable on any photo and use
 * the Jackpix palette (review 2026-11-03, JP-UX-5: white on SuccessGreen was 2.3:1); the edge
 * hints stand still when the user removed animations (JP-UX-3).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SwipeCueColorsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun indicatorGlyphsAndLabelsMeet4_5To1OnAnyPhoto() {
        for (leftIsDelete in listOf(true, false)) {
            val cues = swipeCueColors(DarkColorScheme, leftIsDelete)
            val pairs = mutableMapOf(
                "right glyph" to (cues.rightContent to cues.rightContainer),
                "left glyph (delete=$leftIsDelete)" to (cues.leftContent to cues.leftContainer),
            )
            for ((photo, name) in listOf(Color.White to "white", Color.Black to "black")) {
                val scrim = cues.cueScrim.compositeOver(photo)
                pairs["right label on $name photo"] = cues.rightAccent to scrim
                pairs["left label on $name photo (delete=$leftIsDelete)"] = cues.leftAccent to scrim
            }
            pairs.forEach { (n, p) ->
                val ratio = contrast(p.first, p.second)
                assertTrue("$n contrast %.2f < 4.5".format(ratio), ratio >= 4.5)
            }
            assertNotEquals("the swipe-right cue uses the lime, not SuccessGreen", SuccessGreen, cues.rightContainer)
            assertEquals(DarkColorScheme.primary, cues.rightContainer)
        }
    }

    private val photo = ImageItem(
        uri = "content://photos/a.jpg",
        fileName = "a.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
    )

    /** Unclipped x of the right-swipe hint, now and 700 ms later, with the test clock driven by hand. */
    private fun peekPositions(): Pair<Float, Float> {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(100)
        val first = rule.onAllNodes(hasTestTag("viewer.peek_right")).onFirst().fetchSemanticsNode().positionInRoot.x
        rule.mainClock.advanceTimeBy(700)
        val later = rule.onAllNodes(hasTestTag("viewer.peek_right")).onFirst().fetchSemanticsNode().positionInRoot.x
        return first to later
    }

    @Test
    fun edgeHintsHideUnderTheControlsGuide() {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    topBadgesSuppressed = true,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            "the guide's own callouts sit where the hints are",
            rule.onAllNodes(hasTestTag("viewer.peek_right")).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(hasTestTag("viewer.peek_left")).fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun setAnimatorScale(scale: Float) {
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    @Test
    fun edgeHintsStandStillWithAnimationsRemoved() {
        setAnimatorScale(0f)
        val (first, later) = peekPositions()
        assertEquals("hint moved with animations off", first, later, 0.01f)
    }

    @Test
    fun edgeHintsMoveWithAnimationsOn() {
        setAnimatorScale(1f)
        val (first, later) = peekPositions()
        assertNotEquals("the hint is expected to bob with animations on", first, later)
    }
}
