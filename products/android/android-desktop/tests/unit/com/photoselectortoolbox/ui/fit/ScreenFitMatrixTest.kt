package com.photoselectortoolbox.ui.fit

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.duplicates.DuplicateDetectionProgress
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import com.photoselectortoolbox.ui.duplicates.DuplicatesScreen
import com.photoselectortoolbox.ui.inspect.InspectScreenContent
import com.photoselectortoolbox.ui.inspect.LearnTab
import com.photoselectortoolbox.ui.selector.SelectorScreen
import com.photoselectortoolbox.ui.settings.SettingsScreen
import com.photoselectortoolbox.ui.statistics.StatisticsScreen
import com.photoselectortoolbox.viewmodel.DuplicatesViewModel
import com.photoselectortoolbox.viewmodel.SettingsViewModel
import com.photoselectortoolbox.viewmodel.StatisticsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen outside and inside the selector, at font scale 1.0 / 1.3 / 2.0 x six window
 * sizes (1480x924 down to the compact 360x720 of REQ-AND-LAYOUT.02): no text overflows or is
 * cut, no word is broken mid-word, every control is a 48 dp node (REQ-AND-INPUT.04) inside the
 * window, and no two controls share bounds (REQ-AND-LAYOUT.09). What `main` gets wrong today is
 * listed, with its ledger id and cause, in [KnownFitDefects].
 *
 * Robolectric with native graphics: under LEGACY, text does not grow with the font scale.
 */
@Requirement("REQ-AND-LAYOUT.01", "REQ-AND-LAYOUT.02", "REQ-AND-LAYOUT.09", "REQ-AND-INPUT.04")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = FitMatrix.QUALIFIERS)
class ScreenFitMatrixTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<ViewModel>()

    private val photos = listOf(fitPhoto("A.JPG", 3_000), fitPhoto("B.JPG", 2_000), fitPhoto("C.JPG", 1_000))

    /** User data the fixtures put on screen: these may be ellipsised, chrome words may not. */
    private val policy = FitPolicy(
        dataStrings = setOf("A.JPG", "B.JPG", "C.JPG", "a.jpg", "Trip", "FE 35mm F1.8", "ILCE-6700", "Keepers"),
        sharedBounds = listOf(
            // REQ-AND-LAYOUT.06: each frame carries its 48 dp maximise badge inside its bounds.
            SharedBounds(Regex("^#column_(current|previous|next)$"), Regex("^#maximise_badge$"), "REQ-AND-LAYOUT.06"),
        ),
        // REQ-AND-LAYOUT.07: the action word steps down from 13 sp to 9 sp before it may ellipsise.
        autoSizeMin = mapOf("action_word" to 9.sp),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> T.tracked(): T = also { viewModels += it }

    /** The screen at every cell, as it opens and scrolled to its end. */
    private fun matrix(screen: String, content: @Composable (FitCell) -> Unit) = rule.fitMatrix(
        screen, variants = SCROLL_VARIANTS, policy = policy, prepare = { _, v -> scrollVariant(v) }, content = content,
    )

    private fun check(screen: String, violations: List<FitViolation>) =
        assertFitsExceptKnown(screen, violations, KnownFitDefects.all)

    // ── The selector ───────────────────────────────────────────────────────

    @Test
    fun `the selector with photos fits every window and font scale`() {
        val vm = fitSelectorViewModel(context, appScope, photos = photos).tracked()
        val v = matrix("selector") { cell ->
            SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("selector", v)
    }

    @Test
    fun `the selector with the filmstrip on and the details off fits every window and font scale`() {
        val settings = FitSelectorSettings(filmstrip = true, details = false)
        val vm = fitSelectorViewModel(context, appScope, settings = settings, photos = photos).tracked()
        val v = matrix("selector") { cell ->
            SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("selector", v)
    }

    @Test
    fun `the selector landing state fits every window and font scale`() {
        val vm = fitSelectorViewModel(context, appScope, openFolder = false).tracked()
        val v = matrix("selector/landing") { cell ->
            SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("selector/landing", v)
    }

    @Test
    fun `the selector empty-folder state fits every window and font scale`() {
        val vm = fitSelectorViewModel(context, appScope, photos = emptyList()).tracked()
        val v = matrix("selector/empty") { cell ->
            SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("selector/empty", v)
    }

    // ── Learn (Inspect) ────────────────────────────────────────────────────

    @Test
    fun `Inspect and every Learn tab fit every window and font scale`() {
        val photo = fitPhoto("a.jpg", 1).copy(rating = 3, exifData = fitExif)
        val v = rule.fitMatrix(
            "inspect",
            variants = LearnTab.entries.flatMap { tab -> SCROLL_VARIANTS.map { "${tab.tag}/$it" } },
            policy = policy,
            prepare = { _, variant ->
                onNodeWithTag(variant.substringBefore('/')).performClick()
                waitForIdle()
                scrollVariant(variant)
            },
        ) { cell ->
            InspectScreenContent(
                currentImage = photo, images = listOf(photo), currentIndex = 0,
                windowSizeClass = cell.windowSizeClass,
                onNavigateToSelector = {}, onSelectImage = {}, onPrevious = {}, onNext = {},
                onSetRating = { _, _ -> },
            )
        }
        check("inspect", v)
    }

    // ── Settings ───────────────────────────────────────────────────────────

    @Test
    fun `Settings fits every window and font scale`() {
        val trip = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FTrip"
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { selectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTION_FOLDER_NAME)
            every { secondarySelectionFolderName } returns MutableStateFlow(SettingsRepository.DEFAULT_SECONDARY_SELECTION_FOLDER_NAME)
            every { sortingEnabled } returns MutableStateFlow(SettingsRepository.DEFAULT_SORTING_ENABLED)
            every { customSelectionDestinationUri } returns MutableStateFlow<String?>(null)
            every { lastFolderUri } returns MutableStateFlow<String?>(trip)
            every { groupingEnabled } returns MutableStateFlow(true)
            every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
            every { analysisThreadCount } returns MutableStateFlow(4)
            every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
            every { filingAction } returns MutableStateFlow(FilingAction.DEFAULT)
            every { tvDisplayScaleEnabled } returns MutableStateFlow(false)
        }
        val images: ImageRepository = mockk(relaxed = true) {
            coEvery { describeFolder(any(), any()) } returns FolderDescription(
                uri = trip, displayPath = listOf("Internal storage", "DCIM", "Trip"), name = "Trip",
                hasPersistedGrant = true, exists = true, canWrite = true,
            )
        }
        val vm = SettingsViewModel(settings, mockk<CacheRepository>(relaxed = true), images, context).tracked()
        val v = matrix("settings") { SettingsScreen(viewModel = vm) }
        check("settings", v)
    }

    // ── Statistics ─────────────────────────────────────────────────────────

    private fun statisticsViewModel(open: Boolean): StatisticsViewModel {
        val images: ImageRepository = mockk(relaxed = true) {
            coEvery { openFolder(any(), any()) } returns "Trip"
            coEvery { discoverAllImages(any()) } returns photos
            coEvery { getExifData(any(), any()) } answers {
                val n = photos.indexOfFirst { it.uri == secondArg<Uri>().toString() }
                fitExif.copy(iso = 100 * (n + 1), aperture = 2.8 + n, focalLength = 35.0 + 15 * n)
            }
        }
        return StatisticsViewModel(images, context).also {
            it.ioDispatcher = dispatcher
            if (open) it.selectFolder(Uri.parse("content://test/folder"))
        }.tracked()
    }

    @Test
    fun `Statistics with a scanned folder fits every window and font scale`() {
        val vm = statisticsViewModel(open = true)
        rule.waitUntil(5_000) { !vm.uiState.value.isLoading && vm.uiState.value.imageCount == photos.size }
        val v = matrix("statistics") { cell ->
            StatisticsScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("statistics", v)
    }

    @Test
    fun `Statistics with no folder fits every window and font scale`() {
        val vm = statisticsViewModel(open = false)
        val v = matrix("statistics/empty") { cell ->
            StatisticsScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("statistics/empty", v)
    }

    // ── Duplicates ─────────────────────────────────────────────────────────

    private fun duplicatesViewModel(open: Boolean, scanned: Boolean): DuplicatesViewModel {
        val images: ImageRepository = mockk(relaxed = true) {
            coEvery { openFolder(any(), any()) } returns "Trip"
            coEvery { discoverAllImages(any()) } returns photos
        }
        val group = DuplicateGroup("hash1", listOf(photos[0].uri, photos[1].uri))
        val finder: FindDuplicatesUseCase = mockk {
            every { this@mockk.invoke(any()) } returns flowOf(DuplicateDetectionProgress(3, 3, listOf(group)))
        }
        return DuplicatesViewModel(finder, images, context).also {
            if (open) it.selectFolder(Uri.parse("content://test/folder"))
            if (scanned) {
                it.startScan()
                it.toggleSelection(photos[1].uri)
            }
        }.tracked()
    }

    @Test
    fun `Duplicates before a scan fits every window and font scale`() {
        val vm = duplicatesViewModel(open = true, scanned = false)
        val v = matrix("duplicates") { cell ->
            DuplicatesScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("duplicates", v)
    }

    @Test
    fun `Duplicates with a group found and a copy selected fits every window and font scale`() {
        val vm = duplicatesViewModel(open = true, scanned = true)
        rule.waitUntil(5_000) { vm.uiState.value.duplicateGroups.isNotEmpty() && !vm.uiState.value.isScanning }
        val v = matrix("duplicates/found") { cell ->
            DuplicatesScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("duplicates/found", v)
    }

    @Test
    fun `Duplicates with no folder fits every window and font scale`() {
        val vm = duplicatesViewModel(open = false, scanned = false)
        val v = matrix("duplicates/empty") { cell ->
            DuplicatesScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
        check("duplicates/empty", v)
    }

}
