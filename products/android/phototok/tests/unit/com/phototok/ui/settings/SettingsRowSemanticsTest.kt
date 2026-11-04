package com.phototok.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.phototok.ui.components.ViewerBottomBar
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
 * A settings row is one TalkBack stop that is the switch or the radio button itself, and a disabled
 * Revert is a disabled button, not an unnamed image (review 2026-11-03, JP-UX-7).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SettingsRowSemanticsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun nodesWith(role: Role) =
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, role)).fetchSemanticsNodes()

    /** Every node TalkBack can activate: a row that is clickable and a Switch inside it are two stops. */
    private val focusable = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

    @Test
    fun aToggleRowIsOneSwitchStopAndTogglesFromTheRow() {
        var checked = false
        rule.setContent {
            PhotoTokTheme {
                SettingsToggleItem(title = "Milestones", checked = checked, onCheckedChange = { checked = it }) // ui-text: ignore test fixture
            }
        }
        rule.waitForIdle()
        assertEquals("one switch stop, not row + switch", 1, rule.onAllNodes(focusable).fetchSemanticsNodes().size)
        val row = nodesWith(Role.Switch)
        assertEquals(1, row.size)
        assertEquals(ToggleableState.Off, row.first().config.getOrNull(SemanticsProperties.ToggleableState))
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).onFirst().performClick()
        assertTrue(checked)
    }

    @Test
    fun aRadioOptionIsOneRadioStop() {
        rule.setContent {
            PhotoTokTheme {
                SettingsRadioGroup(
                    title = "Format", // ui-text: ignore test fixture
                    options = listOf(1 to "RAW", 2 to "JPEG"), // ui-text: ignore test fixture
                    selected = 1,
                    onSelect = {},
                )
            }
        }
        rule.waitForIdle()
        assertEquals("one stop per option", 2, rule.onAllNodes(focusable).fetchSemanticsNodes().size)
        assertEquals(2, nodesWith(Role.RadioButton).size)
    }

    @Test
    fun aDisabledRevertIsADisabledButton() {
        rule.setContent {
            PhotoTokTheme {
                Column {
                    ViewerBottomBar(
                        canRevert = false,
                        onRevert = {},
                        onJumpToSelection = {},
                        onShare = {},
                        onGoToLanding = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val node = rule.onAllNodes(hasTestTag("viewer_revert_button")).onFirst().fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertTrue("Revert says it is disabled", node.config.contains(SemanticsProperties.Disabled))
    }
}
