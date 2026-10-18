package com.phototok.ui.phonemode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.model.ExifData
import com.phototok.data.model.ImageItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** TOK-9: the EXIF card and the page counter share one row and can never overlap. */
@RunWith(AndroidJUnit4::class)
class PhoneModeViewerBottomHudTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val images = (1..3).map {
        ImageItem(
            uri = "content://media/external/images/media/IMG_$it",
            fileName = "DSC_LONG_FILE_NAME_000$it.ARW",
            fileSize = 1024L,
            lastModified = 1000L,
            mimeType = "image/jpeg",
            imageWidth = 1920,
            imageHeight = 1080,
            exifData = ExifData(
                shutterSpeed = 1.0 / 250, aperture = 2.8, focalLength = 85.0, iso = 6400,
                lens = "FE 24-70mm F2.8 GM II",
            ),
        )
    }

    private var suppressed by mutableStateOf(false)

    private fun host(fontScale: Float) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(Modifier.size(393.dp, 800.dp)) {
                    PhoneModeViewer(
                        images = images,
                        currentIndex = 0,
                        portraitSectionStart = -1,
                        onNavigate = {},
                        onAddToCollection = {},
                        onRequestDelete = {},
                        showExifOverlay = true,
                        bottomHudSuppressed = suppressed,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun rect(tag: String) =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().boundsInRoot

    private fun count(tag: String) =
        composeTestRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun exifCardAndCounter_neverOverlap_atLargeFontScale() {
        host(fontScale = 1.3f)
        val info = rect("viewer.info")
        val counter = rect("viewer.counter")
        assertFalse("EXIF card $info runs under counter $counter", info.overlaps(counter))
        assertTrue(info.right <= counter.left + 1f)
    }

    @Test
    fun suppressedBottomHud_hidesCounterAndExifCard() {
        host(fontScale = 1.0f)
        assertTrue(count("viewer.counter") > 0)
        composeTestRule.runOnIdle { suppressed = true }
        composeTestRule.waitUntil(3000) { count("viewer.counter") == 0 }
        assertEquals(0, count("viewer.info"))
        composeTestRule.runOnIdle { suppressed = false }
        composeTestRule.waitUntil(3000) { count("viewer.counter") > 0 }
    }

    private fun androidx.compose.ui.geometry.Rect.overlaps(o: androidx.compose.ui.geometry.Rect) =
        left < o.right && o.left < right && top < o.bottom && o.top < bottom
}
