package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.phototok.R
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The landing screen's Pro mode row: one toggle target (`Role.Switch`), a separate 48 dp help button
 * and the help dialog. Runs on the JVM so CI enforces it without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class PhoneModeLandingProModeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var pro by mutableStateOf(false)
    private val changes = mutableListOf<Boolean>()

    private fun host() {
        rule.setContent {
            PhotoTokTheme {
                Box(Modifier.size(393.dp, 851.dp)) {
                    PhoneModeLanding(
                        sourceFolderUri = null,
                        sourceFolderName = "",
                        collectionFolderName = "Selection",
                        isLoading = false,
                        onSelectSource = {},
                        onSelectCollection = {},
                        onStart = {},
                        proMode = pro,
                        onProModeChange = { changes += it; pro = it },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun toggleIsOneSwitchTarget_thatFollowsAndReportsTheState() {
        host()
        rule.onNodeWithTag("landing_pro_mode_toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
        rule.onNodeWithTag("landing_pro_mode_toggle").performClick()
        assertEquals(listOf(true), changes)
        rule.onNodeWithTag("landing_pro_mode_toggle").assertIsOn()
        rule.onNodeWithTag("landing_pro_mode_toggle").performClick()
        assertEquals(listOf(true, false), changes)
        rule.onNodeWithTag("landing_pro_mode_toggle").assertIsOff()
    }

    @Test
    fun theLabelIsPartOfTheToggleTarget() {
        host()
        // The merged node carries the name: a screen reader says "Pro mode, switch, off".
        rule.onNodeWithTag("landing_pro_mode_toggle")
            .assert(hasText(text(R.string.label_pro_mode)))
    }

    @Test
    fun targetsAreBigEnough_byTheirOwnTags() {
        host()
        val density = rule.density.density
        val toggle = rule.onNodeWithTag("landing_pro_mode_toggle").fetchSemanticsNode().boundsInRoot
        val help = rule.onNodeWithTag("landing_pro_mode_help").fetchSemanticsNode().touchBoundsInRoot
        assertTrue("toggle row ${toggle.height / density} dp < 56 dp", toggle.height / density >= 55.5f)
        assertTrue("help target ${help.width / density} x ${help.height / density} dp < 48 dp",
            help.width / density >= 47.5f && help.height / density >= 47.5f)
        // The help button is its own target, not part of the toggle.
        assertTrue(help.left >= toggle.right - 1f)
    }

    @Test
    fun helpButtonOpensTheDialog_andGotItClosesIt() {
        host()
        rule.onAllNodes(hasTestTag("pro_mode_help_dialog")).fetchSemanticsNodes().also { assertEquals(0, it.size) }

        rule.onNodeWithTag("landing_pro_mode_help").performClick()
        rule.onNodeWithTag("pro_mode_help_dialog").assertIsDisplayed()
        val inDialog = hasAnyAncestor(hasTestTag("pro_mode_help_dialog"))
        listOf(
            R.string.dialog_title_pro_mode,
            R.string.label_pro_camera_info,
            R.string.label_pro_swipes_folders,
            R.string.label_raw_jpeg_pair,
            R.string.action_got_it,
        ).forEach { id ->
            rule.onAllNodes(hasText(text(id)) and inDialog, useUnmergedTree = true).onFirst().assertIsDisplayed()
        }
        // Opening the help changes nothing.
        assertTrue(changes.isEmpty())

        rule.onNodeWithTag("pro_mode_help_got_it").performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("pro_mode_help_dialog")).fetchSemanticsNodes().also { assertEquals(0, it.size) }
    }
}
