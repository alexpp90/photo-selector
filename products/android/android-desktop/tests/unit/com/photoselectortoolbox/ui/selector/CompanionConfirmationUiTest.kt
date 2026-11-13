package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.CompanionFile
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.curation.SnackbarLifetime
import com.photoselectortoolbox.domain.interaction.FilingAction
import com.photoselectortoolbox.viewmodel.CompanionConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The card and the delete dialog show "+2" and speak "and 2 companion files"
 * ([USER 2026-11-12] decision #33 (1)): what is drawn and what a screen reader reads.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@Requirement("REQ-AND-LAYOUT.21")
class CompanionConfirmationUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val bundle = ImageItem(
        uri = "content://t/folder/DSC_0003.JPG",
        fileName = "DSC_0003.JPG",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        companions = listOf(
            CompanionFile(uri = "content://t/folder/DSC_0003.ARW", fileName = "DSC_0003.ARW"),
            CompanionFile(uri = "content://t/folder/DSC_0003.xmp", fileName = "DSC_0003.xmp"),
        ),
    )

    private fun node(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).onFirst().fetchSemanticsNode().config

    private fun text(tag: String) = node(tag).getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun spoken(tag: String) = node(tag).getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    @Test
    fun `the card shows +2 and speaks the companion files`() {
        val line = CompanionConfirmation.filed(FilingAction.MOVE, bundle, "Selection")
        rule.setContent {
            SelectorSnackbar(
                message = line.visible,
                spoken = line.spoken,
                onUndo = {},
                onDismiss = {},
                sequence = 1,
                lifetime = SnackbarLifetime.MOVE,
            )
        }
        assertEquals("Moved DSC_0003 +2 · Selection", text("selector_snackbar_message"))
        assertEquals("Moved DSC_0003.JPG and 2 companion files to Selection", spoken("selector_snackbar_message"))
    }

    @Test
    fun `a card without a spoken line reads its text`() {
        rule.setContent {
            SelectorSnackbar(message = "Deleted", onUndo = null, onDismiss = {}, sequence = 1)
        }
        assertEquals("Deleted", text("selector_snackbar_message"))
        assertNull(spoken("selector_snackbar_message"))
    }

    @Test
    fun `the delete dialog's title and body name the companions`() {
        rule.setContent {
            Column {
                DeleteDialogTitle(bundle)
                DeleteDialogBody(bundle, message = "Delete \"DSC_0003.JPG\"?")
            }
        }
        assertEquals("Delete DSC_0003 +2?", text("delete_dialog_title"))
        assertEquals("Delete DSC_0003.JPG and 2 companion files?", spoken("delete_dialog_title"))
        assertEquals("Also deletes ARW, XMP", text("delete_dialog_also"))
    }

    @Test
    fun `a lone photo keeps the plain dialog`() {
        val lone = bundle.copy(companions = emptyList())
        rule.setContent {
            Column {
                DeleteDialogTitle(lone)
                DeleteDialogBody(lone, message = "Delete \"DSC_0003.JPG\"?")
            }
        }
        assertEquals(DELETE_DIALOG_TITLE, text("delete_dialog_title"))
        assertEquals(0, rule.onAllNodes(hasTestTag("delete_dialog_also"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
