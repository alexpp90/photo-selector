package com.photoselectortoolbox.ui.selector

import android.view.KeyEvent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.DeferredDeletion
import com.photoselectortoolbox.domain.curation.SelectorListState
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import com.photoselectortoolbox.domain.interaction.FilingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The fullscreen viewer's keyboard, chrome and EXIF.
 *
 * The three reported symptoms, each as a regression: an arrow key that did
 * nothing the first time (or after the chrome faded, or after a move), and an
 * EXIF line that was not there for the frame just paged to.
 *
 * Keys are dispatched through the dialog's own window (`decorView.dispatchKeyEvent`),
 * as a keyboard would. `performKeyInput` is not used for the key itself: it
 * requests focus on the node first, which is precisely what hid this bug in the
 * instrumented suite. Focus is asserted separately, since focus is the mechanism.
 *
 * The host stands in for the ViewModel: a move or delete removes the current
 * frame and the index follows, which is what `OptimisticEdits` does.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class FullscreenViewerTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(name: String, iso: Int) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 1920,
        imageHeight = 1080,
        exifData = ExifData(
            shutterSpeed = 0.008,
            aperture = 4.0,
            iso = iso,
            focalLength = 85.0,
            focalLength35mm = 85.0,
            lens = "FE 85mm F1.4 GM",
            isFallback = false,
        ),
    )

    private val five = listOf(
        photo("a.jpg", 100), photo("b.jpg", 200), photo("c.jpg", 400),
        photo("d.jpg", 800), photo("e.jpg", 1600),
    )

    private class Harness {
        var images by mutableStateOf(emptyList<ImageItem>())
        var index by mutableIntStateOf(0)
        var pinned by mutableStateOf(false)
        var pinToggles = 0
        var deletes = 0
        var message by mutableStateOf<String?>(null)
        var sequence by mutableStateOf(0L)
        var lifetime by mutableStateOf(SnackbarLifetime.NOTICE)
        var dismissed = 0
        var undos = 0
        val moved = mutableListOf<String>()
        val copied = mutableListOf<String>()
        /** Every page the viewer reported to the ViewModel. */
        val reported = mutableListOf<Int>()
    }

    private fun host(
        harness: Harness,
        width: Int = 1280,
        filing: FilingAction = FilingAction.COPY,
    ) {
        rule.setContent {
            FullscreenViewer(
                images = harness.images,
                initialIndex = harness.index,
                onDismiss = {},
                onDelete = { harness.deletes++ },
                onMoveToSelection = { i ->
                    harness.moved += harness.images[i].fileName
                    harness.images = harness.images.filterIndexed { n, _ -> n != i }
                    harness.index = i.coerceAtMost(harness.images.size - 1)
                },
                onCopyToSelection = { i -> harness.copied += harness.images[i].fileName },
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(width.dp, 800.dp)),
                onPageSelected = { harness.reported += it; harness.index = it },
                fullscreenButtonsEnabled = true,
                filingAction = filing,
                infoPinned = harness.pinned,
                onToggleInfoPinned = {
                    harness.pinToggles++
                    harness.pinned = !harness.pinned
                },
                snackbarMessage = harness.message,
                snackbarSequence = harness.sequence,
                snackbarLifetime = harness.lifetime,
                onUndo = { harness.undos++ },
                onDismissSnackbar = {
                    harness.dismissed++
                    harness.message = null
                },
            )
        }
    }

    private fun open(width: Int = 1280, configure: Harness.() -> Unit = {}): Harness {
        val harness = Harness().apply {
            images = five
            configure()
        }
        host(harness, width)
        rule.waitForIdle()
        return harness
    }

    /** One physical key press, delivered to the dialog window like a keyboard would. */
    private fun press(keyCode: Int) {
        val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "the viewer opened no dialog" }
        val decor = dialog.window!!.decorView
        rule.runOnUiThread {
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        rule.waitForIdle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    private fun waitForChromeToHide() {
        rule.mainClock.advanceTimeBy(3_600)
        rule.waitForIdle()
    }

    private fun position(): String? =
        rule.onAllNodesWithTag("fullscreen_position", useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** The exposure line as the panel shows it: its tokens, in order, one space apart. */
    private fun exif(): String? =
        rule.onAllNodes(
            hasTestTag("exposure_token") and hasAnyAncestor(hasTestTag("fullscreen_exif")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().takeIf { it.isNotEmpty() }
            ?.joinToString(" ") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            }

    private fun chromeShown() = position() != null

    // ── Arrow keys ───────────────────────────────────────────────────────

    @Test
    fun `the viewer holds key focus as soon as it opens`() {
        open()
        rule.onNodeWithTag("fullscreen_root").assertIsFocused()
    }

    @Test
    fun `the first right arrow after opening pages to the next frame`() {
        open()
        assertEquals("1 / 5", position())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertEquals("2 / 5", position())
    }

    @Test
    fun `left arrow pages back and stops at the first frame`() {
        open { index = 1 }

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        settle()
        assertEquals("1 / 5", position())

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        settle()
        assertEquals("1 / 5", position())
    }

    @Test
    fun `focus survives the chrome fading and the next arrow still pages`() {
        open()
        waitForChromeToHide()
        assertFalse("the chrome faded", chromeShown())
        rule.onNodeWithTag("fullscreen_root").assertIsFocused()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertEquals("2 / 5", position())
    }

    @Test
    fun `arrows keep paging after the chrome has faded twice`() {
        open()
        waitForChromeToHide()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()
        waitForChromeToHide()
        assertFalse(chromeShown())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertEquals("3 / 5", position())
    }

    @Test
    fun `two quick presses land two frames on, not one`() {
        open()
        rule.mainClock.autoAdvance = false

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeBy(16)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        rule.mainClock.autoAdvance = true
        settle()
        assertEquals("3 / 5", position())
    }

    // ── A move inside fullscreen ─────────────────────────────────────────

    @Test
    fun `the arrow still pages after a move made inside fullscreen`() {
        val harness = open()

        press(KeyEvent.KEYCODE_M)
        settle()
        assertEquals(listOf("a.jpg"), harness.moved)
        assertEquals("the next frame is now the first", "1 / 4", position())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertEquals("2 / 4", position())
        rule.onNodeWithTag("fullscreen_root").assertIsFocused()
    }

    @Test
    fun `the arrow still pages after the last frame was moved away`() {
        val harness = open { index = 4 }
        settle()
        assertEquals("5 / 5", position())

        press(KeyEvent.KEYCODE_M)
        settle()
        assertEquals(listOf("e.jpg"), harness.moved)
        assertEquals("4 / 4", position())

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        settle()

        assertEquals("3 / 4", position())
    }

    @Test
    fun `a move after the chrome faded, then an arrow, still pages`() {
        val harness = open()
        waitForChromeToHide()

        press(KeyEvent.KEYCODE_M)
        settle()
        waitForChromeToHide()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertEquals(1, harness.moved.size)
        assertEquals("2 / 4", position())
    }

    // ── EXIF ─────────────────────────────────────────────────────────────

    @Test
    fun `the first frame shows its exposure line`() {
        open()
        assertEquals("1/125s · f/4.0 · 85mm · ISO 100", exif())
    }

    @Test
    fun `paging by arrow after the chrome faded shows the new frame's exif`() {
        open()
        waitForChromeToHide()
        assertFalse(chromeShown())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertTrue("ISO 200 is frame b", exif()!!.endsWith("ISO 200"))
    }

    @Test
    fun `any key brings the chrome back`() {
        open()
        waitForChromeToHide()
        assertFalse(chromeShown())

        press(KeyEvent.KEYCODE_SHIFT_LEFT)
        settle()

        assertTrue(chromeShown())
    }

    @Test
    fun `the revealed chrome fades again after three seconds`() {
        open()
        waitForChromeToHide()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()
        assertTrue(chromeShown())

        rule.mainClock.advanceTimeBy(2_000)
        assertTrue("still within 3 s of the last key", chromeShown())
        waitForChromeToHide()
        assertFalse(chromeShown())
    }

    @Test
    fun `a move reveals the chrome for the frame that took its place`() {
        open()
        waitForChromeToHide()

        press(KeyEvent.KEYCODE_M)
        settle()

        assertTrue(chromeShown())
        assertTrue(exif()!!.endsWith("ISO 200"))
    }

    // ── The info pin ─────────────────────────────────────────────────────

    @Test
    fun `I pins the panel and a pinned panel never fades`() {
        val harness = open()

        press(KeyEvent.KEYCODE_I)
        settle()
        assertEquals(1, harness.pinToggles)
        assertTrue(harness.pinned)

        rule.mainClock.advanceTimeBy(20_000)
        rule.waitForIdle()
        assertTrue("pinned: still there after 20 s", chromeShown())
    }

    @Test
    fun `a pinned panel stays through a page change and I releases it`() {
        val harness = open { pinned = true }
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        assertTrue(chromeShown())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()
        assertTrue(exif()!!.endsWith("ISO 200"))

        press(KeyEvent.KEYCODE_I)
        settle()
        assertFalse(harness.pinned)
        waitForChromeToHide()
        assertFalse("unpinned: fades again", chromeShown())
    }

    @Test
    fun `the pin button is a 48 dp toggle in the panel`() {
        val harness = open()
        val node = rule.onNodeWithTag("fullscreen_info_pin", useUnmergedTree = true)
        val size = node.fetchSemanticsNode().size
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("${size.width}x${size.height} px, needs $min", size.width >= min && size.height >= min)

        node.performClickViaSemantics()
        assertEquals(1, harness.pinToggles)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickViaSemantics() {
        rule.runOnUiThread {
            fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        }
        rule.waitForIdle()
    }

    // ── The filing flash ─────────────────────────────────────────────────

    @Test
    fun `the M key flashes what a move says`() {
        open()
        press(KeyEvent.KEYCODE_M)
        assertNotNull(
            rule.onAllNodes(hasContentDescription("Moved to Selection"), useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull(),
        )
    }

    @Test
    fun `the C key flashes what a copy says, whatever the filing action is`() {
        open()
        press(KeyEvent.KEYCODE_C)
        assertNotNull(
            rule.onAllNodes(hasContentDescription("Copied to Selection"), useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull(),
        )
    }

    // ── The confirmation card inside fullscreen ──────────────────────────

    @Test
    fun `a move's confirmation shows in fullscreen with UNDO and no countdown line`() {
        open {
            message = "Moved to Selection"
            sequence = 1
            lifetime = SnackbarLifetime.MOVE
        }
        assertEquals(1, rule.onAllNodesWithTag("selector_snackbar", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("snackbar_countdown", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a delete's card in fullscreen keeps its draining line`() {
        open {
            message = "1 image deleted"
            sequence = 1
            lifetime = SnackbarLifetime.DELETE
        }
        assertEquals(1, rule.onAllNodesWithTag("snackbar_countdown", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // ── The list changes while the viewer is open ────────────────────────
    //
    // The pager is keyed by URI, so a list change re-indexes it with no scroll. The
    // ViewModel's index is the truth, and a re-order it applied must not be reported
    // back as the page the old photo moved to.

    private fun afterListChange() {
        rule.waitForIdle()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    @Test
    fun `a settle re-order that lands on the new frame 1 is not dragged back to the old photo`() {
        // Fullscreen was opened before discovery settled: untouched, on frame 1 (a).
        // Settle puts the newest first and the ViewModel lands on it; the pager's
        // key-follow would carry it to a.jpg's new page, 5.
        val harness = open()
        assertEquals("1 / 5", position())

        harness.images = five.reversed()
        harness.index = 0
        afterListChange()

        assertEquals("the ViewModel's frame is the truth", 0, harness.index)
        assertEquals("e.jpg, the new frame 1, is on screen", "1 / 5", position())
        assertTrue(exif().orEmpty(), exif().orEmpty().contains("1600"))
        assertTrue("the viewer reported ${harness.reported}", harness.reported.isEmpty())
    }

    @Test
    fun `a sort change keeps the photo on screen and reports nothing`() {
        val harness = open { index = 1 }
        assertEquals("2 / 5", position())

        harness.images = five.reversed()
        harness.index = 3
        afterListChange()

        assertEquals(3, harness.index)
        assertEquals("4 / 5", position())
        assertTrue(exif().orEmpty(), exif().orEmpty().contains("200"))
        assertTrue("the viewer reported ${harness.reported}", harness.reported.isEmpty())
    }

    @Test
    fun `an undo that restores a photo above the current one keeps the current photo`() {
        // The real list logic: delete a, then take it back.
        val afterDelete = DeferredDeletion.beginForCurrent(SelectorListState(five, 0), 0L)!!
        val harness = open {
            images = afterDelete.first.images
            index = afterDelete.first.currentIndex
        }
        assertEquals("1 / 4", position())
        assertTrue(exif().orEmpty(), exif().orEmpty().contains("200"))

        val restored = DeferredDeletion.revert(SelectorListState(harness.images, harness.index), afterDelete.second)
        harness.images = restored.images
        harness.index = restored.currentIndex
        afterListChange()

        assertEquals("b.jpg is still the frame on screen", "2 / 5", position())
        assertTrue(exif().orEmpty(), exif().orEmpty().contains("200"))
        assertEquals(1, harness.index)
        assertTrue("the viewer reported ${harness.reported}", harness.reported.isEmpty())
    }

    @Test
    fun `an undo that restores a photo above and re-sorts to a different frame follows the ViewModel`() {
        // Newest first with e deleted earlier: [d, c, b, a] on c. The undo restores e at
        // the top and the ViewModel, having re-sorted, lands on d (its rule, not the
        // pager's): index 1 of [e, d, c, b, a].
        val harness = open {
            images = listOf(five[3], five[2], five[1], five[0])
            index = 1
        }
        assertEquals("2 / 4", position())

        harness.images = listOf(five[4], five[3], five[2], five[1], five[0])
        harness.index = 1
        afterListChange()

        assertEquals(1, harness.index)
        assertEquals("2 / 5", position())
        assertTrue(exif().orEmpty(), exif().orEmpty().contains("800"))
        assertTrue("the viewer reported ${harness.reported}", harness.reported.isEmpty())
    }

    @Test
    fun `a swipe or arrow is still reported`() {
        val harness = open()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()

        assertEquals(listOf(1), harness.reported)
        assertEquals("2 / 5", position())
    }
}
