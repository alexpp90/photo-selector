package com.photoselectortoolbox.ui.inspect

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.RatingFilter
import com.photoselectortoolbox.ui.components.StarRatingBarDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UX-AND-1 / UX-AND-2 / UX-AD-1: every Inspect control is a 48 dp touch target, measured on the node
 * that carries its tag (the node that is clickable), in whole pixels against `48.dp.roundToPx()`.
 * Pixel comparison, because a 117 px slot (exactly 48 dp at 2.4375x) reads back as 47.999985 dp.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1280dp-h800dp-xhdpi")
class InspectTouchTargetTest {

    @get:Rule
    val rule = createComposeRule()

    private val photo = ImageItem(
        uri = "content://test/folder/a.jpg",
        fileName = "a.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
    )

    private fun host(rating: Int = 3) {
        rule.setContent {
            InspectScreenContent(
                currentImage = photo.copy(rating = rating),
                images = listOf(photo.copy(rating = rating)),
                currentIndex = 0,
                windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp)),
                onNavigateToSelector = {},
                onSelectImage = {},
                onPrevious = {},
                onNext = {},
                onSetRating = { _, _ -> },
            )
        }
    }

    private fun assertTarget48(tag: String) {
        val node: SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = false)
        val size = node.fetchSemanticsNode().size
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("$tag is ${size.width}x${size.height} px, needs $min", size.width >= min && size.height >= min)
    }

    @Test
    fun `toolbar controls are 48 dp targets`() {
        host()
        listOf(
            "back_to_cull_button", "toggle_thirds", "toggle_golden_ratio", "toggle_spiral",
            "toggle_spiral_flip", "toggle_diagonals", "toggle_horizon",
        ).forEach(::assertTarget48)
    }

    @Test
    fun `learn tabs are 48 dp targets`() {
        host()
        LearnTab.entries.forEach { assertTarget48(it.tag) }
    }

    @Test
    fun `the explore chips and options are 48 dp targets`() {
        host()
        assertTarget48("guess_first_toggle")
        rule.onNodeWithTag("guess_first_toggle").performClick()
        assertTarget48("guess_option_0")
    }

    @Test
    fun `star slots are 48 dp and abut without a gap`() {
        host(rating = 3)
        val tags = listOf("star_-1", "star_1", "star_2", "star_3", "star_4", "star_5", "star_0")
        tags.forEach(::assertTarget48)
        val lefts = tags.map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        lefts.zipWithNext().forEach { (a, b) ->
            assertEquals("slots must abut", a.right, b.left, 0.5f)
        }
        assertEquals(48.dp, StarRatingBarDefaults.slotSize(24.dp))
    }

    @Test
    fun `rating filter chips are 48 dp targets`() {
        host()
        RatingFilter.PRESETS.forEach { filter ->
            assertTarget48("filter_${filter.label.replace("★", "star").replace("+", "plus").trim()}")
        }
    }

    @Test
    fun `lesson and practice controls are 48 dp targets`() {
        host()
        rule.onNodeWithTag(LearnTab.PRACTICE.tag).performClick()
        assertTarget48("set_goal_${com.photoselectortoolbox.domain.learn.LessonCatalog.ALL_LESSONS.first().id}")
    }
}
