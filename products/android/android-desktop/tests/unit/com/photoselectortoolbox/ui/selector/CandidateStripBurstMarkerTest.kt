package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The 4 dp burst marker (REQ-AND-LAYOUT.14) sits on the burst of the frame on screen, also when that
 * frame is the last of its burst (FD-B-6: the strip used to be handed the 1-based position readout
 * and marked the burst of the next frame).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "xhdpi")
class CandidateStripBurstMarkerTest {

    @get:Rule
    val rule = createComposeRule()

    private fun image(name: String) = ImageItem(
        uri = "content://test/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 0,
        mimeType = "image/jpeg",
        imageWidth = 300,
        imageHeight = 200,
    )

    private val images = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg").map(::image)

    /** Names of the thumbnails drawn 4 dp wider than the rest, i.e. the marked burst. */
    private fun markedNames(): List<String> =
        rule.onAllNodes(hasTestTag("filmstrip_thumbnail")).fetchSemanticsNodes().mapIndexedNotNull { i, node ->
            val bounds = rule.onAllNodes(hasTestTag("filmstrip_thumbnail"))[i].getUnclippedBoundsInRoot()
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?.substringBefore(',')
            name.takeIf { bounds.width > 60.dp }
        }

    private fun host(currentIndex: Int, selectedUri: String?) {
        rule.setContent {
            Box(Modifier.width(120.dp).height(600.dp)) {
                CandidateStrip(
                    images = images,
                    currentIndex = currentIndex,
                    selectedUri = selectedUri,
                    onImageSelected = {},
                    groups = listOf(listOf(0, 1), listOf(2, 3)),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the marker follows the current frame at the last frame of a burst`() {
        host(currentIndex = 1, selectedUri = images[1].uri)

        assertEquals(listOf("a.jpg", "b.jpg"), markedNames())
    }

    @Test
    fun `a position handed over in the wrong base cannot move the marker to the next burst`() {
        // 2 is b.jpg's 1-based position; as an index it names c.jpg, the first frame of burst 2.
        host(currentIndex = 2, selectedUri = images[1].uri)

        assertEquals(listOf("a.jpg", "b.jpg"), markedNames())
    }
}
