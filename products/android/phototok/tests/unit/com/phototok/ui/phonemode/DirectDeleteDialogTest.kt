package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The delete dialog's "Don't ask" choice is reported with Delete (the screen then turns the prompt off). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-xxhdpi")
class DirectDeleteDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val confirmed = mutableListOf<Boolean>()
    private var dismissed = 0

    private fun host() {
        rule.setContent {
            PhotoTokTheme { DirectDeleteDialog(onConfirm = { confirmed += it }, onDismiss = { dismissed++ }) }
        }
        rule.waitForIdle()
    }

    @Test
    fun deleteWithoutTheChoice_reportsFalse() {
        host()
        rule.onNodeWithTag("direct_delete_dont_ask").assertIsOff()
        rule.onNodeWithTag("direct_delete_confirm_button").performClick()
        assertEquals(listOf(false), confirmed)
    }

    @Test
    fun deleteWithTheChoice_reportsTrue() {
        host()
        rule.onNodeWithTag("direct_delete_dont_ask")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        rule.onNodeWithTag("direct_delete_dont_ask").assertIsOn()
        rule.onNodeWithTag("direct_delete_confirm_button").performClick()
        assertEquals(listOf(true), confirmed)
    }

    @Test
    fun cancelWithTheChoiceTicked_deletesNothingAndAsksAgainNextTime() {
        host()
        rule.onNodeWithTag("direct_delete_dont_ask").performClick()
        rule.onNodeWithTag("direct_delete_cancel_button").performClick()
        assertTrue(confirmed.isEmpty())
        assertEquals(1, dismissed)
    }

    @Test
    fun theChoiceIsA48dpTarget() {
        host()
        val b = rule.onNodeWithTag("direct_delete_dont_ask").fetchSemanticsNode().touchBoundsInRoot
        val d = rule.density.density
        assertTrue(b.height / d >= 47.5f)
    }
}
