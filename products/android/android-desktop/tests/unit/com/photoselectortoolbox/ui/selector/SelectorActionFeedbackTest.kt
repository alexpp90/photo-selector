package com.photoselectortoolbox.ui.selector

import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.saf.FileOperationResult
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitImageRepository
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.tagExists
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Every action the photographer takes on the selector answers on screen, naming what happened
 * and where (REQ-AND-LAYOUT.21, REQ-AND-LAYOUT.08): Copy, Move and Delete by key, UNDO by
 * button and by Ctrl+Z, a sort order, and filing inside the fullscreen viewer. (Star ratings
 * are set on the Learn workstation, not here.)
 * Through the real screen and the real ViewModel, reading what is drawn rather than the state.
 */
@Requirement("REQ-AND-LAYOUT.21", "REQ-AND-LAYOUT.08", "REQ-AND-LAYOUT.22", "REQ-AND-WORKFLOW.06", "REQ-AND-LAYOUT.28")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SelectorActionFeedbackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel
    private val photos = (1..5).map { fitPhoto("P$it.JPG", 10_000L - it) }
    private val keepers = "content://com.android.externalstorage.documents/tree/primary%3APictures%2FKeepers"

    @Before
    fun setUp() {
        installNoImageLoader(ApplicationProvider.getApplicationContext())
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun host(filing: FilingAction = FilingAction.COPY, customDestination: Boolean = false) {
        val settings = FitSelectorSettings(filingAction = filing)
        if (customDestination) settings.customDestination.value = keepers
        val repo = fitImageRepository().also { r ->
            // The file keeps its name in the Selection, so no "as …" rename notice is added.
            coEvery { r.moveBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
            coEvery { r.copyBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
            coEvery { r.undoMoveBundle(any(), any(), any(), any(), any()) } answers {
                val moved = secondArg<List<String>>().first()
                FileOperationResult.success(moved, "content://test/folder/" + moved.substringAfterLast('/'))
            }
            coEvery { r.describeFolder(any(), any()) } returns FolderDescription(
                uri = keepers, displayPath = listOf("Internal storage", "Pictures", "Keepers"), name = "Keepers",
                hasPersistedGrant = true, exists = true, canWrite = true,
            )
        }
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope, settings, photos, imageRepository = repo,
        )
        val cell = FitCell(1280, 800, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
        }
        rule.waitUntil(5_000) { rule.tagExists("column_current") }
    }

    private fun press(key: Key) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun ctrlZ() {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) }
        }
        rule.waitForIdle()
    }

    /** The words the snackbar card draws, or null when no card is up. */
    private fun snackbarText(): String? {
        val nodes = rule.onAllNodes(
            hasAnyAncestor(hasTestTag("selector_snackbar")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (nodes.isEmpty()) return null
        return nodes.joinToString(" ") { n -> n.config.getOrNull(SemanticsProperties.Text)!!.joinToString(" ") }
    }

    private fun undoShown() = rule.tagExists("snackbar_undo")

    private fun current() = viewModel.uiState.value.currentImage?.fileName

    @Test
    fun `C, M and Delete each say what they did and where, and only the reversible ones offer UNDO`() {
        host()
        data class Case(val key: Key, val words: String, val undo: Boolean)
        val cases = listOf(
            Case(Key.C, "Copied to Selection", undo = false),
            Case(Key.M, "Moved to Selection", undo = true),
            Case(Key.Delete, "Deleted", undo = true),
        )
        for (case in cases) {
            press(case.key)
            val text = snackbarText()
            assertNotNull("${case.key} gave no feedback", text)
            assertTrue("${case.key}: \"$text\" does not say \"${case.words}\"", text!!.contains(case.words))
            assertEquals("${case.key}: UNDO offered", case.undo, undoShown())
        }
    }

    @Test
    fun `a Copy and a Move into a custom destination name that folder`() {
        host(customDestination = true)
        press(Key.C)
        assertTrue(snackbarText().orEmpty(), snackbarText().orEmpty().contains("Copied to «Keepers › Selection»"))
        press(Key.M)
        assertTrue(snackbarText().orEmpty(), snackbarText().orEmpty().contains("Moved to «Keepers › Selection»"))
    }

    /** True when the Previous frame draws [name] (its picture or a word naming it). */
    private fun previousFrameShows(name: String) = rule.onAllNodes(
        hasAnyAncestor(hasTestTag("column_previous")) and (hasContentDescription(name) or hasText(name)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `UNDO and Ctrl+Z each put the photo back beside the current frame and take the card away`() {
        host()
        val first = current()!!
        press(Key.M)
        assertFalse("the moved photo left the frame", current() == first)
        assertFalse("and is not drawn as the Previous frame", previousFrameShows(first))
        rule.onNodeWithTag("snackbar_undo", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // An undo keeps the photographer on the frame they are looking at (OptimisticListEdit
        // .restore): the photo returns to its slot, here the one just before the current frame.
        assertTrue("UNDO draws the photo again as the Previous frame", previousFrameShows(first))
        assertEquals("and the card goes", null, snackbarText())

        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(Key.DirectionLeft) }
        rule.waitForIdle()
        assertEquals(first, current())
        press(Key.Delete)
        assertFalse(previousFrameShows(first))
        ctrlZ()
        assertTrue("Ctrl+Z draws the deleted photo again as the Previous frame", previousFrameShows(first))
        assertEquals(null, snackbarText())
    }

    @Test
    fun `the Sort chip names the order in force after each choice`() {
        host()
        // F-FD-049 (lab 2026-10-24): after a sort nothing said which one was active.
        for (choice in listOf(SortChoice.OLDEST_FIRST, SortChoice.FILE_NAME, SortChoice.NEWEST_FIRST)) {
            rule.onNodeWithTag("sort_chip", useUnmergedTree = true).performClick()
            rule.waitForIdle()
            rule.onAllNodesWithTag(choice.tag, useUnmergedTree = true).onFirst().performClick()
            rule.waitForIdle()
            val chip = rule.onNodeWithTag("sort_chip_word", useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)?.joinToString(" ")
            assertTrue("after ${choice.tag} the chip reads \"$chip\"", chip == choice.menuLabel || chip == choice.chipWord)
        }
    }

    @Test
    fun `before a scan the disabled Blurriest first says why`() {
        host()
        rule.onNodeWithTag("sort_chip", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val item = rule.onAllNodesWithTag("sort_blurriest_first", useUnmergedTree = true).onFirst().fetchSemanticsNode()
        assertTrue("disabled before a scan (REQ-AND-WORKFLOW.03)", SemanticsProperties.Disabled in item.config)
        // F-FD-049 (fixed 2026-11-10): the item was greyed out with no reason; it now ends in
        // "Scan first".
        val words = rule.onAllNodes(
            hasAnyAncestor(hasTestTag("sort_blurriest_first")) or hasTestTag("sort_blurriest_first"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap {
            (it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text }) +
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.joinToString(" ")
        assertTrue("\"$words\" gives no reason", words.contains("scan", ignoreCase = true))
    }

    // ── Filing inside the fullscreen viewer ─────────────────────────────────

    /** One key press delivered to the viewer's dialog window, like a keyboard would. */
    private fun pressInViewer(keyCode: Int) {
        val dialog = checkNotNull(ShadowDialog.getLatestDialog()) { "the viewer opened no dialog" }
        rule.runOnUiThread {
            dialog.window!!.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            dialog.window!!.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        rule.waitForIdle()
    }

    private fun flashSays(words: String) =
        rule.onAllNodes(hasContentDescription(words), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `C and M in fullscreen flash what they did, as the snackbar words it`() {
        host()
        press(Key.F)
        pressInViewer(KeyEvent.KEYCODE_C)
        assertTrue("the C flash names the Selection", flashSays("Copied to Selection"))
        pressInViewer(KeyEvent.KEYCODE_M)
        assertTrue("the M flash names the Selection", flashSays("Moved to Selection"))
    }

    @Test
    fun `filing in fullscreen into a custom destination flashes that folder`() {
        host(customDestination = true)
        press(Key.F)
        pressInViewer(KeyEvent.KEYCODE_C)
        val said = viewModel.uiState.value.snackbarMessage
        assertEquals("the snackbar names the custom folder", "Copied to «Keepers › Selection»", said)
        // F-FD-074 (fixed 2026-11-10): the flash said "Copied to Selection", not the folder the
        // photo went to; it now uses the same SelectionActionLabels.confirmation call as the card.
        assertTrue("the flash names «Keepers › Selection»", flashSays("Copied to «Keepers › Selection»"))
    }
}
