package com.photoselectortoolbox.ui.inspect

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.DebriefMetric
import com.photoselectortoolbox.domain.learn.MetricCount
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.domain.usecase.ScanProgress
import com.photoselectortoolbox.domain.usecase.UpdateShootSummaryUseCase
import com.photoselectortoolbox.testing.InMemoryLearnRepository
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitImageRepository
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.viewmodel.LearnCompositionViewModel
import com.photoselectortoolbox.viewmodel.LearnViewModel
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/**
 * The bursts the cull finds reach Learn (backlog sweep 2026-11-12, item 5): `ImageItem.groupId` was
 * set by no production code, so Compare's "frames of one burst pair whatever their focal lengths"
 * and the debrief's burst guard never fired in the app. Both through the shipped path: the cull
 * screen's view model with Bursts on and its real grouper, then `InspectScreen`'s Compare tab, and
 * the scan's shoot summary.
 */
@Requirement("REQ-AND-LEARN.03", "REQ-AND-LEARN.04")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class BurstsReachLearnTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<ViewModel>()

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

    private fun frame(name: String, second: Long, sharpness: Double) =
        fitPhoto(name, 1_700_000_000_000L + second * 1_000).copy(scanResult = ScanResult(name, sharpnessScore = sharpness))

    /** A burst whose frames zoomed from 24 to 70 mm: by time and focal length alone, no scene. */
    private val zoomBurst = listOf(frame("DSC_0001.JPG", 0, 100.0), frame("DSC_0002.JPG", 1, 30.0))
    private val focal = mapOf(zoomBurst[0].uri to 24.0, zoomBurst[1].uri to 70.0)

    private fun cull(photos: List<ImageItem>, scan: ScanImagesUseCase = mockk(relaxed = true), repo: InMemoryLearnRepository? = null): SelectorViewModel {
        val images = fitImageRepository().also { r ->
            coEvery { r.getExifData(any(), any()) } answers {
                val f = focal[secondArg<Uri>().toString()] ?: 50.0
                ExifData(make = "SONY", model = "ILCE-7M4", focalLength = f, focalLength35mm = f, shutterSpeed = 1.0 / 250)
            }
        }
        val vm = fitSelectorViewModel(
            context, appScope, FitSelectorSettings(grouping = true), photos, imageRepository = images, scanImagesUseCase = scan,
            updateShootSummary = repo?.let(::UpdateShootSummaryUseCase),
        ).also { viewModels += it }
        rule.waitUntil(10_000) { vm.uiState.value.groups.any { it.size >= 2 } }
        return vm
    }

    @Test
    fun `Compare pairs the frames of a burst whatever their focal lengths`() {
        val selector = cull(zoomBurst)
        val learn = LearnViewModel(InMemoryLearnRepository()).also { viewModels += it }
        val composition = LearnCompositionViewModel(mockk(relaxed = true)).also { viewModels += it }
        rule.setContent {
            InspectScreen(
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                viewModel = selector,
                compositionViewModel = composition,
                learnViewModel = learn,
            )
        }
        rule.onNodeWithTag(LearnTab.COMPARE.tag).performClick()
        rule.waitForIdle()

        assertEquals("no 'No measured difference'", 0, rule.onAllNodesWithTag("compare_empty", useUnmergedTree = true).fetchSemanticsNodes().size)
        val better = rule.onNodeWithTag("compare_frame_better", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        assertTrue(better, better.contains("DSC_0001.JPG"))
    }

    @Test
    fun `the shoot summary's sharp-frames count applies the burst guard`() {
        // Three frames of one burst: 200, 120, 90. The 90 is under half the burst's best.
        val photos = listOf(frame("DSC_0001.JPG", 0, 200.0), frame("DSC_0002.JPG", 1, 120.0), frame("DSC_0003.JPG", 2, 90.0))
        val repo = InMemoryLearnRepository()
        photos.forEach { p ->
            repo.facts[p.uri] = listOf(ScanFacts.MARKER to "1", ScanFacts.CAMERA_CLASS to CameraClass.INTERCHANGEABLE.key).map { (k, v) ->
                TechniqueFactEntity(p.uri, k, v, 0.9, null, p.fileSize, p.lastModified, TechniqueFactDetector.DETECTOR_VERSION)
            }
        }
        val results = photos.associate { it.uri to it.scanResult!! }
        val scan: ScanImagesUseCase = mockk(relaxed = true) { every { this@mockk(any(), any()) } returns flowOf(ScanProgress(3, 3, "", results)) }
        val vm = cull(photos, scan, repo)

        vm.startScan()

        val group = repo.shoots.getValue("content://test/folder").first.single()
        assertEquals(MetricCount(DebriefMetric.SHARP_FRAMES, 2, 3), group.metrics.single { it.metric == DebriefMetric.SHARP_FRAMES })
    }
}
