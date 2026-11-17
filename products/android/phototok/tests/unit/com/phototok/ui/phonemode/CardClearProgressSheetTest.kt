package com.phototok.ui.phonemode

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.CardClearProgress
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Delete all on a card shows its progress and can be cancelled ([USER 2026-11-16] D2,
 * REQ-TOK-FEATURE.15), on the real [PhoneModeScreen]: the sheet counts "done / total", Cancel stops
 * between files, Back is Cancel, a tap beside the sheet closes nothing, and the outcome says how
 * many went and how many are left.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class CardClearProgressSheetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)
    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val photos = (1..10).map { PhoneScreenHarness.photo("IMG_%04d.JPG".format(it), 100L - it) } // ui-text: ignore test fixture
    private val onDisk = photos.map { it.uri }.toMutableSet()
    private val gates = List(photos.size) { CompletableDeferred<Boolean>() }
    private var deletes = 0

    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
    private fun sheetShown() = nodes(CardClearProgressTags.SHEET).fetchSemanticsNodes().isNotEmpty()
    private fun progress(): CardClearProgress? = screen.viewModel.uiState.value.cardClearProgress

    /** A card of ten photos whose deletes wait for [gates]; Delete all confirmed. */
    private fun deleteAllRunning() {
        coEvery { screen.imageRepository.deleteImage(any()) } coAnswers {
            val ok = gates[deletes++].await()
            if (ok) onDisk.remove(firstArg<Uri>().toString())
            ok
        }
        screen.launch()
        screen.openFolder(photos, folder = screen.sdTree)
        rule.waitUntil(5_000) { screen.viewModel.uiState.value.cardClearReady }
        rule.runOnUiThread { screen.viewModel.confirmClearEntireCard() }
        rule.waitUntil(5_000) { sheetShown() }
    }

    private fun finishFile(index: Int, ok: Boolean = true) {
        rule.runOnUiThread { gates[index].complete(ok) }
        rule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15")
    fun cancelAfterFile3Of10_leaves7_says3Deleted7Left_cardNotCleared() {
        assertEquals("3 deleted · 7 left", res.getString(R.string.snackbar_deleted_left, 3, 7))
        deleteAllRunning()
        finishFile(0)
        finishFile(1)
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(res.getString(R.string.label_clear_progress, 2, 10)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // File 3 is in hand: Cancel waits for it, and says so.
        nodes(CardClearProgressTags.CANCEL).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(true, progress()?.cancelling)
        rule.onAllNodes(hasText(res.getString(R.string.action_cancelling)), useUnmergedTree = true).onFirst().assertExists()
        finishFile(2)

        rule.waitUntil(5_000) { !sheetShown() }
        coVerify(exactly = 3) { screen.imageRepository.deleteImage(any()) }
        assertEquals(7, onDisk.size)
        val state = screen.viewModel.uiState.value
        assertEquals(7, state.allImages.size)
        assertFalse("the card is not cleared", state.cardClearComplete)
        assertNull(state.emptyFolderMessage)
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(res.getString(R.string.snackbar_deleted_left, 3, 7)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Delete all is offered again for what is left", 7, state.cardClearFileCount)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15")
    fun systemBackIsCancel_andNeverLeavesTheApp() {
        deleteAllRunning()
        screen.pressSystemBack()
        assertEquals(0, screen.backPressesThatLeftTheApp)
        assertEquals("Back asked the run to stop", true, progress()?.cancelling)
        assertTrue("the sheet stays until the file in hand returns", sheetShown())
        finishFile(0)
        rule.waitUntil(5_000) { !sheetShown() }
        assertEquals(9, onDisk.size)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15")
    fun aTapBesideTheSheetClosesNothing() {
        deleteAllRunning()
        // The sheet's window is the last root; its top edge is the scrim over the feed.
        rule.onAllNodes(isRoot()).onLast().performTouchInput { click(Offset(centerX, 20f)) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue(sheetShown())
        assertEquals(false, progress()?.cancelling)
        finishFile(0)
        rule.waitUntil(5_000) { progress()?.done == 1 }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15", "REQ-TOK-UX.02")
    fun talkBackHearsTheProgress_andCancelIsA48dpTarget() {
        deleteAllRunning()
        val bar = nodes(CardClearProgressTags.PROGRESS).fetchSemanticsNodes().single()
        assertEquals(0f, bar.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current)
        val count = nodes(CardClearProgressTags.COUNT).fetchSemanticsNodes().single()
        assertEquals(LiveRegionMode.Polite, count.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals(
            listOf(res.getString(R.string.cd_clear_progress, 0, 10)),
            count.config.getOrNull(SemanticsProperties.ContentDescription),
        )
        val cancel = nodes(CardClearProgressTags.CANCEL).onFirst().getBoundsInRoot()
        assertTrue("Cancel ≥ 48 dp (${cancel.height})", cancel.height >= 48.dp)
        finishFile(0)
        rule.waitUntil(5_000) {
            nodes(CardClearProgressTags.PROGRESS).fetchSemanticsNodes().single()
                .config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == 0.1f
        }
        for (i in 1 until photos.size) finishFile(i)
        rule.waitUntil(5_000) { !sheetShown() }
        assertTrue(screen.viewModel.uiState.value.cardClearComplete)
    }
}

/** When TalkBack hears the progress: 10 more files or 2 s, whichever first, never twice within 1 s. */
class ClearProgressAnnouncementTest {
    @Test
    fun tenFilesOrTwoSeconds_butNeverTwiceWithinASecond() {
        assertFalse("nothing changed", ClearProgressAnnouncement.shouldAnnounce(5, 5, 10_000, 0))
        assertTrue("ten files", ClearProgressAnnouncement.shouldAnnounce(10, 0, 1_500, 0))
        assertFalse("nine files within 2 s", ClearProgressAnnouncement.shouldAnnounce(9, 0, 1_500, 0))
        assertTrue("one file after 2 s", ClearProgressAnnouncement.shouldAnnounce(1, 0, 2_000, 0))
        assertFalse("a fast card within a second", ClearProgressAnnouncement.shouldAnnounce(50, 0, 999, 0))
    }
}

/** German at font scale 2.0 on a 360 dp phone: the count and Cancel fit the sheet side by side, Cancel stays 48 dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class CardClearProgressSheetTextLoadTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun bounds(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().getBoundsInRoot()

    private fun assertFits(progress: CardClearProgress) {
        rule.setContent { PhotoTokTheme { CardClearProgressSheet(progress = progress, onCancel = {}) } }
        rule.waitForIdle()
        val sheet = bounds(CardClearProgressTags.SHEET)
        val count = bounds(CardClearProgressTags.COUNT)
        val cancel = bounds(CardClearProgressTags.CANCEL)
        assertTrue("Cancel ≥ 48 dp (${cancel.height})", cancel.height >= 48.dp)
        for ((name, b) in listOf("count" to count, "cancel" to cancel)) {
            assertTrue("$name inside the sheet: $b in $sheet", b.left >= sheet.left && b.right <= sheet.right && b.bottom <= sheet.bottom)
        }
        assertTrue("count and Cancel overlap: $count / $cancel", count.right <= cancel.left || count.bottom <= cancel.top)
    }

    @Test
    @Requirement("REQ-TOK-UX.03", "REQ-TOK-FEATURE.15")
    fun germanAtFontScale2_running() = assertFits(CardClearProgress(done = 1234, total = 10000))

    @Test
    @Requirement("REQ-TOK-UX.03", "REQ-TOK-FEATURE.15")
    fun germanAtFontScale2_cancelling() = assertFits(CardClearProgress(done = 1234, total = 10000, cancelling = true))
}
