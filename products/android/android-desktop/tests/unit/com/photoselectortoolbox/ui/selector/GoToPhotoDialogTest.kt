package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoJump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Go to photo": what the field shows while typing, what Go and Enter do, and that
 * a dialog that did not resolve stays open. Hosted directly, so it runs on every
 * emulator and under Robolectric alike.
 */
@Requirement("REQ-AND-WORKFLOW.07")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GoToPhotoDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun photo(name: String) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
    )

    private val images = listOf(photo("DSC_0003.JPG"), photo("DSC_0002.JPG"), photo("DSC_0001.JPG"))

    private class Host {
        val jumped = mutableListOf<String>()
        var dismissed = 0
    }

    private fun host(state: Host = Host()): Host {
        rule.setContent {
            GoToPhotoDialog(
                images = images,
                ratingFilter = RatingFilter.All,
                onGo = { text ->
                    state.jumped += text
                    PhotoJump.resolve(text, images)
                },
                onDismiss = { state.dismissed++ },
            )
        }
        return state
    }

    private fun supportShows(text: String): Boolean =
        rule.onAllNodes(hasTestTag("go_to_photo_support") and hasText(text), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the field is on screen with its two buttons`() {
        host()

        rule.onNodeWithTag("go_to_photo_field").assertIsDisplayed()
        rule.onNodeWithTag("go_to_photo_go").assertIsDisplayed()
        rule.onNodeWithTag("go_to_photo_cancel").assertIsDisplayed()
    }

    @Test
    fun `a number previews the file name it would land on`() {
        host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("2")

        assertTrue(supportShows("DSC_0002.JPG"))
    }

    @Test
    fun `a typed 1 stays in the field and jumps nowhere until Go`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("1")

        assertTrue(supportShows("DSC_0003.JPG"))
        assertTrue("typing never jumps", state.jumped.isEmpty())
        assertEquals(0, state.dismissed)
    }

    @Test
    fun `Go jumps and closes the dialog`() {
        val state = host()
        rule.onNodeWithTag("go_to_photo_field").performTextInput("3")

        rule.onNodeWithTag("go_to_photo_go").performClick()

        assertEquals(listOf("3"), state.jumped)
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `Enter is Go`() {
        val state = host()
        rule.onNodeWithTag("go_to_photo_field").performTextInput("dsc_0001")

        rule.onNodeWithTag("go_to_photo_field").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("dsc_0001"), state.jumped)
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `an out of range number says the range and keeps the dialog open`() {
        val state = host()
        rule.onNodeWithTag("go_to_photo_field").performTextInput("9")
        assertFalse("no scolding before Go", supportShows("Enter 1–3"))

        rule.onNodeWithTag("go_to_photo_go").performClick()

        assertTrue(supportShows("Enter 1–3"))
        assertEquals(0, state.dismissed)
        assertTrue(PhotoJump.resolve("9", images) is JumpResolution.OutOfRange)
    }

    @Test
    fun `an empty Go says the range too`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_go").performClick()

        assertTrue(supportShows("Enter 1–3"))
        assertEquals(0, state.dismissed)
    }

    @Test
    fun `a name that matches nothing says so`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("xyz")

        assertTrue(supportShows("No match"))
        rule.onNodeWithTag("go_to_photo_go").performClick()
        assertEquals(0, state.dismissed)
    }

    @Test
    fun `Escape and Cancel close it without jumping`() {
        val state = host()
        rule.onNodeWithTag("go_to_photo_field").performTextInput("2")

        rule.onNodeWithTag("go_to_photo_field").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, state.dismissed)

        rule.onNodeWithTag("go_to_photo_cancel").performClick()
        assertEquals(2, state.dismissed)
        assertTrue(state.jumped.isEmpty())
    }
}
