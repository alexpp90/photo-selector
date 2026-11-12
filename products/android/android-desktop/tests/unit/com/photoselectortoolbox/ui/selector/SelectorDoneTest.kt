package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.saf.FileOperationResult
import com.photoselectortoolbox.data.model.ImageItem
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
 * The cull has an end (backlog 2026-11-11, REQ-AND-LAYOUT.21): a folder this session emptied by
 * filing and deleting shows what was done and where the kept photos went, not "No photos found";
 * a folder empty when opened still says "No photos found"; Next on the last frame says so.
 */
@Requirement("REQ-AND-LAYOUT.21", "REQ-AND-LAYOUT.22")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SelectorDoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private lateinit var viewModel: SelectorViewModel

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

    private fun host(photos: List<ImageItem>) {
        val repo = fitImageRepository().also { r ->
            coEvery { r.moveBundle(any(), any(), any(), any()) } answers {
                val item = secondArg<ImageItem>()
                FileOperationResult.success(item.uri, "content://test/sel/${item.fileName}")
            }
        }
        viewModel = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            FitSelectorSettings(filingAction = FilingAction.MOVE), photos, imageRepository = repo,
        )
        val cell = FitCell(1280, 800, 1f)
        rule.setContent {
            PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = viewModel) }
        }
        rule.waitUntil(5_000) { !viewModel.uiState.value.isEnumerating }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun spokenSummary(): String? = rule.onNodeWithTag("done_summary", useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `filing and deleting every photo ends on Done with the counts and the destination`() {
        host(listOf(fitPhoto("P1.JPG", 2_000), fitPhoto("P2.JPG", 1_000)))

        press(Key.M)
        press(Key.Delete)

        rule.waitUntil(5_000) { rule.tagExists("done_card") }
        assertFalse("not the empty-folder card", rule.tagExists("empty_folder_card"))
        assertEquals("Done. 1 moved, 1 deleted.", spokenSummary())
        assertTrue(rule.tagExists("done_count_moved"))
        assertTrue(rule.tagExists("done_count_deleted"))
        assertFalse("a zero cell is hidden", rule.tagExists("done_count_copied"))
        assertTrue(
            "the destination names the Selection inside the open folder",
            rule.onAllNodes(hasText("Trip › Selection"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(rule.tagExists("done_open_folder"))
    }

    @Test
    fun `an undo takes the card away and its count back`() {
        host(listOf(fitPhoto("P1.JPG", 2_000), fitPhoto("P2.JPG", 1_000)))
        press(Key.M)
        press(Key.Delete)
        rule.waitUntil(5_000) { rule.tagExists("done_card") }

        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) }
        }
        rule.waitForIdle()
        assertFalse("the card goes on Undo", rule.tagExists("done_card"))
        assertEquals(listOf("P2.JPG"), viewModel.uiState.value.images.map { it.fileName })

        press(Key.Delete)
        rule.waitUntil(5_000) { rule.tagExists("done_card") }
        assertEquals("the undone delete is not counted twice", "Done. 1 moved, 1 deleted.", spokenSummary())
    }

    @Test
    fun `a folder empty when opened still says No photos found`() {
        host(emptyList())

        assertTrue(rule.tagExists("empty_folder_card"))
        assertFalse(rule.tagExists("done_card"))
    }

    @Test
    fun `Next on the last frame says Last photo`() {
        host(listOf(fitPhoto("P1.JPG", 2_000), fitPhoto("P2.JPG", 1_000)))
        press(Key.DirectionRight)
        assertEquals("P2.JPG", viewModel.uiState.value.currentImage?.fileName)
        val before = viewModel.uiState.value.snackbarSequence

        press(Key.DirectionRight)

        assertEquals("P2.JPG", viewModel.uiState.value.currentImage?.fileName)
        assertTrue(viewModel.uiState.value.snackbarSequence > before)
        assertEquals(SelectorLabels.LAST_PHOTO, viewModel.uiState.value.snackbarMessage)
    }
}
