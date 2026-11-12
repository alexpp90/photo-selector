package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.domain.SwipeRightVerb
import com.phototok.viewmodel.SelectionViewerTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lit ★ chip on a photo is where people looked for their favourites (usability panel
 * 2026-10-24: three of four reached for it, none found the bar button): a tap opens the Favourites
 * list, also when swipe right copies to Selection. Its look is unchanged ([USER 2026-11-12]
 * decision #33 (2)); the target is 48 dp, and TalkBack hears what a tap does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w393dp-h851dp-port-xxhdpi")
@Requirement("REQ-TOK-FEATURE.17", "REQ-TOK-FEATURE.06")
class FavouriteChipOpensFavouritesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screen = PhoneScreenHarness(rule)
    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun theLitChipOpensFavourites_evenWhenSwipeRightCopiesToSelection() {
        screen.sourceSettings = screen.sourceSettings.copy(swipeRightVerb = SwipeRightVerb.COPY)
        screen.launch()
        screen.openFolder(PhoneScreenHarness.PHOTOS)
        rule.runOnUiThread { screen.viewModel.toggleCurrentFavourite() }
        rule.waitForIdle()

        val chip = rule.onAllNodes(hasTestTag("viewer.favourite_badge.local"), useUnmergedTree = true).onFirst()
        val node = chip.fetchSemanticsNode()
        val d = rule.density.density
        assertTrue("48 dp target: ${node.touchBoundsInRoot.height / d}", node.touchBoundsInRoot.height / d >= 47.5f)
        assertEquals(res.getString(R.string.cd_open_favourites), node.config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(res.getString(R.string.cd_favourite_only_here), node.config.getOrNull(SemanticsProperties.StateDescription))

        chip.performClick()
        rule.waitUntil(5_000) { screen.selectionViewModel.uiState.value.isOpen }
        assertEquals(SelectionViewerTab.FAVOURITES, screen.selectionViewModel.uiState.value.activeTab)
    }
}
