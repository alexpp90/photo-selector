package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.UiText
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A message Settings raises belongs to the sheet that raised it. Usability lab 2026-11-10: Pro mode
 * was switched on and the sheet closed at once; "Camera info on" then appeared when Settings was
 * opened again minutes later, because the snackbar host's state outlived the sheet and its
 * timeout only runs while the host is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-440dpi")
class SettingsSheetMessageTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun messageNodes(text: String) = rule.onAllNodes(
        hasText(text) and hasAnyAncestor(hasTestTag("settings_snackbar_host")),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun aMessageDoesNotOutliveItsSheet() {
        val text = rule.activity.getString(R.string.snackbar_pro_camera_info_on)
        var visible by mutableStateOf(true)
        var show: ((UiText) -> Unit)? = null
        rule.setContent {
            PhotoTokTheme {
                SettingsSheetHost(visible = visible, onDismissRequest = { visible = false }) { showMessage, _ ->
                    show = showMessage
                    Text("settings") // ui-text: ignore test fixture
                }
            }
        }
        rule.waitForIdle()

        rule.runOnIdle { show!!(UiText.of(R.string.snackbar_pro_camera_info_on)) }
        rule.waitUntil(timeoutMillis = 2_000) { messageNodes(text).isNotEmpty() }

        // Closed while the message shows, as Back right after the Pro switch does.
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.runOnIdle { visible = true }
        rule.waitForIdle()

        assertTrue("the reopened sheet still shows \"$text\"", messageNodes(text).isEmpty())
    }
}
