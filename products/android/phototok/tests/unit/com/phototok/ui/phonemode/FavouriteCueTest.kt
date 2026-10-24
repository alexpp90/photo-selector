package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.domain.FavouritesSnapshot
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * REQ-TOK-FEATURE.17: a favourite that has not reached the system flag must not look synced
 * (outlined star, "Only in Jackpix"), and the long-press menu favourites and un-favourites.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class FavouriteCueTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun stateOf(tag: String): String? =
        rule.onAllNodes(hasTestTag(tag)).onFirst().fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun syncedAndLocalBadgesDifferAndSayWhere() {
        rule.setContent {
            PhotoTokTheme {
                androidx.compose.foundation.layout.Row {
                    FavouriteBadge(synced = true)
                    FavouriteBadge(synced = false)
                }
            }
        }
        assertEquals(res.getString(R.string.cd_favourite_in_system), stateOf("viewer.favourite_badge.synced"))
        assertEquals(res.getString(R.string.cd_favourite_only_here), stateOf("viewer.favourite_badge.local"))
    }

    private val photo = ImageItem(
        uri = "content://photos/a.jpg",
        fileName = "a.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 100,
        imageHeight = 50,
    )

    private fun viewer(favourites: FavouritesSnapshot, onToggle: () -> Unit) {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    favourites = favourites,
                    onToggleFavourite = onToggle,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun longPressMenuFavouritesAndUnfavouritesTheCurrentPhoto() {
        var toggles = 0
        viewer(FavouritesSnapshot(), onToggle = { toggles++ })
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("menu_toggle_favourite")).fetchSemanticsNodes().isNotEmpty() }
        val item = rule.onAllNodes(hasTestTag("menu_toggle_favourite")).onFirst()
        val height = item.fetchSemanticsNode().size.height / rule.density.density
        assertTrue("menu item is $height dp tall", height >= 48f - 0.5f)
        assertTrue(
            rule.onAllNodes(hasText(res.getString(R.string.action_favourite)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        item.performClick()
        assertEquals(1, toggles)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.17")
    fun aFavouriteOffersRemoveFavourite() {
        viewer(FavouritesSnapshot(favourites = setOf(photo.uri)), onToggle = {})
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { longClick() }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(res.getString(R.string.action_unfavourite)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
