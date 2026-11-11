package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The portrait camera-info card shows the whole lens name at font scale 2.0: it wraps rather than
 * ending in an ellipsis (REQ-TOK-UX.05). Usability lab 2026-11-10, Pixel 4a at 2.0: the lens line
 * read "PSTLab 35mm F1…." and lost its aperture. The file name wraps too
 * (`ExifCardFileNameAndDateTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ExifCardLensWrapTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun showBottomHud(lens: String, fontScale: Float) {
        rule.setContent {
            PhotoTokTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
                    // The lab photo: the counter shares the row and takes its share of the width.
                    ViewerBottomHudRow(
                        exif = ExifData(iso = 100, shutterSpeed = 1.0 / 640, aperture = 8.0, focalLength = 35.0, lens = lens),
                        fileName = "IMG_20260612_101800.jpg", // ui-text: ignore test fixture
                        showPageCounter = true,
                        pageIndex = 0,
                        totalCount = 17,
                        isDiscovering = false,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun lensLayout(lens: String): TextLayoutResult {
        val node = rule.onAllNodes(
            hasText(lens) and hasAnyAncestor(hasTestTag("viewer.info")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().single()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        assertEquals("one text layout for the lens line", 1, layouts.size)
        return layouts.single()
    }

    private fun assertWholeLensShown(lens: String) {
        val layout = lensLayout(lens)
        val last = layout.lineCount - 1
        println("lens \"$lens\": ${layout.lineCount} line(s), ellipsis ${layout.isLineEllipsized(last)}")
        // An ellipsised or clipped last line is what the lab saw. didOverflowWidth is not used:
        // a single-line Ellipsis paragraph is laid out at the full max width, wider than the text.
        assertFalse("the lens line \"$lens\" ends in an ellipsis", layout.isLineEllipsized(last))
        assertFalse("the lens line \"$lens\" is cut off at the bottom", layout.didOverflowHeight)
    }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun labLens_fontScale2_isShownWhole() {
        val lens = "PSTLab 35mm F1.4 GM" // ui-text: ignore test fixture
        showBottomHud(lens, fontScale = 2f)
        assertWholeLensShown(lens)
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-440dpi")
    @Requirement("REQ-TOK-UX.05")
    fun longZoomLens_germanFontScale2_isShownWhole() {
        val lens = "FE 24-70mm F2.8 GM II" // ui-text: ignore test fixture
        showBottomHud(lens, fontScale = 2f)
        assertWholeLensShown(lens)
    }

    @Test
    @Requirement("REQ-TOK-UX.05")
    fun labLens_fontScale1_staysOnOneLine() {
        val lens = "PSTLab 35mm F1.4 GM" // ui-text: ignore test fixture
        showBottomHud(lens, fontScale = 1f)
        assertWholeLensShown(lens)
        assertEquals(1, lensLayout(lens).lineCount)
    }
}
