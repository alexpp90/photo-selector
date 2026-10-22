package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX-01: Logo button must satisfy the 48 dp minimum touch target requirement.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PhoneModeTopBarTouchTargetTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun logoButtonHasAtLeast48dpTouchTarget() {
        composeTestRule.setContent {
            PhotoTokTheme {
                Box(Modifier.size(360.dp, 80.dp)) {
                    PhoneModeTopBar(
                        showControls = true,
                        showDoneWithCard = false,
                        guideOpen = false,
                        exifOverlayOn = false,
                        onToggleExif = {},
                        onDoneWithCard = {},
                        onReload = {},
                        onHelp = {},
                        onSettings = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val density = composeTestRule.density.density
        val logoNode = composeTestRule.onAllNodes(hasTestTag("top_bar_logo"), useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val bounds = logoNode.boundsInRoot
        assertTrue("Logo width (${bounds.width}) must be at least 48dp", bounds.width >= 48f * density - 0.5f)
        assertTrue("Logo height (${bounds.height}) must be at least 48dp", bounds.height >= 48f * density - 0.5f)
    }
}
