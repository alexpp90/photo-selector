package com.photoselectortoolbox.ui.fit

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.photoselector.core.model.ExifData
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** A photo as the selector lists it; landscape 3:2 unless told otherwise. */
fun fitPhoto(name: String, modified: Long, width: Int = 1500, height: Int = 1000) = ImageItem(
    uri = "content://test/folder/$name",
    fileName = name,
    fileSize = 1,
    lastModified = modified,
    mimeType = "image/jpeg",
    imageWidth = width,
    imageHeight = height,
)

val fitExif = ExifData(
    shutterSpeed = 1.0 / 250,
    aperture = 2.8,
    focalLength = 35.0,
    focalLength35mm = 52.0,
    iso = 400,
    lens = "FE 35mm F1.8",
    make = "SONY",
    model = "ILCE-6700",
    dateTimeOriginal = "2026:10:24 14:03:11",
)

/**
 * The settings store the selector reads, as a relaxed mock with the flows it collects. [seen]
 * is the set of hints already seen (all of them: no first-run guide, no hint cards).
 */
class FitSelectorSettings(
    seen: Set<SelectorHint> = SelectorHint.entries.toSet(),
    navHintSeen: Boolean = true,
    fullscreenHintSeen: Boolean = true,
    filingAction: FilingAction = FilingAction.COPY,
    filmstrip: Boolean = false,
    details: Boolean = true,
) {
    val seenHints = MutableStateFlow(seen)
    val navHintSeenFlow = MutableStateFlow(navHintSeen)
    val fullscreenHintSeenFlow = MutableStateFlow(fullscreenHintSeen)
    val customDestination = MutableStateFlow<String?>(null)

    val repository: SettingsRepository = mockk(relaxed = true) {
        every { selectorSortOrder } returns MutableStateFlow(SettingsRepository.DEFAULT_SELECTOR_SORT_ORDER)
        every { groupingEnabled } returns MutableStateFlow(false)
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { lastFolderUri } returns MutableStateFlow<String?>(null)
        every { this@mockk.filingAction } returns MutableStateFlow(filingAction)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { fullscreenInfoPinned } returns MutableStateFlow(false)
        every { overlayValuesVisible } returns MutableStateFlow(true)
        every { filmstripVisible } returns MutableStateFlow(filmstrip)
        every { detailsVisible } returns MutableStateFlow(details)
        every { hasSeenFullscreenGestureHint } returns fullscreenHintSeenFlow
        every { hasSeenNavHint } returns navHintSeenFlow
        every { seenHints } returns this@FitSelectorSettings.seenHints
        every { selectionFolderName } returns MutableStateFlow(FilingAction.DEFAULT_SELECTION_FOLDER)
        every { customSelectionDestinationUri } returns customDestination
        every { pendingDeleteJournal } returns MutableStateFlow<Set<String>>(emptySet())
        coEvery { markHintSeen(any()) } answers { val s = this@FitSelectorSettings.seenHints; s.value = s.value + firstArg<SelectorHint>() }
        coEvery { setHasSeenNavHint(any()) } answers { navHintSeenFlow.value = firstArg() }
        coEvery { setHasSeenFullscreenGestureHint(any()) } answers { fullscreenHintSeenFlow.value = firstArg() }
    }
}

/**
 * The real [SelectorViewModel] over mocked storage, opened on a folder of [photos] (none: the
 * empty-folder state; [openFolder] false: the landing state with no folder).
 */
fun fitSelectorViewModel(
    context: Context,
    appScope: CoroutineScope,
    settings: FitSelectorSettings = FitSelectorSettings(),
    photos: List<ImageItem> = listOf(fitPhoto("A.JPG", 3_000), fitPhoto("B.JPG", 2_000), fitPhoto("C.JPG", 1_000)),
    openFolder: Boolean = true,
    imageRepository: ImageRepository = fitImageRepository(),
): SelectorViewModel {
    val discovery = Channel<List<ImageItem>>(Channel.UNLIMITED)
    every { imageRepository.discoverImages(any()) } returns discovery.receiveAsFlow()
    val viewModel = SelectorViewModel(
        imageRepository = imageRepository,
        scanImagesUseCase = mockk<ScanImagesUseCase>(relaxed = true),
        cacheRepository = mockk<CacheRepository>(relaxed = true),
        settingsRepository = settings.repository,
        scoreDao = mockk<ScoreDao>(relaxed = true) { coEvery { getScore(any()) } returns null },
        appScope = appScope,
        context = context,
    )
    if (openFolder) {
        viewModel.selectFolder(Uri.parse("content://test/folder"))
        discovery.trySend(photos)
        discovery.close()
    }
    return viewModel
}

fun fitImageRepository(): ImageRepository = mockk(relaxed = true) {
    coEvery { openFolder(any(), any()) } returns "Trip"
    every { canTrash(any()) } returns true
    coEvery { getExifData(any(), any()) } returns fitExif
    coEvery { resolveDimensions(any(), any()) } returns emptyMap<String, ImageDimensions>()
}

/**
 * Runs [content] through every cell of [cells] and every entry of [variants] in one
 * composition, returning every violation found. The content is re-keyed per (cell, variant), so
 * `remember`ed screen state starts fresh; [prepare] then puts the screen into the variant.
 */
fun ComposeContentTestRule.fitMatrix(
    screen: String,
    cells: List<FitCell> = FitMatrix.cells,
    variants: List<String> = listOf(""),
    policy: FitPolicy = FitPolicy(),
    prepare: ComposeContentTestRule.(cell: FitCell, variant: String) -> Unit = { _, _ -> },
    content: @Composable (FitCell) -> Unit,
): List<FitViolation> {
    var current by mutableStateOf(cells.first() to variants.first())
    setContent {
        val (cell, _) = current
        FitWindow(cell) {
            PhotoSelectorToolboxTheme { key(current) { content(cell) } }
        }
    }
    val px48 = with(density) { 48.dp.roundToPx() }
    val found = mutableListOf<FitViolation>()
    for (cell in cells) for (variant in variants) {
        current = cell to variant
        waitForIdle()
        prepare(cell, variant)
        waitForIdle()
        val name = if (variant.isEmpty()) screen else "$screen/$variant"
        val window = onNodeWithTag(FIT_WINDOW_TAG, useUnmergedTree = true).fetchSemanticsNode()
        found += collectFitViolations(name, cell, window, px48, policy)
    }
    return found
}

/** Scrolls every scroll container in the window to its far end, so a variant can measure what lay below the fold. */
fun ComposeContentTestRule.scrollEveryContainerToEnd() {
    val ids = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy), useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.id }
    for (id in ids) {
        val node = onAllNodes(SemanticsMatcher("node $id") { it.id == id }, useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull() ?: continue
        val horizontal = SemanticsProperties.HorizontalScrollAxisRange in node.config
        onAllNodes(SemanticsMatcher("node $id") { it.id == id }, useUnmergedTree = true).onFirst()
            .performSemanticsAction(SemanticsActions.ScrollBy) {
                if (horizontal) it(100_000f, 0f) else it(0f, 100_000f)
            }
        waitForIdle()
    }
}

/** The variants of a scrolling screen: as it opens, and with every container scrolled to its end. */
val SCROLL_VARIANTS = listOf("top", "end")

fun ComposeContentTestRule.scrollVariant(variant: String) {
    if (variant.endsWith("end")) scrollEveryContainerToEnd()
}

fun ComposeContentTestRule.tagExists(tag: String) =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
