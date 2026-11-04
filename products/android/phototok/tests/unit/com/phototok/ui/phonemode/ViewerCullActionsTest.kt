package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.data.model.ImageItem
import com.phototok.domain.PhotoFolders
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.ui.theme.PhotoTokTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The cull without a drag (review 2026-11-03, JP-UX-1): every configured swipe is a TalkBack
 * custom action, and the long-press menu offers Copy and Move into the Selection folder. No
 * visible control is added (REQ-TOK-UX.02). Also: the drag detector, installed once per page,
 * calls the newest callbacks (JP-Q-4).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ViewerCullActionsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val res get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val photo = ImageItem(
        uri = "content://photos/a.jpg",
        fileName = "a.jpg",
        fileSize = 1,
        lastModified = 1,
        mimeType = "image/jpeg",
        imageWidth = 100,
        imageHeight = 50,
    )

    private fun customActions(): List<CustomAccessibilityAction> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).onFirst()
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()

    private fun invoke(label: String) {
        val action = customActions().first { it.label == label }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun eachConfiguredSwipeIsACustomActionThatRunsIt() {
        var collected = 0
        var deleted = 0
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = { collected++ },
                    onRequestDelete = { deleted++ },
                    leftSwipeAction = SwipeAction.DELETE,
                    swipeRightVerb = SwipeRightVerb.COPY,
                    collectionFolderName = "Keepers",
                    onToggleFavourite = {},
                )
            }
        }
        rule.waitForIdle()
        val copyLabel = res.getString(R.string.cd_swipe_copy, "Keepers")
        val deleteLabel = res.getString(R.string.cd_swipe_delete)
        val labels = customActions().map { it.label }
        assertTrue("actions: $labels", copyLabel in labels)
        assertTrue("actions: $labels", deleteLabel in labels)

        invoke(copyLabel)
        assertEquals(1, collected)
        invoke(deleteLabel)
        assertEquals(1, deleted)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun aLeftSwipeThatMovesIsNamedByItsFolderAndAFavouriteSwipeIsNotOfferedTwice() {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    leftSwipeAction = SwipeAction.MOVE,
                    leftSwipeFolderName = "",
                    swipeRightVerb = SwipeRightVerb.FAVOURITE,
                    onToggleFavourite = {},
                )
            }
        }
        rule.waitForIdle()
        val labels = customActions().map { it.label }
        assertTrue("actions: $labels", res.getString(R.string.cd_swipe_move, PhotoFolders.LEFT_SWIPE) in labels)
        // The Favourite action marks the photo; a second "Mark as favourite" would say the same.
        assertFalse("actions: $labels", res.getString(R.string.cd_swipe_favourite) in labels)
        assertTrue("actions: $labels", res.getString(R.string.action_favourite) in labels)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun aReadOnlyViewerOffersNoCullAction() {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    swipeRightVerb = SwipeRightVerb.COPY,
                    readOnly = true,
                )
            }
        }
        rule.waitForIdle()
        val labels = customActions().map { it.label }
        assertFalse("actions: $labels", res.getString(R.string.cd_swipe_delete) in labels)
        assertFalse("actions: $labels", res.getString(R.string.cd_swipe_copy, PhotoFolders.SELECTION) in labels)
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun longPressMenuCopiesAndMovesIntoSelection() {
        var copies = 0
        var moves = 0
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    onCopyToSelection = { copies++ },
                    onMoveToSelection = { moves++ },
                )
            }
        }
        rule.waitForIdle()
        for ((tag, check) in listOf("menu_copy_to_selection" to { copies }, "menu_move_to_selection" to { moves })) {
            rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { longClick() }
            rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
            val item = rule.onAllNodes(hasTestTag(tag)).onFirst()
            val height = item.fetchSemanticsNode().size.height / rule.density.density
            assertTrue("$tag is $height dp tall", height >= 48f - 0.5f)
            item.performClick()
            rule.waitForIdle()
            assertEquals(tag, 1, check())
        }
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun withoutCallbacksTheMenuHasNoCopyOrMove() {
        rule.setContent {
            PhotoTokTheme {
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = {},
                    onRequestDelete = {},
                    onToggleFavourite = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("menu_toggle_favourite")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag("menu_copy_to_selection")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("menu_move_to_selection")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun aDragCallsTheNewestCallbacks() {
        var deleteTarget by mutableStateOf("first")
        val deletedBy = mutableListOf<String>()
        val collectedBy = mutableListOf<String>()
        rule.setContent {
            PhotoTokTheme {
                val target = deleteTarget
                PhoneModeViewer(
                    images = listOf(photo),
                    currentIndex = 0,
                    portraitSectionStart = -1,
                    onNavigate = {},
                    onAddToCollection = { collectedBy += target },
                    onRequestDelete = { deletedBy += target },
                    swipeRightVerb = SwipeRightVerb.COPY,
                )
            }
        }
        rule.waitForIdle()
        deleteTarget = "second"
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("viewer.photo")).onFirst().performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(listOf("second"), deletedBy)
        assertEquals(listOf("second"), collectedBy)
    }
}
