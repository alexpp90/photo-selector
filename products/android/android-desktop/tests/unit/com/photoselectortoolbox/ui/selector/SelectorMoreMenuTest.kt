package com.photoselectortoolbox.ui.selector

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.saf.FileOperationResult
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.session.SelectorSortOrder
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.photoselectortoolbox.testing.en

/**
 * The More menu, one list in both layouts (REQ-AND-LAYOUT.02, REQ-AND-LAYOUT.22, backlog
 * 2026-11-11):
 * - below 600 dp, where there is no sidebar, the action row's More opens a folder, starts a
 *   scan and changes the order without a keyboard;
 * - its first row is Undo with the number of steps, so touch steps back through the same 20
 *   entries `Ctrl+Z` does, after the confirmation card has gone.
 * Through the real screen and view model, by tap only.
 */
@Requirement("REQ-AND-LAYOUT.02", "REQ-AND-LAYOUT.22", "REQ-AND-WORKFLOW.06")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SelectorMoreMenuTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel
    private val photos = (1..5).map { fitPhoto("P$it.JPG", 10_000L - it) }

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

    private fun host(cell: FitCell) {
        val repo = fitImageRepository().also { r ->
            coEvery { r.moveBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
            coEvery { r.undoMoveBundle(any(), any(), any(), any(), any()) } answers {
                val moved = secondArg<List<String>>().first()
                FileOperationResult.success(moved, "content://test/folder/" + moved.substringAfterLast('/'))
            }
        }
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            FitSelectorSettings(filingAction = FilingAction.MOVE), photos, imageRepository = repo,
        )
        rule.setContent {
            PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
        }
        rule.waitUntil(5_000) { viewModel.uiState.value.images.isNotEmpty() }
        rule.waitForIdle()
    }

    private val compact = FitCell(360, 720, 1f)
    private val wide = FitCell(1280, 800, 1f)

    private fun names() = viewModel.uiState.value.images.map { it.fileName }

    private fun openMenu(moreTag: String) {
        rule.onNodeWithTag(moreTag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    private fun clickMenu(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    /** Move three photos by tap, then let the confirmation card leave. */
    private fun moveThreeByTouch(moveTag: String) {
        repeat(3) {
            rule.onNodeWithTag(moveTag, useUnmergedTree = true).performClick()
            rule.waitForIdle()
        }
        assertEquals(photos.size - 3, names().size)
        rule.mainClock.advanceTimeBy(10_000)
        rule.waitForIdle()
        assertTrue("the card has gone", !rule.tagExists("snackbar_undo"))
    }

    private fun undoCount(): String? = rule.onNodeWithTag("menu_undo_count", useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString()

    private fun undoAllThreeFromTheMenu(moreTag: String) {
        openMenu(moreTag)
        assertEquals("3", undoCount())
        val spoken = rule.onNodeWithTag("menu_undo", useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals(SelectorLabels.undoMenuSpoken(3).en(), spoken)
        // The menu stays open, so each tap is one more step back.
        repeat(3) { clickMenu("menu_undo") }
        assertEquals(photos.map { it.fileName }.sorted(), names().sorted())
        assertEquals("0", undoCount())
        rule.onNodeWithTag("menu_undo", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun `wide window - three moves by tap are undone one by one from the More menu after the card left`() {
        host(wide)
        moveThreeByTouch("move_button_expanded")
        undoAllThreeFromTheMenu("sidebar_more")
    }

    @Test
    fun `compact window - three moves by tap are undone one by one from the More menu after the card left`() {
        host(compact)
        moveThreeByTouch("move_button_compact")
        undoAllThreeFromTheMenu("compact_more_button")
    }

    @Test
    fun `compact window - More opens another folder, starts a scan and changes the order without a key`() {
        host(compact)
        assertTrue("More is on the compact action row", rule.tagExists("compact_more_button"))

        // Order.
        openMenu("compact_more_button")
        clickMenu(SortChoice.OLDEST_FIRST.tag)
        assertEquals(SelectorSortOrder.OLDEST_FIRST, viewModel.uiState.value.sortOrder)
        assertEquals(photos.map { it.fileName }.reversed(), names())

        // Scan: the sheet opens and Start runs the scan, which reports on the card.
        val before = viewModel.uiState.value.snackbarSequence
        openMenu("compact_more_button")
        clickMenu("menu_scan")
        rule.onAllNodes(hasText("Start Scan"), useUnmergedTree = true).onFirst().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { viewModel.uiState.value.snackbarSequence > before }
        assertEquals(SelectorLabels.scanCompleteMessage(0).en(), viewModel.uiState.value.snackbarMessage?.en())

        // Folder: the system picker is asked for a tree, and the folder it returns is opened.
        openMenu("compact_more_button")
        clickMenu("menu_open_folder")
        val activity = shadowOf(rule.activity)
        val request = activity.nextStartedActivityForResult
        assertNotNull("Open folder starts the folder picker", request)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.intent.action)
        val second = Uri.parse("content://test/second")
        activity.receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(second))
        rule.waitForIdle()
        assertEquals(second.toString(), viewModel.uiState.value.folderUri)
    }
}
