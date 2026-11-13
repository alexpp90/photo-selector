package com.photoselectortoolbox.ui.fit

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.R
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.DuplicateGroup
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.HeldDeleteCommitter
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.duplicates.DuplicateDetectionProgress
import com.photoselectortoolbox.domain.format.SelectorLabels
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.DebriefBuilder
import com.photoselectortoolbox.domain.learn.LearnState
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.domain.usecase.FindDuplicatesUseCase
import com.photoselectortoolbox.testing.strDe
import com.photoselectortoolbox.ui.duplicates.DuplicatesScreen
import com.photoselectortoolbox.ui.inspect.InspectScreenContent
import com.photoselectortoolbox.ui.selector.DeleteDialogBody
import com.photoselectortoolbox.ui.selector.DeleteDialogTitle
import com.photoselectortoolbox.ui.selector.SelectorScreen
import com.photoselectortoolbox.ui.settings.SettingsScreen
import com.photoselectortoolbox.ui.statistics.StatisticsScreen
import com.photoselectortoolbox.viewmodel.DuplicatesViewModel
import com.photoselectortoolbox.viewmodel.LearnTab
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frameduel in German (`[USER 2026-11-12]` decision #34, REQ-AND-THEME.06) at the reference
 * geometry, 1480 x 924 dp, at font scale 1.0 and 2.0: the main screens and the delete dialog
 * fit with their German words, the way [ScreenFitMatrixTest] holds them in English. German runs
 * 30-50 % longer, so a fixed width sized to an English word shows up here first. Each screen is
 * also proven to be German (one of its words, read from `values-de`) and to show no resource
 * object in place of a word (`Res(id=…)`, a UiText that reached the screen unresolved).
 *
 * A violation the English matrix already ledgers ([KnownFitDefects]) is not counted again; the
 * English matrix is the one that proves those markers still reproduce.
 */
@Requirement("REQ-AND-THEME.06", "REQ-AND-LAYOUT.01", "REQ-AND-INPUT.04")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "de-w1480dp-h924dp-land-xhdpi")
class GermanScreenFitTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<ViewModel>()

    private val photos = listOf(fitPhoto("A.JPG", 3_000), fitPhoto("B.JPG", 2_000), fitPhoto("C.JPG", 1_000))

    private val cells = listOf(FitCell(1480, 924, 1.0f), FitCell(1480, 924, 2.0f))

    private val policy = FitPolicy(
        dataStrings = setOf("A.JPG", "B.JPG", "C.JPG", "a.jpg", "Trip", "FE 35mm F1.8", "ILCE-6700", "Keepers", "Pixel 8", "FC3582"),
        sharedBounds = listOf(
            SharedBounds(Regex("^#column_(current|previous|next)$"), Regex("^#maximise_badge$"), "REQ-AND-LAYOUT.06"),
        ),
        autoSizeMin = mapOf("action_word" to 9.sp, com.photoselectortoolbox.ui.selector.SIDEBAR_LABEL_TAG to com.photoselectortoolbox.ui.selector.SidebarLabelMinSize),
    )

    @Before
    fun setUp() {
        installNoImageLoader(context)
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
        viewModels.forEach { it.viewModelScope.cancel() }
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> T.tracked(): T = also { viewModels += it }

    /** Every text and spoken label in the fit window, unmerged. */
    private fun shownWords(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.children.forEach(::walk)
        }
        walk(rule.onNodeWithTag(FIT_WINDOW_TAG, useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** The screen at both cells, then: fits, speaks German (shows [germanWord]), and shows no raw resource. */
    private fun check(
        screen: String,
        germanWord: String,
        variants: List<String> = SCROLL_VARIANTS,
        prepare: androidx.compose.ui.test.junit4.ComposeContentTestRule.(FitCell, String) -> Unit = { _, v -> scrollVariant(v) },
        content: @Composable (FitCell) -> Unit,
    ) {
        val violations = rule.fitMatrix(screen, cells = cells, variants = variants, policy = policy, prepare = prepare, content = content)
        val unexplained = violations.filter { v -> KnownFitDefects.all.none { it.explains(screen, v) || it.explains(screen.substringBefore('/'), v) } }
        assertEquals(
            "German fit violations on $screen:\n" + unexplained.joinToString("\n") { "  ${it.check} ${it.node} at ${it.cell.label}: ${it.detail}" },
            emptyList<FitViolation>(),
            unexplained,
        )
        val words = shownWords()
        val unresolved = words.filter { w -> UNRESOLVED.any { it in w } }
        assertEquals("a UiText reached $screen unresolved", emptyList<String>(), unresolved)
        assertTrue("$screen is not German: no \"$germanWord\" in $words", words.any { germanWord in it })
    }

    @Test
    fun `the selector fits in German`() {
        val vm = fitSelectorViewModel(context, appScope, photos = photos).tracked()
        check("selector", strDe(R.string.label_screen_cull)) { cell ->
            SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
    }

    @Test
    fun `the selector landing state fits in German`() {
        val vm = fitSelectorViewModel(context, appScope, openFolder = false).tracked()
        check("selector/landing", strDe(R.string.empty_select_folder)) { cell ->
            SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
    }

    @Test
    fun `the Learn debrief and its worked pair fit in German`() {
        val names = mapOf(CameraClass.PHONE to "Pixel 8", CameraClass.INTERCHANGEABLE to "ILCE-6700", CameraClass.DRONE to "FC3582")
        val shots = mutableListOf<ImageItem>()
        val facts = mutableMapOf<String, List<TechniqueFactEntity>>()
        var n = 0
        for ((c, count) in listOf(CameraClass.PHONE to 24, CameraClass.INTERCHANGEABLE to 22, CameraClass.DRONE to 3)) repeat(count) { i ->
            val p = fitPhoto("p${n++}.jpg", n * 1_000L).copy(
                exifData = fitExif,
                scanResult = ScanResult("x", sharpnessScore = if (i % 2 == 0) 120.0 else 30.0),
            )
            shots += p
            fun f(k: String, v: String) = TechniqueFactEntity(p.uri, k, v, 0.9, null, 1L, 1L, TechniqueFactDetector.DETECTOR_VERSION)
            facts[p.uri] = listOf(
                f(ScanFacts.MARKER, "1"), f(ScanFacts.CAMERA_CLASS, c.key), f(ScanFacts.CAMERA_MAKE, "Make"),
                f(ScanFacts.CAMERA_MODEL, names.getValue(c)),
                f("composition.horizon", if (i < 14) "level" else "tilted"), f("exposure.clipping", "well_exposed"),
            )
        }
        val learn = LearnState(
            folderUri = "content://test/folder",
            factsByUri = facts,
            debrief = DebriefBuilder.build(shots, facts, emptyMap(), emptyList(), partial = true),
        )
        check(
            "inspect/debrief",
            strDe(R.string.label_tab_debrief),
            variants = SCROLL_VARIANTS + "pair",
            prepare = { _, variant ->
                onNodeWithTag(LearnTab.DEBRIEF.tag).performClick()
                waitForIdle()
                if (variant == "pair") {
                    onAllNodesWithTag("debrief_open_pair").onFirst().performScrollTo().performClick()
                    waitForIdle()
                }
                scrollVariant(variant)
            },
        ) { cell ->
            InspectScreenContent(
                currentImage = shots.first(), images = shots, currentIndex = 0,
                windowSizeClass = cell.windowSizeClass,
                onNavigateToSelector = {}, onSelectImage = {}, onPrevious = {}, onNext = {},
                onSetRating = { _, _ -> }, learn = learn,
            )
        }
    }

    @Test
    fun `Setup fits in German`() {
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
                uri = trip, displayPath = listOf("Interner Speicher", "DCIM", "Trip"), name = "Trip",
                hasPersistedGrant = true, exists = true, canWrite = true,
            )
        }
        val vm = SettingsViewModel(settings, mockk<CacheRepository>(relaxed = true), images, context).tracked()
        check("settings", strDe(R.string.title_section_destination)) { SettingsScreen(viewModel = vm) }
    }

    @Test
    fun `Stats fits in German`() {
        val images: ImageRepository = mockk(relaxed = true) {
            coEvery { openFolder(any(), any()) } returns "Trip"
            coEvery { discoverAllImages(any()) } returns photos
            coEvery { getExifData(any(), any()) } answers {
                val i = photos.indexOfFirst { it.uri == secondArg<Uri>().toString() }
                fitExif.copy(iso = 100 * (i + 1), aperture = 2.8 + i, focalLength = 35.0 + 15 * i)
            }
        }
        val vm = StatisticsViewModel(images, context).also {
            it.ioDispatcher = dispatcher
            it.selectFolder(Uri.parse("content://test/folder"))
        }.tracked()
        rule.waitUntil(5_000) { !vm.uiState.value.isLoading && vm.uiState.value.imageCount == photos.size }
        check("statistics", strDe(R.string.label_stats_images)) { cell ->
            StatisticsScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
    }

    @Test
    fun `Dupes with a group found fits in German`() {
        val images: ImageRepository = mockk(relaxed = true) {
            coEvery { openFolder(any(), any()) } returns "Trip"
            coEvery { discoverAllImages(any()) } returns photos
        }
        val group = DuplicateGroup("hash1", listOf(photos[0].uri, photos[1].uri))
        val finder: FindDuplicatesUseCase = mockk {
            every { this@mockk.invoke(any()) } returns flowOf(DuplicateDetectionProgress(3, 3, listOf(group)))
        }
        val held = HeldDeleteCommitter(images, mockk(relaxed = true), mockk(relaxed = true), context)
        val vm = DuplicatesViewModel(finder, images, held, appScope, context, mockk(relaxed = true)).also {
            it.selectFolder(Uri.parse("content://test/folder"))
            it.startScan()
            it.toggleSelection(photos[1].uri)
        }.tracked()
        rule.waitUntil(5_000) { vm.uiState.value.duplicateGroups.isNotEmpty() && !vm.uiState.value.isScanning }
        check("duplicates/found", strDe(R.string.action_select_all_but_first)) { cell ->
            DuplicatesScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm)
        }
    }

    @Test
    fun `the delete dialog fits in German`() {
        val bundle = fitPhoto("DSC_0003.JPG", 1).copy(
            companions = listOf(
                CompanionFile("content://test/folder/DSC_0003.ARW", "DSC_0003.ARW", 1L, 1L),
                CompanionFile("content://test/folder/DSC_0003.xmp", "DSC_0003.xmp", 1L, 1L),
            ),
        )
        check("dialog/delete", "löschen", variants = listOf("")) {
            Column {
                DeleteDialogTitle(bundle)
                DeleteDialogBody(bundle, SelectorLabels.deleteConfirmationMessage(bundle.fileName))
            }
        }
    }

    private companion object {
        /** What a data-class [com.photoselectortoolbox.domain.text.UiText] prints when it is not resolved. */
        val UNRESOLVED = listOf("Res(id=", "Plural(id=", "Raw(value=", "Joined(parts=", "Date(pattern=")
    }
}
