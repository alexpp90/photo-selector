package com.photoselectortoolbox.ui.selector

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fullscreen top bar at the narrowest window the product supports (600 dp):
 * name, position and the exposure line each on one line, the exposure line
 * whole. `GraphicsMode.NATIVE`, because under LEGACY Robolectric measures text
 * at about one pixel a character and every width assertion passes or fails for
 * no reason.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w600dp-h900dp-xhdpi")
class FullscreenTopBarWidthTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(name: String, lens: String = "FE 85mm F1.4 GM") = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1080,
        exifData = ExifData(
            shutterSpeed = 0.0000625, // the longest realistic line: 1/16000s
            aperture = 22.0,
            iso = 102400,
            focalLength = 600.0,
            focalLength35mm = 600.0,
            lens = lens,
            isFallback = false,
        ),
    )

    private fun open(widthDp: Int, vararg images: ImageItem) {
        rule.setContent {
            FullscreenViewer(
                images = images.toList(),
                initialIndex = 0,
                onDismiss = {},
                onDelete = {},
                onMoveToSelection = {},
                onCopyToSelection = {},
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(widthDp.dp, 900.dp)),
            )
        }
        rule.waitForIdle()
    }

    private fun layoutOf(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.runOnUiThread {
            rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        }
        return results.first()
    }

    @Test
    fun `at 600 dp name, position and exposure stay on one line and the exposure is not cut`() {
        open(600, photo("IMG_20260101_123456_HDR.jpg"))

        listOf("fullscreen_file_name", "fullscreen_position", "fullscreen_exif").forEach {
            assertEquals("$it wraps", 1, layoutOf(it).lineCount)
        }
        assertNotCut("fullscreen_exif")
        assertNotCut("fullscreen_position")
        assertEquals(
            "no lens below the Expanded width",
            0,
            rule.onAllNodesWithTag("fullscreen_lens", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        // A long name gives way (ellipsis) — it does not take the exposure with it,
        // and it is not squeezed to nothing either.
        val nameWidth = rule.onNodeWithTag("fullscreen_file_name", useUnmergedTree = true)
            .fetchSemanticsNode().size.width
        assertTrue("name is $nameWidth px wide", nameWidth >= with(rule.density) { 96.dp.roundToPx() })
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `at the Expanded width the lens joins the exposure line and nothing wraps`() {
        open(1280, photo("IMG_20260101_123456_HDR.jpg"))

        assertEquals(
            1,
            rule.onAllNodesWithTag("fullscreen_lens", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        listOf("fullscreen_file_name", "fullscreen_position", "fullscreen_exif", "fullscreen_lens").forEach {
            assertEquals("$it wraps", 1, layoutOf(it).lineCount)
        }
        assertNotCut("fullscreen_exif")
        assertNotCut("fullscreen_lens")
    }

    /**
     * Whether the node is as wide as its text wants to be. `hasVisualOverflow` is
     * not usable for this: with `softWrap = false` it reports true for text that
     * fits.
     */
    private fun assertNotCut(tag: String) {
        val wanted = kotlin.math.ceil(layoutOf(tag).multiParagraph.maxIntrinsicWidth).toInt()
        val actual = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().size.width
        assertTrue("$tag is $actual px wide, its text wants $wanted", actual >= wanted)
    }
}
