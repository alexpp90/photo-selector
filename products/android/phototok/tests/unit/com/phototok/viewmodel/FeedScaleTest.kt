package com.phototok.viewmodel

import android.net.Uri
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.phototok.data.model.FolderLookup
import com.phototok.data.model.ImageItem
import com.phototok.data.model.PhoneSettings
import com.phototok.data.model.SourceSettings
import com.phototok.data.repository.ImageRepository
import com.phototok.data.repository.SettingsRepository
import com.phototok.data.source.FileOperationResult
import com.phototok.data.source.SelectionListing
import com.phototok.domain.FeedOrderMode
import com.phototok.domain.FolderScanInfo
import com.phototok.domain.PhoneFeedOrdering
import com.phototok.testsupport.ScaleConditions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/**
 * Scale tests of the feed through the real [PhoneModeViewModel] and its holders, at the sizes of
 * the conditions contract (`testdata/conditions/conditions.json`): N = `pr / factor` and 4N = `pr`
 * photos, best of 3 after warm-ups, `time(4N) / time(N) <= max_ratio`.
 *
 * Main dispatcher time is what is timed: the main dispatcher is a test dispatcher run to idle on
 * the test thread, and the fake repository answers at once, so discovery, ordering, the dimension
 * pass and the state updates all run there. Its file I/O, which production runs on the I/O
 * dispatcher, is a lookup that yields, as the suspension on I/O does, so the dimension pass and the
 * discovery batches interleave as on a device ([FakeRepository.readsSuspend]). Discovery emits like
 * `LocalImageSourceImpl`: cumulative snapshots after 24 photos, then every 250, then the whole folder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedScaleTest {

    private companion object {
        const val FOLDER = "content://docs/tree/root"

        /** Timings below this are scheduler noise: a sub-millisecond N must not inflate the ratio. */
        const val FLOOR_NANOS = 2_000_000L
    }

    private val dispatcher = StandardTestDispatcher()

    /** Run everything the main dispatcher has queued, as the main thread would. */
    private fun idle() = dispatcher.scheduler.advanceUntilIdle()
    private val sourceSettings = MutableStateFlow(SourceSettings())

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * [readsSuspend]: a header read yields as the suspension on I/O does, so a dimension pass and
     * discovery batches interleave. Off where nothing runs alongside the pass: the dispatch per photo
     * then only adds a linear cost to both sizes.
     */
    private class FakeRepository(
        private val photos: List<ImageItem>,
        private val readsSuspend: Boolean = true,
    ) : ImageRepository {
        /** Every third photo is portrait; the header read itself is a lookup. */
        private val sizes: Map<String, Pair<Int, Int>> = photos.withIndex().associate { (i, p) ->
            p.uri to if (i % 3 == 0) (3000 to 4000) else (4000 to 3000)
        }

        override fun discoverImages(
            folderUri: Uri,
            excludedFolderNames: Set<String>,
            shuffleFirstBatch: Boolean,
        ): Flow<List<ImageItem>> = flow {
            val found = ArrayList<ImageItem>(photos.size)
            var nextEmitAt = 24
            for (photo in photos) {
                found += photo
                if (found.size >= nextEmitAt) {
                    emit(found.toList())
                    nextEmitAt = found.size + 250
                    yield() // the walk runs on the I/O dispatcher: the main thread is free between batches
                }
            }
            emit(found.toList())
        }

        override fun skippedVideoCount(folderUri: Uri) = 0
        override suspend fun prepareSourceFolder(folderUri: Uri) = "Camera"
        override suspend fun resolveFolderName(folderUri: Uri): String? = null
        override suspend fun describeFolder(folderUri: Uri) = FolderLookup()
        override suspend fun getExifData(uri: Uri): ExifData = ExifData(iso = 100)
        override suspend fun deleteImage(uri: Uri) = true
        override suspend fun moveImage(sourceUri: Uri, destFolderUri: Uri, sorting: Boolean, subfolderName: String) = true
        override suspend fun copyImage(sourceUri: Uri, destFolderUri: Uri, sorting: Boolean, subfolderName: String) = true
        override suspend fun moveImageDetailed(sourceUri: Uri, destFolderUri: Uri, sorting: Boolean, subfolderName: String) =
            FileOperationResult.success(sourceUri.toString(), null)
        override suspend fun copyImageDetailed(sourceUri: Uri, destFolderUri: Uri, sorting: Boolean, subfolderName: String) =
            FileOperationResult.success(sourceUri.toString(), null)
        override suspend fun transferBundleDetailed(
            sourceUris: List<Uri>,
            destFolderUri: Uri,
            sorting: Boolean,
            subfolderName: String,
            isMove: Boolean,
        ) = sourceUris.map { FileOperationResult.success(it.toString(), null) }
        override suspend fun getImageDimensions(uri: Uri): Pair<Int, Int> {
            if (readsSuspend) yield() // the header read suspends on the I/O dispatcher
            return sizes.getValue(uri.toString())
        }
        override suspend fun listSelectionImages(folderUri: Uri, subfolderName: String) = SelectionListing.Missing
    }

    /** A camera roll as discovered: directory order is not date order, sizes not read yet. */
    private fun photos(count: Int): List<ImageItem> {
        val rnd = Random(42)
        return (0 until count).map {
            ImageItem(
                uri = "$FOLDER/document/f$it",
                fileName = "IMG_%06d.jpg".format(it),
                fileSize = 1L,
                lastModified = rnd.nextInt(count * 50).toLong(),
                mimeType = "image/jpeg",
                parentUri = "$FOLDER/document/root",
            )
        }
    }

    private fun settingsRepository(): SettingsRepository = mockk(relaxed = true) {
        every { phoneSettings } returns MutableStateFlow(PhoneSettings())
        every { phoneCollectionUri } returns MutableStateFlow(null)
        every { phoneLeftSwipeUri } returns MutableStateFlow(null)
        every { lastFolderUri } returns MutableStateFlow(null)
        every { sortingEnabled } returns MutableStateFlow(false)
        every { phoneGestureTutorialTs } returns MutableStateFlow(System.currentTimeMillis())
        every { lastAppUsedTs } returns MutableStateFlow(System.currentTimeMillis())
        every { sourceSettingsFlow(any()) } returns sourceSettings
        coEvery { getFolderScanInfo(any()) } returns FolderScanInfo(0L, 0)
    }

    private fun viewModel(all: List<ImageItem>, order: FeedOrderMode, readsSuspend: Boolean = true): PhoneModeViewModel {
        sourceSettings.value = SourceSettings(feedOrderMode = order)
        return PhoneModeViewModel(FakeRepository(all, readsSuspend), settingsRepository(), CoroutineScope(dispatcher))
            .also { idle() }
    }

    private fun PhoneModeViewModel.openFolder(): PhoneModeViewModel {
        selectSourceFolder(Uri.parse(FOLDER))
        idle()
        check(!uiState.value.isDiscovering) { "discovery did not finish" }
        return this
    }

    private fun opened(all: List<ImageItem>, order: FeedOrderMode) = viewModel(all, order).openFolder()

    /** Best of 3 timings of [op] after 2 warm-ups, each on a fresh [setup] that is not timed. */
    private fun <T> bestNanos(setup: () -> T, op: (T) -> Unit): Long {
        repeat(2) { op(setup()) }
        return (1..3).minOf {
            val subject = setup()
            val t0 = System.nanoTime()
            op(subject)
            System.nanoTime() - t0
        }
    }

    /**
     * The growth rule of the contract for [op] at N and 4N. A ratio over the limit is measured once
     * more before the test fails: a concurrent build can stall one series, a quadratic cost fails both.
     */
    private fun <T> assertGrowth(what: String, setup: (List<ImageItem>) -> T, op: (T) -> Unit) {
        val small = photos(ScaleConditions.baseN)
        val large = photos(ScaleConditions.prPhotos)
        var ratio = Double.MAX_VALUE
        var report = ""
        for (attempt in 1..2) {
            val tSmall = bestNanos({ setup(small) }, op)
            val tLarge = bestNanos({ setup(large) }, op)
            ratio = tLarge.toDouble() / maxOf(tSmall, FLOOR_NANOS)
            report = "$what: N=${small.size} ${"%.1f".format(tSmall / 1e6)} ms, " +
                "4N=${large.size} ${"%.1f".format(tLarge / 1e6)} ms, ratio ${"%.2f".format(ratio)}"
            println("SCALE $report")
            if (ratio <= ScaleConditions.maxRatio) break
        }
        assertTrue("$report, limit ${ScaleConditions.maxRatio}", ratio <= ScaleConditions.maxRatio)
    }

    // ── Time to the full list (REQ-TOK-ARCH.24 budget 3) ──────────────────────────────────

    /** Scale: pr — the whole folder streamed into a newest-first feed (the default order). */
    @Test
    @Requirement("REQ-TOK-ARCH.24", "REQ-TOK-ARCH.10", "REQ-TOK-ARCH.12")
    fun `discovering a folder grows linearly with its size`() =
        assertGrowth("discovery, newest first", { viewModel(it, FeedOrderMode.NEWEST_FIRST) }) { vm ->
            vm.openFolder()
        }

    /** Scale: pr — landscape first: discovery and the dimension pass that sizes every photo. */
    @Test
    @Requirement("REQ-TOK-ARCH.24", "REQ-TOK-ARCH.12", "REQ-TOK-FEATURE.08")
    fun `discovering a folder in landscape-first order grows linearly with its size`() =
        assertGrowth("discovery, landscape first", { viewModel(it, FeedOrderMode.ORIENTATION_THEN_NEWEST) }) { vm ->
            vm.openFolder()
            check(vm.uiState.value.images.all { it.imageWidth > 0 })
        }

    /**
     * Scale: pr — turning landscape first on in an open folder: one sort and a dimension pass over
     * every photo, applied in chunks. Each application rebuilt the whole feed per 24 photos.
     */
    @Test
    @Requirement("REQ-TOK-ARCH.24", "REQ-TOK-FEATURE.08", "REQ-TOK-ARCH.17")
    fun `sizing every photo for landscape first grows linearly with the folder`() =
        assertGrowth("dimension fill", { viewModel(it, FeedOrderMode.NEWEST_FIRST, readsSuspend = false).openFolder() }) { vm ->
            sourceSettings.value = SourceSettings(feedOrderMode = FeedOrderMode.ORIENTATION_THEN_NEWEST)
            idle()
            check(vm.uiState.value.images.all { it.imageWidth > 0 })
        }

    // ── Main-thread time per navigation (REQ-TOK-ARCH.24 budget 2) ────────────────────────

    /**
     * Scale: pr — 100 page settles at the end of the list, each as the pager reports it: the
     * navigation, then the dwell that marks the photo seen. Each loads the neighbours' EXIF.
     */
    @Test
    @Requirement("REQ-TOK-ARCH.24")
    fun `paging at the end of the list grows linearly with the folder`() =
        assertGrowth("100 page settles at the end", { opened(it, FeedOrderMode.NEWEST_FIRST) }) { vm ->
            val size = vm.uiState.value.images.size
            for (index in size - 100 until size) {
                vm.navigateToImage(index)
                idle()
                vm.onCardDwelled(vm.uiState.value.images[index])
                idle()
            }
            check(vm.uiState.value.currentIndex == size - 1)
        }

    // ── Done with this card: Delete all (REQ-TOK-FEATURE.15) ──────────────────────────────

    /** Scale: pr — every file deleted one after the other; the feed is updated once at the end. */
    @Test
    @Requirement("REQ-TOK-FEATURE.15", "REQ-TOK-ARCH.24")
    fun `clearing a card grows linearly with the files on it`() =
        assertGrowth("delete all", { opened(it, FeedOrderMode.NEWEST_FIRST) }) { vm ->
            vm.confirmClearEntireCard()
            idle()
            check(vm.uiState.value.allImages.isEmpty())
        }

    // ── The order the faster paths must keep (REQ-TOK-FEATURE.08) ─────────────────────────

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `a folder sized in many chunks is landscape first, each section newest first`() {
        val vm = opened(photos(ScaleConditions.photos("typical")), FeedOrderMode.NEWEST_FIRST)
        sourceSettings.value = SourceSettings(feedOrderMode = FeedOrderMode.ORIENTATION_THEN_NEWEST)
        idle()

        val state = vm.uiState.value
        assertTrue("every photo sized", state.images.all { it.imageWidth > 0 })
        val expected = PhoneFeedOrdering.order(state.allImages, FeedOrderMode.ORIENTATION_THEN_NEWEST)
        assertEquals(expected.images.map { it.uri }, state.images.map { it.uri })
        assertEquals(expected.portraitSectionStart, state.portraitSectionStart)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.08")
    fun `a folder opened in landscape-first order ends landscape first, each section newest first`() {
        val vm = opened(photos(ScaleConditions.photos("typical")), FeedOrderMode.ORIENTATION_THEN_NEWEST)

        val state = vm.uiState.value
        assertTrue("every photo sized", state.images.all { it.imageWidth > 0 })
        val expected = PhoneFeedOrdering.order(state.allImages, FeedOrderMode.ORIENTATION_THEN_NEWEST)
        assertEquals(expected.images.map { it.uri }, state.images.map { it.uri })
        assertEquals(expected.portraitSectionStart, state.portraitSectionStart)
    }
}
