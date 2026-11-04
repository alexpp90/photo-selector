package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.phototok.ui.components.ViewerBottomBar
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The feed snackbar never covers the viewer bottom bar's buttons, also under a 48 dp 3-button
 * navigation bar (lab 2026-10-26: the buttons shrank to 26 dp; review JP-UX-2). The test places the
 * bar and the host as `PhoneModeScreen` does: the bar measured, the host above it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp")
class FeedSnackbarPlacementTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun bounds(tag: String): Rect =
        rule.onAllNodes(hasTestTag(tag)).onFirst().fetchSemanticsNode().boundsInRoot

    @Test
    fun theSnackbarSitsAboveTheBarUnderThreeButtonNavigation() {
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }
        val message = "Moved to Selection1" // ui-text: ignore test fixture
        rule.setContent {
            PhotoTokTheme {
                val hostState = remember { SnackbarHostState() }
                var barHeightPx by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { hostState.showSnackbar(message) }
                Box(Modifier.fillMaxSize()) {
                    ViewerBottomBar(
                        canRevert = true,
                        onRevert = {},
                        onJumpToSelection = {},
                        onShare = {},
                        onGoToLanding = {},
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { barHeightPx = it.height },
                    )
                    FeedSnackbarHost(
                        hostState = hostState,
                        bottomBarHeight = with(LocalDensity.current) { barHeightPx.toDp() },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        // A 48 dp 3-button navigation bar.
        rule.runOnUiThread {
            val navPx = (48 * rule.activity.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navPx))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(message), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()

        val snackbar = rule.onAllNodes(hasText(message), useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        for (tag in listOf("viewer_sources_button", "viewer_selection_button", "viewer_share_button")) {
            val button = bounds(tag)
            assertTrue(
                "$tag (${button.top / density} dp) is covered by the snackbar text ending at ${snackbar.bottom / density} dp",
                snackbar.bottom <= button.top,
            )
            assertTrue("$tag is ${button.height / density} dp tall", button.height / density >= 48f - 0.5f)
        }
        val host = bounds("feed_snackbar_host")
        val bar = bounds("viewer_bottom_bar")
        assertTrue("host ends at ${host.bottom / density} dp, bar starts at ${bar.top / density} dp", host.bottom <= bar.top + 0.5f)
        // The inset reached the bar (otherwise this test would not model 3-button navigation).
        val rootHeight = rule.onRoot().fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "bar ends ${(rootHeight - bar.bottom) / density} dp above the screen bottom",
            (rootHeight - bar.bottom) / density >= 48f - 0.5f,
        )
    }
}
