package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The portrait info card at font scale 2.0 on a 360 dp phone (REQ-TOK-UX.05, F-TOK-028): the
 * capture date is the first line, and the long lab file name is shown whole (usability lab
 * 2026-10-24 saw "IMG_202…00.JPG" and "IMG_20…00.JPG"), in English and German.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-port-xhdpi")
class ExifCardFileNameAndDateTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val longName = "IMG_20260815_103600-EDIT.JPG" // ui-text: ignore test fixture

    private fun show(fontScale: Float, date: String? = "2026:08:15 10:36:00") {
        rule.setContent {
            PhotoTokTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = fontScale)) {
                    ViewerBottomHudRow(
                        exif = ExifData(
                            iso = 200, shutterSpeed = 1.0 / 125, aperture = 5.6, focalLength = 50.0,
                            lens = "PSTLab 50mm F1.4", // ui-text: ignore test fixture
                            dateTimeOriginal = date,
                        ),
                        fileName = longName,
                        showPageCounter = true,
                        pageIndex = 1,
                        totalCount = 18,
                        isDiscovering = false,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun layoutOf(tag: String): TextLayoutResult {
        val node = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().single()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.single()
    }

    private fun assertWhole(tag: String, expected: String) {
        val layout = layoutOf(tag)
        assertEquals("$tag shows its whole text", expected, layout.layoutInput.text.text)
        for (line in 0 until layout.lineCount) {
            assertFalse("$tag: line $line ends in an ellipsis", layout.isLineEllipsized(line))
        }
        assertFalse("$tag is cut off at the bottom", layout.didOverflowHeight)
        assertEquals("$tag: every character is laid out", expected.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    private fun dateText(): String {
        val node = rule.onAllNodes(hasTestTag("viewer.info.date"), useUnmergedTree = true).fetchSemanticsNodes().single()
        return node.config[SemanticsProperties.Text].joinToString { it.text }
    }

    @Test
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.07")
    fun english_fontScale2_dateFirstAndWholeFileName() {
        show(fontScale = 2f)
        assertEquals("Aug 15, 2026, 10:36 AM", dateText().replace(' ', ' ').replace(' ', ' '))
        assertWhole("viewer.info.date", dateText())
        assertWhole("viewer.info.file_name", longName)
        val date = rule.onAllNodes(hasTestTag("viewer.info.date"), useUnmergedTree = true).fetchSemanticsNodes().single()
        val name = rule.onAllNodes(hasTestTag("viewer.info.file_name"), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertTrue("the date is the first line", date.boundsInWindow.bottom <= name.boundsInWindow.top + 1f)
    }

    @Test
    @Config(qualifiers = "de-w360dp-h640dp-port-xhdpi")
    @Requirement("REQ-TOK-UX.05", "REQ-TOK-FEATURE.07")
    fun german_fontScale2_dateFirstAndWholeFileName() {
        show(fontScale = 2f)
        assertEquals("15.08.2026, 10:36", dateText())
        assertWhole("viewer.info.date", dateText())
        assertWhole("viewer.info.file_name", longName)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.07")
    fun noDateInTheFile_noDateLine() {
        show(fontScale = 1f, date = null)
        assertTrue(rule.onAllNodes(hasTestTag("viewer.info.date"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertWhole("viewer.info.file_name", longName)
    }
}
