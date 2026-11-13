package com.photoselectortoolbox.ui.inspect

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Learn's Compare tab shows both frames of the pair it names (`[USER 2026-11-12]` decision #17,
 * backlog 2026-10-24 "the Learn Compare tab names two frames but shows neither"): the canvas holds
 * the better frame and the other side by side, each named, the guide bar's composition guides are
 * gone on this tab, choosing another scene's card shows that pair, and a folder where no pair
 * differs measurably says so instead of asking for more photos.
 */
@Requirement("REQ-AND-LEARN.03")
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class CompareTwoFramesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun frame(name: String, minute: Double, sharpness: Double) = ImageItem(
        uri = "content://test/$name.jpg",
        fileName = "$name.JPG",
        fileSize = 1000L,
        lastModified = 1_700_000_000_000L + (minute * 60_000).toLong(),
        mimeType = "image/jpeg",
        exifData = ExifData(shutterSpeed = 1.0 / 250.0, aperture = 4.0, iso = 200, focalLength35mm = 50.0),
        scanResult = ScanResult(filePath = "content://test/$name.jpg", sharpnessScore = sharpness),
    )

    private fun host(images: List<ImageItem>, width: Int = 1280, height: Int = 800) {
        rule.setContent {
            InspectScreenContent(
                currentImage = images.first(),
                images = images,
                currentIndex = 0,
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(width.dp, height.dp)),
                onNavigateToSelector = {},
                onSelectImage = {},
                onPrevious = {},
                onNext = {},
                onSetRating = { _, _ -> },
            )
        }
        rule.onNodeWithTag(LearnTab.COMPARE.tag).performClick()
        rule.waitForIdle()
    }

    private fun spoken(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")

    /** Two scenes an hour apart: A (sharp) vs B, then C (sharp) vs D. */
    private val twoScenes = listOf(
        frame("A", 0.0, 100.0), frame("B", 1.0, 40.0),
        frame("C", 60.0, 90.0), frame("D", 61.0, 30.0),
    )

    @Test
    fun `the canvas shows both frames of the pair, each named`() {
        host(twoScenes)
        assertTrue(spoken(BETTER).contains("A.JPG"))
        assertTrue(spoken(OTHER).contains("B.JPG"))
        val better = rule.onNodeWithTag(BETTER, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val other = rule.onNodeWithTag(OTHER, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the frames stand side by side, not on top of each other", better.right <= other.left + 0.5f)
        assertTrue("each frame has room", better.width > 100f && other.width > 100f)
    }

    @Test
    fun `the composition guides are not offered on Compare`() {
        host(twoScenes)
        assertEquals(0, rule.onAllNodesWithTag("toggle_thirds").fetchSemanticsNodes().size)
        rule.onNodeWithTag("back_to_cull_button").assertExists()

        rule.onNodeWithTag(LearnTab.EXPLORE.tag).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("toggle_thirds").assertExists()
    }

    @Test
    fun `choosing the second scene's card shows that pair`() {
        host(twoScenes)
        val cards = rule.onAllNodesWithTag(CARD)
        assertEquals(2, cards.fetchSemanticsNodes().size)
        cards[1].performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(spoken(BETTER).contains("C.JPG"))
        assertTrue(spoken(OTHER).contains("D.JPG"))
        assertEquals(
            "the chosen card is marked selected",
            true,
            rule.onAllNodesWithTag(CARD)[1].fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )
    }

    @Test
    fun `a folder where nothing differs measurably says so and shows no frames`() {
        host(listOf(frame("A", 0.0, 50.0), frame("B", 1.0, 48.0)))
        val empty = rule.onNodeWithTag(EMPTY, useUnmergedTree = true).fetchSemanticsNode().config
        val text = empty.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertEquals("No measured difference", text)
        assertFalse(text.contains("Load at least"))
        assertEquals(0, rule.onAllNodes(hasTestTag(BETTER), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the compact layout shows both frames too`() {
        host(twoScenes, width = 560, height = 900)
        assertTrue(spoken(BETTER).contains("A.JPG"))
        assertTrue(spoken(OTHER).contains("B.JPG"))
    }

    private companion object {
        const val BETTER = "compare_frame_better"
        const val OTHER = "compare_frame_other"
        const val CARD = "scene_compare_card"
        const val EMPTY = "compare_empty"
    }
}
