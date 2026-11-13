package com.photoselectortoolbox.ui.selector

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.ui.fit.FitCell
import com.photoselectortoolbox.ui.fit.FitSelectorSettings
import com.photoselectortoolbox.ui.fit.FitWindow
import com.photoselectortoolbox.ui.fit.fitExif
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.fitSelectorViewModel
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * The current frame's readout, through the shipped [SelectorScreen] and its ViewModel: a frame of
 * a burst shows its sharpness as its place in the burst ("2nd of 3"), spoken with the centre and
 * the figure; a frame in no burst keeps the figure. The row says "centre" where the score is
 * measured (backlog 2026-11-11 / 2026-11-12, `BurstRank`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1480dp-h924dp-land-xhdpi")
class SharpnessRankReadoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val appScope = CoroutineScope(dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    private fun photo(name: String, modified: Long, sharpness: Double): ImageItem =
        fitPhoto(name, modified).copy(
            exifData = fitExif,
            scanResult = ScanResult(filePath = name, sharpnessScore = sharpness, noiseLevel = 1.0),
        )

    /** Three frames a second apart with consecutive camera names: one burst once Bursts is on. */
    private val burst = listOf(
        photo("DSC_0003.JPG", 3_000, 80.0), photo("DSC_0002.JPG", 2_000, 60.0), photo("DSC_0001.JPG", 1_000, 40.0),
    )

    private fun show(photos: List<ImageItem>, index: Int, grouping: Boolean) {
        val vm = fitSelectorViewModel(
            ApplicationProvider.getApplicationContext(), appScope,
            FitSelectorSettings(details = true, filmstrip = false, grouping = grouping), photos,
        )
        rule.waitUntil(10_000) { !grouping || vm.uiState.value.groups.isNotEmpty() }
        vm.navigateToImage(index)
        val cell = FitCell(1480, 924, 1f)
        rule.setContent {
            FitWindow(cell) {
                PhotoSelectorToolboxTheme { SelectorScreen(windowSizeClass = cell.windowSizeClass, viewModel = vm) }
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag("current_readout")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private val inReadout = hasAnyAncestor(hasTestTag("current_readout"))

    private fun readoutTexts(): List<String> =
        rule.onAllNodes(inReadout, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    private fun readoutSpoken(): List<String> =
        rule.onAllNodes(inReadout, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    @Test
    fun `a frame of a burst shows its place in the burst`() {
        show(burst, index = 1, grouping = true)
        val texts = readoutTexts()
        assertTrue("$texts", "2nd of 3" in texts)
        assertTrue("the area is named on the row: $texts", "centre" in texts)
        val spoken = readoutSpoken().single { it.startsWith("Sharpness") }
        assertEquals("Sharpness at the centre of the frame 2nd of 3 in its burst, 60.0, higher is better", spoken)
    }

    @Test
    fun `with Bursts off a frame keeps its figure`() {
        show(burst, index = 1, grouping = false)
        val texts = readoutTexts()
        assertTrue("$texts", "60.0" in texts)
        assertTrue(texts.none { it.contains(" of ") })
        assertTrue("centre" in texts)
        assertEquals(1, rule.onAllNodes(hasText("Sharpness") and inReadout, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
