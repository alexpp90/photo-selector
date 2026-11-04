package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import com.phototok.data.model.RecentPath
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The landing screen's custom clickables are buttons to TalkBack and keep a 48 dp target
 * (review 2026-11-03, JP-UX-5: "Re-select" was 44 dp, it and Resume and the recent rows had no role).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp")
class PhoneModeLandingButtonsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun landing(missingGrant: Boolean) {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeLanding(
                    sourceFolderUri = "content://tree/photos",
                    sourceFolderName = "Photos",
                    collectionFolderName = "Selection",
                    isLoading = false,
                    missingGrantPrompt = missingGrant,
                    onReselectFolder = {},
                    onSelectSource = {},
                    onSelectCollection = {},
                    onStart = {},
                    recentPaths = listOf(RecentPath("content://tree/card", "Card")),
                    unmountedRecentUris = setOf("content://tree/card"),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertButton(tag: String) {
        val node = rule.onAllNodes(hasTestTag(tag)).onFirst()
        node.performScrollTo()
        val semantics = node.fetchSemanticsNode()
        assertEquals("$tag role", Role.Button, semantics.config.getOrNull(SemanticsProperties.Role))
        val height = semantics.size.height / rule.density.density
        assertTrue("$tag is $height dp tall", height >= 48f - 0.5f)
    }

    @Test
    fun reselectIsA48dpButton() {
        landing(missingGrant = true)
        assertButton("reselect_folder_button")
    }

    @Test
    fun resumeAndRecentRowsAreButtons() {
        landing(missingGrant = false)
        assertButton("landing_start_browsing_button")
        assertButton("landing_recent_row")
    }
}
