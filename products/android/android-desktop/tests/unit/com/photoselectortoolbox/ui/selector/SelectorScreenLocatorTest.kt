package com.photoselectortoolbox.ui.selector

import android.net.Uri
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import java.time.LocalDateTime
import java.time.ZoneId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The whole user path to Sort and Go to photo, through the real screen and the real
 * ViewModel (`[USER 2026-11-04]`: "the option to jump to a position ... to a date ... to
 * change the sorting ... I think right of the current image might be good"): the locator row
 * right of the current frame, the `S` and `G` keys, the Sort menu that re-orders the folder,
 * the date picker that lands on a day. The chips' own geometry is in [SelectorLocatorRowTest];
 * this proves they are wired to what the screen does.
 */
@Requirement("REQ-AND-WORKFLOW.06", "REQ-AND-WORKFLOW.07")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class SelectorScreenLocatorTest {

    @get:Rule
    val rule = createComposeRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher)
    private val discovery = Channel<List<ImageItem>>(Channel.UNLIMITED)
    private val zone = ZoneId.of("UTC")

    private val sortOrderFlow = MutableStateFlow(SettingsRepository.DEFAULT_SELECTOR_SORT_ORDER)

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectorSortOrder } returns sortOrderFlow
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
        // Every first-run explanation seen, so the guide does not open over the screen.
        every { seenHints } returns MutableStateFlow(com.photoselectortoolbox.domain.guidance.SelectorHint.entries.toSet())
        every { selectionFolderName } returns MutableStateFlow(FilingAction.DEFAULT_SELECTION_FOLDER)
        every { pendingDeleteJournal } returns MutableStateFlow<Set<String>>(emptySet())
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { openFolder(any(), any()) } returns "Trip"
        every { canTrash(any()) } returns true
        coEvery { getExifData(any(), any()) } returns null
        coEvery { resolveDimensions(any(), any()) } returns emptyMap<String, ImageDimensions>()
    }

    private fun photo(name: String, taken: String) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = LocalDateTime.parse(taken).atZone(zone).toInstant().toEpochMilli(),
        mimeType = "image/jpeg",
        imageWidth = 1500,
        imageHeight = 1000,
    )

    private lateinit var viewModel: SelectorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { imageRepository.discoverImages(any()) } returns discovery.receiveAsFlow()
        viewModel = SelectorViewModel(
            imageRepository = imageRepository,
            scanImagesUseCase = mockk<ScanImagesUseCase>(relaxed = true),
            cacheRepository = mockk<CacheRepository>(relaxed = true),
            settingsRepository = settingsRepository,
            scoreDao = mockk<ScoreDao>(relaxed = true) { coEvery { getScore(any()) } returns null },
            appScope = appScope,
            context = ApplicationProvider.getApplicationContext(),
        )
        viewModel.zoneId = { zone }
        viewModel.selectFolder(Uri.parse("content://test/folder"))
        // Newest first: D (14 Oct), C (12 Oct), B (12 Oct), A (3 Oct).
        discovery.trySend(
            listOf(
                photo("A.JPG", "2026-10-03T09:00"),
                photo("B.JPG", "2026-10-12T08:00"),
                photo("C.JPG", "2026-10-12T18:00"),
                photo("D.JPG", "2026-10-14T10:00"),
            ),
        )
        discovery.close()
        rule.setContent {
            PhotoSelectorToolboxTheme {
                SelectorScreen(
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                    viewModel = viewModel,
                )
            }
        }
        rule.waitUntil(5_000) { tagExists("locator_row") }
    }

    @After
    fun tearDown() {
        appScope.cancel()
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun tagExists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    private fun names() = viewModel.uiState.value.images.map { it.fileName }

    private fun current() = viewModel.uiState.value.currentImage?.fileName

    private fun pressKey(key: Key) {
        rule.onNodeWithTag("selector_root", useUnmergedTree = true).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun description(tag: String) =
        tagged(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `the locator row sits in the control block right of the current frame`() {
        tagged("locator_row").assertIsDisplayed()
        val frame = tagged("selector.frame.current").fetchSemanticsNode().boundsInRoot
        val row = tagged("locator_row").fetchSemanticsNode().boundsInRoot
        assertTrue("row $row is not right of the current frame $frame", row.left >= frame.right)
        assertEquals("Go to photo, 1 of 4", description("go_chip"))
        assertEquals("Sort order: Newest first. Change", description("sort_chip"))
    }

    @Test
    fun `the sort chip re-orders the folder and keeps the photo on screen`() {
        viewModel.navigateToImage(2) // B.JPG, 12 Oct morning
        assertEquals("B.JPG", current())

        tagged("sort_chip").performClick()
        tagged("sort_oldest_first").assertIsDisplayed()
        tagged("sort_oldest_first").performClick()
        rule.waitForIdle()

        assertEquals(listOf("A.JPG", "B.JPG", "C.JPG", "D.JPG"), names())
        assertEquals("the frame being judged stays", "B.JPG", current())
        assertEquals("Sort order: Oldest first. Change", description("sort_chip"))
        assertFalse("the menu closes on a pick", tagExists("sort_oldest_first"))
    }

    @Test
    fun `the go chip opens Go to photo and a typed date lands on that day`() {
        tagged("go_chip").performClick()
        tagged("go_to_photo_dialog").assertIsDisplayed()

        tagged("go_to_photo_field").performTextInput("3.10.2026")
        tagged("go_to_photo_go").performClick()
        rule.waitForIdle()

        assertEquals("A.JPG", current())
        assertFalse("the dialog closes on a jump", tagExists("go_to_photo_dialog"))
    }

    @Test
    fun `the calendar button picks a day and jumps to it at once`() {
        tagged("go_chip").performClick()
        tagged("go_to_photo_calendar").performClick()

        val day = rule.onAllNodesWithTag("go_to_photo_picker", useUnmergedTree = true)
        assertEquals(1, day.fetchSemanticsNodes().size)
        rule.onAllNodes(
            androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("go_to_photo_picker")) and
                androidx.compose.ui.test.hasText("October 12, 2026", substring = true) and
                androidx.compose.ui.test.hasClickAction(),
            useUnmergedTree = true,
        ).onFirst().also { it.performScrollTo(); it.performClick() }
        rule.waitForIdle()

        // The first photo of 12 Oct in the current (newest first) order is the evening one.
        assertEquals("C.JPG", current())
        assertFalse(tagExists("go_to_photo_dialog"))
    }

    @Test
    fun `S opens the sort menu, and while it is open M files nothing`() {
        pressKey(Key.S)
        tagged("sort_newest_first").assertIsDisplayed()

        pressKey(Key.M)
        pressKey(Key.One)

        assertEquals("a key behind the open menu must not file", 4, names().size)
        assertEquals(null, viewModel.uiState.value.maximisedFrame)
        coVerify(exactly = 0) { imageRepository.moveBundle(any(), any(), any(), any()) }
    }

    @Test
    fun `S does not cycle the order, picking does`() {
        pressKey(Key.S)
        assertEquals("opening is not choosing", listOf("D.JPG", "C.JPG", "B.JPG", "A.JPG"), names())

        tagged("sort_file_name").performClick()
        rule.waitForIdle()

        assertEquals(listOf("A.JPG", "B.JPG", "C.JPG", "D.JPG"), names())
    }

    @Test
    fun `the overflow menu still carries the same sort group and Go to photo`() {
        // Both overflow entries stay (the locator row is dropped in small windows).
        rule.onAllNodes(
            androidx.compose.ui.test.hasText(com.photoselectortoolbox.domain.guidance.SidebarAction.MORE.label),
            useUnmergedTree = true,
        ).onFirst().performClick()
        tagged("sort_oldest_first").assertIsDisplayed()
        tagged("menu_go_to_photo").assertIsDisplayed()
    }
}
