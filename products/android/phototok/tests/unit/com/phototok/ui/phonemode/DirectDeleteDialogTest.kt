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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.DeleteSplit
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertFalse
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

    private fun host(split: DeleteSplit = DeleteSplit.permanentOnly(1)) {
        rule.setContent {
            PhotoTokTheme { DirectDeleteDialog(onConfirm = { confirmed += it }, onDismiss = { dismissed++ }, split = split) }
        }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun allText(): String = rule.onAllNodes(hasAnyAncestor(hasTestTag("direct_delete_dialog")) or hasTestTag("direct_delete_dialog"), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { t -> t.text } }
        .joinToString(" | ")

    /** A phone photo in MediaStore: "Move to Trash?", the Trash button, the bin glyph ([USER 2026-11-12] decision #13). */
    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun binSource_saysTrash() {
        host(DeleteSplit(bin = 2, permanent = 0))
        rule.onAllNodesWithText(str(R.string.dialog_title_trash)).onFirst().assertExists()
        rule.onAllNodesWithText(str(R.string.dialog_title_direct_delete)).assertCountEquals(0)
        rule.onNodeWithTag("direct_delete_confirm_button", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText(str(R.string.action_trash))))
        rule.onAllNodesWithTag("direct_delete_icon_bin", useUnmergedTree = true).onFirst().assertExists()
        rule.onNodeWithTag("direct_delete_confirm_button").assertIsNotFocused()
    }

    /** SD card, USB, cloud: "Delete permanently?" and no word of a bin anywhere in the dialog. */
    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun noBinSource_neverClaimsATrash() {
        host(DeleteSplit.permanentOnly(3))
        rule.onAllNodesWithText(str(R.string.dialog_title_direct_delete)).onFirst().assertExists()
        rule.onAllNodesWithTag("direct_delete_icon_permanent", useUnmergedTree = true).onFirst().assertExists()
        val text = allText().lowercase()
        for (word in listOf("trash", "bin", str(R.string.action_trash).lowercase())) {
            assertFalse("'$word' in a dialog without a bin: $text", Regex("\\b$word\\b").containsMatchIn(text))
        }
        rule.onNodeWithTag("direct_delete_confirm_button").assertIsNotFocused()
    }

    /** A phone photo with a companion MediaStore has no bin for: the count title and both counts. */
    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun mixedBatch_countsAddUp() {
        host(DeleteSplit(bin = 2, permanent = 1))
        val title = rule.activity.resources.getQuantityString(R.plurals.dialog_title_delete_all, 3, 3)
        rule.onAllNodesWithText(title).onFirst().assertExists()
        rule.onAllNodesWithText(str(R.string.dialog_body_trash_split, 2, 1)).onFirst().assertExists()
        rule.onAllNodesWithTag("direct_delete_icon_permanent", useUnmergedTree = true).onFirst().assertExists()
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
