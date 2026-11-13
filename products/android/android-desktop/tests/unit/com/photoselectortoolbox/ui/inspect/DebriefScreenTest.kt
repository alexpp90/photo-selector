package com.photoselectortoolbox.ui.inspect

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.CameraId
import com.photoselectortoolbox.domain.learn.DebriefMetric
import com.photoselectortoolbox.domain.learn.MetricCount
import com.photoselectortoolbox.domain.learn.ScanFacts
import com.photoselectortoolbox.domain.learn.ShootSummary
import com.photoselectortoolbox.domain.learn.TechniqueFactDetector
import com.photoselectortoolbox.domain.usecase.ScanImagesUseCase
import com.photoselectortoolbox.domain.usecase.ScanProgress
import com.photoselectortoolbox.testing.InMemoryLearnRepository
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.viewmodel.LearnCompositionViewModel
import com.photoselectortoolbox.viewmodel.LearnViewModel
import com.photoselectortoolbox.viewmodel.SelectorViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * The Debrief tab and its worked pair (shoot debrief SD-4, SD-5), through `InspectScreen` with the
 * cull screen's real view model and Learn's own over an in-memory store: one card per camera
 * class, the largest open when there are three; the camera chip changes and keeps a class (its
 * menu closing on `Esc` before Learn does is a popup-window key, proved on a device only); the
 * empty states; Learn opens on Debrief once after a scan and on the last tab otherwise; a count is
 * one spoken phrase with its previous value; *Track* keeps the focus as the goal and a later shoot
 * shows it; the worked pair asks first, reveals after A or *Show me*, and the rating keys do
 * nothing while it shows.
 */
@Requirement("REQ-AND-LEARN.05")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class DebriefScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<ViewModel>()
    private val repo = InMemoryLearnRepository()
    private val folder = "content://test/folder"

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

    private var next = 0

    /** A scanned photo of [cameraClass] with a horizon label; its facts go into the store. */
    private fun shot(cameraClass: CameraClass?, horizon: String? = null, sharpness: Double? = null, camera: Boolean = true): ImageItem {
        val n = next++
        val photo = fitPhoto("IMG_%04d.JPG".format(n), 1_700_000_000_000L + n * 1_000L)
            .copy(scanResult = sharpness?.let { ScanResult("x", sharpnessScore = it) })
        fun f(kind: String, value: String) = TechniqueFactEntity(photo.uri, kind, value, 0.9, null, photo.fileSize, photo.lastModified, TechniqueFactDetector.DETECTOR_VERSION)
        repo.facts[photo.uri] = listOfNotNull(
            f(ScanFacts.MARKER, "1"),
            f(ScanFacts.CAPTURE_TIME, photo.lastModified.toString()),
            if (camera) f(ScanFacts.CAMERA_MAKE, cameraClass?.key ?: "x") else null,
            if (camera) f(ScanFacts.CAMERA_MODEL, "M-${cameraClass?.key}") else null,
            cameraClass?.let { f(ScanFacts.CAMERA_CLASS, it.key) },
            horizon?.let { f("composition.horizon", it) },
        )
        return photo
    }

    /** 25 phone frames (10 tilted: the focus), 21 camera frames, 3 drone frames. */
    private fun mixedFolder(): List<ImageItem> =
        List(25) { shot(CameraClass.PHONE, if (it < 15) "level" else "tilted") } +
            List(21) { shot(CameraClass.INTERCHANGEABLE, "level") } +
            List(3) { shot(CameraClass.DRONE, "level") }

    private val shown = mutableStateOf(true)
    private lateinit var selector: SelectorViewModel
    private lateinit var learn: LearnViewModel
    private lateinit var scan: ScanImagesUseCase

    private fun host(photos: List<ImageItem>, scanFirst: Boolean = false) {
        scan = mockk(relaxed = true) { every { this@mockk(any(), any()) } returns flowOf(ScanProgress(photos.size, photos.size, "", emptyMap())) }
        selector = fitSelectorViewModel(context, appScope, FitSelectorSettings(), photos, scanImagesUseCase = scan).also { viewModels += it }
        if (scanFirst) selector.startScan()
        learn = LearnViewModel(repo).also { viewModels += it }
        val composition = LearnCompositionViewModel(mockk(relaxed = true)).also { viewModels += it }
        rule.setContent {
            if (shown.value) {
                InspectScreen(
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                    viewModel = selector,
                    compositionViewModel = composition,
                    learnViewModel = learn,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun debrief() {
        rule.onNodeWithTag(LearnTab.DEBRIEF.tag).performClick()
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun inGroup(c: CameraClass, tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag("debrief_group_${c.key}"))

    private fun spoken(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    @Test
    fun `a mixed folder gives one card per class, the largest open first, unknown last`() {
        host(mixedFolder() + List(2) { shot(null, camera = false) })
        debrief()
        listOf(CameraClass.PHONE, CameraClass.INTERCHANGEABLE, CameraClass.DRONE, CameraClass.UNKNOWN).forEach {
            assertEquals("card of $it", 1, count("debrief_group_${it.key}"))
        }
        val tops = listOf(CameraClass.PHONE, CameraClass.INTERCHANGEABLE, CameraClass.DRONE, CameraClass.UNKNOWN)
            .map { rule.onNodeWithTag("debrief_group_${it.key}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("by photo count, unknown last", tops.sorted(), tops)
        // With three or more classes only the largest is open.
        assertEquals(1, rule.onAllNodes(inGroup(CameraClass.PHONE, "debrief_metric_level_horizon"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(inGroup(CameraClass.INTERCHANGEABLE, "debrief_metric_level_horizon"), useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onAllNodes(inGroup(CameraClass.INTERCHANGEABLE, "debrief_expand_interchangeable"), useUnmergedTree = true).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(inGroup(CameraClass.INTERCHANGEABLE, "debrief_metric_level_horizon"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the camera chip changes the class and keeps it`() {
        host(mixedFolder())
        debrief()
        assertEquals("Shot with drone, change", rule.onAllNodes(inGroup(CameraClass.DRONE, "debrief_camera_chip"), useUnmergedTree = true)
            .onFirst().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString())
        rule.onAllNodes(inGroup(CameraClass.DRONE, "debrief_camera_chip"), useUnmergedTree = true).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("debrief_class_action").performClick()
        rule.waitForIdle()
        assertEquals(CameraClass.ACTION, repo.overrides[CameraId("drone", "M-drone")])
        assertEquals(1, count("debrief_group_action"))
        assertEquals(0, count("debrief_group_drone"))
        assertTrue("the folder's summaries are stored again", folder in repo.shoots)
    }

    @Test
    fun `a folder not scanned says so and offers the scan`() {
        host(listOf(fitPhoto("A.JPG", 1_000), fitPhoto("B.JPG", 2_000)))
        debrief()
        assertEquals(1, count("debrief_not_scanned"))
        rule.onNodeWithTag("debrief_scan").performClick()
        rule.waitForIdle()
        verify(atLeast = 1) { scan(any(), any()) }
    }

    @Test
    fun `no camera data is said once`() {
        host(List(3) { shot(null, horizon = "level", camera = false) })
        debrief()
        assertEquals(1, count("debrief_no_camera_data"))
        assertEquals(1, count("debrief_group_unknown"))
    }

    @Test
    fun `Learn opens on Debrief once after a scan, else on the tab last used`() {
        host(mixedFolder(), scanFirst = true)
        assertEquals(LearnTab.DEBRIEF, learn.tab.value)
        rule.onNodeWithTag(LearnTab.LESSONS.tag).performClick()
        rule.waitForIdle()

        shown.value = false
        rule.waitForIdle()
        shown.value = true
        rule.waitForIdle()
        assertEquals("no new scan: the last tab", LearnTab.LESSONS, learn.tab.value)

        shown.value = false
        rule.waitForIdle()
        Thread.sleep(2)
        selector.startScan()
        shown.value = true
        rule.waitForIdle()
        assertEquals("a new scan: Debrief", LearnTab.DEBRIEF, learn.tab.value)
    }

    @Test
    fun `a count is one spoken phrase with the previous shoot's value`() {
        val previous = ShootSummary(CameraClass.PHONE, listOf("Phone"), 1L, 2L, 40, listOf(MetricCount(DebriefMetric.LEVEL_HORIZON, 30, 40)), false, 3L)
        repo.summaries[folder] = listOf(
            ShootSummary(CameraClass.PHONE, listOf("Phone"), 5L, 6L, 25, listOf(MetricCount(DebriefMetric.LEVEL_HORIZON, 15, 25)), false, 7L, previous),
        )
        host(mixedFolder())
        debrief()
        assertEquals("Level horizon, 15 of 25, before 75 percent", spoken("debrief_metric_level_horizon"))
    }

    @Test
    fun `Track keeps the focus as the goal, and a later shoot shows it`() {
        host(mixedFolder())
        debrief()
        rule.onAllNodes(inGroup(CameraClass.PHONE, "debrief_track"), useUnmergedTree = true).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("debrief:phone:level_horizon", repo.goal?.lessonId)
        assertEquals("the shoot it was set on is its baseline", 0, count("debrief_goal"))
    }

    @Test
    fun `a shoot after the goal was set shows it`() {
        repo.goal = PracticeGoalEntity("debrief:phone:level_horizon", 1L, 0.5, 30)
        host(mixedFolder())
        debrief()
        val line = rule.onNodeWithTag("debrief_goal", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertEquals("Goal: 60 % (was 50 %)", line)
    }

    @Test
    fun `the worked pair asks first, reveals after a choice, and the rating keys do nothing`() {
        host(listOf(shot(CameraClass.PHONE, sharpness = 120.0), shot(CameraClass.PHONE, sharpness = 30.0)))
        debrief()
        rule.onNodeWithTag("debrief_open_pair").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, count("debrief_pair_a"))
        assertEquals(1, count("debrief_pair_b"))
        assertEquals("no answer before the guess", 0, count("debrief_pair_explanation"))
        assertEquals(0, count("debrief_pair_value_a"))

        val root = rule.onNodeWithTag("inspect_screen")
        root.performKeyInput { pressKey(Key.Three) }
        rule.waitForIdle()
        assertEquals("3 rates nothing in the pair view", 0, selector.uiState.value.images.count { it.rating != 0 })

        root.performKeyInput { pressKey(Key.A) }
        rule.waitForIdle()
        val explanation = rule.onNodeWithTag("debrief_pair_explanation", useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue(explanation.getOrNull(SemanticsProperties.LiveRegion) != null)
        assertTrue(explanation.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }.startsWith("Right: A"))
        assertEquals(1, count("debrief_pair_value_a"))

        root.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals("Esc closes the pair view, not Learn", 0, count("debrief_pair_view"))
        assertEquals(1, count("inspect_screen"))
    }

    @Test
    fun `Show me reveals without a guess`() {
        host(listOf(shot(CameraClass.PHONE, sharpness = 30.0), shot(CameraClass.PHONE, sharpness = 120.0)))
        debrief()
        rule.onNodeWithTag("debrief_open_pair").performScrollTo().performClick()
        rule.onNodeWithTag("debrief_pair_reveal").performClick()
        rule.waitForIdle()
        val text = rule.onNodeWithTag("debrief_pair_explanation", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertTrue(text, text.startsWith("B is the better frame."))
    }

    @Test
    fun `Show N puts the photos behind the number in the filmstrip`() {
        host(mixedFolder())
        debrief()
        rule.onAllNodes(inGroup(CameraClass.PHONE, "debrief_show_problems"), useUnmergedTree = true).onFirst().performScrollTo().performClick()
        rule.waitForIdle()
        // The 10 tilted frames, and no other.
        assertEquals(1, count("filmstrip_thumb_9"))
        assertEquals(0, count("filmstrip_thumb_10"))
    }
}
