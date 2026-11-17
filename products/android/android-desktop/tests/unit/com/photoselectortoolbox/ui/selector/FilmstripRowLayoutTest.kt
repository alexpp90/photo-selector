package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.R
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.testing.str
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filmstrip row in tablet portrait, drawn (DESIGN §15.1): under the frames, full
 * content width, 64 dp tall, never over a frame or a control, with 48 dp targets and
 * the current tile in the middle. The window is forced by a `requiredSize` host, so the
 * sizes are the test's and not the display's.
 */
@Requirement("REQ-AND-LAYOUT.14", "REQ-AND-INPUT.04")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1480dp-h1240dp-xhdpi")
class FilmstripRowLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val selected = mutableListOf<Int>()

    private fun photo(n: Int) = ImageItem(
        uri = "content://test/row/image$n.jpg",
        fileName = "image$n.jpg",
        fileSize = 1,
        lastModified = n.toLong(),
        mimeType = "image/jpeg",
        imageWidth = if (n % 4 == 0) 1280 else 1920,
        imageHeight = if (n % 4 == 0) 1920 else 1280,
        exifData = ExifData(shutterSpeed = 0.004, aperture = 2.8, iso = 400),
    )

    private val photos = (1..24).map(::photo)
    private val currentIndex = 11

    /** The image region of a [width] x [height] window: the window less the 88 dp sidebar. */
    private fun host(width: Int, height: Int, filmstripVisible: Boolean = true) {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                Box(Modifier.requiredSize((width - 88).dp, height.dp)) {
                    ThreeUpSelectorLayout(
                        current = photos[currentIndex],
                        previous = photos[currentIndex - 1],
                        next = photos[currentIndex + 1],
                        currentIndex = currentIndex,
                        total = photos.size,
                        filingAction = FilingAction.COPY,
                        folderName = "Row",
                        burstLabel = null,
                        detailsVisible = true,
                        filmstripVisible = filmstripVisible,
                        overlayValuesVisible = true,
                        maximisedFrame = null,
                        actions = SelectorActions({}, {}, {}, {}, {}, {}, {}, {}),
                        onNavigatePrevious = {},
                        onNavigateNext = {},
                        onMaximise = {},
                        onLongPressFrame = {},
                        showFirstRunHint = false,
                        onDismissFirstRunHint = {},
                        filmstrip = { placement ->
                            CandidateStrip(
                                images = photos,
                                currentIndex = currentIndex,
                                selectedUri = photos[currentIndex].uri,
                                onImageSelected = { selected += it },
                                placement = placement,
                            )
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String, index: Int = 0): DpRect =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true)[index].getUnclippedBoundsInRoot()

    private fun count(tag: String): Int =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun intersects(a: DpRect, b: DpRect) =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private val density: Float
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    private fun px(d: androidx.compose.ui.unit.Dp) = (d.value * density).roundToInt()

    private val frames = listOf("selector.frame.current", "selector.frame.previous", "selector.frame.next")
    private val controls = listOf(
        "copy_button_expanded", "move_button_expanded", "delete_button_expanded",
        "fullscreen_button", "rail_previous", "rail_next",
        "details_toggle", "filmstrip_toggle", "overlay_values_toggle", "shortcuts_button",
    )

    private fun assertRow(width: Int, height: Int) {
        host(width, height)
        val at = "${width}x$height"
        assertEquals("one strip at $at", 1, count("filmstrip"))
        assertEquals("the strip is the row at $at", 1, count("filmstrip_row"))
        val strip = bounds("filmstrip")
        val content = (width - 88 - 16).toFloat()

        assertEquals("row height at $at", 64f, strip.height.value, 0.5f)
        assertEquals("row spans the content width at $at", content, strip.width.value, 0.5f)
        frames.forEach { tag ->
            val f = bounds(tag)
            assertTrue("the row $strip is not under $tag $f at $at", strip.top >= f.bottom)
        }
        (controls + "maximise_badge").forEach { tag ->
            for (i in 0 until count(tag)) {
                assertTrue("the row $strip overlaps $tag at $at", !intersects(strip, bounds(tag, i)))
            }
        }

        // Free: the frames are the solver's, which are the same with the strip on and off.
        val region = (width - 88 - 16).dp to (height - 16).dp
        val solved = FrameGeometry.threeUpLayout(region.first, region.second, aspect = 1.5f, filmstripVisible = false).frame
        val current = bounds("column_current")
        assertTrue(
            "the current frame is ${current.width} x ${current.height}, the solver's is $solved at $at",
            abs(current.width.value - solved.width.value) < 1f && abs(current.height.value - solved.height.value) < 1f,
        )

        // 48 dp targets in whole pixels, abutting rather than overlapping.
        val n = count("filmstrip_thumbnail")
        assertTrue("only $n tiles laid out at $at", n >= 3)
        val targets = (0 until n).map { bounds("filmstrip_thumbnail", it) }
        val floor = px(48.dp)
        targets.forEachIndexed { i, t ->
            assertTrue("tile $i is ${px(t.width)} x ${px(t.height)} px at $at", px(t.width) >= floor && px(t.height) >= floor)
        }
        targets.zipWithNext().forEach { (a, b) -> assertTrue("tiles $a and $b overlap at $at", !intersects(a, b)) }
        // Tiles are 60 x 48 whatever the photograph's orientation.
        for (i in 0 until n) {
            val picture = bounds("filmstrip_thumbnail_image", i)
            assertEquals("tile width at $at", 60f, picture.width.value, 0.5f)
            assertEquals("tile height at $at", 48f, picture.height.value, 0.5f)
        }

        // The current tile is spoken by position and name, and sits mid-row.
        val spoken = str(R.string.cd_filmstrip_tile, currentIndex + 1, photos.size, photos[currentIndex].fileName)
        val nodes = rule.onAllNodesWithTag("filmstrip_thumbnail", useUnmergedTree = true).fetchSemanticsNodes()
        val currentNode = nodes.firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() == spoken
        }
        assertTrue("no tile is spoken \"$spoken\" at $at", currentNode != null)
        assertEquals("Photo 12 of 24, image12.jpg", spoken)
        assertTrue("the current tile is not marked selected", currentNode!!.config.getOrNull(SemanticsProperties.Selected) == true)
        val c = currentNode.boundsInRoot
        val tileCentre = (c.left + c.right) / 2f / density
        val rowCentre = (strip.left.value + strip.right.value) / 2f
        assertTrue(
            "the current tile is centred at $tileCentre dp, the row at $rowCentre dp ($at)",
            abs(tileCentre - rowCentre) <= RowThumbnailSlotWidth.value,
        )
    }

    @Test
    fun `the row sits under the frames at 600 x 900`() = assertRow(600, 900)

    @Test
    fun `the row sits under the frames at 720 x 1100`() = assertRow(720, 1100)

    @Test
    fun `the row sits under the frames at 840 x 1200`() = assertRow(840, 1200)

    @Test
    fun `a tile opens its photograph`() {
        host(720, 1100)
        rule.onAllNodesWithTag("filmstrip_thumbnail", useUnmergedTree = true)[0].performClick()
        rule.waitForIdle()
        assertEquals(1, selected.size)
        assertTrue("index ${selected.single()} is not a photo", selected.single() in photos.indices)
    }

    @Test
    fun `the row follows the toggle and is off unless switched on`() {
        host(720, 1100, filmstripVisible = false)
        assertEquals(0, count("filmstrip"))
        assertEquals(1, count("filmstrip_toggle"))
    }

    @Test
    fun `a window without spare height draws no strip and keeps the toggle`() {
        host(800, 420)
        assertEquals(0, count("filmstrip"))
        assertEquals(1, count("filmstrip_toggle"))
    }
}
