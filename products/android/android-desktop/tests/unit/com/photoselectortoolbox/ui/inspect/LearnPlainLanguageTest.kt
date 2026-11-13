package com.photoselectortoolbox.ui.inspect

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ScanResult
import com.photoselectortoolbox.domain.model.SpiralOrientation
import com.photoselectortoolbox.ui.fit.fitExif
import com.photoselectortoolbox.ui.fit.fitPhoto
import com.photoselectortoolbox.ui.fit.installNoImageLoader
import com.photoselectortoolbox.ui.fit.removeNoImageLoader
import com.photoselectortoolbox.ui.selector.ScanSheetText
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.photoselectortoolbox.testing.str
import com.photoselectortoolbox.testing.en

/**
 * The Learn screen and the scan sheet speak photography, not algorithms (usability panel
 * 2026-10-24: "1/f_eff Guideline", "Phi", "Flip (TL)", "via Laplacian variance" read as data,
 * not explanation). Every text and spoken label on every Learn tab is read off the composed
 * screen, so a jargon string anywhere on it fails here.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1480dp-h924dp-land-xhdpi")
@Requirement("REQ-AND-INSPECT.01", "REQ-AND-COACH.01")
class LearnPlainLanguageTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val jargon = listOf(
        Regex("Laplacian", RegexOption.IGNORE_CASE),
        Regex("median absolute deviation", RegexOption.IGNORE_CASE),
        Regex("\\bPhi\\b"),
        Regex("f_eff"),
        Regex("\\((TL|TR|BL|BR)\\)"),
    )

    private fun offending(text: String) = jargon.filter { it.containsMatchIn(text) }.map { it.pattern }

    @Before
    fun setUp() {
        installNoImageLoader(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        removeNoImageLoader()
    }

    private fun SemanticsNode.allTexts(): List<String> {
        val own = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return own + children.flatMap { it.allTexts() }
    }

    @Test
    fun `no Learn tab shows or speaks an algorithm name`() {
        // A slow handheld telephoto frame, scanned, with high noise: every coach row is drawn.
        val photo = fitPhoto("a.jpg", 1).copy(
            rating = 3,
            exifData = fitExif.copy(shutterSpeed = 1.0 / 20, focalLength = 200.0, focalLength35mm = 300.0, iso = 3200),
            scanResult = ScanResult(filePath = "a.jpg", sharpnessScore = 12.0, noiseLevel = 6.5),
        )
        rule.setContent {
            PhotoSelectorToolboxTheme {
                InspectScreenContent(
                    currentImage = photo, images = listOf(photo), currentIndex = 0,
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1480.dp, 924.dp)),
                    onNavigateToSelector = {}, onSelectImage = {}, onPrevious = {}, onNext = {},
                    onSetRating = { _, _ -> },
                )
            }
        }
        val seen = mutableListOf<String>()
        for (tab in LearnTab.entries) {
            rule.onNodeWithTag(tab.tag).performClick()
            rule.waitForIdle()
            // The unmerged tree, so text inside merged rows is read one by one.
            seen += rule.onNode(isRoot(), useUnmergedTree = true).fetchSemanticsNode().allTexts()
        }
        val bad = seen.distinct().mapNotNull { text -> offending(text).takeIf { it.isNotEmpty() }?.let { "\"$text\" ($it)" } }
        assertTrue("jargon on the Learn screen: $bad", bad.isEmpty())
        // The guide bar names what each guide is.
        assertTrue(seen.contains(LearnText.GUIDE_GOLDEN_RATIO.en()))
        assertTrue(seen.contains(LearnText.GUIDE_FLIP_SPIRAL.en()))
    }

    /**
     * The guide toggles (Thirds, Golden ratio, Spiral, Diagonals, Horizon) said their state by
     * colour alone and to a screen reader not at all (lab 2026-11-10): each is a switch that
     * says whether it is on.
     */
    @Test
    fun `every guide toggle tells a screen reader whether it is on`() {
        val photo = fitPhoto("a.jpg", 1).copy(exifData = fitExif)
        rule.setContent {
            PhotoSelectorToolboxTheme {
                InspectScreenContent(
                    currentImage = photo, images = listOf(photo), currentIndex = 0,
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1480.dp, 924.dp)),
                    onNavigateToSelector = {}, onSelectImage = {}, onPrevious = {}, onNext = {},
                    onSetRating = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        for (tag in listOf("toggle_thirds", "toggle_golden_ratio", "toggle_spiral", "toggle_diagonals", "toggle_horizon")) {
            val before = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals("$tag role", Role.Switch, before.getOrNull(SemanticsProperties.Role))
            val state = before.getOrNull(SemanticsProperties.ToggleableState)
            assertTrue("$tag exposes no on/off state", state != null)
            rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            rule.waitForIdle()
            val after = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.ToggleableState)
            assertTrue("$tag state did not change on a tap ($state -> $after)", after != state)
        }
    }

    @Test
    fun `the scan sheet rows say what they find`() {
        val labels = ScanSheetText.all.map { str(it) }
        assertEquals(listOf("Focus quality", "Noise level", "Highlight clipping", "Shadow clipping"), labels)
        labels.forEach { assertTrue(it, offending(it).isEmpty()) }
    }

    @Test
    fun `the shutter line is one sentence with both speeds`() {
        assertEquals(
            "Fast enough to hand-hold: 1/60s beats the 1/50s minimum",
            LearnText.shutterLine(safeSeconds = 1.0 / 50, actualSeconds = 1.0 / 60)?.en(),
        )
        assertEquals(
            "Slower than hand-holding allows: 1/20s, minimum 1/50s",
            LearnText.shutterLine(safeSeconds = 1.0 / 50, actualSeconds = 1.0 / 20)?.en(),
        )
        assertEquals("Slowest speed to hand-hold: 1/50s", LearnText.shutterLine(1.0 / 50, null)?.en())
        assertNull(LearnText.shutterLine(null, 1.0 / 60)?.en())
    }

    @Test
    fun `the coach remarks on noise only when it is high`() {
        assertEquals("Grainy at ISO 3200", LearnText.noiseRemark(noiseLevel = 6.5, iso = 3200)?.en())
        assertEquals("Grainy", LearnText.noiseRemark(noiseLevel = 6.5, iso = null)?.en())
        assertNull(LearnText.noiseRemark(noiseLevel = 0.5, iso = 100)?.en())
        assertNull(LearnText.noiseRemark(noiseLevel = null, iso = 3200)?.en())
    }

    @Test
    fun `the spiral flip is named in words and says where it turns`() {
        assertEquals("Flip spiral, now turning to the top left", LearnText.flipSpiralDescription(SpiralOrientation.TOP_LEFT).en())
        SpiralOrientation.entries.forEach { assertTrue(offending(LearnText.flipSpiralDescription(it).en()).isEmpty()) }
        assertEquals("Low blur risk (12%)", LearnText.blurRisk(0.12f).en())
        assertEquals("High blur risk (80%)", LearnText.blurRisk(0.8f).en())
    }
}
