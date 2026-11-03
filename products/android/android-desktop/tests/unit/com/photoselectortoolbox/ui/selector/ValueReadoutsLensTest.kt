package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.scoring.ScoreMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lens joins the exposure on the current frame's readout and on the
 * neighbour overlay (REQ-AND-LAYOUT.13), and the extra line stays inside the
 * height the 25 % coverage rule (AD-16) prices the overlay at.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class ValueReadoutsLensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(lens: String) = ImageItem(
        uri = "content://test/a.jpg",
        fileName = "IMG_0001.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1080,
        exifData = ExifData(
            shutterSpeed = 1.0 / 250,
            aperture = 2.8,
            iso = 400,
            focalLength = 35.0,
            lens = lens,
        ),
        scanResult = ScanResult(
            filePath = "content://test/a.jpg",
            sharpnessScore = 64.0,
            noiseLevel = 1.2,
            highlightClipping = 0.4,
            shadowClipping = 0.2,
        ),
    )

    private fun readout(image: ImageItem) = rule.setContent {
        Box(Modifier.width(388.dp)) {
            CurrentFrameReadout(image = image, bestMetrics = emptySet())
        }
    }

    private fun overlay(image: ImageItem, overlaid: Boolean = true) = rule.setContent {
        NeighbourValueOverlay(
            image = image,
            position = 12,
            total = 842,
            bestMetrics = ScoreMetric.entries.toSet(),
            isOverlaid = overlaid,
        )
    }

    private fun count(tag: String) =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun text(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the current frame's readout shows a known lens under the exposure`() {
        readout(photo("FE 85mm F1.4 GM"))
        assertEquals("FE 85mm F1.4 GM", text("readout_lens"))
        val exposure = rule.onNodeWithTag("exposure_line", useUnmergedTree = true).fetchSemanticsNode()
        val lens = rule.onNodeWithTag("readout_lens", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(lens.positionInRoot.y >= exposure.positionInRoot.y + exposure.size.height)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the current frame's readout has no lens line for an unknown or blank lens`() {
        readout(photo(ExifFormatter.UNKNOWN))
        assertEquals(0, count("readout_lens"))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the neighbour overlay shows a known lens, with the full name in its description`() {
        val lens = "Sigma 100-400mm F5-6.3 DG DN OS | Contemporary 020"
        overlay(photo(lens))
        assertEquals(1, count("neighbour_lens"))
        val node = rule.onNodeWithTag("neighbour_lens", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Lens $lens", node.config.getOrNull(SemanticsProperties.ContentDescription)?.first())
        // One line, ellipsised: it never grows the overlay.
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.runOnUiThread {
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)
                ?.action?.invoke(layout)
        }
        assertEquals(1, layout.first().lineCount)
        assertTrue(layout.first().hasVisualOverflow)
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the neighbour overlay has no lens line for an unknown lens`() {
        overlay(photo(ExifFormatter.UNKNOWN))
        assertEquals(0, count("neighbour_lens"))
    }

    @Test
    @Requirement("REQ-AND-LAYOUT.13")
    fun `the neighbour overlay with a lens stays within the height AD-16 prices it at`() {
        // Worst case: every metric row, the position, two exposure lines and a lens.
        overlay(photo("FE 85mm F1.4 GM"))
        val height = rule.onNodeWithTag("neighbour_overlay", useUnmergedTree = true)
            .fetchSemanticsNode().size.height
        val priced = with(rule.density) { FrameGeometry.OverlayEstimatedHeight.roundToPx() }
        assertTrue("overlay is $height px tall, the coverage rule prices $priced px", height <= priced)
    }
}
