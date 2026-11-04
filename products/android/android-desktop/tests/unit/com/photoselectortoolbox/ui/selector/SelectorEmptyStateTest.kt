package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** An open folder with no photos is not "no folder" (FD-UX-5). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectorEmptyStateTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the kind follows what is known about the folder`() {
        fun kind(uri: String?, grant: Boolean = false, loading: Boolean = false, enumerating: Boolean = false) =
            selectorEmptyKind(uri, grant, loading, enumerating)

        assertEquals(SelectorEmptyKind.NO_FOLDER, kind(null))
        assertEquals(SelectorEmptyKind.MISSING_GRANT, kind(null, grant = true))
        assertEquals(SelectorEmptyKind.OPENING, kind("content://f", loading = true))
        assertEquals(SelectorEmptyKind.OPENING, kind("content://f", enumerating = true))
        assertEquals(SelectorEmptyKind.EMPTY_FOLDER, kind("content://f"))
    }

    @Test
    fun `an open folder without photos names itself and offers another folder`() {
        rule.setContent {
            EmptySelectorState(
                kind = selectorEmptyKind("content://f", false, false, false),
                folderName = "Wedding",
                onOpenFolder = {},
            )
        }

        rule.onNodeWithTag("empty_folder_card").assertIsDisplayed()
        rule.onNodeWithText("Wedding holds no supported photos.").assertIsDisplayed()
        rule.onNodeWithText("Open Folder").assertIsDisplayed()
    }
}
