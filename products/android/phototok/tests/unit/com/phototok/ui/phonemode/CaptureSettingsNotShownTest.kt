package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.photoselector.core.Requirement
import com.photoselector.core.model.ExifData
import com.photoselector.core.model.LensSpecification
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Jackpix reads more capture settings through `:core` and shows none of them
 * (REQ-TOK-FEATURE.24, [USER 2026-11-11]): the two camera info cards render the same words
 * for a photo with every new setting as for one without, in English and German; and no
 * Jackpix source reads one of the new fields.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CaptureSettingsNotShownTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val plain = ExifData(iso = 100, shutterSpeed = 1.0 / 640, aperture = 8.0, focalLength = 35.0, lens = "Lens 35mm")

    private val withEverything = plain.copy(
        exposureProgram = 6,
        exposureMode = 1,
        exposureCompensationEv = -1.7,
        meteringMode = 4,
        flash = 77,
        whiteBalance = 1,
        digitalZoomRatio = 3.3,
        lensSpecification = LensSpecification(17.0, 123.0, 1.9, 9.1),
        customRendered = 1,
        sceneCaptureType = 2,
        gimbalPitchDegrees = -88.8,
    )

    private fun texts(node: SemanticsNode, out: MutableList<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += it }
        node.children.forEach { texts(it, out) }
    }

    private fun assertCardsUnchanged() {
        var exif by mutableStateOf(plain)
        rule.setContent {
            PhotoTokTheme {
                Column {
                    ViewerBottomHudRow(
                        exif = exif,
                        fileName = "IMG_0001.jpg", // ui-text: ignore test fixture
                        showPageCounter = true,
                        pageIndex = 0,
                        totalCount = 3,
                        isDiscovering = false,
                    )
                    ExifSidebarPanel(exif = exif, fileName = "IMG_0001.jpg") // ui-text: ignore test fixture
                }
            }
        }
        fun shown(): List<String> {
            rule.waitForIdle()
            val out = mutableListOf<String>()
            texts(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), out)
            return out
        }
        val before = shown()
        assertTrue("the cards show something to compare", before.isNotEmpty())
        exif = withEverything
        assertEquals("the two camera info cards must not show a new capture setting", before, shown())
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.24")
    fun `the camera info cards show no further capture setting in English`() {
        assertCardsUnchanged()
    }

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-440dpi")
    @Requirement("REQ-TOK-FEATURE.24")
    fun `the camera info cards show no further capture setting in German`() {
        assertCardsUnchanged()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.24")
    fun `no Jackpix source reads a capture setting that only Frameduel uses`() {
        val src = listOf(File("src"), File("products/android/phototok/src")).first { it.isDirectory }
        val fields = listOf(
            "exposureProgram", "exposureMode", "exposureCompensationEv", "meteringMode", "flash",
            "whiteBalance", "digitalZoomRatio", "lensSpecification", "customRendered", "sceneCaptureType",
            "gimbalPitchDegrees",
        )
        val read = Regex("""\.(${fields.joinToString("|")})\b""")
        val offenders = mutableListOf<String>()
        for (f in src.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, line ->
                if (read.containsMatchIn(line)) offenders += "${f.name}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("Jackpix must not read these ExifData fields (REQ-TOK-FEATURE.24): $offenders", offenders.isEmpty())
    }
}
