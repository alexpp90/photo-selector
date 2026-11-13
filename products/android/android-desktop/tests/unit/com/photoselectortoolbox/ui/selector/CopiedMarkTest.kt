package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.saf.FileOperationResult
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitImageRepository
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.ui.fit.tagExists
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A copy has a visible result (backlog 2026-11-11, REQ-AND-LAYOUT.08): with the shipped default
 * (Copy), `C` copies, moves on to the next frame, and the copied photo carries a mark on its
 * frame and on its filmstrip tile after the confirmation card has gone, clear of every badge
 * and value overlay (REQ-AND-LAYOUT.09). A second Copy of it writes nothing and says so.
 */
@Requirement("REQ-AND-LAYOUT.08", "REQ-AND-LAYOUT.09", "REQ-AND-LAYOUT.21")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class CopiedMarkTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel
    private lateinit var repo: ImageRepository
    private val photos = (1..4).map { fitPhoto("P$it.JPG", 10_000L - it) }

    @Before
    fun setUp() {
        installNoImageLoader(ApplicationProvider.getApplicationContext())
        Dispatchers.setMain(dispatcher)
        repo = fitImageRepository().also { r ->
            coEvery { r.copyBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
        }
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            FitSelectorSettings(filingAction = FilingAction.DEFAULT, filmstrip = true), photos, imageRepository = repo,
        )
        val cell = FitCell(1280, 800, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
        }
        rule.waitUntil(5_000) { rule.tagExists("column_current") }
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        viewModel.viewModelScope.cancel()
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun current() = viewModel.uiState.value.currentImage?.fileName

    private fun press(key: Key) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun marksIn(frameTag: String): List<SemanticsNode> = rule.onAllNodes(
        hasTestTag("copied_mark") and hasAnyAncestor(hasTestTag(frameTag)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun bounds(tag: String): List<Rect> = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.boundsInRoot }

    private fun letTheCardGo() {
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        rule.waitUntil(15_000) { viewModel.uiState.value.snackbarMessage == null }
    }

    @Test
    fun `C copies, steps on, and the copied photo is marked on its frame and filmstrip tile after the card left`() {
        assertEquals(FilingAction.COPY, FilingAction.DEFAULT)
        assertEquals("P1.JPG", current())

        press(Key.C)
        assertEquals("Copy moves on to the next frame", "P2.JPG", current())
        letTheCardGo()

        // P1 is now the Previous frame, with the mark, and nothing else is marked.
        val onPrevious = marksIn("selector.frame.previous")
        assertEquals(1, onPrevious.size)
        assertEquals(
            SelectorLabels.copiedMarkSpoken("Selection"),
            onPrevious.single().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
        )
        assertTrue(marksIn("selector.frame.current").isEmpty())
        assertTrue(marksIn("selector.frame.next").isEmpty())
        assertEquals("one filmstrip tile is marked", 1, bounds("filmstrip_copied_mark").size)

        // The mark shares bounds with no badge and no value overlay.
        val mark = onPrevious.single().boundsInRoot
        for (other in bounds("maximise_badge") + bounds("neighbour_overlay")) {
            assertFalse("copied mark $mark overlaps $other", mark.overlaps(other))
        }

        // Back on P1 the current frame carries it.
        press(Key.DirectionLeft)
        assertEquals("P1.JPG", current())
        assertEquals(1, marksIn("selector.frame.current").size)
    }

    @Test
    fun `a second Copy of a copied photo writes nothing, stays put and says Already copied`() {
        press(Key.C)
        letTheCardGo()
        press(Key.DirectionLeft)
        assertEquals("P1.JPG", current())
        val before = viewModel.uiState.value.snackbarSequence

        press(Key.C)

        // Handled: a new message was raised for it.
        assertTrue(viewModel.uiState.value.snackbarSequence > before)
        assertEquals(SelectorLabels.ALREADY_COPIED, viewModel.uiState.value.snackbarMessage)
        assertEquals("P1.JPG", current())
        coVerify(exactly = 1) { repo.copyBundle(any(), match { it.fileName == "P1.JPG" }, any(), any()) }
    }

    // [USER 2026-11-12] decision #29: a copied photo is decided; a later Move files nothing twice
    @Test
    fun `a Move of a copied photo files nothing a second time and counts it once`() {
        press(Key.C)
        letTheCardGo()
        press(Key.DirectionLeft)
        assertEquals("P1.JPG", current())
        val before = viewModel.uiState.value.snackbarSequence

        press(Key.M)

        // Handled: a new message was raised for it, and nothing was moved.
        assertTrue(viewModel.uiState.value.snackbarSequence > before)
        assertEquals(SelectorLabels.ALREADY_COPIED, viewModel.uiState.value.snackbarMessage)
        assertEquals("P1.JPG", current())
        coVerify(exactly = 0) { repo.moveBundle(any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.copyBundle(any(), any(), any(), any()) }
        assertEquals(1, viewModel.uiState.value.sessionCopiedCount)
        assertEquals(0, viewModel.uiState.value.sessionMovedCount)
    }
}
