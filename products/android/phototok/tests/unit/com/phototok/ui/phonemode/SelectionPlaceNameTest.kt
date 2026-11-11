package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.source.SelectionListing
import io.mockk.coEvery
import com.phototok.domain.SwipeRightVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One word for one place (usability panel 2026-10-24: the guide said "Selection", the view
 * "Favourites 2"): the bar's Selection button speaks the same name as its controls-guide callout
 * and the Selection viewer's first list — "Favourites" when swipe right marks a favourite, the
 * Selection folder's name when it copies or moves (REQ-TOK-FEATURE.06, .17, REQ-TOK-GESTURE.07).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h851dp-port-xxhdpi")
class SelectionPlaceNameTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)
    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun SemanticsNode.words(): List<String> =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) +
            children.flatMap { it.words() }

    private fun wordsOf(tag: String): List<String> =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.words() }

    private fun assertOneName(verb: SwipeRightVerb, expected: String) {
        screen.sourceSettings = screen.sourceSettings.copy(swipeRightVerb = verb)
        screen.launch()
        screen.openFolder(PhoneScreenHarness.PHOTOS)

        assertTrue("bar button speaks '$expected': ${wordsOf("viewer_selection_button")}", expected in wordsOf("viewer_selection_button"))

        rule.runOnUiThread { screen.viewModel.showControlsGuide() }
        rule.waitForIdle()
        assertEquals("guide callout", listOf(expected), wordsOf("guide_callout_SELECTION"))
        rule.runOnUiThread { screen.viewModel.hideControlsGuide() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)

        // One photo in the list the verb fills, none in the other: the viewer shows that list alone.
        if (verb == SwipeRightVerb.FAVOURITE) {
            rule.runOnUiThread { screen.viewModel.toggleCurrentFavourite() }
        } else {
            coEvery { screen.imageRepository.listSelectionImages(any(), any()) } returns
                SelectionListing.Available(listOf(PhoneScreenHarness.PHOTOS[1]), "Selection")
        }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("viewer_selection_button"), useUnmergedTree = true).onFirst().performClick()
        rule.waitUntil(5_000) { screen.selectionViewModel.uiState.value.isOpen }
        rule.waitForIdle()
        val title = wordsOf("selection_title")
        assertTrue("the viewer opens on '$expected': $title", title.any { it.startsWith(expected) })
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.06", "REQ-TOK-FEATURE.17", "REQ-TOK-GESTURE.07")
    fun favouriteVerb_isFavouritesEverywhere() =
        assertOneName(SwipeRightVerb.FAVOURITE, res.getString(R.string.chip_favourites))

    @Test
    @Config(qualifiers = "de-w393dp-h851dp-port-xxhdpi")
    @Requirement("REQ-TOK-FEATURE.06", "REQ-TOK-FEATURE.17", "REQ-TOK-GESTURE.07")
    fun favouriteVerb_german() {
        assertEquals("Favoriten", res.getString(R.string.chip_favourites))
        assertOneName(SwipeRightVerb.FAVOURITE, res.getString(R.string.chip_favourites))
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.06", "REQ-TOK-FEATURE.17", "REQ-TOK-GESTURE.07")
    fun copyVerb_isTheSelectionFolderEverywhere() =
        assertOneName(SwipeRightVerb.COPY, "Selection")
}
