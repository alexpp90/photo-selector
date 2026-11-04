package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.phototok.domain.PhotoFolders
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The folder dialog in German at font scale 2.0 on a 360 dp phone: options stay ≥ 48 dp and inside it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class FolderTargetDialogTextLoadTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun germanAtFontScale2_optionsAreFullTargetsInsideTheDialog() {
        rule.setContent {
            PhotoTokTheme {
                FolderTargetDialog(
                    title = "Ordner für Links-Wischen",
                    defaultSubfolder = PhotoFolders.LEFT_SWIPE,
                    isCustom = true,
                    onUseDefault = {},
                    onChooseFolder = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        val dialog = rule.onNodeWithTag("folder_target_dialog", useUnmergedTree = true).getBoundsInRoot()
        for (tag in listOf("folder_target_default", "folder_target_choose", "folder_target_cancel")) {
            val b = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$tag ≥ 48 dp tall (${b.height})", b.height >= 48.dp)
            assertTrue("$tag inside the dialog", b.left >= dialog.left && b.right <= dialog.right && b.bottom <= dialog.bottom)
        }
    }
}
