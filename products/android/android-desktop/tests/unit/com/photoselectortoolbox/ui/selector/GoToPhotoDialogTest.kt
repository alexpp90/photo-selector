package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.JumpResolution
import com.photoselectortoolbox.domain.session.PhotoDates
import com.photoselectortoolbox.domain.session.PhotoJump
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Go to photo": what the field shows while typing — a position, a name or a date — what Go
 * and Enter do, that a dialog that did not resolve stays open, and the calendar button that
 * swaps a date picker into the same dialog. Hosted directly, so it runs on every emulator
 * and under Robolectric alike.
 */
@Requirement("REQ-AND-WORKFLOW.07")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GoToPhotoDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 4)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(zone).toEpochMilli()

    private fun photo(name: String, taken: Long = 1) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = taken,
        mimeType = "image/jpeg",
    )

    // Newest first, the order the selector opens in: 12 Oct, 10 Oct, 3 Oct 2026.
    private val images = listOf(
        photo("DSC_0003.JPG", at(2026, 10, 12, 14, 32)),
        photo("DSC_0002.JPG", at(2026, 10, 10, 9, 5)),
        photo("DSC_0001.JPG", at(2026, 10, 3)),
    )

    private class Host {
        val jumped = mutableListOf<String>()
        val datesJumped = mutableListOf<LocalDate>()
        var dismissed = 0
    }

    private fun host(state: Host = Host(), list: List<ImageItem> = images): Host {
        val dates = PhotoDates.of(list, zone)
        rule.setContent {
            GoToPhotoDialog(
                images = list,
                ratingFilter = RatingFilter.All,
                dates = dates,
                pickerStartDay = dates.last,
                preview = { text -> PhotoJump.preview(text, list, RatingFilter.All, zone, today) },
                onGo = { text ->
                    state.jumped += text
                    PhotoJump.resolve(text, list, RatingFilter.All, zone, today)
                },
                onJumpToDate = { day ->
                    state.datesJumped += day
                    PhotoJump.resolveDate(day, list, RatingFilter.All, zone)
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

        assertTrue(supportShows("DSC_0002.JPG · 10 Oct 2026, 09:05"))
    }

    @Test
    fun `the field says it takes a number, a name or a date`() {
        host()

        rule.onAllNodes(hasText("Number, name or date"), useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun `the empty field says which days the folder spans`() {
        host()

        assertTrue(supportShows("3 Oct 2026 – 12 Oct 2026"))
    }

    @Test
    fun `a typed date previews the photo of that day and Go jumps with the text`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("10.10.2026")

        assertTrue(supportShows("DSC_0002.JPG · 10 Oct 2026, 09:05"))
        assertEquals("an exact day has no nearest-day line", 0, count("go_to_photo_nearest_day"))
        rule.onNodeWithTag("go_to_photo_go").performClick()
        assertEquals(listOf("10.10.2026"), state.jumped)
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `a day without photos previews the nearest day in the neutral colour and Go still jumps`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("7.10.2026")

        assertTrue(supportShows("DSC_0002.JPG · 10 Oct 2026, 09:05"))
        rule.onAllNodes(
            hasTestTag("go_to_photo_nearest_day") and hasText("No photos on 7 Oct 2026 — showing 10 Oct 2026"),
            useUnmergedTree = true,
        ).onFirst().assertIsDisplayed()
        // Not an error: the field is not flagged, and Go works.
        assertFalse(
            rule.onNodeWithTag("go_to_photo_field").fetchSemanticsNode().config.contains(SemanticsProperties.Error),
        )
        rule.onNodeWithTag("go_to_photo_go").performClick()
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `a date still being typed is not scolded until Go`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("2026-10-")

        assertFalse(supportShows("No match"))
        assertFalse(
            rule.onNodeWithTag("go_to_photo_field").fetchSemanticsNode().config.contains(SemanticsProperties.Error),
        )
        rule.onNodeWithTag("go_to_photo_go").performClick()
        assertTrue(supportShows("No match"))
        assertEquals(0, state.dismissed)
    }

    private fun count(tag: String): Int =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    // ── The date picker ──────────────────────────────────────────────────

    private fun openPicker() {
        rule.onNodeWithTag("go_to_photo_calendar").performClick()
        rule.onNodeWithTag("go_to_photo_picker").assertIsDisplayed()
    }

    /** The picker's cell for a day (its spoken date), found by what a screen reader hears. */
    private fun dayCell(spoken: String) = rule.onAllNodes(
        hasAnyAncestor(hasTestTag("go_to_photo_picker")) and hasText(spoken, substring = true) and hasClickAction(),
        useUnmergedTree = true,
    ).onFirst()

    @Test
    fun `the calendar button is a 48 dp target named Pick a date`() {
        host()

        val node = rule.onNodeWithTag("go_to_photo_calendar").fetchSemanticsNode()
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("${node.size}", node.size.width >= min && node.size.height >= min)
        rule.onAllNodes(hasContentDescription("Pick a date"), useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun `the calendar button is disabled when no photo has a known date`() {
        host(list = listOf(photo("A.JPG", 0), photo("B.JPG", 0)))

        rule.onNodeWithTag("go_to_photo_calendar").assertIsNotEnabled()
        assertFalse("no span to show", supportShows("1 Jan 1970"))
    }

    @Test
    fun `the calendar button swaps the picker into the same dialog and Back returns to the field`() {
        host()

        openPicker()

        assertEquals("the field is replaced, not stacked", 0, count("go_to_photo_field"))
        rule.onNodeWithTag("go_to_photo_dialog").assertIsDisplayed()
        rule.onNodeWithTag("go_to_photo_back").performClick()
        rule.onNodeWithTag("go_to_photo_field").assertIsDisplayed()
    }

    @Test
    fun `the picker offers only days that have photos`() {
        host()
        openPicker()

        dayCell("October 10, 2026").assertIsEnabled()
        dayCell("October 3, 2026").assertIsEnabled()
        dayCell("October 11, 2026").assertIsNotEnabled()
    }

    @Test
    fun `picking a day jumps at once to that day and closes`() {
        val state = host()
        openPicker()

        // The dialog is short in a 320 dp-tall window, so the day may sit below the fold.
        dayCell("October 10, 2026").performScrollTo()
        dayCell("October 10, 2026").performClick()

        rule.waitForIdle()
        assertEquals(listOf(LocalDate.of(2026, 10, 10)), state.datesJumped)
        assertEquals(1, state.dismissed)
    }

    @Test
    fun `Cancel closes the picker without jumping`() {
        val state = host()
        openPicker()

        rule.onNodeWithTag("go_to_photo_cancel").performClick()

        assertEquals(1, state.dismissed)
        assertTrue(state.datesJumped.isEmpty())
    }

    @Test
    fun `the picker opens on the month of the newest day`() {
        host()
        openPicker()

        // October 2026 is showing without paging: the newest photo is 12 Oct.
        dayCell("October 12, 2026").assertExists()
    }

    @Test
    fun `a typed 1 stays in the field and jumps nowhere until Go`() {
        val state = host()

        rule.onNodeWithTag("go_to_photo_field").performTextInput("1")

        assertTrue(supportShows("DSC_0003.JPG · 12 Oct 2026, 14:32"))
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
