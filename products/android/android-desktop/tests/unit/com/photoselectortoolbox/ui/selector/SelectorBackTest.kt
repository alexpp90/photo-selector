package com.photoselectortoolbox.ui.selector

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.guidance.SelectorHint
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * System Back does what Escape does (device lab 2026-11-06: Back with the layout guide open
 * closed the whole app). One Back closes exactly the topmost layer drawn in the selector's own
 * window; at the bare selector Back is not consumed, so the system takes it and leaves the app.
 *
 * Through the real screen and the real ViewModel. Dialogs, the Sort menu, the sheet and the
 * fullscreen viewer are windows that take Back themselves; the last test pins that this screen
 * does not claim it while one is up, which is what keeps a press from closing two layers.
 */
@Requirement("REQ-AND-LAYOUT.25")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class SelectorBackTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher)
    private val discovery = Channel<List<ImageItem>>(Channel.UNLIMITED)

    private val seenHintsFlow = MutableStateFlow<Set<SelectorHint>>(SelectorHint.entries.toSet())

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectorSortOrder } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTOR_SORT_ORDER)
        every { groupingEnabled } returns MutableStateFlow(false)
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { filingAction } returns MutableStateFlow(FilingAction.COPY)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { fullscreenInfoPinned } returns MutableStateFlow(false)
        every { overlayValuesVisible } returns MutableStateFlow(true)
        every { filmstripVisible } returns MutableStateFlow(false)
        every { detailsVisible } returns MutableStateFlow(true)
        every { hasSeenFullscreenGestureHint } returns MutableStateFlow(true)
        every { hasSeenNavHint } returns MutableStateFlow(true)
        every { seenHints } returns seenHintsFlow
        every { selectionFolderName } returns MutableStateFlow(FilingAction.DEFAULT_SELECTION_FOLDER)
        every { pendingDeleteJournal } returns MutableStateFlow<Set<String>>(emptySet())
        // The real store re-emits the set; without this the first-launch guide would reopen at once.
        coEvery { markHintSeen(any()) } answers { seenHintsFlow.value = seenHintsFlow.value + firstArg<SelectorHint>() }
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { openFolder(any(), any()) } returns "Trip"
        every { canTrash(any()) } returns true
        coEvery { getExifData(any(), any()) } returns null
        coEvery { resolveDimensions(any(), any()) } returns emptyMap<String, ImageDimensions>()
    }

    private lateinit var viewModel: SelectorViewModel

    private fun photo(name: String, modified: Long) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
        imageWidth = 1500,
        imageHeight = 1000,
    )

    /** Hosts the real selector at 1280 x 800 dp; [guideSeen] false is a first launch. */
    private fun host(guideSeen: Boolean = true) {
        Dispatchers.setMain(testDispatcher)
        if (!guideSeen) seenHintsFlow.value = emptySet()
        every { imageRepository.discoverImages(any()) } returns discovery.receiveAsFlow()
        viewModel = SelectorViewModel(
            imageRepository = imageRepository,
            scanImagesUseCase = mockk<ScanImagesUseCase>(relaxed = true),
            cacheRepository = mockk<CacheRepository>(relaxed = true),
            settingsRepository = settingsRepository,
            scoreDao = mockk<ScoreDao>(relaxed = true) { coEvery { getScore(any()) } returns null },
            appScope = appScope,
            context = ApplicationProvider.getApplicationContext(),
        ).also { it.ioDispatcher = testDispatcher }
        viewModel.selectFolder(Uri.parse("content://test/folder"))
        discovery.trySend(listOf(photo("A.JPG", 3_000), photo("B.JPG", 2_000), photo("C.JPG", 1_000)))
        discovery.close()
        rule.setContent {
            PhotoSelectorToolboxTheme {
                SelectorScreen(
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                    viewModel = viewModel,
                )
            }
        }
        rule.waitUntil(5_000) { tagExists("selector.frame.current") }
    }

    @After
    fun tearDown() {
        appScope.cancel()
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun tagExists(tag: String) =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun pressKey(key: Key) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun guideOpen() = tagExists("selector_coach_overlay")

    private fun maximised() = viewModel.uiState.value.maximisedFrame

    private fun backEnabled(): Boolean {
        var enabled = false
        rule.activityRule.scenario.onActivity { enabled = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    /** One system Back, as the activity's dispatcher routes it; true when this screen took it. */
    private fun back(): Boolean {
        val consumed = backEnabled()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        // The guide fades out over 150 ms; wait for it rather than for a frame.
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        return consumed
    }

    private fun activityFinishing(): Boolean {
        var finishing = false
        rule.activityRule.scenario.onActivity { finishing = it.isFinishing }
        return finishing
    }

    private fun openContextMenu() {
        val frame = if (maximised() != null) "maximised_frame" else "selector.frame.current"
        rule.onNodeWithTag(frame, useUnmergedTree = true).performTouchInput { longClick() }
        rule.waitUntil(5_000) { tagExists("selector_context_menu") }
    }

    @Test
    fun `Back at the bare selector is not consumed and leaves the app`() {
        host()
        assertFalse("nothing is open: this screen must not claim Back", backEnabled())

        back()

        assertTrue("the system took Back", activityFinishing())
    }

    @Test
    fun `Back closes the layout guide and does not leave the app`() {
        host()
        pressKey(Key.Slash)
        assertTrue("the guide is open", guideOpen())

        assertTrue("Back is claimed while the guide is open", back())

        assertFalse("the guide is closed", guideOpen())
        assertFalse("one Back closes the guide, not the app", activityFinishing())
        assertFalse("with the guide closed Back is the system's again", backEnabled())
    }

    @Test
    fun `Back closes the first-launch guide and marks it seen like Skip`() {
        host(guideSeen = false)
        rule.waitUntil(5_000) { guideOpen() }

        assertTrue(back())

        assertFalse(guideOpen())
        assertFalse(activityFinishing())
        coVerify { settingsRepository.markHintSeen(SelectorHint.TOUR) }
    }

    @Test
    fun `Escape closes the guide that opened by itself at first launch`() {
        host(guideSeen = false)
        rule.waitUntil(5_000) { guideOpen() }

        pressKey(Key.Escape)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()

        assertFalse("Esc closes the guide with no click first", guideOpen())
    }

    @Test
    fun `Escape closes the guide opened with the question mark`() {
        host()
        pressKey(Key.Slash)
        assertTrue(guideOpen())

        pressKey(Key.Escape)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()

        assertFalse(guideOpen())
    }

    @Test
    fun `Back leaves a maximised frame and then the next Back is not consumed`() {
        host()
        pressKey(Key.Two)
        assertNotNull("the frame is maximised", maximised())

        assertTrue(back())

        assertNull("one Back returns to three-up", maximised())
        assertTrue(tagExists("selector.frame.current"))
        assertFalse(activityFinishing())
        assertFalse(backEnabled())
    }

    @Test
    fun `Back closes the context menu first, then the maximised frame behind it`() {
        host()
        pressKey(Key.Two)
        openContextMenu()
        assertNotNull(maximised())

        assertTrue(back())
        assertFalse("the menu is the top layer", tagExists("selector_context_menu"))
        assertNotNull("one Back closes one layer: the frame stays maximised", maximised())

        assertTrue(back())
        assertNull(maximised())
        assertFalse(back())
        assertTrue("only now does Back leave the app", activityFinishing())
    }

    @Test
    fun `Back closes the context menu over the three-up layout and keeps the selection`() {
        host()
        openContextMenu()

        assertTrue(back())

        assertFalse(tagExists("selector_context_menu"))
        assertEquals("A.JPG", viewModel.uiState.value.currentImage?.fileName)
        assertFalse(activityFinishing())
    }

    @Test
    fun `this screen does not claim Back while a dialog or menu of its own window is open`() {
        host()
        rule.onNodeWithTag("go_chip", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { tagExists("go_to_photo_dialog") }
        assertFalse("Go to photo is a Dialog and takes Back itself", backEnabled())

        pressKey(Key.Escape)
        rule.waitUntil(5_000) { !tagExists("go_to_photo_dialog") }

        pressKey(Key.S)
        assertTrue(tagExists("sort_newest_first"))
        assertFalse("the Sort menu is a focusable popup and takes Back itself", backEnabled())
    }
}
