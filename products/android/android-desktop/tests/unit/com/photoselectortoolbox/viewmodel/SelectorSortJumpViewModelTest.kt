package com.photoselectortoolbox.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persisted sort order and "Go to photo", through [SelectorViewModel].
 *
 * Unlike the other ViewModel suites this one runs on the **default** order
 * (newest first), because the default is the thing under test. Discovery is a
 * channel the test closes, so enumeration can settle on demand.
 */
@Requirement("REQ-AND-WORKFLOW.06", "REQ-AND-WORKFLOW.07")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectorSortJumpViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher)

    private val sortOrderFlow = MutableStateFlow(SettingsRepository.DEFAULT_SELECTOR_SORT_ORDER)
    private val groupingEnabledFlow = MutableStateFlow(false)
    private val lastFolderUriFlow = MutableStateFlow<String?>(null)

    private var discovery = Channel<List<ImageItem>>(Channel.UNLIMITED)

    private companion object {
        const val FOLDER_URI = "content://test/folder"
    }

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectorSortOrder } returns sortOrderFlow
        every { groupingEnabled } returns groupingEnabledFlow
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { lastFolderUri } returns lastFolderUriFlow
        every { filingAction } returns MutableStateFlow(FilingAction.COPY)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { fullscreenInfoPinned } returns MutableStateFlow(false)
        every { overlayValuesVisible } returns MutableStateFlow(true)
        every { filmstripVisible } returns MutableStateFlow(true)
        every { detailsVisible } returns MutableStateFlow(true)
        every { hasSeenFullscreenGestureHint } returns MutableStateFlow(true)
        every { hasSeenNavHint } returns MutableStateFlow(true)
        every { selectionFolderName } returns MutableStateFlow(FilingAction.DEFAULT_SELECTION_FOLDER)
        every { pendingDeleteJournal } returns MutableStateFlow<Set<String>>(emptySet())
    }

    private val imageRepository: ImageRepository = mockk(relaxed = true) {
        coEvery { openFolder(any(), any()) } returns "Test Shoot"
        every { canTrash(any()) } returns true
        coEvery { getExifData(any(), any()) } returns null
        coEvery { resolveDimensions(any(), any()) } returns emptyMap<String, ImageDimensions>()
        coEvery { deleteImage(any(), any()) } returns true
    }

    private val scanImagesUseCase: ScanImagesUseCase = mockk(relaxed = true)
    private val cacheRepository: CacheRepository = mockk(relaxed = true)
    private val scoreDao: ScoreDao = mockk(relaxed = true) {
        coEvery { getScore(any()) } returns null
    }

    private fun image(name: String, modified: Long, sharpness: Double? = null, rating: Int = 0) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = modified,
        mimeType = "image/jpeg",
        scanResult = sharpness?.let { ScanResult(filePath = "content://test/folder/$name", sharpnessScore = it) },
        rating = rating,
    )

    private val viewModels = mutableListOf<SelectorViewModel>()

    private fun open(): SelectorViewModel {
        every { imageRepository.discoverImages(any()) } returns discovery.receiveAsFlow()
        val viewModel = SelectorViewModel(
            imageRepository = imageRepository,
            scanImagesUseCase = scanImagesUseCase,
            cacheRepository = cacheRepository,
            settingsRepository = settingsRepository,
            scoreDao = scoreDao,
            appScope = appScope,
            context = ApplicationProvider.getApplicationContext(),
        )
        // The score restore (`withContext(ioDispatcher)` on viewModelScope) and the dimension
        // backfill ran on Dispatchers.IO and resumed on Main from an IO thread, which raced
        // `resetMain` in the teardown: "Dispatchers.Main is used concurrently with setting it"
        // (TestMainDispatcher.kt:72, CI run 41781784118). On the test dispatcher nothing leaves it.
        viewModel.ioDispatcher = testDispatcher
        viewModels.add(viewModel)
        viewModel.selectFolder(Uri.parse(FOLDER_URI))
        return viewModel
    }

    private fun SelectorViewModel.names() = uiState.value.images.map { it.fileName }

    private fun SelectorViewModel.current() = uiState.value.currentImage?.fileName

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        discovery.close()
        appScope.cancel()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    // ── The default order ────────────────────────────────────────────────

    @Test
    fun `a folder opens newest first by default`() = runTest {
        val viewModel = open()
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 3_000)))

        assertEquals(SelectorSortOrder.NEWEST_FIRST, viewModel.uiState.value.sortOrder)
        assertEquals(listOf("IMG_3", "IMG_2", "IMG_1"), viewModel.names())
        assertEquals("IMG_3", viewModel.current())
    }

    @Test
    fun `the persisted order is what the folder opens in`() = runTest {
        sortOrderFlow.value = SelectorSortOrder.OLDEST_FIRST
        val viewModel = open()
        discovery.send(listOf(image("IMG_3", 3_000), image("IMG_1", 1_000), image("IMG_2", 2_000)))

        assertEquals(listOf("IMG_1", "IMG_2", "IMG_3"), viewModel.names())
    }

    @Test
    fun `a persisted order that arrives after the first batch re-orders keeping the frame`() = runTest {
        val viewModel = open()
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 3_000)))
        viewModel.navigateToImage(1)

        sortOrderFlow.value = SelectorSortOrder.OLDEST_FIRST

        assertEquals(listOf("IMG_1", "IMG_2", "IMG_3"), viewModel.names())
        assertEquals("IMG_2", viewModel.current())
    }

    // ── The settle re-order ──────────────────────────────────────────────

    /** Two batches: the second brings a photo newer than anything in the first. */
    private suspend fun enumerateWithLateNewestPhoto() {
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000)))
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_9", 9_000)))
    }

    @Test
    fun `batches append in order and only the settle puts the whole folder in order`() = runTest {
        val viewModel = open()
        enumerateWithLateNewestPhoto()

        // Append-only while enumerating: the newer photo joins at the end.
        assertEquals(listOf("IMG_2", "IMG_1", "IMG_9"), viewModel.names())
        assertTrue(viewModel.uiState.value.isEnumerating)

        discovery.close()

        assertEquals(listOf("IMG_9", "IMG_2", "IMG_1"), viewModel.names())
        assertFalse(viewModel.uiState.value.isEnumerating)
    }

    @Test
    fun `an untouched session lands on the newest photo at settle`() = runTest {
        val viewModel = open()
        enumerateWithLateNewestPhoto()
        assertEquals("IMG_2", viewModel.current())

        discovery.close()

        assertEquals("IMG_9", viewModel.current())
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `a photographer who has navigated keeps the frame on screen at settle`() = runTest {
        val viewModel = open()
        enumerateWithLateNewestPhoto()
        viewModel.navigateNext()
        assertEquals("IMG_1", viewModel.current())

        discovery.close()

        assertEquals(listOf("IMG_9", "IMG_2", "IMG_1"), viewModel.names())
        assertEquals("IMG_1", viewModel.current())
        assertEquals(2, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `a rating on frame 1 counts as acting, so settle keeps that frame`() = runTest {
        val viewModel = open()
        enumerateWithLateNewestPhoto()
        viewModel.setRatingForCurrent(4)

        discovery.close()

        assertEquals("IMG_2", viewModel.current())
    }

    @Test
    fun `a folder already in order is not re-ordered at settle`() = runTest {
        val viewModel = open()
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000)))
        val before = viewModel.uiState.value.images

        discovery.close()

        assertEquals(before, viewModel.uiState.value.images)
        assertEquals("IMG_2", viewModel.current())
    }

    @Test
    fun `settle leaves worst-first triage alone`() = runTest {
        val viewModel = open()
        discovery.send(
            listOf(image("IMG_1", 1_000, sharpness = 50.0), image("IMG_2", 2_000, sharpness = 10.0)),
        )
        viewModel.toggleSortWorstFirst()
        assertEquals(listOf("IMG_2", "IMG_1"), viewModel.names())

        discovery.close()

        assertEquals(listOf("IMG_2", "IMG_1"), viewModel.names())
        assertTrue(viewModel.uiState.value.sortWorstFirst)
    }

    // ── Changing the order ───────────────────────────────────────────────

    @Test
    fun `changing the order keeps the frame on screen by URI and persists the choice`() = runTest {
        val viewModel = open()
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 3_000)))
        discovery.close()
        viewModel.navigateToImage(1)
        assertEquals("IMG_2", viewModel.current())

        viewModel.setSortOrder(SelectorSortOrder.FILE_NAME)

        assertEquals(listOf("IMG_1", "IMG_2", "IMG_3"), viewModel.names())
        assertEquals("IMG_2", viewModel.current())
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(SelectorSortOrder.FILE_NAME, viewModel.uiState.value.sortOrder)
        coVerify { settingsRepository.setSelectorSortOrder(SelectorSortOrder.FILE_NAME) }
    }

    @Test
    fun `leaving worst-first returns to the persisted order, not the file name`() = runTest {
        val viewModel = open()
        discovery.send(
            listOf(
                image("IMG_1", 1_000, sharpness = 50.0),
                image("IMG_2", 2_000, sharpness = 10.0),
                image("IMG_3", 3_000, sharpness = 30.0),
            ),
        )
        discovery.close()

        viewModel.toggleSortWorstFirst()
        assertEquals(listOf("IMG_2", "IMG_3", "IMG_1"), viewModel.names())
        assertEquals(0, viewModel.uiState.value.currentIndex)
        viewModel.navigateToImage(1)
        assertEquals("IMG_3", viewModel.current())

        viewModel.toggleSortWorstFirst()

        // Newest first is the persisted order — file-name order would be 1, 2, 3.
        assertEquals(listOf("IMG_3", "IMG_2", "IMG_1"), viewModel.names())
        assertEquals("the frame on screen stays on screen", "IMG_3", viewModel.current())
        assertFalse(viewModel.uiState.value.sortWorstFirst)
    }

    @Test
    fun `picking an order while worst-first is on leaves worst-first`() = runTest {
        val viewModel = open()
        discovery.send(
            listOf(image("IMG_1", 1_000, sharpness = 50.0), image("IMG_2", 2_000, sharpness = 10.0)),
        )
        discovery.close()
        viewModel.toggleSortWorstFirst()

        viewModel.setSortOrder(SelectorSortOrder.OLDEST_FIRST)

        assertFalse(viewModel.uiState.value.sortWorstFirst)
        assertEquals(listOf("IMG_1", "IMG_2"), viewModel.names())
    }

    // ── Groups and undo slots follow the order ───────────────────────────

    private fun SelectorViewModel.groupedUris(): Set<Set<String>> =
        uiState.value.groups.map { group -> group.map { uiState.value.images[it].uri }.toSet() }.toSet()

    private fun awaitGroups(viewModel: SelectorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.uiState.value.groups.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("grouping finished", viewModel.uiState.value.groups.isNotEmpty())
    }

    @Test
    fun `burst groups are remapped by URI when the order changes`() = runTest {
        groupingEnabledFlow.value = true
        val viewModel = open()
        // IMG_1 and IMG_2 are one burst (same prefix, a second apart); IMG_3 is far later.
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 900_000)))
        discovery.close()
        awaitGroups(viewModel)
        val expected = setOf(
            setOf("content://test/folder/IMG_1", "content://test/folder/IMG_2"),
            setOf("content://test/folder/IMG_3"),
        )
        assertEquals(expected, viewModel.groupedUris())

        viewModel.setSortOrder(SelectorSortOrder.OLDEST_FIRST)

        assertEquals(expected, viewModel.groupedUris())
        viewModel.uiState.value.groups.forEach { group -> assertEquals(group.sorted(), group) }
    }

    @Test
    fun `groups lose a deleted frame instead of pointing at its neighbour`() = runTest {
        groupingEnabledFlow.value = true
        val viewModel = open()
        discovery.send(listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 900_000)))
        discovery.close()
        awaitGroups(viewModel)
        // Newest first: IMG_3, IMG_2, IMG_1. Delete IMG_3 (frame 1).
        viewModel.deleteCurrentImage()

        assertEquals(
            setOf(setOf("content://test/folder/IMG_1", "content://test/folder/IMG_2")),
            viewModel.groupedUris(),
        )
    }

    @Test
    fun `a delete undone after the order changed lands in order`() = runTest {
        val viewModel = open()
        discovery.send(
            listOf(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 3_000), image("IMG_4", 4_000)),
        )
        discovery.close()
        // Newest first: 4, 3, 2, 1. Delete IMG_2 (index 2) and stay on the frame after it.
        viewModel.navigateToImage(2)
        viewModel.deleteCurrentImage()
        assertEquals(listOf("IMG_4", "IMG_3", "IMG_1"), viewModel.names())
        assertEquals("IMG_1", viewModel.current())

        viewModel.setSortOrder(SelectorSortOrder.OLDEST_FIRST)
        assertEquals(listOf("IMG_1", "IMG_3", "IMG_4"), viewModel.names())

        // The recorded slot is index 2 of the *old* order; it would land after IMG_4.
        viewModel.undoLastOperation()

        assertEquals(listOf("IMG_1", "IMG_2", "IMG_3", "IMG_4"), viewModel.names())
        assertEquals("the frame on screen stays on screen", "IMG_1", viewModel.current())
        coVerify(exactly = 0) { imageRepository.deleteImage(any(), any()) }
    }

    // ── Go to photo ──────────────────────────────────────────────────────

    private suspend fun openSettled(vararg images: ImageItem): SelectorViewModel {
        val viewModel = open()
        discovery.send(images.toList())
        discovery.close()
        return viewModel
    }

    @Test
    fun `a number is the position the counter shows, in the current order`() = runTest {
        val viewModel = openSettled(image("IMG_1", 1_000), image("IMG_2", 2_000), image("IMG_3", 3_000))
        // Newest first: IMG_3, IMG_2, IMG_1. Photo number 3 is IMG_1.

        val result = viewModel.jumpToPhoto("3")

        assertEquals(JumpResolution.Resolved(index = 2), result)
        assertEquals("IMG_1", viewModel.current())
        assertEquals(3, viewModel.uiState.value.position)
    }

    @Test
    fun `a file name jumps to the first match in list order`() = runTest {
        val viewModel = openSettled(image("DSC_0001.JPG", 1_000), image("DSC_0123.JPG", 2_000), image("DSC_0124.JPG", 3_000))

        viewModel.jumpToPhoto("dsc_012")

        // Newest first: 0124 comes before 0123.
        assertEquals("DSC_0124.JPG", viewModel.current())
    }

    @Test
    fun `an out of range number or an unknown name does not move`() = runTest {
        val viewModel = openSettled(image("IMG_1", 1_000), image("IMG_2", 2_000))

        assertEquals(JumpResolution.OutOfRange(total = 2), viewModel.jumpToPhoto("9"))
        assertEquals(JumpResolution.Empty, viewModel.jumpToPhoto("  "))
        assertTrue(viewModel.jumpToPhoto("nope") is JumpResolution.NoNameMatch)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `Go to in the cull reaches a photo Learn's filter hides and leaves Learn's filter alone`() = runTest {
        val viewModel = openSettled(
            image("IMG_1", 1_000, rating = 5),
            image("IMG_2", 2_000, rating = 0),
            image("IMG_3", 3_000, rating = 5),
        )
        viewModel.setRatingFilter(RatingFilter.ExactRating(5))
        // Newest first: IMG_3 (5★), IMG_2 (hidden in Learn), IMG_1 (5★). Number 2 is IMG_2.

        assertEquals("IMG_2", viewModel.previewJump("2").fileName)
        val result = viewModel.jumpToPhoto("2")

        // Go-to is a cull control and the filter is Learn's (REQ-AND-RATING.01): nothing is hidden.
        assertEquals(JumpResolution.Resolved(index = 1, hiddenByFilter = false), result)
        assertEquals("IMG_2", viewModel.current())
        assertEquals(RatingFilter.ExactRating(5), viewModel.uiState.value.ratingFilter)
    }

    @Test
    fun `a photo the filter shows keeps the filter`() = runTest {
        val viewModel = openSettled(image("IMG_1", 1_000, rating = 5), image("IMG_2", 2_000, rating = 5))
        viewModel.setRatingFilter(RatingFilter.ExactRating(5))

        viewModel.jumpToPhoto("2")

        assertEquals(RatingFilter.ExactRating(5), viewModel.uiState.value.ratingFilter)
        assertEquals("IMG_1", viewModel.current())
    }

    // ── Go to a date ([USER 2026-11-04]) ─────────────────────────────────

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun on(dateTime: String): Long = LocalDateTime.parse(dateTime).atZone(berlin).toInstant().toEpochMilli()

    /** The ViewModel reads photo times in Berlin, with "today" on 4 Nov 2026. */
    private fun SelectorViewModel.inBerlin(): SelectorViewModel = apply {
        zoneId = { berlin }
        clock = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), berlin)
    }

    /** A returning user's folder: three days, one photo of unknown time, settled newest first. */
    private suspend fun openTrip(): SelectorViewModel = openSettled(
        image("A_1011.JPG", on("2026-10-11T09:00")),
        image("B_1012_morning.JPG", on("2026-10-12T08:00")),
        image("C_1012_evening.JPG", on("2026-10-12T18:00"), rating = 5),
        image("D_1014.JPG", on("2026-10-14T10:00")),
        image("E_unknown.JPG", 0L),
    ).inBerlin()

    @Test
    fun `a typed date goes to the first photo of that day in the current order`() = runTest {
        val viewModel = openTrip()
        // Newest first: D, C, B, A, E. The first of 12 Oct is the evening photo.

        val result = viewModel.jumpToPhoto("12.10.2026") as JumpResolution.Resolved

        assertEquals("C_1012_evening.JPG", viewModel.current())
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals(2, viewModel.uiState.value.position)
        assertTrue(result.date!!.isExact)

        viewModel.setSortOrder(SelectorSortOrder.OLDEST_FIRST)
        viewModel.jumpToPhoto("2026-10-12")
        assertEquals("B_1012_morning.JPG", viewModel.current())
        assertEquals(1, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `the picker opens on the day of the photo on screen`() = runTest {
        val viewModel = openTrip()
        viewModel.jumpToPhoto("12.10.2026")

        assertEquals(LocalDate.of(2026, 10, 12), viewModel.pickerStartDay())
    }

    @Test
    fun `the picker opens on the newest day when the photo on screen has no known time`() = runTest {
        val viewModel = openTrip()
        viewModel.jumpToPhoto("E_unknown")
        assertEquals("E_unknown.JPG", viewModel.current())

        assertEquals(LocalDate.of(2026, 10, 14), viewModel.pickerStartDay())
    }

    @Test
    fun `there is no day to open the picker on when no photo has a known time`() = runTest {
        val viewModel = openSettled(image("A.JPG", 0L), image("B.JPG", 0L)).inBerlin()

        assertEquals(null, viewModel.pickerStartDay())
    }

    @Test
    fun `a day without photos goes to the nearest day and the result says which`() = runTest {
        val viewModel = openTrip()

        val result = viewModel.jumpToPhoto("13.10.26") as JumpResolution.Resolved

        assertEquals(LocalDate.of(2026, 10, 13), result.date!!.requested)
        assertEquals(LocalDate.of(2026, 10, 14), result.date!!.landed)
        assertEquals("D_1014.JPG", viewModel.current())
    }

    @Test
    fun `day and month without a year use the current year`() = runTest {
        val viewModel = openTrip()

        viewModel.jumpToPhoto("11.10.")

        assertEquals("A_1011.JPG", viewModel.current())
    }

    @Test
    fun `the picker entry goes to the day and leaves Learn's filter alone`() = runTest {
        val viewModel = openTrip()
        viewModel.setRatingFilter(RatingFilter.ExactRating(5))

        val result = viewModel.jumpToDate(LocalDate.of(2026, 10, 14))

        // Go-to is a cull control; the filter is Learn's (REQ-AND-RATING.01).
        assertFalse(result!!.hiddenByFilter)
        assertEquals("D_1014.JPG", viewModel.current())
        assertEquals(RatingFilter.ExactRating(5), viewModel.uiState.value.ratingFilter)
    }

    @Test
    fun `within the day the first photo in the order is the target, whatever Learn's filter`() = runTest {
        val viewModel = openTrip()
        viewModel.setSortOrder(SelectorSortOrder.OLDEST_FIRST)
        viewModel.setRatingFilter(RatingFilter.ExactRating(5))

        viewModel.jumpToDate(LocalDate.of(2026, 10, 12))

        // Oldest first the morning photo comes first; Learn's filter does not reach the cull.
        assertEquals("B_1012_morning.JPG", viewModel.current())
        assertEquals(RatingFilter.ExactRating(5), viewModel.uiState.value.ratingFilter)
    }

    @Test
    fun `a folder with no known times does not move on a picked date`() = runTest {
        val viewModel = openSettled(image("X.JPG", 0L), image("Y.JPG", 0L)).inBerlin()
        viewModel.navigateToImage(1)

        // The action is handled — the answer is "nowhere to go" — and nothing moved.
        assertEquals(null, viewModel.jumpToDate(LocalDate.of(2026, 10, 12)))
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertTrue(viewModel.photoDates().isEmpty)
    }

    @Test
    fun `the dates with photos follow the list`() = runTest {
        val viewModel = openTrip()

        val dates = viewModel.photoDates()

        assertEquals(
            listOf(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)),
            dates.dates,
        )
        assertTrue(dates === viewModel.photoDates())

        // Deleting the only photo of 11 Oct takes the day out.
        viewModel.jumpToPhoto("A_1011")
        viewModel.deleteCurrentImage()
        assertFalse(LocalDate.of(2026, 10, 11) in viewModel.photoDates())
    }

    @Test
    fun `the preview names the target and its time, and agrees with the jump`() = runTest {
        val viewModel = openTrip()

        val preview = viewModel.previewJump("12.10.2026")

        assertEquals("C_1012_evening.JPG", preview.fileName)
        assertEquals(LocalDateTime.of(2026, 10, 12, 18, 0), preview.takenAt)
        assertEquals(viewModel.jumpToPhoto("12.10.2026"), preview.resolution)
    }
}
