package com.photoselectortoolbox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.ui.theme.PhotoSelectorToolboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A sideways drag that ends on a Settings row does nothing; a tap still acts (backlog
 * 2026-11-12, the Frameduel sibling of a Jackpix defect). "Reset Guidance" acts without asking,
 * and a toggle or a radio row flips at once, so a drag that happened to end on one changed a
 * setting. Hosted in a vertical scroller, as the Settings screen is, which does not take a
 * sideways gesture.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class SettingsRowDragTest {

    @get:Rule
    val rule = createComposeRule()

    private fun host(content: @Composable () -> Unit) {
        rule.setContent {
            PhotoSelectorToolboxTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
        rule.waitForIdle()
    }

    private fun row(tag: String) = rule.onAllNodes(hasTestTag(tag)).onFirst()

    /** A finger put down on the row's left and dragged 300 px to the right, released on the row. */
    private fun dragAcross(tag: String) {
        row(tag).performTouchInput {
            down(Offset(left + 20f, centerY))
            repeat(10) { moveBy(Offset(30f, 0f)) }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun `a drag across the Reset Guidance row does not reset, a tap and a keyboard click do`() {
        var resets = 0
        host {
            Column(Modifier.testTag("reset_row")) {
                SettingsClickItem(title = "Reset Guidance", onClick = { resets++ })
            }
        }
        dragAcross("reset_row")
        assertEquals("a drag counted as a tap", 0, resets)

        row("reset_row").performClick()
        rule.waitForIdle()
        assertEquals(1, resets)

        rule.onAllNodes(hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("the accessibility / keyboard click still acts", 2, resets)
    }

    @Test
    fun `a drag across a switch row does not flip it`() {
        var checked by mutableStateOf(false)
        host {
            Column(Modifier.testTag("switch_row")) {
                SettingsToggleItem(title = "Bursts", checked = checked, onCheckedChange = { checked = it })
            }
        }
        dragAcross("switch_row")
        assertEquals("a drag flipped the switch", false, checked)
        row("switch_row").performClick()
        rule.waitForIdle()
        assertEquals(true, checked)
    }

    @Test
    fun `a drag across a radio row does not select it`() {
        var selected by mutableStateOf(false)
        host {
            SettingsRadioRow(label = "Newest first", selected = selected, onSelect = { selected = true }, tag = "radio_row")
        }
        dragAcross("radio_row")
        assertEquals("a drag selected the row", false, selected)
        row("radio_row").performClick()
        rule.waitForIdle()
        assertEquals(true, selected)
    }
}
