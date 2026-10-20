package com.phototok.ui.phonemode

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TOK-11a: all three landing source cards must be reachable without scrolling on small phones.
 * Runs on the JVM (Robolectric) so CI enforces it without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PhoneModeLandingLayoutTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val cards = listOf(
        "landing_phone_camera_card",
        "landing_sd_card_card",
        "landing_custom_folder_card",
    )

    private fun host(widthDp: Int, heightDp: Int, fontScale: Float) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PhotoTokTheme {
                    Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                        PhoneModeLanding(
                            sourceFolderUri = null,
                            sourceFolderName = "",
                            collectionFolderName = "Selection",
                            isLoading = false,
                            onSelectSource = {},
                            onSelectCollection = {},
                            onStart = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun bounds(tag: String) =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().boundsInRoot

    private fun assertCardsFit(widthDp: Int, heightDp: Int, fontScale: Float) {
        host(widthDp, heightDp, fontScale)
        val density = composeTestRule.density.density
        val rootHeight = heightDp * density
        cards.forEach { tag ->
            val r = bounds(tag)
            println("TOK-11a ${widthDp}x$heightDp fs=$fontScale $tag top=${r.top / density}dp " +
                "bottom=${r.bottom / density}dp height=${r.height / density}dp")
            assertTrue("$tag bottom ${r.bottom / density}dp exceeds $heightDp dp " +
                "(fontScale $fontScale)", r.bottom <= rootHeight + 0.5f)
            // A node scrolled/clipped out of the window reports empty bounds: require a real row.
            assertTrue("$tag is not laid out inside the viewport (height ${r.height / density}dp)",
                r.height / density >= 63.5f)
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi") fun cardsFit_360x640_font1() = assertCardsFit(360, 640, 1.0f)
    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi") fun cardsFit_360x640_font1_3() = assertCardsFit(360, 640, 1.3f)
    @Test @Config(qualifiers = "w393dp-h851dp-xhdpi") fun cardsFit_393x851_font1() = assertCardsFit(393, 851, 1.0f)
    @Test @Config(qualifiers = "w393dp-h851dp-xhdpi") fun cardsFit_393x851_font1_3() = assertCardsFit(393, 851, 1.3f)

    @Test
    @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun rowsHaveMinimumTouchHeight() {
        host(393, 851, 1.0f)
        val density = composeTestRule.density.density
        cards.forEach { tag ->
            val h = bounds(tag).height / density
            assertTrue("$tag height $h dp < 64 dp", h >= 63.5f)
        }
        assertEquals(3, cards.size)
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp-xhdpi")
    fun fadeShownOnlyWhenContentCanScroll() {
        host(360, 400, 1.3f)
        composeTestRule.onAllNodes(hasTestTag("landing_scroll_fade"), useUnmergedTree = true)
            .fetchSemanticsNodes().also { assertTrue("fade expected when scrollable", it.isNotEmpty()) }
    }

    @Test
    @Config(qualifiers = "w393dp-h1600dp-xhdpi")
    fun fadeHiddenWhenContentFits() {
        host(393, 1600, 1.0f)
        val nodes = composeTestRule
            .onAllNodes(hasTestTag("landing_scroll_fade"), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("fade must be absent when everything fits", nodes.isEmpty())
    }
}
