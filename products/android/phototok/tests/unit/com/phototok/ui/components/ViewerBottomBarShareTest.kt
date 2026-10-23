package com.phototok.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.photoselector.core.Requirement
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** REQ-TOK-FEATURE.06 / .14: the portrait bar has an icon-only Share control between Selection and Revert. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ViewerBottomBarShareTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun bounds(tag: String) =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot

    private fun host(onShare: () -> Unit) {
        composeTestRule.setContent {
            PhotoTokTheme {
                Box(Modifier.fillMaxSize()) {
                    ViewerBottomBar(
                        canRevert = false,
                        onRevert = {},
                        onJumpToSelection = {},
                        onShare = onShare,
                        onGoToLanding = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.14")
    fun shareButtonInvokesTheShareCallback() {
        var shared = 0
        host { shared++ }

        composeTestRule.onAllNodes(hasTestTag("viewer_share_button"), useUnmergedTree = true).onFirst().performClick()

        assertEquals(1, shared)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.06")
    fun barOrderIsSourcesSelectionShareRevertAndShareIs72x48dp() {
        host {}
        val density = composeTestRule.density.density

        val xs = listOf("viewer_sources_button", "viewer_selection_button", "viewer_share_button", "viewer_revert_button")
            .map { bounds(it).center.x }
        assertEquals(xs.sorted(), xs)
        val share = bounds("viewer_share_button")
        assertEquals(72f * density, share.width, 1f)
        assertTrue(share.height >= 48f * density - 1f)
    }
}
