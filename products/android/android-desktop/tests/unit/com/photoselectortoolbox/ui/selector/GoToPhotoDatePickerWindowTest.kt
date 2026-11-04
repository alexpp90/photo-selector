package com.photoselectortoolbox.ui.selector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselector.core.Requirement
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.domain.session.PhotoDates
import com.photoselectortoolbox.domain.session.PhotoJump
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Material 3 date picker is ~400 dp tall; the manifest's smallest window is 600 x 480 dp,
 * where the dialog cannot show it whole. The picker scrolls inside the dialog, so every day is
 * still reachable (REQ-AND-WORKFLOW.07, `[USER 2026-11-04]` "jump to a date").
 */
@Requirement("REQ-AND-WORKFLOW.07")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w600dp-h480dp-xhdpi")
class GoToPhotoDatePickerWindowTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneOffset.UTC

    private fun photo(name: String, day: Int) = ImageItem(
        uri = "content://test/folder/$name",
        fileName = name,
        fileSize = 1,
        lastModified = LocalDateTime.of(2026, 10, day, 9, 0).toInstant(zone).toEpochMilli(),
        mimeType = "image/jpeg",
    )

    private val images = listOf(photo("C.JPG", 28), photo("B.JPG", 10), photo("A.JPG", 3))
    private val jumped = mutableListOf<LocalDate>()
    private var dismissed = 0

    @Test
    fun `at 600 x 480 the last week of the month is reachable by scrolling the picker`() {
        val dates = PhotoDates.of(images, zone)
        rule.setContent {
            GoToPhotoDialog(
                images = images,
                ratingFilter = RatingFilter.All,
                dates = dates,
                pickerStartDay = dates.last,
                preview = { PhotoJump.preview(it, images, RatingFilter.All, zone, LocalDate.of(2026, 11, 4)) },
                onGo = { PhotoJump.resolve(it, images, RatingFilter.All, zone, LocalDate.of(2026, 11, 4)) },
                onJumpToDate = { day ->
                    jumped += day
                    PhotoJump.resolveDate(day, images, RatingFilter.All, zone)
                },
                onDismiss = { dismissed++ },
            )
        }
        rule.onNodeWithTag("go_to_photo_calendar").performClick()
        rule.onNodeWithTag("go_to_photo_picker").assertIsDisplayed()

        val cell = rule.onAllNodesDay("October 28, 2026")
        cell.performScrollTo()
        cell.assertIsDisplayed()
        // The dialog stays inside the 480 dp window, and the Cancel button is still on it.
        val dialog = rule.onNodeWithTag("go_to_photo_dialog").getUnclippedBoundsInRoot()
        assertTrue("dialog ${dialog.height} tall in a 480 dp window", dialog.height <= 480.dp)
        rule.onNodeWithTag("go_to_photo_cancel").assertIsDisplayed()

        cell.performClick()
        rule.waitForIdle()
        assertEquals(listOf(LocalDate.of(2026, 10, 28)), jumped)
        assertEquals(1, dismissed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesDay(spoken: String) = onAllNodes(
        hasAnyAncestor(hasTestTag("go_to_photo_picker")) and
            hasText(spoken, substring = true) and hasClickAction(),
        useUnmergedTree = true,
    ).onFirst()
}
