package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.photoselector.core.model.ExifData
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The camera-info panels show the file name as it is on disk (review 2026-11-03, JP-UX-6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ExifPanelFileNameTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothPanelsShowTheNameUnchanged() {
        val name = "dsc_0001.arw" // ui-text: ignore test fixture
        rule.setContent {
            PhotoTokTheme {
                Column {
                    ExifOverlay(exif = ExifData(iso = 100), fileName = name)
                    ExifSidebarPanel(exif = ExifData(iso = 100), fileName = name, modifier = Modifier.width(125.dp))
                }
            }
        }
        rule.waitForIdle()
        for (panel in listOf("viewer.info", "landscape.exif_panel")) {
            val nodes = rule.onAllNodes(
                hasText(name) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(panel)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("$panel shows $name as it is", nodes.isNotEmpty())
        }
    }
}
