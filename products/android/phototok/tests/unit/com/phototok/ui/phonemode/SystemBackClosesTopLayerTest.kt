package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.photoselector.core.Requirement
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.ImageRepository
import com.phototok.domain.FileTypeFilter
import com.phototok.domain.FirstRunHint
import com.phototok.ui.theme.PhotoTokTheme
import com.phototok.viewmodel.ReloadPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Class check "System Back closes the topmost layer, never the app" (gap analysis 2026-11-09,
 * rank 10; F-TOK-025 Selection viewer, F-TOK-085 controls guide, Frameduel AD-A1).
 *
 * Each case opens one layer over the feed of the real [PhoneModeScreen], delivers System Back to the
 * topmost window the way the platform does, and asserts: the layer is gone, the feed is still there
 * with its photo, and no Back press fell through to the activity (which would close the app).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class SystemBackClosesTopLayerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)

    private fun nodes(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun feed(images: List<ImageItem> = PhoneScreenHarness.PHOTOS, folder: String = screen.phoneTree) {
        screen.launch()
        screen.openFolder(images, folder)
    }

    /** Back closes what [isOpen] reports, keeps the feed on [photoIndex], and never leaves the app. */
    private fun assertBackClosesIt(layer: String, isOpen: () -> Boolean, photoIndex: Int = 0) {
        assertTrue("$layer: the layer did not open", isOpen())
        val photo = screen.viewModel.uiState.value.images[photoIndex].uri
        screen.pressSystemBack()
        assertEquals("$layer: Back closed the app instead of the layer", 0, screen.backPressesThatLeftTheApp)
        assertTrue("$layer: still open after Back", !isOpen())
        val state = screen.viewModel.uiState.value
        assertEquals("$layer: the feed lost its photo", photo, state.images.getOrNull(state.currentIndex)?.uri)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun selectionViewer() {
        feed()
        rule.runOnUiThread {
            screen.selectionViewModel.openWithFavourites(
                targetFolderUri = screen.phoneTree,
                sourceImages = screen.viewModel.uiState.value.images,
                favourites = com.phototok.domain.FavouritesSnapshot(
                    screen.viewModel.uiState.value.images.map { it.uri }.toSet(),
                ),
            )
        }
        rule.waitUntil(5_000) { screen.selectionViewModel.uiState.value.isOpen }
        rule.waitForIdle()
        assertBackClosesIt("Selection viewer", { screen.selectionViewModel.uiState.value.isOpen })
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun deleteConfirmationDialog() {
        feed()
        rule.runOnUiThread { screen.viewModel.onSwipeLeft() }
        rule.waitForIdle()
        assertTrue("the dialog is a window of its own", screen.windowCount() > 1)
        assertBackClosesIt("delete confirmation", { screen.viewModel.uiState.value.confirmDeleteRequested })
        assertEquals("Back deletes nothing", PhoneScreenHarness.PHOTOS.size, screen.viewModel.uiState.value.images.size)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.15")
    fun cardDoneDialog() {
        feed(folder = screen.sdTree)
        rule.runOnUiThread { screen.viewModel.openCardDoneSheet() }
        rule.waitForIdle()
        assertBackClosesIt("Done with this card", { screen.viewModel.uiState.value.showCardDoneSheet })
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.11")
    fun rawJpegSuggestionDialog() {
        screen.sourceSettings = screen.sourceSettings.copy(fileTypeFilter = FileTypeFilter.ALL, moveRelatedFiles = false)
        feed(
            listOf(
                PhoneScreenHarness.photo("IMG_0001.JPG", 30), // ui-text: ignore test fixture
                PhoneScreenHarness.photo("IMG_0001.CR3", 30, mime = "image/x-canon-cr3"), // ui-text: ignore test fixture
            ),
        )
        rule.runOnUiThread { screen.viewModel.maybeShowRawJpegSuggestion() }
        rule.waitForIdle()
        assertBackClosesIt("RAW + JPEG suggestion", { screen.viewModel.uiState.value.showRawJpegSuggestion })
    }

    /** F-TOK-085 (fixed): the guide is drawn in the activity window; its own BackHandler closes it. */
    @Test
    @Requirement("REQ-TOK-GESTURE.07")
    fun controlsGuide() {
        feed()
        rule.runOnUiThread { screen.viewModel.showControlsGuide() }
        rule.waitForIdle()
        assertTrue("the guide opened", screen.viewModel.uiState.value.showControlsGuide)
        assertBackClosesIt("controls guide", { screen.viewModel.uiState.value.showControlsGuide })
        // One Back closed only the guide: the next one reaches the activity again.
        screen.pressSystemBack()
        assertEquals("a second Back is not consumed by the closed guide", 1, screen.backPressesThatLeftTheApp)
    }

    /** F-TOK-085 (fixed, same cause): the first-run tutorial is the same overlay. */
    @Test
    @Requirement("REQ-TOK-GESTURE.08")
    fun firstRunTutorial() {
        screen.gestureTutorialTs = 0L
        feed()
        rule.waitUntil(5_000) { screen.viewModel.uiState.value.showGestureTutorial }
        assertBackClosesIt("first-run tutorial", { screen.viewModel.uiState.value.showGestureTutorial })
    }

    /** With no layer over the feed, Back is not swallowed by an invisible handler. */
    @Test
    fun aBareFeedDoesNotSwallowBack() {
        feed()
        assertEquals("no window but the activity", 1, screen.windowCount())
        screen.pressSystemBack()
        assertEquals("Back over the bare feed reached the activity", 1, screen.backPressesThatLeftTheApp)
    }

    /**
     * The first-run hint card is an in-window card, not a modal layer; this records what Back does
     * over it today (it reaches the activity) without asserting a requirement nobody has written.
     */
    @Test
    fun theHintCardIsNotAModalLayer() {
        feed()
        rule.runOnUiThread { screen.viewModel.maybeShowFirstRunHint(FirstRunHint.DOUBLE_TAP_ZOOM) }
        rule.waitForIdle()
        assertTrue("the hint card is shown", nodes("first_run_hint").isNotEmpty())
        screen.pressSystemBack()
        assertEquals(1, screen.backPressesThatLeftTheApp)
    }
}

/** Layers that are composed outside [PhoneModeScreen] in tests (see [PhoneScreenHarness]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class SystemBackClosesStandaloneLayerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)

    @Test
    @Requirement("REQ-TOK-FEATURE.09")
    fun folderResumeDialog() {
        var open by mutableStateOf(true)
        var stayed = 0
        screen.launch { _ ->
            if (open) {
                FolderResumeDialog(
                    prompt = ReloadPrompt(newCount = 3, firstNewIndex = 0, resumeIndex = 4, totalCount = 15),
                    onStartWithNewest = { open = false },
                    onContinueWhereLeftOff = { stayed++; open = false },
                )
            }
        }
        assertTrue(screen.windowCount() > 1)
        screen.pressSystemBack()
        assertEquals("Back leaves the app", 0, screen.backPressesThatLeftTheApp)
        assertEquals("Back means 'stay where I was'", 1, stayed)
        assertEquals(1, screen.windowCount())
    }

    @Test
    @Requirement("REQ-TOK-UX.02")
    fun settingsSheet() {
        var open by mutableStateOf(true)
        val settingsRepository = screen.settingsRepository
        val imageRepository: ImageRepository = screen.imageRepository
        val settingsViewModel = com.phototok.viewmodel.SettingsViewModel(settingsRepository, imageRepository)
        screen.launch { _ ->
            if (open) {
                SettingsSheet(onDismissRequest = { open = false }) {
                    com.phototok.ui.settings.SettingsScreen(viewModel = settingsViewModel)
                }
            }
        }
        rule.waitForIdle()
        assertTrue("the sheet is a window of its own", screen.windowCount() > 1)
        screen.pressSystemBack()
        rule.waitUntil(5_000) { !open }
        assertEquals("Back leaves the app", 0, screen.backPressesThatLeftTheApp)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun proModeHelpDialog() {
        var open by mutableStateOf(true)
        screen.launch { _ ->
            if (open) com.phototok.ui.components.ProModeHelpDialog(onDismiss = { open = false })
        }
        assertTrue(screen.windowCount() > 1)
        screen.pressSystemBack()
        assertEquals("Back leaves the app", 0, screen.backPressesThatLeftTheApp)
        assertTrue("still open", !open)
    }
}
