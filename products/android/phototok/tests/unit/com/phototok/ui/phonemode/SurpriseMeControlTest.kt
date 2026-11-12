package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.ui.components.ViewerBottomBar
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Casual's "surprise me" in the top bar ([USER 2026-11-12] decision #14, REQ-TOK-UX.02): an
 * icon-only 48 dp toggle between Reload and Help, clear of its neighbours, spoken with its state,
 * and labelled by the controls guide where it really is (measured anchors, as on a device), in
 * English and German at font scale 1.0 and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
@Requirement("REQ-TOK-FEATURE.20", "REQ-TOK-UX.02")
class SurpriseMeControlTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val controls = mapOf(
        GuideAnchor.RELOAD to "reload_folder_button",
        GuideAnchor.SURPRISE to "surprise_me_button",
        GuideAnchor.HELP to "help_button",
        GuideAnchor.SETTINGS to "settings_button",
    )

    private var toggles = 0

    private fun host(fontScale: Float, guide: Boolean = true) {
        rule.setContent {
            PhotoTokTheme {
                val anchors = remember { ControlsGuideAnchors() }
                var on by remember { mutableStateOf(false) }
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.fillMaxSize()) {
                        if (guide) GestureTutorialOverlay(visible = true, onDismiss = {}, anchors = anchors, cameraInfo = false)
                        PhoneModeTopBar(
                            showControls = true,
                            showDoneWithCard = false,
                            guideOpen = guide,
                            exifOverlayOn = false,
                            onToggleExif = {},
                            onDoneWithCard = {},
                            onReload = {},
                            onHelp = {},
                            onSettings = {},
                            modifier = Modifier.align(Alignment.TopCenter),
                            anchors = anchors,
                            cameraInfoEnabled = false,
                            surpriseMe = on,
                            onSurpriseMe = { toggles++; on = !on },
                        )
                        ViewerBottomBar(
                            canRevert = true,
                            onRevert = {},
                            onJumpToSelection = {},
                            onShare = {},
                            onGoToLanding = {},
                            modifier = Modifier.align(Alignment.BottomCenter),
                            anchors = anchors,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot

    private fun exists(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun checkGuide(fontScale: Float) {
        host(fontScale)
        val d = rule.density.density
        val button = bounds("surprise_me_button")
        assertTrue("48 dp wide: ${button.width / d}", button.width / d >= 47.5f)
        assertTrue("48 dp tall: ${button.height / d}", button.height / d >= 47.5f)
        val reload = bounds("reload_folder_button")
        val help = bounds("help_button")
        assertFalse("clear of Reload", button.overlaps(reload))
        assertFalse("clear of Help", button.overlaps(help))
        assertTrue("between Reload and Help", reload.center.x < button.center.x && button.center.x < help.center.x)

        assertTrue("the guide labels it", exists("guide_callout_SURPRISE"))
        val topBar = bounds("phone_top_bar")
        val callouts = controls.keys.filter { exists("guide_callout_${it.name}") }.associateWith { bounds("guide_callout_${it.name}") }
        val c = callouts.getValue(GuideAnchor.SURPRISE)
        assertTrue("callout below the bar", c.top >= topBar.bottom - 1f)
        assertEquals("centred on its icon", button.center.x, c.center.x, 12f * d)
        assertTrue("on screen", c.left >= -1f && c.right <= rule.activity.window.decorView.width + 1f)
        val list = callouts.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            assertFalse("${list[i].key} and ${list[j].key} overlap", list[i].value.overlaps(list[j].value))
        }
        for ((id, r) in callouts) controls.values.forEach { tag ->
            assertFalse("$id callout covers $tag", r.overlaps(bounds(tag)))
        }
    }

    @Test
    @Config(qualifiers = "en-w393dp-h851dp-xxhdpi")
    fun english393_fontScale1() = checkGuide(1f)

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-xxhdpi")
    fun german393_fontScale2() = checkGuide(2f)

    @Test
    @Config(qualifiers = "de-w320dp-h480dp-xhdpi")
    fun german320_fontScale2() = checkGuide(2f)

    @Test
    @Config(qualifiers = "en-w320dp-h480dp-xhdpi")
    fun english320_fontScale2() = checkGuide(2f)

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-xxhdpi")
    fun spokenWithItsState_andTheTapToggles() {
        host(1f, guide = false)
        val ctx = rule.activity
        fun node() = rule.onAllNodes(hasTestTag("surprise_me_button"), useUnmergedTree = true).onFirst()
        val cfg = node().fetchSemanticsNode().config
        assertEquals(listOf(ctx.getString(R.string.cd_surprise_me)), cfg[SemanticsProperties.ContentDescription])
        assertEquals(ctx.getString(R.string.cd_state_off), cfg[SemanticsProperties.StateDescription])
        assertEquals("Überrasch mich", ctx.getString(R.string.cd_surprise_me))
        node().performClick()
        rule.waitForIdle()
        assertEquals(1, toggles)
        assertEquals(ctx.getString(R.string.cd_state_on), node().fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
}
