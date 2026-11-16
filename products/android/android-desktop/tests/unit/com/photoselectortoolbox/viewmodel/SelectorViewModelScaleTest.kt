package com.photoselectortoolbox.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ComponentRegistry
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.DefaultRequestOptions
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.size.Size
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoreEntity
import com.photoselectortoolbox.data.model.FolderDescription
import com.photoselectortoolbox.data.model.ImageDimensions
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.data.model.SelectionFolderState
import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.FileOperationResult
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.domain.curation.FileIdentity
import com.photoselectortoolbox.domain.grouping.GroupingLevel
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.domain.usecase.ScanProgress
import com.photoselectortoolbox.testing.ScaleConditions
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The selector's view model at the folder sizes users have (`testdata/conditions/conditions.json`,
 * docs/shared/USAGE_CONDITIONS.md): what the screen calls, per event, at N = 2,500 and 4N = 10,000.
 *
 * Main and the view model's IO dispatcher are one unconfined test dispatcher, so every call returns
 * with its work done and the time measured is the time the main thread would spend. Hand-written
 * fakes in every timed path (a mock records each call); the settings flows are read once, at
 * construction, and the scan use case is called once per scan.
 *
 * Memory: mockk keeps the calls every mock recorded, the child mocks of a relaxed one included,
 * until `clearAllMocks()`, and those calls reach the view models: without the clear in [tearDown]
 * every 10,000-photo view model of this class stayed alive (heap after GC 75 → 243 MB over the
 * class; the full suite then ran out of its 512 MB test heap). With it the heap stays at 75–78 MB.
 * The mocks here are also one per test, answer from fields and never see a folder-sized argument.
 */
@Requirement("REQ-AND-PERF.02", "REQ-AND-PERF.04", "REQ-AND-PERF.05", "REQ-AND-PERF.06", "REQ-AND-PERF.10", "REQ-AND-CACHE.03")
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectorViewModelScaleTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(testDispatcher)
    private val loader = CountingImageLoader()
    private val viewModels = mutableListOf<SelectorViewModel>()

    /** What the scan use case returns next; one mock for the test, never one per view model. */
    private var scanFlow: Flow<ScanProgress> = emptyFlow()
    private val scanUseCase = mockk<ScanImagesUseCase> { every { this@mockk.invoke(any(), any()) } answers { scanFlow } }

    private val settingsRepository: SettingsRepository = mockk(relaxed = true) {
        every { selectorSortOrder } returns MutableStateFlow(SelectorSortOrder.FILE_NAME)
        every { groupingEnabled } returns MutableStateFlow(false)
        every { groupingLevel } returns MutableStateFlow(GroupingLevel.TIME_FILENAME)
        every { sortingEnabled } returns MutableStateFlow(true)
        every { lastFolderUri } returns MutableStateFlow(null)
        every { filingAction } returns MutableStateFlow(FilingAction.COPY)
        every { fullscreenButtonsEnabled } returns MutableStateFlow(true)
        every { fullscreenInfoPinned } returns MutableStateFlow(false)
        every { overlayValuesVisible } returns MutableStateFlow(true)
        every { filmstripVisible } returns MutableStateFlow(true)
        every { detailsVisible } returns MutableStateFlow(true)
        every { hasSeenFullscreenGestureHint } returns MutableStateFlow(true)
        every { hasSeenNavHint } returns MutableStateFlow(true)
        every { selectionFolderName } returns MutableStateFlow(FilingAction.DEFAULT_SELECTION_FOLDER)
        every { customSelectionDestinationUri } returns MutableStateFlow(null)
        every { pendingDeleteJournal } returns MutableStateFlow(emptySet())
        every { pendingDeleteIdentities } returns MutableStateFlow(emptyMap())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        Coil.setImageLoader(loader)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Coil.reset()
        Dispatchers.resetMain()
        loader.clear()
        scanFlow = emptyFlow()
        clearAllMocks() // mockk holds recorded calls globally; they keep the view models alive
    }

    private fun folder(n: Int, root: String = "content://test/folder") = (0 until n).map { i ->
        val name = "IMG_%05d.jpg".format(java.util.Locale.ROOT, i)
        ImageItem("$root/$name", name, 1_000L + i, 1_000L + i, "image/jpeg")
    }

    private fun score(uri: String) = ScanResult(filePath = uri, sharpnessScore = 80.0, noiseLevel = 5.0)

    /** A view model with [photos] listed and settled (discovery, restore and backfill done). */
    private fun opened(
        photos: List<ImageItem>,
        repository: ScaleImageRepository = ScaleImageRepository(photos),
        cache: CountingCacheRepository = CountingCacheRepository(),
        scoreDao: CountingScoreDao = CountingScoreDao(),
        scan: Flow<ScanProgress>? = null,
        folderUri: String = "content://test/folder",
    ): SelectorViewModel {
        scanFlow = scan ?: emptyFlow()
        val viewModel = SelectorViewModel(
            imageRepository = repository,
            scanImagesUseCase = scanUseCase,
            cacheRepository = cache,
            settingsRepository = settingsRepository,
            scoreDao = scoreDao,
            appScope = appScope,
            context = ApplicationProvider.getApplicationContext(),
            injectedIoDispatcher = testDispatcher,
        )
        viewModels += viewModel
        viewModel.selectFolder(Uri.parse(folderUri))
        check(viewModel.uiState.value.images.size == photos.size) { "folder not listed: ${viewModel.uiState.value.images.size} of ${photos.size}" }
        check(!viewModel.uiState.value.isEnumerating) { "discovery has not settled" }
        return viewModel
    }

    private fun release(viewModel: SelectorViewModel) {
        viewModel.viewModelScope.cancel()
        viewModels -= viewModel
    }

    // ── The scan ─────────────────────────────────────────────────────────

    /**
     * A scan's progress as the use case delivers it when every photo finishes on its own: the
     * opening emission, then one emission per photo with its one new result (REQ-AND-PERF.02).
     */
    private fun onePerPhoto(photos: List<ImageItem>): Flow<ScanProgress> = flow {
        emit(ScanProgress(0, photos.size, "", emptyMap()))
        photos.forEachIndexed { i, photo -> emit(ScanProgress(i + 1, photos.size, photo.fileName, mapOf(photo.uri to score(photo.uri)))) }
    }

    /**
     * REQ-AND-PERF.02, REQ-AND-PERF.04, REQ-AND-PERF.10: the selector folds a whole scan's
     * progress into the list in time linear in the folder. Each emission rewrote the whole list on
     * the main thread (`ScoreMerge.apply` per emission), so a scan of N photos cost N passes over N.
     *
     * Scale: pr
     */
    @Test
    fun `a scan's progress lands in a folder of the PR size in linear time`() {
        ScaleConditions.assertGrowth("selector applies a scan's progress") { n ->
            val photos = folder(n)
            val viewModel = opened(photos, scan = onePerPhoto(photos));
            {
                viewModel.startScan()
                val state = viewModel.uiState.value
                check(!state.isScanRunning) { "scan still running" }
                check(state.images.count { it.scanResult != null } == n) { "scores applied to ${state.images.count { it.scanResult != null }} of $n" }
                release(viewModel)
            }
        }
    }

    /** REQ-AND-PERF.04: the scan merges the score alone; what landed in the item meanwhile stays. */
    @Test
    fun `a scan applied in batches keeps every score and the fields that landed meanwhile`() {
        val photos = folder(60)
        val exif = ExifData(iso = 200)
        val repository = ScaleImageRepository(photos, exif = exif)
        val viewModel = opened(photos, repository, scan = onePerPhoto(photos))
        viewModel.navigateToImage(30) // EXIF lands on the frames around 30 before the scan

        viewModel.startScan()

        val images = viewModel.uiState.value.images
        assertEquals(photos.map { it.uri }, images.map { it.uri })
        assertTrue(images.all { it.scanResult?.sharpnessScore == 80.0 })
        assertEquals(exif, images[30].exifData)
        assertEquals("100 % when done", 1f, viewModel.uiState.value.scanProgress)
    }

    // ── The cached-score restore ─────────────────────────────────────────

    /**
     * REQ-AND-CACHE.03, REQ-AND-PERF.10: opening a folder of the PR size restores its cached scores
     * with one batched read, not one query per photo (10,000 `getScore` queries before; 403 ms
     * against 39 ms batched in Room, `ScoreRestoreScaleTest`), and the open grows linearly.
     *
     * Scale: pr
     */
    @Test
    fun `a folder of the PR size restores its cached scores in one batched read`() {
        val n = ScaleConditions.prPhotos
        val photos = folder(n)
        val cache = CountingCacheRepository(photos.associate { it.uri to ScoreEntity(it.uri, it.fileSize, it.lastModified, sharpnessScore = 70.0) })
        val dao = CountingScoreDao()
        val viewModel = opened(photos, cache = cache, scoreDao = dao)

        assertEquals("no per-photo query", 0, dao.singleScoreQueries)
        assertEquals("no per-photo query", 0, cache.singleScoreQueries)
        assertEquals("one batched read of the folder's scores", 1, cache.scoreBatches.size)
        assertEquals(n, cache.scoreBatches.single().size)
        assertEquals(n, viewModel.uiState.value.images.count { it.scanResult?.sharpnessScore == 70.0 })

        ScaleConditions.assertGrowth("selector opens a cached folder") { size ->
            val listed = folder(size)
            val rows = listed.associate { it.uri to ScoreEntity(it.uri, it.fileSize, it.lastModified, sharpnessScore = 70.0) };
            {
                val opened = opened(listed, cache = CountingCacheRepository(rows))
                check(opened.uiState.value.images.last().scanResult != null) { "scores not restored" }
                release(opened)
            }
        }
    }

    /** REQ-AND-CACHE.03: a cached row of another file version is not restored; a stored rating is. */
    @Test
    fun `the batched restore validates each row against its file and restores ratings`() {
        val photos = folder(3)
        val cache = CountingCacheRepository(
            scores = mapOf(
                photos[0].uri to ScoreEntity(photos[0].uri, photos[0].fileSize, photos[0].lastModified, sharpnessScore = 70.0),
                photos[1].uri to ScoreEntity(photos[1].uri, photos[1].fileSize + 1, photos[1].lastModified, sharpnessScore = 60.0),
            ),
            ratings = mapOf(photos[2].uri to 4),
        )
        val images = opened(photos, cache = cache).uiState.value.images

        assertEquals(70.0, images[0].scanResult?.sharpnessScore)
        assertEquals("another file size: stale", null, images[1].scanResult)
        assertEquals(4, images[2].rating)
    }

    // ── Navigation, prefetch and decisions ───────────────────────────────

    private val navigationKeys = 100

    /** Opens [n] photos and stands [navigationKeys] + 1 frames before the end. */
    private fun atTheEnd(n: Int, repository: ScaleImageRepository = ScaleImageRepository(folder(n), exif = ExifData(iso = 100))): SelectorViewModel =
        opened(repository.photos, repository).also { it.navigateToImage(n - navigationKeys - 1) }

    /**
     * REQ-AND-PERF.10: main-thread time of 100 navigation keys at the end of the list grows at
     * most linearly with the folder (EXIF and dimensions of the frames around, the prefetch).
     *
     * Scale: pr
     */
    @Test
    fun `navigation at the end of a folder of the PR size stays linear`() {
        ScaleConditions.assertGrowth("selector navigation keys at the end of the list") { n ->
            val viewModel = atTheEnd(n);
            {
                repeat(navigationKeys) { viewModel.navigateNext() }
                check(viewModel.uiState.value.currentIndex == n - 1)
                release(viewModel)
            }
        }
    }

    /**
     * REQ-AND-PERF.06, REQ-AND-PERF.10: the neighbour prefetch per navigation is independent of the
     * folder: the same requests at 2,500 and 10,000 photos, a fixed few per key, each sized to a
     * frame and never to the original, and the ones left behind disposed.
     *
     * Scale: pr
     */
    @Test
    fun `the neighbour prefetch per navigation does not depend on the folder size`() {
        fun prefetchOf(n: Int): List<Int> {
            val viewModel = atTheEnd(n)
            loader.requests.clear()
            val perKey = (1..navigationKeys).map {
                val before = loader.requests.size
                viewModel.navigateNext()
                loader.requests.size - before
            }
            release(viewModel)
            return perKey
        }
        val small = prefetchOf(ScaleConditions.baseN)
        val large = prefetchOf(ScaleConditions.prPhotos)

        assertEquals("prefetch requests per key at N and 4N", small, large)
        assertTrue("a few requests per key, at most the four of ±2 and ±3: $large", large.all { it in 0..4 })
        assertTrue("the prefetch runs while navigating", large.sum() > 0)
        assertTrue("every prefetch is sized to a frame", loader.requests.all { runBlocking { it.sizeResolver.size() } != Size.ORIGINAL })
        val live = loader.disposables.count { !it.isDisposed }
        assertTrue("requests behind the window are disposed: $live live", live <= 4)
    }

    /**
     * REQ-AND-PERF.10: main-thread time of 100 decisions (delete, held back for undo) at the end of
     * the list grows at most linearly with the folder.
     *
     * Scale: pr
     */
    @Test
    fun `decisions at the end of a folder of the PR size stay linear`() {
        ScaleConditions.assertGrowth("selector decisions at the end of the list") { n ->
            val viewModel = atTheEnd(n);
            {
                repeat(navigationKeys) { viewModel.deleteCurrentImage() }
                check(viewModel.uiState.value.images.size == n - navigationKeys)
                release(viewModel)
            }
        }
    }

    // ── The dimension backfill ───────────────────────────────────────────

    /** REQ-AND-PERF.05: the folder-wide backfill reads every header on phone storage. */
    @Test
    fun `phone storage backfills every photo's dimensions`() {
        val photos = folder(200)
        val repository = ScaleImageRepository(photos)
        opened(photos, repository)
        assertEquals(photos.map { it.uri }.toSet(), repository.dimensionRequests.flatten().toSet())
    }

    /**
     * REQ-AND-PERF.05: on a cloud provider (each open downloads the file) and a camera over MTP only
     * the visible range is resolved, never the whole folder.
     */
    @Test
    fun `cloud and camera folders resolve only the visible range`() {
        listOf(
            "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Droot",
            "content://com.android.mtp.documents/tree/1",
        ).forEach { root ->
            val photos = folder(200, root)
            val repository = ScaleImageRepository(photos)
            opened(photos, repository, folderUri = root)
            val asked = repository.dimensionRequests.flatten().toSet()
            assertTrue("$root: only frames near the first one, not ${asked.size}", asked.size in 1..24)
            assertNotEquals(photos.size, asked.size)
        }
    }
}

// ── Hand-written fakes ───────────────────────────────────────────────────

/** A provider holding [photos]: one discovery emission, EXIF and dimensions for any photo, counted. */
private class ScaleImageRepository(
    val photos: List<ImageItem>,
    private val exif: ExifData? = null,
) : ImageRepository {
    val dimensionRequests = mutableListOf<List<String>>()

    override suspend fun openFolder(context: Context, folderUri: Uri): String = "Scale"
    override suspend fun describeFolder(context: Context, folderUri: Uri) = FolderDescription(folderUri.toString(), listOf("Scale"), name = "Scale")
    override fun discoverImages(folderUri: Uri): Flow<List<ImageItem>> = flowOf(photos)
    override suspend fun discoverAllImages(folderUri: Uri): List<ImageItem> = photos
    override suspend fun getExifData(context: Context, uri: Uri): ExifData? = exif
    override suspend fun deleteImage(context: Context, uri: Uri) = true
    override suspend fun documentIdentity(context: Context, uri: Uri) = FileIdentity(size = 1, lastModified = 1)
    override fun canTrash(uri: Uri) = false
    override suspend fun restoreFromTrash(context: Context, uri: Uri) = false
    override suspend fun moveImage(context: Context, sourceUri: Uri, destFolderUri: Uri, sorting: Boolean): FileOperationResult = error("not in this test")
    override suspend fun copyImage(context: Context, sourceUri: Uri, destFolderUri: Uri, sorting: Boolean): FileOperationResult = error("not in this test")
    override suspend fun undoMove(context: Context, movedTo: Uri, restoreFolderUri: Uri): FileOperationResult = error("not in this test")
    override suspend fun moveBundle(context: Context, item: ImageItem, destFolderUri: Uri, sorting: Boolean): FileOperationResult = error("not in this test")
    override suspend fun copyBundle(context: Context, item: ImageItem, destFolderUri: Uri, sorting: Boolean): FileOperationResult = error("not in this test")
    override suspend fun undoMoveBundle(
        context: Context,
        destinationUris: List<String>,
        rootFolderUri: Uri,
        parentDocumentId: String?,
        copiesOfSurvivingOriginals: Map<String, String>,
    ): FileOperationResult = error("not in this test")
    override suspend fun deleteBundle(context: Context, item: ImageItem) = true
    override suspend fun selectionFolder(context: Context, folderUri: Uri): SelectionFolderState? = null
    override suspend fun resolveDimensions(context: Context, uris: Collection<String>): Map<String, ImageDimensions> {
        val asked = uris.toList()
        dimensionRequests += asked
        return asked.associateWith { ImageDimensions(6000, 4000) }
    }
}

/** The relaxed mocks behind the fakes' untouched methods: one each, cleared after every test. */
private val CacheDelegate: CacheRepository = mockk(relaxed = true)
private val DaoDelegate: ScoreDao = mockk(relaxed = true)

/** The Room cache as [scores] and [ratings]; counts single and batched reads. */
private class CountingCacheRepository(
    private val scores: Map<String, ScoreEntity> = emptyMap(),
    private val ratings: Map<String, Int> = emptyMap(),
) : CacheRepository by CacheDelegate {
    var singleScoreQueries = 0
    val scoreBatches = mutableListOf<List<String>>()

    override suspend fun getCachedScore(filePath: String): ScoreEntity? {
        singleScoreQueries++
        return scores[filePath]
    }

    override suspend fun getCachedScores(filePaths: List<String>): List<ScoreEntity> {
        scoreBatches += filePaths
        return filePaths.mapNotNull(scores::get)
    }

    override suspend fun getRatings(uris: List<String>): Map<String, Int> = uris.mapNotNull { uri -> ratings[uri]?.let { uri to it } }.toMap()
    override suspend fun updateRating(filePath: String, rating: Int) = Unit
}

/**
 * A [ScoreDao] that counts the per-photo `getScore` query. The user-rating fallback is answered
 * here: an interface method with a body is still delegated, so the mock would record the folder.
 */
private class CountingScoreDao : ScoreDao by DaoDelegate {
    var singleScoreQueries = 0

    override suspend fun getScore(filePath: String): ScoreEntity? {
        singleScoreQueries++
        return null
    }

    override suspend fun getUserRatings(uris: List<String>) = emptyList<com.photoselectortoolbox.data.cache.UserRatingEntity>()
}

/** A Coil loader that records the requests the selector enqueues and hands back disposables. */
private class CountingImageLoader : ImageLoader {
    val requests = mutableListOf<ImageRequest>()
    val disposables = mutableListOf<RecordedDisposable>()

    fun clear() {
        requests.clear()
        disposables.clear()
    }

    override val defaults = DefaultRequestOptions()
    override val components = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null

    override fun enqueue(request: ImageRequest): Disposable {
        requests += request
        return RecordedDisposable().also { disposables += it }
    }

    override suspend fun execute(request: ImageRequest): ImageResult = error("the selector prefetches with enqueue")
    override fun shutdown() = Unit
    override fun newBuilder(): ImageLoader.Builder = error("not in this test")

    class RecordedDisposable : Disposable {
        override val job: Deferred<ImageResult> = CompletableDeferred()
        override var isDisposed: Boolean = false
            private set

        override fun dispose() {
            isDisposed = true
        }
    }
}
