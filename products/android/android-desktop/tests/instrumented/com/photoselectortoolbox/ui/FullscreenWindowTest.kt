package com.photoselectortoolbox.ui

// Explicit imports only; see SelectorScreenTest for why the wildcard is banned.
import android.os.Build
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.ui.selector.FullscreenViewer
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fullscreen viewer's window covers the whole app window (REQ-AND-THEME.04).
 *
 * The viewer is a `Dialog`, a window of its own, and whether that window really
 * reaches every edge is a property of the platform and the theme, not of the
 * composable tree: Robolectric cannot answer it. So the root's position and size
 * *on screen* are compared with the activity's window bounds, with the info
 * overlay visible and pinned.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
class FullscreenWindowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val image = ImageItem(
        uri = "content://test/folder/a.jpg",
        fileName = "a.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1080,
        exifData = ExifData(shutterSpeed = 0.008, aperture = 4.0, iso = 100, focalLength = 85.0, lens = "FE 85mm F1.4 GM"),
    )

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun fullscreenWindow_coversTheActivityWindow_withTheInfoOverlayPinned() {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                FullscreenViewer(
                    images = listOf(image),
                    initialIndex = 0,
                    onDismiss = {},
                    onDelete = {},
                    onMoveToSelection = {},
                    onCopyToSelection = {},
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                    infoPinned = true,
                )
            }
        }
        rule.waitForIdle()

        val window = rule.activity.windowManager.currentWindowMetrics.bounds
        listOf("fullscreen_root", "fullscreen_pager", "fullscreen_info_overlay").forEach { tag ->
            check(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.width > 0) { tag }
        }
        val root = rule.onNodeWithTag("fullscreen_root", useUnmergedTree = true).fetchSemanticsNode()
        val pager = rule.onNodeWithTag("fullscreen_pager", useUnmergedTree = true).fetchSemanticsNode()

        // Covers the window edge to edge. A pixel of rounding overshoot is the
        // platform's (measured on the Pixel Tablet: 2560 x 1601 for a 2560 x 1600
        // window); a band that is missing would be tens of pixels (a status bar,
        // a cutout, the 64 dp the old top bar reserved).
        val tolerance = 2f
        val where = "root ${root.positionOnScreen} ${root.size} vs window $window"
        assertEquals("root left: $where", window.left.toFloat(), root.positionOnScreen.x, tolerance)
        assertEquals("root top: $where", window.top.toFloat(), root.positionOnScreen.y, tolerance)
        assertEquals("root width: $where", window.width().toFloat(), root.size.width.toFloat(), tolerance)
        assertEquals("root height: $where", window.height().toFloat(), root.size.height.toFloat(), tolerance)
        assertEquals("pager is the whole root (width)", root.size.width, pager.size.width)
        assertEquals("pager is the whole root (height)", root.size.height, pager.size.height)
    }

    /**
     * The viewer is a Dialog with its own window and its own insets controller:
     * hiding the bars on the activity's window (what it used to do alone) left the
     * status bar and the launcher taskbar over the photograph. Unit tests cannot
     * see this (Robolectric reports no insets), so it is asserted here.
     */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun fullscreenWindow_hidesTheSystemBars_inItsOwnWindow() {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                FullscreenViewer(
                    images = listOf(image),
                    initialIndex = 0,
                    onDismiss = {},
                    onDelete = {},
                    onMoveToSelection = {},
                    onCopyToSelection = {},
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                    infoPinned = true,
                )
            }
        }
        rule.waitForIdle()

        val activityDecor = rule.activity.window.decorView
        val dialogDecor = WindowInspector.getGlobalWindowViews()
            .map { it.rootView }
            .distinct()
            .first { it !== activityDecor && it.isAttachedToWindow }
        rule.runOnIdle {
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(dialogDecor)) { "dialog has no insets" }
            assertFalse("status bar visible over the photograph", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            assertFalse("navigation bar / taskbar visible over the photograph", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
        }
    }
}
